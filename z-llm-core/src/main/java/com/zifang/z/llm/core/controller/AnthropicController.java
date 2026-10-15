package com.zifang.z.llm.core.controller;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Choice;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.credential.AuthorizationExtractor;
import com.zifang.z.llm.core.mapper.AnthropicRequestMapper;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic Messages API 协议兼容端点.
 *
 * <p>POST /v1/messages              — 与 Anthropic 官方 Messages API 对齐 (含流式).
 * <p>POST /v1/messages/count_tokens — Claude Code / Agent SDK 会先调它做上下文预算,
 *                                     端点不存在会让客户端直接报错.
 *
 * <p>model 字段: 接受 "claude-3-5-sonnet-latest" 或 "anthropic/claude-3-5-sonnet-latest" 两种写法.
 */
@RestController
@RequestMapping("/v1")
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final ChatGatewayService gateway;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final AnthropicRequestMapper mapper;

    public AnthropicController(ChatGatewayService gateway, ApiKeyService apiKeyService,
                               RateLimiter rateLimiter) {
        this.gateway = gateway;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.mapper = new AnthropicRequestMapper();
    }

    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void messages(@RequestBody String body,
                         @RequestHeader(value = "anthropic-version", required = false) String apiVersion,
                         HttpServletRequest req,
                         HttpServletResponse resp) throws IOException {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        acquireOrThrow(key, resp);
        try {
            UnifiedRequest unified = normalize(mapper.parse(parseBody(body)));
            boolean stream = Boolean.TRUE.equals(unified.getStream()) || isStreamQueryParam(req);
            if (stream) {
                streamMessages(unified, apiVersion, key, resp);
            } else {
                UnifiedResponse r = gateway.chat(unified, key).response();
                resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(JsonUtil.toJson(toAnthropicResponse(r, apiVersion, 0L)));
                resp.getWriter().flush();
            }
        } finally {
            rateLimiter.release(key);
        }
    }

    /**
     * token 计数 (估算).
     *
     * <p>网关这层没有各 vendor 的 tokenizer, 因此按 "约 4 字符 = 1 token" 估算,
     * 并在响应里以 {@code estimate=true} 明示 —— 宁可给出带标记的近似值,
     * 也不要让只依赖"端点存在"的客户端拿不到任何预算信号.
     */
    @PostMapping(value = "/messages/count_tokens", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> countTokens(@RequestBody String body) {
        JsonCursor root0 = parseBody(body);
        UnifiedRequest unified = normalize(mapper.parse(root0));
        long chars = textLen(unified.getMessages());
        if (root0.has("system")) {
            chars += JsonUtil.toJson(root0.path("system").value()).length();
        }
        if (root0.has("tools")) {
            chars += JsonUtil.toJson(root0.path("tools").value()).length();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> usage = new LinkedHashMap<>();
        root.put("usage", usage);
        long inputTokens = (long) Math.ceil(chars / 4.0);
        usage.put("input_tokens", inputTokens);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        usage.put("output_tokens", 0);
        root.put("estimate", true);
        return ResponseEntity.ok(root);
    }

    // ---- 流式: 严格对齐 Anthropic 的事件帧序 ----

    private void streamMessages(UnifiedRequest unified, String apiVersion, ApiKey key,
                                HttpServletResponse resp) throws IOException {
        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");

        final PrintWriter writer = resp.getWriter();
        final String messageId = "msg_" + System.currentTimeMillis();
        // 帧序要求: message_start → content_block_start → [delta...] → content_block_stop
        //          → message_delta(stop_reason, usage) → message_stop.
        // 此前每个增量都重发一对 start/stop, 客户端会看到无数个空文本块.
        final AtomicBoolean blockOpened = new AtomicBoolean(false);
        final AtomicBoolean blockClosed = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final long[] outputTokens = new long[1];
        final String[] stopReason = new String[1];

        writeAnthropicEvent(writer, "message_start", buildMessageStart(messageId, unified.getModel(), apiVersion));
        writeAnthropicEvent(writer, "ping", one("type", "ping"));

        Runnable closeBlock = () -> {
            if (blockOpened.get() && blockClosed.compareAndSet(false, true)) {
                writeAnthropicEvent(writer, "content_block_stop", one("index", 0));
            }
        };
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                closeBlock.run();
                Map<String, Object> md = new LinkedHashMap<>();
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("stop_reason", stopReason[0] == null ? "end_turn" : mapFinish(stopReason[0]));
                // 原来放的是显式 NullNode, Map 版本用 null 值等价 (写出去仍是 "stop_sequence":null).
                delta.put("stop_sequence", null);
                md.put("delta", delta);
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("output_tokens", outputTokens[0]);
                md.put("usage", usage);
                writeAnthropicEvent(writer, "message_delta", md);
                writeAnthropicEvent(writer, "message_stop", new LinkedHashMap<String, Object>());
                writer.flush();
            }
        };

        gateway.streamChat(unified, key,
                chunk -> {
                    String text = textOf(chunk);
                    if (text != null && !text.isEmpty()) {
                        if (blockOpened.compareAndSet(false, true)) {
                            Map<String, Object> blockStart = new LinkedHashMap<>();
                            Map<String, Object> block = new LinkedHashMap<>();
                            block.put("type", "text");
                            block.put("text", "");
                            blockStart.put("index", 0);
                            blockStart.put("content_block", block);
                            writeAnthropicEvent(writer, "content_block_start", blockStart);
                        }
                        Map<String, Object> delta = new LinkedHashMap<>();
                        delta.put("type", "text_delta");
                        delta.put("text", text);
                        Map<String, Object> blockDelta = new LinkedHashMap<>();
                        blockDelta.put("index", 0);
                        blockDelta.put("delta", delta);
                        writeAnthropicEvent(writer, "content_block_delta", blockDelta);
                        outputTokens[0] += Math.max(1L, (long) Math.ceil(text.length() / 4.0));
                    }
                    String fr = finishReasonOf(chunk);
                    if (fr != null) {
                        stopReason[0] = fr;
                    }
                },
                err -> {
                    log.error("anthropic stream error", err);
                    if (!finished.get()) {
                        finished.set(true);
                        closeBlock.run();
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("type", "error");
                        error.put("message", err.getMessage() == null ? "stream error" : err.getMessage());
                        writeAnthropicEvent(writer, "error", error);
                        writer.flush();
                    }
                },
                finish);
        finish.run();
    }

    // ---- helpers ----

    private UnifiedRequest normalize(UnifiedRequest unified) {
        if (unified != null && unified.getModel() != null && !unified.getModel().contains("/")) {
            unified.setModel("anthropic/" + unified.getModel());
        }
        return unified;
    }

    private static String textOf(UnifiedStreamChunk chunk) {
        if (chunk == null || chunk.getChoices() == null) {
            return null;
        }
        for (Choice c : chunk.getChoices()) {
            if (c != null && c.getDelta() != null && c.getDelta().getContent() != null) {
                return c.getDelta().getContent();
            }
        }
        return null;
    }

    private static String finishReasonOf(UnifiedStreamChunk chunk) {
        if (chunk == null || chunk.getChoices() == null) {
            return null;
        }
        for (Choice c : chunk.getChoices()) {
            if (c != null && c.getFinishReason() != null && !c.getFinishReason().isEmpty()) {
                return c.getFinishReason();
            }
        }
        return null;
    }

    private static long textLen(List<UnifiedMessage> msgs) {
        long n = 0L;
        if (msgs == null) {
            return 0L;
        }
        for (UnifiedMessage m : msgs) {
            if (m == null) {
                continue;
            }
            if (m.getContent() != null) {
                n += m.getContent().length();
            }
            if (m.getContents() != null) {
                for (com.zifang.z.llm.api.dto.ContentPart p : m.getContents()) {
                    if (p != null && p.getText() != null) {
                        n += p.getText().length();
                    }
                }
            }
        }
        return n;
    }

    private void acquireOrThrow(ApiKey key, HttpServletResponse resp) {
        if (!rateLimiter.tryAcquire(key)) {
            long waitMs = rateLimiter.retryAfterMs(key);
            resp.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, waitMs / 1000L)));
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
    }

    /**
     * 自己按 String 收再解析 —— 不让 Spring 的 Jackson 转换器碰这条链路.
     *
     * <p>代价是"body 读坏"从 HttpMessageNotReadableException 变成 JsonUtil 抛的运行时异常,
     * 所以这里显式翻回 400 invalid_request, 否则坏请求会退化成 500 (对外语义回归).
     */
    private static JsonCursor parseBody(String body) {
        try {
            return JsonCursor.of(JsonUtil.parse(body));
        } catch (RuntimeException e) {
            throw GatewayException.invalidRequest("request body is not valid JSON");
        }
    }

    private static boolean isStreamQueryParam(HttpServletRequest req) {
        String p = req.getParameter("stream");
        return p != null && ("true".equalsIgnoreCase(p) || "1".equals(p));
    }

    private Map<String, Object> toAnthropicResponse(UnifiedResponse resp, String apiVersion, long inputTokens) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", resp.getId() == null ? "msg_" + System.currentTimeMillis() : resp.getId());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", resp.getModel());
        if (apiVersion != null) {
            root.put("anthropic_version", apiVersion);
        }

        List<Object> contentArr = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Choice c : safe(resp.getChoices())) {
            if (c.getMessage() == null || c.getMessage().getContent() == null) {
                continue;
            }
            text.append(c.getMessage().getContent());
        }
        if (text.length() > 0 || contentArr.isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text.toString());
            contentArr.add(block);
        }
        root.put("content", contentArr);

        String stop = null;
        List<Choice> choices = resp.getChoices();
        if (choices != null && !choices.isEmpty()) {
            stop = choices.get(0).getFinishReason();
        }
        root.put("stop_reason", stop == null ? "end_turn" : mapFinish(stop));
        root.put("stop_sequence", null);

        Map<String, Object> usage = new LinkedHashMap<>();
        long in = inputTokens;
        long out = 0L;
        if (resp.getUsage() != null) {
            if (resp.getUsage().getPromptTokens() != null) {
                in = resp.getUsage().getPromptTokens();
            }
            if (resp.getUsage().getCompletionTokens() != null) {
                out = resp.getUsage().getCompletionTokens();
            }
        }
        usage.put("input_tokens", in);
        usage.put("output_tokens", out);
        root.put("usage", usage);
        return root;
    }

    private static Map<String, Object> one(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static List<Choice> safe(List<Choice> in) {
        return in == null ? java.util.Collections.<Choice>emptyList() : in;
    }

    private static String mapFinish(String s) {
        if (s == null) return "end_turn";
        if ("stop".equalsIgnoreCase(s)) return "end_turn";
        if ("length".equalsIgnoreCase(s)) return "max_tokens";
        if ("tool_calls".equalsIgnoreCase(s)) return "tool_use";
        return s;
    }

    private Map<String, Object> buildMessageStart(String messageId, String model, String apiVersion) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", messageId);
        msg.put("type", "message");
        msg.put("role", "assistant");
        msg.put("model", model == null ? "" : model);
        if (apiVersion != null) {
            msg.put("anthropic_version", apiVersion);
        }
        List<Object> content = new ArrayList<>();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", "");
        content.add(block);
        msg.put("content", content);
        msg.put("stop_reason", null);
        msg.put("stop_sequence", null);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        msg.put("usage", usage);
        return msg;
    }

    private void writeAnthropicEvent(PrintWriter writer, String eventName, Map<String, Object> data) {
        try {
            writer.write("event: " + eventName + "\n");
            writer.write("data: " + JsonUtil.toJson(data) + "\n\n");
            writer.flush();
        } catch (RuntimeException e) {
            log.warn("SSE write failed: {}", e.getMessage());
        }
    }
}
