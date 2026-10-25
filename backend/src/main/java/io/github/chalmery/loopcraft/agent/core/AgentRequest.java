package io.github.chalmery.loopcraft.agent.core;

import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** 一次运行的固定输入：模型实例与工具表在运行开始前解析完成，运行期不再变化。 */
public record AgentRequest(
        String message,
        String systemPrompt,
        ChatModel chatModel,
        ChatOptions options,
        Map<String, ToolCallback> tools,
        RunBudget budget) {
}
