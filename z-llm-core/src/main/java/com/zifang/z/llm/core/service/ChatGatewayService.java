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
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Choice;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.dto.UsageInfo;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.params.ParamTranslator;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.upstream.UpstreamEndpoints;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 核心编排服务 — 把 UnifiedRequest 转为 kernel ChatCompletionsRequest, 调用 provider, 再转回 UnifiedResponse.
 *
 * <p>所有对外 controller (OpenAI / Anthropic) 最终都汇聚到本服务.
 *
 * <p>职责链: 模型路由 → 授权 → 参数下沉 → 跨凭据 failover → 协议回转 → 用量记账.
 */
public class ChatGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ChatGatewayService.class);

    /** in-process 调用 (无外部 ApiKey) 的记账主体. */
    private static final ApiKey INTERNAL_CALLER = internalCaller();

    private static ApiKey internalCaller() {
        ApiKey k = new ApiKey();
        k.setId("internal");
        k.setKey("internal");
        k.setStatus("active");
        return k;
    }

    private final LlmProviderRegistry providerRegistry;
    private final LlmCredentialStore credentialStore;
    private final ModelRouter router;
    private final ProviderInvoker invoker;
    private final UsageLedger usageLedger;
    private final RateLimiter rateLimiter;
    private final AccessControl accessControl;
    private final com.zifang.z.llm.core.properties.GatewayProperties properties;

    /** 可选协作者: 带图片的请求改由它直连上游 (kernel provider 传不了多模态). */
    private MultimodalChatRelay relay;

    public ChatGatewayService(LlmProviderRegistry providerRegistry,
                              LlmCredentialStore credentialStore,
                              ModelRouter router,
                              ProviderInvoker invoker,
                              UsageLedger usageLedger,
                              RateLimiter rateLimiter,
                              AccessControl accessControl,
                              com.zifang.z.llm.core.properties.GatewayProperties properties) {
        this.providerRegistry = providerRegistry;
        this.credentialStore = credentialStore;
        this.router = router;
        this.invoker = invoker;
        this.usageLedger = usageLedger;
        this.rateLimiter = rateLimiter;
        this.accessControl = accessControl;
        this.properties = properties;
    }

    /** 一次调用的结果 + 记账所需上下文. */
    public static final class ChatOutcome {
        private final UnifiedResponse response;
        private final UsageLedger.Record usage;
        private final Vendor vendor;
        private final String credential;

        ChatOutcome(UnifiedResponse response, UsageLedger.Record usage, Vendor vendor, String credential) {
            this.response = response;
            this.usage = usage;
            this.vendor = vendor;
            this.credential = credential;
        }

        public UnifiedResponse response() {
            return response;
        }

        public UsageLedger.Record usage() {
            return usage;
        }

        public Vendor vendor() {
            return vendor;
        }

        public String credential() {
            return credential;
        }
    }

    /** 流式调用的结果 (usage 在流结束后才知道). */
    public static final class StreamOutcome {
        private final UsageLedger.Record usage;
        private final Vendor vendor;
        private final String credential;
        private final int chunks;

        StreamOutcome(UsageLedger.Record usage, Vendor vendor, String credential, int chunks) {
            this.usage = usage;
            this.vendor = vendor;
            this.credential = credential;
            this.chunks = chunks;
        }

        public UsageLedger.Record usage() {
            return usage;
        }

        public Vendor vendor() {
            return vendor;
        }

        public String credential() {
            return credential;
        }

        public int chunks() {
            return chunks;
        }
    }

    /**
     * in-process 调用方兼容入口 (z-team / z-agent / z-lc 直接注入本服务, 不经过 HTTP 鉴权).
     *
     * <p>这类调用没有外部 ApiKey, 统一记到一个 internal 主体上: 仍受网关 max_tokens 上限约束、
     * 仍进用量台账, 只是不参与按 key 的白名单与限流.
     */
    public UnifiedResponse chat(UnifiedRequest request) {
        return chat(request, INTERNAL_CALLER).response();
    }

    public StreamOutcome streamChat(UnifiedRequest request,
                                    Consumer<UnifiedStreamChunk> onChunk,
                                    Consumer<Throwable> onError,
                                    Runnable onComplete) {
        return streamChat(request, INTERNAL_CALLER, onChunk, onError, onComplete);
    }

    /** 同步 chat. */
    public ChatOutcome chat(UnifiedRequest request, ApiKey key) {
        ModelRouter.Resolved resolved = prepare(request, key);
        if (useRelay(request, resolved)) {
            MultimodalChatRelay.RelayResult r = relay.chat(request, resolved, key);
            return new ChatOutcome(r.response(), r.usage(), resolved.vendor(), r.credential());
        }
        ChatCompletionsRequest kernelReq = toKernelRequest(request, resolved, false);

        final Vendor vendor = resolved.vendor();
        ProviderInvoker.Handle[] served = new ProviderInvoker.Handle[1];
        ChatCompletionsResponse kernelResp = invoker.execute(vendor,
                invoker.attemptsFor(vendor),
                h -> {
                    served[0] = h;
                    return h.provider().chat(kernelReq);
                });

        UnifiedResponse out = fromKernelResponse(kernelResp, resolved);
        UsageLedger.Record rec = settle(key, vendor, resolved.bareModel(), out.getUsage());
        return new ChatOutcome(out, rec, vendor, served[0] == null ? null : served[0].alias());
    }

    /**
     * 流式 chat. 每个增量 chunk 通过 onChunk 吐给下游.
     *
     * <p>与同步版的差别: 只能在"还没吐出任何 chunk"时安全换凭据, 一旦向客户端吐过内容
     * 再切凭据会让客户端收到两段互相矛盾的回复, 所以中途失败只能报错收尾.
     *
     * <p>onComplete 保证"至多且恰好一次": 上游不给 finish_reason 时, provider.streamChat
     * 正常返回同样收尾, 否则客户端会一直等 [DONE] 直到读超时.
     */
    public StreamOutcome streamChat(UnifiedRequest request,
                                    ApiKey key,
                                    Consumer<UnifiedStreamChunk> onChunk,
                                    Consumer<Throwable> onError,
                                    Runnable onComplete) {
        ModelRouter.Resolved resolved = prepare(request, key);
        final Vendor vendor = resolved.vendor();
        if (useRelay(request, resolved)) {
            MultimodalChatRelay.RelayResult r = relay.streamChat(
                    request, resolved, key, onChunk, onError, onComplete);
            return new StreamOutcome(r.usage(), vendor, r.credential(), r.chunks());
        }
        ChatCompletionsRequest kernelReq = toKernelRequest(request, resolved, true);

        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean emitted = new AtomicBoolean(false);
        final long[] usage = new long[3]; // prompt, completion, seen(flag)
        final String[] modelName = new String[1];
        final int[] chunkCount = new int[1];

        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        };

        List<ProviderInvoker.Handle> pool = invoker.candidatesForFailover(vendor);
        if (pool.isEmpty()) {
            throw GatewayException.internal("No active credential for vendor " + vendor, null);
        }
        final int cap = Math.min(invoker.attemptsFor(vendor), pool.size());
        ProviderInvoker.Handle served = null;
        Throwable lastError = null;
        for (int i = 0; i < cap; i++) {
            ProviderInvoker.Handle h = pool.get(i);
            served = h;
            emitted.set(false);
            final Throwable[] asyncError = new Throwable[1];
            try {
                h.provider().streamChat(kernelReq,
                        chunk -> {
                            if (chunk == null) {
                                return;
                            }
                            TokenUsage tu = chunk.getUsage();
                            if (tu != null) {
                                usage[0] = tu.getPromptTokens();
                                usage[1] = tu.getCompletionTokens();
                                usage[2] = 1L;
                            }
                            if (chunk.getModel() != null) {
                                modelName[0] = chunk.getModel();
                            }
                            chunkCount[0]++;
                            emitted.set(true);
                            onChunk.accept(fromKernelChunk(chunk, resolved));
                            String fr = chunkFinishReason(chunk);
                            if (fr != null) {
                                finish.run();
                            }
                        },
                        // 上游的失败是通过 onError 回调报出来的 (provider 不抛异常),
                        // 因此这里只暂存, 由外层循环决定"换凭据重试"还是"向客户端报错".
                        err -> asyncError[0] = err);
            } catch (Throwable t) {
                asyncError[0] = t;
            }

            if (asyncError[0] == null) {
                lastError = null;
                invoker.reportSuccess(h);
                finish.run();
                break;
            }
            lastError = asyncError[0];
            if (emitted.get() || !ProviderInvoker.retryable(asyncError[0])) {
                // 已经向客户端吐过内容再换凭据, 会让回复变成两段互相矛盾的内容,
                // 所以这种失败只能就地报错收尾.
                invoker.reportFailure(h, asyncError[0]);
                usageLedger.recordFailure(key);
                notifyErrorOnce(onError, finished, asyncError[0]);
                throw GatewayException.upstreamFailed(
                        "stream failed: " + asyncError[0].getMessage(), asyncError[0]);
            }
            log.warn("stream attempt on {} failed before first chunk, failing over: {}",
                    h.key(), asyncError[0].getMessage());
        }

        // 整池凭据都在首 chunk 之前失败: 绝不能当成"成功但 0 chunk"返回,
        // 否则客户端拿到一个空流而无人报错.
        if (lastError != null) {
            usageLedger.recordFailure(key);
            notifyErrorOnce(onError, finished, lastError);
            throw GatewayException.upstreamFailed(
                    "stream failed after all credentials: " + lastError.getMessage(), lastError);
        }

        UsageInfo seen = usage[2] == 1L
                ? new UsageInfo((int) Math.min(Integer.MAX_VALUE, usage[0]),
                        (int) Math.min(Integer.MAX_VALUE, usage[1]),
                        (int) Math.min(Integer.MAX_VALUE, usage[0] + usage[1]))
                : null;
        UsageLedger.Record rec = settle(key, vendor, resolved.bareModel(), seen);
        if (modelName[0] != null && log.isTraceEnabled()) {
            log.trace("stream served model {} chunks {}", modelName[0], chunkCount[0]);
        }
        return new StreamOutcome(rec, vendor, served == null ? null : served.alias(), chunkCount[0]);
    }

    private static String chunkFinishReason(ChatCompletionsResponse chunk) {
        String fr = chunk.getFinishReason();
        if (fr != null && !fr.isEmpty()) {
            return fr;
        }
        if (chunk.getChoices() != null) {
            for (ChatCompletionsResponse.Choice c : chunk.getChoices()) {
                if (c != null && c.getFinishReason() != null && !c.getFinishReason().isEmpty()) {
                    return c.getFinishReason();
                }
            }
        }
        return null;
    }

    /** 只有"尚未收尾"时才对客户端报错, 保证 onError 也至多一次. */
    private static void notifyErrorOnce(Consumer<Throwable> onError, AtomicBoolean finished, Throwable t) {
        if (finished.compareAndSet(false, true) && onError != null) {
            onError.accept(t);
        }
    }

    // ---- 前置: 路由 + 授权 ----

    /** 注入多模态直连面 (可选; 不注入时带图请求按降级/报错两条老路走). */
    public void setRelay(MultimodalChatRelay relay) {
        this.relay = relay;
    }

    /**
     * 本次请求是否该绕过 kernel provider 直连上游.
     *
     * <p>只有"请求里确实带图片"才值得绕开主链路 —— 纯文本请求仍走 provider, 免得两条路
     * 的鉴权/重试行为出现无谓差异。
     *
     * @throws GatewayException 带图但无法直连, 且部署方没打开摊平降级时
     */
    private boolean useRelay(UnifiedRequest request, ModelRouter.Resolved resolved) {
        if (!MultimodalChatRelay.carriesNonTextParts(request)) {
            return false;
        }
        boolean canRelay = relay != null
                && properties != null && properties.isRelayMultimodal()
                && UpstreamEndpoints.canRelayChat(resolved.vendor());
        if (canRelay) {
            return true;
        }
        if (properties != null && properties.isAllowMultimodalDowngrade()) {
            return false;
        }
        throw GatewayException.invalidRequest(
                "request carries image parts but vendor " + resolved.vendor().code()
                        + " cannot receive them (the gateway relays images for openai/deepseek/qwen"
                        + " and anthropic; set z.llm.allow-multimodal-downgrade=true to accept"
                        + " text-only flattening)");
    }

    private ModelRouter.Resolved prepare(UnifiedRequest request, ApiKey key) {
        if (request == null) {
            throw GatewayException.invalidRequest("empty request");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw GatewayException.invalidRequest("'messages' must contain at least one message");
        }
        ModelRouter.Resolved resolved = router.resolve(request.getModel());
        if (accessControl != null) {
            accessControl.authorize(key, resolved, request.getMaxTokens());
        }
        // 路由结果回写, 后续 kernel 转换按裸 model 下发.
        request.setModel(resolved.canonical());
        return resolved;
    }

    // ---- 记账 ----

    private UsageLedger.Record settle(ApiKey key, Vendor vendor, String bareModel, UsageInfo u) {
        long prompt = u == null || u.getPromptTokens() == null ? 0L : u.getPromptTokens();
        long completion = u == null || u.getCompletionTokens() == null ? 0L : u.getCompletionTokens();
        UsageLedger.Record rec = usageLedger.record(key, vendor, bareModel, prompt, completion);
        if (rateLimiter != null && key != null) {
            rateLimiter.recordTokenUsage(key, prompt + completion);
        }
        return rec;
    }

    // ---- UnifiedRequest → kernel ChatCompletionsRequest ----

    private ChatCompletionsRequest toKernelRequest(UnifiedRequest req,
                                                  ModelRouter.Resolved resolved,
                                                  boolean stream) {
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
        Map<String, Object> providerParams =
                new LinkedHashMap<>(ParamTranslator.forVendor(resolved.vendor(), req));
        if (stream && properties != null && properties.isInjectStreamUsage()) {
            // OpenAI 只有在带 stream_options.include_usage 时才会在末片回 usage,
            // 否则流式请求的 token 记账永远是 0 —— 由部署方按上游能力显式打开.
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("include_usage", true);
            providerParams.put("stream_options", so);
        }
        return new ChatCompletionsRequest(
                resolved.bareModel(),
                msgs,
                tools,
                req.getTemperature(),
                req.getTopP(),
                req.getMaxTokens(),
                stream,
                providerParams);
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
        return new Msg(role, m.getName(), textOf(m), type,
                m.getToolCallId(), tcs, Collections.emptyMap());
    }

    /**
     * 多模态摊平路径: kernel 的 provider 只把 {@code Msg.content} 当文本下发 (它不读
     * {@code Msg.metadata}), 所以走这条路的图片必然到不了模型。
     * 默认不会走到这里 —— {@code useRelay} 会先要求直连或显式报错, 只有部署方打开
     * {@code z.llm.allow-multimodal-downgrade} 才允许静默丢图。
     */
    private String textOf(UnifiedMessage m) {
        List<ContentPart> parts = m.getContents();
        if (parts == null || parts.isEmpty()) {
            return m.getContent();
        }
        StringBuilder sb = new StringBuilder();
        List<String> dropped = new ArrayList<>();
        for (ContentPart p : parts) {
            if (p == null) {
                continue;
            }
            if (p.getText() != null && !p.getText().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p.getText());
            } else if (p.getImageUrl() != null) {
                dropped.add("image_url");
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("message role={} carried {} non-text part(s) {} which were flattened to text "
                            + "because z.llm.allow-multimodal-downgrade is on — the model never saw "
                            + "them, so the answer is text-only by construction",
                    m.getRole(), dropped.size(), dropped);
        }
        return sb.length() == 0 ? m.getContent() : sb.toString();
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

    private UnifiedResponse fromKernelResponse(ChatCompletionsResponse r, ModelRouter.Resolved resolved) {
        UnifiedResponse out = new UnifiedResponse();
        out.setId(r.getId());
        // 对外回显调用方使用的命名空间 id, 而不是 vendor 内部裸 id.
        out.setModel(ModelNames.external(r.getModel(), resolved));
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

    private UnifiedStreamChunk fromKernelChunk(ChatCompletionsResponse c, ModelRouter.Resolved resolved) {
        UnifiedStreamChunk chunk = new UnifiedStreamChunk();
        chunk.setId(c.getId());
        // 流式与同步必须回显同一个 id: 此前这里直接透传上游裸名, 同一个模型经两条路
        // 会得到 "gpt-4o" 和 "openai/gpt-4o" 两种答案.
        chunk.setModel(ModelNames.external(c.getModel(), resolved));
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
        TokenUsage cu = c.getUsage();
        if (cu != null) {
            // 上游在末片回 usage 时必须透传给客户端, 否则流式调用对外永远没有用量.
            chunk.setUsage(new UsageInfo(safeInt(cu.getPromptTokens()),
                    safeInt(cu.getCompletionTokens()),
                    safeInt(cu.getTotalTokens())));
        }
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
