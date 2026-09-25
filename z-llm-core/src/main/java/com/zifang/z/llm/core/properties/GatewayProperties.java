package com.zifang.z.llm.core.properties;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.LlmCredential;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关配置根 — z.llm.* 配置树.
 *
 * <p>credentials: 后端 vendor 凭据列表 (每个 vendor 可配多个 AK 做兜底/轮询).
 * <p>apiKeys: 对外接入凭证列表 (网关签发, 调用方凭此鉴权).
 * <p>exposeAdmin: 是否注册 admin 端点 (默认 false, 由 z-llm-admin 引入后开启).
 */
@ConfigurationProperties(prefix = "z.llm")
public class GatewayProperties {

    /** 网关自身默认 base path (空表示无前缀). */
    private String basePath = "";

    /** 凭据列表. */
    @NestedConfigurationProperty
    private List<LlmCredential> credentials = new ArrayList<>();

    /** API Key 列表. */
    @NestedConfigurationProperty
    private List<ApiKey> apiKeys = new ArrayList<>();

    /** 是否暴露 admin 端点. */
    private boolean exposeAdmin = false;

    /** 默认最大 token 上限 (越权拒绝). */
    private Integer maxTokensLimit = 32768;

    /** 是否启用限流. */
    private boolean rateLimitEnabled = true;

    /** 单 key 最大并发 in-flight 请求数; <=0 表示不限并发. */
    private Integer maxConcurrentRequests = 0;

    /**
     * 流式请求是否替客户端补 {@code stream_options.include_usage=true}.
     * <p>OpenAI 只在带该参数时才在最后一个 chunk 里回 usage; 但部分自建兼容端点
     * 会对未知的 stream_options 报 400, 因此默认 false, 由部署方按上游能力打开.
     */
    private boolean injectStreamUsage = false;

    /** 是否强制 ApiKey 的 allowedModels / allowedVendors 白名单. */
    private boolean enforceKeyRestrictions = true;

    /** 失败重试 / 凭据冷却. */
    @NestedConfigurationProperty
    private Retry retry = new Retry();

    /**
     * 模型别名表: 请求里的 model → 网关内部 "vendor/model" 形式.
     * <p>用于让裸 model 名 (OpenAI SDK 只发 "gpt-4o") 与含斜杠的三方 model id
     * (如 "meta-llama/Llama-3.1-70B") 都能命中正确的 vendor.
     */
    private Map<String, String> modelAliases = new LinkedHashMap<>();

    /** 模型计价表 (每百万 token 单价, 美元), key 为 "vendor/model". */
    @NestedConfigurationProperty
    private Map<String, Price> pricing = new LinkedHashMap<>();

    /** 计费币种标记, 仅用于 /usage 接口回显. */
    private String currency = "USD";

    public static class Retry {
        /** 是否启用跨凭据 failover. */
        private boolean enabled = true;
        /** 单次请求最多尝试几个凭据 (含首个). */
        private int maxAttempts = 3;
        /** 每次重试前的基准退避毫秒 (按尝试次数线性放大). */
        private long backoffMs = 200L;
        /** 凭据被打上冷却的时长. */
        private long cooldownMs = 30_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }

        public long getCooldownMs() {
            return cooldownMs;
        }

        public void setCooldownMs(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }
    }

    public static class Price {
        private double promptPerMillion;
        private double completionPerMillion;

        public Price() {
        }

        public Price(double promptPerMillion, double completionPerMillion) {
            this.promptPerMillion = promptPerMillion;
            this.completionPerMillion = completionPerMillion;
        }

        public double getPromptPerMillion() {
            return promptPerMillion;
        }

        public void setPromptPerMillion(double promptPerMillion) {
            this.promptPerMillion = promptPerMillion;
        }

        public double getCompletionPerMillion() {
            return completionPerMillion;
        }

        public void setCompletionPerMillion(double completionPerMillion) {
            this.completionPerMillion = completionPerMillion;
        }
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public List<LlmCredential> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<LlmCredential> credentials) {
        this.credentials = credentials;
    }

    public List<ApiKey> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKey> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public boolean isExposeAdmin() {
        return exposeAdmin;
    }

    public void setExposeAdmin(boolean exposeAdmin) {
        this.exposeAdmin = exposeAdmin;
    }

    public Integer getMaxTokensLimit() {
        return maxTokensLimit;
    }

    public void setMaxTokensLimit(Integer maxTokensLimit) {
        this.maxTokensLimit = maxTokensLimit;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public Integer getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(Integer maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public boolean isInjectStreamUsage() {
        return injectStreamUsage;
    }

    public void setInjectStreamUsage(boolean injectStreamUsage) {
        this.injectStreamUsage = injectStreamUsage;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public boolean isEnforceKeyRestrictions() {
        return enforceKeyRestrictions;
    }

    public void setEnforceKeyRestrictions(boolean enforceKeyRestrictions) {
        this.enforceKeyRestrictions = enforceKeyRestrictions;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry == null ? new Retry() : retry;
    }

    public Map<String, String> getModelAliases() {
        return modelAliases;
    }

    public void setModelAliases(Map<String, String> modelAliases) {
        this.modelAliases = modelAliases == null ? new LinkedHashMap<>() : modelAliases;
    }

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing == null ? new LinkedHashMap<>() : pricing;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
