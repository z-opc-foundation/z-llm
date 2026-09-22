package com.zifang.z.llm.api.dto;

import java.util.List;

/**
 * SSE 流式增量 — 网关吐给下游的一片内容.
 *
 * <p>OpenAI 协议下每片就是一个 ChatCompletionChunk.
 * <p>Anthropic 协议下经 mapper 转成同结构 (event + delta).
 */
public class UnifiedStreamChunk {

    private String id;
    private String model;
    private Long created;
    private String object;

    /** 单 choice 增量 (含 delta). */
    private List<Choice> choices;

    public UnifiedStreamChunk() {
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