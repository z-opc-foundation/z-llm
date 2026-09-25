package com.zifang.z.llm.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.core.upstream.UpstreamHttp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 假上游 HTTP 面 —— 让网关直连上游的协议逻辑 (embeddings / 多模态转发) 在单测里可断言.
 *
 * <p>真实网络只出现在 env 门控的 E2E 用例里; 这里记录每次出站请求 (url/headers/body),
 * 并按队列回放响应或抛错。
 */
public class FakeUpstreamHttp implements UpstreamHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final class Call {
        public final String url;
        public final Map<String, String> headers;
        public final JsonNode body;

        Call(String url, Map<String, String> headers, JsonNode body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
        }

        /** 便捷取字段: call.body.path("input"). */
        public JsonNode field(String name) {
            return body.path(name);
        }

        public String header(String name) {
            return headers.get(name);
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();
    private final List<String> sseData = new ArrayList<>();
    private Throwable sseFailure;

    public List<Call> calls() {
        return calls;
    }

    public Call lastCall() {
        return calls.get(calls.size() - 1);
    }

    public int callCount() {
        return calls.size();
    }

    /** 排队一个 JSON 响应 (字符串会被 parse). */
    public FakeUpstreamHttp enqueue(String json) {
        try {
            replies.add(JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad fixture json: " + e.getMessage(), e);
        }
        return this;
    }

    /** 排队的下一次调用抛这个错. */
    public FakeUpstreamHttp failNext(Throwable t) {
        replies.add(t);
        return this;
    }

    /** 流式回放: 依次吐这些 data 帧; 末尾自动补 [DONE] 以贴近真实上游. */
    public FakeUpstreamHttp stream(String... frames) {
        this.sseFailure = null;
        sseData.clear();
        for (String f : frames) {
            sseData.add(f);
        }
        sseData.add("[DONE]");
        return this;
    }

    public FakeUpstreamHttp streamFails(Throwable t) {
        this.sseFailure = t;
        return this;
    }

    @Override
    public JsonNode postJson(String url, Map<String, String> headers, Object body) {
        calls.add(new Call(url, headers, JSON.valueToTree(body)));
        Object next = replies.poll();
        if (next == null) {
            throw new IllegalStateException("no queued upstream reply for " + url);
        }
        if (next instanceof Throwable) {
            Throwable t = (Throwable) next;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new IllegalStateException(t);
        }
        return (JsonNode) next;
    }

    @Override
    public void postSse(String url, Map<String, String> headers, Object body, Consumer<String> onData) {
        calls.add(new Call(url, headers, JSON.valueToTree(body)));
        if (sseFailure != null) {
            throw new IllegalStateException(sseFailure.getMessage(), sseFailure);
        }
        for (String frame : sseData) {
            onData.accept(frame);
        }
    }
}
