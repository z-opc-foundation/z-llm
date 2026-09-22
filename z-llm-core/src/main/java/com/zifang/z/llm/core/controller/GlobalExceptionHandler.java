package com.zifang.z.llm.core.controller;

import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.llm.api.dto.ErrorResponse;
import com.zifang.z.llm.api.exception.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 网关全局异常处理 — 把 GatewayException / LlmException 转为带正确 HTTP 状态码的 ErrorResponse.
 */
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
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(er);
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