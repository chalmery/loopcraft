package io.github.chalmery.loopcraft.identity.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginProtectionPropertiesTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsSafeDefaultsAndDurationOverrides() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LoginProtectionProperties.class))
                    .isEqualTo(new LoginProtectionProperties(5, 30, Duration.ofMinutes(15), 10000));
        });
        runner.withPropertyValues("loopcraft.identity.login-protection.window=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LoginProtectionProperties.class).window())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void refusesDisabledLimitsAndUnboundedMemoryOrDurations() {
        for (String invalid : new String[]{"account-failures=0", "ip-attempts=0", "max-entries=1",
                "max-entries=100001", "window=0s", "window=25h"}) {
            runner.withPropertyValues("loopcraft.identity.login-protection." + invalid)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginProtectionProperties.class)
    static class PropertiesConfiguration {
    }
}
