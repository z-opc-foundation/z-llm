package com.zifang.z.llm.core.support;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 测试装配 — 造 properties / credential store / registry. */
public final class TestFixtures {

    private TestFixtures() {}

    public static LlmCredential credential(Vendor vendor, String alias, int priority) {
        LlmCredential c = new LlmCredential();
        c.setVendor(vendor);
        c.setAlias(alias);
        c.setApiKey("sk-test-" + alias);
        c.setPriority(priority);
        c.setStatus("active");
        return c;
    }

    public static GatewayProperties properties(LlmCredential... creds) {
        GatewayProperties p = new GatewayProperties();
        p.setCredentials(new ArrayList<>(Arrays.asList(creds)));
        return p;
    }

    public static LlmCredentialStore store(GatewayProperties p) {
        LlmCredentialStore s = new LlmCredentialStore(p);
        s.reload();
        return s;
    }

    public static LlmProviderRegistry registry(LlmCredentialStore s) {
        return new LlmProviderRegistry(s);
    }

    public static ApiKey key(String id, String key, Integer rpm, Long tpm) {
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setKey(key);
        k.setStatus("active");
        k.setRequestsPerMinute(rpm);
        k.setTokensPerMinute(tpm);
        return k;
    }
}
