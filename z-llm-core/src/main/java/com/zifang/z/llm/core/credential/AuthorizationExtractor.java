package com.zifang.z.llm.core.credential;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.exception.GatewayException;
import org.springframework.http.HttpHeaders;

import javax.servlet.http.HttpServletRequest;

/**
 * HTTP Authorization header → ApiKey 提取.
 */
public final class AuthorizationExtractor {

    private AuthorizationExtractor() {}

    /**
     * 解析请求里的 Authorization header, 返回 ApiKey (未通过校验抛 401).
     */
    public static ApiKey requireApiKey(HttpServletRequest req, ApiKeyService service) {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isEmpty()) {
            header = req.getHeader("x-api-key"); // Anthropic 客户端常用此 header
        }
        if (header == null || header.isEmpty()) {
            throw GatewayException.unauthorized("Missing Authorization header");
        }
        // 去掉 "Bearer " 前缀
        String token = header.startsWith("Bearer ")
                ? header.substring(7).trim()
                : header.trim();
        ApiKey key = service.authenticate(token);
        if (key == null) {
            throw GatewayException.unauthorized("Invalid API key");
        }
        return key;
    }
}