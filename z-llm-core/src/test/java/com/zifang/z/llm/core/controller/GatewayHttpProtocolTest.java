package com.zifang.z.llm.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.EmbeddingService;
import com.zifang.z.llm.core.service.MultimodalChatRelay;
import com.zifang.z.llm.core.service.RateLimiter;
import com.zifang.z.llm.core.support.FakeLlmProvider;
import com.zifang.z.llm.core.support.FakeUpstreamHttp;
import com.zifang.z.llm.core.support.TestFixtures;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
    private LlmCredentialStore store;
    private LlmProviderRegistry registry;
    private RateLimiter rateLimiter;
    private FakeUpstreamHttp upstream;
    private UsageLedger ledger;
    private MockMvc openaiMvc;
    private MockMvc anthropicMvc;
    /** 留着引用是给 {@link #mappingPhaseErrorsEvadePackageScopedAdvice()} 换 advice 重建 MockMvc 用. */
    private OpenAIController openaiController;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.ANTHROPIC, "ak-anth", 1));
        props.getRetry().setBackoffMs(0L);
        ApiKey key = TestFixtures.key("k1", "sk-live-1", null, null);
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));

        store = TestFixtures.store(props);
        registry = TestFixtures.registry(store);
        openai = new FakeLlmProvider("openai", "gpt-");
        anthropic = new FakeLlmProvider("anthropic", "claude-");
        registry.replace(Vendor.OPENAI, "ak-a", openai);
        registry.replace(Vendor.ANTHROPIC, "ak-anth", anthropic);

        ModelRouter router = new ModelRouter(registry, props);
        rateLimiter = new RateLimiter(props);
        ProviderInvoker invoker = new ProviderInvoker(props, registry, store);
        ledger = new UsageLedger(props);
        upstream = new FakeUpstreamHttp();
        MultimodalChatRelay relay = new MultimodalChatRelay(store, invoker, ledger, rateLimiter, upstream);
        ChatGatewayService service = new ChatGatewayService(registry, store, router, invoker,
                ledger, rateLimiter, new AccessControl(props), props);
        service.setRelay(relay);
        EmbeddingService embeddingService = new EmbeddingService(props, store, router, invoker,
                ledger, rateLimiter, new AccessControl(props), upstream);

        ApiKeyService apiKeyService = new ApiKeyService(props);
        apiKeyService.afterPropertiesSet();
        ObjectMapper json = new ObjectMapper();

        openaiController = new OpenAIController(service, apiKeyService, rateLimiter, json, router,
                embeddingService);
        openaiMvc = MockMvcBuilders.standaloneSetup(openaiController)
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

    // ---- /v1/embeddings ----

    @Test
    public void embeddingsReturnsOpenAiShapedVectors() throws Exception {
        upstream.enqueue("{\"object\":\"list\",\"data\":["
                + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.5,0.25]},"
                + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.125,0.75]}],"
                + "\"model\":\"text-embedding-3-small\","
                + "\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}");

        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":[\"a\",\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].object").value("embedding"))
                .andExpect(jsonPath("$.data[0].embedding[1]").value(0.25))
                .andExpect(jsonPath("$.data[1].embedding[0]").value(0.125))
                .andExpect(jsonPath("$.model").value("openai/text-embedding-3-small"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(7))
                .andExpect(jsonPath("$.usage.total_tokens").value(7));

        assertEquals(1, upstream.callCount());
        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertTrue("应打到 OpenAI 兼容 embeddings 端点: " + call.url,
                call.url.endsWith("/v1/embeddings"));
        assertEquals("Bearer sk-test-ak-a", call.header("Authorization"));
        assertEquals("网关侧命名空间不能漏进上游 model",
                "text-embedding-3-small", call.field("model").asText());
        assertEquals(2, call.field("input").size());
        ApiKey k1 = TestFixtures.key("k1", "sk-live-1", null, null);
        assertEquals("向量用量必须进台账 (只算 prompt 侧)", 7L, ledger.totalTokens(k1));
    }

    @Test
    public void embeddingsSingleStringInputIsAccepted() throws Exception {
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[1.0]}],\"usage\":{\"prompt_tokens\":3}}");
        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        assertEquals(1, upstream.lastCall().field("input").size());
    }

    @Test
    public void embeddingsRejectsEmptyAndOversizedInputWithoutCallingUpstream() throws Exception {
        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":[]}"))
                .andExpect(status().isBadRequest());

        props.setMaxEmbeddingBatch(2);
        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":[\"a\",\"b\",\"c\"]}"))
                .andExpect(status().isBadRequest());

        assertEquals("两类入参错误都必须在出网之前挡掉", 0, upstream.callCount());
    }

    @Test
    public void embeddingsForVendorWithoutEndpointIsRejected() throws Exception {
        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"anthropic/claude-3-5-sonnet\",\"input\":\"hi\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, upstream.callCount());
    }

    @Test
    public void embeddingsFailOverToNextCredentialOnUpstream500() throws Exception {
        props.setCredentials(new ArrayList<>(Arrays.asList(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-b", 2),
                TestFixtures.credential(Vendor.ANTHROPIC, "ak-anth", 1))));
        store.reload();
        registry.replace(Vendor.OPENAI, "ak-b", new FakeLlmProvider("openai", "gpt-"));

        upstream.failNext(new LlmException("openai", 500, "upstream boom"));
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}");

        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":\"a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        assertEquals("首个凭据 5xx 后必须换下一个", 2, upstream.callCount());
        assertEquals("Bearer sk-test-ak-a", upstream.calls().get(0).header("Authorization"));
        assertEquals("Bearer sk-test-ak-b", upstream.calls().get(1).header("Authorization"));
    }

    @Test
    public void embeddingsUpstream429BecomesGateway429NotAGeneric502() throws Exception {
        upstream.failNext(new LlmException("openai", 429, "slow down"));
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}");

        openaiMvc.perform(post("/v1/embeddings")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/text-embedding-3-small\",\"input\":\"a\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // ---- 多模态转发 ----

    @Test
    public void imagePartsAreRelayedToUpstreamInsteadOfFlattened() throws Exception {
        upstream.enqueue("{\"id\":\"gen-9\",\"object\":\"chat.completion\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a cat\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}");

        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\",\"detail\":\"low\"}}"
                                + "]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("a cat"))
                .andExpect(jsonPath("$.model").value("openai/gpt-4o"))
                .andExpect(jsonPath("$.usage.total_tokens").value(13));

        assertEquals("带图请求必须走直连面", 1, upstream.callCount());
        assertEquals("kernel provider 不该被碰 (它传不了图)", 0, openai.chatCalls());
        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertTrue(call.url.endsWith("/v1/chat/completions"));
        com.fasterxml.jackson.databind.JsonNode content =
                call.field("messages").get(0).path("content");
        assertTrue("content 必须是多模态数组", content.isArray());
        assertEquals("what is this", content.get(0).path("text").asText());
        assertEquals("https://x/y.png", content.get(1).path("image_url").path("url").asText());
        assertEquals("low", content.get(1).path("image_url").path("detail").asText());
        assertFalse("转发请求不能带网关侧命名空间", call.field("model").asText().contains("openai/"));
        ApiKey k1 = TestFixtures.key("k1", "sk-live-1", null, null);
        assertEquals(13L, ledger.totalTokens(k1));
    }

    @Test
    public void imagePartsToNonCompatibleVendorFailLoudly() throws Exception {
        props.setCredentials(new ArrayList<>(Arrays.asList(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.ANTHROPIC, "ak-anth", 1),
                TestFixtures.credential(Vendor.DASHSCOPE, "ak-ds", 1))));
        store.reload();
        registry.replace(Vendor.DASHSCOPE, "ak-ds", new FakeLlmProvider("dashscope", "qwen-vl-"));

        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"dashscope/qwen-vl-plus\",\"messages\":[{\"role\":\"user\","
                                + "\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\"}}]}]}"))
                .andExpect(status().isBadRequest());

        assertEquals("绝不能退回一个没看到图的回答", 0, upstream.callCount());
        assertEquals(0, anthropic.chatCalls());
    }

    @Test
    public void relayedStreamEmitsSseFramesWithUsage() throws Exception {
        upstream.stream(
                "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":"
                        + "[{\"index\":0,\"delta\":{\"content\":\"a \"}}]}",
                "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":"
                        + "[{\"index\":0,\"delta\":{\"content\":\"cat\"},\"finish_reason\":\"stop\"}]}",
                "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}");

        MvcResult res = openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"describe\"},"
                                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\"}}]}]}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpServletResponse resp = res.getResponse();
        assertTrue("流式必须是 event-stream: " + resp.getContentType(),
                resp.getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        String body = resp.getContentAsString();
        assertEquals("收尾标记恰好一次", 1, countOccurrences(body, "data: [DONE]"));
        assertEquals("3 帧内容 + 1 个 [DONE]", 4, countOccurrences(body, "data:"));
        assertTrue("上游裸名要补回命名空间: " + body, body.contains("openai/gpt-4o"));
        assertTrue("流式末片 usage 必须透传: " + body, body.contains("\"total_tokens\":13"));
        assertTrue("出站体必须带 stream", upstream.lastCall().field("stream").asBoolean());
        assertEquals(0, openai.chatCalls());
    }

    // ---- Anthropic 原生多模态转发 ----

    @Test
    public void imagePartsForAnthropicAreRelayedAsNativeBlocks() throws Exception {
        upstream.enqueue("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-3-5-sonnet\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"a cat\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":2}}");

        openaiMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"anthropic/claude-3-5-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAA=\"}}"
                                + "]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("a cat"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.model").value("anthropic/claude-3-5-sonnet"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(11))
                .andExpect(jsonPath("$.usage.total_tokens").value(13));

        assertEquals(1, upstream.callCount());
        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertTrue("Anthropic 的 base 不带版本段, 端点必须自己拼 /v1/messages: " + call.url,
                call.url.endsWith("/v1/messages"));
        assertEquals("Messages API 只认 x-api-key", "sk-test-ak-anth", call.header("x-api-key"));
        assertNull("发 Bearer 会被上游当成缺 key", call.header("Authorization"));
        assertEquals("max_tokens 是 Messages API 的必填项", 8192, call.field("max_tokens").asInt());

        com.fasterxml.jackson.databind.JsonNode msg = call.field("messages").get(0);
        assertEquals("user", msg.path("role").asText());
        com.fasterxml.jackson.databind.JsonNode content = msg.path("content");
        assertTrue("带图必须用 block 数组", content.isArray());
        assertEquals("image", content.get(1).path("type").asText());
        com.fasterxml.jackson.databind.JsonNode source = content.get(1).path("source");
        assertEquals("base64", source.path("type").asText());
        assertEquals("image/png", source.path("media_type").asText());
        assertEquals("AAA=", source.path("data").asText());
        assertEquals("kernel provider 传不了图, 不能被碰", 0, anthropic.chatCalls());
        ApiKey k1 = TestFixtures.key("k1", "sk-live-1", null, null);
        assertEquals(13L, ledger.totalTokens(k1));
    }

    @Test
    public void nativeMessagesEndpointRelaysImagesAndKeepsAnthropicShape() throws Exception {
        upstream.enqueue("{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-3-5-sonnet\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"a dog\"}],"
                + "\"stop_reason\":\"max_tokens\","
                + "\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}");

        anthropicMvc.perform(post("/v1/messages")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-3-5-sonnet\",\"system\":\"be terse\",\"max_tokens\":64,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":["
                                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                                + "\"media_type\":\"image/jpeg\",\"data\":\"//9z\"}}]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[0].text").value("a dog"))
                .andExpect(jsonPath("$.stop_reason").value("max_tokens"))
                .andExpect(jsonPath("$.usage.input_tokens").value(9))
                .andExpect(jsonPath("$.usage.output_tokens").value(4));

        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertEquals("system 必须提到顶层", "be terse", call.field("system").asText());
        assertEquals("system 不该留在 messages 里 (上游会 400)", 1, call.field("messages").size());
        assertEquals("调用方给的 max_tokens 不能被默认值盖掉", 64, call.field("max_tokens").asInt());
        com.fasterxml.jackson.databind.JsonNode source =
                call.field("messages").get(0).path("content").get(1).path("source");
        assertEquals("image/jpeg", source.path("media_type").asText());
        assertEquals("//9z", source.path("data").asText());
    }

    @Test
    public void relayedAnthropicStreamIsReframedIntoNativeEvents() throws Exception {
        upstream.stream(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_9\",\"type\":\"message\","
                        + "\"role\":\"assistant\",\"model\":\"claude-3-5-sonnet\","
                        + "\"usage\":{\"input_tokens\":11,\"output_tokens\":0}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"a \"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"cat\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":2}}",
                "{\"type\":\"message_stop\"}");

        MvcResult res = anthropicMvc.perform(post("/v1/messages")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-3-5-sonnet\",\"stream\":true,\"messages\":[{\"role\":\"user\","
                                + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"url\","
                                + "\"url\":\"https://x/y.png\"}}]}]}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertTrue("必须回 message_start: " + body, body.contains("event: message_start"));
        assertEquals("两段文本增量都要透传", 2, countOccurrences(body, "text_delta"));
        assertEquals("文本块只能开一次", 1, countOccurrences(body, "event: content_block_start"));
        assertTrue(body.contains("event: message_stop"));
        assertFalse("不该出现错误帧", body.contains("event: error"));
        assertEquals("外链 source 的 url 不能被丢", "https://x/y.png",
                upstream.lastCall().field("messages").get(0).path("content").get(0)
                        .path("source").path("url").asText());
    }

    @Test
    public void relayedAnthropicStreamErrorFrameFailsTheStream() throws Exception {
        upstream.stream(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_10\",\"model\":\"claude-3-5-sonnet\"}}",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");

        try {
            anthropicMvc.perform(post("/v1/messages")
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"model\":\"claude-3-5-sonnet\",\"stream\":true,\"messages\":[{\"role\":\"user\","
                            + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"url\","
                            + "\"url\":\"https://x/y.png\"}}]}]}")).andReturn();
            fail("error 帧必须让调用方看到失败, 不能当成正常收尾");
        } catch (Exception expected) {
            Throwable root = expected;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertTrue("实际: " + root, root instanceof GatewayException);
            assertTrue(root.getMessage().contains("Overloaded"));
        }
        assertEquals(1, upstream.callCount());
    }

    // ---- #40① 对外错误语义：body 级输入错误 / mapping 级错误 ----

    /**
     * 只钉"结构性"字符串（类名、方法签名、库内部类名），不钉对外措辞 ——
     * 否则将来改一句提示语就会误红。最后一条是 0.1.5 实际泄漏出去的那句原文。
     */
    private static final String[] INTERNAL_MARKERS = {
            "public void",
            "com.zifang.z.llm.core.controller",
            "com.fasterxml.jackson",
            "StreamUtils",
            "javax.servlet",
            "Required request body is missing"};

    /**
     * body 在进 controller 之前就读坏了（截断 / 全空 / 不是 JSON）必须回 400 + 网关信封.
     *
     * <p>0.1.5 及之前这五格全是 {@code 500 internal_error}：advice 没挂
     * {@code HttpMessageNotReadableException}，它直接落到 {@code Exception} 兑底，于是
     * (a) OpenAI 兼容客户端把"我的请求坏了"当"服务端坏了"，退避重试一个永远坏的请求；
     * (b) Spring 原文（controller 方法签名 + Jackson 内部类名）被原样吐给调用方.
     *
     * <p>每格只往表里记失败、最后一次性断言：JUnit 的 fail-fast 会把第一格之后的格子
     * 全变成"没跑到"而不是"没红"，五格就退化成一格.
     */
    @Test
    public void malformedOrMissingBodyIs400AndDoesNotLeakInternals() throws Exception {
        String[][] cells = {
                {"openai-truncated", "openai", "/v1/chat/completions",
                        "{\"model\":\"openai/gpt-4o\",\"messages\":["},
                {"openai-empty", "openai", "/v1/chat/completions", ""},
                {"openai-not-json", "openai", "/v1/chat/completions", "hello world"},
                {"embeddings-not-json", "openai", "/v1/embeddings", "{oops"},
                {"anthropic-empty", "anthropic", "/v1/messages", ""}};
        List<String> bad = new ArrayList<>();
        for (String[] c : cells) {
            MockMvc mvc = "openai".equals(c[1]) ? openaiMvc : anthropicMvc;
            MvcResult r = mvc.perform(post(c[2])
                    .header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(c[3])).andReturn();
            String body = r.getResponse().getContentAsString();
            if (r.getResponse().getStatus() != 400) {
                bad.add(c[0] + " 状态码=" + r.getResponse().getStatus() + "（要 400）body=" + body);
            }
            if (!body.contains("\"type\":\"gateway_error\"")
                    || !body.contains("\"code\":\"invalid_request\"")) {
                bad.add(c[0] + " 信封不是 gateway_error/invalid_request: " + body);
            }
            for (String leak : INTERNAL_MARKERS) {
                if (body.contains(leak)) {
                    bad.add(c[0] + " 对外 body 泄漏内部细节 \"" + leak + "\": " + body);
                }
            }
        }
        assertEquals("body 读坏的五格都要回 400 且不吐内部细节 -> " + bad, 0, bad.size());
        assertEquals("坏 body 不该打到上游", 0, openai.chatCalls() + anthropic.chatCalls());
    }

    private static final String MAPPING_MARKER = "MAPPING-PHASE-REACHED";

    /**
     * mapping 阶段的 415 / 405 到不了"限定了包名"的 advice —— 结构性，不是漏改.
     *
     * <p>四份 advice 的 handler 集合逐字相同（都继承 {@link MappingPhaseHandlers}），
     * 唯一差别是类级限定，所以 A/B 之间的差值就是 #35 那次"收窄包名"花掉的覆盖：
     * <ul>
     *   <li>A 裸 advice ⇒ <b>接得到</b>（阳性腿：它不成立则下面三条 assertFalse 全是空判）
     *   <li>B 限定成本库 controller 所在的那个包 ⇒ 接不到
     *   <li>C 限定成 {@code com.zifang}（宿主 z-opc 那份 advice 的写法）⇒ 也接不到
     *   <li>D 出厂 advice（本来就没挂 mapping handler）⇒ 接不到
     * </ul>
     * 机制：异常由 {@code HandlerMapping} 抛出，此时 handler 还没定下来 ⇒ 带限定的 advice
     * 一律不参与，连"包名正好等于 controller 的包"都没用（B 腿）. C 腿顺带证伪一个常见想当然：
     * 合并进程里宿主那份 {@code basePackages="com.zifang"} 的 advice 同样接不到 405，
     * 接得到的只有<b>裸</b> advice（z-config-web 那份的形状）。
     *
     * <p>这条同时也说明了为什么 0.1.6 没有顺手"收 415"：给本库 advice 加
     * {@code HttpMediaTypeNotSupportedException} 的 handler 是够不着的死代码（D 腿量出来的）.
     */
    @Test
    public void mappingPhaseErrorsEvadePackageScopedAdvice() throws Exception {
        assertTrue("A 腿：裸 advice 必须接得到 415，否则 B/C/D 三条断言都是空判",
                bodyWithAdvice(new BareMappingAdvice(), true).contains(MAPPING_MARKER));
        assertTrue("A 腿：同上，405",
                bodyWithAdvice(new BareMappingAdvice(), false).contains(MAPPING_MARKER));
        for (Object advice : new Object[]{
                new SamePackageScopedMappingAdvice(), new HostStyleScopedMappingAdvice(),
                new GlobalExceptionHandler()}) {
            String cls = advice.getClass().getSimpleName();
            assertFalse(cls + " 腿：限定了包名的 advice 不该接得到 415（接得到说明收窄失效了）",
                    bodyWithAdvice(advice, true).contains(MAPPING_MARKER));
            assertFalse(cls + " 腿：同上，405",
                    bodyWithAdvice(advice, false).contains(MAPPING_MARKER));
        }

        MvcResult r404 = openaiMvc.perform(get("/v1/nope").header("Authorization", AUTH)).andReturn();
        assertEquals("未知路径状态码", 404, r404.getResponse().getStatus());
        assertNull("未知路径在这一层根本不抛异常（所以谈不上谁接得住它）",
                r404.getResolvedException());
        assertEquals("未知路径没有错误信封", "", r404.getResponse().getContentAsString());
    }

    /** @param wrongContentType true 走 415（Content-Type 不对），false 走 405（方法不对）. */
    private String bodyWithAdvice(Object advice, boolean wrongContentType) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(openaiController)
                .setControllerAdvice(advice).build();
        return (wrongContentType
                ? mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"model\":\"openai/gpt-4o\",\"messages\":"
                                + "[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                : mvc.perform(get("/v1/chat/completions").header("Authorization", AUTH)))
                .andReturn().getResponse().getContentAsString();
    }

    /** 唯一真源：handler 集合。三个子类只改类级 {@code @ControllerAdvice} 的限定范围. */
    static class MappingPhaseHandlers {
        @ExceptionHandler({HttpMediaTypeNotSupportedException.class,
                HttpRequestMethodNotSupportedException.class})
        public ResponseEntity<String> handle(Exception ex) {
            return ResponseEntity.status(400).body(MAPPING_MARKER);
        }
    }

    @ControllerAdvice
    static class BareMappingAdvice extends MappingPhaseHandlers {
    }

    @ControllerAdvice(basePackages = "com.zifang.z.llm.core.controller")
    static class SamePackageScopedMappingAdvice extends MappingPhaseHandlers {
    }

    @ControllerAdvice(basePackages = "com.zifang")
    static class HostStyleScopedMappingAdvice extends MappingPhaseHandlers {
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
