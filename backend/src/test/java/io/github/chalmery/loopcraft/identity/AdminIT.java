package io.github.chalmery.loopcraft.identity;

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

import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Sql(
        scripts = {
            "file:sql/identity/create_user.sql",
            "file:sql/identity/create_tenant.sql",
            "file:sql/identity/create_tenant_member.sql"
        })
@Sql(
        statements =
                "DROP TABLE IF EXISTS tenant_dependency_probe, lc_tenant_member, lc_tenant, lc_user"
                        + " CASCADE",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class AdminIT {

    private static final String PASSWORD = "integration-password-123";

    @Autowired private IdentityService identity;

    @Autowired private MockMvc mvc;

    @Autowired private UserMapper users;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private PlatformTransactionManager transactions;

    private UserAccount admin;

    private UserAccount owner;

    private UserAccount member;

    private long tenantId;

    @BeforeEach
    void setup() {
        identity.bootstrap("rooter", PASSWORD);
        admin = identity.login("rooter", PASSWORD);
        owner = identity.createUser(admin, "owner", PASSWORD, "负责人");
        member = identity.createUser(admin, "member", PASSWORD, "普通成员");
        tenantId = identity.createTenant(admin, "测试租户", owner.getId()).getId();
        identity.addMember(owner, tenantId, member.getId());
    }

    @Test
    void paginatesWithTotalsAndReturnsOwnerAndCurrentRole() throws Exception {
        mvc.perform(get("/api/admin/users?page=2&size=2").with(authentication(auth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(member.getId()))
                .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/admin/tenants").with(authentication(auth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].ownerUserId").value(owner.getId()));
        mvc.perform(get("/api/tenants").with(authentication(auth(owner))))
                .andExpect(jsonPath("$.data[0].myRole").value("OWNER"));
        mvc.perform(get("/api/admin/users?page=0").with(authentication(auth(admin))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users?size=101").with(authentication(auth(admin))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsEveryAdminOperationForOwnersAndMembersAndRequiresCsrf() throws Exception {
        for (UserAccount actor : List.of(owner, member)) {
            for (String resource : List.of("users", "tenants")) {
                mvc.perform(get("/api/admin/" + resource).with(authentication(auth(actor))))
                        .andExpect(status().isForbidden());
                String body =
                        resource.equals("users")
                                ? "{\"username\":\"another\",\"password\":\""
                                        + PASSWORD
                                        + "\",\"displayName\":\"测试\"}"
                                : "{\"name\":\"新租户\",\"ownerUserId\":" + owner.getId() + "}";
                mvc.perform(
                                post("/api/admin/" + resource)
                                        .with(authentication(auth(actor)))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isForbidden());
                String update =
                        resource.equals("users")
                                ? "{\"username\":\"member\",\"displayName\":\"测试\",\"enabled\":true}"
                                : body;
                mvc.perform(
                                patch(
                                                "/api/admin/"
                                                        + resource
                                                        + "/"
                                                        + (resource.equals("users")
                                                                ? member.getId()
                                                                : tenantId))
                                        .with(authentication(auth(actor)))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(update))
                        .andExpect(status().isForbidden());
                mvc.perform(
                                delete("/api/admin/" + resource + "/1")
                                        .with(authentication(auth(actor)))
                                        .with(csrf()))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(delete("/api/admin/users/" + member.getId()).with(authentication(auth(admin))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        for (UserAccount actor : List.of(admin, member)) {
            mvc.perform(
                            get("/api/tenants/" + tenantId + "/members")
                                    .with(authentication(auth(actor))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/tenants/" + tenantId + "/members").with(authentication(auth(owner))))
                .andExpect(status().isOk());
    }

    @Test
    void editsAccountsRevokesSessionsAndDeletesMemberships() throws Exception {
        Authentication oldSession = auth(member);
        mvc.perform(
                        patch("/api/admin/users/" + member.getId())
                                .with(authentication(auth(admin)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"renamed\",\"displayName\":\"新名称\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("新名称"));
        mvc.perform(get("/api/users/me").with(authentication(oldSession)))
                .andExpect(status().isUnauthorized());
        identity.updateUser(admin, member.getId(), "renamed", "新名称", true);
        mvc.perform(get("/api/users/me").with(authentication(oldSession)))
                .andExpect(status().isUnauthorized());
        Authentication current = auth(identity.login("renamed", PASSWORD));
        mvc.perform(
                        delete("/api/admin/users/" + member.getId())
                                .with(authentication(auth(admin)))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(users.selectById(member.getId())).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lc_tenant_member WHERE user_id = ?",
                                Long.class,
                                member.getId()))
                .isZero();
        mvc.perform(get("/api/users/me").with(authentication(current)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectsAdminsAndOwnersAndTransfersOwnershipAtomically() throws Exception {
        for (long id : List.of(admin.getId(), owner.getId())) {
            mvc.perform(
                            delete("/api/admin/users/" + id)
                                    .with(authentication(auth(admin)))
                                    .with(csrf()))
                    .andExpect(status().isConflict());
        }
        mvc.perform(
                        patch("/api/admin/users/" + admin.getId())
                                .with(authentication(auth(admin)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"rooter\",\"displayName\":\"管理员\",\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/api/admin/tenants/" + tenantId)
                                .with(authentication(auth(admin)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"新租户名称\",\"ownerUserId\":"
                                                + member.getId()
                                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新租户名称"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lc_tenant_member WHERE tenant_id = ? AND role"
                                        + " = 'OWNER'",
                                Long.class,
                                tenantId))
                .isEqualTo(1);
        mvc.perform(get("/api/tenants/" + tenantId + "/members").with(authentication(auth(owner))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/" + tenantId + "/members").with(authentication(auth(member))))
                .andExpect(status().isOk());
        identity.deleteUser(admin, owner.getId());
    }

    @Test
    void deletesEmptyTenantButRollsBackWhenBusinessDataExists() throws Exception {
        long empty = identity.createTenant(admin, "空租户", owner.getId()).getId();
        mvc.perform(
                        delete("/api/admin/tenants/" + empty)
                                .with(authentication(auth(admin)))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lc_tenant_member WHERE tenant_id = ?",
                                Long.class,
                                empty))
                .isZero();
        // 用测试专属外键验证删除保护，不依赖尚未实现的业务模块。
        jdbc.execute(
                "CREATE TABLE tenant_dependency_probe (tenant_id bigint NOT NULL REFERENCES"
                        + " lc_tenant(id))");
        jdbc.update("INSERT INTO tenant_dependency_probe (tenant_id) VALUES (?)", tenantId);
        mvc.perform(
                        delete("/api/admin/tenants/" + tenantId)
                                .with(authentication(auth(admin)))
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("租户仍有关联业务数据，请先清理关联记录"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lc_tenant_member WHERE tenant_id = ?",
                                Long.class,
                                tenantId))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(transactions)
                                        .executeWithoutResult(
                                                status ->
                                                        identity.updateTenant(
                                                                admin, tenantId, "不应保存", 999999)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(identity.tenant(owner, tenantId, false).getName()).isEqualTo("测试租户");
    }

    private Authentication auth(UserAccount user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
    }

    @Test
    void memberRemovalWaitsForTransferAndRechecksOwnerPermission() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> removal =
                    new TransactionTemplate(transactions)
                            .execute(
                                    status -> {
                                        // 在交接取得租户锁后启动删除，稳定复现两次请求交错。
                                        jdbc.queryForObject(
                                                "SELECT id FROM lc_tenant WHERE id=? FOR UPDATE",
                                                Long.class,
                                                tenantId);
                                        Future<Integer> pending =
                                                executor.submit(
                                                        () -> {
                                                            try {
                                                                new TransactionTemplate(
                                                                                transactions)
                                                                        .executeWithoutResult(
                                                                                removalStatus -> {
                                                                                    jdbc.execute(
                                                                                            "SET LOCAL"
                                                                                                + " application_name"
                                                                                                + " = 'loopcraft-member-removal-test'");
                                                                                    identity
                                                                                            .removeMember(
                                                                                                    owner,
                                                                                                    tenantId,
                                                                                                    member
                                                                                                            .getId());
                                                                                });
                                                                return 200;
                                                            } catch (
                                                                    ResponseStatusException error) {
                                                                return error.getStatusCode()
                                                                        .value();
                                                            }
                                                        });
                                        awaitMemberRemovalLock(pending);
                                        identity.updateTenant(
                                                admin, tenantId, "交接后", member.getId());
                                        return pending;
                                    });
            assertThat(removal.get(10, TimeUnit.SECONDS)).isEqualTo(403);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_id FROM lc_tenant_member WHERE tenant_id=? AND"
                                        + " role='OWNER'",
                                Long.class,
                                tenantId))
                .isEqualTo(member.getId());
        assertThat(identity.tenantPage(admin, 1, 10).records()).hasSize(1);
    }

    @Test
    void recreatesMembershipWhenTransferFollowsMemberRemoval() {
        identity.removeMember(owner, tenantId, member.getId());
        identity.updateTenant(admin, tenantId, "交接后", member.getId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_id FROM lc_tenant_member WHERE tenant_id=? AND"
                                        + " role='OWNER'",
                                Long.class,
                                tenantId))
                .isEqualTo(member.getId());
    }

    private void awaitMemberRemovalLock(Future<?> pending) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !pending.isDone()) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Long waiting =
                    jdbc.queryForObject(
                            """
                            SELECT count(*) FROM pg_stat_activity
                            WHERE application_name='loopcraft-member-removal-test' AND wait_event_type='Lock'
                            """,
                            Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待成员删除锁时被中断", error);
            }
        }
        throw new AssertionError("成员删除没有等待租户锁");
    }
}
