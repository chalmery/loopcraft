package io.github.chalmery.loopcraft.identity.model;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 全局账号，平台权限与租户角色独立。 */
@TableName("lc_user")
public class UserAccount {
    /** 用户 ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，统一小写。 */
    private String username;

    /** 带算法标识的密码哈希。 */
    private String passwordHash;

    /** 显示名称。 */
    private String displayName;

    /** 是否为平台管理员。 */
    private boolean platformAdmin;

    /** 账号是否启用。 */
    private boolean enabled = true;

    /** 凭证版本，变更后旧会话失效。 */
    private long credentialVersion;

    /** 更新时间。 */
    private OffsetDateTime updatedAt;

    public UserAccount() {
    }

    public UserAccount(Long id, String username, String passwordHash, String displayName,
            boolean platformAdmin, boolean enabled, long credentialVersion) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.platformAdmin = platformAdmin;
        this.enabled = enabled;
        this.credentialVersion = credentialVersion;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean getPlatformAdmin() {
        return platformAdmin;
    }

    public void setPlatformAdmin(boolean platformAdmin) {
        this.platformAdmin = platformAdmin;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCredentialVersion() {
        return credentialVersion;
    }

    public void setCredentialVersion(long credentialVersion) {
        this.credentialVersion = credentialVersion;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
