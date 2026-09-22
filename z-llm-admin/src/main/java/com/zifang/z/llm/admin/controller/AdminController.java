package com.zifang.z.llm.admin.controller;

import com.zifang.z.llm.admin.service.AdminQueryService;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.RateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面 REST 端点 — 在 /z-llm/admin/* 下, 与 OpenAI/Anthropic 公网端点隔离.
 */
@RestController
@RequestMapping("/z-llm/admin")
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

    @GetMapping("/api-keys")
    public Object apiKeys() {
        return apiKeyService.list();
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