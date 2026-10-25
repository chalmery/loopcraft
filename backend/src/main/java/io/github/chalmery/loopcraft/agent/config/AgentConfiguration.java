package io.github.chalmery.loopcraft.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Agent 模块装配：仅注册全局预算配置，不注册模型或循环 Bean。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
}
