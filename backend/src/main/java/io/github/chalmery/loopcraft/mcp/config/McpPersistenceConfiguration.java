package io.github.chalmery.loopcraft.mcp.config;

import io.github.chalmery.loopcraft.mcp.mapper.McpServerMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 复用现有分页插件，常规测试不装配数据库。 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@MapperScan(basePackageClasses = McpServerMapper.class)
public class McpPersistenceConfiguration {}
