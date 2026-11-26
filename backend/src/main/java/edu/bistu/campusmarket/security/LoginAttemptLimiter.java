package edu.bistu.campusmarket.security;

import edu.bistu.campusmarket.exception.BusinessException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
public class LoginAttemptLimiter {
    private final int limit;
    private final long windowNanos;
    private final int capacity;
    private final LongSupplier ticker;
    private final Map<Key, Attempts> entries = new HashMap<>();
    private long lastCleanup;

    @Autowired
    public LoginAttemptLimiter(@Value("${app.auth.login-limit.max-failures:5}") int limit,
            @Value("${app.auth.login-limit.window:PT5M}") Duration window,
            @Value("${app.auth.login-limit.max-entries:10000}") int capacity) {
        this(limit, window, capacity, System::nanoTime);
    }

    LoginAttemptLimiter(int limit, Duration window, int capacity, LongSupplier ticker) {
        if (limit < 1 || window.isZero() || window.isNegative() || capacity < 1) {
            throw new IllegalArgumentException("Login limit settings must be positive");
        }
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.capacity = capacity;
        this.ticker = ticker;
        this.lastCleanup = ticker.getAsLong();
    }

    public <T> T authenticate(String loginName, String remoteAddress, Supplier<T> action) {
        Key key = new Key(loginName, remoteAddress);
        Attempts attempts = acquire(key);
        boolean success = false;
        boolean invalidCredentials = false;
        try {
            T result = action.get();
            success = true;
            return result;
        } catch (BusinessException exception) {
            invalidCredentials = "INVALID_CREDENTIALS".equals(exception.code());
            throw exception;
        } finally {
            finish(key, attempts, success, invalidCredentials);
        }
    }

    private synchronized Attempts acquire(Key key) {
        long now = ticker.getAsLong();
        if (now - lastCleanup >= Duration.ofSeconds(1).toNanos()) {
            entries.values().removeIf(attempts -> attempts.inFlight == 0 && attempts.expired(now));
            lastCleanup = now;
        }

        Attempts attempts = entries.get(key);
        if (attempts == null) {
            if (entries.size() >= capacity) {
                throw limited();
            }
            attempts = new Attempts();
            entries.put(key, attempts);
        } else if (attempts.expired(now)) {
            attempts.failures = 0;
        }
        if (attempts.failures + attempts.inFlight >= limit) {
            throw limited();
        }
        attempts.inFlight++;
        return attempts;
    }

    private synchronized void finish(Key key, Attempts attempts, boolean success, boolean invalidCredentials) {
        attempts.inFlight--;
        long now = ticker.getAsLong();
        if (success || attempts.expired(now)) {
            attempts.failures = 0;
        }
        if (invalidCredentials) {
            if (attempts.failures == 0) {
                attempts.expiresAt = now + windowNanos;
            }
            attempts.failures++;
        }
        if (attempts.inFlight == 0 && attempts.failures == 0) {
            entries.remove(key);
        }
    }

    private BusinessException limited() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "登录尝试过多，请稍后再试");
    }

    private record Key(String loginName, String remoteAddress) {
    }

    private static final class Attempts {
        private int failures;
        private int inFlight;
        private long expiresAt;

        private boolean expired(long now) {
            return failures > 0 && now - expiresAt >= 0;
        }
    }
}
