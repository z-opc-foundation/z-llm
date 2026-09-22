package com.zifang.z.llm.api.dto;

import java.util.Map;

/**
 * 工具定义 — 给模型可用的工具清单.
 *
 * <p>OpenAI 协议: { type: "function", function: { name, description, parameters } }
 * <p>Anthropic 协议: { name, description, input_schema } — mapper 层转.
 */
public class ToolSpec {

    /** 固定 "function". */
    private String type;

    /** OpenAI 协议用 function 嵌套结构. */
    private FunctionSpec function;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionSpec getFunction() {
        return function;
    }

    public void setFunction(FunctionSpec function) {
        this.function = function;
    }

    public static class FunctionSpec {
        private String name;
        private String description;

        /** JSON Schema (Map 表示, 序列化为 JSON 对象). */
        private Map<String, Object> parameters;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }
}