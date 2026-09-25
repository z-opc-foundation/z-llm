package com.zifang.z.llm.core.router;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型路由 — 把请求里的 model 字符串解析成 (vendor, 裸 model id).
 *
 * <p>解决的三类真实输入:
 * <ul>
 *   <li>{@code "openai/gpt-4o"} — 显式 vendor 前缀.</li>
 *   <li>{@code "gpt-4o"} — OpenAI 官方 SDK / 绝大多数客户端只会发裸名, 不带前缀.</li>
 *   <li>{@code "meta-llama/Llama-3.1-70B"} — 名字本身就含斜杠的三方 model id,
 *       其前缀不是任何 vendor, 必须按 provider 声明的 supportsModel 判定而非按前缀切.</li>
 * </ul>
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** 解析结果. */
    public static final class Resolved {
        private final Vendor vendor;
        private final String bareModel;
        private final String requested;

        public Resolved(Vendor vendor, String bareModel, String requested) {
            this.vendor = vendor;
            this.bareModel = bareModel;
            this.requested = requested;
        }

        public Vendor vendor() {
            return vendor;
        }

        public String bareModel() {
            return bareModel;
        }

        public String requested() {
            return requested;
        }

        /** "vendor/model" 形式的规范化 id. */
        public String canonical() {
            return vendor.code() + "/" + bareModel;
        }
    }

    private final LlmProviderRegistry registry;
    private final GatewayProperties properties;

    public ModelRouter(LlmProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public Resolved resolve(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            throw GatewayException.modelNotFound(String.valueOf(requested));
        }
        String model = requested.trim();

        String aliased = applyAlias(model);
        if (aliased != null) {
            model = aliased;
        }

        int slash = model.indexOf('/');
        if (slash > 0) {
            Vendor byPrefix = vendorByPrefix(model.substring(0, slash));
            if (byPrefix != null) {
                String bare = model.substring(slash + 1);
                if (bare.isEmpty()) {
                    throw GatewayException.modelNotFound(requested);
                }
                requireCredential(byPrefix, requested);
                return new Resolved(byPrefix, bare, requested);
            }
            // 前缀不是 vendor ⇒ 整个字符串可能就是裸 model id (deepseek 风格 / HF 风格命名).
            Resolved byCapability = resolveByCapability(model, model, requested);
            if (byCapability != null) {
                return byCapability;
            }
            throw GatewayException.modelNotFound(requested);
        }

        Resolved byCapability = resolveByCapability(model, model, requested);
        if (byCapability != null) {
            return byCapability;
        }
        throw GatewayException.modelNotFound(requested);
    }

    /** 列出网关当前可路由的模型卡 (供 /v1/models). */
    public Map<String, ModelCard> availableModels() {
        Map<String, ModelCard> out = new LinkedHashMap<>();
        for (Vendor v : Vendor.values()) {
            for (LlmProvider p : registry.listByVendor(v)) {
                if (p == null) {
                    continue;
                }
                for (Model m : p.listModels()) {
                    out.put(v.code() + "/" + m.getId(), new ModelCard(v, m));
                }
            }
        }
        return out;
    }

    // ---- 私有 ----

    private String applyAlias(String model) {
        Map<String, String> aliases = properties.getModelAliases();
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        String hit = aliases.get(model);
        if (hit == null) {
            hit = aliases.get(model.toLowerCase(Locale.ROOT));
        }
        if (hit != null) {
            log.debug("model alias {} -> {}", model, hit);
        }
        return hit;
    }

    private static Vendor vendorByPrefix(String prefix) {
        for (Vendor v : Vendor.values()) {
            if (v.code().equalsIgnoreCase(prefix)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 按 provider 自己声明的 supportsModel 判定归属.
     * 命中唯一 vendor 时返回; 多个 vendor 都能claim 时按凭据 priority 取第一个 (确定性).
     */
    private Resolved resolveByCapability(String probe, String bareIfHit, String requested) {
        List<Vendor> hits = new ArrayList<>();
        for (Vendor v : Vendor.values()) {
            if (!registry.hasCredential(v)) {
                continue;
            }
            for (LlmProvider p : registry.listByVendor(v)) {
                if (p != null && safeSupports(p, probe)) {
                    hits.add(v);
                    break;
                }
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        if (hits.size() > 1) {
            log.info("model {} claimed by {} vendors {}, routed to {}",
                    requested, hits.size(), hits, hits.get(0));
        }
        Vendor chosen = hits.get(0);
        String bare = bareIfHit;
        if (chosen == Vendor.QWEN && bare.startsWith("qwen/") && bare.length() > 5) {
            bare = bare.substring(5);
        }
        return new Resolved(chosen, bare, requested);
    }

    private static boolean safeSupports(LlmProvider p, String modelId) {
        try {
            return p.supportsModel(modelId);
        } catch (Exception e) {
            return false;
        }
    }

    private void requireCredential(Vendor vendor, String requested) {
        if (!registry.hasCredential(vendor)) {
            throw GatewayException.modelNotFound(requested);
        }
    }

    /** 模型卡: vendor + kernel Model. */
    public static final class ModelCard {
        private final Vendor vendor;
        private final Model model;

        public ModelCard(Vendor vendor, Model model) {
            this.vendor = vendor;
            this.model = model;
        }

        public Vendor vendor() {
            return vendor;
        }

        public Model model() {
            return model;
        }
    }
}
