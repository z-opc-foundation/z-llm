package com.zifang.z.llm.core.service;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.llm.api.dto.Choice;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.dto.UsageInfo;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 核心编排服务 — 把 UnifiedRequest 转为 kernel ChatCompletionsRequest, 调用 provider, 再转回 UnifiedResponse.
 *
 * <p>所有对外 controller (OpenAI / Anthropic) 最终都汇聚到本服务.
 */
public class ChatGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ChatGatewayService.class);

    private final LlmProviderRegistry providerRegistry;
    private final LlmCredentialStore credentialStore;

    public ChatGatewayService(LlmProviderRegistry providerRegistry, LlmCredentialStore credentialStore) {
        this.providerRegistry = providerRegistry;
        this.credentialStore = credentialStore;
    }

    /** 同步 chat. */
    public UnifiedResponse chat(UnifiedRequest request) {
        LlmProvider provider = pickProvider(request.getModel());
        ChatCompletionsRequest kernelReq = toKernelRequest(request);
        ChatCompletionsResponse kernelResp = provider.chat(kernelReq);
        return fromKernelResponse(kernelResp);
    }

    /** 流式 chat. 每个增量 chunk 通过 onChunk 吐给下游. */
    public void streamChat(UnifiedRequest request,
                           Consumer<UnifiedStreamChunk> onChunk,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {
        LlmProvider provider = pickProvider(request.getModel());
        ChatCompletionsRequest kernelReq = toKernelRequest(request);
        provider.streamChat(kernelReq,
                chunk -> {
                    String fr = chunk.getFinishReason();
                    onChunk.accept(fromKernelChunk(chunk));
                    if (fr != null && !fr.isEmpty() && onComplete != null) {
                        onComplete.run();
                    }
                },
                err -> onError.accept(err));
    }

    // ---- model 解析 ----

    /**
     * 由 "openai/gpt-4o" 形式拆出 vendor + model id, 选 provider.
     * 不带 vendor 前缀时 (直接 "gpt-4o"), 不识别, 报 404.
     */
    private LlmProvider pickProvider(String fullModel) {
        if (fullModel == null || fullModel.isEmpty()) {
            throw GatewayException.modelNotFound(String.valueOf(fullModel));
        }
        Vendor vendor = Vendor.fromModel(fullModel);
        if (vendor == null) {
            throw GatewayException.modelNotFound(fullModel);
        }
        LlmProvider p = providerRegistry.pick(vendor);
        if (p == null) {
            throw GatewayException.modelNotFound(fullModel);
        }
        // 模型支持性校验 (仅 debug, 让 vendor 自行决定)
        String bare = stripVendor(fullModel);
        if (!providerSupportsModel(p, bare)) {
            log.debug("Provider {} did not claim model {} via supportsModel()", vendor.code(), bare);
        }
        return p;
    }

    private static String stripVendor(String fullModel) {
        int slash = fullModel.indexOf('/');
        return slash < 0 ? fullModel : fullModel.substring(slash + 1);
    }

    private static boolean providerSupportsModel(LlmProvider p, String modelId) {
        try {
            return p.supportsModel(modelId);
        } catch (Exception ignore) {
            return true;
        }
    }

    // ---- UnifiedRequest → kernel ChatCompletionsRequest ----

    private ChatCompletionsRequest toKernelRequest(UnifiedRequest req) {
        List<Msg> msgs = new ArrayList<>();
        if (req.getMessages() != null) {
            for (UnifiedMessage m : req.getMessages()) {
                msgs.add(toKernelMessage(m));
            }
        }
        List<Tool> tools = null;
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            tools = new ArrayList<>();
            for (com.zifang.z.llm.api.dto.ToolSpec ts : req.getTools()) {
                if (ts == null || ts.getFunction() == null) continue;
                tools.add(new SchemaOnlyTool(
                        ts.getFunction().getName(),
                        ts.getFunction().getDescription(),
                        ts.getFunction().getParameters()));
            }
        }
        return new ChatCompletionsRequest(
                stripVendor(req.getModel()),
                msgs,
                tools,
                req.getTemperature(),
                req.getTopP(),
                req.getMaxTokens(),
                Boolean.TRUE.equals(req.getStream()),
                null);
    }

    private Msg toKernelMessage(UnifiedMessage m) {
        MessageRole role = parseRole(m.getRole());
        List<ToolCall> tcs = Collections.emptyList();
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            tcs = new ArrayList<>();
            for (com.zifang.z.llm.api.dto.ToolCall utc : m.getToolCalls()) {
                String args = utc.getFunction() == null ? "{}" :
                        (utc.getFunction().getArguments() == null ? "{}" : utc.getFunction().getArguments());
                tcs.add(new ToolCall(utc.getId(),
                        utc.getFunction() == null ? null : utc.getFunction().getName(),
                        args));
            }
        }
        MessageType type = MessageType.TEXT;
        if (role == MessageRole.TOOL) {
            type = MessageType.TOOL_RESULT;
        } else if (!tcs.isEmpty()) {
            type = MessageType.TOOL_CALL;
        }
        return new Msg(role, m.getName(), m.getContent(), type,
                m.getToolCallId(), tcs, Collections.emptyMap());
    }

    private MessageRole parseRole(String role) {
        if (role == null) return MessageRole.USER;
        switch (role.toLowerCase()) {
            case "system": return MessageRole.SYSTEM;
            case "user": return MessageRole.USER;
            case "assistant": return MessageRole.ASSISTANT;
            case "tool": return MessageRole.TOOL;
            case "function": return MessageRole.FUNCTION;
            default: return MessageRole.USER;
        }
    }

    // ---- kernel 响应 → Unified ----

    private UnifiedResponse fromKernelResponse(ChatCompletionsResponse r) {
        UnifiedResponse out = new UnifiedResponse();
        out.setId(r.getId());
        out.setModel(r.getModel());
        out.setCreated(System.currentTimeMillis() / 1000L);
        out.setObject("chat.completion");
        List<Choice> choices = new ArrayList<>();
        if (r.getChoices() != null) {
            for (ChatCompletionsResponse.Choice c : r.getChoices()) {
                choices.add(toApiChoice(c));
            }
        }
        out.setChoices(choices);
        TokenUsage u = r.getUsage();
        if (u != null) {
            out.setUsage(new UsageInfo(
                    safeInt(u.getPromptTokens()),
                    safeInt(u.getCompletionTokens()),
                    safeInt(u.getTotalTokens())));
        }
        return out;
    }

    private UnifiedStreamChunk fromKernelChunk(ChatCompletionsResponse c) {
        UnifiedStreamChunk chunk = new UnifiedStreamChunk();
        chunk.setId(c.getId());
        chunk.setModel(c.getModel());
        chunk.setCreated(System.currentTimeMillis() / 1000L);
        chunk.setObject("chat.completion.chunk");
        List<Choice> choices = new ArrayList<>();
        if (c.getChoices() != null) {
            for (ChatCompletionsResponse.Choice kc : c.getChoices()) {
                Choice ch = new Choice();
                ch.setIndex(kc.getIndex());
                ch.setFinishReason(kc.getFinishReason());
                Choice.Delta d = new Choice.Delta();
                d.setRole("assistant");
                if (kc.getContent() != null && !kc.getContent().isEmpty()) {
                    d.setContent(kc.getContent());
                }
                if (kc.getToolCalls() != null && !kc.getToolCalls().isEmpty()) {
                    List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
                    for (ToolCall tc : kc.getToolCalls()) {
                        tcs.add(toApiToolCall(tc));
                    }
                    d.setToolCalls(tcs);
                }
                ch.setDelta(d);
                choices.add(ch);
            }
        }
        chunk.setChoices(choices);
        return chunk;
    }

    private Choice toApiChoice(ChatCompletionsResponse.Choice c) {
        Choice ch = new Choice();
        ch.setIndex(c.getIndex());
        ch.setFinishReason(c.getFinishReason());
        UnifiedMessage um = new UnifiedMessage();
        um.setRole("assistant");
        um.setContent(c.getContent());
        if (c.getToolCalls() != null && !c.getToolCalls().isEmpty()) {
            List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
            for (ToolCall tc : c.getToolCalls()) {
                tcs.add(toApiToolCall(tc));
            }
            um.setToolCalls(tcs);
        }
        ch.setMessage(um);
        return ch;
    }

    private com.zifang.z.llm.api.dto.ToolCall toApiToolCall(ToolCall tc) {
        com.zifang.z.llm.api.dto.ToolCall utc = new com.zifang.z.llm.api.dto.ToolCall();
        utc.setId(tc.getId());
        utc.setType("function");
        com.zifang.z.llm.api.dto.ToolCall.FunctionCall fn = new com.zifang.z.llm.api.dto.ToolCall.FunctionCall();
        fn.setName(tc.getName());
        fn.setArguments(tc.getArgumentsJson());
        utc.setFunction(fn);
        return utc;
    }

    private static Integer safeInt(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, v));
    }
}