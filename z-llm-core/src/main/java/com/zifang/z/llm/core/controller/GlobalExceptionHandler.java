package com.zifang.z.llm.core.controller;

import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.llm.api.dto.ErrorResponse;
import com.zifang.z.llm.api.exception.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 网关全局异常处理 — 把 GatewayException / LlmException 转为带正确 HTTP 状态码的 ErrorResponse.
 * <p>Spring 默认按类名首字母小写注册 bean id="globalExceptionHandler"，
 * 会与 z-config-web/z-team-web 等子项目的同名 ControllerAdvice 冲突。
 * 用显式 @Component("zLlmGlobalExceptionHandler") 给个独立 bean name，
 * Spring MVC 会按 @ExceptionHandler 的最具体类型自动合并所有 handler。
 */
@Component("zLlmGlobalExceptionHandler")
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
        ErrorResponse er = new ErrorResponse(
                ex.getMessage(),
                "gateway_error",
                ex.getCode());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(er);
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ErrorResponse> handleLlm(LlmException ex) {
        log.warn("LlmException: {}", ex.getMessage());
        ErrorResponse er = new ErrorResponse(
                ex.getMessage(),
                "upstream_error",
                "upstream_failed");
        // 上游 4xx 是调用方或配置问题, 原样透出状态码; 只有 5xx/未知才归 502.
        Integer status = ex.getHttpStatus();
        HttpStatus out = HttpStatus.BAD_GATEWAY;
        if (status != null) {
            if (status == 429) {
                out = HttpStatus.TOO_MANY_REQUESTS;
            } else if (status >= 400 && status < 500) {
                out = HttpStatus.valueOf(status);
            }
        }
        return ResponseEntity.status(out).body(er);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        ErrorResponse er = new ErrorResponse(
                ex.getMessage(),
                "invalid_request",
                "bad_request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(er);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorResponse er = new ErrorResponse(
                ex.getMessage() == null ? "internal error" : ex.getMessage(),
                "internal_error",
                "internal");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(er);
    }
}