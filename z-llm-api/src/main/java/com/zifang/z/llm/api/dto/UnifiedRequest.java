package com.zifang.z.llm.api.dto;

import java.util.List;

/**
 * 平台无关的统一请求结构 — 网关入口处的规范表达.
 *
 * <p>OpenAI / Anthropic 等协议的 mapper 把入参转成 UnifiedRequest,
 * 然后再由 core 内 LlmProviderRegistry 选择 provider 并下发.
 *
 * <p>字段命名取 OpenAI 协议习惯, 但含义覆盖所有 vendor.
 */
public class UnifiedRequest {

    /** 模型标识 (vendor 命名空间, 如 "openai/gpt-4o", "anthropic/claude-3-5-sonnet-latest"). */
    private String model;

    /** 消息列表 (system/user/assistant/tool). */
    private List<UnifiedMessage> messages;

    /** 采样温度 (0~2), null 由 provider 决定默认. */
    private Double temperature;

    /** top_p, null 由 provider 决定默认. */
    private Double topP;

    /** 最大输出 token, null 由 provider 决定默认. */
    private Integer maxTokens;

    /** 是否流式 (SSE). */
    private Boolean stream;

    /** 工具定义. */
    private List<ToolSpec> tools;

    /** 工具选择策略: "auto" / "none" / {"type":"function","function":{"name":"x"}}. */
    private Object toolChoice;

    /** 停止词列表. */
    private List<String> stop;

    /** 频率惩罚 (-2 ~ 2). */
    private Double frequencyPenalty;

    /** 存在惩罚 (-2 ~ 2). */
    private Double presencePenalty;

    /** 用户透传 id (用于日志/审计关联). */
    private String user;

    /** 额外透传字段 (provider-specific, 透传不解析). */
    private java.util.Map<String, Object> extra;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<UnifiedMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<UnifiedMessage> messages) {
        this.messages = messages;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public List<ToolSpec> getTools() {
        return tools;
    }

    public void setTools(List<ToolSpec> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public List<String> getStop() {
        return stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public java.util.Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(java.util.Map<String, Object> extra) {
        this.extra = extra;
    }
}