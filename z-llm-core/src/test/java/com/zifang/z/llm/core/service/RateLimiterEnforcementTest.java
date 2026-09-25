package com.zifang.z.llm.core.service;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.support.TestFixtures;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 限流的真实拦截行为.
 *
 * <p>原实现只拦 RPM: recordTokenUsage 在全仓没有任何调用点, 所以配了 TPM 的 key
 * 无论烧多少 token 都不会被限. 这里把 TPM 与并发维度钉成可测的拦截.
 */
public class RateLimiterEnforcementTest {

    private RateLimiter limiter(boolean enabled) {
        GatewayProperties p = TestFixtures.properties();
        p.setRateLimitEnabled(enabled);
        return new RateLimiter(p);
    }

    @Test
    public void rpmBlocksAsBefore() {
        RateLimiter l = limiter(true);
        ApiKey k = TestFixtures.key("k", "sk", 2, null);
        assertTrue(l.tryAcquire(k));
        assertTrue(l.tryAcquire(k));
        assertFalse("third request over rpm=2 must be rejected", l.tryAcquire(k));
    }

    /** TPM 维度: 记账之后必须能在下一个请求上拦下来. */
    @Test
    public void tpmBlocksOnceRecordedUsageExceedsLimit() {
        RateLimiter l = limiter(true);
        ApiKey k = TestFixtures.key("k", "sk", null, 1000L);
        assertTrue(l.tryAcquire(k));
        l.recordTokenUsage(k, 1000L);
        assertFalse("tpm budget exhausted must reject", l.tryAcquire(k));
    }

    @Test
    public void partialTpmBudgetStillAllowsTraffic() {
        RateLimiter l = limiter(true);
        ApiKey k = TestFixtures.key("k", "sk", null, 1000L);
        l.tryAcquire(k);
        l.recordTokenUsage(k, 400L);
        assertTrue(l.tryAcquire(k));
        l.recordTokenUsage(k, 700L);
        assertFalse(l.tryAcquire(k));
    }

    @Test
    public void retryAfterShrinksWithinWindow() {
        RateLimiter l = limiter(true);
        ApiKey k = TestFixtures.key("k", "sk", 1, null);
        l.tryAcquire(k);
        long waitMs = l.retryAfterMs(k);
        assertTrue("expected 0..60000, got " + waitMs, waitMs > 0 && waitMs <= 60_000L);
    }

    @Test
    public void concurrencyLimitCapsInFlightAndFreesOnRelease() {
        GatewayProperties p = TestFixtures.properties();
        p.setMaxConcurrentRequests(1);
        RateLimiter l = new RateLimiter(p);
        ApiKey k = TestFixtures.key("k", "sk", null, null);
        assertTrue(l.tryAcquire(k));
        assertFalse("second in-flight request must be rejected", l.tryAcquire(k));
        l.release(k);
        assertTrue("slot must be reusable after release", l.tryAcquire(k));
    }

    @Test
    public void disabledLimiterNeverBlocks() {
        RateLimiter l = limiter(false);
        ApiKey k = TestFixtures.key("k", "sk", 1, 1L);
        for (int i = 0; i < 50; i++) {
            assertTrue(l.tryAcquire(k));
            l.recordTokenUsage(k, 100_000L);
        }
    }

    @Test
    public void nullKeyAndZeroUsageAreInert() {
        RateLimiter l = limiter(true);
        assertTrue(l.tryAcquire(null));
        l.recordTokenUsage(null, 10L);
        ApiKey k = TestFixtures.key("k", "sk", 1, null);
        l.tryAcquire(k);
        l.recordTokenUsage(k, 0L);
        l.release(k);
        assertEquals(1, l.activeBuckets());
    }

    /** 并发下的计数不能丢: 200 个线程抢 rpm=50 的桶, 放行数必须恰好等于额度. */
    @Test
    public void concurrentAcquireDoesNotOvershootQuota() throws Exception {
        RateLimiter l = limiter(true);
        ApiKey k = TestFixtures.key("k", "sk", 50, null);
        int threads = 8;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (l.tryAcquire(k)) {
                            granted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals("quota must be honoured exactly under concurrency", 50, granted.get());
    }
}
