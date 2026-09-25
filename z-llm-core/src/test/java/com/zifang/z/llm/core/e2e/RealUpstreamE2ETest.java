package com.zifang.z.llm.core.e2e;

import com.zifang.z.llm.api.dto.ApiKey;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.EmbeddingsRequest;
import com.zifang.z.llm.api.dto.EmbeddingsResponse;
import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.UnifiedResponse;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.EmbeddingService;
import com.zifang.z.llm.core.service.MultimodalChatRelay;
import com.zifang.z.llm.core.service.RateLimiter;
import com.zifang.z.llm.core.support.TestFixtures;
import com.zifang.z.llm.core.upstream.LlmHttpUpstream;
import com.zifang.z.llm.core.upstream.UpstreamHttp;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 真实上游端到端 —— chat / stream / embeddings / 图片转发四条面.
 *
 * <p>为什么必须有真上游: 网关自造的假上游永远测不出"上游其实不认这个字段"这一类问题
 * (stream 只认 URL query 那个缺陷正是假上游看不见的那一类)。
 *
 * <p>为什么默认跳过: API key 属于部署环境, 不在仓库里。没配 key 时这些用例是
 * <b>skipped 而不是 passed</b> —— 记账时不能把它们算成已验证。
 *
 * <p>开启方式:
 * <pre>
 * Z_LLM_E2E_OPENAI_API_KEY=sk-... \
 * Z_LLM_E2E_ANTHROPIC_API_KEY=sk-ant-... \
 * mvn -o -pl z-llm-core -am test -Dtest=RealUpstreamE2ETest
 * </pre>
 * 模型名可用 {@code Z_LLM_E2E_OPENAI_MODEL} / {@code Z_LLM_E2E_OPENAI_EMBED_MODEL} /
 * {@code Z_LLM_E2E_ANTHROPIC_MODEL} 覆盖.
 */
public class RealUpstreamE2ETest {

    private static final String OPENAI_KEY_ENV = "Z_LLM_E2E_OPENAI_API_KEY";
    private static final String ANTHROPIC_KEY_ENV = "Z_LLM_E2E_ANTHROPIC_API_KEY";

    private GatewayProperties props;
    private ChatGatewayService service;
    private EmbeddingService embeddings;
    private final ApiKey key = TestFixtures.key("k-e2e", "sk-e2e", null, null);

    @Before
    public void setUp() {
        List<LlmCredential> creds = new ArrayList<>();
        String openai = System.getenv(OPENAI_KEY_ENV);
        if (openai != null && !openai.trim().isEmpty()) {
            creds.add(credential(Vendor.OPENAI, openai.trim()));
        }
        String anthropic = System.getenv(ANTHROPIC_KEY_ENV);
        if (anthropic != null && !anthropic.trim().isEmpty()) {
            creds.add(credential(Vendor.ANTHROPIC, anthropic.trim()));
        }
        assumeTrue("no upstream key configured via " + OPENAI_KEY_ENV + " / " + ANTHROPIC_KEY_ENV
                + " — real-upstream E2E skipped", !creds.isEmpty());

        props = new GatewayProperties();
        props.setCredentials(creds);
        LlmCredentialStore store = TestFixtures.store(props);
        // 不 replace(): 这里要的就是 registry 按凭据构造出来的真 provider.
        LlmProviderRegistry registry = new LlmProviderRegistry(store);
        ModelRouter router = new ModelRouter(registry, props);
        ProviderInvoker invoker = new ProviderInvoker(props, registry, store);
        UsageLedger ledger = new UsageLedger(props);
        RateLimiter rateLimiter = new RateLimiter(props);
        UpstreamHttp http = new LlmHttpUpstream(props.getUpstreamConnectTimeoutSec(),
                props.getUpstreamReadTimeoutSec(), props.getUpstreamWriteTimeoutSec());
        MultimodalChatRelay relay = new MultimodalChatRelay(store, invoker, ledger, rateLimiter, http);
        service = new ChatGatewayService(registry, store, router, invoker, ledger,
                rateLimiter, new AccessControl(props), props);
        service.setRelay(relay);
        embeddings = new EmbeddingService(props, store, router, invoker, ledger,
                rateLimiter, new AccessControl(props), http);
    }

    private static LlmCredential credential(Vendor vendor, String apiKey) {
        LlmCredential c = new LlmCredential();
        c.setVendor(vendor);
        c.setAlias("e2e-" + vendor.code());
        c.setApiKey(apiKey);
        c.setPriority(1);
        c.setStatus("active");
        return c;
    }

