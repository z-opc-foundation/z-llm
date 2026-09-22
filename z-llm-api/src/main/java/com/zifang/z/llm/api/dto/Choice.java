package com.zifang.z.llm.api.dto;

/**
 * 单 choice — 对应一次候选结果.
 *
 * <p>非流式: message 字段填充完整回复.
 * <p>流式: message 字段为 null, delta 字段填充增量.
 */
public class Choice {

    /** choice 索引 (多数 vendor 只有一个, index=0). */
    private int index;

    /** 完整消息 (非流式). */
    private UnifiedMessage message;

    /** 增量 (流式). */
    private Delta delta;

    /** 停止原因: "stop" / "length" / "tool_calls" / "content_filter" 等. */
    private String finishReason;

    public Choice() {
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public UnifiedMessage getMessage() {
        return message;
    }

    public void setMessage(UnifiedMessage message) {
        this.message = message;
    }

    public Delta getDelta() {
        return delta;
    }

    public void setDelta(Delta delta) {
        this.delta = delta;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    /**
     * 流式增量 — 比 UnifiedMessage 简化, 只保留可增量更新的字段.
     */
    public static class Delta {
        private String role;
        private String content;
        private java.util.List<ToolCall> toolCalls;

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

        public java.util.List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(java.util.List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }
}