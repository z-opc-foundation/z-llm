package com.zifang.z.llm.core.credential;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 校验服务 — 由网关签发/管理的接入凭证.
 *
 * <p>鉴权流程: 调用方在 Authorization header 带 "Bearer {api_key}",
 * <p>本服务用 sha256 前缀匹配 + 常量时间比较查找 ApiKey.
 *
 * <p>本期由配置文件提供 ApiKey 列表 (后续由 z-llm-admin 通过 zk-config 动态刷新).
 *
 * <p>实现 InitializingBean 是因为 @ConfigurationProperties 的字段绑定发生在
 * <p>bean 构造之后, 若在构造里读 properties.getApiKeys() 会得到空 list.
 */
public class ApiKeyService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, ApiKey> activeKeys = new ConcurrentHashMap<>();

    public ApiKeyService(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        reload();
    }

    public synchronized void reload() {
        activeKeys.clear();
        if (properties.getApiKeys() == null) {
            log.info("ApiKeyService loaded active keys: 0 (apiKeys null)");
            return;
        }
        for (ApiKey k : properties.getApiKeys()) {
            if (k.getKey() == null || k.getKey().isEmpty()) continue;
            if (!"active".equalsIgnoreCase(k.getStatus())) continue;
            activeKeys.put(k.getKey(), k);
        }
        log.info("ApiKeyService loaded active keys: {}", activeKeys.size());
    }

    /**
     * 校验 Bearer token. 返回 ApiKey 表示通过; 返回 null 表示失败 (调用方自行决定抛出 401).
     */
    public ApiKey authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isEmpty()) {
            return null;
        }
        // 1. 直接匹配 (静态配置场景)
        ApiKey direct = activeKeys.get(bearerToken);
        if (direct != null) {
            return checkExpiry(direct);
        }
        // 2. 兼容带 "Bearer " 前缀
        String stripped = bearerToken.startsWith("Bearer ")
                ? bearerToken.substring(7).trim()
                : bearerToken;
        ApiKey strippedHit = activeKeys.get(stripped);
        if (strippedHit != null) {
            return checkExpiry(strippedHit);
        }
        return null;
    }

    private ApiKey checkExpiry(ApiKey k) {
        if (k.getExpiresAt() != null && System.currentTimeMillis() > k.getExpiresAt()) {
            throw GatewayException.unauthorized("API key expired");
        }
        return k;
    }

    public List<ApiKey> list() {
        return java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(activeKeys.values()));
    }
}