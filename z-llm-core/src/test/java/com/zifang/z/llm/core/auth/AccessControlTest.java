package com.zifang.z.llm.core.auth;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.support.TestFixtures;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * ApiKey 白名单强制 — allowedModels / allowedVendors 此前从未被任何代码读取,
 * 等于"限模型"的 key 可以打遍全部 vendor.
 */
public class AccessControlTest {

    private final ModelRouter.Resolved openAiGpt4o =
            new ModelRouter.Resolved(Vendor.OPENAI, "gpt-4o", "openai/gpt-4o");
    private final ModelRouter.Resolved anthropicSonnet =
            new ModelRouter.Resolved(Vendor.ANTHROPIC, "claude-3-5-sonnet-latest", "anthropic/x");

    private AccessControl control(boolean enforce, int maxTokens) {
        GatewayProperties p = TestFixtures.properties();
        p.setEnforceKeyRestrictions(enforce);
        p.setMaxTokensLimit(maxTokens);
        return new AccessControl(p);
    }

    @Test
    public void unrestrictedKeyPasses() {
        control(true, 32768).authorize(TestFixtures.key("k", "sk", null, null), openAiGpt4o, 100);
    }

    @Test
    public void allowedModelsExactMatch() {
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        k.setAllowedModels(Collections.singletonList("openai/gpt-4o"));
        control(true, 32768).authorize(k, openAiGpt4o, 100);
    }

    @Test
    public void allowedModelsWildcard() {
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        k.setAllowedModels(Collections.singletonList("openai/gpt-4*"));
        control(true, 32768).authorize(k, openAiGpt4o, 100);
        try {
            control(true, 32768).authorize(k,
                    new ModelRouter.Resolved(Vendor.OPENAI, "o3-mini", "openai/o3-mini"), 100);
            fail("wildcard must not match o3-mini");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    @Test
    public void disallowedModelIs403() {
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        k.setAllowedModels(Arrays.asList("gpt-3.5-turbo"));
        try {
            control(true, 32768).authorize(k, openAiGpt4o, 100);
            fail("expected 403");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    @Test
    public void disallowedVendorIs403() {
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        k.setAllowedVendors(Collections.singletonList(Vendor.DEEPSEEK));
        try {
            control(true, 32768).authorize(k, anthropicSonnet, 100);
            fail("expected 403");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    /** max_tokens 上限此前是死配置. */
    @Test
    public void maxTokensOverCapIs400() {
        GatewayProperties p = TestFixtures.properties();
        p.setMaxTokensLimit(8192);
        try {
            new AccessControl(p).authorize(TestFixtures.key("k", "sk", null, null), openAiGpt4o, 99999);
            fail("expected 400");
        } catch (GatewayException e) {
            assertEquals(400, e.getHttpStatus());
        }
        new AccessControl(p).authorize(TestFixtures.key("k", "sk", null, null), openAiGpt4o, 1024);
        new AccessControl(p).authorize(TestFixtures.key("k", "sk", null, null), openAiGpt4o, null);
    }

    @Test
    public void enforcementCanBeDisabledForGradualRollout() {
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        k.setAllowedModels(Collections.singletonList("something-else"));
        control(false, 32768).authorize(k, openAiGpt4o, 100);
    }

    @Test
    public void nullKeyIs401() {
        try {
            control(true, 32768).authorize(null, openAiGpt4o, 10);
            fail("expected 401");
        } catch (GatewayException e) {
            assertEquals(401, e.getHttpStatus());
        }
    }
}
