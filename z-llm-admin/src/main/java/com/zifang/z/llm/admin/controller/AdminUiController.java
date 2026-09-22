package com.zifang.z.llm.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 最小化 UI — Thymeleaf 渲染概览页. 业务方可挂在前端网关后或自建完整 UI.
 */
@Controller
@RequestMapping("/z-llm/admin/ui")
public class AdminUiController {

    @GetMapping({"", "/", "/index"})
    public String index(Model model) {
        model.addAttribute("title", "z-llm 控制台");
        return "z-llm-admin/index";
    }
}