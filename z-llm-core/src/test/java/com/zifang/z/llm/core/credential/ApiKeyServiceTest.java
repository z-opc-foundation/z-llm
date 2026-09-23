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
}