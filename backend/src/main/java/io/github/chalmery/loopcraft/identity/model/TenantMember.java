package io.github.chalmery.loopcraft.identity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 租户与用户的成员关系。 */
@TableName("lc_tenant_member")
public class TenantMember {
    /** 关系 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 成员用户 ID。 */
    private Long userId;

    /** 租户成员角色。 */
    private TenantRole role;

    public TenantMember() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public TenantRole getRole() {
        return role;
    }

    public void setRole(TenantRole role) {
        this.role = role;
    }
}
