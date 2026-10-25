package io.github.chalmery.loopcraft.tool;

import io.github.chalmery.loopcraft.tool.SkillTools.Binding;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 列出当前模板绑定技能的只读工具；绑定快照在运行开始时固定，执行不访问数据库。 */
class ListSkillsTool implements ToolCallback {

    /** 单条描述的展示长度上限，控制多技能时整体结果体积。 */
    private static final int DESCRIPTION_LIMIT = 200;

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private final ObjectMapper json;

    private final List<Binding> bindings;

    private final int maxResultChars;

    ListSkillsTool(ObjectMapper json, List<Binding> bindings, int maxResultChars) {
        this.json = json;
        this.bindings = bindings;
        this.maxResultChars = maxResultChars;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("list_skills")
                .description(
                        "列出当前 Agent 模板绑定的全部技能，返回每个技能的 slug、名称、描述和版本；"
                                + "调用 read_skill 前先查看本结果。")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        if (!acceptsEmptyArguments(toolInput)) {
            return ToolResults.error(
                    json, "INVALID_ARGUMENT", "list_skills 不接受参数，请传入空 JSON 对象");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Binding binding : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slug", binding.slug());
            item.put("name", binding.name());
            item.put("description", shorten(binding.description()));
            item.put("version", binding.version());
            data.add(item);
        }
        return ToolResults.data(json, data, maxResultChars);
    }

    private boolean acceptsEmptyArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return true;
        }
        try {
            Map<?, ?> arguments = json.readValue(toolInput, Map.class);
            return arguments != null && arguments.isEmpty();
        } catch (JacksonException error) {
            return false;
        }
    }

    private String shorten(String description) {
        if (description == null || description.length() <= DESCRIPTION_LIMIT) {
            return description;
        }
        return description.substring(0, DESCRIPTION_LIMIT);
    }
}
