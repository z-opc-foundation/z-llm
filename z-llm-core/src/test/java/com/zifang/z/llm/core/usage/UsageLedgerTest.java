package com.zifang.z.llm.core.usage;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.support.TestFixtures;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 用量与花费记账 — 网关此前只有 RPM 计数, 没有任何 token/成本口径. */
public class UsageLedgerTest {

    private GatewayProperties priced() {
        GatewayProperties p = TestFixtures.properties();
        Map<String, GatewayProperties.Price> pricing = new LinkedHashMap<>();
        pricing.put("openai/gpt-4o", new GatewayProperties.Price(2.5, 10.0));
        p.setPricing(pricing);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> snapshot, String name) {
        return (List<Map<String, Object>>) snapshot.get(name);
    }

    @Test
    public void costUsesPerMillionPrices() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k1", "sk-k1", null, null);
        UsageLedger.Record r = ledger.record(k, Vendor.OPENAI, "gpt-4o", 1_000_000L, 500_000L);
        // 1M prompt * 2.5 + 0.5M completion * 10 = 2.5 + 5.0
        assertEquals(7.5, r.cost(), 1e-9);
        assertEquals(7.5, ledger.totalCost(k), 1e-9);
        assertEquals(1_500_000L, ledger.totalTokens(k));
    }

    @Test
    public void aggregatesPerKeyAndPerModel() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k1", "sk-k1", null, null);
        ledger.record(k, Vendor.OPENAI, "gpt-4o", 100L, 50L);
        ledger.record(k, Vendor.OPENAI, "gpt-4o", 200L, 60L);
        ledger.record(k, Vendor.DEEPSEEK, "deepseek-chat", 10L, 5L);

        Map<String, Object> snap = ledger.snapshot();
        List<Map<String, Object>> byKey = rows(snap, "byKey");
        assertEquals(1, byKey.size());
        assertEquals(3L, byKey.get(0).get("requests"));
        assertEquals(310L, byKey.get(0).get("promptTokens"));

        List<Map<String, Object>> byModel = rows(snap, "byKeyModel");
        assertEquals(2, byModel.size());
    }

    /** 没配价格的模型不能静默算成"免费", 要留下痕迹. */
    @Test
    public void unpricedModelIsFlaggedNotSilent() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k9", "sk-k9", null, null);
        UsageLedger.Record r = ledger.record(k, Vendor.OPENAI, "gpt-5-turbo", 10L, 10L);
        assertEquals(0.0, r.cost(), 1e-9);
        assertTrue("must be marked estimated/unpriced", r.estimated());
        assertEquals(1L, rows(ledger.snapshot(), "byKey").get(0).get("unpricedRequests"));
    }

    @Test
    public void vendorDefaultPriceApplies() {
        GatewayProperties p = TestFixtures.properties();
        Map<String, GatewayProperties.Price> pricing = new LinkedHashMap<>();
        pricing.put("openai", new GatewayProperties.Price(5.0, 15.0));
        p.setPricing(pricing);
        UsageLedger ledger = new UsageLedger(p);
        UsageLedger.Record r = ledger.record(TestFixtures.key("k", "sk", null, null),
                Vendor.OPENAI, "anything-new", 1_000_000L, 0L);
        assertEquals(5.0, r.cost(), 1e-9);
    }

    @Test
    public void nullKeyIsStillReturnedButNotTracked() {
        UsageLedger ledger = new UsageLedger(priced());
        UsageLedger.Record r = ledger.record(null, Vendor.OPENAI, "gpt-4o", 10L, 10L);
        // 10/1M * 2.5 + 10/1M * 10.0 = 2.5e-5 + 1e-4
        assertEquals(0.000125, r.cost(), 1e-9);
        assertTrue(ledger.snapshot().toString(), rows(ledger.snapshot(), "byKey").isEmpty());
    }

    @Test
    public void negativeUsageIsClampedToZero() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k2", "sk-k2", null, null);
        UsageLedger.Record r = ledger.record(k, Vendor.OPENAI, "gpt-4o", -50L, -5L);
        assertEquals(0L, r.promptTokens());
        assertEquals(0L, ledger.totalTokens(k));
        assertFalse(Double.isNaN(r.cost()));
    }

    @Test
    public void failuresTrackedSeparately() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k3", "sk-k3", null, null);
        ledger.recordFailure(k);
        ledger.recordFailure(k);
        assertEquals(2L, rows(ledger.snapshot(), "byKey").get(0).get("failures"));
    }

    @Test
    public void resetClears() {
        UsageLedger ledger = new UsageLedger(priced());
        ApiKey k = TestFixtures.key("k4", "sk-k4", null, null);
        ledger.record(k, Vendor.OPENAI, "gpt-4o", 1L, 1L);
        ledger.reset();
        assertTrue(rows(ledger.snapshot(), "byKey").isEmpty());
    }
}
