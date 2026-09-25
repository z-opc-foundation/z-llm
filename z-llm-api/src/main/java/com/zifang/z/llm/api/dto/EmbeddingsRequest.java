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

    @com.fasterxml.jackson.annotation.JsonProperty("dimensions")
    private Integer dimensions;

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
