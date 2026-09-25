package com.zifang.z.llm.api.dto;

import java.util.List;

/**
 * POST /v1/embeddings 响应体 — 与 OpenAI 线格式对齐, 这样任意 OpenAI 客户端
 * 把 base_url 指到网关就能直接拿向量.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class EmbeddingsResponse {

    private String object = "list";

    private List<Item> data;

    private String model;

    @com.fasterxml.jackson.annotation.JsonProperty("usage")
    private UsageInfo usage;

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<Item> getData() {
        return data;
    }

    public void setData(List<Item> data) {
        this.data = data;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public UsageInfo getUsage() {
        return usage;
    }

    public void setUsage(UsageInfo usage) {
        this.usage = usage;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class Item {
        private String object = "embedding";
        private Integer index;
        private List<Double> embedding;

        public Item() {
        }

        public Item(Integer index, List<Double> embedding) {
            this.index = index;
            this.embedding = embedding;
        }

        public String getObject() {
            return object;
        }

        public void setObject(String object) {
            this.object = object;
        }

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public List<Double> getEmbedding() {
            return embedding;
        }

        public void setEmbedding(List<Double> embedding) {
            this.embedding = embedding;
        }
    }
}
