package io.github.chalmery.loopcraft.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.chalmery.loopcraft.identity.model.Tenant;
import org.apache.ibatis.annotations.Select;

/** 租户数据访问，使用 MyBatis-Plus 基础操作。 */
public interface TenantMapper extends BaseMapper<Tenant> {
    /** MyBatis-Plus 无行锁接口，成员变更与负责人交接共用租户行锁。 */
    @Select("SELECT id FROM lc_tenant WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);
}
