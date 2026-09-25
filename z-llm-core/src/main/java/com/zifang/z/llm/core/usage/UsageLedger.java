package com.zifang.z.llm.core.usage;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * 用量与花费台账 — 按 (ApiKey, model) 维度累计请求数 / token / 成本.
 *
 * <p>网关原本只有限流没有记账: 调用方无法知道自己烧了多少钱, 运维也无法定位
 * 是哪个 key 在打哪个模型. 这里是记账口, 与限流解耦 (限流看窗口, 记账看累计).
 *
 * <p>单价来自 {@code z.llm.pricing}, 单位是每百万 token; 未配置价格的模型按 0 计,
 * 并计入 {@code unpricedRequests} 而不是静默当作没花销.
 */
public class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);

    /** 单次调用的用量记录. */
    public static final class Record {
        private final String model;
        private final long promptTokens;
        private final long completionTokens;
        private final double cost;
        private final boolean estimated;

        public Record(String model, long promptTokens, long completionTokens, double cost, boolean estimated) {
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.cost = cost;
            this.estimated = estimated;
        }

        public String model() {
            return model;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public double cost() {
            return cost;
        }

        public boolean estimated() {
            return estimated;
        }
    }

    private static final class Bucket {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong unpriced = new AtomicLong();
        final DoubleAdder cost = new DoubleAdder();
        volatile long lastSeenMs = System.currentTimeMillis();
    }

    private final GatewayProperties properties;
    /** keyId → bucket. */
    private final ConcurrentHashMap<String, Bucket> byKey = new ConcurrentHashMap<>();
    /** "keyId|model" → bucket. */
    private final ConcurrentHashMap<String, Bucket> byKeyModel = new ConcurrentHashMap<>();

    public UsageLedger(GatewayProperties properties) {
        this.properties = properties;
    }

    public Record record(ApiKey key, Vendor vendor, String bareModel,
                         long promptTokens, long completionTokens) {
        long p = Math.max(0L, promptTokens);
        long c = Math.max(0L, completionTokens);
        String canonical = vendor == null ? String.valueOf(bareModel) : vendor.code() + "/" + bareModel;
        GatewayProperties.Price price = priceOf(canonical, bareModel);
        double cost = 0d;
        boolean unpriced = price == null;
        if (price != null) {
            cost = p / 1_000_000d * price.getPromptPerMillion()
                    + c / 1_000_000d * price.getCompletionPerMillion();
        }
        Record rec = new Record(canonical, p, c, cost, unpriced);
        if (key == null || key.getId() == null) {
            return rec;
        }
        Bucket b = bucket(byKey, key.getId());
        accumulate(b, p, c, cost, unpriced);
        accumulate(bucket(byKeyModel, key.getId() + "|" + canonical), p, c, cost, unpriced);
        return rec;
    }

    public void recordFailure(ApiKey key) {
        if (key == null || key.getId() == null) {
            return;
        }
        bucket(byKey, key.getId()).failures.incrementAndGet();
    }

    /** 累计 token 数 (供限流做 TPM 判定前的已用量查询). */
    public long totalTokens(ApiKey key) {
        if (key == null || key.getId() == null) {
            return 0L;
        }
        Bucket b = byKey.get(key.getId());
        return b == null ? 0L : b.promptTokens.get() + b.completionTokens.get();
    }

    public double totalCost(ApiKey key) {
        if (key == null || key.getId() == null) {
            return 0d;
        }
        Bucket b = byKey.get(key.getId());
        return b == null ? 0d : b.cost.sum();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", properties.getCurrency());
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : byKey.entrySet()) {
            keys.add(row(e.getKey(), null, e.getValue()));
        }
        List<Map<String, Object>> perModel = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : byKeyModel.entrySet()) {
            String[] split = e.getKey().split("\\|", 2);
            perModel.add(row(split[0], split.length > 1 ? split[1] : null, e.getValue()));
        }
        out.put("byKey", keys);
        out.put("byKeyModel", perModel);
        return out;
    }

    public void reset() {
        byKey.clear();
        byKeyModel.clear();
    }

    // ---- 私有 ----

    private static Map<String, Object> row(String keyId, String model, Bucket b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyId", keyId);
        if (model != null) {
            m.put("model", model);
        }
        m.put("requests", b.requests.get());
        m.put("promptTokens", b.promptTokens.get());
        m.put("completionTokens", b.completionTokens.get());
        m.put("failures", b.failures.get());
        m.put("unpricedRequests", b.unpriced.get());
        m.put("cost", Math.round(b.cost.sum() * 1e8) / 1e8);
        m.put("lastSeenMs", b.lastSeenMs);
        return m;
    }

    private static void accumulate(Bucket b, long p, long c, double cost, boolean unpriced) {
        b.requests.incrementAndGet();
        b.promptTokens.addAndGet(p);
        b.completionTokens.addAndGet(c);
        b.cost.add(cost);
        b.lastSeenMs = System.currentTimeMillis();
        if (unpriced) {
            b.unpriced.incrementAndGet();
        }
    }

    private static Bucket bucket(ConcurrentHashMap<String, Bucket> map, String k) {
        Bucket b = map.get(k);
        if (b == null) {
            b = new Bucket();
            Bucket existing = map.putIfAbsent(k, b);
            if (existing != null) {
                b = existing;
            }
        }
        return b;
    }

    /** 先查 "vendor/model" 精确价, 再查裸 model 价, 再查 vendor 默认价. */
    private GatewayProperties.Price priceOf(String canonical, String bareModel) {
        Map<String, GatewayProperties.Price> pricing = properties.getPricing();
        if (pricing == null || pricing.isEmpty()) {
            return null;
        }
        GatewayProperties.Price p = pricing.get(canonical);
        if (p == null && bareModel != null) {
            p = pricing.get(bareModel);
        }
        if (p == null && canonical != null) {
            int slash = canonical.indexOf('/');
            if (slash > 0) {
                p = pricing.get(canonical.substring(0, slash));
            }
        }
        if (p == null) {
            for (Map.Entry<String, GatewayProperties.Price> e : pricing.entrySet()) {
                if (e.getKey() != null && bareModel != null
                        && e.getKey().toLowerCase(Locale.ROOT).endsWith(bareModel.toLowerCase(Locale.ROOT))) {
                    return e.getValue();
                }
            }
        }
        if (p == null && log.isDebugEnabled()) {
            log.debug("no price configured for model {}", canonical);
        }
        return p;
    }
}
