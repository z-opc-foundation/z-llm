package com.zifang.z.llm.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.admin.config.ZLlmAdminAutoConfiguration;
import com.zifang.z.llm.admin.controller.AdminController;
import com.zifang.z.llm.admin.service.AdminQueryService;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.RateLimiter;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 控制面的两条边界: 默认不注册, 且注册了也看不到凭证原文.
 */
public class AdminExposureTest {

    private static final String RAW_KEY = "sk-gateway-abcdef123456";

    private ApplicationContextRunner runner() {
        GatewayProperties props = new GatewayProperties();
        LlmCredentialStore store = new LlmCredentialStore(props);
        return new ApplicationContextRunner()
                .withBean(GatewayProperties.class, () -> props)
                .withBean(LlmCredentialStore.class, () -> store)
                .withBean(LlmProviderRegistry.class, () -> new LlmProviderRegistry(store))
                .withBean(ApiKeyService.class, () -> new ApiKeyService(props))
                .withBean(RateLimiter.class, () -> new RateLimiter(props))
                .withUserConfiguration(ZLlmAdminAutoConfiguration.class);
    }

    @Test
    public void adminSurfaceIsOffUnlessExposeAdminIsTrue() {
        runner().run(ctx -> assertEquals(
                "control plane must not register by default", 0,
                ctx.getBeanNamesForType(AdminController.class).length));

        runner().withPropertyValues("z.llm.expose-admin=true").run(ctx -> assertEquals(1,
                ctx.getBeanNamesForType(AdminController.class).length));
    }

    @Test
    public void apiKeysEndpointNeverEchoesRawKey() throws Exception {
        ApiKey key = new ApiKey();
        key.setId("k-1");
        key.setName("ci-runner");
        key.setKey(RAW_KEY);
        key.setStatus("active");

        GatewayProperties props = new GatewayProperties();
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));
        ApiKeyService apiKeyService = new ApiKeyService(props);
        apiKeyService.afterPropertiesSet();

        LlmCredentialStore store = new LlmCredentialStore(props);
        LlmProviderRegistry registry = new LlmProviderRegistry(store);
        RateLimiter rateLimiter = new RateLimiter(props);
        AdminController controller = new AdminController(
                new AdminQueryService(registry, store, apiKeyService, rateLimiter),
                store, registry, apiKeyService, rateLimiter);

        List<Map<String, Object>> rows = controller.apiKeys();
        assertEquals(1, rows.size());
        assertTrue(String.valueOf(rows.get(0).get("maskedKey")),
                String.valueOf(rows.get(0).get("maskedKey")).contains("****"));
        // 序列化后的整体形状里都不该有原文 —— 只查一个字段会漏掉将来新增的透出.
        assertFalse(new ObjectMapper().writeValueAsString(rows).contains(RAW_KEY));
    }
}
