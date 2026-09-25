package com.zifang.z.llm.core.service;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流 — 每 ApiKey 维度同时限制 RPM / TPM / 并发数.
 *
 * <p>RPM 此前已生效; TPM 只有记录没有拦截 (recordTokenUsage 全仓零调用点),
 * 现在改为: 请求前查窗口内已用量是否越过额度, 请求后把真实 usage 记进窗口.
 * 上游返回多少 token 就扣多少, 不做预估扣费.
 *
 * <p>并发数是新维度: 流式请求会长时间占住 servlet 线程, 只限 RPM 挡不住
 * 少量慢请求把线程池打满.
 *
 * <p>分布式场景下, 后续接入 z-cache 原子计数器 / redis 即可替换实现.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, ApiKeyBucket> buckets = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int maxConcurrent;

    public RateLimiter(GatewayProperties properties) {
        this.enabled = properties.isRateLimitEnabled();
        this.maxConcurrent = properties.getMaxConcurrentRequests() == null
                ? 0 : properties.getMaxConcurrentRequests();
    }

    /** 请求进入前: 查 RPM 余量 + 窗口内 TPM 余量 + 并发余量. */
    public boolean tryAcquire(ApiKey key) {
        if (!enabled || key == null) return true;
        ApiKeyBucket bucket = buckets.computeIfAbsent(key.getId(), k -> new ApiKeyBucket(key));
        return bucket.tryAcquire(key);
    }

    /** 请求完成后: 把真实 token 用量计入当前窗口. */
    public void recordTokenUsage(ApiKey key, long tokens) {
        if (!enabled || key == null || tokens <= 0) return;
        // 必须是 computeIfAbsent: 用 get() 的话, 记账就依赖于"限流是否先见过这个 key",
        // 绕开 controller 直接调 service 的调用方 (admin / 批处理) 用量会被静默丢掉, TPM 永不生效.
        buckets.computeIfAbsent(key.getId(), k -> new ApiKeyBucket(key)).addTokens(tokens);
    }

    public void release(ApiKey key) {
        if (!enabled || key == null) return;
        ApiKeyBucket bucket = buckets.get(key.getId());
        if (bucket != null) {
            bucket.release();
        }
    }

    /** 距离当前窗口还有多久重置 (ms), 用于 Retry-After 头. */
    public long retryAfterMs(ApiKey key) {
        if (!enabled || key == null) return 0L;
        ApiKeyBucket bucket = buckets.get(key.getId());
        if (bucket == null) return 0L;
        return bucket.retryAfterMs();
    }

    public int activeBuckets() {
        return buckets.size();
    }

    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(buckets.keySet()));
    }

    public Map<String, Object> detail(String keyId) {
        ApiKeyBucket bucket = buckets.get(keyId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (bucket == null) {
            return out;
        }
        out.putAll(bucket.snapshot());
        return out;
    }

    private final class ApiKeyBucket {
        final Integer rpmLimit;
        final Long tpmLimit;
        long windowStartMs;
        int reqCount;
        long tokenCount;
        final AtomicInteger inFlight = new AtomicInteger();

        ApiKeyBucket(ApiKey key) {
            this.rpmLimit = key.getRequestsPerMinute();
            this.tpmLimit = key.getTokensPerMinute();
            this.windowStartMs = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(ApiKey key) {
            long now = System.currentTimeMillis();
            if (now - windowStartMs >= WINDOW_MS) {
                reqCount = 0;
                tokenCount = 0;
                windowStartMs = now;
            }
            if (maxConcurrent > 0 && inFlight.get() >= maxConcurrent) {
                log.warn("Rate limited (concurrency): keyId={}, inFlight={}/{}",
                        key.getId(), inFlight.get(), maxConcurrent);
                return false;
            }
            if (rpmLimit != null && reqCount >= rpmLimit) {
                log.warn("Rate limited (rpm): keyId={}, count={}/{}", key.getId(), reqCount, rpmLimit);
                return false;
            }
            if (tpmLimit != null && tpmLimit > 0 && tokenCount >= tpmLimit) {
                log.warn("Rate limited (tpm): keyId={}, tokens={}/{}", key.getId(), tokenCount, tpmLimit);
                return false;
            }
            reqCount++;
            if (maxConcurrent > 0) {
                inFlight.incrementAndGet();
            }
            return true;
        }

        synchronized void addTokens(long tokens) {
            tokenCount += tokens;
        }

        void release() {
            if (maxConcurrent > 0) {
                inFlight.decrementAndGet();
            }
        }

        synchronized long retryAfterMs() {
            long elapsed = System.currentTimeMillis() - windowStartMs;
            return Math.max(0L, WINDOW_MS - elapsed);
        }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rpmLimit", rpmLimit);
            m.put("tpmLimit", tpmLimit);
            m.put("requests", reqCount);
            m.put("tokens", tokenCount);
            m.put("inFlight", inFlight.get());
            m.put("windowResetInMs", retryAfterMs());
            return m;
        }
    }
}
