package com.zifang.z.llm.core.registry;

import com.zifang.z.llm.api.dto.LlmCredential;
import com.zifang.z.llm.api.dto.Vendor;
import com.zifang.z.llm.api.exception.GatewayException;
import com.zifang.z.llm.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据仓库 — 持有所有 LlmCredential, 提供按 vendor + alias 查询.
 *
 * <p>本类是网关 AK 集中地. 由 z-llm-admin 通过 refresh() 刷新 (配置中心场景).
 * <p>线程安全: 用 ConcurrentHashMap + 不可变快照避免读写竞争.
 */
public class LlmCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(LlmCredentialStore.class);

    /** vendor → 该 vendor 的凭据列表 (按 priority 升序). */
    private final Map<Vendor, List<LlmCredential>> byVendor = new ConcurrentHashMap<>();

    /** alias → 凭据 (单一映射, 假设 alias 全局唯一). */
    private final Map<String, LlmCredential> byAlias = new ConcurrentHashMap<>();

    public LlmCredentialStore(GatewayProperties properties) {
        reload(properties);
    }

    /** 从配置重新加载. */
    public synchronized void reload(GatewayProperties properties) {
        Map<Vendor, List<LlmCredential>> newByVendor = new EnumMap<>(Vendor.class);
        Map<String, LlmCredential> newByAlias = new ConcurrentHashMap<>();

        for (LlmCredential cred : properties.getCredentials()) {
            if (cred.getVendor() == null) {
                log.warn("Skip credential without vendor: alias={}", cred.getAlias());
                continue;
            }
            if (cred.getAlias() == null || cred.getAlias().isEmpty()) {
                log.warn("Skip credential without alias: vendor={}", cred.getVendor());
                continue;
            }
            newByVendor.computeIfAbsent(cred.getVendor(), v -> new ArrayList<>()).add(cred);
            newByAlias.put(cred.getAlias(), cred);
        }

        // 按 priority 升序
        for (List<LlmCredential> list : newByVendor.values()) {
            list.sort((a, b) -> {
                Integer pa = a.getPriority() == null ? 100 : a.getPriority();
                Integer pb = b.getPriority() == null ? 100 : b.getPriority();
                return pa.compareTo(pb);
            });
        }

        this.byVendor.clear();
        this.byVendor.putAll(newByVendor);
        this.byAlias.clear();
        this.byAlias.putAll(newByAlias);
        log.info("LlmCredentialStore loaded: vendors={}, total={}",
                byVendor.size(), newByAlias.size());
    }

    /** 按 vendor 列出所有可用凭据 (已按 priority 排序). */
    public List<LlmCredential> findByVendor(Vendor vendor) {
        if (vendor == null) {
            return java.util.Collections.emptyList();
        }
        List<LlmCredential> list = byVendor.get(vendor);
        if (list == null) {
            return java.util.Collections.emptyList();
        }
        // 过滤掉非 active 的
        List<LlmCredential> active = new ArrayList<>();
        for (LlmCredential c : list) {
            String status = c.getStatus();
            if (status == null || "active".equalsIgnoreCase(status)) {
                active.add(c);
            }
        }
        return active;
    }

    /** 按 alias 查找. */
    public LlmCredential findByAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return byAlias.get(alias);
    }

    /** 取该 vendor 的最优凭据 (priority 最低的 active 凭据). */
    public LlmCredential pick(Vendor vendor) {
        List<LlmCredential> list = findByVendor(vendor);
        if (list.isEmpty()) {
            throw GatewayException.internal("No active credential for vendor " + vendor, null);
        }
        return list.get(0);
    }

    public int size() {
        return byAlias.size();
    }
}