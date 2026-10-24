package io.github.chalmery.loopcraft.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.skill.dto.SkillInputs;
import io.github.chalmery.loopcraft.skill.dto.SkillViews;
import io.github.chalmery.loopcraft.skill.service.SkillObjectUploads;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.skill.service.SkillService;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.storage.service.FileStorageException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(SkillIT.StorageConfig.class)
@Sql(
        scripts = {
            "file:sql/identity/create_user.sql",
            "file:sql/identity/create_tenant.sql",
            "file:sql/identity/create_tenant_member.sql",
            "file:sql/skill/create_skill.sql",
            "file:sql/skill/create_skill_version.sql",
            "file:sql/skill/create_skill_tag.sql",
            "file:sql/skill/add_skill_creation_digest.sql",
            "file:sql/skill/create_skill_object_upload.sql",
            "file:sql/llm/create_llm_source.sql",
            "file:sql/llm/create_llm_model.sql",
            "file:sql/llm/add_model_capabilities.sql",
            "file:sql/template/create_agent_template.sql",
            "file:sql/template/create_template_skill.sql",
            "file:sql/template/create_template_request.sql"
        })
@Sql(
        statements =
                "DROP TABLE IF EXISTS lc_template_skill, lc_agent_template, lc_template_request,"
                        + " lc_llm_model, lc_llm_source, lc_skill_object_upload, lc_skill_tag, lc_skill_version,"
                        + " lc_skill, lc_tenant_member, lc_tenant, lc_user CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class SkillIT {

    @Autowired IdentityService identity;

    @Autowired SkillService skills;

    @Autowired SkillObjectUploads uploads;

    @Autowired MemoryStorage objects;

    @Autowired JdbcTemplate jdbc;

    @Autowired MockMvc mvc;

    UserAccount admin;

    UserAccount owner;

    UserAccount member;

    UserAccount outsider;

    long tenantId;

    long otherTenantId;

    Map<String, byte[]> files =
            Map.of(
                    "SKILL.md",
                    "---\nname: package-name\ndescription: 包内描述\n---\n内容"
                            .getBytes(StandardCharsets.UTF_8),
                    "assets/a.bin",
                    new byte[] {0, 1, -1});

    @BeforeEach
    void setup() {
        objects.data.clear();
        objects.afterUpload = () -> {};
        objects.failUpload = false;
        objects.failDelete = false;
        identity.bootstrap("rooter", "integration-password-123");
        admin = identity.login("rooter", "integration-password-123");
        owner = identity.createUser(admin, "owner", "integration-password-123", "负责人");
        member = identity.createUser(admin, "member", "integration-password-123", "成员");
        outsider = identity.createUser(admin, "outsider", "integration-password-123", "其他人");
        tenantId = identity.createTenant(admin, "租户一", owner.getId()).getId();
        otherTenantId = identity.createTenant(admin, "租户二", outsider.getId()).getId();
        identity.addMember(owner, tenantId, member.getId());
    }

    SkillViews.Summary create() throws Exception {
        return skills.create(
                member,
                tenantId,
                new SkillInputs.Create(
                        "test-skill",
                        "平台名称",
                        "平台描述",
                        List.of("标签"),
                        UUID.randomUUID().toString(),
                        null),
                files);
    }

    Authentication auth(UserAccount user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }

    String base(long id) {
        return "/api/tenants/" + tenantId + "/skills/" + id;
    }

    @Test
    void memberCrudAndMetadataRemainIndependent() throws Exception {
        var s = create();
        assertThat(s.latestVersion()).isEqualTo(1);
        var updated =
                skills.update(
                        member,
                        tenantId,
                        s.id(),
                        new SkillInputs.Metadata(
                                "新平台名称", "新平台描述", List.of("新标签", "新标签"), false, s.revision()));
        assertThat(updated.tags()).containsExactly("新标签");
        assertThat(updated.enabled()).isFalse();
        assertThat(
                        skills.content(member, tenantId, s.id(), 1).files().stream()
                                .filter(f -> f.path().equals("SKILL.md"))
                                .findFirst()
                                .orElseThrow()
                                .text())
                .contains("name: package-name")
                .doesNotContain("新平台");
        assertThat(skills.list(member, tenantId, 1, 12, "新标签").total()).isEqualTo(1);
        assertThat(skills.list(member, tenantId, 1, 12, "新平台").total()).isEqualTo(1);
        assertThat(skills.list(member, tenantId, 1, 12, "不存在").total()).isZero();
        assertThatThrownBy(
                        () ->
                                skills.update(
                                        member,
                                        tenantId,
                                        s.id(),
                                        new SkillInputs.Metadata(
                                                "过期", "描述", List.of(), true, s.revision())))
                .hasMessageContaining("已被他人修改");
        mvc.perform(
                        delete(base(s.id()))
                                .param("revision", String.valueOf(updated.revision()))
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(skills.list(member, tenantId, 1, 12, null).total()).isZero();
        assertThat(objects.data).hasSize(1);
        objects.failDelete = true;
        assertThatThrownBy(() -> skills.purge(tenantId, s.id()))
                .isInstanceOf(FileStorageException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_version", Long.class))
                .isEqualTo(1);
        objects.failDelete = false;
        skills.purge(tenantId, s.id());
        assertThat(objects.data).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
    }

    @Test
    void archivePreviewParsesDefaultsWithoutCreatingRecordsOrObjects() throws Exception {
        MockMultipartFile archive =
                new MockMultipartFile(
                        "archive", "preview.zip", "application/zip", SkillPackages.zip(files));
        String endpoint = "/api/tenants/" + tenantId + "/skills/package-preview";
        mvc.perform(
                        multipart(endpoint)
                                .file(archive)
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("package-name"))
                .andExpect(jsonPath("$.data.name").value("package-name"))
                .andExpect(jsonPath("$.data.description").value("包内描述"))
                .andExpect(jsonPath("$.data.files.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
        assertThat(objects.data).isEmpty();
        mvc.perform(
                        multipart(endpoint)
                                .file(
                                        new MockMultipartFile(
                                                "archive",
                                                "bad.zip",
                                                "application/zip",
                                                new byte[] {1, 2}))
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart(endpoint)
                                .file(archive)
                                .with(authentication(auth(outsider)))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(endpoint).file(archive).with(authentication(auth(member))))
                .andExpect(status().isForbidden());
    }

    @Test
    void libraryFiltersAndCountsRemainTenantScopedAcrossPages() throws Exception {
        SkillViews.Summary system = create();
        jdbc.update("UPDATE lc_skill SET origin='SYSTEM' WHERE id=?", system.id());
        SkillViews.Summary custom =
                skills.create(
                        member,
                        tenantId,
                        new SkillInputs.Create(
                                "custom-target",
                                "自建目标",
                                "描述",
                                List.of("验收"),
                                UUID.randomUUID().toString(),
                                null),
                        files);
        for (int i = 0; i < 12; i++) {
            skills.create(
                    member,
                    tenantId,
                    new SkillInputs.Create(
                            "custom-" + i,
                            "自建 " + i,
                            "分页样例",
                            List.of(),
                            UUID.randomUUID().toString(),
                            null),
                    files);
        }
        skills.create(
                outsider,
                otherTenantId,
                new SkillInputs.Create(
                        "other-tenant",
                        "其他租户",
                        "描述",
                        List.of(),
                        UUID.randomUUID().toString(),
                        null),
                files);
        SkillViews.Summary removed =
                skills.create(
                        member,
                        tenantId,
                        new SkillInputs.Create(
                                "removed",
                                "待删除",
                                "描述",
                                List.of(),
                                UUID.randomUUID().toString(),
                                null),
                        files);
        skills.delete(member, tenantId, removed.id(), removed.revision());
        SkillViews.ListPage all = skills.list(member, tenantId, 1, 5, null, null);
        assertThat(all.records()).hasSize(5);
        assertThat(all.total()).isEqualTo(14);
        assertThat(all.counts().custom()).isEqualTo(13);
        assertThat(all.counts().system()).isEqualTo(1);
        SkillViews.ListPage second = skills.list(member, tenantId, 2, 5, null, "CUSTOM");
        assertThat(second.records()).hasSize(5).allMatch(item -> item.origin().equals("CUSTOM"));
        assertThat(second.total()).isEqualTo(13);
        assertThat(second.counts()).isEqualTo(all.counts());
        assertThat(skills.list(member, tenantId, 1, 5, "不存在", "SYSTEM").counts())
                .isEqualTo(all.counts());
        Map<String, byte[]> changed =
                Map.of(
                        "SKILL.md",
                        files.get("SKILL.md"),
                        "guide.md",
                        new byte[] {65},
                        "assets/a.bin",
                        new byte[] {0});
        SkillViews.Version saved =
                skills.save(
                        member,
                        tenantId,
                        custom.id(),
                        new SkillInputs.Save(1, UUID.randomUUID().toString(), null),
                        changed);
        skills.update(
                member,
                tenantId,
                custom.id(),
                new SkillInputs.Metadata("自建目标", "更新描述", List.of(), true, custom.revision()));
        SkillViews.Summary latest =
                skills.list(member, tenantId, 1, 5, "自建目标", "CUSTOM").records().getFirst();
        assertThat(latest.fileCount()).isEqualTo(3);
        assertThat(latest.latestVersion()).isEqualTo(2);
        assertThat(latest.contentUpdatedAt())
                .isCloseTo(
                        saved.createdAt(),
                        org.assertj.core.api.Assertions.within(
                                1, java.time.temporal.ChronoUnit.MICROS));
        mvc.perform(
                        get("/api/tenants/" + tenantId + "/skills")
                                .param("origin", "INVALID")
                                .with(authentication(auth(member))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/tenants/" + tenantId + "/skills")
                                .param("origin", "CUSTOM")
                                .with(authentication(auth(outsider))))
                .andExpect(status().isForbidden());
    }

    @Test
    void builtinAuthorizationAndTenantScopeAreServerEnforced() throws Exception {
        var s = create();
        jdbc.update("UPDATE lc_skill SET origin='SYSTEM' WHERE id=?", s.id());
        mvc.perform(get(base(s.id())).with(authentication(auth(member))))
                .andExpect(status().isOk());
        String payload =
                "{\"name\":\"name\",\"description\":\"description\",\"tags\":[],\"enabled\":true,\"revision\":0}";
        mvc.perform(
                        patch(base(s.id()))
                                .contentType("application/json")
                                .content(payload)
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete(base(s.id()))
                                .param("revision", "0")
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        assertThatThrownBy(
                        () ->
                                skills.save(
                                        member,
                                        tenantId,
                                        s.id(),
                                        new SkillInputs.Save(1, UUID.randomUUID().toString(), null),
                                        files))
                .hasMessageContaining("403");
        mvc.perform(
                        patch(base(s.id()))
                                .contentType("application/json")
                                .content(payload)
                                .with(authentication(auth(owner)))
                                .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(
                        patch(base(s.id()))
                                .contentType("application/json")
                                .content(payload.replace("\"revision\":0", "\"revision\":1"))
                                .with(authentication(auth(admin)))
                                .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(base(s.id())).with(authentication(auth(outsider))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/tenants/" + otherTenantId + "/skills/" + s.id())
                                .with(authentication(auth(outsider))))
                .andExpect(status().isNotFound());
        mvc.perform(get(base(s.id()))).andExpect(status().isUnauthorized());
        mvc.perform(delete(base(s.id())).param("revision", "2").with(authentication(auth(owner))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/" + tenantId + "/members").with(authentication(auth(member))))
                .andExpect(status().isForbidden());
    }

    @Test
    void retriesConcurrentSavesAppendOnceAndOldVersionsCannotBeEdited() throws Exception {
        var s = create();
        var request = new SkillInputs.Save(1, UUID.randomUUID().toString(), null);
        Map<String, byte[]> changed = Map.of("SKILL.md", "第二版".getBytes(StandardCharsets.UTF_8));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> skills.save(member, tenantId, s.id(), request, changed));
            var b =
                    pool.submit(
                            () ->
                                    skills.save(
                                            owner,
                                            tenantId,
                                            s.id(),
                                            new SkillInputs.Save(
                                                    1, UUID.randomUUID().toString(), null),
                                            changed));
            int success = 0;
            for (var future : List.of(a, b)) {
                try {
                    assertThat(future.get(15, TimeUnit.SECONDS).number()).isEqualTo(2);
                    success++;
                } catch (java.util.concurrent.ExecutionException error) {
                    assertThat(error.getCause()).hasMessageContaining("旧版本只能查看");
                }
            }
            assertThat(success).isEqualTo(1);
        }
        assertThat(skills.history(member, tenantId, s.id(), 1, 20).total()).isEqualTo(2);
        assertThat(skills.content(member, tenantId, s.id(), 1).files()).hasSize(2);
        assertThatThrownBy(
                        () ->
                                skills.save(
                                        member,
                                        tenantId,
                                        s.id(),
                                        new SkillInputs.Save(1, UUID.randomUUID().toString(), null),
                                        files))
                .hasMessageContaining("旧版本只能查看");
        var retry = new SkillInputs.Save(2, UUID.randomUUID().toString(), null);
        assertThat(skills.save(member, tenantId, s.id(), retry, changed).number()).isEqualTo(3);
        assertThat(skills.save(member, tenantId, s.id(), retry, changed).number()).isEqualTo(3);
        assertThat(skills.history(member, tenantId, s.id(), 1, 20).total()).isEqualTo(3);
    }

    @Test
    void failedUploadOrDatabaseCommitLeavesNoVisibleVersion() throws Exception {
        objects.failUpload = true;
        assertThatThrownBy(this::create).hasMessageContaining("503");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
        objects.failUpload = false;
        var s = create();
        jdbc.execute("ALTER TABLE lc_skill_version ADD CONSTRAINT test_fail CHECK (number < 2)");
        assertThatThrownBy(
                        () ->
                                skills.save(
                                        member,
                                        tenantId,
                                        s.id(),
                                        new SkillInputs.Save(1, UUID.randomUUID().toString(), null),
                                        files))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(objects.data).hasSize(1);
        assertThat(skills.detail(member, tenantId, s.id()).latestVersion()).isEqualTo(1);
    }

    @Test
    void creationIsIdempotentAndMultipartKeepsYamlAsPlainFiles() throws Exception {
        String request = UUID.randomUUID().toString();
        var input = new SkillInputs.Create("first", "平台", "描述", List.of(), request, null);
        var s = skills.create(member, tenantId, input, files);
        assertThat(skills.create(member, tenantId, input, files).id()).isEqualTo(s.id());
        assertThat(objects.data).hasSize(1);
        String json =
                "{\"slug\":\"upload\",\"name\":\"手填名称\",\"description\":\"手填描述\",\"tags\":[],\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"paths\":[\"SKILL.md\"]}";
        mvc.perform(
                        multipart("/api/tenants/" + tenantId + "/skills")
                                .file(
                                        new MockMultipartFile(
                                                "files",
                                                "SKILL.md",
                                                "text/plain",
                                                files.get("SKILL.md")))
                                .param("input", json)
                                .with(authentication(auth(member)))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("手填名称"))
                .andExpect(jsonPath("$.data.origin").value("CUSTOM"))
                .andExpect(jsonPath("$.data.latestVersion").value(1));
        mvc.perform(
                        get("/api/tenants/" + tenantId + "/skills?size=101")
                                .with(authentication(auth(member))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creationIdempotencyBindsMetadataTagsActorAndArchive() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var input = new SkillInputs.Create("bound", "原名", "原描述", List.of("原标签"), requestId, null);
        var saved = skills.create(member, tenantId, input, files);
        for (var changed :
                List.of(
                        new SkillInputs.Create(
                                "bound", "改名", "原描述", List.of("原标签"), requestId, null),
                        new SkillInputs.Create(
                                "bound", "原名", "改描述", List.of("原标签"), requestId, null),
                        new SkillInputs.Create(
                                "bound", "原名", "原描述", List.of("改标签"), requestId, null))) {
            assertThatThrownBy(() -> skills.create(member, tenantId, changed, files))
                    .hasMessageContaining("重复请求内容不一致");
        }
        assertThatThrownBy(() -> skills.create(owner, tenantId, input, files))
                .hasMessageContaining("创建请求标识已使用");
        assertThatThrownBy(
                        () ->
                                skills.create(
                                        member,
                                        tenantId,
                                        input,
                                        Map.of("SKILL.md", new byte[] {65})))
                .hasMessageContaining("重复请求内容不一致");
        skills.update(
                member,
                tenantId,
                saved.id(),
                new SkillInputs.Metadata("后续名称", "后续描述", List.of(), true, saved.revision()));
        assertThat(skills.create(member, tenantId, input, files).id()).isEqualTo(saved.id());
        assertThat(objects.data).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_object_upload", Long.class))
                .isZero();
    }

    @Test
    void saveIdempotencyAlsoBindsBaseVersion() throws Exception {
        var skill = create();
        String requestId = UUID.randomUUID().toString();
        assertThat(
                        skills.save(
                                        member,
                                        tenantId,
                                        skill.id(),
                                        new SkillInputs.Save(1, requestId, null),
                                        files)
                                .number())
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                skills.save(
                                        member,
                                        tenantId,
                                        skill.id(),
                                        new SkillInputs.Save(2, requestId, null),
                                        files))
                .hasMessageContaining("重复请求内容不一致");
        assertThat(skills.history(member, tenantId, skill.id(), 1, 20).total()).isEqualTo(2);
        assertThat(objects.data).hasSize(2);
    }

    @Test
    void rollbackAndCleanupFailureRetainDurableObjectReferenceForRetry() throws Exception {
        var skill = create();
        jdbc.execute("ALTER TABLE lc_skill_version ADD CONSTRAINT test_fail CHECK (number < 2)");
        objects.failDelete = true;
        assertThatThrownBy(
                        () ->
                                skills.save(
                                        member,
                                        tenantId,
                                        skill.id(),
                                        new SkillInputs.Save(1, UUID.randomUUID().toString(), null),
                                        files))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(objects.data).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_version", Long.class))
                .isEqualTo(1);
        Long uploadId = jdbc.queryForObject("SELECT id FROM lc_skill_object_upload", Long.class);
        jdbc.update(
                "UPDATE lc_skill_object_upload SET created_at=CURRENT_TIMESTAMP - INTERVAL '16"
                        + " minutes'");
        assertThat(uploads.pending()).hasSize(1);
        objects.failDelete = false;
        uploads.discard(uploadId);
        assertThat(objects.data).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_object_upload", Long.class))
                .isZero();
        assertThat(skills.content(member, tenantId, skill.id(), 1).files()).hasSize(2);
    }

    @Test
    void cleanupProtectsObjectsAlreadyReferencedByCommittedVersions() throws Exception {
        create();
        jdbc.update(
                "INSERT INTO lc_skill_object_upload (tenant_id,bucket,object_key) SELECT"
                        + " tenant_id,bucket,object_key FROM lc_skill_version");
        Long uploadId = jdbc.queryForObject("SELECT id FROM lc_skill_object_upload", Long.class);
        uploads.discard(uploadId);
        assertThat(objects.data).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_object_upload", Long.class))
                .isZero();
    }

    @Test
    void multipartJsonReceivesBeanValidationBeforeAnyObjectWrite() throws Exception {
        String endpoint = "/api/tenants/" + tenantId + "/skills";
        String template =
                "{\"slug\":\"validated\",\"name\":\"名称\",\"description\":\"描述\",\"tags\":[],\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"paths\":[\"SKILL.md\"]}";
        for (String invalid :
                List.of(
                        template.replace("\"name\":\"名称\"", "\"name\":null"),
                        template.replace("\"tags\":[]", "\"tags\":[null]"),
                        template.replace("\"paths\":[\"SKILL.md\"]", "\"paths\":[null]"),
                        template.replace("validated", "Invalid Slug"))) {
            mvc.perform(
                            multipart(endpoint)
                                    .file(
                                            new MockMultipartFile(
                                                    "files",
                                                    "SKILL.md",
                                                    "text/plain",
                                                    files.get("SKILL.md")))
                                    .param("input", invalid)
                                    .with(authentication(auth(member)))
                                    .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        assertThat(objects.data).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
    }

    @Test
    void cleanupSkipsAnObjectWhileUploadIsInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        objects.afterUpload =
                () -> {
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("上传测试等待超时");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(this::create);
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                Long uploadId =
                        jdbc.queryForObject("SELECT id FROM lc_skill_object_upload", Long.class);
                uploads.discard(uploadId);
                assertThat(objects.data).hasSize(1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM lc_skill_object_upload", Long.class))
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(result.get(10, TimeUnit.SECONDS).latestVersion()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_object_upload", Long.class))
                .isZero();
    }

    @Test
    void deletedSkillsArePurgedInBoundedBatches() throws Exception {
        var skill = create();
        for (int baseVersion = 1; baseVersion <= 20; baseVersion++) {
            skills.save(
                    member,
                    tenantId,
                    skill.id(),
                    new SkillInputs.Save(baseVersion, UUID.randomUUID().toString(), null),
                    files);
        }
        skills.delete(member, tenantId, skill.id(), skill.revision());
        skills.purge(tenantId, skill.id());
        assertThat(objects.data).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isEqualTo(1);
        skills.purge(tenantId, skill.id());
        assertThat(objects.data).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
    }

    @TestConfiguration
    static class StorageConfig {

        @Bean
        MemoryStorage memoryStorage() {
            return new MemoryStorage();
        }
    }

    static class MemoryStorage implements FileStorage {

        final Map<ObjectLocation, byte[]> data = new ConcurrentHashMap<>();

        Runnable afterUpload = () -> {};

        boolean failUpload;

        boolean failDelete;

        public StoredFile upload(ObjectLocation at, Path source, String filename, String type)
                throws IOException {
            if (failUpload) {
                throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
            }
            data.put(at, Files.readAllBytes(source));
            afterUpload.run();
            return new StoredFile(at, filename, type, data.get(at).length);
        }

        public StoredFile stat(ObjectLocation at) {
            return new StoredFile(at, "skill.zip", "application/zip", data.get(at).length);
        }

        public FileDownload open(ObjectLocation at) {
            byte[] bytes = data.get(at);
            if (bytes == null) {
                throw new FileStorageException(FileStorageException.Reason.NOT_FOUND);
            }
            return new FileDownload(
                    "skill.zip", "application/zip", bytes.length, new ByteArrayInputStream(bytes));
        }

        public void delete(ObjectLocation at) {
            if (failDelete) {
                throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
            }
            data.remove(at);
        }

        public URI presignDownload(ObjectLocation at) {
            throw new UnsupportedOperationException();
        }
    }
}