    private static String model(String env, String fallback) {
        String v = System.getenv(env);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    @Test
    public void openAiChatRoundTrip() {
        assumeTrue(OPENAI_KEY_ENV + " is required", System.getenv(OPENAI_KEY_ENV) != null);
        UnifiedResponse out = service.chat(request("openai/" + model("Z_LLM_E2E_OPENAI_MODEL",
                "gpt-4o-mini"), "Reply with exactly the word: pong"), key).response();

        String text = out.getChoices().get(0).getMessage().getContent();
        assertNotNull(text);
        assertFalse("真上游必须给出非空回答", text.trim().isEmpty());
        assertTrue("命名空间必须回显给客户端: " + out.getModel(), out.getModel().startsWith("openai/"));
        assertTrue("真实调用必须有 usage", out.getUsage().getTotalTokens() > 0);
    }

    @Test
    public void openAiStreamRoundTrip() {
        assumeTrue(OPENAI_KEY_ENV + " is required", System.getenv(OPENAI_KEY_ENV) != null);
        StringBuilder sb = new StringBuilder();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        service.streamChat(request("openai/" + model("Z_LLM_E2E_OPENAI_MODEL", "gpt-4o-mini"),
                        "Count from 1 to 5, digits only"), key,
                c -> {
                    if (c.getChoices() != null && !c.getChoices().isEmpty()
                            && c.getChoices().get(0).getDelta() != null
                            && c.getChoices().get(0).getDelta().getContent() != null) {
                        sb.append(c.getChoices().get(0).getDelta().getContent());
                    }
                },
                e -> errors.incrementAndGet(), completions::incrementAndGet);

        assertEquals("流式不应出错: " + errors.get(), 0, errors.get());
        assertEquals("收尾回调恰好一次", 1, completions.get());
        assertTrue("流式内容必须拼出正文: " + sb, sb.toString().contains("1"));
        assertTrue("必须含 5: " + sb, sb.toString().contains("5"));
    }

    @Test
    public void openAiEmbeddingsRoundTrip() {
        assumeTrue(OPENAI_KEY_ENV + " is required", System.getenv(OPENAI_KEY_ENV) != null);
        EmbeddingsResponse out = embeddings.embed(new EmbeddingsRequest(
                "openai/" + model("Z_LLM_E2E_OPENAI_EMBED_MODEL", "text-embedding-3-small"),
                Arrays.asList("hello world", "goodbye moon")), key);

        assertEquals(2, out.getData().size());
        int dims = out.getData().get(0).getEmbedding().size();
        assertTrue("向量维度不可能为 0", dims > 0);
        assertEquals("两条向量的维度必须一致", dims, out.getData().get(1).getEmbedding().size());
        assertFalse("向量不能是全零 (说明上游没真算)", out.getData().get(0).getEmbedding()
                .stream().allMatch(v -> v == 0.0));
    }

    @Test
    public void openAiImageRelayRoundTrip() {
        assumeTrue(OPENAI_KEY_ENV + " is required", System.getenv(OPENAI_KEY_ENV) != null);
        UnifiedRequest r = request("openai/" + model("Z_LLM_E2E_OPENAI_MODEL", "gpt-4o-mini"),
                "What single colour dominates this image? One word.");
        r.getMessages().get(0).setContents(Arrays.asList(
                textPart("What single colour dominates this image? One word."),
                imagePart(imageDataUrl())));
        r.getMessages().get(0).setContent(null);

        UnifiedResponse out = service.chat(r, key).response();
        String text = out.getChoices().get(0).getMessage().getContent();
        assertFalse("带图请求必须拿到回答: " + text, text == null || text.trim().isEmpty());
        // 图被摊平丢掉时上游只会按纯文本计费 (十几 token), 这条断言正是这个缺陷的探测器.
        assertTrue("图片必须真的进了上游 (prompt_tokens 应远高于纯文本): "
                        + out.getUsage().getPromptTokens(),
                out.getUsage().getPromptTokens() > 40);
    }

    @Test
    public void anthropicImageRelayRoundTrip() {
        assumeTrue(ANTHROPIC_KEY_ENV + " is required", System.getenv(ANTHROPIC_KEY_ENV) != null);
        UnifiedRequest r = request("anthropic/" + model("Z_LLM_E2E_ANTHROPIC_MODEL",
                        "claude-3-5-haiku-latest"),
                "What single colour dominates this image? One word.");
        r.getMessages().get(0).setContents(Arrays.asList(
                textPart("What single colour dominates this image? One word."),
                imagePart(imageDataUrl())));
        r.getMessages().get(0).setContent(null);
        r.setMaxTokens(64);

        UnifiedResponse out = service.chat(r, key).response();
        String text = out.getChoices().get(0).getMessage().getContent();
        assertFalse("Anthropic 原生转发必须拿到回答: " + text, text == null || text.trim().isEmpty());
        assertTrue("图片必须真的进了上游: " + out.getUsage().getPromptTokens(),
                out.getUsage().getPromptTokens() > 40);
    }

    // ---- 请求构造 ----

    private UnifiedRequest request(String model, String prompt) {
        UnifiedRequest r = new UnifiedRequest();
        r.setModel(model);
        UnifiedMessage m = new UnifiedMessage();
        m.setRole("user");
        m.setContent(prompt);
        r.setMessages(Collections.singletonList(m));
        return r;
    }

    private static ContentPart textPart(String text) {
        ContentPart p = new ContentPart();
        p.setType("text");
        p.setText(text);
        return p;
    }

    private static ContentPart imagePart(String dataUrl) {
        ContentPart p = new ContentPart();
        p.setType("image_url");
        ContentPart.ImageUrl img = new ContentPart.ImageUrl();
        img.setUrl(dataUrl);
        p.setImageUrl(img);
        return p;
    }

    /**
     * 现场画一张够大的纯色图 (240x240 实心绿 + 白块) 而不是内联一段 base64:
     * 手抄的像素串一旦不合法, 失败会看起来像"转发坏了"; 过小的图又会被上游以分辨率不足拒掉.
     */
    private static String imageDataUrl() {
        try {
            BufferedImage img = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 240; x++) {
                for (int y = 0; y < 240; y++) {
                    img.setRGB(x, y, (x > 90 && x < 150 && y > 90 && y < 150)
                            ? 0xFFFFFF : 0x1FA34A);
                }
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(img, "png", buf);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(buf.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("fixture image generation failed", e);
        }
    }
}
