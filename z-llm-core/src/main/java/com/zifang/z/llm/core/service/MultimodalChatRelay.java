package com.zifang.z.llm.core.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Choice;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.ToolCall;
import com.zifang.z.llm.api.dto.ToolSpec;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.UnifiedStreamChunk;
import com.zifang.z.llm.api.dto.UsageInfo;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.params.ParamTranslator;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.upstream.UpstreamEndpoints;
import com.zifang.z.llm.core.upstream.UpstreamHttp;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 多模态直连转发 —— 把带图片 part 的请求按 vendor 真实协议发出去, 而不是摊平成文本.
 *
 * <p>存在理由: kernel 的 provider 只读 {@code Msg.content} 这一个字符串 (实测 kernel-llm
 * 全模块 0 处读 {@code Msg.getMetadata()}), 走它必然丢图。因此 openai / deepseek / qwen 由网关
 * 直连 {@code /chat/completions}, anthropic 直连原生 {@code /v1/messages}; 其余 vendor 交给
 * 调用方决定是报错还是摊平 (见 {@code z.llm.allow-multimodal-downgrade})。
 *
 * <p>凭据顺序、冷却与 failover 仍走 {@link ProviderInvoker}, 与 chat 主链路共用一套韧性判定。
 */
public class MultimodalChatRelay {

    private static final Logger log = LoggerFactory.getLogger(MultimodalChatRelay.class);

    /** 上游 chunk 里除 OpenAI 已知字段外还有 logprobs / index 等, 因此回转必须宽容. */
    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** ObjectMapper 构造不便宜且线程安全, 出站体组装全程共用一个. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmCredentialStore credentialStore;
    private final ProviderInvoker invoker;
    private final UsageLedger usageLedger;
    private final RateLimiter rateLimiter;
    private final UpstreamHttp http;

    public MultimodalChatRelay(LlmCredentialStore credentialStore,
                               ProviderInvoker invoker,
                               UsageLedger usageLedger,
                               RateLimiter rateLimiter,
                               UpstreamHttp http) {
        this.credentialStore = credentialStore;
        this.invoker = invoker;
        this.usageLedger = usageLedger;
        this.rateLimiter = rateLimiter;
        this.http = http;
    }

    /** 请求里是否带了 kernel 传不下去的非文本 part. */
    public static boolean carriesNonTextParts(UnifiedRequest req) {
        if (req == null || req.getMessages() == null) {
            return false;
        }
        for (UnifiedMessage m : req.getMessages()) {
            if (m != null && hasImageParts(m.getContents())) {
                return true;
            }
        }
        return false;
    }

    /** 一次转发的结果 + 记账上下文. */
    public static final class RelayResult {
        private final UnifiedResponse response;
        private final String credential;
        private final UsageLedger.Record usage;
        private final int chunks;

        RelayResult(UnifiedResponse response, String credential, UsageLedger.Record usage, int chunks) {
            this.response = response;
            this.credential = credential;
            this.usage = usage;
            this.chunks = chunks;
        }

        public UnifiedResponse response() {
            return response;
        }

        public String credential() {
            return credential;
        }

        public UsageLedger.Record usage() {
            return usage;
        }

        public int chunks() {
            return chunks;
        }
    }

    public RelayResult chat(UnifiedRequest req, ModelRouter.Resolved resolved, ApiKey key) {
        final Vendor vendor = resolved.vendor();
        final ObjectNode body = buildBody(req, resolved, false);
        JsonNode raw = invoker.execute(vendor, invoker.attemptsFor(vendor), h -> {
            LlmCredential cred = credentialStore.findByAlias(h.alias());
            if (cred == null) {
                throw GatewayException.internal("credential " + h.alias() + " vanished", null);
            }
            return http.postJson(UpstreamEndpoints.chatUrl(cred, vendor),
                    UpstreamEndpoints.headers(cred, vendor), body);
        });
        UnifiedResponse out = vendor == Vendor.ANTHROPIC
                ? toAnthropicResponse(raw, resolved) : toResponse(raw, resolved);
        UsageLedger.Record rec = settle(key, resolved, out.getUsage());
        return new RelayResult(out, null, rec, 0);
    }

