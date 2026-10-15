package com.zifang.z.llm.core.service;

import com.zifang.util.json.JsonCursor;
import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.EmbeddingsRequest;
import com.zifang.z.llm.api.dto.EmbeddingsResponse;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.UsageInfo;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.upstream.UpstreamEndpoints;
import com.zifang.z.llm.core.upstream.UpstreamHttp;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/embeddings} —— 网关侧向量化.
 *
 * <p>为什么不经 kernel provider: kernel 的 {@code LlmProvider} 只有 chat/streamChat,
 * 没有 embedding 方法 (实测 kernel-llm 6 个 provider 全数如此), 所以向量请求只能由网关
 * 按各家真实端点直连。凭据选择、冷却与 failover 仍复用 {@link ProviderInvoker},
 * 避免两套韧性逻辑。
 *
 * <p>记账: 向量调用只有输入 token, 因此 usage 只落 prompt 侧, 与 OpenAI 的
 * {@code usage:{prompt_tokens,total_tokens}} 形状一致。
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final GatewayProperties properties;
    private final LlmCredentialStore credentialStore;
    private final ModelRouter router;
    private final ProviderInvoker invoker;
    private final UsageLedger usageLedger;
    private final RateLimiter rateLimiter;
    private final AccessControl accessControl;
    private final UpstreamHttp http;

    public EmbeddingService(GatewayProperties properties,
                            LlmCredentialStore credentialStore,
                            ModelRouter router,
                            ProviderInvoker invoker,
                            UsageLedger usageLedger,
                            RateLimiter rateLimiter,
                            AccessControl accessControl,
                            UpstreamHttp http) {
        this.properties = properties;
        this.credentialStore = credentialStore;
        this.router = router;
        this.invoker = invoker;
        this.usageLedger = usageLedger;
        this.rateLimiter = rateLimiter;
        this.accessControl = accessControl;
        this.http = http;
    }

    public EmbeddingsResponse embed(EmbeddingsRequest req, ApiKey key) {
        if (req == null || req.getModel() == null || req.getModel().trim().isEmpty()) {
            throw GatewayException.invalidRequest("'model' is required");
        }
        List<String> inputs = req.inputs();
        if (inputs.isEmpty()) {
            throw GatewayException.invalidRequest(
                    "'input' must be a non-empty string or a non-empty array of strings");
        }
        int cap = properties.getMaxEmbeddingBatch();
        if (cap > 0 && inputs.size() > cap) {
            throw GatewayException.invalidRequest(
                    "'input' carries " + inputs.size() + " items, over the gateway batch limit " + cap);
        }

        ModelRouter.Resolved resolved = route(req.getModel().trim());
        if (accessControl != null) {
            accessControl.authorize(key, resolved, null);
        }
        final Vendor vendor = resolved.vendor();
        final String bare = resolved.bareModel();
        if (!UpstreamEndpoints.supportsEmbeddings(vendor)) {
            throw GatewayException.invalidRequest(
                    "vendor " + vendor.code() + " has no embeddings endpoint");
        }

        JsonCursor raw = invoker.execute(vendor, invoker.attemptsFor(vendor), h -> {
            LlmCredential cred = credentialStore.findByAlias(h.alias());
            if (cred == null) {
                throw GatewayException.internal("credential " + h.alias() + " vanished", null);
            }
            return http.postJson(UpstreamEndpoints.embeddingsUrl(cred, vendor, bare),
                    UpstreamEndpoints.headers(cred, vendor),
                    buildBody(vendor, bare, inputs, req));
        });

        EmbeddingsResponse out = parse(vendor, raw, inputs.size());
        out.setModel(resolved.canonical());
        settle(key, vendor, bare, out.getUsage());
        return out;
    }

    // ---- 路由 ----

    /**
     * 向量模型不在任何 provider 声明的模型清单里, {@link ModelRouter#resolve} 按能力判定必然 404,
     * 因此这里补两条路: 显式 vendor 前缀 / {@code z.llm.embedding-vendor} 归属,
     * 以及"只有一个可服务 vendor 时自动归它"。
     */
    ModelRouter.Resolved route(String model) {
        Vendor forced = forcedVendor();
        if (forced != null) {
            String bare = stripPrefix(model, forced.code());
            requireCredential(forced, model);
            return new ModelRouter.Resolved(forced, bare, model);
        }
        try {
            return router.resolve(model);
        } catch (GatewayException notFound) {
            List<Vendor> candidates = new ArrayList<>();
            for (Vendor v : Vendor.values()) {
                if (UpstreamEndpoints.supportsEmbeddings(v) && credentialStore.findByVendor(v).size() > 0) {
                    candidates.add(v);
                }
            }
            if (candidates.size() == 1) {
                Vendor v = candidates.get(0);
                log.info("embedding model {} is not in any provider catalogue, routed to sole "
                        + "embedding-capable vendor {}", model, v.code());
                return new ModelRouter.Resolved(v, stripPrefix(model, v.code()), model);
            }
            throw GatewayException.modelNotFound(model + " (no embeddings match; prefix it with a "
                    + "vendor such as openai/ or set z.llm.embedding-vendor)");
        }
    }

    private Vendor forcedVendor() {
        String configured = properties.getEmbeddingVendor();
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        Vendor v = Vendor.fromModel(configured.trim());
        if (v == null) {
            throw GatewayException.internal(
                    "z.llm.embedding-vendor=" + configured + " is not a known vendor", null);
        }
        return v;
    }

    private void requireCredential(Vendor vendor, String model) {
        if (credentialStore.findByVendor(vendor).isEmpty()) {
            throw GatewayException.modelNotFound(model);
        }
    }

    private static String stripPrefix(String model, String vendorCode) {
        if (model.startsWith(vendorCode + "/") && model.length() > vendorCode.length() + 1) {
            return model.substring(vendorCode.length() + 1);
        }
        return model;
    }

    // ---- 各家请求体 ----

    Map<String, Object> buildBody(Vendor vendor, String bare, List<String> inputs, EmbeddingsRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", bare);
        switch (vendor) {
            case DASHSCOPE:
                // 原生形状: input 是对象 {texts: [...]}, 与 OpenAI 的裸数组不同.
                Map<String, Object> input = new LinkedHashMap<>();
                body.put("input", input);
                input.put("texts", new ArrayList<Object>(inputs));
                // 原来是一个空对象, 这里保持同形状.
                body.put("parameters", new LinkedHashMap<String, Object>());
                break;
            case GEMINI:
                List<Object> requests = new ArrayList<>();
                body.put("requests", requests);
                for (String text : inputs) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    requests.add(r);
                    r.put("model", "models/" + bare);
                    Map<String, Object> content = new LinkedHashMap<>();
                    r.put("content", content);
                    List<Object> parts = new ArrayList<>();
                    content.put("parts", parts);
                    parts.add(one("text", text));
                    if (req.getDimensions() != null) {
                        r.put("outputDimensionality", req.getDimensions());
                    }
                }
                break;
            default:
                body.put("input", new ArrayList<Object>(inputs));
                if (req.getDimensions() != null) {
                    body.put("dimensions", req.getDimensions());
                }
                if (req.getEncodingFormat() != null) {
                    body.put("encoding_format", req.getEncodingFormat());
                }
                if (req.getUser() != null) {
                    body.put("user", req.getUser());
                }
                break;
        }
        return body;
    }

    private static Map<String, Object> one(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // ---- 各家响应体 ----

    EmbeddingsResponse parse(Vendor vendor, JsonCursor raw, int expected) {
        if (raw == null || raw.isNull() || raw.isMissing()) {
            throw GatewayException.upstreamFailed("embeddings upstream returned an empty body", null);
        }
        List<EmbeddingsResponse.Item> items = new ArrayList<>();
        UsageInfo usage = null;
        switch (vendor) {
            case DASHSCOPE:
                JsonCursor emb = raw.path("output").path("embeddings");
                if (!emb.isArray()) {
                    throw GatewayException.upstreamFailed(
                            "dashscope embeddings response has no output.embeddings", null);
                }
                for (JsonCursor e : emb.elements()) {
                    items.add(new EmbeddingsResponse.Item(
                            e.path("text_index").asInt(items.size()), readVector(e.path("embedding"))));
                }
                long total = raw.path("usage").path("total_tokens").asLong(0L);
                if (total > 0) {
                    usage = new UsageInfo((int) total, 0, (int) total);
                }
                break;
            case GEMINI:
                JsonCursor gemini = raw.path("embedding");
                if (!gemini.isArray()) {
                    throw GatewayException.upstreamFailed(
                            "gemini embeddings response has no embedding array", null);
                }
                for (JsonCursor e : gemini.elements()) {
                    items.add(new EmbeddingsResponse.Item(
                            items.size(), readVector(e.path("values"))));
                }
                break;
            default:
                JsonCursor data = raw.path("data");
                if (!data.isArray()) {
                    throw GatewayException.upstreamFailed(
                            "embeddings response has no data array", null);
                }
                for (JsonCursor e : data.elements()) {
                    items.add(new EmbeddingsResponse.Item(
                            e.path("index").asInt(items.size()), readVector(e.path("embedding"))));
                }
                JsonCursor u = raw.path("usage");
                if (u.isObject()) {
                    int prompt = u.path("prompt_tokens").asInt(0);
                    int tot = u.path("total_tokens").asInt(prompt);
                    usage = new UsageInfo(prompt, 0, tot);
                }
                break;
        }
        Collections.sort(items, (a, b) -> Integer.compare(
                a.getIndex() == null ? 0 : a.getIndex(), b.getIndex() == null ? 0 : b.getIndex()));
        if (items.size() != expected) {
            // 少给一条还要当成成功返回的话, 下游会把向量按错位的文本存进索引.
            throw GatewayException.upstreamFailed("embeddings upstream returned " + items.size()
                    + " vectors for " + expected + " inputs", null);
        }
        EmbeddingsResponse out = new EmbeddingsResponse();
        out.setObject("list");
        out.setData(items);
        out.setUsage(usage);
        return out;
    }

    private static List<Double> readVector(JsonCursor arr) {
        List<Double> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonCursor n : arr.elements()) {
                out.add(n.asDouble(0D));
            }
        }
        return out;
    }

    private void settle(ApiKey key, Vendor vendor, String bare, UsageInfo usage) {
        long prompt = usage == null || usage.getPromptTokens() == null ? 0L : usage.getPromptTokens();
        UsageLedger.Record rec = usageLedger.record(key, vendor, bare, prompt, 0L);
        if (rateLimiter != null && key != null && prompt > 0) {
            rateLimiter.recordTokenUsage(key, prompt);
        }
        if (log.isDebugEnabled()) {
            log.debug("embeddings {} served by {} prompt_tokens {}", bare, vendor.code(), prompt);
        }
        if (rec != null && Double.isNaN(rec.cost())) {
            log.warn("embeddings cost came back NaN for {} — check pricing config", bare);
        }
    }
}
