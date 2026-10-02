package com.zifang.z.llm.config.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zifang.util.core.meta.Result;
import com.zifang.z.llm.config.dto.LlmModelDto;
import com.zifang.z.llm.config.dto.LlmProviderDto;
import com.zifang.z.llm.config.dto.ToolTemplateDto;
import com.zifang.z.llm.config.service.LlmConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * LLM 配置 API: 提供 Provider / Model / 工具模板的元数据管理.
 * <p>
 * 替代 z-opc 内 z-agent-llm-center（原 /api/llm-center/*）。
 * 新路径: /api/z-llm/config
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET    /provider/list — 查询所有 LLM 提供方</li>
 *   <li>GET    /provider?providerCode= — 按编码查询提供方</li>
 *   <li>POST   /provider/create — 创建 LLM 提供方</li>
 *   <li>POST   /provider/update — 更新 LLM 提供方</li>
 *   <li>POST   /provider/delete — 删除 LLM 提供方</li>
 *   <li>GET    /model/list — 查询模型列表 (可按 providerCode 过滤)</li>
 *   <li>GET    /model/page — 模型分页查询</li>
 *   <li>GET    /model?modelCode= — 按编码查询模型</li>
 *   <li>POST   /model/create — 创建模型</li>
 *   <li>POST   /model/update — 更新模型</li>
 *   <li>POST   /model/delete — 删除模型</li>
 *   <li>GET    /tool-template/list — 查询工具模板列表</li>
 *   <li>GET    /tool-template?toolCode= — 按编码查询工具模板</li>
 *   <li>POST   /tool-template/create — 创建工具模板</li>
 *   <li>POST   /tool-template/delete — 删除工具模板</li>
 * </ul>
 */
@Tag(name = "z-llm-配置")
@RestController
@RequestMapping("/api/z-llm/config")
public class LlmConfigController {

    @Resource
    private LlmConfigService llmConfigService;

    // ==================== Provider ====================

    @Operation(summary = "查询所有 LLM 提供方")
    @GetMapping("/provider/list")
    public Result<List<LlmProviderDto>> listProviders() {
        return Result.success(llmConfigService.listProviders());
    }

    @Operation(summary = "按编码查询 LLM 提供方")
    @GetMapping("/provider")
    public Result<LlmProviderDto> getProvider(@RequestParam String providerCode) {
        return Result.success(llmConfigService.getProvider(providerCode));
    }

    @Operation(summary = "创建 LLM 提供方")
    @PostMapping("/provider/create")
    public Result<Object> createProvider(@RequestBody LlmProviderDto dto) {
        llmConfigService.saveProvider(dto);
        return Result.success();
    }

    @Operation(summary = "更新 LLM 提供方")
    @PostMapping("/provider/update")
    public Result<Object> updateProvider(@RequestBody LlmProviderDto dto) {
        llmConfigService.saveProvider(dto);
        return Result.success();
    }

    @Operation(summary = "删除 LLM 提供方")
    @PostMapping("/provider/delete")
    public Result<Object> deleteProvider(@RequestBody Long id) {
        llmConfigService.deleteProvider(id);
        return Result.success();
    }

    // ==================== Model ====================

    @Operation(summary = "查询模型列表 (可按 providerCode 过滤)")
    @GetMapping("/model/list")
    public Result<List<LlmModelDto>> listModels(@RequestParam(required = false) String providerCode) {
        return Result.success(llmConfigService.listModels(providerCode));
    }

    @Operation(summary = "模型分页查询 (支持 providerCode 和关键字过滤)")
    @GetMapping("/model/page")
    public Result<Page<LlmModelDto>> pageModels(
            @RequestParam(required = false) String providerCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(llmConfigService.pageModels(providerCode, keyword, pageNum, pageSize));
    }

    @Operation(summary = "按编码查询模型")
    @GetMapping("/model")
    public Result<LlmModelDto> getModel(@RequestParam String modelCode) {
        return Result.success(llmConfigService.getModel(modelCode));
    }

    @Operation(summary = "创建模型")
    @PostMapping("/model/create")
    public Result<Object> createModel(@RequestBody LlmModelDto dto) {
        llmConfigService.saveModel(dto);
        return Result.success();
    }

    @Operation(summary = "更新模型")
    @PostMapping("/model/update")
    public Result<Object> updateModel(@RequestBody LlmModelDto dto) {
        llmConfigService.saveModel(dto);
        return Result.success();
    }

    @Operation(summary = "删除模型")
    @PostMapping("/model/delete")
    public Result<Object> deleteModel(@RequestBody Long id) {
        llmConfigService.deleteModel(id);
        return Result.success();
    }

    // ==================== Tool Template ====================

    @Operation(summary = "查询工具模板列表")
    @GetMapping("/tool-template/list")
    public Result<List<ToolTemplateDto>> listToolTemplates() {
        return Result.success(llmConfigService.listToolTemplates());
    }

    @Operation(summary = "按编码查询工具模板")
    @GetMapping("/tool-template")
    public Result<ToolTemplateDto> getToolTemplate(@RequestParam String toolCode) {
        return Result.success(llmConfigService.getToolTemplate(toolCode));
    }

    @Operation(summary = "创建工具模板")
    @PostMapping("/tool-template/create")
    public Result<Object> createToolTemplate(@RequestBody ToolTemplateDto dto) {
        llmConfigService.saveToolTemplate(dto);
        return Result.success();
    }

    @Operation(summary = "更新工具模板")
    @PostMapping("/tool-template/update")
    public Result<Object> updateToolTemplate(@RequestBody ToolTemplateDto dto) {
        llmConfigService.saveToolTemplate(dto);
        return Result.success();
    }

    @Operation(summary = "删除工具模板")
    @PostMapping("/tool-template/delete")
    public Result<Object> deleteToolTemplate(@RequestBody Long id) {
        llmConfigService.deleteToolTemplate(id);
        return Result.success();
    }
}