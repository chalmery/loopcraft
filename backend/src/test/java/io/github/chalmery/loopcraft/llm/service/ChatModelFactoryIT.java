package io.github.chalmery.loopcraft.llm.service;

import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.dto.LlmRequests;
import io.github.chalmery.loopcraft.llm.dto.LlmViews;
import io.github.chalmery.loopcraft.template.dto.TemplateInput;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.template.service.TemplateService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatModelFactory 真链路集成测试：模板→模型→来源经过真实 Mapper 查询与凭证解密；
 * 模板对模型有复合外键，悬挂引用由数据库排除，失效路径覆盖禁用与凭证清空。
 */
@SpringBootTest(
        properties = "loopcraft.llm.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration")
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
                "DROP TABLE IF EXISTS lc_template_request, lc_template_skill, lc_agent_template,"
                        + " lc_llm_model, lc_llm_source, lc_skill_object_upload, lc_skill_tag,"
                        + " lc_skill_version, lc_skill, lc_tenant_member, lc_tenant, lc_user CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ChatModelFactoryIT {

    private static final String PASSWORD = "integration-password-123";

    @Autowired private IdentityService identity;

    @Autowired private LlmAccessService llm;

    @Autowired private TemplateService templates;

    @Autowired private AgentTemplateMapper templateRows;

    @Autowired private ChatModelFactory factory;

    @Autowired private JdbcTemplate jdbc;

    private long tenant;

    private long sourceId;

    private long modelId;

    private long templateId;

    @BeforeEach
    void setup() {
        identity.bootstrap("rooter", PASSWORD);
        UserAccount admin = identity.login("rooter", PASSWORD);
        UserAccount owner = identity.createUser(admin, "owner", PASSWORD, "负责人");
        tenant = identity.createTenant(admin, "工厂租户", owner.getId()).getId();
        LlmViews.Source source =
                llm.createSource(
                        owner,
                        tenant,
                        new LlmRequests.CreateSource(
                                "工厂连接", "测试供应商", "OPENAI_COMPATIBLE",
                                "https://example.test/v1", "it-factory-key", true));
        sourceId = source.id();
        LlmViews.Model model =
                llm.createModel(owner, tenant, sourceId, new LlmRequests.ModelInput("集成模型", "it-stub-model"));
        modelId = model.id();
        templateId =
                templates
                        .save(
                                owner,
                                tenant,
                                null,
                                new TemplateInput(
                                        UUID.randomUUID().toString(),
                                        null,
                                        "集成模板",
                                        "",
                                        true,
                                        modelId,
                                        "系统提示",
                                        new BigDecimal("0.7"),
                                        null,
                                        null,
                                        10,
                                        6L,
                                        new BigDecimal("2"),
                                        List.of()))
                        .id();
    }

    @Test
    void prepareBuildsOptionsFromRealRowsAndDecryptsStoredCredential() {
        AgentTemplate template = templateRows.selectById(templateId);

        ChatModelFactory.Prepared prepared = factory.prepare(tenant, template, Map.of());

        assertThat(prepared.chatModel()).isNotNull();
        OpenAiChatOptions options = (OpenAiChatOptions) prepared.options();
        assertThat(options.getModel()).isEqualTo("it-stub-model");
        assertThat(options.getTemperature()).isEqualTo(0.7d);
    }

    @Test
    void disabledModelFailsClosedWith503() {
        jdbc.update("UPDATE lc_llm_model SET enabled = false WHERE id = ?", modelId);

        assertThatPrepareFailsWith("模板引用的模型不存在或已停用");
    }

    @Test
    void disabledSourceFailsClosedWith503() {
        jdbc.update("UPDATE lc_llm_source SET enabled = false WHERE id = ?", sourceId);

        assertThatPrepareFailsWith("模板引用的模型连接不存在或已停用");
    }

    @Test
    void clearedApiKeyFailsClosedWith503() {
        jdbc.update("UPDATE lc_llm_source SET api_key_ciphertext = NULL WHERE id = ?", sourceId);

        assertThatPrepareFailsWith("模板引用的模型连接未配置 API Key");
    }

    private void assertThatPrepareFailsWith(String reason) {
        AgentTemplate template = templateRows.selectById(templateId);
        assertThatThrownBy(() -> factory.prepare(tenant, template, Map.of()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(error.getReason()).isEqualTo(reason);
                        });
    }
}
