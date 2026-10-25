package io.github.chalmery.loopcraft.agent.model;

/** 一次运行的终态结果；answer 仅在 COMPLETED 时非空。 */
public record RunOutcome(RunStatus status, RunFailure failure, String answer, int iterations, int toolCalls) {

    public static RunOutcome completed(String answer, int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.COMPLETED, null, answer, iterations, toolCalls);
    }

    public static RunOutcome iterationLimit(int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.ITERATION_LIMIT, null, null, iterations, toolCalls);
    }

    public static RunOutcome toolCallLimit(int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.TOOL_CALL_LIMIT, null, null, iterations, toolCalls);
    }

    public static RunOutcome timedOut(int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.TIMED_OUT, null, null, iterations, toolCalls);
    }

    public static RunOutcome cancelled(int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.CANCELLED, null, null, iterations, toolCalls);
    }

    public static RunOutcome failed(RunFailure failure, int iterations, int toolCalls) {
        return new RunOutcome(RunStatus.FAILED, failure, null, iterations, toolCalls);
    }

    public boolean completed() {
        return status == RunStatus.COMPLETED;
    }
}
