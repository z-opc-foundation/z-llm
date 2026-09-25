package com.zifang.z.llm.core.support;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 测试用 provider — 可脚本化的回复/异常/流式行为, 并记录收到的请求.
 */
public class FakeLlmProvider implements LlmProvider {

    private final String name;
    private final List<String> supported = new ArrayList<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger streamCalls = new AtomicInteger();
    private final List<ChatCompletionsRequest> seen = Collections.synchronizedList(new ArrayList<>());

    /** 每次 chat/streamChat 依次消费的脚本化结果; null 表示正常回复. */
    private final List<Throwable> failures = new ArrayList<>();
    private String replyText = "hello";
    private TokenUsage usage = new TokenUsage(11L, 7L, 18L);
    private List<String> streamTexts = Arrays.asList("he", "llo");
    private String streamFinishReason = "stop";
    private boolean streamSetsUsage = true;
    private Consumer<ChatCompletionsRequest> onReceive;

    public FakeLlmProvider(String name, String... supportedModels) {
        this.name = name;
        this.supported.addAll(Arrays.asList(supportedModels));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Model> listModels() {
        List<Model> out = new ArrayList<>();
        for (String id : supported) {
            out.add(new Model(id, id, name,
                    Arrays.asList(Model.Capability.CHAT, Model.Capability.STREAM, Model.Capability.VISION),
                    128000L, 4096L));
        }
        return out;
    }

    @Override
    public boolean supportsModel(String modelId) {
        if (modelId == null) {
            return false;
        }
        for (String s : supported) {
            if (s.equals(modelId) || modelId.toLowerCase().startsWith(s.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        record(request);
        int idx = chatCalls.incrementAndGet() - 1;
        maybeFail(idx);
        return new ChatCompletionsResponse("cmpl-" + idx, request.getModel(),
                Arrays.asList(new ChatCompletionsResponse.Choice(0, replyText, null, "stop")),
                usage, "stop", Collections.emptyMap());
    }

    @Override
    public void streamChat(ChatCompletionsRequest request,
                           Consumer<ChatCompletionsResponse> onChunk,
                           Consumer<Throwable> onError) {
        record(request);
        int idx = streamCalls.incrementAndGet() - 1;
        try {
            maybeFail(idx);
        } catch (Throwable t) {
            onError.accept(t);
            return;
        }
        for (String text : streamTexts) {
            onChunk.accept(new ChatCompletionsResponse("cmpl-s", request.getModel(),
                    Arrays.asList(new ChatCompletionsResponse.Choice(0, text, null, null)),
                    null, null, Collections.emptyMap()));
        }
        onChunk.accept(new ChatCompletionsResponse("cmpl-s", request.getModel(),
                Arrays.asList(new ChatCompletionsResponse.Choice(0, "", null, streamFinishReason)),
                streamSetsUsage ? usage : null, streamFinishReason, Collections.emptyMap()));
    }

    private void record(ChatCompletionsRequest request) {
        seen.add(request);
        if (onReceive != null) {
            onReceive.accept(request);
        }
    }

    private void maybeFail(int idx) {
        if (idx < failures.size()) {
            Throwable t = failures.get(idx);
            if (t != null) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                throw new IllegalStateException(t);
            }
        }
    }

    // ---- 脚本化 setter ----

    public FakeLlmProvider failOn(int callIndex, Throwable t) {
        while (failures.size() <= callIndex) {
            failures.add(null);
        }
        failures.set(callIndex, t);
        return this;
    }

    public FakeLlmProvider replyText(String s) {
        this.replyText = s;
        return this;
    }

    public FakeLlmProvider usage(TokenUsage u) {
        this.usage = u;
        return this;
    }

    public FakeLlmProvider streamTexts(String... texts) {
        this.streamTexts = Arrays.asList(texts);
        return this;
    }

    public FakeLlmProvider streamFinishReason(String s) {
        this.streamFinishReason = s;
        return this;
    }

    public FakeLlmProvider streamSetsUsage(boolean b) {
        this.streamSetsUsage = b;
        return this;
    }

    public FakeLlmProvider onReceive(Consumer<ChatCompletionsRequest> c) {
        this.onReceive = c;
        return this;
    }

    public List<ChatCompletionsRequest> requests() {
        return seen;
    }

    public int chatCalls() {
        return chatCalls.get();
    }

    public int streamCalls() {
        return streamCalls.get();
    }
}
