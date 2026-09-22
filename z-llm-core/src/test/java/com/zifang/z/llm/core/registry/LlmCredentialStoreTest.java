package com.zifang.z.llm.core.registry;

import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LlmCredentialStoreTest {

    private GatewayProperties sample() {
        GatewayProperties p = new GatewayProperties();
        List<LlmCredential> creds = new ArrayList<>();

        LlmCredential c1 = new LlmCredential();
        c1.setAlias("openai-primary");
        c1.setVendor(Vendor.OPENAI);
        c1.setApiKey("sk-1");
        c1.setPriority(10);
        c1.setStatus("active");
        creds.add(c1);

        LlmCredential c2 = new LlmCredential();
        c2.setAlias("openai-backup");
        c2.setVendor(Vendor.OPENAI);
        c2.setApiKey("sk-2");
        c2.setPriority(20);
        c2.setStatus("active");
        creds.add(c2);

        LlmCredential c3 = new LlmCredential();
        c3.setAlias("openai-disabled");
        c3.setVendor(Vendor.OPENAI);
        c3.setApiKey("sk-3");
        c3.setPriority(5);
        c3.setStatus("disabled");
        creds.add(c3);

        LlmCredential c4 = new LlmCredential();
        c4.setAlias("anthropic-primary");
        c4.setVendor(Vendor.ANTHROPIC);
        c4.setApiKey("sk-ant");
        c4.setPriority(10);
        c4.setStatus("active");
        creds.add(c4);

        p.setCredentials(creds);
        return p;
    }

    @Test
    public void findByVendor_returns_active_only_sorted_by_priority() {
        LlmCredentialStore store = new LlmCredentialStore(sample());
        List<LlmCredential> openai = store.findByVendor(Vendor.OPENAI);
        // c3 disabled 排除, 剩 c1 (priority 10) + c2 (priority 20)
        assertEquals(2, openai.size());
        assertEquals("openai-primary", openai.get(0).getAlias());
        assertEquals("openai-backup", openai.get(1).getAlias());
    }

    @Test
    public void pick_returns_lowest_priority_active() {
        LlmCredentialStore store = new LlmCredentialStore(sample());
        LlmCredential picked = store.pick(Vendor.OPENAI);
        assertEquals("openai-primary", picked.getAlias());
    }

    @Test(expected = com.zifang.z.llm.api.exception.GatewayException.class)
    public void pick_throws_for_empty_vendor() {
        LlmCredentialStore store = new LlmCredentialStore(sample());
        store.pick(Vendor.GEMINI);
    }

    @Test
    public void reload_replaces_state() {
        GatewayProperties p1 = sample();
        LlmCredentialStore store = new LlmCredentialStore(p1);

        GatewayProperties p2 = new GatewayProperties();
        LlmCredential only = new LlmCredential();
        only.setAlias("gemini-only");
        only.setVendor(Vendor.GEMINI);
        only.setApiKey("gk");
        only.setPriority(10);
        only.setStatus("active");
        List<LlmCredential> list = new ArrayList<>();
        list.add(only);
        p2.setCredentials(list);

        store.reload(p2);
        assertEquals(1, store.size());
        assertEquals("gemini-only", store.pick(Vendor.GEMINI).getAlias());
        assertEquals(0, store.findByVendor(Vendor.OPENAI).size());
    }

    @Test
    public void findByAlias_returns_null_for_unknown() {
        LlmCredentialStore store = new LlmCredentialStore(sample());
        assertNotNull(store.findByAlias("openai-primary"));
        assertNull(store.findByAlias("does-not-exist"));
    }
}