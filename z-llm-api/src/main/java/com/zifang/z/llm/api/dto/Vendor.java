package com.zifang.z.llm.api.dto;

/**
 * Vendor 枚举 — 网关识别的 6 家 vend 后端.
 *
 * <p>与 z-agent-kernel-llm 的 provider 名一一对应:
 * openai / anthropic / deepseek / qwen / dashscope / gemini.
 *
 * <p>模型命名空间约定: "{vendor}/{model}", 如 "openai/gpt-4o", "anthropic/claude-3-5-sonnet-latest".
 */
public enum Vendor {

    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    DEEPSEEK("deepseek"),
    QWEN("qwen"),
    DASHSCOPE("dashscope"),
    GEMINI("gemini");

    private final String code;

    Vendor(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 由 "openai/gpt-4o" 形式拆出 vendor 段. 未命中返回 null. */
    public static Vendor fromModel(String fullModel) {
        if (fullModel == null) {
            return null;
        }
        int slash = fullModel.indexOf('/');
        String prefix = slash < 0 ? fullModel : fullModel.substring(0, slash);
        for (Vendor v : values()) {
            if (v.code.equalsIgnoreCase(prefix)) {
                return v;
            }
        }
        return null;
    }
}