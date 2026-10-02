package com.zifang.z.llm.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zifang.z.llm.config.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProvider> {
}