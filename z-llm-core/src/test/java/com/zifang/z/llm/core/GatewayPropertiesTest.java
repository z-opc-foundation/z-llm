package com.zifang.z.llm.core;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class GatewayPropertiesTest {

    @Test
    public void defaults_are_safe() {
        GatewayProperties p = new GatewayProperties();
        assertEquals("", p.getBasePath());
        assertNotNull(p.getCredentials());
        assertNotNull(p.getApiKeys());
        assertTrue(p.getCredentials().isEmpty());
        assertFalse(p.isExposeAdmin());
        assertEquals(Integer.valueOf(32768), p.getMaxTokensLimit());
        assertTrue(p.isRateLimitEnabled());
    }

    @Test
    public void credentials_and_api_keys_round_trip() {
        GatewayProperties p = new GatewayProperties();

        LlmCredential cred = new LlmCredential();
        cred.setAlias("openai-primary");
        cred.setVendor(Vendor.OPENAI);
        cred.setApiKey("sk-test");
        cred.setBaseUrl("https://api.openai.com/v1");
        cred.setPriority(10);
        cred.setStatus("active");
        List<LlmCredential> creds = new ArrayList<>();
        creds.add(cred);
        p.setCredentials(creds);

        ApiKey k = new ApiKey();
        k.setId("k1");
        k.setName("dev-key");
        k.setKey("sk-gw-test");
        k.setMaskedKey("sk-***test");
        k.setStatus("active");
        k.setRequestsPerMinute(60);
        k.setTokensPerMinute(100000L);
        List<ApiKey> keys = new ArrayList<>();
        keys.add(k);
        p.setApiKeys(keys);

        assertEquals(1, p.getCredentials().size());
        assertEquals(Vendor.OPENAI, p.getCredentials().get(0).getVendor());
        assertEquals("sk-test", p.getCredentials().get(0).getApiKey());
        assertEquals(1, p.getApiKeys().size());
        assertEquals("sk-gw-test", p.getApiKeys().get(0).getKey());
        assertEquals(Integer.valueOf(60), p.getApiKeys().get(0).getRequestsPerMinute());
    }
}