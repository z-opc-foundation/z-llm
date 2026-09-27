package com.zifang.z.llm.core.controller;

import com.zifang.z.hostapp.web.HostLikeWebSurface;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合并进程里的 advice 优先级闸 —— 网关 advice 必须既"抢先"又"不越界".
 *
 * <p><b>为什么要这一层</b>: 网关单独跑 (standaloneSetup / 独立启动) 一直是对的, 而 z-opc 把
 * L3 库和自己装进同一个 JVM 后, {@code GatewayException} 的 429/400/502 全被宿主 advice 压成
 * 500 + {@code Result} 信封 (2026-09-27 本机 fat jar 实测, 见 {@code ~/.cache/zopc-bootfix/evidence-34.md §4}).
 * 根因是 Spring 解析异常时**按 advice 顺序逐个问"你有没有这个异常的 handler", 第一个命中的赢**,
 * 不是全进程挑最具体的 handler; 宿主那个 {@code basePackages="com.zifang"} + {@code Exception} 兑底
 * 注册在前, 于是每次都轮先到它. 这个形状在 standaloneSetup 下结构上测不到, 必须造一个真容器.
 *
 * <p><b>两条腿各自钉住一个修复手段</b> (缺一根就有一例红):
 * <ul>
 *   <li>{@link #gatewayScopedEndpointKeepsHttpStatusAheadOfHostCatchAll()} — 摘掉 {@code @Order} 即红:
 *       宿主 advice 无序 (退到 LOWEST_PRECEDENCE), 网关也无序时变成平手, 由注册序决定, 而宿主先注册.</li>
 *   <li>{@link #hostScopedEndpointKeepsHostEnvelope()} — 摘掉 {@code @ControllerAdvice(basePackages=...)}
 *       即红: 最高优先级的网关 advice 会连别家 controller 一起吃掉, 把宿主的错误语义反向破坏.</li>
 * </ul>
 *
 * <p>用 {@link AnnotationConfigWebApplicationContext} 而不是 {@code standaloneSetup}: 后者把 advice
 * 实例直接塞进 resolver, 绕过 {@code ControllerAdviceBean.findAnnotatedBeans} 的包名匹配与排序,
 * 正好是本次要测的两个机制.
 */
public class MergedProcessAdvicePrecedenceTest {

    private AnnotationConfigWebApplicationContext wac;
    private MockMvc mvc;

    @Before
    public void setUp() {
        wac = new AnnotationConfigWebApplicationContext();
        // 宿主 advice 排在前: 复现"宿主先注册"的真实顺序, 这样平手时它赢, 网关只能靠 @Order 抢回来.
        wac.register(WebConfig.class,
                HostLikeWebSurface.HostCatchAllAdvice.class,
                GlobalExceptionHandler.class,
                GatewayScopedProbe.class,
                HostLikeWebSurface.class);
        wac.setServletContext(new MockServletContext());
        wac.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @After
    public void tearDown() {
        if (wac != null) {
            wac.close();
        }
    }

    @Test
    public void gatewayScopedEndpointKeepsHttpStatusAheadOfHostCatchAll() throws Exception {
        mvc.perform(get("/probe/gateway-scoped/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("gateway_error"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andExpect(jsonPath("$.hostEnvelope").doesNotExist());
    }

    @Test
    public void hostScopedEndpointKeepsHostEnvelope() throws Exception {
        mvc.perform(get("/probe/host-like/rate-limited"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.hostEnvelope").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Configuration
    @EnableWebMvc
    static class WebConfig {
    }

    /** 与网关 controller 同包 —— {@code @ControllerAdvice(basePackages)} 罩得住它. */
    @RestController
    @RequestMapping("/probe/gateway-scoped")
    static class GatewayScopedProbe {

        @GetMapping("/rate-limited")
        public String rateLimited() {
            throw com.zifang.z.llm.api.exception.GatewayException
                    .rateLimited("gateway controller hits rate limit");
        }
    }
}
