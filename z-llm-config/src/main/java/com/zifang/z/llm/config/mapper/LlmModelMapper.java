package com.zifang.z.llm.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zifang.z.llm.config.entity.LlmModel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmModelMapper extends BaseMapper<LlmModel> {
}