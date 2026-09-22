package com.zifang.z.llm.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Choice;
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

/**
 * Anthropic Messages API 协议兼容端点.
 *
 * <p>POST /v1/messages — 与 Anthropic 官方 Messages API 对齐.
 * <p>model 字段: 接受 "claude-3-5-sonnet-latest" 或 "anthropic/claude-3-5-sonnet-latest" 两种写法.
 */
@RestController
@RequestMapping("/v1")
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final ChatGatewayService gateway;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;
    private final AnthropicRequestMapper mapper;

    public AnthropicController(ChatGatewayService gateway, ApiKeyService apiKeyService,
                               RateLimiter rateLimiter, ObjectMapper json) {
        this.gateway = gateway;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.mapper = new AnthropicRequestMapper();
    }

    @PostMapping(value = "/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> messages(@RequestBody JsonNode body,
                                      @RequestHeader(value = "anthropic-version", required = false) String apiVersion,
                                      HttpServletRequest req) {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        if (!rateLimiter.tryAcquire(key)) {
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
        UnifiedRequest unified = mapper.parse(body);
        // 兜底: 若用户传 "claude-3-5-sonnet-latest" 不带 vendor 前缀, 自动补 anthropic/
        if (unified.getModel() != null && !unified.getModel().contains("/")) {
            unified.setModel("anthropic/" + unified.getModel());
        }
        UnifiedResponse resp = gateway.chat(unified);
        return ResponseEntity.ok().body(toAnthropicResponse(resp, apiVersion));
    }

    @PostMapping(value = "/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            params = "stream=true",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamMessages(@RequestBody JsonNode body,
                               @RequestHeader(value = "anthropic-version", required = false) String apiVersion,
                               HttpServletRequest req,
                               HttpServletResponse resp) throws IOException {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        if (!rateLimiter.tryAcquire(key)) {
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
        UnifiedRequest unified = mapper.parse(body);
        if (unified.getModel() != null && !unified.getModel().contains("/")) {
            unified.setModel("anthropic/" + unified.getModel());
        }
        unified.setStream(true);

        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = resp.getWriter();
        String messageId = "msg_" + System.currentTimeMillis();
        writeAnthropicEvent(writer, "message_start",
                buildMessageStart(messageId, unified.getModel()));
        gateway.streamChat(unified,
                chunk -> {
                    if (chunk.getChoices() != null) {
                        for (Choice c : chunk.getChoices()) {
                            if (c.getDelta() == null) continue;
                            // content_block_start
                            ObjectNode blockStart = json.createObjectNode();
                            ObjectNode block = json.createObjectNode();
                            block.put("type", "text");
                            block.put("text", "");
                            blockStart.set("content_block", block);
                            blockStart.put("index", c.getIndex());
                            writeAnthropicEvent(writer, "content_block_start", blockStart);

                            // content_block_delta
                            if (c.getDelta().getContent() != null && !c.getDelta().getContent().isEmpty()) {
                                ObjectNode delta = json.createObjectNode();
                                delta.put("type", "text_delta");
                                delta.put("text", c.getDelta().getContent());
                                ObjectNode blockDelta = json.createObjectNode();
                                blockDelta.put("index", c.getIndex());
                                blockDelta.set("delta", delta);
                                writeAnthropicEvent(writer, "content_block_delta", blockDelta);
                            }

                            // content_block_stop
                            ObjectNode stop = json.createObjectNode();
                            stop.put("index", c.getIndex());
                            writeAnthropicEvent(writer, "content_block_stop", stop);
                        }
                    }
                },
                err -> {
                    log.error("anthropic stream error", err);
                    try {
                        writeAnthropicEvent(writer, "error", json.createObjectNode().put("message",
                                err.getMessage() == null ? "stream error" : err.getMessage()));
                    } catch (Exception ignore) {}
                },
                () -> {
                    // message_delta with stop_reason
                    ObjectNode md = json.createObjectNode();
                    ObjectNode delta = json.createObjectNode();
                    delta.put("stop_reason", "end_turn");
                    md.set("delta", delta);
                    md.put("type", "message_delta");
                    writeAnthropicEvent(writer, "message_delta", md);
                    // message_stop
                    writeAnthropicEvent(writer, "message_stop", json.createObjectNode());
                    writer.flush();
                });
    }

    // ---- Anthropic 协议响应序列化 ----

    private JsonNode toAnthropicResponse(UnifiedResponse resp, String apiVersion) {
        ObjectNode root = json.createObjectNode();
        root.put("id", resp.getId() == null ? "msg_" + System.currentTimeMillis() : resp.getId());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", resp.getModel());
        if (apiVersion != null) root.put("anthropic_version", apiVersion);

        // content 数组
        com.fasterxml.jackson.databind.node.ArrayNode contentArr = json.createArrayNode();
        if (resp.getChoices() != null) {
            for (Choice c : resp.getChoices()) {
                ObjectNode block = json.createObjectNode();
                block.put("type", "text");
                block.put("text", c.getMessage() == null ? "" : c.getMessage().getContent());
                contentArr.add(block);
            }
        }
        root.set("content", contentArr);

        // stop_reason
        String stop = null;
        if (resp.getChoices() != null && !resp.getChoices().isEmpty()) {
            stop = resp.getChoices().get(0).getFinishReason();
        }
        root.put("stop_reason", stop == null ? "end_turn" : mapFinish(stop));
        root.put("stop_sequence", json.nullNode());

        // usage
        ObjectNode usage = json.createObjectNode();
        if (resp.getUsage() != null) {
            usage.put("input_tokens", resp.getUsage().getPromptTokens() == null ? 0 : resp.getUsage().getPromptTokens());
            usage.put("output_tokens", resp.getUsage().getCompletionTokens() == null ? 0 : resp.getUsage().getCompletionTokens());
        }
        root.set("usage", usage);
        return root;
    }

    private static String mapFinish(String s) {
        if (s == null) return "end_turn";
        if ("stop".equalsIgnoreCase(s)) return "end_turn";
        if ("length".equalsIgnoreCase(s)) return "max_tokens";
        if ("tool_calls".equalsIgnoreCase(s)) return "tool_use";
        return s;
    }

    private ObjectNode buildMessageStart(String messageId, String model) {
        ObjectNode msg = json.createObjectNode();
        msg.put("id", messageId);
        msg.put("type", "message");
        msg.put("role", "assistant");
        msg.put("model", model == null ? "" : model);
        ObjectNode content = json.createObjectNode();
        content.put("type", "text");
        content.put("text", "");
        msg.set("content", content);
        msg.put("stop_reason", json.nullNode());
        msg.put("stop_sequence", json.nullNode());
        return msg;
    }

    private void writeAnthropicEvent(PrintWriter writer, String eventName, JsonNode data) {
        try {
            writer.write("event: " + eventName + "\n");
            writer.write("data: " + json.writeValueAsString(data) + "\n\n");
            writer.flush();
        } catch (IOException e) {
            log.warn("SSE write failed: {}", e.getMessage());
        }
    }
}