package com.zifang.z.llm.api.dto;

/**
 * 一条 LLM 凭据 — vendor 的 AK + 路由信息.
 *
 * <p>网关持有多个 LlmCredential, 通过 alias 选择, 实际请求时由 ChatGatewayService 注入到 provider 头部.
 * <p>zk-config / nacos / apollo 等配置中心均能映射成此结构 (admin 模块负责读取/刷新).
 */
public class LlmCredential {

    /** 凭据别名 (路由键), 如 "openai-primary". */
    private String alias;

    /** 厂商. */
    private Vendor vendor;

    /** API key / access token. */
    private String apiKey;

    /** 自定义 base URL (可选, 不填走 provider 默认). */
    private String baseUrl;

    /** 该凭据可用模型白名单 (null 表示不限制, 使用 provider 默认全量). */
    private java.util.List<String> allowedModels;

    /** 优先级 (数字越小越优先, 用于多 AK 轮询/兜底). */
    private Integer priority;

    /** 状态: "active" / "disabled" / "rate-limited". */
    private String status;

    /** 额外透传头 (如 anthropic 自定义 version, openai 组织 id). */
    private java.util.Map<String, String> extraHeaders;

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public void setVendor(Vendor vendor) {
        this.vendor = vendor;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public java.util.List<String> getAllowedModels() {
        return allowedModels;
    }

    public void setAllowedModels(java.util.List<String> allowedModels) {
        this.allowedModels = allowedModels;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public java.util.Map<String, String> getExtraHeaders() {
        return extraHeaders;
    }

    public void setExtraHeaders(java.util.Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
    }
}