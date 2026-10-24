package io.github.chalmery.loopcraft.template.config;

import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 常规测试不装配模板数据源。 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@MapperScan(basePackageClasses = AgentTemplateMapper.class)
public class TemplateConfiguration {
}
