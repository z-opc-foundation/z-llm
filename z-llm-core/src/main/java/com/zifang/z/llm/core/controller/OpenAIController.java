package com.zifang.z.llm.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.ErrorResponse;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.credential.AuthorizationExtractor;
import com.zifang.z.llm.core.mapper.OpenAIRequestMapper;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI Chat Completions 协议兼容端点.
 *
 * <p>POST /v1/chat/completions — 与 OpenAI 官方协议对齐 (含流式).
 * <p>GET  /v1/models, /v1/models/{"{id}"} — OpenAI 模型清单.
 *
 * <p>model 字段既接受 "openai/gpt-4o" 命名空间形式, 也接受官方 SDK 默认发送的裸名 "gpt-4o".
 */
@RestController
@RequestMapping("/v1")
public class OpenAIController {

    private static final Logger log = LoggerFactory.getLogger(OpenAIController.class);

    private final ChatGatewayService gateway;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final OpenAIRequestMapper mapper;
    private final ModelRouter modelRouter;

    public OpenAIController(ChatGatewayService gateway, ApiKeyService apiKeyService,
                            RateLimiter rateLimiter, ObjectMapper json, ModelRouter modelRouter) {
        this.gateway = gateway;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.mapper = new OpenAIRequestMapper(json);
        this.modelRouter = modelRouter;
    }

    /**
     * chat/completions 统一入口.
     *
     * <p>流式与否由 <b>请求体</b> 里的 {@code stream} 决定, 而不是 URL 参数 ——
     * OpenAI 官方 SDK 只会把 stream 放进 JSON body, 此前按 {@code params="stream=true"}
     * 分派的写法对真实客户端永远不生效, 只会退回一个缓冲好的 JSON.
     * 为兼容既有调用方, {@code ?stream=true} 仍然有效.
     */
    @PostMapping(value = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void chatCompletions(@RequestBody JsonNode body,
                               HttpServletRequest req,
                               HttpServletResponse resp) throws IOException {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        acquireOrThrow(key, resp);
        try {
            UnifiedRequest unified = mapper.parse(body);
            boolean stream = Boolean.TRUE.equals(unified.getStream())
                    || isStreamQueryParam(req);
            if (stream) {
                streamCompletion(unified, key, resp);
            } else {
                jsonCompletion(unified, key, resp);
            }
        } finally {
            rateLimiter.release(key);
        }
    }

    private void jsonCompletion(UnifiedRequest unified, ApiKey key, HttpServletResponse resp)
            throws IOException {
        ChatGatewayService.ChatOutcome outcome = gateway.chat(unified, key);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(json.writeValueAsString(outcome.response()));
        resp.getWriter().flush();
    }

    private void streamCompletion(UnifiedRequest unified, ApiKey key, HttpServletResponse resp)
            throws IOException {
        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        resp.setHeader(HttpHeaders.CONNECTION, "keep-alive");
        resp.setHeader("X-Accel-Buffering", "no");

        final PrintWriter writer = resp.getWriter();
        final AtomicBoolean done = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (done.compareAndSet(false, true)) {
                writer.write("data: [DONE]\n\n");
                writer.flush();
            }
        };
        gateway.streamChat(unified, key,
                chunk -> writeSse(writer, chunk),
                err -> {
                    if (done.get()) {
                        return;
                    }
                    log.error("stream error", err);
                    try {
                        ErrorResponse er = new ErrorResponse(
                                messageOf(err), "upstream_error", "stream_failed");
                        writer.write("data: " + json.writeValueAsString(er) + "\n\n");
                        writer.flush();
                    } catch (IOException ignore) {
                    } finally {
                        finish.run();
                    }
                },
                finish);
        finish.run();
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> models() {
        ObjectNode root = json.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        for (Map.Entry<String, ModelRouter.ModelCard> e : modelRouter.availableModels().entrySet()) {
            Model m = e.getValue().model();
            ObjectNode node = data.addObject();
            node.put("id", e.getKey());
            node.put("object", "model");
            node.put("created", System.currentTimeMillis() / 1000L);
            node.put("owned_by", m.getProvider() == null ? e.getValue().vendor().code() : m.getProvider());
            if (m.getDisplayName() != null) {
                node.put("display_name", m.getDisplayName());
            }
            ArrayNode caps = node.putArray("capabilities");
            for (Model.Capability c : m.getCapabilities()) {
                caps.add(c.name().toLowerCase());
            }
            node.put("context_window", m.getContextWindow());
            node.put("max_output_tokens", m.getMaxOutputTokens());
        }
        return ResponseEntity.ok(root);
    }

    /**
     * 单个模型查询.
     *
     * <p>用 {@code /models/**} 而不是 {@code {id}} 路径变量: OpenAI 的模型 id 本身就带斜杠
     * (网关侧还有 "openai/gpt-4o" 这种命名空间写法), 单个路径变量匹配不到多段.
     */
    @GetMapping(value = "/models/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> model(HttpServletRequest req) {
        String id = pathAfterModels(req);
        Map<String, ModelRouter.ModelCard> cards = modelRouter.availableModels();
        ModelRouter.ModelCard card = cards.get(id);
        if (card == null) {
            for (Map.Entry<String, ModelRouter.ModelCard> e : cards.entrySet()) {
                if (e.getKey().equalsIgnoreCase(id) || bare(e.getKey()).equalsIgnoreCase(id)) {
                    card = e.getValue();
                    break;
                }
            }
        }
        if (card == null) {
            throw GatewayException.modelNotFound(id);
        }
        ObjectNode node = json.createObjectNode();
        Model m = card.model();
        node.put("id", id);
        node.put("object", "model");
        node.put("owned_by", m.getProvider() == null ? card.vendor().code() : m.getProvider());
        node.put("display_name", m.getDisplayName());
        node.put("context_window", m.getContextWindow());
        node.put("max_output_tokens", m.getMaxOutputTokens());
        ArrayNode caps = node.putArray("capabilities");
        for (Model.Capability c : m.getCapabilities()) {
            caps.add(c.name().toLowerCase());
        }
        return ResponseEntity.ok(node);
    }

    private static String bare(String canonical) {
        int slash = canonical.indexOf('/');
        return slash < 0 ? canonical : canonical.substring(slash + 1);
    }

    private static String pathAfterModels(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String marker = "/v1/models/";
        int idx = uri.indexOf(marker);
        String tail = idx < 0 ? "" : uri.substring(idx + marker.length());
        return UriUtils.decode(tail, StandardCharsets.UTF_8);
    }

    private void acquireOrThrow(ApiKey key, HttpServletResponse resp) {
        if (!rateLimiter.tryAcquire(key)) {
            long waitMs = rateLimiter.retryAfterMs(key);
            resp.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, waitMs / 1000L)));
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
    }

    private static boolean isStreamQueryParam(HttpServletRequest req) {
        String p = req.getParameter("stream");
        return p != null && ("true".equalsIgnoreCase(p) || "1".equals(p));
    }

    private static String messageOf(Throwable err) {
        return err.getMessage() == null ? "stream error" : err.getMessage();
    }

    private void writeSse(PrintWriter writer, com.zifang.z.llm.api.dto.UnifiedStreamChunk chunk) {
        try {
            writer.write("data: " + json.writeValueAsString(chunk) + "\n\n");
            writer.flush();
        } catch (IOException e) {
            log.warn("SSE write failed: {}", e.getMessage());
        }
    }
}
