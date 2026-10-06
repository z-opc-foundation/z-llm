package com.zifang.z.llm.core.resilience;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 凭据池调用器 — 同一 vendor 内跨凭据 failover + 冷却.
 *
 * <p>对标 LiteLLM router 的 cooldown 与 one-api 的渠道自动禁用:
 * 一个 AK 被打爆 (429) 或上游 5xx / 网络故障时, 该凭据进入冷却窗口,
 * 请求自动改投同 vendor 的下一个凭据, 而不是把 429 直接抛给调用方.
 *
 * <p>4xx 中除 408/429 外属调用方或配置问题 (400 参数错 / 401 上游 key 失效),
 * 换凭据无意义或需明确暴露, 因此 400 直接透出, 401/403 仍换凭据重试.
 */
public class ProviderInvoker {

    private static final Logger log = LoggerFactory.getLogger(ProviderInvoker.class);

    /** 一次可尝试的凭据句柄. */
    public static final class Handle {
        private final Vendor vendor;
        private final String alias;
        private final LlmProvider provider;

        Handle(Vendor vendor, String alias, LlmProvider provider) {
            this.vendor = vendor;
            this.alias = alias;
            this.provider = provider;
        }

        public Vendor vendor() {
            return vendor;
        }

        public String alias() {
            return alias;
        }

        public LlmProvider provider() {
            return provider;
        }

        public String key() {
            return vendor.code() + "::" + alias;
        }
    }

    private static final class Stat {
        final AtomicLong success = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
        final AtomicLong cooldownHits = new AtomicLong();
    }

    private final GatewayProperties properties;
    private final LlmProviderRegistry registry;
    private final LlmCredentialStore credentialStore;

    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Stat> stats = new ConcurrentHashMap<>();

    /** 可注入时钟（毫秒）：总时长预算按它判定，判据因此零 sleep。 */
    private final LongSupplier clockMs;
    /** 可注入退避：判据据此断言"预算用尽后不再退避"。 */
    private final LongConsumer backoffSleeper;

    public ProviderInvoker(GatewayProperties properties,
                          LlmProviderRegistry registry,
                          LlmCredentialStore credentialStore) {
        this(properties, registry, credentialStore, System::currentTimeMillis, ProviderInvoker::sleepQuietly);
    }

    public ProviderInvoker(GatewayProperties properties,
                          LlmProviderRegistry registry,
                          LlmCredentialStore credentialStore,
                          LongSupplier clockMs,
                          LongConsumer backoffSleeper) {
        this.properties = properties;
        this.registry = registry;
        this.credentialStore = credentialStore;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
        this.backoffSleeper = backoffSleeper == null ? ProviderInvoker::sleepQuietly : backoffSleeper;
    }

    /** 本次请求在该 vendor 上最多尝试几个凭据. */
    public int attemptsFor(Vendor vendor) {
        GatewayProperties.Retry r = properties.getRetry();
        if (!r.isEnabled()) {
            return 1;
        }
        return Math.max(1, r.getMaxAttempts());
    }

    /** failover 用的候选池 (含冷却兜底). */
    public List<Handle> candidatesForFailover(Vendor vendor) {
        return properties.getRetry().isEnabled()
                ? candidatesWithOverflow(vendor)
                : candidates(vendor);
    }

