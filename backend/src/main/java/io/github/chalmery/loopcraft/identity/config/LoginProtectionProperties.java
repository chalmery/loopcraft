package io.github.chalmery.loopcraft.identity.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

/** 登录失败窗口、冷却时长及单实例计数容量。 */
@Validated
@ConfigurationProperties("loopcraft.identity.login-protection")
public record LoginProtectionProperties(
        @DefaultValue("5") @Min(1) @Max(1000) int accountFailures,
        @DefaultValue("30") @Min(1) @Max(10000) int ipAttempts,
        @DefaultValue("15m") @DurationMin(seconds = 1) @DurationMax(days = 1) Duration window,
        @DefaultValue("10000") @Min(2) @Max(100000) int maxEntries) {
}
