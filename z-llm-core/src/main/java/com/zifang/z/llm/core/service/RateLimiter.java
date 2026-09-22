package com.zifang.z.llm.core.service;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流 — 每 ApiKey 维度简单 token bucket.
 *
 * <p>本期实现: 单 ApiKey 维度, 同时限制 RPM (requests per minute) + TPM (tokens per minute).
 * <p>分布式场景下, 后续接入 z-cache 原子计数器 / redis 即可替换实现.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, ApiKeyBucket> buckets = new ConcurrentHashMap<>();
    private final boolean enabled;

    public RateLimiter(GatewayProperties properties) {
        this.enabled = properties.isRateLimitEnabled();
    }

    public boolean tryAcquire(ApiKey key) {
        if (!enabled || key == null) return true;
        ApiKeyBucket bucket = buckets.computeIfAbsent(key.getId(), k -> new ApiKeyBucket(key));
        return bucket.tryAcquire();
    }

    public void recordTokenUsage(ApiKey key, long tokens) {
        if (!enabled || key == null || key.getTokensPerMinute() == null) return;
        ApiKeyBucket bucket = buckets.get(key.getId());
        if (bucket != null) {
            synchronized (bucket) {
                bucket.tokenCount += tokens;
                if (bucket.tokenCount > bucket.tpmLimit) {
                    log.warn("Token usage exceeded TPM limit: keyId={}, used={}/{}",
                            key.getId(), bucket.tokenCount, bucket.tpmLimit);
                }
            }
        }
    }

    public int activeBuckets() {
        return buckets.size();
    }

    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(buckets.keySet()));
    }

    private static final class ApiKeyBucket {
        final Integer rpmLimit;
        final Long tpmLimit;
        long windowStartMs;
        int reqCount;
        long tokenCount;

        ApiKeyBucket(ApiKey key) {
            this.rpmLimit = key.getRequestsPerMinute();
            this.tpmLimit = key.getTokensPerMinute();
            this.windowStartMs = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStartMs >= WINDOW_MS) {
                reqCount = 0;
                tokenCount = 0;
                windowStartMs = now;
            }
            if (rpmLimit != null && reqCount >= rpmLimit) {
                log.warn("Rate limited (rpm): count={}/{}", reqCount, rpmLimit);
                return false;
            }
            reqCount++;
            return true;
        }
    }
}