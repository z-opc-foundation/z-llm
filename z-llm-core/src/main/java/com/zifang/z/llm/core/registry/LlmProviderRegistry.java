package com.zifang.z.llm.core.registry;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.provider.AnthropicProvider;
import com.zifang.z.agent.kernel.llm.provider.DashScopeProvider;
import com.zifang.z.agent.kernel.llm.provider.DeepSeekProvider;
import com.zifang.z.agent.kernel.llm.provider.GeminiProvider;
import com.zifang.z.agent.kernel.llm.provider.OpenAIProvider;
import com.zifang.z.agent.kernel.llm.provider.QwenProvider;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LlmProvider 注册表 — 网关通过 (vendor, alias) 取出已注入凭据的 provider.
 *
 * <p>每个 (vendor, alias) 组合缓存一个独立的 provider 实例, 在凭据变更时重建.
 * <p>不依赖 kernel-llm 的 setter / configure, 通过构造注入 apiKey/baseUrl 完成.
 */
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final LlmCredentialStore credentialStore;

    /**
     * (vendor, alias) → provider.
     * <p>rebuild() 会在运行时被 admin 线程调用, 而 get() 在每个请求线程上跑,
     * 用 HashMap 会有可见性问题 (读到半初始化的表), 因此必须是并发容器.
     */
    private final Map<String, LlmProvider> cache = new ConcurrentHashMap<>();

    public LlmProviderRegistry(LlmCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
        rebuild();
    }

    /** 根据当前凭据重建缓存. */
    public synchronized void rebuild() {
        Map<String, LlmProvider> rebuilt = new ConcurrentHashMap<>();
        // 遍历全部凭据, 为每个 (vendor, alias) 实例化 provider.
        // 一律重建: 复用旧实例会让轮换过的 apiKey 继续留在服务路径上.
        for (Vendor vendor : Vendor.values()) {
            for (LlmCredential cred : credentialStore.findByVendor(vendor)) {
                rebuilt.put(key(vendor, cred.getAlias()), buildProvider(vendor, cred));
            }
        }
        // 一次性替换, 避免清空→重建窗口内请求读到空表.
        cache.clear();
        cache.putAll(rebuilt);
        log.info("LlmProviderRegistry cached providers: {}", cache.size());
    }

    /** 由 vendor + alias 取 provider. */
    public LlmProvider get(Vendor vendor, String alias) {
        return cache.get(key(vendor, alias));
    }

    /** 该 vendor 当前是否有可用凭据. */
    public boolean hasCredential(Vendor vendor) {
        return !credentialStore.findByVendor(vendor).isEmpty();
    }

    /**
     * 用给定实例替换 (vendor, alias) 的 provider.
     *
     * <p>存在的意义: 让适配层 (如 z-agent-proxy 把三方 agent 伪装成 provider) 和测试
     * 能在不重启 JVM 的情况下接管某个凭据; rebuild() 会按凭据重新构造并覆盖此处的实例.
     */
    public void replace(Vendor vendor, String alias, LlmProvider provider) {
        if (provider == null) {
            cache.remove(key(vendor, alias));
        } else {
            cache.put(key(vendor, alias), provider);
        }
    }

    /** 取 vendor 最优凭据对应的 provider. */
    public LlmProvider pick(Vendor vendor) {
        LlmCredential cred = credentialStore.pick(vendor);
        return get(vendor, cred.getAlias());
    }

    /** 取 vendor 全部 provider (按 priority 排序). */
    public List<LlmProvider> listByVendor(Vendor vendor) {
        return credentialStore.findByVendor(vendor).stream()
                .map(c -> cache.get(key(vendor, c.getAlias())))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
    }

    /** 列出已注册 (vendor, alias) 组合. */
    public java.util.Set<String> keys() {
        return cache.keySet();
    }

    // ---- 私有 ----

    private static String key(Vendor vendor, String alias) {
        return vendor.code() + "::" + (alias == null ? "" : alias);
    }

    private LlmProvider buildProvider(Vendor vendor, LlmCredential cred) {
        String apiKey = cred.getApiKey();
        String baseUrl = cred.getBaseUrl();
        switch (vendor) {
            case OPENAI:
                return new OpenAIProvider(apiKey, baseUrl);
            case ANTHROPIC:
                return new AnthropicProvider(apiKey, baseUrl);
            case DEEPSEEK:
                return new DeepSeekProvider(apiKey, baseUrl);
            case QWEN:
                return new QwenProvider(apiKey, baseUrl);
            case DASHSCOPE:
                return new DashScopeProvider(apiKey, baseUrl);
            case GEMINI:
                return new GeminiProvider(apiKey, baseUrl);
            default:
                throw new IllegalArgumentException("Unsupported vendor: " + vendor);
        }
    }
}