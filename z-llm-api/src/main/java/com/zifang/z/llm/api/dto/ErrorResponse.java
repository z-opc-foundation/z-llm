package com.zifang.z.llm.api.dto;

/**
 * 错误响应 — 网关对外统一错误格式.
 *
 * <p>兼容 OpenAI 错误格式: { error: { message, type, code, param } }.
 * <p>同时支持 Anthropic 错误格式 mapper 转换.
 */
public class ErrorResponse {

    private ErrorBody error;

    public ErrorResponse() {
    }

    public ErrorResponse(String message, String type, String code) {
        ErrorBody b = new ErrorBody();
        b.setMessage(message);
        b.setType(type);
        b.setCode(code);
        this.error = b;
    }

    public ErrorBody getError() {
        return error;
    }

    public void setError(ErrorBody error) {
        this.error = error;
    }

    public static class ErrorBody {
        private String message;
        private String type;
        private String code;
        private String param;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }
    }
}