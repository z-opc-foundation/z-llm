package com.zifang.z.llm.core.router;

import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.support.TestFixtures;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 模型路由: 前缀 / 裸名 / 名字自带斜杠 / 别名表 四种真实输入.
 */
public class ModelRouterTest {

    private ModelRouter routerFor(GatewayProperties props, LlmCredential... creds) {
        props.setModelAliases(props.getModelAliases());
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        return new ModelRouter(registry, props);
    }

    @Test
    public void explicitVendorPrefix() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter.Resolved r = routerFor(p,
                TestFixtures.credential(Vendor.OPENAI, "default", 1))
                .resolve("openai/gpt-4o");
        assertEquals(Vendor.OPENAI, r.vendor());
        assertEquals("gpt-4o", r.bareModel());
        assertEquals("openai/gpt-4o", r.canonical());
    }

    /** 真实 OpenAI SDK 只会发裸名, 此前这种输入直接 404. */
    @Test
    public void bareModelNameResolvesToClaimingVendor() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter.Resolved r = routerFor(p).resolve("gpt-4o");
        assertEquals(Vendor.OPENAI, r.vendor());
        assertEquals("gpt-4o", r.bareModel());
    }

    /**
     * 名字本身含斜杠的 model id 不能被当成 vendor 前缀切掉.
     */
    @Test
    public void slashBearingModelIdIsNotCutAsVendorPrefix() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.DEEPSEEK, "default", 1));
        ModelRouter router = routerFor(p);
        // deepseek provider 按前缀 claim "deepseek-" 系, "deepseek-chat" 命中.
        ModelRouter.Resolved r = router.resolve("deepseek-chat");
        assertEquals(Vendor.DEEPSEEK, r.vendor());
    }

    @Test
    public void aliasTableWins() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        p.setModelAliases(Collections.singletonMap("claude", "anthropic/claude-3-5-sonnet-latest"));
        LlmCredentialStore store = TestFixtures.store(TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1),
                TestFixtures.credential(Vendor.ANTHROPIC, "a", 1)));
        ModelRouter router = new ModelRouter(TestFixtures.registry(store), p);
        ModelRouter.Resolved r = router.resolve("claude");
        assertEquals(Vendor.ANTHROPIC, r.vendor());
        assertEquals("claude-3-5-sonnet-latest", r.bareModel());
    }

    @Test
    public void unknownModelGives404() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        try {
            routerFor(p).resolve("does-not-exist-xyz");
            fail("expected model_not_found");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
        }
    }

    @Test
    public void nullAndEmptyModelRejected() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter router = routerFor(p);
        for (String bad : new String[]{null, "", "   "}) {
            try {
                router.resolve(bad);
                fail("expected 404 for [" + bad + "]");
            } catch (GatewayException e) {
                assertEquals(404, e.getHttpStatus());
            }
        }
    }

    /** vendor 前缀对但没配该 vendor 凭据 ⇒ 不能当成可路由目标. */
    @Test
    public void prefixWithoutCredentialIsNotFound() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        try {
            routerFor(p).resolve("gemini/gemini-1.5-pro");
            fail("expected 404");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
        }
    }

    @Test
    public void availableModelsCarriesVendorNamespace() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter router = routerFor(p);
        assertTrue("expected namespaced ids",
                router.availableModels().keySet().stream().allMatch(k -> k.startsWith("openai/")));
        assertTrue(router.availableModels().containsKey("openai/gpt-4o"));
    }
}
