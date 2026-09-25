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
 * <p>exposeAdmin: 是否注册 z-llm-admin 的控制面端点 (默认 false). 该开关由 z-llm-admin 的
 * {@code ZLlmAdminAutoConfiguration} 与两个控制器各自读取, 引入 z-llm-admin 且值为 true 才注册.
 */
@ConfigurationProperties(prefix = "z.llm")
public class GatewayProperties {

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

    /**
     * 带图片 part 的请求是否走"网关直连上游 OpenAI 兼容端点".
     * <p>kernel 的 provider 只把 {@code Msg.content} 当纯文本下发, 经它转发的图片会被静默丢掉;
     * 打开后网关对 openai / deepseek / qwen 三家绕过 provider 直连, 图片才真能到模型。
     */
    private boolean relayMultimodal = true;

    /**
     * 直连关闭 (或 vendor 非 OpenAI 兼容) 时, 是否允许把多模态摊平成纯文本继续服务.
     * <p>默认 false: 宁可显式报错, 也不要返回一个"根本没看到图"的回答。
     */
    private boolean allowMultimodalDowngrade = false;

    /** 网关直连上游的连接超时 (秒). */
    private int upstreamConnectTimeoutSec = 10;

    /** 网关直连上游的读超时 (秒); 流式长回答要留足. */
    private int upstreamReadTimeoutSec = 300;

    /** 网关直连上游的写超时 (秒). */
    private int upstreamWriteTimeoutSec = 60;

    /** 单次 /v1/embeddings 最多允许几条输入 (OpenAI 上限 2048). */
    private int maxEmbeddingBatch = 2048;

    /**
     * 裸 embedding 模型名 (如 "text-embedding-3-small") 归属的 vendor.
     * <p>kernel provider 的 supportsModel 只声明对话模型, 向量模型走那条路必然 404,
     * 因此 embeddings 路由需要这个显式归属; 未配置时若只配了一个 OpenAI 兼容 vendor 则自动选中它。
     */
    private String embeddingVendor;

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

    public boolean isRelayMultimodal() {
        return relayMultimodal;
    }

    public void setRelayMultimodal(boolean relayMultimodal) {
        this.relayMultimodal = relayMultimodal;
    }

    public boolean isAllowMultimodalDowngrade() {
        return allowMultimodalDowngrade;
    }

    public void setAllowMultimodalDowngrade(boolean allowMultimodalDowngrade) {
        this.allowMultimodalDowngrade = allowMultimodalDowngrade;
    }

    public int getUpstreamConnectTimeoutSec() {
        return upstreamConnectTimeoutSec;
    }

    public void setUpstreamConnectTimeoutSec(int upstreamConnectTimeoutSec) {
        this.upstreamConnectTimeoutSec = upstreamConnectTimeoutSec;
    }

    public int getUpstreamReadTimeoutSec() {
        return upstreamReadTimeoutSec;
    }

    public void setUpstreamReadTimeoutSec(int upstreamReadTimeoutSec) {
        this.upstreamReadTimeoutSec = upstreamReadTimeoutSec;
    }

    public int getUpstreamWriteTimeoutSec() {
        return upstreamWriteTimeoutSec;
    }

    public void setUpstreamWriteTimeoutSec(int upstreamWriteTimeoutSec) {
        this.upstreamWriteTimeoutSec = upstreamWriteTimeoutSec;
    }

    public int getMaxEmbeddingBatch() {
        return maxEmbeddingBatch;
    }

    public void setMaxEmbeddingBatch(int maxEmbeddingBatch) {
        this.maxEmbeddingBatch = maxEmbeddingBatch;
    }

    public String getEmbeddingVendor() {
        return embeddingVendor;
    }

    public void setEmbeddingVendor(String embeddingVendor) {
        this.embeddingVendor = embeddingVendor;
    }
}
