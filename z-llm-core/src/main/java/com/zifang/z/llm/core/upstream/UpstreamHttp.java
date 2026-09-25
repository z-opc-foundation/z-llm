package com.zifang.z.llm.core.upstream;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 网关直连上游的最小 HTTP 面.
 *
 * <p>为什么网关要自己发 HTTP: kernel 的 provider 只会把 {@code Msg.content} 当纯文本下发
 * (实测 kernel-llm 全模块 0 处读 {@code Msg.getMetadata()}), 所以多模态 part 与
 * embeddings 这类 kernel 没有对应 SPI 的调用, 只能由网关按上游协议直连.
 *
 * <p>做成接口是为了让协议层测试能注入假实现 —— 真实网络留给 env 门控的 E2E 用例,
 * 单元测试永远不该依赖外网可达性。
 */
public interface UpstreamHttp {

    /**
     * POST JSON 并解析响应.
     *
     * @throws com.zifang.z.agent.kernel.llm.support.LlmException 非 2xx (带上游 http status) 或 I/O 失败
     */
    JsonNode postJson(String url, Map<String, String> headers, Object body);

    /**
     * SSE POST. 实现必须阻塞到流关闭或出错, 以便 failover 逻辑能在首块之前安全换凭据.
     *
     * @param onData  每个 data 负载 (含 {@code [DONE]} 哨兵)
     * @throws RuntimeException 流失败 (含上游 http status 时抛 LlmException)
     */
    void postSse(String url, Map<String, String> headers, Object body, Consumer<String> onData);
}
