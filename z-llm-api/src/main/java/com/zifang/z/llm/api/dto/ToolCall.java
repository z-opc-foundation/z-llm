package com.zifang.z.llm.api.dto;

import java.util.List;

/**
 * 工具调用 — assistant 角色发起的工具调用请求.
 *
 * <p>OpenAI 协议: { id, type: "function", function: { name, arguments(str) } }
 * <p>Anthropic 协议: content block 中 { type: "tool_use", id, name, input } — mapper 层转.
 */
public class ToolCall {

    private String id;
    private String type;

    /** OpenAI 协议嵌套结构. */
    private FunctionCall function;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionCall getFunction() {
        return function;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
    }

    public static class FunctionCall {
        private String name;

        /** 参数 JSON 字符串 (OpenAI 协议是 str). */
        private String arguments;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }
}