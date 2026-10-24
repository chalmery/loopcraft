package io.github.chalmery.loopcraft.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.github.chalmery.loopcraft.skill.model.SkillObjectUpload;

import org.apache.ibatis.annotations.Select;

/** 普通查询与清理沿用 BaseMapper，行锁使用 PostgreSQL 的跳过已锁行能力。 */
public interface SkillObjectUploadMapper extends BaseMapper<SkillObjectUpload> {
    /** 上传及发布均锁住引用；清理任务不得处理正在上传或提交的对象。 */
    @Select("SELECT id FROM lc_skill_object_upload WHERE id = #{id} FOR UPDATE")
    Long lock(long id);

    /** 后台不等待正在处理的对象，避免持锁调用互相阻塞。 */
    @Select("SELECT id FROM lc_skill_object_upload WHERE id = #{id} FOR UPDATE SKIP LOCKED")
    Long tryLock(long id);
}
