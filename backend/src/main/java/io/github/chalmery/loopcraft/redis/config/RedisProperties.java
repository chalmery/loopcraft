package io.github.chalmery.loopcraft.redis.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/** Redis 连接配置，仅在显式启用时绑定和校验；密码留空表示无鉴权。 */
@ConfigurationProperties("loopcraft.redis")
public record RedisProperties(
        @DefaultValue("127.0.0.1") String host,
        @DefaultValue("16379") int port,
        String password,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("3s") Duration timeout) {

    public RedisProperties {
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("Redis host 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Redis port 必须在 1 到 65535 之间");
        }
        requireDuration(connectTimeout, "connect-timeout");
        requireDuration(timeout, "timeout");
    }

    private static void requireDuration(Duration duration, String name) {
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Redis " + name + " 必须在 1 秒到 1 分钟之间");
        }
    }

    /** 配置对象被诊断输出时也不暴露密码。 */
    @Override
    public String toString() {
        return "RedisProperties[password=REDACTED]";
    }
}
