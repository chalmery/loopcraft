package io.github.chalmery.loopcraft.identity.model;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 租户工作空间。 */
@TableName("lc_tenant")
public class Tenant {
    /** 租户 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户名称。 */
    private String name;

    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    public Tenant() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
