package com.zifang.z.llm.starter.smoke;

import com.zifang.z.llm.core.controller.AnthropicController;
import com.zifang.z.llm.core.controller.OpenAIController;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.starter.autoconfig.ZLlmAutoConfiguration;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * z-llm 冒烟测试 — 验证 auto-config 能正常挂载, 所有 bean 被 Spring 容器管理.
 *
 * <p>不依赖 z-opc, 用最小化 Spring Boot context 模拟引入 z-llm-starter 的场景.
 */
public class ZLlmAutoConfigurationSmokeTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    ZLlmAutoConfiguration.class));

    @Test
    public void auto_config_registers_all_gateway_beans() {
        contextRunner.withPropertyValues(
                "z.llm.credentials[0].alias=openai-primary",
                "z.llm.credentials[0].vendor=OPENAI",
                "z.llm.credentials[0].api-key=sk-test",
                "z.llm.credentials[0].priority=10",
                "z.llm.credentials[0].status=active"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmCredentialStore.class);
            assertThat(ctx).hasSingleBean(LlmProviderRegistry.class);
            assertThat(ctx).hasSingleBean(ChatGatewayService.class);
            assertThat(ctx).hasSingleBean(OpenAIController.class);
            assertThat(ctx).hasSingleBean(AnthropicController.class);
            // 配置 1 个凭据后, registry 内 1 个 cached provider
            assertThat(ctx.getBean(LlmProviderRegistry.class).keys().size()).isEqualTo(1);
        });
    }

    @Test
    public void application_yaml_credentials_loaded_into_store() {
        contextRunner.withPropertyValues(
                "z.llm.credentials[0].alias=openai-primary",
                "z.llm.credentials[0].vendor=OPENAI",
                "z.llm.credentials[0].api-key=sk-test",
                "z.llm.credentials[0].priority=10",
                "z.llm.credentials[0].status=active",
                "z.llm.api-keys[0].id=k1",
                "z.llm.api-keys[0].key=sk-gw-test",
                "z.llm.api-keys[0].status=active"
        ).run(ctx -> {
            LlmCredentialStore store = ctx.getBean(LlmCredentialStore.class);
            assertThat(store.size()).isEqualTo(1);
            assertThat(store.pick(com.zifang.z.llm.api.dto.Vendor.OPENAI).getApiKey())
                    .isEqualTo("sk-test");
        });
    }
}