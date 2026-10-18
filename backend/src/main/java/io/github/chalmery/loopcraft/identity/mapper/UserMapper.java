package io.github.chalmery.loopcraft.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import org.apache.ibatis.annotations.Select;

/** 用户基础操作由 MyBatis-Plus 提供。 */
public interface UserMapper extends BaseMapper<UserAccount> {
    /** MyBatis-Plus 无行锁接口，串行处理账号编辑、删除与负责人任命。 */
    @Select("SELECT id FROM lc_user WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);

    /** 首次初始化管理员时串行执行，防止并发创建。 */
    @Select("SELECT pg_advisory_xact_lock(180018001)")
    String lockBootstrap();
}
