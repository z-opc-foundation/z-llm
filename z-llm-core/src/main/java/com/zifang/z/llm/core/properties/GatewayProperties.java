package com.zifang.z.llm.core.properties;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.LlmCredential;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

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

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }
}