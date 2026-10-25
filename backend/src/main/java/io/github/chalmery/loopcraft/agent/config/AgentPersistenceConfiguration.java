package io.github.chalmery.loopcraft.agent.config;

import io.github.chalmery.loopcraft.agent.mapper.AgentRunMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 复用现有分页插件，常规测试不装配数据库。 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@MapperScan(basePackageClasses = AgentRunMapper.class)
public class AgentPersistenceConfiguration {
}
