package com.zifang.z.llm.starter.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.core.credential.ApiKeyService;
import com.zifang.z.llm.core.properties.GatewayProperties;
import com.zifang.z.llm.core.registry.LlmCredentialStore;
import com.zifang.z.llm.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.core.service.ChatGatewayService;
import com.zifang.z.llm.core.service.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
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
    public ChatGatewayService chatGatewayService(LlmProviderRegistry registry,
                                                 LlmCredentialStore credentialStore) {
        return new ChatGatewayService(registry, credentialStore);
    }

    /**
     * 注入 ObjectMapper (确保 Spring Boot 的 JSON 序列化器能被 controller 复用).
     */
    @Autowired(required = false)
    public void setObjectMapper(ObjectMapper objectMapper) {
        // noop — Spring Boot 默认会注册 ObjectMapper bean, controller 通过构造注入即可
    }
}