package io.github.chalmery.loopcraft.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.dto.LlmRequests;
import io.github.chalmery.loopcraft.llm.service.LlmAccessService;
import io.github.chalmery.loopcraft.skill.service.SkillService;
import io.github.chalmery.loopcraft.template.dto.TemplateInput;
import io.github.chalmery.loopcraft.template.dto.TemplateViews;
import io.github.chalmery.loopcraft.template.service.TemplateService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Sql(scripts = {
    "file:sql/identity/create_user.sql", "file:sql/identity/create_tenant.sql",
    "file:sql/identity/create_tenant_member.sql", "file:sql/llm/create_llm_source.sql",
    "file:sql/llm/create_llm_model.sql", "file:sql/llm/add_model_capabilities.sql",
    "file:sql/skill/create_skill.sql", "file:sql/skill/create_skill_version.sql",
    "file:sql/skill/create_skill_tag.sql", "file:sql/skill/add_skill_creation_digest.sql",
    "file:sql/skill/create_skill_object_upload.sql", "file:sql/template/create_agent_template.sql",
    "file:sql/template/create_template_skill.sql", "file:sql/template/create_template_request.sql"
})
@Sql(statements = "DROP TABLE IF EXISTS lc_template_request, lc_template_skill, lc_agent_template,"
        + " lc_skill_object_upload, lc_skill_tag, lc_skill_version, lc_skill, lc_llm_model, lc_llm_source,"
        + " lc_tenant_member, lc_tenant, lc_user CASCADE", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class TemplateIT {
    @Autowired private IdentityService identity;
    @Autowired private TemplateService templates;
    @Autowired private LlmAccessService llm;
    @Autowired private SkillService skills;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    private UserAccount admin;
    private UserAccount owner;
    private UserAccount member;
    private UserAccount outsider;
    private long tenantId;
    private long otherTenantId;
    private long modelId;
    private long sourceId;
    private long skillId;

    @BeforeEach
    void setup() {
        String password = "template-integration-only-password";
        identity.bootstrap("rooter", password);
        admin = identity.login("rooter", password);
        owner = identity.createUser(admin, "owner", password, "负责人");
        member = identity.createUser(admin, "member", password, "成员");
        outsider = identity.createUser(admin, "outsider", password, "其他成员");
        tenantId = identity.createTenant(admin, "模板租户", owner.getId()).getId();
        otherTenantId = identity.createTenant(admin, "其他租户", outsider.getId()).getId();
        identity.addMember(owner, tenantId, member.getId());
        sourceId = llm.createSource(owner, tenantId, new LlmRequests.CreateSource(
                "示例连接", "示例供应商", "OPENAI_COMPATIBLE", "https://example.com/v1", null, true)).id();
        modelId = llm.createModel(owner, tenantId, sourceId, new LlmRequests.ModelInput(
                "示例模型", "example", 131072L, true, BigDecimal.ZERO, BigDecimal.valueOf(2))).id();
        skillId = jdbc.queryForObject("INSERT INTO lc_skill (tenant_id,slug,name,description,latest_version,creation_request,created_by)"
                + " VALUES (?, 'research', '资料整理', '整理资料', 2, ?, ?) RETURNING id", Long.class,
                tenantId, UUID.randomUUID().toString(), member.getId());
        for (int number = 1; number <= 2; number++) {
            jdbc.update("INSERT INTO lc_skill_version (tenant_id,skill_id,number,request_id,digest,bucket,object_key,file_count,total_bytes,created_by)"
                    + " VALUES (?,?,?,?,?,'test-only','example',1,1,?)", tenantId, skillId, number,
                    UUID.randomUUID().toString(), "test-digest", member.getId());
        }
    }

    @Test
    void tenantMembersCanMaintainWhilePlatformIdentityCannotBypassMembership() throws Exception {
        String path = path(tenantId);
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(auth(admin)))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(authentication(auth(outsider)))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(authentication(auth(member))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input("基础模板", null, null, null, null, List.of()))))
                .andExpect(status().isForbidden());
        var created = templates.save(member, tenantId, null, input("基础模板", null, null, null, null, List.of()));
        mvc.perform(get(path + "/" + created.id()).with(authentication(auth(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.model.contextLimit").value(131072))
                .andExpect(jsonPath("$.data.model.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.contextBudgetTokens").isEmpty())
                .andExpect(jsonPath("$.data.maxIterations").value(0));
        mvc.perform(get(path(otherTenantId) + "/" + created.id()).with(authentication(auth(outsider))))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/models").with(authentication(auth(member))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].contextLimit").value(131072));
        var updated = templates.save(owner, tenantId, created.id(), input("协作修改", created.revision(), null, null, null, List.of()));
        assertThat(updated.name()).isEqualTo("协作修改");
        assertThat(templates.copyName(member, tenantId, created.id())).isEqualTo("协作修改（副本）");
        templates.save(member, tenantId, null, input("协作修改（副本）", null, null, null, null, List.of()));
        assertThat(templates.copyName(member, tenantId, created.id())).isEqualTo("协作修改（副本 2）");
    }

    @Test
    void preservesZeroDecimalAndExplicitNullsAndRejectsMissingNullableKeys() throws Exception {
        var created = templates.save(member, tenantId, null, input("参数", null, BigDecimal.ONE, 32768L, modelId, List.of()));
        assertThat(created.summaryModelId()).isEqualTo(modelId);
        var cleared = input("参数", created.revision(), null, null, null, List.of());
        var result = templates.save(member, tenantId, created.id(), cleared);
        assertThat(result.temperature()).isNull();
        assertThat(result.contextBudgetTokens()).isNull();
        assertThat(result.summaryModelId()).isNull();
        assertThat(result.timeoutMinutes()).isEqualByComparingTo("0.5");
        assertThat(result.maxIterations()).isZero();
        var body = json.readTree(json.writeValueAsString(cleared)).deepCopy();
        var missing = json.writeValueAsString(cleared).replace("\"temperature\":null,", "");
        mvc.perform(put(path(tenantId) + "/" + created.id()).with(authentication(auth(member))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(missing)).andExpect(status().isBadRequest());
        assertThat(body.has("temperature")).isTrue();
    }

    @Test
    void retriesReturnOriginalResponseBeforeRevisionChecksAndKeysBindCompletePayload() {
        var request = input(" 幂等 ", null, null, null, null, List.of());
        var created = templates.save(member, tenantId, null, request);
        assertThat(templates.save(member, tenantId, null, request).id()).isEqualTo(created.id());
        var update = input("新名称", created.revision(), null, null, null, List.of());
        var result = templates.save(member, tenantId, created.id(), update);
        templates.save(member, tenantId, created.id(), input("后来修改", result.revision(), null, null, null, List.of()));
        assertThat(templates.save(member, tenantId, created.id(), update).name()).isEqualTo("新名称");
        var changed = new TemplateInput(update.requestId(), update.baseRevision(), "不同内容", update.description(),
                true, modelId, "", null, null, null, 10, 0L, BigDecimal.ZERO, List.of());
        assertThatThrownBy(() -> templates.save(member, tenantId, created.id(), changed))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_agent_template", Long.class)).isEqualTo(1);
    }

    @Test
    void pinsSkillVersionsAndAllowsRetainedDisabledBindingsButRejectsNewOnes() {
        var binding = new TemplateInput.Binding(skillId, 1);
        var created = templates.save(member, tenantId, null, input("版本固定", null, null, null, null, List.of(binding)));
        assertThat(created.skillBindings().getFirst().versionNumber()).isEqualTo(1);
        assertThat(created.skillBindings().getFirst().latestVersion()).isEqualTo(2);
        jdbc.update("UPDATE lc_skill SET enabled=false WHERE id=?", skillId);
        llm.updateSource(owner, tenantId, sourceId, Map.of("enabled", false));
        var updated = templates.save(member, tenantId, created.id(), input("仍可维护", created.revision(), null, null, null, List.of(binding)));
        assertThat(updated.available()).isFalse();
        assertThat(updated.enabled()).isTrue();
        assertThatThrownBy(() -> templates.save(member, tenantId, null, input("新增拒绝", null, null, null, null, List.of(binding))))
                .isInstanceOf(ResponseStatusException.class);
        llm.updateSource(owner, tenantId, sourceId, Map.of("enabled", true));
        assertThatThrownBy(() -> templates.save(member, tenantId, created.id(), input("切换拒绝", updated.revision(), null, null, null,
                List.of(new TemplateInput.Binding(skillId, 2)))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void protectsReferencesBeforeModelDeletionOrSkillObjectCleanup() {
        var created = templates.save(member, tenantId, null, input("受保护", null, null, null, modelId,
                List.of(new TemplateInput.Binding(skillId, 1))));
        assertThatThrownBy(() -> skills.delete(member, tenantId, skillId, 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT deleted FROM lc_skill WHERE id=?", Boolean.class, skillId)).isFalse();
        assertThatThrownBy(() -> llm.deleteSource(owner, tenantId, sourceId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_llm_source", Long.class)).isEqualTo(1);
        templates.delete(member, tenantId, created.id(), created.revision());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_template_skill", Long.class)).isZero();
        skills.delete(member, tenantId, skillId, 0);
        llm.deleteSource(owner, tenantId, sourceId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_llm_source", Long.class)).isZero();
    }

    @Test
    void validatesBudgetNamesVersionsAndUnknownCapabilitiesWithoutPartialWrites() throws Exception {
        templates.save(member, tenantId, null, input("唯一", null, null, null, null, List.of()));
        assertThatThrownBy(() -> templates.save(member, tenantId, null, input("唯一", null, null, null, null, List.of())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> templates.save(member, tenantId, null, input("超预算", null, null, 262144L, null, List.of())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> templates.save(member, tenantId, null, input("未知版本", null, null, null, null,
                List.of(new TemplateInput.Binding(skillId, 3)))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_agent_template", Long.class)).isEqualTo(1);
        mvc.perform(patch("/api/tenants/" + tenantId + "/llm-sources/" + sourceId + "/models/" + modelId)
                .with(authentication(auth(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contextLimit\":null,\"temperatureSupported\":null,\"temperatureMin\":null,\"temperatureMax\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contextLimit").isEmpty());
        var incomplete = templates.save(member, tenantId, null, input("待补齐", null, null, null, null, List.of()));
        assertThat(incomplete.available()).isFalse();
        assertThat(incomplete.unavailableReasons()).anyMatch(r -> r.contains("上下文上限"));
        var invalid = new TemplateInput(UUID.randomUUID().toString(), null, "非法数字", "", true, modelId, "", null, null,
                null, 10, -1L, new BigDecimal("0.5"), List.of());
        mvc.perform(post(path(tenantId)).with(authentication(auth(member))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
    }

    @Test
    void serializesConcurrentRetriesAndRejectsLostUpdates() throws Exception {
        var request = input("并发", null, null, null, null, List.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> templates.save(member, tenantId, null, request));
            var second = pool.submit(() -> templates.save(member, tenantId, null, request));
            assertThat(first.get(15, TimeUnit.SECONDS).id()).isEqualTo(second.get(15, TimeUnit.SECONDS).id());
        }
        var created = templates.list(member, tenantId, 1, 12, null, null).records().getFirst();
        var detail = templates.detail(member, tenantId, created.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(detail, "修改一"));
            var second = pool.submit(() -> attempt(detail, "修改二"));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    private int attempt(TemplateViews.Detail detail, String name) {
        try {
            templates.save(member, tenantId, detail.id(), input(name, detail.revision(), null, null, null, List.of()));
            return 200;
        } catch (ResponseStatusException cause) {
            return cause.getStatusCode().value();
        }
    }

    private TemplateInput input(String name, Long revision, BigDecimal temperature, Long budget, Long summary,
            List<TemplateInput.Binding> bindings) {
        return new TemplateInput(UUID.randomUUID().toString(), revision, name, "", true, modelId, "",
                temperature, budget, summary, 10, 0L, new BigDecimal("0.5"), bindings);
    }

    private Authentication auth(UserAccount user) {
        return new UsernamePasswordAuthenticationToken(new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }

    private String path(long id) {
        return "/api/tenants/" + id + "/agent-templates";
    }
}
