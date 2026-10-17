package com.zifang.z.llm.core.credential;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ApiKeyServiceTest {

    private GatewayProperties withKey(String key, String status) {
        GatewayProperties p = new GatewayProperties();
        ApiKey k = new ApiKey();
        k.setId("k1");
        k.setKey(key);
        k.setStatus(status);
        List<ApiKey> keys = new ArrayList<>();
        keys.add(k);
        p.setApiKeys(keys);
        return p;
    }

    @Test
    public void authenticate_active_key() {
        ApiKeyService svc = new ApiKeyService(withKey("sk-test", "active"));
        svc.afterPropertiesSet();
        assertNotNull(svc.authenticate("sk-test"));
    }

    @Test
    public void authenticate_unknown_returns_null() {
        ApiKeyService svc = new ApiKeyService(withKey("sk-test", "active"));
        svc.afterPropertiesSet();
        assertNull(svc.authenticate("sk-other"));
        assertNull(svc.authenticate(null));
    }

    @Test
    public void authenticate_strips_bearer_prefix() {
        ApiKeyService svc = new ApiKeyService(withKey("sk-test", "active"));
        svc.afterPropertiesSet();
        assertNotNull(svc.authenticate("Bearer sk-test"));
    }

    @Test
    public void disabled_key_not_loaded() {
        ApiKeyService svc = new ApiKeyService(withKey("sk-test", "disabled"));
        svc.afterPropertiesSet();
        assertNull(svc.authenticate("sk-test"));
    }

    /**
     * 本期唯一真实的 key 形状就是配置文件里那一条 —— 没有 id。
     * 既有 fixture 全部手动 setId("k1")，于是这个形状整个套件都没覆盖，
     * 而 RateLimiter 拿它的 null id 去 computeIfAbsent 直接 NPE：
     * /v1/chat/completions 一律 500，UsageLedger 那边则是静默丢掉用量。
     */
    @Test
    public void property_key_without_id_is_given_a_usable_one() {
        GatewayProperties p = new GatewayProperties();
        ApiKey k = new ApiKey();
        k.setKey("sk-no-id");
        k.setName("gw");
        k.setStatus("active");
        p.setApiKeys(java.util.Collections.singletonList(k));

        ApiKeyService svc = new ApiKeyService(p);
        svc.afterPropertiesSet();
        ApiKey loaded = svc.authenticate("sk-no-id");

        assertNotNull(loaded.getId());
        assertTrue(loaded.getId().startsWith("property:gw:"));
        // id 会出现在限流日志和 admin 输出里, 所以不能带出 key 明文
        assertFalse(loaded.getId().contains("sk-no-id"));
        // 原 NPE 现场: 限流这条链现在必须能跑通
        assertTrue(new com.zifang.z.llm.core.service.RateLimiter(p).tryAcquire(loaded));
    }

    /** 显式配了 id 的不能被摘要顶掉 —— admin 按 id 查限流明细靠的是它。 */
    @Test
    public void explicit_id_is_preserved() {
        ApiKeyService svc = new ApiKeyService(withKey("sk-test", "active"));
        svc.afterPropertiesSet();
        assertEquals("k1", svc.authenticate("sk-test").getId());
    }
}