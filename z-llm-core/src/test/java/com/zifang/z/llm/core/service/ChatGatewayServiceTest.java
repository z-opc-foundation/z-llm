package com.zifang.z.llm.core.service;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.support.FakeLlmProvider;
import com.zifang.z.llm.core.support.TestFixtures;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 编排层端到端: 路由 → 授权 → 参数下沉 → failover → 回转 → 记账, 全部走假 provider.
 */
public class ChatGatewayServiceTest {

    private GatewayProperties props;
    private LlmProviderRegistry registry;
    private LlmCredentialStore store;
    private UsageLedger ledger;
    private RateLimiter rateLimiter;
    private ChatGatewayService service;
    private FakeLlmProvider openai;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-b", 2));
        props.getRetry().setBackoffMs(0L);
        store = TestFixtures.store(props);
        registry = TestFixtures.registry(store);
        openai = new FakeLlmProvider("openai", "gpt-");
        registry.replace(Vendor.OPENAI, "ak-a", openai);
        registry.replace(Vendor.OPENAI, "ak-b", new FakeLlmProvider("openai", "gpt-"));
        ModelRouter router = new ModelRouter(registry, props);
        ledger = new UsageLedger(props);
        rateLimiter = new RateLimiter(props);
        service = new ChatGatewayService(registry, store, router,
                new ProviderInvoker(props, registry, store),
                ledger, rateLimiter, new AccessControl(props), props);
    }

    private UnifiedRequest req(String model, String... prompts) {
        UnifiedRequest r = new UnifiedRequest();
        r.setModel(model);
        List<UnifiedMessage> msgs = new ArrayList<>();
        for (String p : prompts) {
            UnifiedMessage m = new UnifiedMessage();
            m.setRole("user");
            m.setContent(p);
            msgs.add(m);
        }
        r.setMessages(msgs);
        return r;
    }

    private final ApiKey key = TestFixtures.key("k1", "sk-k1", null, null);

    @Test
    public void syncChatReturnsOpenAiShapedResponse() {
        ChatGatewayService.ChatOutcome out = service.chat(req("openai/gpt-4o", "hi"), key);
        assertEquals("chat.completion", out.response().getObject());
        assertEquals(1, out.response().getChoices().size());
        assertEquals("hello", out.response().getChoices().get(0).getMessage().getContent());
        // 对外回显带命名空间的 model, 而不是 vendor 内部裸 id.
        assertEquals("openai/gpt-4o", out.response().getModel());
        assertNotNull(out.response().getUsage());
        assertEquals(Integer.valueOf(11), out.response().getUsage().getPromptTokens());
    }

    /** 上游回裸 model 名时, 网关要补回命名空间, 否则客户端的路由/计费匹配不上. */
    @Test
    public void bareUpstreamModelIsNamespacedOnTheWayOut() {
        ChatGatewayService.ChatOutcome out = service.chat(req("gpt-4o", "hi"), key);
        assertTrue(out.response().getModel().startsWith("openai/"));
    }

    /** 采样参数必须真的到达 provider. */
    @Test
    public void samplingParamsReachTheProvider() {
        UnifiedRequest r = req("openai/gpt-4o", "hi");
        r.setStop(Arrays.asList("END"));
        r.setToolChoice("required");
        r.setPresencePenalty(0.5);
        service.chat(r, key);
        ChatCompletionsRequest sent = openai.requests().get(0);
        assertEquals("gpt-4o", sent.getModel());
        assertEquals(Arrays.asList("END"), sent.getProviderParams().get("stop"));
        assertEquals("required", sent.getProviderParams().get("tool_choice"));
        assertEquals(0.5, sent.getProviderParams().get("presence_penalty"));
    }

    @Test
    public void toolsReachTheProvider() {
        UnifiedRequest r = req("openai/gpt-4o", "hi");
        com.zifang.z.llm.api.dto.ToolSpec ts = new com.zifang.z.llm.api.dto.ToolSpec();
        ts.setType("function");
        com.zifang.z.llm.api.dto.ToolSpec.FunctionSpec fn = new com.zifang.z.llm.api.dto.ToolSpec.FunctionSpec();
        fn.setName("get_weather");
        fn.setParameters(Collections.singletonMap("type", "object"));
        ts.setFunction(fn);
        r.setTools(Collections.singletonList(ts));
        service.chat(r, key);
        assertEquals(1, openai.requests().get(0).getTools().size());
        assertEquals("get_weather", openai.requests().get(0).getTools().get(0).getName());
    }

    @Test
    public void emptyMessagesRejectedAs400() {
        try {
            service.chat(req("openai/gpt-4o"), key);
            fail("expected 400");
        } catch (GatewayException e) {
            assertEquals(400, e.getHttpStatus());
        }
    }

    /** 越权模型必须在打到上游之前被拦下 — provider 调用数为 0 才算真拦住. */
    @Test
    public void deniedModelNeverReachesUpstream() {
        ApiKey restricted = TestFixtures.key("k2", "sk-k2", null, null);
        restricted.setAllowedModels(Collections.singletonList("gpt-3.5-turbo"));
        try {
            service.chat(req("openai/gpt-4o", "hi"), restricted);
            fail("expected 403");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
        assertEquals(0, openai.chatCalls());
    }

    @Test
    public void usageIsBookedToLedgerAndRateLimiter() {
        service.chat(req("openai/gpt-4o", "hi"), key);
        assertEquals(18L, ledger.totalTokens(key));
        // 一次调用烧 18 token, 额度只有 10 ⇒ 下一个请求必须被限住
        ApiKey tight = TestFixtures.key("k3", "sk-k3", null, 10L);
        service.chat(req("openai/gpt-4o", "hi"), tight);
        assertTrue("tpm must be enforced after real usage", rateLimiter.tryAcquire(tight) == false);
    }

    /** z-team / z-agent / z-lc 是直接注入本服务调用的, 这条无 ApiKey 的老签名不能断. */
    @Test
    public void inProcessSingleArgApiStillWorks() {
        com.zifang.z.llm.api.dto.UnifiedResponse r = service.chat(req("openai/gpt-4o", "hi"));
        assertEquals("hello", r.getChoices().get(0).getMessage().getContent());
        assertEquals("openai/gpt-4o", r.getModel());
    }

    @Test
    public void failoverAcrossCredentialsWhenFirstIsRateLimited() {
        openai.failOn(0, new LlmException("openai", 429, "slow down"));
        ChatGatewayService.ChatOutcome out = service.chat(req("openai/gpt-4o", "hi"), key);
        assertEquals("hello", out.response().getChoices().get(0).getMessage().getContent());
        assertEquals("ak-b", out.credential());
        assertEquals(1, openai.chatCalls());
    }

    // ---- 流式 ----

    @Test
    public void streamEmitsAllChunksThenCompletesOnce() {
        openai.streamTexts("a", "b", "c");
        List<UnifiedStreamChunk> chunks = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        service.streamChat(req("openai/gpt-4o", "hi"), key,
                chunks::add, e -> errors.incrementAndGet(), completions::incrementAndGet);
        assertEquals(0, errors.get());
        // 3 个内容片 + 1 个带 finish_reason 的收尾片: 收尾片也要透传给客户端.
        assertEquals(4, chunks.size());
        assertEquals("a", chunks.get(0).getChoices().get(0).getDelta().getContent());
        assertEquals("chat.completion.chunk", chunks.get(0).getObject());
        assertEquals("stop", chunks.get(3).getChoices().get(0).getFinishReason());
        assertEquals("exactly one completion", 1, completions.get());
    }

    /** 上游不给 finish_reason 时也必须收尾, 否则客户端会一直等 [DONE] 到读超时. */
    @Test
    public void streamCompletesEvenWhenUpstreamNeverSetsFinishReason() {
        openai.streamTexts("x").streamFinishReason(null);
        AtomicInteger completions = new AtomicInteger();
        service.streamChat(req("openai/gpt-4o", "hi"), key,
                c -> { }, e -> fail("unexpected error " + e), completions::incrementAndGet);
        assertEquals(1, completions.get());
    }

    @Test
    public void streamCarriesUsageIntoTheLedger() {
        openai.streamTexts("hey").usage(new TokenUsage(30L, 12L, 42L)).streamSetsUsage(true);
        ApiKey k = TestFixtures.key("k4", "sk-k4", null, null);
        ChatGatewayService.StreamOutcome out = service.streamChat(req("openai/gpt-4o", "hi"), k,
                c -> { }, e -> fail("unexpected " + e), () -> { });
        assertEquals(42L, ledger.totalTokens(k));
        assertEquals(30L, out.usage().promptTokens());
    }

    @Test
    public void streamWithNoUsageRecordsZeroWithoutCrashing() {
        openai.streamTexts("hey").streamSetsUsage(false);
        ApiKey k = TestFixtures.key("k5", "sk-k5", null, null);
        service.streamChat(req("openai/gpt-4o", "hi"), k, c -> { }, e -> fail(""), () -> { });
        assertEquals(0L, ledger.totalTokens(k));
    }

    /** 首个 chunk 之前就失败可以换凭据; 已吐过内容则不能换. */
    @Test
    public void streamFailsOverBeforeFirstChunk() {
        openai.failOn(0, new LlmException("openai", 429, "slow down"));
        AtomicInteger completions = new AtomicInteger();
        ChatGatewayService.StreamOutcome out = service.streamChat(req("openai/gpt-4o", "hi"), key,
                c -> { }, e -> fail("should have failed over"), completions::incrementAndGet);
        assertEquals("ak-b", out.credential());
        assertEquals(1, completions.get());
    }

    @Test
    public void streamExhaustingPoolSurfacesErrorOnce() {
        openai.failOn(0, new LlmException("openai", 500, "boom"));
        props.getRetry().setMaxAttempts(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try {
            service.streamChat(req("openai/gpt-4o", "hi"), key,
                    c -> { }, e -> errors.incrementAndGet(), () -> completions.incrementAndGet());
            fail("expected upstream failure");
        } catch (GatewayException expected) {
            assertEquals(502, expected.getHttpStatus());
        }
        assertEquals(1, errors.get());
        assertEquals("error path must not also fire completion", 0, completions.get());
    }

    @Test
    public void streamRequestsAreMarkedStreamOnTheWire() {
        service.streamChat(req("openai/gpt-4o", "hi"), key, c -> { }, e -> { }, () -> { });
        assertTrue(openai.requests().get(0).isStream());
    }

    /** 多模态: kernel 的 Msg 没有图片位, 只能摊平文本, 但绝不能假装图被看见了. */
    @Test
    public void multimodalPartsAreFlattenedToText() {
        UnifiedRequest r = new UnifiedRequest();
        r.setModel("openai/gpt-4o");
        UnifiedMessage m = new UnifiedMessage();
        m.setRole("user");
        ContentPart text = new ContentPart();
        text.setType("text");
        text.setText("what is in this image?");
        ContentPart img = new ContentPart();
        img.setType("image_url");
        ContentPart.ImageUrl iu = new ContentPart.ImageUrl();
        iu.setUrl("https://example.com/a.png");
        img.setImageUrl(iu);
        m.setContents(Arrays.asList(text, img));
        r.setMessages(Collections.singletonList(m));

        service.chat(r, key);
        assertEquals("what is in this image?",
                openai.requests().get(0).getMessages().get(0).getContent());
    }

    @Test
    public void streamUsageInjectionIsOptIn() {
        props.setInjectStreamUsage(true);
        service.streamChat(req("openai/gpt-4o", "hi"), key, c -> { }, e -> { }, () -> { });
        Object so = openai.requests().get(0).getProviderParams().get("stream_options");
        assertNotNull("include_usage must be injected when opted in", so);
    }
}
