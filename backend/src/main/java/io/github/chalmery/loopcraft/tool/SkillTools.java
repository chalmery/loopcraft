package io.github.chalmery.loopcraft.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.template.mapper.TemplateSkillMapper;
import io.github.chalmery.loopcraft.template.model.TemplateSkill;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 首批内置工具：按模板技能绑定构建 list_skills / read_skill，供手写 Agent 循环使用。 */
@Component
public class SkillTools {

    private final ObjectProvider<TemplateSkillMapper> templateSkillMappers;

    private final ObjectProvider<SkillMapper> skillMappers;

    private final ObjectProvider<SkillVersionMapper> versionMappers;

    private final ObjectProvider<FileStorage> fileStorages;

    private final ObjectMapper json;

    private final AgentProperties properties;

    public SkillTools(
            ObjectProvider<TemplateSkillMapper> templateSkillMappers,
            ObjectProvider<SkillMapper> skillMappers,
            ObjectProvider<SkillVersionMapper> versionMappers,
            ObjectProvider<FileStorage> fileStorages,
            ObjectMapper json,
            AgentProperties properties) {
        this.templateSkillMappers = templateSkillMappers;
        this.skillMappers = skillMappers;
        this.versionMappers = versionMappers;
        this.fileStorages = fileStorages;
        this.json = json;
        this.properties = properties;
    }

    /** 解析模板绑定并构建本次运行固定的工具表；提供给模型的定义与可执行范围来自同一份快照。 */
    public Map<String, ToolCallback> forTemplate(long tenantId, long templateId) {
        List<Binding> bindings = bindings(tenantId, templateId);
        int maxResultChars = properties.maxToolResultChars();
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        tools.put("list_skills", new ListSkillsTool(json, bindings, maxResultChars));
        tools.put("read_skill", new ReadSkillTool(json, bindings, fileStorages, maxResultChars));
        return Collections.unmodifiableMap(tools);
    }

    private List<Binding> bindings(long tenantId, long templateId) {
        List<TemplateSkill> rows =
                templateSkillMappers.getObject()
                        .selectList(
                                Wrappers.<TemplateSkill>lambdaQuery()
                                        .eq(TemplateSkill::getTenantId, tenantId)
                                        .eq(TemplateSkill::getTemplateId, templateId)
                                        .orderByAsc(TemplateSkill::getPosition));
        List<Binding> result = new ArrayList<>();
        Set<String> slugs = new HashSet<>();
        for (TemplateSkill row : rows) {
            Binding binding = binding(tenantId, row);
            if (binding != null && slugs.add(binding.slug())) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    private Binding binding(long tenantId, TemplateSkill row) {
        Skill skill =
                skillMappers.getObject()
                        .selectOne(
                                Wrappers.<Skill>lambdaQuery()
                                        .eq(Skill::getTenantId, tenantId)
                                        .eq(Skill::getId, row.getSkillId()));
        // 技能已删除或停用时不再暴露给模型；对应 slug 的 read_skill 会得到未绑定错误。
        if (skill == null
                || Boolean.TRUE.equals(skill.getDeleted())
                || !Boolean.TRUE.equals(skill.getEnabled())) {
            return null;
        }
        SkillVersion version =
                versionMappers.getObject()
                        .selectOne(
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getTenantId, tenantId)
                                        .eq(SkillVersion::getSkillId, row.getSkillId())
                                        .eq(SkillVersion::getNumber, row.getVersionNumber()));
        if (version == null) {
            return null;
        }
        return new Binding(
                skill.getSlug(),
                skill.getName(),
                skill.getDescription(),
                row.getVersionNumber(),
                version.getBucket(),
                version.getObjectKey());
    }

    /** 一次运行内不可变的技能绑定快照；read_skill 执行时不再访问数据库。 */
    public record Binding(
            String slug,
            String name,
            String description,
            int version,
            String bucket,
            String objectKey) {}
}
