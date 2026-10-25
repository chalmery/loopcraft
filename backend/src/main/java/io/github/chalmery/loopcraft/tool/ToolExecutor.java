package io.github.chalmery.loopcraft.tool;

import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

/** 工具白名单查找与结果整理；未知工具回填错误观察，超时与中断由 Agent 的执行句柄统一控制。 */
public final class ToolExecutor {

    private final Map<String, ToolCallback> tools;

    private final ObjectMapper json;

    public ToolExecutor(Map<String, ToolCallback> tools, ObjectMapper json) {
        this.tools = Map.copyOf(tools);
        this.json = json;
    }

    /** 白名单里是否存在该工具；不存在时调用不执行，转为错误观察结果。 */
    public boolean registered(String name) {
        return tools.containsKey(name);
    }

    /** 执行已通过查找的工具；参数解析与可预期错误回填在工具内部完成，不可预期异常原样上抛。 */
    public String execute(AssistantMessage.ToolCall call) {
        return tools.get(call.name()).call(call.arguments());
    }

    /** 未知工具的安全错误结果，保留原 call id 供模型修正。 */
    public String unknownTool(String name) {
        return ToolResults.error(
                json, "UNKNOWN_TOOL", "工具 " + name + " 未注册，请从可用工具列表中选择");
    }

    /** 结果 envelope 的 ok 标记；无法解析时不当作成功。 */
    public boolean ok(String result) {
        return json.readTree(result).path("ok").asBoolean(false);
    }

    /** 结果 envelope 是否为截断后的降级内容。 */
    public boolean truncated(String result) {
        return json.readTree(result).path("truncated").asBoolean(false);
    }
}
