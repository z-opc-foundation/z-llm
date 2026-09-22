package com.zifang.z.llm.core.service;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;

import java.util.Map;

/**
 * Tool SPI 适配器 — 仅承载 schema 给 LLM 看, 不实际执行 (执行由网关外的 agent 框架负责).
 */
public class SchemaOnlyTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;

    public SchemaOnlyTool(String name, String description, Map<String, Object> schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * 网关层不实际执行工具 — 仅做 schema 透传.
     * 若有下游 agent 接管, 不会进入本方法.
     */
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        throw new UnsupportedOperationException(
                "SchemaOnlyTool is schema-only. Tool execution must be handled by downstream agent.");
    }
}