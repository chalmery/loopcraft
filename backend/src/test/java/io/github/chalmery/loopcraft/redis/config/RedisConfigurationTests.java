package io.github.chalmery.loopcraft.redis.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RedisConfiguration.class);

    @Test
    void disabledByDefaultWithoutConnectionFactoryOrTemplate() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(LettuceConnectionFactory.class)
                .doesNotHaveBean(StringRedisTemplate.class)
                .doesNotHaveBean(RedisProperties.class));
    }

    @Test
    void enabledBindsDefaultsWithoutContactingTheServer() {
        runner.withPropertyValues("loopcraft.redis.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LettuceConnectionFactory.class)
                    .hasSingleBean(StringRedisTemplate.class).hasSingleBean(RedisHealthIndicator.class);
            var properties = context.getBean(RedisProperties.class);
            assertThat(properties.host()).isEqualTo("127.0.0.1");
            assertThat(properties.port()).isEqualTo(16379);
            assertThat(properties.password()).isNull();
        });
    }

    @Test
    void enabledWithPasswordRedactsItInDiagnostics() {
        runner.withPropertyValues("loopcraft.redis.enabled=true", "loopcraft.redis.password=secret-value")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LettuceConnectionFactory.class);
                    assertThat(context.getBean(RedisProperties.class).toString()).doesNotContain("secret-value");
                });
    }

    @Test
    void healthIsDownWhenRedisIsUnreachable() {
        runner.withPropertyValues("loopcraft.redis.enabled=true", "loopcraft.redis.port=1",
                        "loopcraft.redis.connect-timeout=1s", "loopcraft.redis.timeout=1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var indicator = context.getBean(RedisHealthIndicator.class);
                    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
                });
    }

    @Test
    void refusesInvalidHostsPortsAndTimeouts() {
        for (String invalid : new String[]{"host=", "host=  ", "port=0", "port=-1", "port=65536",
                "connect-timeout=0s", "connect-timeout=61s", "timeout=500ms", "timeout=61s"}) {
            runner.withPropertyValues("loopcraft.redis.enabled=true", "loopcraft.redis." + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
