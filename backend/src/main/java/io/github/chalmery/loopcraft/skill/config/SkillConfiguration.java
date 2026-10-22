package io.github.chalmery.loopcraft.skill.config;

import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Skill 使用已有数据源和分页插件，删除对象的清理可重试。 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableScheduling
@MapperScan(basePackageClasses = SkillMapper.class)
public class SkillConfiguration { }
