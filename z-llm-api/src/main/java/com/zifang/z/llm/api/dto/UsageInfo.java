package com.zifang.z.llm.api.dto;

/**
 * 用量信息 — 各 vendor 字段基本对齐 (prompt / completion / total).
 *
 * <p>流式 chunk 中部分 vendor 不带 usage, 只在最后一个 chunk 或非流式响应里给.
 */
public class UsageInfo {

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /** prompt 侧缓存命中 token (Anthropic / OpenAI 部分模型). */
    private Integer cachedTokens;

    /** 思考/推理 token (o1 / claude thinking). */
    private Integer reasoningTokens;

    public UsageInfo() {
    }

    public UsageInfo(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public static UsageInfo empty() {
        return new UsageInfo(0, 0, 0);
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(Integer cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public Integer getReasoningTokens() {
        return reasoningTokens;
    }

    public void setReasoningTokens(Integer reasoningTokens) {
        this.reasoningTokens = reasoningTokens;
    }
}