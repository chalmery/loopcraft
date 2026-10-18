package io.github.chalmery.loopcraft.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;

/** 连接下的模型配置，不包含运行参数。 */
public interface LlmModelMapper extends BaseMapper<LlmModel> {
}
