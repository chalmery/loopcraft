package io.github.chalmery.loopcraft.agent.dto;

/** Agent 模块响应体。 */
public final class AgentViews {

    /** chat 受理响应：运行 ID，用于订阅事件流。 */
    public record RunStarted(String runId) {}

    private AgentViews() {}
}
