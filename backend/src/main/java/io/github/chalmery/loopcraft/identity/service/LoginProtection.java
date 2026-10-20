package io.github.chalmery.loopcraft.identity.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.chalmery.loopcraft.identity.config.LoginProtectionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/** 在密码计算前原子预占名额；计数保存在当前实例内，过期后回收。 */
@Service
public class LoginProtection {
    private final LoginProtectionProperties properties;
    private final LongSupplier ticker;
    private final long windowNanos;
    private final Map<Key, Counter> counters = new HashMap<>();
    private long nextCleanup;

    @Autowired
    public LoginProtection(LoginProtectionProperties properties) {
        this(properties, System::nanoTime);
    }

    LoginProtection(LoginProtectionProperties properties, LongSupplier ticker) {
        this.properties = properties;
        this.ticker = ticker;
        this.windowNanos = properties.window().toNanos();
        this.nextCleanup = ticker.getAsLong();
    }

    public <T> T authenticate(String username, String remoteAddress, Supplier<T> authentication) {
        Counter account = reserve(username.toLowerCase(Locale.ROOT), remoteAddress);
        boolean succeeded = false;
        boolean badCredentials = false;
        try {
            T result = authentication.get();
            succeeded = true;
            return result;
        } catch (BadCredentialsException exception) {
            badCredentials = true;
            throw exception;
        } finally {
            finish(account, succeeded, badCredentials);
        }
    }

    private synchronized Counter reserve(String username, String remoteAddress) {
        long now = ticker.getAsLong();
        if (now - nextCleanup >= 0 || counters.size() >= properties.maxEntries()) {
            counters.values().removeIf(counter -> expired(counter, now));
            nextCleanup = now + TimeUnit.MINUTES.toNanos(1);
        }
        Key accountKey = new Key(false, username);
        Key ipKey = new Key(true, remoteAddress);
        Counter account = active(accountKey, now);
        Counter ip = active(ipKey, now);
        long retryNanos = 0;
        if (account != null && account.count + account.inFlight >= properties.accountFailures()) {
            retryNanos = Math.max(1, account.expiresAt - now);
        }
        if (ip != null && ip.count >= properties.ipAttempts()) {
            retryNanos = Math.max(retryNanos, Math.max(1, ip.expiresAt - now));
        }
        if (retryNanos > 0) {
            throw throttled(retryNanos);
        }
        int needed = (account == null ? 1 : 0) + (ip == null ? 1 : 0);
        if (counters.size() + needed > properties.maxEntries()) {
            // 容量耗尽时拒绝新键，不能淘汰仍有效的记录让攻击者绕过限制。
            long earliestExpiry = counters.values().stream()
                    .mapToLong(counter -> Math.max(1, counter.expiresAt - now)).min().orElse(windowNanos);
            throw throttled(earliestExpiry);
        }
        if (account == null) {
            account = new Counter(now + windowNanos);
            counters.put(accountKey, account);
        }
        if (ip == null) {
            ip = new Counter(now + windowNanos);
            counters.put(ipKey, ip);
        }
        account.inFlight++;
        // IP 统计所有已放行尝试，成功登录也不能清空撞库请求的来源计数。
        ip.count++;
        return account;
    }

    private synchronized void finish(Counter account, boolean succeeded, boolean badCredentials) {
        account.inFlight--;
        if (succeeded) {
            account.count = 0;
            account.expiresAt = ticker.getAsLong() + windowNanos;
        } else if (badCredentials) {
            account.count++;
            if (account.count >= properties.accountFailures()) {
                account.expiresAt = ticker.getAsLong() + windowNanos;
            }
        }
    }

    private Counter active(Key key, long now) {
        Counter counter = counters.get(key);
        if (counter != null && expired(counter, now)) {
            counters.remove(key);
            return null;
        }
        return counter;
    }

    private boolean expired(Counter counter, long now) {
        return counter.inFlight == 0 && now - counter.expiresAt >= 0;
    }

    private LoginThrottledException throttled(long nanos) {
        return new LoginThrottledException(1 + (nanos - 1) / TimeUnit.SECONDS.toNanos(1));
    }

    private record Key(boolean ip, String value) {
    }

    private static final class Counter {
        private int count;
        private int inFlight;
        private long expiresAt;

        private Counter(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
