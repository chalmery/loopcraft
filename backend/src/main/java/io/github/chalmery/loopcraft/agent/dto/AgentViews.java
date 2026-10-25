package io.github.chalmery.loopcraft.agent.dto;

/** Agent 模块响应体。 */
public final class AgentViews {

    /** chat 成功响应：最终答案与运行规模计数，不包含轨迹与工具内容。 */
    public record ChatReply(String answer, int iterations, int toolCalls) {}

    private AgentViews() {}
}
