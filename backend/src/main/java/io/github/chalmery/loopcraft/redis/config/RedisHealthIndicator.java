package io.github.chalmery.loopcraft.redis.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 通过真实 PING 验证应用与 Redis 的连通性，纳入 /actuator/health 整体状态。 */
class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            return Health.up().build();
        } catch (RuntimeException exception) {
            // 健康检查高频调用且不展示细节，不记录日志、不透出连接异常中的地址信息。
            return Health.down().build();
        }
    }
}
