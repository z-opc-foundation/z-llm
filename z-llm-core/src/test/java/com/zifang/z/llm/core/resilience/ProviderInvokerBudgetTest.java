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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 跨凭据重试的<b>总时长预算</b>（{@code retry.max-elapsed-ms}）。
 *
 * <p>为什么需要这条：{@code maxAttempts} 只约束<b>次数</b>，而每次尝试都要跑一遍真实的
 * 上游调用。总挂起时间是 {@code min(maxAttempts, 凭据池大小) × 单次上游超时} 再加退避——
 * 按本仓默认（3 次 × 读超时 300s + 连接 10s）最坏约 <b>930 秒</b>，占着一个 servlet
 * 请求线程，而没有任何配置项能看见这个乘积。</p>
 *
 * <p>全部判据<b>零 {@code Thread.sleep}</b>：时钟（{@code LongSupplier}）与退避
 * （{@code LongConsumer}）都从构造器注入，"时间流逝"由 body 推进可注入时钟表示，
 * "有没有退避"由 sleeper 记录次数与毫秒数表示。</p>
 */
public class ProviderInvokerBudgetTest {

    private GatewayProperties props;
    private LlmCredentialStore store;
    private LlmProviderRegistry registry;

    /** 可手工推进的时钟。 */
    private AtomicLong now;
    /** 退避记录：每次退避记下毫秒数。 */
    private List<Long> slept;
    private int attempts;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-b", 2),
                TestFixtures.credential(Vendor.OPENAI, "ak-c", 3));
        props.getRetry().setBackoffMs(100L);
        props.getRetry().setCooldownMs(60_000L);
        props.getRetry().setMaxAttempts(3);
        store = TestFixtures.store(props);
        registry = TestFixtures.registry(store);

        now = new AtomicLong(1_000L);
        slept = new ArrayList<>();
        attempts = 0;
    }

    private ProviderInvoker invoker() {
        return new ProviderInvoker(props, registry, store, now::get, slept::add);
    }

    /** 每次尝试都 429 失败，并把时钟往前推 {@code costMs}。返回对外报告的"试了几个"。 */
    private int runFailing(long costMs) {
        try {
            invoker().execute(Vendor.OPENAI, invoker().attemptsFor(Vendor.OPENAI), h -> {
                attempts++;
                now.addAndGet(costMs);
                throw new LlmException("openai", 429, "rate limited");
            });
            fail("应当以池耗尽结束");
            return -1;
        } catch (GatewayException e) {
            return parseTried(e.getMessage());
        }
    }

    private static int parseTried(String detail) {
        // 形如 "All 3 credential(s) of vendor OPENAI failed: ..."
        String head = detail.substring(0, detail.indexOf("credential(s)"));
        String[] parts = head.trim().split(" ");
        return Integer.parseInt(parts[parts.length - 1]);
    }

    // ==================================================================
    // 默认值：0 = 不限，现有行为逐字不变
    // ==================================================================

    @Test
    public void maxElapsedMsDefaultsToUnbounded() {
        assertEquals("默认值必须是 0（不限）——一设成非 0 就会按部署的单次上游超时截断 failover，"
                        + "默认配置下的流式长回答可能被直接砍掉",
                0L, props.getRetry().getMaxElapsedMs());

        assertEquals("不限预算时三次尝试要照跑（与引入预算前一致）", 3, runFailing(400L));
    }

    // ==================================================================
    // 预算生效
    // ==================================================================

    @Test
    public void budgetStopsFailoverOnceElapsed() {
        props.getRetry().setMaxElapsedMs(500L);

        // 每次尝试花 400ms：第 1 次后 elapsed=400 还没超，第 2 次后 800 超了 ⇒ 只试 2 次
        int tried = runFailing(400L);

        assertEquals("预算 500ms、每次尝试 400ms 时应当只试 2 次 —— 不加预算会试满 3 次，"
                + "总挂起 ≈ 3 × 单次上游超时", 2, tried);
        assertEquals("实际执行的尝试次数应当与对外报告的一致", 2, attempts);
    }

    @Test
    public void budgetExhaustionSkipsBackoff() {
        props.getRetry().setMaxElapsedMs(500L);

        runFailing(400L);

        // 第 1 次失败后还有下一次 ⇒ 退避一次；第 2 次失败后预算用尽 ⇒ 不退避
        assertEquals("预算用尽的那一轮不该再退避：调用方注定要收到池耗尽错误，让它在请求线程上"
                + "多睡 backoffMs 没有意义。实际退避了 " + slept, 1, slept.size());
        assertEquals("唯一一次退避应是第 1 轮之后的基准退避 backoffMs × (attempt+1) = 100",
                Long.valueOf(100L), slept.get(0));
    }

    // ==================================================================
    // 对照组：预算充足时不得误伤
    // ==================================================================

    @Test
    public void generousBudgetKeepsFullFailover() {
        props.getRetry().setMaxElapsedMs(5_000L);

        int tried = runFailing(100L);

        assertEquals("预算 5000ms、每次 100ms 时三次尝试要照跑", 3, tried);
        assertEquals("两次尝试之间各退避一次、最后一次失败后不退避；实际退避了 " + slept,
                2, slept.size());
        assertEquals("第 1 轮退避 = 100ms", Long.valueOf(100L), slept.get(0));
        assertEquals("第 2 轮退避按尝试次数线性放大 = 200ms", Long.valueOf(200L), slept.get(1));
    }

    @Test
    public void budgetExactlyReachedStillStops() {
        props.getRetry().setMaxElapsedMs(400L);

        // 一次尝试正好花掉 400ms = 预算 ⇒ 应当在此刻停止（>= 而非 >）
        assertEquals("elapsed 恰好等于预算时必须停（>= 语义）；写成 > 会让边界上多试一次",
                1, runFailing(400L));
    }

    @Test
    public void retryDisabledIgnoresBudget() {
        props.getRetry().setEnabled(false);
        props.getRetry().setMaxElapsedMs(1L);

        assertEquals("retry 关掉时只试一次，预算不参与", 1, runFailing(400L));
        assertTrue("retry 关掉时不退避；实际退避了 " + slept, slept.isEmpty());
    }
}