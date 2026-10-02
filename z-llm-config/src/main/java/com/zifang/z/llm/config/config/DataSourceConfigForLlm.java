package com.zifang.z.llm.config.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zifang.z.boot.datasource.starter.ModuleDataSourceTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

@Configuration
@MapperScan(basePackages = "com.zifang.z.llm.config.mapper", sqlSessionFactoryRef = "sqlSessionFactoryLlmConfig")
public class DataSourceConfigForLlm extends ModuleDataSourceTemplate {

    @Bean("dataSourceLlmConfig")
    public DataSource dataSource(Environment env) {
        return buildDataSource(env, "default");
    }

    @Bean("sqlSessionFactoryLlmConfig")
    public SqlSessionFactory sqlSessionFactoryLlmConfig(DataSource dataSourceLlmConfig) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSourceLlmConfig);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/**/*.xml"));
        factoryBean.setTypeAliasesPackage("com.zifang.z.llm.config.entity");
        return factoryBean.getObject();
    }
}