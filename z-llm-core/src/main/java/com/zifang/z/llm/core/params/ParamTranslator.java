package com.zifang.z.llm.core.params;

import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.Vendor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采样/控制参数按 vendor 协议下沉.
 *
 * <p>kernel 的 ChatCompletionsRequest 只显式承载 model/messages/tools/temperature/topP/maxTokens/stream,
 * 其余字段必须经 providerParams 透传 (provider 侧 body.putAll(providerParams)), 否则被静默丢弃.
 *
 * <p>各家 API 对未知字段严格程度不同 (Anthropic 会对多余 top-level 字段报 400),
 * 因此按 vendor 过滤 + 改名, 而不是无脑全量透传.
 */
public final class ParamTranslator {

    private ParamTranslator() {}

    /**
     * @return 需要经 providerParams 透传给该 vendor 的字段; 无字段时返回空 map.
     */
    public static Map<String, Object> forVendor(Vendor vendor, UnifiedRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (req == null) {
            return out;
        }
        switch (vendor) {
            case ANTHROPIC:
                putAnthropic(vendor, req, out);
                break;
            case GEMINI:
                putGemini(req, out);
                break;
            case DASHSCOPE:
                putDashScope(req, out);
                break;
            default:
                // openai / deepseek / qwen 走 OpenAI 兼容协议
                putOpenAiCompatible(req, out);
                break;
        }
        // 调用方显式配置的 vendor-specific 字段最后覆盖.
        Map<String, Object> extra = req.getExtra();
        if (extra != null && isOpenAiCompatible(vendor)) {
            // 只有 OpenAI 兼容协议才吃任意扩展字段; Anthropic/Gemini 对未知字段会报 400.
            out.putAll(extra);
        }
        return out;
    }

    private static boolean isOpenAiCompatible(Vendor vendor) {
        return vendor == Vendor.OPENAI || vendor == Vendor.DEEPSEEK || vendor == Vendor.QWEN;
    }

    private static void putOpenAiCompatible(UnifiedRequest req, Map<String, Object> out) {
        copyIfPresent(out, "stop", req.getStop());
        copyIfPresent(out, "tool_choice", req.getToolChoice());
        copyIfPresent(out, "presence_penalty", req.getPresencePenalty());
        copyIfPresent(out, "frequency_penalty", req.getFrequencyPenalty());
        copyIfPresent(out, "user", req.getUser());
    }

    private static void putAnthropic(Vendor vendor, UnifiedRequest req, Map<String, Object> out) {
        // Anthropic 用 stop_sequences 而非 stop.
        List<String> stop = req.getStop();
        if (stop != null && !stop.isEmpty()) {
            out.put("stop_sequences", stop);
        }
        copyIfPresent(out, "tool_choice", req.getToolChoice());
        // presence/frequency_penalty 与 user 在 Messages API 里不存在, 不透传以免 400.
    }

    private static void putGemini(UnifiedRequest req, Map<String, Object> out) {
        // GeminiProvider 把这些塞进 generationConfig.
        List<String> stop = req.getStop();
        if (stop != null && !stop.isEmpty()) {
            out.put("stopSequences", stop);
        }
        copyIfPresent(out, "presencePenalty", req.getPresencePenalty());
        copyIfPresent(out, "frequencyPenalty", req.getFrequencyPenalty());
    }

    private static void putDashScope(UnifiedRequest req, Map<String, Object> out) {
        // DashScopeProvider 把 providerParams 合并进 parameters 节点.
        copyIfPresent(out, "stop", req.getStop());
        copyIfPresent(out, "repetition_penalty", req.getFrequencyPenalty());
    }

    private static void copyIfPresent(Map<String, Object> out, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List && ((List<?>) value).isEmpty()) {
            return;
        }
        out.put(key, value);
    }
}
