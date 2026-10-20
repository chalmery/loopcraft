package io.github.chalmery.loopcraft.identity.service;

/** 不区分账号是否存在，只向客户端提供统一提示和重试秒数。 */
public class LoginThrottledException extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginThrottledException(long retryAfterSeconds) {
        super("登录尝试过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
