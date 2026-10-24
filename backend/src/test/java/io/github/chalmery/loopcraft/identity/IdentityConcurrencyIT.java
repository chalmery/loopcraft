package io.github.chalmery.loopcraft.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.chalmery.loopcraft.identity.dto.IdentityViews;
import io.github.chalmery.loopcraft.identity.mapper.TenantMemberMapper;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 使用真实事务和数据库锁，固定并发请求交错的位置。 */
@SpringBootTest
@ActiveProfiles("integration")
@Import(IdentityConcurrencyIT.GateConfiguration.class)
@Sql({
    "file:sql/identity/create_user.sql",
    "file:sql/identity/create_tenant.sql",
    "file:sql/identity/create_tenant_member.sql"
})
@Sql(
        statements = "DROP TABLE lc_tenant_member, lc_tenant, lc_user",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class IdentityConcurrencyIT {

    private static final String PASSWORD = "concurrency-password-123";

    @Autowired private IdentityService identity;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private PlatformTransactionManager transactions;

    @Autowired private OwnerQueryGate gate;

    private UserAccount admin;

    private UserAccount owner;

    private UserAccount member;

    private long tenantId;

    @BeforeEach
    void setup() {
        gate.reset();
        identity.bootstrap("rooter", PASSWORD);
        admin = identity.login("rooter", PASSWORD);
        owner = identity.createUser(admin, "owner", PASSWORD, "原负责人");
        member = identity.createUser(admin, "member", PASSWORD, "成员");
        tenantId = identity.createTenant(admin, "原租户名称", owner.getId()).getId();
        identity.addMember(owner, tenantId, member.getId());
    }

    @Test
    void concurrentAccountEditReadsLatestStateAndRevokesTheInterveningSession() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PendingEdit pending =
                    new TransactionTemplate(transactions)
                            .execute(
                                    status -> {
                                        jdbc.queryForObject(
                                                "SELECT id FROM lc_user WHERE id=? FOR UPDATE",
                                                Long.class,
                                                member.getId());
                                        Future<UserAccount> edit =
                                                executor.submit(
                                                        () ->
                                                                new TransactionTemplate(
                                                                                transactions)
                                                                        .execute(
                                                                                editStatus -> {
                                                                                    jdbc.execute(
                                                                                            "SET LOCAL"
                                                                                                + " application_name"
                                                                                                + " = 'loopcraft-account-edit-test'");
                                                                                    return identity
                                                                                            .updateUser(
                                                                                                    admin,
                                                                                                    member
                                                                                                            .getId(),
                                                                                                    "member",
                                                                                                    "旧编辑",
                                                                                                    true);
                                                                                }));
                                        awaitAccountEditLock(edit);
                                        identity.updateUser(
                                                admin, member.getId(), "renamed", "新编辑", true);
                                        UserAccount loggedIn = identity.login("renamed", PASSWORD);
                                        Authentication session =
                                                UsernamePasswordAuthenticationToken.authenticated(
                                                        new SessionUser(
                                                                loggedIn.getId(),
                                                                loggedIn.getCredentialVersion()),
                                                        null,
                                                        List.of());
                                        return new PendingEdit(edit, session);
                                    });
            UserAccount updated = pending.edit().get(10, TimeUnit.SECONDS);
            assertThat(updated.getUsername()).isEqualTo("member");
            assertThat(updated.getCredentialVersion()).isEqualTo(2);
            assertThatThrownBy(() -> identity.current(pending.session()))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    @Test
    void tenantPageKeepsItsSnapshotDuringOwnerTransferAndAccountDeletion() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IdentityViews.PageResult<IdentityViews.ManagedTenant>> page =
                    executor.submit(this::pausedTenantPage);
            try {
                await(gate.ownerRead);
                identity.updateTenant(admin, tenantId, "交接后", member.getId());
                identity.deleteUser(admin, owner.getId());
            } finally {
                gate.resume.countDown();
            }
            IdentityViews.ManagedTenant snapshot =
                    page.get(10, TimeUnit.SECONDS).records().getFirst();
            assertThat(snapshot.ownerUserId()).isEqualTo(owner.getId());
            assertThat(snapshot.ownerUsername()).isEqualTo("owner");
            assertThat(snapshot.name()).isEqualTo("原租户名称");
        }
        assertThat(identity.tenantPage(admin, 1, 10).records().getFirst().ownerUserId())
                .isEqualTo(member.getId());
    }

    @Test
    void tenantPageKeepsItsSnapshotDuringTenantDeletion() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<IdentityViews.PageResult<IdentityViews.ManagedTenant>> page =
                    executor.submit(this::pausedTenantPage);
            try {
                await(gate.ownerRead);
                identity.deleteTenant(admin, tenantId);
            } finally {
                gate.resume.countDown();
            }
            IdentityViews.PageResult<IdentityViews.ManagedTenant> snapshot =
                    page.get(10, TimeUnit.SECONDS);
            assertThat(snapshot.total()).isEqualTo(1);
            assertThat(snapshot.records())
                    .extracting(IdentityViews.ManagedTenant::id)
                    .containsExactly(tenantId);
        }
        assertThat(identity.tenantPage(admin, 1, 10).records()).isEmpty();
    }

    @Test
    void missingOwnerReturnsConflictWithoutGeneratingAnEmptyInClause() {
        jdbc.update("DELETE FROM lc_tenant_member WHERE tenant_id=? AND role='OWNER'", tenantId);
        assertThatThrownBy(() -> identity.tenantPage(admin, 1, 10))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private IdentityViews.PageResult<IdentityViews.ManagedTenant> pausedTenantPage() {
        Thread.currentThread().setName("tenant-page-reader");
        return identity.tenantPage(admin, 1, 10);
    }

    private void awaitAccountEditLock(Future<?> pending) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !pending.isDone()) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Long waiting =
                    jdbc.queryForObject(
                            """
                            SELECT count(*) FROM pg_stat_activity
                            WHERE application_name='loopcraft-account-edit-test' AND wait_event_type='Lock'
                            """,
                            Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待账号编辑锁时被中断", error);
            }
        }
        throw new AssertionError("账号编辑没有等待行锁");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待并发请求时被中断", error);
        }
    }

    private record PendingEdit(Future<UserAccount> edit, Authentication session) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {

        @Bean
        OwnerQueryGate ownerQueryGate() {
            return new OwnerQueryGate();
        }
    }

    /** 只暂停列表读取线程，交接和删除仍完整经过生产代码。 */
    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {
                        MappedStatement.class,
                        Object.class,
                        RowBounds.class,
                        ResultHandler.class
                    }))
    static class OwnerQueryGate implements Interceptor {

        private final AtomicBoolean paused = new AtomicBoolean();

        private CountDownLatch ownerRead;

        private CountDownLatch resume;

        void reset() {
            paused.set(false);
            ownerRead = new CountDownLatch(1);
            resume = new CountDownLatch(1);
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (Thread.currentThread().getName().equals("tenant-page-reader")
                    && statement.getId().equals(TenantMemberMapper.class.getName() + ".selectList")
                    && paused.compareAndSet(false, true)) {
                ownerRead.countDown();
                await(resume);
            }
            return result;
        }
    }
}
