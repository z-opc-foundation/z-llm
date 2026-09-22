package com.zifang.z.llm.api.dto;

import java.util.List;

/**
 * 调用方接入网关的 API Key.
 *
 * <p>区别于 LlmCredential (后端 vendor AK): ApiKey 是租户级接入凭证, 由网关签发/校验,
 * <p>用于对外暴露 OpenAI/Anthropic 协议时做调用方鉴权.
 */
public class ApiKey {

    /** 网关内部 id. */
    private String id;

    /** 名称 (展示用). */
    private String name;

    /** 完整 key (只在创建时返回一次, 列表/详情只给脱敏). */
    private String key;

    /** 脱敏后的 key (sk-***xxx, 仅展示). */
    private String maskedKey;

    /** 关联租户/owner (未来接 z-ctc). */
    private String ownerId;

    /** 状态: "active" / "disabled". */
    private String status;

    /** 过期时间 (epoch millis, null 表示永不过期). */
    private Long expiresAt;

    /** 限定可访问模型 (null 表示不限制). */
    private List<String> allowedModels;

    /** 限定可访问 vendor (null 表示不限制). */
    private List<Vendor> allowedVendors;

    /** 每分钟 token 速率限制 (null 表示不限). */
    private Long tokensPerMinute;

    /** 每分钟请求次数限制 (null 表示不限). */
    private Integer requestsPerMinute;

    /** 创建时间 (epoch millis). */
    private Long createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getMaskedKey() {
        return maskedKey;
    }

    public void setMaskedKey(String maskedKey) {
        this.maskedKey = maskedKey;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<String> getAllowedModels() {
        return allowedModels;
    }

    public void setAllowedModels(List<String> allowedModels) {
        this.allowedModels = allowedModels;
    }

    public List<Vendor> getAllowedVendors() {
        return allowedVendors;
    }

    public void setAllowedVendors(List<Vendor> allowedVendors) {
        this.allowedVendors = allowedVendors;
    }

    public Long getTokensPerMinute() {
        return tokensPerMinute;
    }

    public void setTokensPerMinute(Long tokensPerMinute) {
        this.tokensPerMinute = tokensPerMinute;
    }

    public Integer getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(Integer requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}