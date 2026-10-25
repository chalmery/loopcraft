package io.github.chalmery.loopcraft.agent.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Agent 循环的全局执行预算；模型轮次与运行时长来自模板字段（提案 9.2）。 */
@Validated
@ConfigurationProperties("loopcraft.agent")
public record AgentProperties(
        @DefaultValue("45s") @DurationMin(seconds = 1) Duration modelRequestTimeout,
        @DefaultValue("15s") @DurationMin(seconds = 1) Duration toolTimeout,
        @DefaultValue("32") @Min(1) @Max(1000) int maxToolCalls,
        @DefaultValue("8000") @Min(200) @Max(200000) int maxToolResultChars) {
}
