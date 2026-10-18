package io.github.chalmery.loopcraft.identity.model;

import java.io.Serializable;

/** 会话只保存用户 ID 和凭证版本，不缓存角色和密码。 */
public record SessionUser(long userId, long credentialVersion) implements Serializable {
}
