package com.zifang.z.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 统一消息 — role + content + (assistant 的 tool_calls / tool 的 tool_call_id).
 *
 * <p>role 取值: "system" / "user" / "assistant" / "tool".
 * <p>content 可以是纯文本 string, 也可以是 content parts 列表 (多模态).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedMessage {

    /** 角色: system / user / assistant / tool. */
    private String role;

    /** 文本内容 (纯文本场景). */
    private String content;

    /** 多模态内容片段列表 (与 content 二选一). */
    private List<ContentPart> contents;

    /** assistant 角色回复的工具调用. */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /** tool 角色消息关联的 tool_call id. */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** tool 角色消息关联的函数名 (OpenAI 协议). */
    private String name;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ContentPart> getContents() {
        return contents;
    }

    public void setContents(List<ContentPart> contents) {
        this.contents = contents;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}