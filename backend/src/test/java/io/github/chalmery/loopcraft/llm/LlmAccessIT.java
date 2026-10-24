package io.github.chalmery.loopcraft.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.dto.LlmRequests;
import io.github.chalmery.loopcraft.llm.dto.LlmViews;
import io.github.chalmery.loopcraft.llm.service.LlmAccessService;
import io.github.chalmery.loopcraft.llm.service.LlmCredentialCipher;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(
        properties = "loopcraft.llm.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Sql(
        scripts = {
            "file:sql/identity/create_user.sql",
            "file:sql/identity/create_tenant.sql",
            "file:sql/identity/create_tenant_member.sql",
            "file:sql/llm/create_llm_source.sql",
            "file:sql/llm/create_llm_model.sql"
        })
@Sql(
        statements =
                "DROP TABLE IF EXISTS llm_dependency_probe, lc_llm_model, lc_llm_source,"
                        + " lc_tenant_member, lc_tenant, lc_user CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class LlmAccessIT {

    private static final String PASSWORD = "integration-password-123";

    @Autowired private IdentityService identity;

    @Autowired private LlmAccessService llm;

    @Autowired private LlmCredentialCipher cipher;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private MockMvc mvc;

    private UserAccount admin;

    private UserAccount owner;

    private UserAccount member;

    private UserAccount outsider;

    private long tenant;

    private long otherTenant;

    private LlmViews.Source source;

    @BeforeEach
    void setup() {
        identity.bootstrap("rooter", PASSWORD);
        admin = identity.login("rooter", PASSWORD);
        owner = identity.createUser(admin, "owner", PASSWORD, "负责人");
        member = identity.createUser(admin, "member", PASSWORD, "成员");
        outsider = identity.createUser(admin, "outsider", PASSWORD, "其他租户成员");
        tenant = identity.createTenant(admin, "租户一", owner.getId()).getId();
        otherTenant = identity.createTenant(admin, "租户二", outsider.getId()).getId();
        identity.addMember(owner, tenant, member.getId());
        source = llm.createSource(owner, tenant, input("研发连接", "test-only-key"));
    }

    @Test
    void scopesReadsWritesAndKeepsMemberAdministrationOwnerOnly() throws Exception {
        String base = base(tenant, source.id());
        mvc.perform(get(base).with(authentication(auth(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasApiKey").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.apiKeyCiphertext").doesNotExist());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get(base).with(authentication(auth(outsider))))
                .andExpect(status().isForbidden());
        mvc.perform(get(base(otherTenant, source.id())).with(authentication(auth(outsider))))
                .andExpect(status().isNotFound());
        for (String path : List.of(base, base + "/models/1")) {
            mvc.perform(
                            patch(path)
                                    .with(authentication(auth(member)))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(path).with(authentication(auth(member))).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        for (String path :
                List.of(base + "/models", base + "/model-discovery", base + "/models/import")) {
            String body =
                    path.endsWith("import")
                            ? "{\"models\":[{\"name\":\"测试\",\"modelId\":\"x\"}]}"
                            : path.endsWith("discovery")
                                    ? "{}"
                                    : "{\"name\":\"测试\",\"modelId\":\"x\"}";
            mvc.perform(
                            post(path)
                                    .with(authentication(auth(member)))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(delete(base).with(authentication(auth(owner))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch(base)
                                .with(authentication(auth(admin)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"全局管理修改\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tenants/" + tenant + "/members").with(authentication(auth(admin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void preservesReplacesAndExplicitlyClearsKeysAndValidatesPatchPresence() throws Exception {
        String before = ciphertext();
        llm.updateSource(owner, tenant, source.id(), Map.of("name", "新名称", "apiKey", "  "));
        assertThat(ciphertext()).isEqualTo(before);
        assertThat(cipher.decrypt(tenant, before)).isEqualTo("test-only-key");
        llm.updateSource(owner, tenant, source.id(), Map.of("apiKey", "replacement-test-key"));
        assertThat(cipher.decrypt(tenant, ciphertext())).isEqualTo("replacement-test-key");
        for (String patch :
                List.of(
                        "{\"name\":null}",
                        "{\"name\":\" \"}",
                        "{\"enabled\":null}",
                        "{\"protocol\":\"UNKNOWN\"}",
                        "{\"apiKey\":\"new\",\"clearApiKey\":true}")) {
            mvc.perform(
                            patch(base(tenant, source.id()))
                                    .with(authentication(auth(owner)))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(patch))
                    .andExpect(status().isBadRequest());
        }
        assertThat(
                        llm.updateSource(owner, tenant, source.id(), Map.of("clearApiKey", true))
                                .hasApiKey())
                .isFalse();
        assertThat(ciphertext()).isNull();
        mvc.perform(
                        post(base(tenant, source.id()) + "/model-discovery")
                                .with(authentication(auth(owner)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void enforcesUniqueTrimmedNamesCompositeTenantForeignKeysAndPagination() throws Exception {
        llm.createSource(owner, tenant, input("第二连接", null));
        llm.createSource(outsider, otherTenant, input("研发连接", null));
        assertThatThrownBy(() -> llm.createSource(owner, tenant, input(" 研发连接 ", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(
                        get("/api/tenants/" + tenant + "/llm-sources?page=2&size=1")
                                .with(authentication(auth(member))))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(
                        get("/api/tenants/" + tenant + "/llm-sources?size=101")
                                .with(authentication(auth(member))))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO"
                                            + " lc_llm_model(tenant_id,source_id,name,model_id,origin)"
                                            + " VALUES (?,?, '测试','x','MANUAL')",
                                        otherTenant,
                                        source.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var model =
                llm.createModel(
                        owner, tenant, source.id(), new LlmRequests.ModelInput("模型", "ID_100%"));
        assertThat(
                        llm.sources(member, tenant, 1, 20, null).records().stream()
                                .filter(s -> s.id() == source.id())
                                .findFirst()
                                .orElseThrow()
                                .modelCount())
                .isEqualTo(1);
        assertThat(llm.models(member, tenant, source.id(), 1, 20, "_").total()).isEqualTo(1);
        assertThat(llm.models(member, tenant, source.id(), 1, 20, "%").total()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                llm.createModel(
                                        owner,
                                        tenant,
                                        source.id(),
                                        new LlmRequests.ModelInput("重复", " ID_100% ")))
                .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(
                        patch(base(tenant, source.id()) + "/models/" + model.id())
                                .with(authentication(auth(owner)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"新模型\",\"modelId\":\"New-ID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("New-ID"));
        assertThatThrownBy(() -> llm.deleteModel(outsider, otherTenant, source.id(), model.id()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void importsIdempotentlyUnderConcurrencyAndKeepsDisabledModelsDisabled() throws Exception {
        var manual =
                llm.createModel(
                        owner, tenant, source.id(), new LlmRequests.ModelInput("原名", "existing"));
        llm.updateModel(owner, tenant, source.id(), manual.id(), Map.of("enabled", false));
        var input =
                new LlmRequests.ImportModels(
                        List.of(
                                new LlmRequests.ModelInput("覆盖名", "existing"),
                                new LlmRequests.ModelInput("新模型", "New-ID"),
                                new LlmRequests.ModelInput("重复新模型", "New-ID")));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> llm.importModels(owner, tenant, source.id(), input));
            var second = pool.submit(() -> llm.importModels(owner, tenant, source.id(), input));
            assertThat(
                            first.get(10, TimeUnit.SECONDS).created().size()
                                    + second.get(10, TimeUnit.SECONDS).created().size())
                    .isEqualTo(1);
        }
        var all = llm.models(member, tenant, source.id(), 1, 20, null);
        assertThat(all.total()).isEqualTo(2);
        var old =
                all.records().stream().filter(m -> m.id() == manual.id()).findFirst().orElseThrow();
        assertThat(old.name()).isEqualTo("原名");
        assertThat(old.enabled()).isFalse();
        assertThat(old.origin()).isEqualTo("MANUAL");
        llm.updateSource(owner, tenant, source.id(), Map.of("enabled", false));
        llm.updateSource(owner, tenant, source.id(), Map.of("enabled", true));
        assertThat(
                        llm.models(member, tenant, source.id(), 1, 20, "existing")
                                .records()
                                .getFirst()
                                .enabled())
                .isFalse();
    }

    @Test
    void deletesAtomicallyAndPreservesTenantAndFutureForeignKeyProtection() throws Exception {
        var model =
                llm.createModel(
                        owner, tenant, source.id(), new LlmRequests.ModelInput("模型", "test-model"));
        // 仅在测试中建立依赖探针，验证未来业务外键能使级联删除整体回滚。
        jdbc.execute(
                "CREATE TABLE llm_dependency_probe (model_id bigint REFERENCES lc_llm_model(id) ON"
                        + " DELETE RESTRICT)");
        jdbc.update("INSERT INTO llm_dependency_probe VALUES (?)", model.id());
        mvc.perform(
                        delete(base(tenant, source.id()))
                                .with(authentication(auth(owner)))
                                .with(csrf()))
                .andExpect(status().isConflict());
        assertThat(llm.models(member, tenant, source.id(), 1, 20, null).total()).isEqualTo(1);
        assertThatThrownBy(() -> identity.deleteTenant(admin, tenant))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(identity.members(owner, tenant, 1, 20)).hasSize(2);
        jdbc.execute("DROP TABLE llm_dependency_probe");
        mvc.perform(
                        delete(base(tenant, source.id()))
                                .with(authentication(auth(owner)))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_llm_model", Long.class)).isZero();
        assertThat(llm.sources(member, tenant, 1, 20, null).total()).isZero();
    }

    private String ciphertext() {
        return jdbc.queryForObject(
                "SELECT api_key_ciphertext FROM lc_llm_source WHERE id=?",
                String.class,
                source.id());
    }

    private LlmRequests.CreateSource input(String name, String key) {
        return new LlmRequests.CreateSource(
                name, "测试供应商", "OPENAI_COMPATIBLE", "https://example.test/v1", key, true);
    }

    private String base(long tenantId, long sourceId) {
        return "/api/tenants/" + tenantId + "/llm-sources/" + sourceId;
    }

    private Authentication auth(UserAccount user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }
}
