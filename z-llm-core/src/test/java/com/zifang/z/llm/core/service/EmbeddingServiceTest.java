package com.zifang.z.llm.core.service;

import com.zifang.z.llm.api.dto.EmbeddingsRequest;
import com.zifang.z.llm.api.dto.EmbeddingsResponse;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.support.FakeLlmProvider;
import com.zifang.z.llm.core.support.FakeUpstreamHttp;
import com.zifang.z.llm.core.support.TestFixtures;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * embeddings 的各家形状 — 端点路径、请求体、响应解析都按 vendor 分形, 错一条就是静默拿错向量.
 */
public class EmbeddingServiceTest {

    private GatewayProperties props;
    private FakeUpstreamHttp upstream;
    private EmbeddingService service;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.DASHSCOPE, "ak-ds", 1),
                TestFixtures.credential(Vendor.GEMINI, "ak-gm", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-oa", 1));
        props.getRetry().setBackoffMs(0L);
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        registry.replace(Vendor.DASHSCOPE, "ak-ds", new FakeLlmProvider("dashscope", "text-embedding-"));
        registry.replace(Vendor.GEMINI, "ak-gm", new FakeLlmProvider("gemini", "gemini-"));
        registry.replace(Vendor.OPENAI, "ak-oa", new FakeLlmProvider("openai", "text-embedding-"));
        ModelRouter router = new ModelRouter(registry, props);
        ProviderInvoker invoker = new ProviderInvoker(props, registry, store);
        RateLimiter rateLimiter = new RateLimiter(props);
        UsageLedger ledger = new UsageLedger(props);
        upstream = new FakeUpstreamHttp();
        service = new EmbeddingService(props, store, router, invoker, ledger,
                rateLimiter, new AccessControl(props), upstream);
    }

    private EmbeddingsRequest req(String model, String... inputs) {
        return new EmbeddingsRequest(model, new ArrayList<>(Arrays.asList(inputs)));
    }

    @Test
    public void dashScopeUsesItsNativeTextsShape() {
        upstream.enqueue("{\"output\":{\"embeddings\":["
                + "{\"text_index\":1,\"embedding\":[0.2]},{\"text_index\":0,\"embedding\":[0.1]}]},"
                + "\"usage\":{\"total_tokens\":5}}");

        EmbeddingsResponse out = service.embed(req("dashscope/text-embedding-v3", "a", "b"),
                TestFixtures.key("k1", "sk-k1", null, null));

        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertTrue("DashScope 原生端点: " + call.url,
                call.url.endsWith("/services/embeddings/text-embedding/text-embedding"));
        assertEquals("a", call.field("input").path("texts").get(0).asText());
        assertEquals(2, call.field("input").path("texts").size());
        // 上游乱序回 text_index 时必须按索引排回去, 否则向量会配错文本.
        assertEquals(0.1, out.getData().get(0).getEmbedding().get(0), 1e-9);
        assertEquals(0.2, out.getData().get(1).getEmbedding().get(0), 1e-9);
        assertEquals(Integer.valueOf(5), out.getUsage().getPromptTokens());
    }

    @Test
    public void geminiUsesBatchEmbedContentsShape() {
        upstream.enqueue("{\"embedding\":[{\"values\":[0.5,0.25]},{\"values\":[0.125,0.75]}]}");

        EmbeddingsResponse out = service.embed(req("gemini/gemini-embedding-001", "a", "b"),
                TestFixtures.key("k1", "sk-k1", null, null));

        FakeUpstreamHttp.Call call = upstream.lastCall();
        assertTrue("模型名在路径里: " + call.url,
                call.url.endsWith("/v1beta/models/gemini-embedding-001:batchEmbedContents"));
        assertEquals(2, call.field("requests").size());
        assertEquals("models/gemini-embedding-001",
                call.field("requests").get(0).path("model").asText());
        assertEquals("a", call.field("requests").get(0).path("content").path("parts").get(0)
                .path("text").asText());
        assertEquals(0.75, out.getData().get(1).getEmbedding().get(1), 1e-9);
    }

    @Test
    public void missingVectorForAnInputIsAFailureNotAShortList() {
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        try {
            service.embed(req("openai/text-embedding-3-small", "a", "b"),
                    TestFixtures.key("k1", "sk-k1", null, null));
            fail("少给一条向量必须报错, 否则下游会把向量按错位的文本存进索引");
        } catch (GatewayException expected) {
            assertEquals(502, expected.getHttpStatus());
            assertTrue(expected.getMessage().contains("1 vectors for 2"));
        }
    }

    /** 只有一个可服务 vendor 时, 裸向量名不必加前缀 —— 这是单向量渠道部署的常态. */
    @Test
    public void soleEmbeddingVendorOwnsBareModelName() {
        EmbeddingService only = serviceFor(TestFixtures.credential(Vendor.OPENAI, "ak-oa", 1));
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        only.embed(req("bge-m3", "a"), TestFixtures.key("k1", "sk-k1", null, null));
        assertEquals(1, upstream.callCount());
        assertTrue(upstream.lastCall().url.endsWith("/v1/embeddings"));
    }

    private EmbeddingService serviceFor(com.zifang.z.llm.api.dto.LlmCredential... creds) {
        GatewayProperties p = TestFixtures.properties(creds);
        p.getRetry().setBackoffMs(0L);
        LlmCredentialStore store = TestFixtures.store(p);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        for (com.zifang.z.llm.api.dto.LlmCredential c : creds) {
            registry.replace(c.getVendor(), c.getAlias(), new FakeLlmProvider(c.getVendor().code(), "gpt-"));
        }
        return new EmbeddingService(p, store, new ModelRouter(registry, p),
                new ProviderInvoker(p, registry, store), new UsageLedger(p), new RateLimiter(p),
                new AccessControl(p), upstream);
    }

    @Test
    public void bareEmbeddingModelNeedsAHomeAndSaysSo() {
        try {
            service.embed(req("bge-m3", "a"), TestFixtures.key("k1", "sk-k1", null, null));
            fail("三个可服务 vendor 都在, 裸向量名无法判定归属");
        } catch (GatewayException expected) {
            assertEquals(404, expected.getHttpStatus());
            assertTrue("报错要给可操作提示: " + expected.getMessage(),
                    expected.getMessage().contains("embedding-vendor"));
        }

        props.setEmbeddingVendor("openai");
        upstream.enqueue("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}");
        EmbeddingsResponse out = service.embed(req("bge-m3", "a"),
                TestFixtures.key("k1", "sk-k1", null, null));
        assertEquals("bge-m3", upstream.lastCall().field("model").asText());
        assertEquals("openai/bge-m3", out.getModel());
    }
}
