package com.zifang.z.llm.core.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.agent.kernel.llm.support.LlmHttp;
import okhttp3.Headers;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 复用 kernel {@link LlmHttp} (okhttp + okhttp-sse) 的直连实现.
 *
 * <p>不引入新依赖: okhttp 已经随 z-agent-kernel-llm 传递进来, 而 LlmHttp 抛的
 * {@code LlmException} 携带上游 http status, 正好被 ProviderInvoker 的重试判定与
 * GlobalExceptionHandler 的状态码映射识别。
 */
public class LlmHttpUpstream implements UpstreamHttp {

    private final LlmHttp http;
    private final ObjectMapper json = new ObjectMapper();

    public LlmHttpUpstream(int connectTimeoutSec, int readTimeoutSec, int writeTimeoutSec) {
        this.http = new LlmHttp(connectTimeoutSec, readTimeoutSec, writeTimeoutSec);
    }

    @Override
    public JsonNode postJson(String url, Map<String, String> headers, Object body) {
        String raw = http.postJson(url, toHeaders(headers), body);
        if (raw == null || raw.trim().isEmpty()) {
            return json.nullNode();
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new LlmException(url, "response is not valid JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public void postSse(String url, Map<String, String> headers, Object body, Consumer<String> onData) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        http.postJsonStream(url, toHeaders(headers), body, onData, err -> {
            failure.compareAndSet(null, err);
            latch.countDown();
        }, () -> latch.countDown());
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException(url, "stream interrupted", ie);
        }
        Throwable t = failure.get();
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t != null) {
            throw new LlmException(url, "stream failed: " + t.getMessage(), t);
        }
    }

    private static Headers toHeaders(Map<String, String> headers) {
        Headers.Builder b = new Headers.Builder();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    b.add(e.getKey(), e.getValue());
                }
            }
        }
        return b.build();
    }
}
