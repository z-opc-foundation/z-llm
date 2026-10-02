package com.zifang.z.llm.config.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zifang.z.llm.config.dto.LlmModelDto;
import com.zifang.z.llm.config.dto.LlmProviderDto;
import com.zifang.z.llm.config.dto.ToolTemplateDto;
import com.zifang.z.llm.config.entity.AgentToolTemplate;
import com.zifang.z.llm.config.entity.LlmModel;
import com.zifang.z.llm.config.entity.LlmProvider;
import com.zifang.z.llm.config.mapper.AgentToolTemplateMapper;
import com.zifang.z.llm.config.mapper.LlmModelMapper;
import com.zifang.z.llm.config.mapper.LlmProviderMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LlmConfigService {

    @Autowired(required = false)
    private LlmProviderMapper providerMapper;
    @Autowired(required = false)
    private LlmModelMapper modelMapper;
    @Autowired(required = false)
    private AgentToolTemplateMapper toolTemplateMapper;

    // ==================== Provider ====================

    public List<LlmProviderDto> listProviders() {
        LambdaQueryWrapper<LlmProvider> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(LlmProvider::getPriority);
        return providerMapper.selectList(wrapper).stream()
                .map(this::toProviderDto)
                .collect(Collectors.toList());
    }

    public LlmProviderDto getProvider(String providerCode) {
        LambdaQueryWrapper<LlmProvider> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmProvider::getProviderCode, providerCode);
        LlmProvider p = providerMapper.selectOne(wrapper);
        return p != null ? toProviderDto(p) : null;
    }

    public void saveProvider(LlmProviderDto dto) {
        LlmProvider p = new LlmProvider();
        BeanUtils.copyProperties(dto, p);
        if (dto.getId() != null) {
            providerMapper.updateById(p);
        } else {
            providerMapper.insert(p);
        }
    }

    public void deleteProvider(Long id) {
        providerMapper.deleteById(id);
    }

    // ==================== Model ====================

    public List<LlmModelDto> listModels(String providerCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getEnabled, 1);
        if (StringUtils.hasText(providerCode)) {
            wrapper.eq(LlmModel::getProviderCode, providerCode);
        }
        wrapper.orderByDesc(LlmModel::getGmtCreate);
        return modelMapper.selectList(wrapper).stream()
                .map(this::toModelDto)
                .collect(Collectors.toList());
    }

    public List<LlmModelDto> listModelsByProvider(String providerCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getEnabled, 1);
        wrapper.eq(StringUtils.hasText(providerCode), LlmModel::getProviderCode, providerCode);
        return modelMapper.selectList(wrapper).stream()
                .map(this::toModelDto)
                .collect(Collectors.toList());
    }

    public Page<LlmModelDto> pageModels(String providerCode, String keyword, int pageNum, int pageSize) {
        Page<LlmModel> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(providerCode)) {
            wrapper.eq(LlmModel::getProviderCode, providerCode);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(LlmModel::getModelName, keyword).or()
                    .like(LlmModel::getModelCode, keyword);
        }
        wrapper.orderByDesc(LlmModel::getGmtCreate);
        Page<LlmModel> result = modelMapper.selectPage(page, wrapper);
        Page<LlmModelDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(result.getRecords().stream().map(this::toModelDto).collect(Collectors.toList()));
        return dtoPage;
    }

    public LlmModelDto getModel(String modelCode) {
        LambdaQueryWrapper<LlmModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModel::getModelCode, modelCode);
        LlmModel m = modelMapper.selectOne(wrapper);
        return m != null ? toModelDto(m) : null;
    }

    public void saveModel(LlmModelDto dto) {
        LlmModel m = new LlmModel();
        BeanUtils.copyProperties(dto, m);
        if (dto.getId() != null) {
            modelMapper.updateById(m);
        } else {
            modelMapper.insert(m);
        }
    }

    public void deleteModel(Long id) {
        modelMapper.deleteById(id);
    }

    // ==================== Tool Template ====================

    public List<ToolTemplateDto> listToolTemplates() {
        LambdaQueryWrapper<AgentToolTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolTemplate::getEnabled, 1);
        return toolTemplateMapper.selectList(wrapper).stream()
                .map(this::toToolTemplateDto)
                .collect(Collectors.toList());
    }

    public ToolTemplateDto getToolTemplate(String toolCode) {
        LambdaQueryWrapper<AgentToolTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolTemplate::getToolCode, toolCode);
        AgentToolTemplate t = toolTemplateMapper.selectOne(wrapper);
        return t != null ? toToolTemplateDto(t) : null;
    }

    public void saveToolTemplate(ToolTemplateDto dto) {
        AgentToolTemplate t = new AgentToolTemplate();
        BeanUtils.copyProperties(dto, t);
        if (dto.getId() != null) {
            toolTemplateMapper.updateById(t);
        } else {
            toolTemplateMapper.insert(t);
        }
    }

    public void deleteToolTemplate(Long id) {
        toolTemplateMapper.deleteById(id);
    }

    // ==================== DTO Converters ====================

    private LlmProviderDto toProviderDto(LlmProvider p) {
        LlmProviderDto dto = new LlmProviderDto();
        BeanUtils.copyProperties(p, dto);
        return dto;
    }

    private LlmModelDto toModelDto(LlmModel m) {
        LlmModelDto dto = new LlmModelDto();
        BeanUtils.copyProperties(m, dto);
        return dto;
    }

    private ToolTemplateDto toToolTemplateDto(AgentToolTemplate t) {
        ToolTemplateDto dto = new ToolTemplateDto();
        BeanUtils.copyProperties(t, dto);
        return dto;
    }
}