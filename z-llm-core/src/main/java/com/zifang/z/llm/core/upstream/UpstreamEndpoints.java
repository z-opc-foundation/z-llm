package com.zifang.z.llm.core.upstream;

import com.zifang.z.agent.kernel.llm.provider.AnthropicProvider;
import com.zifang.z.agent.kernel.llm.provider.DashScopeProvider;
import com.zifang.z.agent.kernel.llm.provider.DeepSeekProvider;
import com.zifang.z.agent.kernel.llm.provider.GeminiProvider;
import com.zifang.z.agent.kernel.llm.provider.OpenAIProvider;
import com.zifang.z.agent.kernel.llm.provider.QwenProvider;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游协议族与端点解析.
 *
 * <p>默认 base URL 一律引用 kernel provider 的常量, 不在网关里另抄一份 ——
 * 抄一份就会有第二份会过期的事实。
 *
 * <p>base URL 语义与 kernel provider 一致: 指向 <b>版本化根</b> (如 {@code https://api.openai.com/v1}),
 * 本类只负责在其后拼具体资源路径。
 */
public final class UpstreamEndpoints {

    private UpstreamEndpoints() {}

    /** vendor 的默认版本化根. */
    public static String defaultBaseUrl(Vendor vendor) {
        switch (vendor) {
            case OPENAI:
                return OpenAIProvider.DEFAULT_BASE_URL;
            case DEEPSEEK:
                return DeepSeekProvider.DEFAULT_BASE_URL;
            case QWEN:
                return QwenProvider.DEFAULT_BASE_URL;
            case DASHSCOPE:
                return DashScopeProvider.DEFAULT_BASE_URL;
            case GEMINI:
                return GeminiProvider.DEFAULT_BASE_URL;
            case ANTHROPIC:
            default:
                return AnthropicProvider.DEFAULT_BASE_URL;
        }
    }

    /** 该 vendor 能否被网关按 OpenAI 兼容协议直连 (chat 与 embeddings 共用这个判定). */
    public static boolean isOpenAiCompatible(Vendor vendor) {
        return vendor == Vendor.OPENAI || vendor == Vendor.DEEPSEEK || vendor == Vendor.QWEN;
    }

    /** 带图的 chat 请求能不能直连转发: OpenAI 兼容三家 + Anthropic 原生 Messages API. */
    public static boolean canRelayChat(Vendor vendor) {
        return isOpenAiCompatible(vendor) || vendor == Vendor.ANTHROPIC;
    }

    /** 该 vendor 有没有 embeddings 端点 (Anthropic 的 Messages API 没有向量接口). */
    public static boolean supportsEmbeddings(Vendor vendor) {
        return vendor != Vendor.ANTHROPIC;
    }

    public static String baseUrl(LlmCredential credential, Vendor vendor) {
        String configured = credential == null ? null : credential.getBaseUrl();
        String base = configured == null || configured.trim().isEmpty()
                ? defaultBaseUrl(vendor) : configured.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** OpenAI 兼容 chat/completions —— 只用于带图片 part 的转发场景. */
    public static String chatCompletionsUrl(LlmCredential credential, Vendor vendor) {
        if (!isOpenAiCompatible(vendor)) {
            throw GatewayException.invalidRequest(
                    "vendor " + vendor.code() + " is not OpenAI-compatible; cannot relay chat/completions");
        }
        return baseUrl(credential, vendor) + "/chat/completions";
    }

    /**
     * 带图 chat 转发的实际端点 —— OpenAI 兼容家走 {@code /chat/completions},
     * Anthropic 走原生 {@code /v1/messages} (它的 base 不带版本段, 与 kernel provider 一致).
     */
    public static String chatUrl(LlmCredential credential, Vendor vendor) {
        if (vendor == Vendor.ANTHROPIC) {
            return baseUrl(credential, vendor) + "/v1/messages";
        }
        return chatCompletionsUrl(credential, vendor);
    }

    /** embeddings 端点, 按各家真实协议分形. {@code bareModel} 只有 Gemini 用得上 (模型名在路径里). */
    public static String embeddingsUrl(LlmCredential credential, Vendor vendor, String bareModel) {
        String base = baseUrl(credential, vendor);
        switch (vendor) {
            case DASHSCOPE:
                // DashScope 原生 (非兼容模式) 的向量端点.
                return base + "/services/embeddings/text-embedding/text-embedding";
            case GEMINI:
                return base + "/v1beta/models/" + bareModel + ":batchEmbedContents";
            case ANTHROPIC:
                throw GatewayException.invalidRequest(
                        "vendor anthropic has no embeddings endpoint");
            default:
                return base + "/embeddings";
        }
    }

    /** 鉴权头 + 凭据上配置的透传头. */
    public static Map<String, String> headers(LlmCredential credential, Vendor vendor) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        String apiKey = credential == null ? null : credential.getApiKey();
        if (vendor == Vendor.GEMINI) {
            h.put("x-goog-api-key", apiKey == null ? "" : apiKey);
        } else if (vendor == Vendor.ANTHROPIC) {
            // Messages API 只认 x-api-key; 发 Bearer 会被当成缺 key.
            h.put("x-api-key", apiKey == null ? "" : apiKey);
            h.put("anthropic-version", AnthropicProvider.ANTHROPIC_VERSION);
        } else {
            h.put("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
        }
        Map<String, String> extra = credential == null ? null : credential.getExtraHeaders();
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    h.put(e.getKey(), e.getValue());
                }
            }
        }
        return h;
    }
}
