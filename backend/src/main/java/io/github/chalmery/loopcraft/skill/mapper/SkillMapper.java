package io.github.chalmery.loopcraft.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import org.apache.ibatis.annotations.Select;

/** 租户 Skill 基本信息，与包内文件独立。 */
public interface SkillMapper extends BaseMapper<Skill> {
    /** MyBatis-Plus 无行锁接口，串行化版本提交、基本信息修改与删除。 */
    @Select("SELECT id FROM lc_skill WHERE id = #{id} AND tenant_id = #{tenantId} FOR UPDATE")
    Long lock(long tenantId, long id);
}
