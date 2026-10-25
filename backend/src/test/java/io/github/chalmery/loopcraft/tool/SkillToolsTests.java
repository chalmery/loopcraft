package io.github.chalmery.loopcraft.tool;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.template.mapper.TemplateSkillMapper;
import io.github.chalmery.loopcraft.template.model.TemplateSkill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Skill 只读工具行为测试：绑定快照、参数校验回填、存储不可用降级与截断 envelope（提案 11 节）。 */
class SkillToolsTests {

    private final ObjectMapper json = new ObjectMapper();

    private final TemplateSkillMapper templateSkills = mock(TemplateSkillMapper.class);

    private final SkillMapper skills = mock(SkillMapper.class);

    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);

    private final MemoryStorage objects = new MemoryStorage();

    @BeforeEach
    void reset() {
        Mockito.reset(templateSkills, skills, versions);
        objects.data.clear();
        objects.opens.clear();
    }

    @Test
    void snapshotExposesOnlyEnabledSkillsWithExistingBoundVersion() {
        bindings(rows());
        when(skills.selectOne(any())).thenReturn(
                activeSkill("active-slug", "活跃技能", "描述"),
                deletedSkill("removed-slug"),
                disabledSkill("disabled-slug"),
                activeSkill("orphan-slug", "孤儿技能", "描述"),
                activeSkill("active-slug", "重复技能", "另一份描述"));
        when(versions.selectOne(any())).thenReturn(
                version("bucket", "skills/active.zip"), null, version("dup", "skills/dup.zip"));

        Map<String, ToolCallback> tools = skills(8000);

        JsonNode data = json.readTree(tools.get("list_skills").call("{}")).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("slug").asString()).isEqualTo("active-slug");
        assertThat(data.get(0).path("name").asString()).isEqualTo("活跃技能");
        assertThat(data.get(0).path("version").asInt()).isEqualTo(2);
        // 已删除技能的 slug 不再暴露，read_skill 得到未绑定错误而不是文档。
        JsonNode removed =
                json.readTree(tools.get("read_skill").call("{\"slug\":\"removed-slug\"}"));
        assertThat(removed.path("ok").asBoolean()).isFalse();
        assertThat(removed.path("error").path("code").asString()).isEqualTo("SKILL_NOT_BOUND");
    }

    @Test
    void listSkillsShortensLongDescriptions() {
        bindings(List.of(row(31L, 2, 1)));
        when(skills.selectOne(any()))
                .thenReturn(activeSkill("long-slug", "长描述技能", "描".repeat(260)));
        when(versions.selectOne(any())).thenReturn(version("bucket", "skills/long.zip"));

        JsonNode data = json.readTree(skills(8000).get("list_skills").call("{}")).path("data");

        assertThat(data.get(0).path("description").asString()).hasSize(200);
    }

    @Test
    void listSkillsAcceptsOnlyEmptyArguments() {
        bindings(List.of());
        ToolCallback list = skills(8000).get("list_skills");

        for (String input : List.of("null", "{\"query\":\"x\"}", "not-json")) {
            JsonNode error = json.readTree(list.call(input)).path("error");
            assertThat(error.path("code").asString())
                    .as("输入 %s 应回填参数错误", input)
                    .isEqualTo("INVALID_ARGUMENT");
        }
        for (String input : List.of("", "   ", "{}")) {
            assertThat(json.readTree(list.call(input)).path("ok").asBoolean())
                    .as("输入 %s 应按空参数成功", input)
                    .isTrue();
        }
    }

    @Test
    void readSkillReturnsBoundVersionDocumentFromStorage() throws Exception {
        bindings(List.of(row(31L, 2, 1)));
        when(skills.selectOne(any())).thenReturn(activeSkill("active-slug", "技能", "描述"));
        SkillVersion version = version("loopcraft-skills", "skills/active/v2.zip");
        when(versions.selectOne(any())).thenReturn(version);
        objects.data.put(
                new ObjectLocation("loopcraft-skills", "skills/active/v2.zip"),
                SkillPackages.zip(Map.of("SKILL.md", "# 技能文档\n正文".getBytes(StandardCharsets.UTF_8))));

        JsonNode result = json.readTree(skills(8000).get("read_skill").call("{\"slug\":\"active-slug\"}"));

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("data").asString()).isEqualTo("# 技能文档\n正文");
        // 文档必须来自绑定版本行记录的对象地址。
        assertThat(objects.opens).containsExactly(new ObjectLocation("loopcraft-skills", "skills/active/v2.zip"));
    }

    @Test
    void readSkillRejectsInvalidArguments() {
        bindings(rows());
        ToolCallback read = skills(8000).get("read_skill");

        List<String> inputs =
                java.util.Arrays.asList(
                        null,
                        "   ",
                        "not-json",
                        "{\"slug\":123}",
                        "{\"slug\":\"" + "s".repeat(65) + "\"}",
                        "{\"other\":\"value\"}");
        for (String input : inputs) {
            JsonNode error = json.readTree(read.call(input)).path("error");
            assertThat(error.path("code").asString())
                    .as("输入 %s 应回填参数错误", input)
                    .isEqualTo("INVALID_ARGUMENT");
        }
    }

    @Test
    void readSkillReportsStorageUnavailableWithoutThrowing() {
        bindings(List.of(row(31L, 2, 1)));
        when(skills.selectOne(any())).thenReturn(activeSkill("active-slug", "技能", "描述"));
        when(versions.selectOne(any())).thenReturn(version("loopcraft-skills", "skills/active/v2.zip"));

        JsonNode result =
                json.readTree(skillsWithoutStorage(8000).get("read_skill").call("{\"slug\":\"active-slug\"}"));

        assertThat(result.path("ok").asBoolean()).isFalse();
        assertThat(result.path("error").path("code").asString()).isEqualTo("SKILL_STORAGE_UNAVAILABLE");
    }

    @Test
    void readSkillReportsCorruptPackageAsContentUnreadable() throws Exception {
        bindings(List.of(row(31L, 2, 1), row(36L, 1, 2)));
        when(skills.selectOne(any()))
                .thenReturn(
                        activeSkill("broken-slug", "损坏技能", "描述"),
                        activeSkill("empty-slug", "缺文档技能", "描述"));
        when(versions.selectOne(any()))
                .thenReturn(
                        version("loopcraft-skills", "skills/broken.zip"),
                        version("loopcraft-skills", "skills/empty.zip"));
        objects.data.put(new ObjectLocation("loopcraft-skills", "skills/broken.zip"), new byte[] {1, 2, 3});
        objects.data.put(
                new ObjectLocation("loopcraft-skills", "skills/empty.zip"),
                SkillPackages.zip(Map.of("assets/a.txt", new byte[] {1})));

        ToolCallback read = skills(8000).get("read_skill");
        JsonNode broken = json.readTree(read.call("{\"slug\":\"broken-slug\"}"));
        assertThat(broken.path("error").path("code").asString()).isEqualTo("SKILL_CONTENT_UNREADABLE");
        JsonNode missing = json.readTree(read.call("{\"slug\":\"empty-slug\"}"));
        assertThat(missing.path("error").path("code").asString()).isEqualTo("SKILL_CONTENT_UNREADABLE");
    }

    @Test
    void readSkillTruncatesLongDocumentToEnvelope() throws Exception {
        bindings(List.of(row(31L, 2, 1)));
        when(skills.selectOne(any())).thenReturn(activeSkill("active-slug", "技能", "描述"));
        when(versions.selectOne(any())).thenReturn(version("loopcraft-skills", "skills/active/v2.zip"));
        String document = "文".repeat(600);
        objects.data.put(
                new ObjectLocation("loopcraft-skills", "skills/active/v2.zip"),
                SkillPackages.zip(Map.of("SKILL.md", document.getBytes(StandardCharsets.UTF_8))));

        String result = skills(300).get("read_skill").call("{\"slug\":\"active-slug\"}");

        JsonNode envelope = json.readTree(result);
        assertThat(envelope.path("ok").asBoolean()).isTrue();
        assertThat(envelope.path("truncated").asBoolean()).isTrue();
        assertThat(envelope.path("totalChars").asInt()).isEqualTo(600);
        assertThat(result.length()).isLessThanOrEqualTo(300);
    }

    @Test
    void snapshotQueriesUseTemplateScopeOnce() {
        bindings(List.of(row(31L, 2, 1)));
        when(skills.selectOne(any())).thenReturn(activeSkill("active-slug", "技能", "描述"));
        when(versions.selectOne(any())).thenReturn(version("bucket", "skills/a.zip"));

        skills(8000);

        // 快照在构建期一次解析，工具执行不再访问数据库。
        verify(templateSkills).selectList(any());
        verify(skills).selectOne(any());
        verify(versions).selectOne(any());
    }

    private Map<String, ToolCallback> skills(int maxResultChars) {
        return new SkillTools(
                        serving(templateSkills),
                        serving(skills),
                        serving(versions),
                        serving((FileStorage) objects),
                        json,
                        properties(maxResultChars))
                .forTemplate(1L, 5L);
    }

    private Map<String, ToolCallback> skillsWithoutStorage(int maxResultChars) {
        return new SkillTools(
                        serving(templateSkills),
                        serving(skills),
                        serving(versions),
                        absent(FileStorage.class),
                        json,
                        properties(maxResultChars))
                .forTemplate(1L, 5L);
    }

    private AgentProperties properties(int maxResultChars) {
        return new AgentProperties(
                Duration.ofSeconds(45),
                Duration.ofSeconds(15),
                32,
                maxResultChars,
                1000,
                Duration.ofMinutes(10));
    }

    private List<TemplateSkill> rows() {
        List<TemplateSkill> result = new ArrayList<>();
        result.add(row(31L, 2, 1));
        result.add(row(32L, 1, 2));
        result.add(row(33L, 1, 3));
        result.add(row(34L, 3, 4));
        result.add(row(35L, 1, 5));
        return result;
    }

    private void bindings(List<TemplateSkill> rows) {
        when(templateSkills.selectList(any())).thenReturn(rows);
    }

    private TemplateSkill row(long skillId, int versionNumber, int position) {
        TemplateSkill row = new TemplateSkill();
        row.setTenantId(1L);
        row.setTemplateId(5L);
        row.setSkillId(skillId);
        row.setVersionNumber(versionNumber);
        row.setPosition(position);
        return row;
    }

    private Skill activeSkill(String slug, String name, String description) {
        Skill skill = new Skill();
        skill.setTenantId(1L);
        skill.setSlug(slug);
        skill.setName(name);
        skill.setDescription(description);
        skill.setEnabled(true);
        skill.setDeleted(false);
        return skill;
    }

    private Skill deletedSkill(String slug) {
        Skill skill = activeSkill(slug, "已删除", "描述");
        skill.setDeleted(true);
        return skill;
    }

    private Skill disabledSkill(String slug) {
        Skill skill = activeSkill(slug, "已停用", "描述");
        skill.setEnabled(false);
        return skill;
    }

    private SkillVersion version(String bucket, String objectKey) {
        SkillVersion version = new SkillVersion();
        version.setTenantId(1L);
        version.setSkillId(31L);
        version.setNumber(2);
        version.setBucket(bucket);
        version.setObjectKey(objectKey);
        return version;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> serving(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent(Class<T> type) {
        return mock(ObjectProvider.class);
    }

    /** 记录读取地址的内存对象存储；open 之外的写操作与真实存储无关，不在此实现。 */
    private static final class MemoryStorage implements FileStorage {

        private final Map<ObjectLocation, byte[]> data = new LinkedHashMap<>();

        private final List<ObjectLocation> opens = new ArrayList<>();

        @Override
        public StoredFile upload(ObjectLocation location, Path source, String filename, String contentType) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public StoredFile stat(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public FileDownload open(ObjectLocation location) {
            opens.add(location);
            byte[] bytes = data.get(location);
            return new FileDownload(
                    "skill.zip", "application/zip", bytes.length, new ByteArrayInputStream(bytes));
        }

        @Override
        public void delete(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public URI presignDownload(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }
    }
}
