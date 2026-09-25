package com.zifang.z.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 平台无关的统一响应结构 — 非流式场景.
 *
 * <p>流式场景用 UnifiedStreamChunk, 一片片吐出增量 delta.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedResponse {

    /** 响应唯一 id (provider 生成, 透传). */
    private String id;

    /** 实际下发模型 (provider 透传). */
    private String model;

    /** 创建时间戳 (epoch seconds). */
    private Long created;

    /** 对象类型 ("chat.completion"). */
    private String object;

    /** 备选结果 (多数 vendor 只有一个 choice). */
    private List<Choice> choices;

    /** 用量. */
    private UsageInfo usage;

    /** 系统指纹 (OpenAI "sf", 可选). */
    @JsonProperty("system_fingerprint")
    private String systemFingerprint;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public UsageInfo getUsage() {
        return usage;
    }

    public void setUsage(UsageInfo usage) {
        this.usage = usage;
    }

    public String getSystemFingerprint() {
        return systemFingerprint;
    }

    public void setSystemFingerprint(String systemFingerprint) {
        this.systemFingerprint = systemFingerprint;
    }
}