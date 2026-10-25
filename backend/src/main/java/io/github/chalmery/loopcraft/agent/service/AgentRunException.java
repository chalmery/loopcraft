package io.github.chalmery.loopcraft.agent.service;

import io.github.chalmery.loopcraft.agent.model.RunOutcome;

/** 运行到达终态但未成功；HTTP 层按 D11 映射状态码，区分未完成与成功。 */
public class AgentRunException extends RuntimeException {

    private final RunOutcome outcome;

    public AgentRunException(RunOutcome outcome) {
        super(outcome.status().name());
        this.outcome = outcome;
    }

    public RunOutcome outcome() {
        return outcome;
    }
}
