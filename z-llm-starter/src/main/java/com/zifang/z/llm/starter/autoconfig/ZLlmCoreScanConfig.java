package com.zifang.z.llm.starter.autoconfig;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 扫描 z-llm-core 的 controller + service + registry + credential 包, 让 RestController 自动注册.
 */
@Configuration
@ComponentScan(basePackages = {
        "com.zifang.z.llm.core.controller",
        "com.zifang.z.llm.core.service",
        "com.zifang.z.llm.core.registry",
        "com.zifang.z.llm.core.credential"
})
public class ZLlmCoreScanConfig {
}