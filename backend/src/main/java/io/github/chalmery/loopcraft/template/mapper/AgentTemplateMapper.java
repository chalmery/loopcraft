package io.github.chalmery.loopcraft.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;

/** 租户共享的 Agent 模板，只保存配置，不执行任务。 */
public interface AgentTemplateMapper extends BaseMapper<AgentTemplate> {
}
