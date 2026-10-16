package com.zifang.z.llm.api.dto;

import com.zifang.util.json.annotation.JsonDeserialize;
import com.zifang.util.json.annotation.JsonSerialize;

/**
 * 用量信息 — 各 vendor 字段基本对齐 (prompt / completion / total).
 *
 * <p>流式 chunk 中部分 vendor 不带 usage, 只在最后一个 chunk 或非流式响应里给.
 */
public class UsageInfo {

    // 两套序列化器的夹缝已消除: /v1/embeddings 原本是 MVC 的 ResponseEntity (要 Jackson 注解),
    // UnifiedResponse / UnifiedStreamChunk 走 JsonUtil (要 z-util 注解), 两边各钉一份才不出错。
    // 现在 /v1/embeddings 改由 OpenAIController 显式 toJsonExcludingNulls, 与另两条路径同源,
    // Jackson 那份已成死码, 只留 z-util 这套。
    @JsonSerialize(name = "prompt_tokens")
    @JsonDeserialize(name = "prompt_tokens")
    private Integer promptTokens;
    @JsonSerialize(name = "completion_tokens")
    @JsonDeserialize(name = "completion_tokens")
    private Integer completionTokens;
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