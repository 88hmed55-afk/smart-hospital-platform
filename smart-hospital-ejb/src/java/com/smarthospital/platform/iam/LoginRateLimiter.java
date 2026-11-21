package com.smarthospital.platform.iam;

import com.smarthospital.shared.error.AppException;
import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Singleton;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login throttle for a single central server.
 *
 * <p>Account lockout is persisted in {@code users.locked_until}; this limiter
 * additionally protects the login endpoint from a single address hammering many
 * usernames. It is intentionally simple: one node, fixed window.
 */
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 30;
    private static final long WINDOW_SECONDS = 300;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_SECONDS) {
                return new Window(now);
            }
            current.count++;
            return current;
        });
        if (window.count > MAX_ATTEMPTS) {
            throw AppException.tooMany(
                    "RATE_LIMITED",
                    "تجاوزت الحد المسموح من محاولات الدخول. حاول بعد قليل.",
                    "Too many login attempts. Try again shortly.");
        }
    }

    public void reset(String ip) {
        if (ip != null) {
            windows.remove(ip);
        }
    }

    private static final class Window {
        private final long startedAt;
        private int count = 1;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
