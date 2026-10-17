package io.github.chalmery.loopcraft.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration contract for the future Agent loop, not an execution policy. */
@Validated
@ConfigurationProperties(prefix = "loopcraft.agent", ignoreUnknownFields = false)
public record AgentProperties(
        @Min(1) @Max(100) int maxIterations,
        @NotNull Duration runTimeout,
        @NotNull Duration toolTimeout) {

    @AssertTrue(message = "timeouts must be positive and tool-timeout must not exceed run-timeout")
    public boolean isTimeoutBudgetValid() {
        return runTimeout != null && toolTimeout != null
                && runTimeout.compareTo(Duration.ZERO) > 0
                && toolTimeout.compareTo(Duration.ZERO) > 0
                && toolTimeout.compareTo(runTimeout) <= 0;
    }
}
