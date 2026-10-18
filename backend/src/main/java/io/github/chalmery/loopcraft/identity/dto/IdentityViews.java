package io.github.chalmery.loopcraft.identity.dto;

import java.util.List;

import io.github.chalmery.loopcraft.identity.model.TenantRole;
import io.github.chalmery.loopcraft.identity.model.UserAccount;

/** 对外响应，不包含密码哈希和凭证版本。 */
public final class IdentityViews {
    private IdentityViews() {
    }

    public record User(long id, String username, String displayName, boolean platformAdmin, boolean enabled) {
        public static User of(UserAccount user) {
            return new User(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getPlatformAdmin(), user.getEnabled());
        }
    }

    /** 租户成员的公开信息。 */
    public record Member(long userId, String username, String displayName, TenantRole role, boolean enabled) {
    }

    /** 添加成员时展示的账号选项。 */
    public record MemberCandidate(long id, String username, String displayName) {
        public static MemberCandidate of(UserAccount user) {
            return new MemberCandidate(user.getId(), user.getUsername(), user.getDisplayName());
        }
    }

    /** 分页列表包含总数，前端无需猜测是否还有下一页。 */
    public record PageResult<T>(List<T> records, long total, long page, long size) {
    }

    /** 租户选择携带当前用户角色，不依赖成员列表推断权限。 */
    public record AccessibleTenant(long id, String name, java.time.OffsetDateTime updatedAt, TenantRole myRole) {
    }

    /** 平台租户列表批量补齐负责人信息。 */
    public record ManagedTenant(long id, String name, long ownerUserId, String ownerUsername,
            String ownerDisplayName, java.time.OffsetDateTime updatedAt) {
    }

    public record Csrf(String headerName, String token) {
    }
}
