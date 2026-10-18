package io.github.chalmery.loopcraft.identity.config;

import io.github.chalmery.loopcraft.identity.service.IdentityService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 显式启用时，仅在用户表为空的情况下初始化管理员。 */
@Component
@ConditionalOnProperty(name = "loopcraft.identity.bootstrap.enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityBootstrap.Properties.class)
public class IdentityBootstrap implements ApplicationRunner {
    private final IdentityService identity;
    private final Properties properties;

    public IdentityBootstrap(IdentityService identity, Properties properties) {
        this.identity = identity;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        identity.bootstrap(properties.username(), properties.password());
    }

    @ConfigurationProperties("loopcraft.identity.bootstrap")
    public record Properties(String username, String password) {
        public Properties {
            // 配置校验失败时也不输出密码原文。
            if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}")
                    || password == null || password.isBlank() || password.length() < 12 || password.length() > 128) {
                throw new IllegalArgumentException("初始化管理员需有效用户名及 12 至 128 字符的密码");
            }
        }

        @Override
        public String toString() {
            return "IdentityBootstrap.Properties[redacted]";
        }
    }
}
