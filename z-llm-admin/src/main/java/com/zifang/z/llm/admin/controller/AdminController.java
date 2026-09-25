package com.zifang.z.llm.admin.controller;

import com.zifang.z.llm.admin.service.AdminQueryService;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面 REST 端点 — 在 /z-llm/admin/* 下, 与 OpenAI/Anthropic 公网端点隔离.
 */
@RestController
@RequestMapping("/z-llm/admin")
@ConditionalOnProperty(name = "z.llm.expose-admin", havingValue = "true")
public class AdminController {

    private final AdminQueryService query;
    private final LlmCredentialStore credentialStore;
    private final LlmProviderRegistry providerRegistry;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;

    public AdminController(AdminQueryService query,
                           LlmCredentialStore credentialStore,
                           LlmProviderRegistry providerRegistry,
                           ApiKeyService apiKeyService,
                           RateLimiter rateLimiter) {
        this.query = query;
        this.credentialStore = credentialStore;
        this.providerRegistry = providerRegistry;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return query.overview();
    }

    @GetMapping("/vendors")
    public List<Map<String, Object>> vendors() {
        return query.providers();
    }

    @GetMapping("/vendors/{vendor}/models")
    public Map<String, Object> models(@PathVariable String vendor) {
        Vendor v = Vendor.fromModel(vendor + "/x");
        Map<String, Object> out = new HashMap<>();
        out.put("vendor", vendor);
        out.put("models", query.models(v));
        return out;
    }

    @GetMapping("/credentials")
    public Map<String, Object> credentials() {
        Map<String, Object> out = new HashMap<>();
        out.put("total", credentialStore.size());
        out.put("byVendor", query.providers());
        return out;
    }

    /**
     * API Key 清单 — 只给掩码, 不给原文.
     *
     * <p>{@link ApiKey#getKey()} 就是鉴权用的凭证本身; 直接序列化 DTO 会让这个
     * 不带鉴权的端点变成一张"可拿来调网关的全部凭证"清单.
     */
    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : apiKeyService.list()) {
            out.add(maskedView(k));
        }
        return out;
    }

    private static Map<String, Object> maskedView(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("ownerId", k.getOwnerId());
        m.put("status", k.getStatus());
        m.put("maskedKey", mask(k.getKey()));
        m.put("expiresAt", k.getExpiresAt());
        m.put("allowedModels", k.getAllowedModels());
        m.put("allowedVendors", k.getAllowedVendors());
        m.put("tokensPerMinute", k.getTokensPerMinute());
        m.put("requestsPerMinute", k.getRequestsPerMinute());
        m.put("createdAt", k.getCreatedAt());
        return m;
    }

    /** 留头尾各 4 位供人工对号, 中间一律打星. */
    static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    @GetMapping("/rate-limit")
    public Object rateLimit() {
        return rateLimiter.snapshot();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "UP");
        out.put("providers", providerRegistry.keys().size());
        out.put("credentials", credentialStore.size());
        return out;
    }
}