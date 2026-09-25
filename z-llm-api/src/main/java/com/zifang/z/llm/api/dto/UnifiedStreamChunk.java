package com.zifang.z.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * SSE 流式增量 — 网关吐给下游的一片内容.
 *
 * <p>OpenAI 协议下每片就是一个 ChatCompletionChunk.
 * <p>Anthropic 协议下经 mapper 转成同结构 (event + delta).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedStreamChunk {

    private String id;
    private String model;
    private Long created;
    @JsonProperty("object")
    private String object;

    /**
     * 末片 usage — OpenAI 只有在请求带 stream_options.include_usage 时才回,
     * 此前本类根本没有该字段, 于是上游给的流式用量被丢掉, 客户端与网关都记不到账.
     */
    @JsonProperty("usage")
    private UsageInfo usage;

    /** 单 choice 增量 (含 delta). */
    private List<Choice> choices;

    public UnifiedStreamChunk() {
    }

    public UsageInfo getUsage() {
        return usage;
    }

    public void setUsage(UsageInfo usage) {
        this.usage = usage;
    }

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
}