    /**
     * 流式转发. 与主链路同样的约束: 只在"还没向客户端吐过内容"时换凭据.
     */
    public RelayResult streamChat(UnifiedRequest req,
                                  ModelRouter.Resolved resolved,
                                  ApiKey key,
                                  Consumer<UnifiedStreamChunk> onChunk,
                                  Consumer<Throwable> onError,
                                  Runnable onComplete) {
        final Vendor vendor = resolved.vendor();
        final ObjectNode body = buildBody(req, resolved, true);
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean emitted = new AtomicBoolean(false);
        final int[] chunkCount = new int[1];
        final UsageInfo[] usage = new UsageInfo[1];

        Runnable finish = () -> {
            if (finished.compareAndSet(false, true) && onComplete != null) {
                onComplete.run();
            }
        };

        List<ProviderInvoker.Handle> pool = invoker.candidatesForFailover(vendor);
        if (pool.isEmpty()) {
            throw GatewayException.internal("No active credential for vendor " + vendor, null);
        }
        int cap = Math.min(invoker.attemptsFor(vendor), pool.size());
        Throwable lastError = null;
        for (int i = 0; i < cap; i++) {
            ProviderInvoker.Handle h = pool.get(i);
            LlmCredential cred = credentialStore.findByAlias(h.alias());
            if (cred == null) {
                continue;
            }
            emitted.set(false);
            // Anthropic 的增量帧不带 id/model, 只在 message_start 出现一次, 因此每次尝试要有一份帧间状态.
            final AnthropicStreamState state = vendor == Vendor.ANTHROPIC
                    ? new AnthropicStreamState() : null;
            try {
                http.postSse(UpstreamEndpoints.chatUrl(cred, vendor),
                        UpstreamEndpoints.headers(cred, vendor), body,
                        data -> {
                            if (data == null) {
                                return;
                            }
                            String payload = data.trim();
                            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                                // 上游的 [DONE] 不透传: 收尾由网关自己保证恰好一次.
                                return;
                            }
                            UnifiedStreamChunk chunk = state == null
                                    ? toChunk(payload, resolved)
                                    : toAnthropicChunk(payload, resolved, state);
                            if (chunk == null) {
                                return;
                            }
                            if (chunk.getUsage() != null) {
                                usage[0] = chunk.getUsage();
                            }
                            chunkCount[0]++;
                            emitted.set(true);
                            if (onChunk != null) {
                                onChunk.accept(chunk);
                            }
                        });
                invoker.reportSuccess(h);
                lastError = null;
                finish.run();
                break;
            } catch (Throwable t) {
                lastError = t;
                if (emitted.get() || !ProviderInvoker.retryable(t)) {
                    invoker.reportFailure(h, t);
                    usageLedger.recordFailure(key);
                    notifyErrorOnce(onError, finished, t);
                    throw GatewayException.upstreamFailed(
                            "multimodal stream failed: " + t.getMessage(), t);
                }
                log.warn("multimodal stream attempt on {} failed before first chunk, failing over: {}",
                        h.key(), t.getMessage());
            }
        }
        if (lastError != null) {
            usageLedger.recordFailure(key);
            notifyErrorOnce(onError, finished, lastError);
            throw GatewayException.upstreamFailed(
                    "multimodal stream failed after all credentials: " + lastError.getMessage(), lastError);
        }
        return new RelayResult(null, null, settle(key, resolved, usage[0]), chunkCount[0]);
    }

    private static void notifyErrorOnce(Consumer<Throwable> onError, AtomicBoolean finished, Throwable t) {
        if (finished.compareAndSet(false, true) && onError != null) {
            onError.accept(t);
        }
    }

    // ---- 请求体 ----

    ObjectNode buildBody(UnifiedRequest req, ModelRouter.Resolved resolved, boolean stream) {
        return resolved.vendor() == Vendor.ANTHROPIC
                ? buildAnthropicBody(req, resolved, stream)
                : buildOpenAiBody(req, resolved, stream);
    }

    private ObjectNode buildOpenAiBody(UnifiedRequest req, ModelRouter.Resolved resolved, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", resolved.bareModel());
        ArrayNode messages = body.putArray("messages");
        if (req.getMessages() != null) {
            for (UnifiedMessage m : req.getMessages()) {
                messages.add(toOpenAiMessage(m));
            }
        }
        if (req.getTemperature() != null) {
            body.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            body.put("top_p", req.getTopP());
        }
        if (req.getMaxTokens() != null) {
            body.put("max_tokens", req.getMaxTokens());
        }
        if (stream) {
            body.put("stream", true);
        }
        List<ToolSpec> specs = req.getTools();
        if (specs != null && !specs.isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec ts : specs) {
                if (ts == null || ts.getFunction() == null) {
                    continue;
                }
                ObjectNode t = tools.addObject();
                t.put("type", ts.getType() == null ? "function" : ts.getType());
                ObjectNode fn = t.putObject("function");
                fn.put("name", ts.getFunction().getName());
                if (ts.getFunction().getDescription() != null) {
                    fn.put("description", ts.getFunction().getDescription());
                }
                if (ts.getFunction().getParameters() != null) {
                    fn.set("parameters", MAPPER.valueToTree(ts.getFunction().getParameters()));
                }
            }
        }
        // 其余采样参数按 vendor 下沉 (stop / tool_choice / penalties / user / extra).
        Map<String, Object> params = new LinkedHashMap<>(
                ParamTranslator.forVendor(resolved.vendor(), req));
        for (Map.Entry<String, Object> e : params.entrySet()) {
            body.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
        }
        return body;
    }

    private ObjectNode toOpenAiMessage(UnifiedMessage m) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", m.getRole() == null ? "user" : m.getRole());
        if (m.getName() != null) {
            node.put("name", m.getName());
        }
        List<ContentPart> parts = m.getContents();
        boolean hasImages = hasImageParts(parts);
        if (hasImages) {
            // content 只在"确有图片"时才升成数组, 纯文本消息保持字符串形状。
            ArrayNode arr = node.putArray("content");
            if (parts != null) {
                for (ContentPart p : parts) {
                    if (p == null) {
                        continue;
                    }
                    if (isImage(p)) {
                        ObjectNode item = arr.addObject();
                        item.put("type", "image_url");
                        ObjectNode img = item.putObject("image_url");
                        if (p.getImageUrl() != null) {
                            img.put("url", p.getImageUrl().getUrl());
                            if (p.getImageUrl().getDetail() != null) {
                                img.put("detail", p.getImageUrl().getDetail());
                            }
                        }
                    } else {
                        ObjectNode item = arr.addObject();
                        item.put("type", "text");
                        item.put("text", p.getText() == null ? "" : p.getText());
                    }
                }
            }
        } else {
            node.put("content", textOf(parts, m.getContent()));
        }
        if (m.getToolCallId() != null) {
            node.put("tool_call_id", m.getToolCallId());
        }
        List<ToolCall> tcs = m.getToolCalls();
        if (tcs != null && !tcs.isEmpty()) {
            ArrayNode arr = node.putArray("tool_calls");
            for (ToolCall tc : tcs) {
                ObjectNode t = arr.addObject();
                t.put("id", tc.getId());
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                if (tc.getFunction() != null) {
                    fn.put("name", tc.getFunction().getName());
                    fn.put("arguments", tc.getFunction().getArguments() == null
                            ? "{}" : tc.getFunction().getArguments());
                }
            }
        }
        return node;
    }

    private static String textOf(List<ContentPart> parts, String fallback) {
        if (parts == null || parts.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentPart p : parts) {
            if (p != null && p.getText() != null && !p.getText().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p.getText());
            }
        }
        return sb.length() == 0 ? (fallback == null ? "" : fallback) : sb.toString();
    }

    // ---- Anthropic 原生 Messages API 形状 ----

    /**
     * system 在顶层、max_tokens 必填、tool 结果要挂在 user 消息里 ——
     * 这三条与 OpenAI 形状不同, 直接复用会换来上游 400, 所以单独组装.
     */
    private ObjectNode buildAnthropicBody(UnifiedRequest req, ModelRouter.Resolved resolved, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", resolved.bareModel());
        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        if (req.getMessages() != null) {
            for (UnifiedMessage m : req.getMessages()) {
                if (m == null) {
                    continue;
                }
                String role = m.getRole() == null ? "user" : m.getRole();
                if ("system".equals(role)) {
                    String text = textOf(m.getContents(), m.getContent());
                    if (!text.isEmpty()) {
                        if (system.length() > 0) {
                            system.append("\n\n");
                        }
                        system.append(text);
                    }
                    continue;
                }
                messages.add(toAnthropicMessage(m, role));
            }
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        // 与 kernel AnthropicProvider 取同一个默认值: 缺 max_tokens 会被上游直接拒掉.
        body.put("max_tokens", req.getMaxTokens() == null ? 8192 : req.getMaxTokens());
        if (req.getTemperature() != null) {
            body.put("temperature", req.getTemperature());
        }
        if (req.getTopP() != null) {
            body.put("top_p", req.getTopP());
        }
        if (stream) {
            body.put("stream", true);
        }
        List<ToolSpec> specs = req.getTools();
        if (specs != null && !specs.isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec ts : specs) {
                if (ts == null || ts.getFunction() == null) {
                    continue;
                }
                ObjectNode t = tools.addObject();
                t.put("name", ts.getFunction().getName());
                if (ts.getFunction().getDescription() != null) {
                    t.put("description", ts.getFunction().getDescription());
                }
                t.set("input_schema", ts.getFunction().getParameters() == null
                        ? MAPPER.createObjectNode() : MAPPER.valueToTree(ts.getFunction().getParameters()));
            }
        }
        Map<String, Object> params = new LinkedHashMap<>(
                ParamTranslator.forVendor(resolved.vendor(), req));
        for (Map.Entry<String, Object> e : params.entrySet()) {
            body.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
        }
        return body;
    }

    private ObjectNode toAnthropicMessage(UnifiedMessage m, String role) {
        ObjectNode node = MAPPER.createObjectNode();
        List<ContentPart> parts = m.getContents();
        boolean hasImages = hasImageParts(parts);
        if ("tool".equals(role) || m.getToolCallId() != null) {
            node.put("role", "user");
            ObjectNode tr = node.putArray("content").addObject();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", m.getToolCallId());
            tr.put("content", textOf(parts, m.getContent()));
            return node;
        }
        node.put("role", "assistant".equals(role) ? "assistant" : "user");
        List<ToolCall> tcs = m.getToolCalls();
        boolean hasToolCalls = tcs != null && !tcs.isEmpty();
        if (!hasImages && !hasToolCalls) {
            node.put("content", textOf(parts, m.getContent()));
            return node;
        }
        ArrayNode blocks = node.putArray("content");
        if (hasImages) {
            for (ContentPart p : parts) {
                if (p == null) {
                    continue;
                }
                if (isImage(p)) {
                    addImageBlock(blocks.addObject(), p);
                } else {
                    ObjectNode text = blocks.addObject();
                    text.put("type", "text");
                    text.put("text", p.getText() == null ? "" : p.getText());
                }
            }
        } else {
            String text = textOf(parts, m.getContent());
            if (!text.isEmpty()) {
                blocks.addObject().put("type", "text").put("text", text);
            }
        }
        if (hasToolCalls) {
            for (ToolCall tc : tcs) {
                if (tc == null || tc.getFunction() == null) {
                    continue;
                }
                ObjectNode use = blocks.addObject();
                use.put("type", "tool_use");
                use.put("id", tc.getId());
                use.put("name", tc.getFunction().getName());
                use.set("input", parseArgsOrEmpty(tc.getFunction().getArguments()));
            }
        }
        return node;
    }

    private static void addImageBlock(ObjectNode item, ContentPart p) {
        item.put("type", "image");
        ObjectNode source = item.putObject("source");
        String url = p.getImageUrl() == null ? null : p.getImageUrl().getUrl();
        if (url != null && url.startsWith("data:")) {
            // data:image/png;base64,xxx —— Anthropic 的 base64 source 要拆开 media_type 与 data.
            int comma = url.indexOf(',');
            String meta = comma < 0 ? url.substring(5) : url.substring(5, comma);
            int semi = meta.indexOf(';');
            source.put("type", "base64");
            source.put("media_type", semi < 0 ? "image/png" : meta.substring(0, semi));
            source.put("data", comma < 0 ? "" : url.substring(comma + 1));
        } else {
            source.put("type", "url");
            source.put("url", url == null ? "" : url);
        }
    }

    private JsonNode parseArgsOrEmpty(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode parsed = lenient.readTree(arguments);
            return parsed.isObject() ? parsed : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static boolean hasImageParts(List<ContentPart> parts) {
        if (parts == null) {
            return false;
        }
        for (ContentPart p : parts) {
            if (isImage(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImage(ContentPart p) {
        return p != null && (p.getImageUrl() != null || "image_url".equals(p.getType()));
    }

    // ---- 回转 ----

    UnifiedResponse toResponse(JsonNode raw, ModelRouter.Resolved resolved) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            throw GatewayException.upstreamFailed("relay upstream returned an empty body", null);
        }
        UnifiedResponse out;
        try {
            out = lenient.treeToValue(raw, UnifiedResponse.class);
        } catch (Exception e) {
            throw GatewayException.upstreamFailed("relay response is not a chat.completion: "
                    + e.getMessage(), e);
        }
        out.setModel(ModelNames.external(out.getModel(), resolved));
        if (out.getObject() == null) {
            out.setObject("chat.completion");
        }
        return out;
    }

    UnifiedStreamChunk toChunk(String payload, ModelRouter.Resolved resolved) {
        try {
            UnifiedStreamChunk chunk = lenient.readValue(payload, UnifiedStreamChunk.class);
            chunk.setModel(ModelNames.external(chunk.getModel(), resolved));
            return chunk;
        } catch (Exception e) {
            throw GatewayException.upstreamFailed(
                    "relay produced a non-JSON stream frame: " + payload, e);
        }
    }

    /** Anthropic 的增量帧不带 id/model/usage, 只在 message_start 出现一次, 因此要跨帧记状态. */
    private static final class AnthropicStreamState {
        private String id;
        private String model;
        private long prompt;
        private long completion;
    }

    UnifiedResponse toAnthropicResponse(JsonNode raw, ModelRouter.Resolved resolved) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            throw GatewayException.upstreamFailed("relay upstream returned an empty body", null);
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : raw.path("content")) {
            String type = str(block, "type");
            if ("text".equals(type)) {
                text.append(str(block, "text"));
            } else if ("tool_use".equals(type)) {
                ToolCall call = new ToolCall();
                call.setId(str(block, "id"));
                call.setType("function");
                ToolCall.FunctionCall fn = new ToolCall.FunctionCall();
                fn.setName(str(block, "name"));
                fn.setArguments(block.path("input").isMissingNode() ? "{}" : block.path("input").toString());
                call.setFunction(fn);
                calls.add(call);
            }
        }
        UnifiedMessage message = new UnifiedMessage();
        message.setRole("assistant");
        message.setContent(text.toString());
        if (!calls.isEmpty()) {
            message.setToolCalls(calls);
        }
        Choice choice = new Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(anthropicFinish(str(raw, "stop_reason")));

        UnifiedResponse out = new UnifiedResponse();
        out.setObject("chat.completion");
        out.setId(str(raw, "id"));
        out.setModel(ModelNames.external(str(raw, "model"), resolved));
        out.setCreated(System.currentTimeMillis() / 1000L);
        List<Choice> choices = new ArrayList<>();
        choices.add(choice);
        out.setChoices(choices);
        JsonNode usage = raw.path("usage");
        if (usage.isObject()) {
            out.setUsage(usageInfo(usage.path("input_tokens").asLong(0L),
                    usage.path("output_tokens").asLong(0L)));
        }
        return out;
    }

    /**
     * 单条 Anthropic SSE data 帧 → 统一 chunk.
     *
     * <p>返回 null 表示这帧对外没有信息量 (ping / content_block_* / message_stop), 调用方直接跳过。
     */
    UnifiedStreamChunk toAnthropicChunk(String payload, ModelRouter.Resolved resolved,
                                       AnthropicStreamState state) {
        JsonNode root;
        try {
            root = lenient.readTree(payload);
        } catch (Exception e) {
            throw GatewayException.upstreamFailed(
                    "relay produced a non-JSON stream frame: " + payload, e);
        }
        String type = str(root, "type");
        if ("message_start".equals(type)) {
            JsonNode message = root.path("message");
            state.id = str(message, "id");
            state.model = str(message, "model");
            readUsage(message.path("usage"), state);
            UnifiedStreamChunk chunk = newAnthropicChunk(state, resolved);
            chunk.getChoices().get(0).getDelta().setRole("assistant");
            return chunk;
        }
        if ("content_block_delta".equals(type)) {
            JsonNode delta = root.path("delta");
            if (!"text_delta".equals(str(delta, "type"))) {
                // input_json_delta 是工具入参增量, 统一层没有对应的位置承载半截 JSON, 跳过.
                return null;
            }
            UnifiedStreamChunk chunk = newAnthropicChunk(state, resolved);
            chunk.getChoices().get(0).getDelta().setContent(str(delta, "text"));
            return chunk;
        }
        if ("message_delta".equals(type)) {
            readUsage(root.path("usage"), state);
            UnifiedStreamChunk chunk = newAnthropicChunk(state, resolved);
            chunk.getChoices().get(0)
                    .setFinishReason(anthropicFinish(str(root.path("delta"), "stop_reason")));
            if (state.prompt > 0 || state.completion > 0) {
                chunk.setUsage(usageInfo(state.prompt, state.completion));
            }
            return chunk;
        }
        if ("error".equals(type)) {
            // 吞掉 error 帧会让客户端以为回答正常结束, 必须当失败上抛.
            throw GatewayException.upstreamFailed("anthropic stream error: "
                    + root.path("error").path("message").asText("unknown"), null);
        }
        return null;
    }

    private UnifiedStreamChunk newAnthropicChunk(AnthropicStreamState state,
                                                 ModelRouter.Resolved resolved) {
        UnifiedStreamChunk chunk = new UnifiedStreamChunk();
        chunk.setId(state.id);
        chunk.setModel(ModelNames.external(state.model, resolved));
        chunk.setCreated(System.currentTimeMillis() / 1000L);
        chunk.setObject("chat.completion.chunk");
        Choice.Delta delta = new Choice.Delta();
        Choice choice = new Choice();
        choice.setIndex(0);
        choice.setDelta(delta);
        List<Choice> choices = new ArrayList<>();
        choices.add(choice);
        chunk.setChoices(choices);
        return chunk;
    }

    private static void readUsage(JsonNode usage, AnthropicStreamState state) {
        if (usage.isObject()) {
            long in = usage.path("input_tokens").asLong(0L);
            long out = usage.path("output_tokens").asLong(0L);
            if (in > 0) {
                state.prompt = in;
            }
            if (out > 0) {
                state.completion = out;
            }
        }
    }

    private static UsageInfo usageInfo(long prompt, long completion) {
        return new UsageInfo((int) Math.min(Integer.MAX_VALUE, prompt),
                (int) Math.min(Integer.MAX_VALUE, completion),
                (int) Math.min(Integer.MAX_VALUE, prompt + completion));
    }

    /** Anthropic 的 stop_reason 翻成 OpenAI 形状的 finish_reason — 网关内部只认后者. */
    private static String anthropicFinish(String stopReason) {
        if (stopReason == null || stopReason.isEmpty()) {
            return null;
        }
        switch (stopReason) {
            case "end_turn":
            case "stop_sequence":
                return "stop";
            case "max_tokens":
                return "length";
            case "tool_use":
                return "tool_calls";
            default:
                return stopReason;
        }
    }

    private static String str(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private UsageLedger.Record settle(ApiKey key, ModelRouter.Resolved resolved, UsageInfo usage) {
        long prompt = usage == null || usage.getPromptTokens() == null ? 0L : usage.getPromptTokens();
        long completion = usage == null || usage.getCompletionTokens() == null ? 0L
                : usage.getCompletionTokens();
        UsageLedger.Record rec = usageLedger.record(key, resolved.vendor(), resolved.bareModel(),
                prompt, completion);
        if (rateLimiter != null && key != null) {
            rateLimiter.recordTokenUsage(key, prompt + completion);
        }
        return rec;
    }
}
