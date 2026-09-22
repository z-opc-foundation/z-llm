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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LlmProvider 注册表 — 网关通过 (vendor, alias) 取出已注入凭据的 provider.
 *
 * <p>每个 (vendor, alias) 组合缓存一个独立的 provider 实例, 在凭据变更时重建.
 * <p>不依赖 kernel-llm 的 setter / configure, 通过构造注入 apiKey/baseUrl 完成.
 */
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final LlmCredentialStore credentialStore;

    /** (vendor, alias) → provider. */
    private final Map<String, LlmProvider> cache = new HashMap<>();

    public LlmProviderRegistry(LlmCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
        rebuild();
    }

    /** 根据当前凭据重建缓存. */
    public synchronized void rebuild() {
        cache.clear();
        // 遍历全部凭据, 为每个 (vendor, alias) 实例化 provider
        for (Vendor vendor : Vendor.values()) {
            for (LlmCredential cred : credentialStore.findByVendor(vendor)) {
                String key = key(vendor, cred.getAlias());
                cache.put(key, buildProvider(vendor, cred));
            }
        }
        log.info("LlmProviderRegistry cached providers: {}", cache.size());
    }

    /** 由 vendor + alias 取 provider. */
    public LlmProvider get(Vendor vendor, String alias) {
        return cache.get(key(vendor, alias));
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