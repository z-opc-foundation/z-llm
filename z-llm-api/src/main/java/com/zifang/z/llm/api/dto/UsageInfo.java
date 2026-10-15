package com.zifang.z.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zifang.util.json.annotation.JsonDeserialize;
import com.zifang.util.json.annotation.JsonSerialize;

/**
 * 用量信息 — 各 vendor 字段基本对齐 (prompt / completion / total).
 *
 * <p>流式 chunk 中部分 vendor 不带 usage, 只在最后一个 chunk 或非流式响应里给.
 */
public class UsageInfo {

    // 本类同时被两套序列化器经手: EmbeddingsResponse 是 Spring MVC 的 ResponseEntity (Jackson 注解仍然活着),
    // 而 UnifiedResponse / UnifiedStreamChunk 走 JsonUtil。所以两套注解都要留 ——
    // 只留 Jackson 那份会让 UnifiedResponse 的 usage 退回 camelCase, 只留 JsonUtil 那份会让
    // /v1/embeddings 对外的 usage 字段退回 camelCase, 两者都是对外契约变更。
    @JsonProperty("prompt_tokens")
    @JsonSerialize(name = "prompt_tokens")
    @JsonDeserialize(name = "prompt_tokens")
    private Integer promptTokens;
    @JsonProperty("completion_tokens")
    @JsonSerialize(name = "completion_tokens")
    @JsonDeserialize(name = "completion_tokens")
    private Integer completionTokens;
    @JsonProperty("total_tokens")
    @JsonSerialize(name = "total_tokens")
    @JsonDeserialize(name = "total_tokens")
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