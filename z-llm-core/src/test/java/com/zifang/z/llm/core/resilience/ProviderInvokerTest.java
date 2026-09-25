package com.zifang.z.llm.core.resilience;

import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.support.TestFixtures;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 跨凭据 failover 与冷却 — 对标 LiteLLM cooldown / one-api 渠道自动禁用.
 */
public class ProviderInvokerTest {

    private GatewayProperties props;
    private ProviderInvoker invoker;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-b", 2),
                TestFixtures.credential(Vendor.OPENAI, "ak-c", 3));
        props.getRetry().setBackoffMs(0L);
        props.getRetry().setCooldownMs(60_000L);
        props.getRetry().setMaxAttempts(3);
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        invoker = new ProviderInvoker(props, registry, store);
    }

    @Test
    public void candidatesFollowCredentialPriority() {
        List<ProviderInvoker.Handle> hs = invoker.candidates(Vendor.OPENAI);
        assertEquals(3, hs.size());
        assertEquals("ak-a", hs.get(0).alias());
        assertEquals("ak-c", hs.get(2).alias());
    }

    /** 首把 AK 被限流时应当自动换下一把, 而不是把 429 抛给调用方. */
    @Test
    public void failoverOn429ServesFromNextCredential() {
        List<String> served = new ArrayList<>();
        String out = invoker.execute(Vendor.OPENAI, 3, h -> {
            served.add(h.alias());
            if ("ak-a".equals(h.alias())) {
                throw new LlmException("openai", 429, "rate limit reached");
            }
            return "ok:" + h.alias();
        });
        assertEquals("ok:ak-b", out);
        assertEquals(2, served.size());
    }

    @Test
    public void serverErrorAlsoFailsOver() {
        String out = invoker.execute(Vendor.OPENAI, 3, h -> {
            if ("ak-a".equals(h.alias())) {
                throw new LlmException("openai", 503, "overloaded");
            }
            return "ok:" + h.alias();
        });
        assertEquals("ok:ak-b", out);
    }

    /** 参数错属调用方问题, 换凭据没有意义, 必须立刻透出且不打冷却. */
    @Test
    public void clientErrorDoesNotRetry() {
        List<String> tried = new ArrayList<>();
        try {
            invoker.execute(Vendor.OPENAI, 3, h -> {
                tried.add(h.alias());
                throw new LlmException("openai", 400, "bad request");
            });
            fail("expected 400 to propagate");
        } catch (LlmException expected) {
            assertEquals(Integer.valueOf(400), expected.getHttpStatus());
        }
        assertEquals(1, tried.size());
        assertFalse(invoker.inCooldown(new ProviderInvoker.Handle(
                Vendor.OPENAI, "ak-a", null)));
    }

    @Test
    public void failedCredentialEntersCooldownAndIsSkipped() {
        // 第一次: ak-a 失败 → 冷却.
        invoker.execute(Vendor.OPENAI, 3, h -> {
            if ("ak-a".equals(h.alias())) {
                throw new LlmException("openai", 429, "slow down");
            }
            return "x";
        });
        List<String> next = new ArrayList<>();
        invoker.execute(Vendor.OPENAI, 3, h -> {
            next.add(h.alias());
            return "y";
        });
        assertEquals("cooled-off ak-a must not be retried next request", false, next.contains("ak-a"));
        assertEquals("ak-b", next.get(0));
        assertTrue(invoker.snapshot().toString().contains("ak-a"));
    }

    @Test
    public void successClearsCooldown() {
        invoker.execute(Vendor.OPENAI, 3, h -> {
            if ("ak-a".equals(h.alias())) {
                throw new LlmException("openai", 429, "boom");
            }
            return "x";
        });
        // 手工清冷却后成功一次, 应回到可用池.
        invoker.clearCooldowns();
        invoker.execute(Vendor.OPENAI, 3, h -> "ok");
        assertFalse(((Map<?, ?>) invoker.snapshot().get("coolingDown")).containsKey("openai::ak-a"));
    }

    /** 全部凭据都在冷却时不能变成拒绝服务 — 至少要放一个出来试. */
    @Test
    public void allCoolingOffStillServesFromOverflowPool() {
        for (ProviderInvoker.Handle h : invoker.candidates(Vendor.OPENAI)) {
            invoker.reportFailure(h, new LlmException("openai", 429, "boom"));
        }
        assertTrue(invoker.candidates(Vendor.OPENAI).isEmpty());
        assertEquals(3, invoker.candidatesWithOverflow(Vendor.OPENAI).size());
        assertEquals("ok:ak-a", invoker.execute(Vendor.OPENAI, 3, h -> "ok:" + h.alias()));
    }

    @Test
    public void exhaustingPoolSurfacesUpstreamFailure() {
        try {
            invoker.execute(Vendor.OPENAI, 3, h -> {
                throw new LlmException("openai", 500, "all broken");
            });
            fail("expected 502");
        } catch (GatewayException e) {
            assertEquals(502, e.getHttpStatus());
            assertTrue(e.getMessage().contains("All 3 credential(s)"));
        }
    }

    /** 全池都被上游限流时 429 不能被压成笼统的 502: 客户端的正确动作是退避, 不是立刻重试. */
    @Test
    public void exhaustedPoolKeepsUpstream429AsRateLimited() {
        try {
            invoker.execute(Vendor.OPENAI, 3, h -> {
                throw new LlmException("openai", 429, "slow down");
            });
            fail("expected 429");
        } catch (GatewayException e) {
            assertEquals(429, e.getHttpStatus());
        }
    }

    @Test
    public void retryDisabledUsesExactlyOneCredential() {
        props.getRetry().setEnabled(false);
        List<String> tried = new ArrayList<>();
        Function<ProviderInvoker.Handle, String> body = h -> {
            tried.add(h.alias());
            if ("ak-a".equals(h.alias())) {
                throw new LlmException("openai", 429, "boom");
            }
            return "ok";
        };
        try {
            invoker.execute(Vendor.OPENAI, 3, body);
            fail("expected failure when retry is off");
        } catch (RuntimeException expected) {
            assertEquals(1, tried.size());
        }
    }

    @Test
    public void attemptsCapLimitsPoolUsage() {
        List<String> tried = new ArrayList<>();
        try {
            invoker.execute(Vendor.OPENAI, 2, h -> {
                tried.add(h.alias());
                throw new LlmException("openai", 429, "boom");
            });
            fail();
        } catch (GatewayException expected) {
            assertEquals(2, tried.size());
        }
    }

    @Test
    public void networkFailureWithoutHttpStatusIsRetryable() {
        assertTrue(ProviderInvoker.retryable(new java.io.UncheckedIOException(
                new java.io.IOException("connection reset"))));
        assertTrue(ProviderInvoker.retryable(new RuntimeException("timeout")));
    }
}
