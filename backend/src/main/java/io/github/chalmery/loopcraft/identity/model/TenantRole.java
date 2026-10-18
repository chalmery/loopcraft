package io.github.chalmery.loopcraft.identity.model;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 租户成员角色。 */
public enum TenantRole {
    OWNER("OWNER"),
    MEMBER("MEMBER");

    @EnumValue
    private final String value;

    TenantRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
