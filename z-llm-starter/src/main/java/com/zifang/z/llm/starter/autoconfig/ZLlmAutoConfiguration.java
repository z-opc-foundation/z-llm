package com.zifang.z.llm.starter.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.core.auth.AccessControl;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.resilience.ProviderInvoker;
import com.zifang.z.llm.core.router.ModelRouter;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import com.zifang.z.llm.core.usage.UsageLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * z-llm starter 自动装配 — 把网关核心 bean (credential store, registry, gateway service, controllers) 注册到 Spring 容器.
 *
 * <p>Controller 通过 @ComponentScan 自动发现 (z-llm.core.controller 包).
 * <p>本 starter 不依赖 spring-boot-starter-web (由应用方决定是否引入 web 容器).
 *
 * <p>FEATURE066 2026-09-23: 加 @ConditionalOnProperty(name = "z.llm.enabled", havingValue = "true"),
 * 让 z-llm 与 z-opc 内部遗留的 z-agent-llm-gateway-* 共存. 老 Netty 网关走 z-agent.llm-gateway.enabled
 * 控制; 新 Spring MVC 网关走 z.llm.enabled 控制. 两者默认都不启用, 必须显式开.
 * 完全替换后, 此条件可去掉.
 */
@Configuration
@ConditionalOnProperty(name = "z.llm.enabled", havingValue = "true")
@EnableWebMvc
@EnableConfigurationProperties(GatewayProperties.class)
@Import(ZLlmCoreScanConfig.class)
public class ZLlmAutoConfiguration {

    @Bean
    public LlmCredentialStore llmCredentialStore(GatewayProperties properties) {
        return new LlmCredentialStore(properties);
    }

    @Bean
    public LlmProviderRegistry llmProviderRegistry(LlmCredentialStore credentialStore) {
        return new LlmProviderRegistry(credentialStore);
    }

    @Bean
    public ApiKeyService apiKeyService(GatewayProperties properties) {
        return new ApiKeyService(properties);
    }

    @Bean
    public RateLimiter rateLimiter(GatewayProperties properties) {
        return new RateLimiter(properties);
    }

    @Bean
    public ModelRouter modelRouter(LlmProviderRegistry registry, GatewayProperties properties) {
        return new ModelRouter(registry, properties);
    }

    @Bean
    public ProviderInvoker providerInvoker(GatewayProperties properties,
                                           LlmProviderRegistry registry,
                                           LlmCredentialStore credentialStore) {
        return new ProviderInvoker(properties, registry, credentialStore);
    }

    @Bean
    public UsageLedger usageLedger(GatewayProperties properties) {
        return new UsageLedger(properties);
    }

    @Bean
    public AccessControl accessControl(GatewayProperties properties) {
        return new AccessControl(properties);
    }

    @Bean
    public ChatGatewayService chatGatewayService(LlmProviderRegistry registry,
                                                 LlmCredentialStore credentialStore,
                                                 ModelRouter router,
                                                 ProviderInvoker invoker,
                                                 UsageLedger usageLedger,
                                                 RateLimiter rateLimiter,
                                                 AccessControl accessControl,
                                                 GatewayProperties properties) {
        return new ChatGatewayService(registry, credentialStore, router, invoker,
                usageLedger, rateLimiter, accessControl, properties);
    }

    /** ObjectMapper 由 Spring Boot 默认注册; 无 web-json 的容器下兜一个, 保证 controller 构造注入不断. */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper zLlmObjectMapper() {
        return new ObjectMapper();
    }
}
