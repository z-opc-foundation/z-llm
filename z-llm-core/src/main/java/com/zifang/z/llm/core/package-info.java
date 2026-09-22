/**
 * z-llm-core — HTTP 网关核心.
 *
 * <p>职责: 暴露 OpenAI / Anthropic 双协议, 通过 z-agent-kernel-llm 调用各家 provider.
 * <p>依赖 z-llm-api + z-agent-kernel-llm, 不依赖 Spring Boot 自动装配 (由 z-llm-starter 提供).
 *
 * @author yuku123
 */
package com.zifang.z.llm.core;