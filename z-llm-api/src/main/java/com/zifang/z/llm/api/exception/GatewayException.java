package com.zifang.z.llm.api.exception;

/**
 * 网关异常 — 网关层抛出的运行时异常基类.
 *
 * <p>controller 层 catch 后转 ErrorResponse 输出.
 * <p>与 kernel 层 LlmException 区分: LlmException 表示上游 vendor 调用失败,
 * 网关层异常表示鉴权、限流、模型路由、协议转换等本端问题.
 */
public class GatewayException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public GatewayException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public GatewayException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    /** 401 — 鉴权失败 (api key 缺失/失效). */
    public static GatewayException unauthorized(String message) {
        return new GatewayException(401, "unauthorized", message);
    }

    /** 403 — 权限不足 (api key 被禁用/越权访问模型). */
    public static GatewayException forbidden(String message) {
        return new GatewayException(403, "forbidden", message);
    }

    /** 404 — 模型未找到. */
    public static GatewayException modelNotFound(String model) {
        return new GatewayException(404, "model_not_found", "Model not found: " + model);
    }

    /** 400 — 请求体不合法. */
    public static GatewayException invalidRequest(String message) {
        return new GatewayException(400, "invalid_request", message);
    }

    /** 413 — 请求体超限. */
    public static GatewayException payloadTooLarge(String message) {
        return new GatewayException(413, "payload_too_large", message);
    }

    /** 429 — 限流. */
    public static GatewayException rateLimited(String message) {
        return new GatewayException(429, "rate_limited", message);
    }

    /** 502 — 上游 vendor 调用失败. */
    public static GatewayException upstreamFailed(String message, Throwable cause) {
        return new GatewayException(502, "upstream_failed", message, cause);
    }

    /** 500 — 网关内部错误. */
    public static GatewayException internal(String message, Throwable cause) {
        return new GatewayException(500, "internal_error", message, cause);
    }
}