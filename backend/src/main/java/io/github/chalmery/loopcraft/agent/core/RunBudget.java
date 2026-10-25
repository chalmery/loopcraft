package io.github.chalmery.loopcraft.agent.core;

import java.time.Duration;

/** 一次运行的全部执行预算：轮次与总时长来自模板，其余来自全局配置（提案 9.2）。 */
public record RunBudget(
        int maxIterations,
        Duration runTimeout,
        Duration modelRequestTimeout,
        Duration toolTimeout,
        int maxToolCalls) {

    public RunBudget {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须为正整数");
        }
        if (runTimeout.isZero() || runTimeout.isNegative()) {
            throw new IllegalArgumentException("runTimeout 必须为正时长");
        }
    }
}
