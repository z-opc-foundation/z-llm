/**
 * z-llm-api — 平台无关的公共 DTO 与异常类型.
 *
 * <p>网关核心 (z-llm-core) 与业务方可单独引用, 不依赖 Spring 与 kernel.
 * <p>OpenAI / Anthropic 等协议由 z-llm-core 内 mapper 转成 UnifiedRequest 后再下发.
 *
 * @author yuku123
 */
package com.zifang.z.llm.api;