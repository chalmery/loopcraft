package io.github.chalmery.loopcraft.identity.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.chalmery.loopcraft.identity.config.LoginProtectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class LoginProtectionTests {
    private final AtomicLong now = new AtomicLong();

    @Test
    void sharesAccountLimitAcrossCaseAndAddressesAndDoesNotExtendCooldown() {
        var protection = protection(2, 30, 100);
        wrong(protection, "Tester", "ip-1");
        now.set(TimeUnit.SECONDS.toNanos(2));
        wrong(protection, "TESTER", "ip-2");
        assertBlocked(protection, "tester", "ip-3", 10);
        now.set(TimeUnit.MILLISECONDS.toNanos(2501));
        assertBlocked(protection, "Tester", "ip-4", 10);
        now.set(TimeUnit.SECONDS.toNanos(11));
        assertBlocked(protection, "tester", "ip-5", 1);
        now.set(TimeUnit.SECONDS.toNanos(12));
        assertThat(protection.authenticate("tester", "ip-6", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void successfulLoginClearsAccountFailuresButNeverSourceAttempts() {
        var protection = protection(2, 4, 100);
        wrong(protection, "tester", "ip");
        protection.authenticate("TESTER", "ip", () -> true);
        wrong(protection, "tester", "ip");
        protection.authenticate("different-account", "ip", () -> true);
        assertBlocked(protection, "another-account", "ip", 10);
        // 换 IP 后账号仍有一次机会，说明成功登录已清除先前失败次数。
        wrong(protection, "tester", "other-ip");
        assertBlocked(protection, "tester", "third-ip", 10);
    }

    @Test
    void expiresPartialFailuresAndSourceWindow() {
        var protection = protection(2, 2, 100);
        wrong(protection, "tester", "ip");
        wrong(protection, "other", "ip");
        assertBlocked(protection, "third", "ip", 10);
        now.set(TimeUnit.SECONDS.toNanos(10));
        wrong(protection, "tester", "ip");
        assertThat(protection.authenticate("tester", "ip", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void capacityCannotEvictActiveLimitsAndExpiredEntriesAreReclaimed() {
        var protection = protection(1, 30, 2);
        wrong(protection, "tester", "ip");
        assertBlocked(protection, "new-user", "new-ip", 10);
        assertBlocked(protection, "tester", "ip", 10);
        now.set(TimeUnit.SECONDS.toNanos(10));
        assertThat(protection.authenticate("new-user", "new-ip", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void backendErrorsReleaseReservationsWithoutCountingAsBadCredentials() {
        var protection = protection(1, 30, 100);
        assertThatThrownBy(() -> protection.authenticate("tester", "ip", () -> {
            throw new IllegalStateException("database unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(protection.authenticate("tester", "ip", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void concurrentRequestsReserveAccountSlotsBeforePasswordChecksEvenAcrossExpiry() throws Exception {
        var protection = protection(3, 30, 100);
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<?>>();
            for (int index = 0; index < 3; index++) {
                String ip = "ip-" + index;
                attempts.add(executor.submit(() ->
                        assertThatThrownBy(() -> protection.authenticate("tester", ip, () -> {
                            entered.countDown();
                            await(release);
                            throw new BadCredentialsException("wrong");
                        })).isInstanceOf(BadCredentialsException.class)));
            }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertBlocked(protection, "TESTER", "other-ip", 10);
                now.set(TimeUnit.SECONDS.toNanos(11));
                assertBlocked(protection, "tester", "other-ip", 1);
                assertThat(protection.authenticate("unrelated", "other-ip", () -> true)).isTrue();
            } finally {
                release.countDown();
            }
            for (Future<?> attempt : attempts) {
                attempt.get(5, TimeUnit.SECONDS);
            }
        }
        assertBlocked(protection, "tester", "new-ip", 10);
    }

    @Test
    void concurrentAccountsCannotOverrunSourceBudget() throws Exception {
        var protection = protection(5, 3, 100);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong admitted = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = new ArrayList<Future<?>>();
            for (int index = 0; index < 20; index++) {
                String username = "user-" + index;
                attempts.add(executor.submit(() -> {
                    await(start);
                    try {
                        protection.authenticate(username, "shared-ip", () -> admitted.incrementAndGet());
                    } catch (LoginThrottledException expected) {
                        assertThat(expected.retryAfterSeconds()).isEqualTo(10);
                    }
                }));
            }
            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(admitted.get()).isEqualTo(3);
    }

    private LoginProtection protection(int accountFailures, int ipAttempts, int maxEntries) {
        return new LoginProtection(new LoginProtectionProperties(
                accountFailures, ipAttempts, Duration.ofSeconds(10), maxEntries), now::get);
    }

    private void wrong(LoginProtection protection, String username, String ip) {
        assertThatThrownBy(() -> protection.authenticate(username, ip, () -> {
            throw new BadCredentialsException("wrong");
        })).isInstanceOf(BadCredentialsException.class);
    }

    private void assertBlocked(LoginProtection protection, String username, String ip, long retryAfter) {
        assertThatThrownBy(() -> protection.authenticate(username, ip, () -> {
            fail("被限制的请求不能执行密码校验");
            return null;
        })).isInstanceOfSatisfying(LoginThrottledException.class,
                exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(retryAfter));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
