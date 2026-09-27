package com.zifang.z.hostapp.web;

import com.zifang.z.llm.api.exception.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宿主应用的替身 —— 复刻 z-opc-main-starter 那类"全公司兜底 advice + 自己的 controller".
 *
 * <p>包名必须满足两条: 在 {@code com.zifang} 之下 (所以宿主 advice 的 basePackages 罩得到它),
 * 又不在 z-llm advice 限定的两个包里 (所以网关 advice 不该管它). 因此本类不能和
 * {@code MergedProcessAdvicePrecedenceTest} 同包 —— 嵌套类改不了包名.
 *
 * <p>真参照: {@code z-opc/bootstraps/z-opc-main-starter/.../config/GlobalExceptionAdvice.java}
 * 是 {@code @RestControllerAdvice(basePackages = "com.zifang")} 且**没有 @Order**.
 * 这里刻意也不加 @Order, 保持"宿主无序、只靠注册序抢先"的真实形状.
 */
@RestController
@RequestMapping("/probe/host-like")
public class HostLikeWebSurface {

    /** 抛的是网关异常, 但 handler 类不在网关 advice 的包范围内 —— 该由宿主信封接. */
    @GetMapping("/rate-limited")
    public String rateLimited() {
        throw GatewayException.rateLimited("host controller hits gateway exception");
    }

    @RestControllerAdvice(basePackages = "com.zifang")
    public static class HostCatchAllAdvice {

        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, Object>> handle(Exception ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("hostEnvelope", true);
            body.put("message", "服务器内部错误: " + ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