    /** 按 priority 返回该 vendor 当前可用 (未冷却) 的凭据句柄. */
    public List<Handle> candidates(Vendor vendor) {
        List<Handle> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LlmCredential cred : credentialStore.findByVendor(vendor)) {
            LlmProvider p = registry.get(vendor, cred.getAlias());
            if (p == null) {
                continue;
            }
            Handle h = new Handle(vendor, cred.getAlias(), p);
            Long until = cooldownUntil.get(h.key());
            if (until != null && until > now) {
                stat(h.key()).cooldownHits.incrementAndGet();
                continue;
            }
            out.add(h);
        }
        return out;
    }

    /** 全池为空时把冷却最浅的凭据放出来兜底, 避免一次限流让整个 vendor 拒绝服务. */
    public List<Handle> candidatesWithOverflow(Vendor vendor) {
        List<Handle> available = candidates(vendor);
        if (!available.isEmpty()) {
            return available;
        }
        List<Handle> all = new ArrayList<>();
        for (LlmCredential cred : credentialStore.findByVendor(vendor)) {
            LlmProvider p = registry.get(vendor, cred.getAlias());
            if (p != null) {
                all.add(new Handle(vendor, cred.getAlias(), p));
            }
        }
        if (!all.isEmpty()) {
            log.warn("vendor {} has all {} credentials cooling off, serving from cooldown pool",
                    vendor.code(), all.size());
        }
        return all;
    }

    /**
     * 带 failover 的执行. body 抛出的异常按可重试性决定是否换下一个凭据.
     *
     * <p>换凭据的次数上限是 {@code min(attemptsCap, 凭据池大小)}；
     * 若配了 {@code retry.max-elapsed-ms}，还额外受一个<b>总时长预算</b>约束：
     * 预算用尽就停止换凭据、不再退避，直接返回失败。两次约束都要，因为单次数上限
     * 乘上单次上游超时（默认读超时 300s）才是真正挂住请求线程的那个数。</p>
     *
     * @param attemptsCap 本次最多尝试几个凭据 (含首个)
     */
    public <T> T execute(Vendor vendor, int attemptsCap, Function<Handle, T> body) {
        GatewayProperties.Retry retry = properties.getRetry();
        List<Handle> pool = retry.isEnabled() ? candidatesWithOverflow(vendor) : candidatesOrSingle(vendor);
        if (pool.isEmpty()) {
            throw GatewayException.internal("No active credential for vendor " + vendor, null);
        }
        int max = retry.isEnabled() ? Math.max(1, Math.min(attemptsCap, pool.size())) : 1;
        long budgetMs = retry.getMaxElapsedMs();
        long startedAt = clockMs.getAsLong();
        Throwable last = null;
        int tried = 0;
        for (int i = 0; i < max; i++) {
            Handle h = pool.get(i);
            tried++;
            try {
                T result = body.apply(h);
                reportSuccess(h);
                return result;
            } catch (Throwable t) {
                last = t;
                if (!retryable(t)) {
                    throw propagate(t);
                }
                reportFailure(h, t);
                // 预算已经用完就不再换下一个凭据，也不再退避：调用方注定要收到
                // poolExhausted，让它在 servlet 请求线程上多睡一轮纯属浪费。
                if (budgetMs > 0 && clockMs.getAsLong() - startedAt >= budgetMs) {
                    break;
                }
                // 只有"后面还有下一次尝试"时才退避。最后一次失败之后没有下一次了，
                // 再睡就是纯浪费：调用方已经注定要收到 poolExhausted，却要多等
                // backoffMs * maxAttempts（默认 200ms * 3 = 600ms）才拿到那个错误，
                // 而这整条链路跑在 servlet 请求线程上。
                if (i < max - 1) {
                    sleepBackoff(retry, i);
                }
            }
        }
        throw poolExhausted(tried, vendor, last);
    }

    /**
     * 池子跑光后的对外状态码.
     *
     * <p>429 单独放行: 把它压成 502 会让客户端按"上游坏了"去无限重试, 而正确动作是退避。
     * 其余 (5xx / 未知) 仍是 502 — 上游拒绝的是网关的凭据, 不是调用方的请求。
     */
    private static GatewayException poolExhausted(int tried, Vendor vendor, Throwable last) {
        String detail = "All " + tried + " credential(s) of vendor " + vendor.code() + " failed: "
                + (last == null ? "unknown" : last.getMessage());
        Integer status = httpStatusOf(last);
        if (status != null && status == 429) {
            return GatewayException.rateLimited(detail);
        }
        return GatewayException.upstreamFailed(detail, last);
    }

    private List<Handle> candidatesOrSingle(Vendor vendor) {
        List<Handle> available = candidates(vendor);
        if (!available.isEmpty()) {
            return available;
        }
        // 限流关闭时仍要能服务 (只取 priority 首个, 不看冷却).
        return candidatesWithOverflow(vendor);
    }

    /** 该异常是否值得换一个凭据重试. */
    public static boolean retryable(Throwable t) {
        Integer status = httpStatusOf(t);
        if (status != null) {
            if (status == 429 || status == 401 || status == 403) {
                return true;
            }
            return status >= 500;
        }
        // 无 HTTP 状态 ⇒ 连接/超时/解析类故障, 换凭据有意义.
        return t instanceof RuntimeException || t instanceof java.io.IOException;
    }

    public static Integer httpStatusOf(Throwable t) {
        if (t instanceof com.zifang.z.agent.kernel.llm.support.LlmException) {
            return ((com.zifang.z.agent.kernel.llm.support.LlmException) t).getHttpStatus();
        }
        if (t instanceof GatewayException) {
            int s = ((GatewayException) t).getHttpStatus();
            return s > 0 ? s : null;
        }
        Throwable c = t.getCause();
        return c == null || c == t ? null : httpStatusOf(c);
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof GatewayException) {
            throw (GatewayException) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        throw GatewayException.upstreamFailed(t.getMessage(), t);
    }

    public void reportSuccess(Handle h) {
        cooldownUntil.remove(h.key());
        stat(h.key()).success.incrementAndGet();
    }

    public void reportFailure(Handle h, Throwable t) {
        stat(h.key()).failure.incrementAndGet();
        long until = System.currentTimeMillis() + properties.getRetry().getCooldownMs();
        Long prev = cooldownUntil.putIfAbsent(h.key(), until);
        if (prev != null) {
            cooldownUntil.put(h.key(), until);
        }
        log.warn("credential {} failed ({}), cooling off for {} ms",
                h.key(), t.getMessage(), properties.getRetry().getCooldownMs());
    }

    public boolean inCooldown(Handle h) {
        Long until = cooldownUntil.get(h.key());
        return until != null && until > System.currentTimeMillis();
    }

    public void clearCooldowns() {
        cooldownUntil.clear();
    }

    /** 供 admin/metrics 暴露的凭据健康快照. */
    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, Long>> it = cooldownUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
            }
        }
        Map<String, Object> cooldown = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : cooldownUntil.entrySet()) {
            cooldown.put(e.getKey(), e.getValue() - now);
        }
        out.put("coolingDown", cooldown);
        Map<String, Object> perKey = new LinkedHashMap<>();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("success", e.getValue().success.get());
            row.put("failure", e.getValue().failure.get());
            row.put("cooldownSkips", e.getValue().cooldownHits.get());
            perKey.put(e.getKey(), row);
        }
        out.put("credentials", perKey);
        return out;
    }

    private Stat stat(String key) {
        Stat s = stats.get(key);
        if (s == null) {
            s = new Stat();
            Stat existing = stats.putIfAbsent(key, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    private void sleepBackoff(GatewayProperties.Retry retry, int attempt) {
        long ms = retry.getBackoffMs() * (attempt + 1L);
        if (ms <= 0) {
            return;
        }
        backoffSleeper.accept(Math.min(ms, 5_000L));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
