package com.zifang.z.llm.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * POST /v1/embeddings 请求体.
 *
 * <p>{@code input} 既可以是单个字符串也可以是字符串数组 (OpenAI 协议两种都收),
 * 因此用 Object 承载并由 {@link #inputs()} 归一, 避免为两种写法拆两套 DTO.
 */
public class EmbeddingsRequest {

    private String model;
    private Object input;

    private Integer dimensions;

    // 唯一残留的 Jackson 注解, 且是本仓仅剩的一处: 请求侧走 @RequestBody, 由 Spring MVC 的
    // 消息转换器隐式反序列化 —— 正是"Jackson 只活在 controller 层"的那一层。
    // 出站侧(EmbeddingsResponse)已改由 controller 显式 toJsonExcludingNulls, 不再经 Jackson。
    // 删不掉的原因是它是承重的: EmbeddingService 把这个值原样转发给上游, 而线上客户端
    // 按 OpenAI 协议发的是下划线 spelling。改成字段改名会连带 getEncoding_format() 这种
    // 非法访问器名, 得不偿失。
    @com.fasterxml.jackson.annotation.JsonProperty("encoding_format")
    private String encodingFormat;

    private String user;

    public EmbeddingsRequest() {
    }

    public EmbeddingsRequest(String model, List<String> inputs) {
        this.model = model;
        this.input = inputs;
    }

    /** 归一化输入: 单串 → 单元素列表. 空/缺失返回空列表, 由调用方判错. */
    public List<String> inputs() {
        if (input == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        if (input instanceof String) {
            String s = ((String) input).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
            return out;
        }
        if (input instanceof List) {
            for (Object o : (List<?>) input) {
                if (o instanceof String && !((String) o).trim().isEmpty()) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public String getEncodingFormat() {
        return encodingFormat;
    }

    public void setEncodingFormat(String encodingFormat) {
        this.encodingFormat = encodingFormat;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }
}
