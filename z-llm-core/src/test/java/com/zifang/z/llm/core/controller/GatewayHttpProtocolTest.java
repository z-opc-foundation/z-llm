package com.zifang.z.llm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import com.zifang.z.llm.core.support.FakeLlmProvider;
import com.zifang.z.llm.core.support.TestFixtures;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 协议面测试 — 走真实 Spring MVC 分派 + servlet 响应, 覆盖双协议端点.
 *
 * <p>此前网关的 615 行 HTTP 路径 0 覆盖, 而最严重的那个缺陷 (stream 只在 URL query 上生效,
 * 真实 OpenAI/Anthropic SDK 只把它放进 body) 恰好在单元层看不见.
 */
public class GatewayHttpProtocolTest {

    private static final String AUTH = "Bearer sk-live-1";

    private GatewayProperties props;
    private FakeLlmProvider openai;
    private FakeLlmProvider anthropic;
    private RateLimiter rateLimiter;
    private MockMvc openaiMvc;
    private MockMvc anthropicMvc;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.ANTHROPIC, "ak-anth", 1));
        props.getRetry().setBackoffMs(0L);
        ApiKey key = TestFixtures.key("k1", "sk-live-1", null, null);
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));

        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        openai = new FakeLlmProvider("openai", "gpt-");
        anthropic = new FakeLlmProvider("anthropic", "claude-");
        registry.replace(Vendor.OPENAI, "ak-a", openai);
        registry.replace(Vendor.ANTHROPIC, "ak-anth", anthropic);

        ModelRouter router = new ModelRouter(registry, props);
        rateLimiter = new RateLimiter(props);
        ChatGatewayService service = new ChatGatewayService(registry, store, router,
                new ProviderInvoker(props, registry, store),
                new UsageLedger(props), rateLimiter, new AccessControl(props), props);

        ApiKeyService apiKeyService = new ApiKeyService(props);
        apiKeyService.afterPropertiesSet();
        ObjectMapper json = new ObjectMapper();

        openaiMvc = MockMvcBuilders.standaloneSetup(
                        new OpenAIController(service, apiKeyService, rateLimiter, json, router))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        anthropicMvc = MockMvcBuilders.standaloneSetup(
                        new AnthropicController(service, apiKeyService, rateLimiter, json))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---- OpenAI 协议 ----

    @Test
    public void nonStreamReturnsChatCompletion() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hello"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(11))
                .andExpect(jsonPath("$.usage.completion_tokens").value(7))
                .andExpect(jsonPath("$.usage.total_tokens").value(18))
                // 对外回显命名空间 id, 而不是上游裸 id
                .andExpect(jsonPath("$.model").value("openai/gpt-4o"));
    }

    /** 官方 SDK 发的就是裸 model 名, 不带 vendor 前缀. */
    @Test
    public void bareModelNameIsRoutedToVendor() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("openai/gpt-4o"));
        assertEquals(1, openai.chatCalls());
    }

    /**
     * 核心协议兼容点: stream 在 body 里就必须走 SSE.
     *
     * <p>此前分派条件是 {@code params="stream=true"} (URL query), 对任何真实客户端都不成立.
     */
    @Test
    public void streamFlagInBodyProducesSse() throws Exception {
        MvcResult result = openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue("body stream=true 必须走 SSE, 实际 content-type="
                        + result.getResponse().getContentType(),
                MediaType.valueOf(result.getResponse().getContentType()).isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        assertTrue(body, body.contains("data: {"));
        assertTrue(body, body.contains("\"object\":\"chat.completion.chunk\""));
        assertEquals("[DONE] 只能出现一次", 1, countOccurrences(body, "data: [DONE]"));
        assertTrue("上游已给 finish_reason, 不应再补一个空内容片",
                body.contains("\"finish_reason\":\"stop\""));
    }

    /** 既有调用方的 ?stream=true 写法要保持可用. */
    @Test
    public void legacyStreamQueryParamStillProducesSse() throws Exception {
        MvcResult result = openaiMvc.perform(post("/v1/chat/completions?stream=true")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body, body.contains("data: [DONE]"));
    }

    @Test
    public void missingAndInvalidCredentialsAre401() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
        assertEquals("鉴权失败不应打到上游", 0, openai.chatCalls());
    }

    @Test
    public void emptyMessagesRejectedAs400() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void unknownModelRejectedAs404() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"nonexistent-9\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isNotFound());
        assertEquals(0, openai.chatCalls());
    }

    /** 429 必须带 Retry-After, 否则客户端只会立刻重试. */
    @Test
    public void rateLimitedResponsesCarryRetryAfter() throws Exception {
        props.getApiKeys().get(0).setRequestsPerMinute(1);
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limited"));
        MockHttpServletResponse second = openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andReturn().getResponse();
        assertEquals(429, second.getStatus());
        assertTrue("Retry-After 必须是正秒数",
                Integer.parseInt(second.getHeader("Retry-After")) >= 1);
    }

    @Test
    public void modelsListingServesVendorNamespace() throws Exception {
        openaiMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value(org.hamcrest.Matchers.startsWith("openai/")))
                .andExpect(jsonPath("$.data[0].object").value("model"))
                .andExpect(jsonPath("$.data[0].capabilities[0]").exists());
    }

    @Test
    public void singleModelLookupByCanonicalAndBareId() throws Exception {
        openaiMvc.perform(get("/v1/models/openai/gpt-"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("openai/gpt-"));
        openaiMvc.perform(get("/v1/models/gpt-"))
                .andExpect(status().isOk());
        openaiMvc.perform(get("/v1/models/openai/nope"))
                .andExpect(status().isNotFound());
    }

    // ---- Anthropic 协议 ----

    @Test
    public void anthropicNonStreamReturnsContentBlocks() throws Exception {
        anthropic.replyText("你好");
        anthropicMvc.perform(post("/v1/messages")
                        .header("x-api-key", "sk-live-1")
                        .header("anthropic-version", "2023-06-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-3-5-sonnet-latest\",\"max_tokens\":64,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[0].text").value("你好"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"))
                .andExpect(jsonPath("$.usage.input_tokens").value(11))
                .andExpect(jsonPath("$.usage.output_tokens").value(7));
        assertEquals(1, anthropic.chatCalls());
    }

    /** Anthropic 增量帧序: start → delta* → stop → message_delta → message_stop, 且 start/stop 各一次. */
    @Test
    public void anthropicStreamFrameOrder() throws Exception {
        anthropic.streamTexts("a", "b", "c");
        MvcResult result = anthropicMvc.perform(post("/v1/messages")
                        .header("x-api-key", "sk-live-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-3-5-sonnet-latest\",\"max_tokens\":64,\"stream\":true,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue("content-type 应为 SSE, 实际 " + result.getResponse().getContentType(),
                MediaType.valueOf(result.getResponse().getContentType()).isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        assertEquals(body, 1, countOccurrences(body, "event: message_start"));
        assertEquals("每轮增量重发 start/stop 会让客户端看到无数个空文本块",
                1, countOccurrences(body, "event: content_block_start"));
        assertEquals(1, countOccurrences(body, "event: content_block_stop"));
        assertEquals(3, countOccurrences(body, "event: content_block_delta"));
        assertEquals(1, countOccurrences(body, "event: message_delta"));
        assertEquals(1, countOccurrences(body, "event: message_stop"));
        assertTrue(body, body.indexOf("event: message_start") < body.indexOf("event: content_block_start"));
        assertTrue(body, body.indexOf("event: content_block_stop") < body.indexOf("event: message_stop"));
        assertTrue("stop_reason 要随 message_delta 给出",
                body.contains("\"stop_reason\":\"end_turn\""));
        assertTrue("增量文本要原样落在 text_delta 里",
                body.contains("\"text\":\"a\"") && body.contains("\"text\":\"c\""));
    }

    /** Claude Code / Agent SDK 会先调 count_tokens 做上下文预算, 端点缺失会直接报错. */
    @Test
    public void countTokensEndpointExists() throws Exception {
        anthropicMvc.perform(post("/v1/messages/count_tokens")
                        .header("x-api-key", "sk-live-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-3-5-sonnet-latest\",\"system\":\"be brief\","
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"count these characters please\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.input_tokens").value(org.hamcrest.Matchers.greaterThan(0)))
                // 网关没有各 vendor 的 tokenizer, 估算值必须自己标明
                .andExpect(jsonPath("$.estimate").value(true));
    }

    /**
     * 显式 vendor 前缀一律按该 vendor 下发, 即使模型不在清单里 —— 上游刚发布新模型时清单还没同步,
     * 网关不该成为拦路者; 只有 vendor 本身不认识 (裸名且无 provider 支持) 才 404.
     */
    @Test
    public void explicitVendorPrefixIsServedEvenIfModelUnlisted() throws Exception {
        anthropicMvc.perform(post("/v1/messages")
                        .header("x-api-key", "sk-live-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4o\",\"max_tokens\":16,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isOk());
        assertEquals("请求被前缀归到 anthropic 凭据", 1, anthropic.chatCalls());
        assertEquals("openai provider 不应被触碰", 0, openai.chatCalls());
    }

    @Test
    public void unconfiguredVendorIs404() throws Exception {
        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gemini/gemini-2.5-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isNotFound());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
