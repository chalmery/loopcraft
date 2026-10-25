package io.github.chalmery.loopcraft.mcp;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.github.chalmery.loopcraft.llm.service.LlmCredentialCipher;
import io.github.chalmery.loopcraft.mcp.service.McpServerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

/** MCP 服务配置的权限边界、鉴权头不回显、slug 唯一与 stdio 开关。 */
@SpringBootTest(
        properties = "loopcraft.llm.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Sql(
        scripts = {
            "file:sql/identity/create_user.sql",
            "file:sql/identity/create_tenant.sql",
            "file:sql/identity/create_tenant_member.sql",
            "file:sql/mcp/create_mcp_server.sql"
        })
@Sql(
        statements =
                "DROP TABLE IF EXISTS lc_mcp_server, lc_tenant_member, lc_tenant, lc_user CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class McpServerIT {

    private static final String PASSWORD = "integration-password-123";

    private static final String CREATE_BODY =
            """
            {"name":"Context7","slug":"context7","transport":"HTTP_STREAMABLE",
             "url":"https://mcp.example.com/mcp",
             "authHeaders":[{"name":"Authorization","value":"secret-token"}]}
            """;

    @Autowired private IdentityService identity;

    @Autowired private McpServerService servers;

    @Autowired private LlmCredentialCipher cipher;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private tools.jackson.databind.ObjectMapper json;

    @Autowired private MockMvc mvc;

    private UserAccount admin;

    private UserAccount owner;

    private UserAccount member;

    private UserAccount outsider;

    private long tenant;

    private long otherTenant;

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
    }

    @Test
    void scopesReadsWritesAndNeverEchoesCredentialValues() throws Exception {
        long id = createServer();
        String base = base(tenant, id);
        mvc.perform(get(base).with(authentication(auth(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("context7"))
                .andExpect(jsonPath("$.data.authHeaders[0].name").value("Authorization"))
                .andExpect(jsonPath("$.data.authHeaders[0].ciphertext").doesNotExist())
                .andExpect(jsonPath("$.data.authHeaders[0].value").doesNotExist());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get(base(otherTenant, id)).with(authentication(auth(outsider))))
                .andExpect(status().isNotFound());
        mvc.perform(get(base).with(authentication(auth(outsider))))
                .andExpect(status().isForbidden());
        mvc.perform(patch(base).with(authentication(auth(member))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(base).with(authentication(auth(member))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/tenants/" + tenant + "/mcp-servers")
                                .with(authentication(auth(member)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/" + tenant + "/mcp-servers/options")
                        .with(authentication(auth(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stdioEnabled").value(false));
        mvc.perform(delete(base).with(authentication(auth(owner))).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get(base).with(authentication(auth(owner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void replacesHeadersAsWholeListAndKeepsBlankSameNameEntries() throws Exception {
        long id = createServer();
        String before = storedCiphertext(id);
        assertThat(cipher.decrypt(tenant, before)).isEqualTo("secret-token");
        // 改名不动鉴权头；同名留空沿用原密文。
        mvc.perform(patch(base(tenant, id)).with(authentication(auth(owner))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Context7 文档\",\"authHeaders\":[{\"name\":\"Authorization\",\"value\":\"\"}]}"))
                .andExpect(status().isOk());
        assertThat(storedCiphertext(id)).isEqualTo(before);
        // 提供新值即更换。
        mvc.perform(patch(base(tenant, id)).with(authentication(auth(owner))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authHeaders\":[{\"name\":\"Authorization\",\"value\":\"replacement\"}]}"))
                .andExpect(status().isOk());
        assertThat(cipher.decrypt(tenant, storedCiphertext(id))).isEqualTo("replacement");
        // 新名称留空没有可沿用的密文。
        mvc.perform(patch(base(tenant, id)).with(authentication(auth(owner))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authHeaders\":[{\"name\":\"X-Api-Key\",\"value\":\"\"}]}"))
                .andExpect(status().isBadRequest());
        // 空清单整体清除。
        mvc.perform(patch(base(tenant, id)).with(authentication(auth(owner))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authHeaders\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authHeaders.length()").value(0));
        assertThat(storedCiphertext(id)).isNull();
    }

    @Test
    void enforcesSlugUniquenessShapeAndTransportFields() throws Exception {
        createServer();
        mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers")
                        .with(authentication(auth(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers")
                        .with(authentication(auth(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"坏标识","slug":"Context7","transport":"HTTP_STREAMABLE",
                                 "url":"https://mcp.example.com/mcp"}
                                """))
                .andExpect(status().isBadRequest());
        // 远程传输缺地址、stdio 缺命令分别拒绝。
        for (String body :
                List.of(
                        "{\"name\":\"无地址\",\"slug\":\"no-url\",\"transport\":\"HTTP_STREAMABLE\"}",
                        "{\"name\":\"无命令\",\"slug\":\"no-cmd\",\"transport\":\"STDIO\",\"command\":\"\"}")) {
            mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers")
                            .with(authentication(auth(owner)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        // 相同 slug 允许出现在其他租户。
        mvc.perform(post("/api/tenants/" + otherTenant + "/mcp-servers")
                        .with(authentication(auth(outsider)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void stdioSwitchGatesConfigAndProbeReportsLocalRefusal() throws Exception {
        // 默认 stdio 关闭：创建被拒，未保存配置的探测返回失败原因。
        mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers")
                        .with(authentication(auth(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"本地文件","slug":"filesystem","transport":"STDIO",
                                 "command":"npx","args":["server-filesystem","/data"]}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers/test")
                        .with(authentication(auth(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"本地文件","slug":"filesystem","transport":"STDIO",
                                 "command":"npx"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.reason").isNotEmpty());
        // 已保存的远程配置探测：指向本机未监听端口，确定性拿到 ok=false 的原因。
        long id = createServer();
        mvc.perform(patch(base(tenant, id)).with(authentication(auth(owner))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://127.0.0.1:9/mcp\"}"))
                .andExpect(status().isOk());
        MvcResult result =
                mvc.perform(post(base(tenant, id) + "/test")
                                .with(authentication(auth(owner)))
                                .with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.ok").value(false))
                        .andExpect(jsonPath("$.data.reason").isNotEmpty())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-token");
    }

    private long createServer() throws Exception {
        MvcResult result =
                mvc.perform(post("/api/tenants/" + tenant + "/mcp-servers")
                                .with(authentication(auth(owner)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_BODY))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();
    }

    private String storedCiphertext(long id) {
        List<String> rows =
                jdbc.query(
                        "SELECT auth_headers FROM lc_mcp_server WHERE id = ?",
                        (rs, i) -> rs.getString(1),
                        id);
        String stored = rows.isEmpty() ? null : rows.get(0);
        if (stored == null || stored.isBlank() || "[]".equals(stored)) {
            return null;
        }
        return json.readTree(stored).get(0).path("ciphertext").asString();
    }

    private String base(long tenantId, long serverId) {
        return "/api/tenants/" + tenantId + "/mcp-servers/" + serverId;
    }

    private Authentication auth(UserAccount user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }
}
