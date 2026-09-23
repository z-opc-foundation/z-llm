package com.zifang.z.llm.core.registry;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LlmProviderRegistryTest {

    private LlmCredentialStore storeWithOne(Vendor v, String alias, String key) {
        GatewayProperties p = new GatewayProperties();
        LlmCredential c = new LlmCredential();
        c.setAlias(alias);
        c.setVendor(v);
        c.setApiKey(key);
        c.setStatus("active");
        c.setPriority(10);
        List<LlmCredential> list = new ArrayList<>();
        list.add(c);
        p.setCredentials(list);
        LlmCredentialStore s = new LlmCredentialStore(p);
        s.afterPropertiesSet();
        return s;
    }

    @Test
    public void registry_creates_one_provider_per_credential() {
        GatewayProperties p = new GatewayProperties();
        List<LlmCredential> creds = new ArrayList<>();

        LlmCredential c1 = new LlmCredential();
        c1.setAlias("openai-1");
        c1.setVendor(Vendor.OPENAI);
        c1.setApiKey("sk-1");
        c1.setPriority(10);
        c1.setStatus("active");
        creds.add(c1);

        LlmCredential c2 = new LlmCredential();
        c2.setAlias("anthropic-1");
        c2.setVendor(Vendor.ANTHROPIC);
        c2.setApiKey("ant-1");
        c2.setPriority(10);
        c2.setStatus("active");
        creds.add(c2);

        p.setCredentials(creds);
        LlmCredentialStore store = new LlmCredentialStore(p);

        store.afterPropertiesSet();
        LlmProviderRegistry reg = new LlmProviderRegistry(store);

        assertEquals("openai", reg.get(Vendor.OPENAI, "openai-1").name());
        assertEquals("anthropic", reg.get(Vendor.ANTHROPIC, "anthropic-1").name());
        assertNull(reg.get(Vendor.OPENAI, "openai-2"));
        assertEquals(2, reg.keys().size());
    }

    @Test
    public void pick_returns_provider_with_lowest_priority() {
        LlmCredentialStore store = storeWithOne(Vendor.OPENAI, "openai-primary", "sk-test");
        LlmProviderRegistry reg = new LlmProviderRegistry(store);

        LlmProvider p = reg.pick(Vendor.OPENAI);
        assertNotNull(p);
        assertEquals("openai", p.name());
    }

    @Test
    public void listByVendor_returns_per_credential_providers() {
        LlmCredentialStore store = storeWithOne(Vendor.GEMINI, "gemini-1", "gk");
        LlmProviderRegistry reg = new LlmProviderRegistry(store);

        List<LlmProvider> ps = reg.listByVendor(Vendor.GEMINI);
        assertEquals(1, ps.size());
        assertEquals("gemini", ps.get(0).name());
    }
}