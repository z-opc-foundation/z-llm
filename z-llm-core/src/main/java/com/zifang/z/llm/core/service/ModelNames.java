package com.zifang.z.llm.core.service;

import com.zifang.z.llm.core.router.ModelRouter;

/**
 * 对外回显的 model id: 上游只给裸名时补回调用方使用的 vendor 命名空间.
 *
 * <p>主链路 (kernel provider) 与多模态直连转发共用同一份规则, 否则同一模型经两条路
 * 会得到两种 id, 客户端的 model 断言会时灵时不灵。
 */
public final class ModelNames {

    private ModelNames() {}

    public static String external(String served, ModelRouter.Resolved resolved) {
        if (served == null || served.isEmpty()) {
            return resolved.canonical();
        }
        if (served.contains("/")) {
            return served;
        }
        return resolved.vendor().code() + "/" + served;
    }
}
