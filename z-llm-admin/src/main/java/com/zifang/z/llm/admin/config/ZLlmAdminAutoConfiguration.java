package com.zifang.z.llm.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-llm 控制面自动装配 — 把 admin REST/UI/查询服务注册进容器.
 *
 * <p>默认不注册: 控制面能列出全部接入凭证与 vendor 凭据, 只能给部署方自己看,
 * 因此必须 {@code z.llm.expose-admin=true} 显式打开。
 *
 * <p>开关写在控制器上 (不只是这里), 因为宿主应用若自己 {@code @ComponentScan("com.zifang.z.llm")}
 * 会绕过本配置类直接扫到控制器。
 *
 * <p>前置条件: 同时需要 {@code z.llm.enabled=true} —— 控制器依赖的 LlmCredentialStore /
 * ApiKeyService / RateLimiter 都由 {@code ZLlmAutoConfiguration} 提供, 只开 expose-admin
 * 会在启动时缺 bean 直接失败 (按设计: 没有网关就没有网关的控制面)。
 */
@Configuration
@ConditionalOnProperty(name = "z.llm.expose-admin", havingValue = "true")
@ComponentScan(basePackages = {
        "com.zifang.z.llm.admin.controller",
        "com.zifang.z.llm.admin.service"
})
public class ZLlmAdminAutoConfiguration {
}
