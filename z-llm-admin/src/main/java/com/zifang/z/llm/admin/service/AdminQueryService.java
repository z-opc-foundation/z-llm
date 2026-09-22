package com.zifang.z.llm.admin.service;

import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面查询服务 — admin REST 接口背后的数据组装.
 */
@Service
public class AdminQueryService {

    private static final Logger log = LoggerFactory.getLogger(AdminQueryService.class);

    private final LlmProviderRegistry providerRegistry;
    private final LlmCredentialStore credentialStore;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;

    public AdminQueryService(LlmProviderRegistry providerRegistry,
                             LlmCredentialStore credentialStore,
                             ApiKeyService apiKeyService,
                             RateLimiter rateLimiter) {
        this.providerRegistry = providerRegistry;
        this.credentialStore = credentialStore;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
    }

    /** 概览. */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendors", providers());
        out.put("credentialCount", credentialStore.size());
        out.put("activeApiKeys", apiKeyService.list().size());
        out.put("rateLimitBuckets", rateLimiter.activeBuckets());
        return out;
    }

    /** 各 vendor 状态. */
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Vendor v : Vendor.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vendor", v.code());
            row.put("credentials", credentialStore.findByVendor(v).size());
            row.put("models", providerRegistry.listByVendor(v).isEmpty() ? 0
                    : providerRegistry.listByVendor(v).get(0).listModels().size());
            out.add(row);
        }
        return out;
    }

    /** 列模型 (按 vendor). */
    public List<String> models(Vendor vendor) {
        List<String> all = new ArrayList<>();
        providerRegistry.listByVendor(vendor).forEach(p -> {
            try {
                p.listModels().forEach(m -> all.add(m.getId()));
            } catch (Exception e) {
                log.warn("listModels failed: {}", e.getMessage());
            }
        });
        return all;
    }
}