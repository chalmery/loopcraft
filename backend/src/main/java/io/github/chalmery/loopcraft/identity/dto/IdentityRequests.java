package io.github.chalmery.loopcraft.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 账号与租户接口的请求参数。 */
public final class IdentityRequests {
    private IdentityRequests() {
    }

    public record Login(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    /** 用户名统一保存为小写；密码至少 12 个字符。 */
    public record CreateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{2,63}") String username,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName) {
    }

    /** 管理员编辑账号，不允许通过此接口提升平台权限。 */
    public record UpdateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{2,63}") String username,
            @NotBlank @Size(max = 100) String displayName, @NotNull Boolean enabled) {
    }

    public record Rename(@NotBlank @Size(max = 100) String name) {
    }

    public record ChangePassword(@NotBlank @Size(max = 128) String oldPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }

    public record Enabled(@NotNull Boolean enabled) {
    }

    /** 创建租户时指定已有用户作为负责人。 */
    public record CreateTenant(@NotBlank @Size(max = 100) String name, @Positive long ownerUserId) {
    }

    public record AddMember(@Positive long userId) {
    }
}
