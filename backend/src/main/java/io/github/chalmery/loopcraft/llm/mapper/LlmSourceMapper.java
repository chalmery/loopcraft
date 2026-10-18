package io.github.chalmery.loopcraft.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.llm.model.LlmSource;
import org.apache.ibatis.annotations.Select;

/** 租户供应商连接，密文仅供服务端使用。 */
public interface LlmSourceMapper extends BaseMapper<LlmSource> {
    // 同一来源的写入先锁来源，避免并发导入、改密钥和级联删除相互覆盖。
    @Select("SELECT * FROM lc_llm_source WHERE tenant_id = #{tenantId} AND id = #{sourceId} FOR UPDATE")
    LlmSource lock(long tenantId, long sourceId);
}
