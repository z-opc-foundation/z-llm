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

import java.util.function.Function;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 跨凭据 failover 的<b>退避预算</b>：池子跑光之后不该再白等一次退避。
 *
 * <p>现状（修前）：循环体最后一次失败后仍然执行 {@code sleepBackoff}，然后才跳出
 * 抛 {@code poolExhausted}。也就是说客户端已经注定要收到 502/429，却还要多等
 * {@code backoffMs * maxAttempts} ——默认 200ms × 3 = 600ms 的纯浪费，
 * 而这条链路跑在 servlet 请求线程上。</p>
 *
 * <p>判据用「相对耗时差」而不是绝对值：对照组是"第 3 把凭据成功"（应当有 2 次退避），
 * 主测是"3 把全失败"（应当也只有 2 次退避）。两者之差应当是一次退避以内，
 * 而不是三次。这样对机器抖动不敏感。</p>
 */
public class ProviderInvokerBackoffBudgetTest {

    /** 取一个够大又把测试时长相控制在 5s 以内的基准退避。 */
    private static final long BACKOFF_MS = 400L;
    private static final int MAX_ATTEMPTS = 3;

    private GatewayProperties props;
    private LlmCredentialStore store;
    private LlmProviderRegistry registry;

    @Before
    public void setUp() {
        props = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "ak-a", 1),
                TestFixtures.credential(Vendor.OPENAI, "ak-b", 2),
                TestFixtures.credential(Vendor.OPENAI, "ak-c", 3));
        props.getRetry().setBackoffMs(BACKOFF_MS);
        props.getRetry().setCooldownMs(60_000L);
        props.getRetry().setMaxAttempts(MAX_ATTEMPTS);
        store = TestFixtures.store(props);
        registry = TestFixtures.registry(store);
    }

    private ProviderInvoker newInvoker() {
        return new ProviderInvoker(props, registry, store);
    }

    private static Function<ProviderInvoker.Handle, String> failAll = h -> {
        throw new LlmException("openai", 503, "overloaded");
    };

    private static Function<ProviderInvoker.Handle, String> failFirstTwo = h -> {
        if ("ak-c".equals(h.alias())) {
            return "ok:" + h.alias();
        }
        throw new LlmException("openai", 503, "overloaded");
    };

    private static long elapsedOf(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /**
     * 池子跑光应当抛 GatewayException。
     *
     * <p>注意只能捕 GatewayException：{@code execute} 的 catch 子句收的是
     * {@code Throwable}，若在 lambda 里直接调 {@code fail()}，抛出的 AssertionError
     * 会被它当成"这次凭据失败"给转成 GatewayException，判据就测不到想测的东西了。</p>
     */
    private void runExpectingFailure(Function<ProviderInvoker.Handle, String> body) {
        try {
            newInvoker().execute(Vendor.OPENAI, MAX_ATTEMPTS, body);
        } catch (GatewayException expected) {
            return;
        }
        fail("前提：这三个凭据应当全部失败并抛出 GatewayException");
    }

    /**
     * 对照组：第 3 把凭据成功时，两次失败之后的退避<b>必须</b>发生。
     * 这条绿着，才说明下面那条红不是因为退避整体没跑。
     */
    @Test
    public void failoverSuccessStillWaitsItsBackoff() {
        long ms = elapsedOf(() -> {
            String out = newInvoker().execute(Vendor.OPENAI, MAX_ATTEMPTS, failFirstTwo);
            assertTrue("前提：第 3 把凭据应当成功", "ok:ak-c".equals(out));
        });
        // 2 次退避 = BACKOFF_MS * (1 + 2)
        assertTrue("两次失败后的退避被吞了，耗时 " + ms + "ms",
                ms >= BACKOFF_MS * 2);
    }

    /** 主测：3 把全失败时，退避次数应当与"成功那次"一样，都只发生在还有下一次尝试之前。 */
    @Test
    public void poolExhaustedDoesNotSleepAfterTheLastAttempt() {
        long exhausted = elapsedOf(() -> runExpectingFailure(failAll));
        long succeeded = elapsedOf(() -> {
            String out = newInvoker().execute(Vendor.OPENAI, MAX_ATTEMPTS, failFirstTwo);
            assertTrue(out != null);
        });

        long extra = exhausted - succeeded;
        assertTrue("池子耗尽比'最后一次成功'多花了 " + extra
                        + "ms（" + exhausted + " vs " + succeeded + "），"
                        + "说明最后一次尝试失败后还在白等一次退避；上限允许一次退避的抖动",
                extra < BACKOFF_MS * 2);
    }
}
