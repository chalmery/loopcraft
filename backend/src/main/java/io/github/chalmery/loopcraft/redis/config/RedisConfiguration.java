package io.github.chalmery.loopcraft.redis.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/** 装配 Redis 连接；启动时不访问 Redis，连接按需建立，未启用时不创建任何 Bean。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "loopcraft.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfiguration {

    @Bean
    LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.host(), properties.port());
        if (StringUtils.hasText(properties.password())) {
            server.setPassword(RedisPassword.of(properties.password()));
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.timeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build())
                        .build())
                .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    HealthIndicator redisHealthIndicator(LettuceConnectionFactory redisConnectionFactory) {
        return new RedisHealthIndicator(redisConnectionFactory);
    }
}
