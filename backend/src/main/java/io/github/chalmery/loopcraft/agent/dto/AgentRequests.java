package io.github.chalmery.loopcraft.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Agent 模块请求体。 */
public final class AgentRequests {

    /** 发起一次运行：模板决定模型、提示词与预算；message 为本次用户输入。 */
    public record Chat(
            @NotNull @Positive Long templateId,
            @NotBlank @Size(max = 16000) String message) {}

    private AgentRequests() {}
}
