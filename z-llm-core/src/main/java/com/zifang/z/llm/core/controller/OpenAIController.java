package com.zifang.z.llm.core.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.ErrorResponse;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.credential.AuthorizationExtractor;
import com.zifang.z.llm.core.mapper.OpenAIRequestMapper;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * OpenAI Chat Completions 协议兼容端点.
 *
 * <p>POST /v1/chat/completions — 与 OpenAI 官方协议完全对齐 (含流式).
 * <p>model 字段: 用 "openai/gpt-4o" / "anthropic/claude-3-5-sonnet-latest" 这种命名空间形式路由.
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

    public OpenAIController(ChatGatewayService gateway, ApiKeyService apiKeyService,
                            RateLimiter rateLimiter, ObjectMapper json) {
        this.gateway = gateway;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.mapper = new OpenAIRequestMapper(json);
    }

    @PostMapping(value = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chatCompletions(@RequestBody JsonNode body, HttpServletRequest req) {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        if (!rateLimiter.tryAcquire(key)) {
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
        UnifiedRequest unified = mapper.parse(body);
        UnifiedResponse resp = gateway.chat(unified);
        return ResponseEntity.ok().body(resp);
    }

    /**
     * 流式端点 — 直接吐 SSE.
     */
    @PostMapping(value = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            params = "stream=true",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void streamChatCompletions(@RequestBody JsonNode body,
                                      HttpServletRequest req,
                                      HttpServletResponse resp) throws IOException {
        ApiKey key = AuthorizationExtractor.requireApiKey(req, apiKeyService);
        if (!rateLimiter.tryAcquire(key)) {
            throw GatewayException.rateLimited("Rate limit exceeded");
        }
        UnifiedRequest unified = mapper.parse(body);
        unified.setStream(true);

        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = resp.getWriter();
        gateway.streamChat(unified,
                chunk -> writeSse(writer, chunk),
                err -> {
                    log.error("stream error", err);
                    try {
                        ErrorResponse er = new ErrorResponse(
                                err.getMessage() == null ? "stream error" : err.getMessage(),
                                "stream_error", "stream_failed");
                        writer.write("data: " + json.writeValueAsString(er) + "\n\n");
                        writer.flush();
                    } catch (IOException ignore) {}
                },
                () -> {
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                });
    }

    private void writeSse(PrintWriter writer, UnifiedStreamChunk chunk) {
        try {
            writer.write("data: " + json.writeValueAsString(chunk) + "\n\n");
            writer.flush();
        } catch (IOException e) {
            log.warn("SSE write failed: {}", e.getMessage());
        }
    }
}