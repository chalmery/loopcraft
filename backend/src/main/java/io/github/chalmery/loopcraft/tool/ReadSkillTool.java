package io.github.chalmery.loopcraft.tool;

import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.tool.SkillTools.Binding;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 读取模板绑定技能 SKILL.md 的只读工具；范围与版本在运行开始时固定，执行只访问对象存储。 */
class ReadSkillTool implements ToolCallback {

    /** 与 lc_skill.slug 的长度约束对齐，拒绝超长输入。 */
    private static final int SLUG_LIMIT = 64;

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "slug": {
                  "type": "string",
                  "description": "技能 slug，来自 list_skills 的返回结果"
                }
              },
              "required": ["slug"],
              "additionalProperties": false
            }
            """;

    private final ObjectMapper json;

    private final List<Binding> bindings;

    private final ObjectProvider<FileStorage> fileStorages;

    private final int maxResultChars;

    ReadSkillTool(
            ObjectMapper json,
            List<Binding> bindings,
            ObjectProvider<FileStorage> fileStorages,
            int maxResultChars) {
        this.json = json;
        this.bindings = bindings;
        this.fileStorages = fileStorages;
        this.maxResultChars = maxResultChars;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("read_skill")
                .description("读取指定技能的 SKILL.md 完整文档内容；slug 必须来自 list_skills 的返回结果。")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        String slug = slug(toolInput);
        if (slug == null) {
            return ToolResults.error(
                    json, "INVALID_ARGUMENT", "read_skill 需要字符串参数 slug，长度不超过 64");
        }
        Binding binding = binding(slug);
        if (binding == null) {
            return ToolResults.error(
                    json,
                    "SKILL_NOT_BOUND",
                    "slug 未绑定到当前模板，请以 list_skills 返回的 slug 为准");
        }
        try {
            return ToolResults.text(json, document(binding), maxResultChars);
        } catch (IOException error) {
            return ToolResults.error(
                    json, "SKILL_STORAGE_UNAVAILABLE", "技能文档存储当前不可用，可向用户说明后直接回答");
        } catch (ResponseStatusException error) {
            return ToolResults.error(
                    json, "SKILL_CONTENT_UNREADABLE", "技能版本包无法读取，内容可能已损坏");
        }
    }

    private String slug(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        Map<?, ?> arguments;
        try {
            arguments = json.readValue(toolInput, Map.class);
        } catch (JacksonException error) {
            return null;
        }
        Object slug = arguments == null ? null : arguments.get("slug");
        if (!(slug instanceof String value) || value.isBlank() || value.length() > SLUG_LIMIT) {
            return null;
        }
        return value.strip();
    }

    private Binding binding(String slug) {
        for (Binding binding : bindings) {
            if (binding.slug().equals(slug)) {
                return binding;
            }
        }
        return null;
    }

    private String document(Binding binding) throws IOException {
        FileStorage files = fileStorages.getIfAvailable();
        if (files == null) {
            throw new IOException("对象存储未启用");
        }
        try (FileDownload download =
                files.open(new ObjectLocation(binding.bucket(), binding.objectKey()))) {
            Map<String, byte[]> entries = SkillPackages.unzip(download.stream());
            byte[] document = entries.get("SKILL.md");
            if (document == null || document.length == 0) {
                throw SkillPackages.invalid("包内缺少 SKILL.md");
            }
            return new String(document, StandardCharsets.UTF_8);
        }
    }
}
