package com.zifang.z.llm.core.service;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.junit.Test;

import static org.junit.Assert.*;

public class RateLimiterTest {

    @Test
    public void disabled_passes_all() {
        GatewayProperties p = new GatewayProperties();
        p.setRateLimitEnabled(false);
        RateLimiter rl = new RateLimiter(p);
        ApiKey k = new ApiKey();
        k.setId("k1");
        k.setRequestsPerMinute(1);
        // 即使设置了 RPM=1, 关掉限流也总能通过
        assertTrue(rl.tryAcquire(k));
        assertTrue(rl.tryAcquire(k));
        assertTrue(rl.tryAcquire(k));
    }

    @Test
    public void enabled_blocks_after_rpm() {
        GatewayProperties p = new GatewayProperties();
        p.setRateLimitEnabled(true);
        RateLimiter rl = new RateLimiter(p);
        ApiKey k = new ApiKey();
        k.setId("k1");
        k.setRequestsPerMinute(2);
        assertTrue(rl.tryAcquire(k));
        assertTrue(rl.tryAcquire(k));
        assertFalse(rl.tryAcquire(k));
    }

    @Test
    public void null_key_passes() {
        GatewayProperties p = new GatewayProperties();
        p.setRateLimitEnabled(true);
        RateLimiter rl = new RateLimiter(p);
        assertTrue(rl.tryAcquire(null));
    }

    @Test
    public void no_limit_passes() {
        GatewayProperties p = new GatewayProperties();
        p.setRateLimitEnabled(true);
        RateLimiter rl = new RateLimiter(p);
        ApiKey k = new ApiKey();
        k.setId("k1");
        // requestsPerMinute=null 不限
        for (int i = 0; i < 100; i++) {
            assertTrue(rl.tryAcquire(k));
        }
    }
}