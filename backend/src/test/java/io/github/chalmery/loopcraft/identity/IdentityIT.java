package io.github.chalmery.loopcraft.identity;

import java.util.List;

import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.Tenant;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
@Sql({"file:sql/identity/create_user.sql", "file:sql/identity/create_tenant.sql",
        "file:sql/identity/create_tenant_member.sql"})
class IdentityIT {
    private static final String PASSWORD = "integration-password-123";

    @Autowired
    private IdentityService identity;
    @Autowired
    private UserMapper users;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    private UserAccount admin;

    @BeforeEach
    void initializeAdmin() {
        identity.bootstrap("rooter", PASSWORD);
        admin = identity.login("rooter", PASSWORD);
    }

    @Test
    void persistsUsersWithLambdaCrudAndNeverOverwritesBootstrap() {
        identity.bootstrap("different-admin", "different-password-123");
        assertThat(users.selectCount(null)).isEqualTo(1);
        assertThat(identity.login("ROOTER", PASSWORD).getId()).isEqualTo(admin.getId());

        UserAccount user = identity.createUser(admin, "Alice", PASSWORD, " 爱丽丝 ");
        assertThat(user.getId()).isNotNull();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPasswordHash()).startsWith("{pbkdf2@SpringSecurity_v5_8}").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(identity.rename(user, "新名称").getDisplayName()).isEqualTo("新名称");
        assertThat(identity.users(admin, 2, 1)).extracting(UserAccount::getId).containsExactly(user.getId());
        identity.setEnabled(admin, user.getId(), false);
        assertThat(users.selectById(user.getId()).getEnabled()).isFalse();
        assertThat(users.selectById(user.getId()).getCredentialVersion()).isEqualTo(1);
    }

    @Test
    void isolatesTenantsAndSupportsMemberLifecycleWithBatchQueries() {
        UserAccount owner = identity.createUser(admin, "owner", PASSWORD, "负责人");
        UserAccount member = identity.createUser(admin, "member", PASSWORD, "成员");
        UserAccount outsider = identity.createUser(admin, "outsider", PASSWORD, "其他用户");
        Tenant tenant = identity.createTenant(admin, "团队", owner.getId());
        Tenant other = identity.createTenant(admin, "其他团队", outsider.getId());
        identity.addMember(owner, tenant.getId(), member.getId());
        assertThat(identity.tenants(owner, 1, 50)).extracting(Tenant::getId).containsExactly(tenant.getId());
        assertThat(identity.tenants(admin, 2, 1)).extracting(Tenant::getId).containsExactly(other.getId());
        assertThat(identity.tenants(member, 1, 50)).extracting(Tenant::getId).containsExactly(tenant.getId());
        assertThat(identity.members(owner, tenant.getId(), 2, 1)).extracting(value -> value.username())
                .containsExactly("member");
        assertThat(identity.renameTenant(owner, tenant.getId(), "新团队").getName()).isEqualTo("新团队");
        assertThatThrownBy(() -> identity.tenant(outsider, tenant.getId(), false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> identity.addMember(member, tenant.getId(), outsider.getId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> identity.removeMember(owner, tenant.getId(), owner.getId()))
                .isInstanceOf(ResponseStatusException.class);
        identity.removeMember(owner, tenant.getId(), member.getId());
        assertThat(identity.tenants(member, 1, 50)).isEmpty();
        assertThatThrownBy(() -> identity.tenant(member, tenant.getId(), false))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsDuplicateUsernamesInDatabase() {
        identity.createUser(admin, "alice", PASSWORD, "用户");
        assertThatThrownBy(() -> identity.createUser(admin, "ALICE", PASSWORD, "用户"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateMembershipInDatabase() {
        Tenant tenant = identity.createTenant(admin, "团队", admin.getId());
        UserAccount member = identity.createUser(admin, "member", PASSWORD, "成员");
        identity.addMember(admin, tenant.getId(), member.getId());
        assertThatThrownBy(() -> identity.addMember(admin, tenant.getId(), member.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validatesRealCsrfRotationAndSessionPersistence() throws Exception {
        var initial = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession();
        String oldToken = token(initial.getResponse().getContentAsString());
        String oldSessionId = session.getId();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", oldToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"rooter\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.platformAdmin").value(true));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(patch("/api/users/me").session(session).header("X-CSRF-TOKEN", oldToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新名称\"}"))
                .andExpect(status().isForbidden());
        String newToken = token(mvc.perform(get("/api/auth/csrf").session(session)).andReturn()
                .getResponse().getContentAsString());
        mvc.perform(patch("/api/users/me").session(session).header("X-CSRF-TOKEN", newToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新名称\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("新名称"));
    }

    @Test
    void passwordChangeRevokesOtherSessionsAndRequiresNewPassword() throws Exception {
        UserAccount user = identity.createUser(admin, "alice", PASSWORD, "用户");
        Authentication before = authenticationFor(user);
        mvc.perform(post("/api/users/me/password").with(authentication(before)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"" + PASSWORD
                        + "\",\"newPassword\":\"new-integration-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/users/me").with(authentication(before))).andExpect(status().isUnauthorized());
        assertThat(identity.login("alice", "new-integration-password").getCredentialVersion()).isEqualTo(1);
        assertThatThrownBy(() -> identity.login("alice", PASSWORD))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }

    @Test
    void mapsConstraintConflictsThroughHttp() throws Exception {
        identity.createUser(admin, "alice", PASSWORD, "用户");
        mvc.perform(post("/api/users").with(authentication(authenticationFor(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"用户\"}"))
                .andExpect(status().isConflict());
        // 唯一键冲突后不继续使用当前事务。
    }

    @Test
    void disablingUserImmediatelyRevokesSessionAndReenableDoesNotReviveIt() throws Exception {
        UserAccount user = identity.createUser(admin, "alice", PASSWORD, "用户");
        Authentication before = authenticationFor(user);
        identity.setEnabled(admin, user.getId(), false);
        mvc.perform(get("/api/users/me").with(authentication(before))).andExpect(status().isUnauthorized());
        identity.setEnabled(admin, user.getId(), true);
        mvc.perform(get("/api/users/me").with(authentication(before))).andExpect(status().isUnauthorized());
        assertThat(identity.login("alice", PASSWORD).getEnabled()).isTrue();
    }

    @Test
    void allThreeTablesAndColumnsHaveChineseComments() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname IN ('lc_user', 'lc_tenant', 'lc_tenant_member')
                AND obj_description(c.oid, 'pg_class') IS NOT NULL
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname IN ('lc_user', 'lc_tenant', 'lc_tenant_member') AND a.attnum > 0
                AND NOT a.attisdropped AND col_description(c.oid, a.attnum) IS NULL
                """, Integer.class)).isZero();
    }

    private Authentication authenticationFor(UserAccount user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }

    private String token(String json) {
        return JsonMapper.builder().build().readTree(json).path("data").path("token").asString();
    }
}
