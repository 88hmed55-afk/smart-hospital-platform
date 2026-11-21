package com.smarthospital.platform.iam;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authentication, session lifecycle and self-service password/MFA operations.
 *
 * <p>Passwords are PBKDF2-HMAC-SHA256 (ADR-004). Account lockout is persisted;
 * the login endpoint is additionally throttled per source address. Every
 * outcome that matters for accountability is written to the hash-chained audit
 * log in the same transaction.
 */
@Stateless
public class AuthService implements AuthServiceLocal {

    private static final String GENERIC_AR = "بيانات الدخول غير صحيحة.";
    private static final String GENERIC_EN = "Invalid username or password.";

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "123456789012", "qwerty123456",
            "admin123456", "welcome12345", "changeme1234", "letmein12345");

    @EJB
    private IamRepository repository;

    @EJB
    private SettingsRepository settings;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private LoginRateLimiter rateLimiter;

    @Override
    public LoginResult login(String username, String password, String ip, String userAgent) {
        String normalized = username == null ? "" : username.trim().toLowerCase();
        rateLimiter.check(ip);

        if (normalized.isEmpty() || password == null || password.isEmpty()) {
            return LoginResult.failure("INVALID_CREDENTIALS", GENERIC_AR, GENERIC_EN);
        }

        int lockoutMinutes = intSetting("security.lockout.minutes", 15);
        int threshold = intSetting("security.lockout.threshold", 5);
        LocalDateTime since = LocalDateTime.now().minusMinutes(lockoutMinutes);

        if (repository.countFailedAttemptsByIp(ip, since) >= 30) {
            throw AppException.tooMany(
                    "RATE_LIMITED",
                    "تجاوزت الحد المسموح من محاولات الدخول. حاول بعد قليل.",
                    "Too many login attempts. Try again shortly.");
        }

        User user = repository.findUserByUsername(normalized);
        if (user == null) {
            repository.recordLoginAttempt(normalized, null, ip, false, "UNKNOWN_USER");
            audit.record(null, "LOGIN_FAILED", "USER", null, null,
                    json("username", normalized), "UNKNOWN_USER", ip);
            return LoginResult.failure("INVALID_CREDENTIALS", GENERIC_AR, GENERIC_EN);
        }

        if (!"ACTIVE".equals(user.getStatus())) {
            repository.recordLoginAttempt(normalized, user.getId(), ip, false, "ACCOUNT_" + user.getStatus());
            audit.record(user.getId(), "LOGIN_FAILED", "USER", user.getPublicId(), null,
                    json("username", normalized, "status", user.getStatus()), "ACCOUNT_" + user.getStatus(), ip);
            return LoginResult.failure("ACCOUNT_DISABLED",
                    "الحساب غير مفعّل. راجع مسؤول النظام.",
                    "This account is not active. Contact the administrator.");
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            repository.recordLoginAttempt(normalized, user.getId(), ip, false, "LOCKED");
            audit.record(user.getId(), "LOGIN_FAILED", "USER", user.getPublicId(), null,
                    json("username", normalized), "LOCKED", ip);
            return LoginResult.failure("ACCOUNT_LOCKED",
                    "الحساب مقفل مؤقتًا بسبب محاولات فاشلة. حاول لاحقًا.",
                    "This account is temporarily locked after failed attempts.");
        }

        boolean passwordOk = PasswordHasher.verify(
                password, user.getPasswordSalt(), user.getPasswordIterations(), user.getPasswordHash());

        if (!passwordOk) {
            int failures = user.getFailedAttempts() + 1;
            user.setFailedAttempts(failures);
            String reason = "BAD_PASSWORD";
            if (failures >= threshold) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutMinutes));
                reason = "LOCKED";
            }
            repository.saveUser(user);
            repository.recordLoginAttempt(normalized, user.getId(), ip, false, reason);
            audit.record(user.getId(), "LOGIN_FAILED", "USER", user.getPublicId(), null,
                    json("username", normalized, "failedAttempts", failures), reason, ip);
            return LoginResult.failure("INVALID_CREDENTIALS", GENERIC_AR, GENERIC_EN);
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        if (PasswordHasher.needsRehash(user.getPasswordAlgorithm(), user.getPasswordIterations())) {
            byte[] salt = PasswordHasher.newSalt();
            String hash = PasswordHasher.hash(password, salt, PasswordHasher.DEFAULT_ITERATIONS);
            user.setPassword(hash, salt, PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);
        }
        repository.saveUser(user);
        rateLimiter.reset(ip);

        if (user.isMfaEnabled()) {
            repository.recordLoginAttempt(normalized, user.getId(), ip, true, "MFA_PENDING");
            audit.record(user.getId(), "LOGIN_MFA_PENDING", "USER", user.getPublicId(), null,
                    json("username", normalized), null, ip);
            AuthPrincipal principal = buildPrincipal(user, null);
            principal.setMfaVerified(false);
            return LoginResult.success(principal, null, true);
        }

        String token = createSession(user, ip, userAgent);
        AuthPrincipal principal = buildPrincipal(user, SecurityTokens.sha256Hex(token));
        audit.record(user.getId(), "LOGIN_SUCCESS", "USER", user.getPublicId(), null,
                json("username", normalized, "roles", principal.getRoles()), null, ip);
        return LoginResult.success(principal, token, false);
    }

    @Override
    public LoginResult completeMfaLogin(Long userId, String ip, String userAgent) {
        User user = repository.findUserById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        if (!user.isMfaEnabled()) {
            throw AppException.unprocessable("MFA_NOT_ENABLED",
                    "التحقق بخطوة ثانية غير مفعّل لهذا الحساب.",
                    "Second-factor authentication is not enabled for this account.");
        }
        String token = createSession(user, ip, userAgent);
        AuthPrincipal principal = buildPrincipal(user, SecurityTokens.sha256Hex(token));
        audit.record(user.getId(), "LOGIN_SUCCESS", "USER", user.getPublicId(), null,
                json("username", user.getUsername(), "mfa", true), null, ip);
        return LoginResult.success(principal, token, false);
    }

    @Override
    public AuthPrincipal loadPrincipalByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String hash = SecurityTokens.sha256Hex(rawToken);
        UserSession session = repository.findSessionByHash(hash);
        if (session == null || session.getEndedAt() != null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        int idleMinutes = intSetting("security.session.idle_minutes", 30);
        int absoluteHours = intSetting("security.session.absolute_hours", 12);
        if (session.getLastSeenAt().plusMinutes(idleMinutes).isBefore(now)
                || session.getStartedAt().plusHours(absoluteHours).isBefore(now)) {
            repository.endSession(session, "EXPIRED");
            return null;
        }

        User user = repository.findUserById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            repository.endSession(session, "REVOKED");
            return null;
        }

        session.touch();
        repository.saveSession(session);
        return buildPrincipal(user, hash);
    }

    @Override
    public UserProfile profileOf(AuthPrincipal principal) {
        return new UserProfile(
                principal.getPublicId(),
                principal.getUsername(),
                principal.getDisplayNameAr(),
                principal.getDisplayNameEn(),
                principal.getRoles(),
                principal.getPermissions(),
                principal.getScopes(),
                principal.isMustChangePassword(),
                principal.isMfaEnabled());
    }

    @Override
    public void logout(AuthPrincipal principal, String ip) {
        if (principal == null) {
            return;
        }
        if (principal.getSessionHash() != null) {
            UserSession session = repository.findSessionByHash(principal.getSessionHash());
            if (session != null) {
                repository.endSession(session, "LOGOUT");
            }
        }
        audit.record(principal.getUserId(), "LOGOUT", "USER", principal.getPublicId(), null, null, null, ip);
    }

    @Override
    public void changePassword(AuthPrincipal principal, String currentPassword, String newPassword, String ip) {
        if (principal == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        User user = repository.findUserById(principal.getUserId());
        if (user == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        if (!PasswordHasher.verify(currentPassword, user.getPasswordSalt(),
                user.getPasswordIterations(), user.getPasswordHash())) {
            throw AppException.unprocessable("INVALID_CURRENT_PASSWORD",
                    "كلمة المرور الحالية غير صحيحة.",
                    "The current password is incorrect.");
        }
        if (currentPassword != null && currentPassword.equals(newPassword)) {
            throw AppException.unprocessable("PASSWORD_REUSED",
                    "لا يمكن استخدام كلمة المرور الحالية مرة أخرى.",
                    "The new password must differ from the current password.");
        }
        validatePassword(user, newPassword);

        byte[] salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(newPassword, salt,
                PasswordHasher.DEFAULT_ITERATIONS);
        user.setPassword(hash, salt, PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);
        user.setMustChangePassword(false);
        repository.saveUser(user);
        repository.addPasswordHistory(user.getId(), hash, salt,
                PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);
        repository.endOtherSessions(user.getId(), principal.getSessionHash(), "REVOKED");

        principal.setMustChangePassword(false);
        audit.record(user.getId(), "PASSWORD_CHANGED", "USER", user.getPublicId(), null, null, null, ip);
    }

    @Override
    public boolean verifyMfa(AuthPrincipal principal, String code) {
        if (principal == null) {
            return false;
        }
        User user = repository.findUserById(principal.getUserId());
        if (user == null || !user.isMfaEnabled() || user.getMfaSecret() == null) {
            return false;
        }
        String secret = new String(user.getMfaSecret(), StandardCharsets.UTF_8);
        return TotpService.verify(secret, code, 1);
    }

    @Override
    public String beginMfaSetup(AuthPrincipal principal) {
        if (principal == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        User user = repository.findUserById(principal.getUserId());
        if (user == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        if (user.isMfaEnabled()) {
            throw AppException.conflict("MFA_ALREADY_ENABLED",
                    "التحقق بخطوة ثانية مفعّل بالفعل.",
                    "Second-factor authentication is already enabled.");
        }
        String secret = TotpService.generateSecret();
        user.setMfaSecret(secret.getBytes(StandardCharsets.UTF_8));
        repository.saveUser(user);
        audit.record(user.getId(), "MFA_SETUP_STARTED", "USER", user.getPublicId(), null, null, null, null);
        return secret;
    }

    @Override
    public void enableMfa(AuthPrincipal principal, String code, String ip) {
        if (principal == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        User user = repository.findUserById(principal.getUserId());
        if (user == null || user.getMfaSecret() == null) {
            throw AppException.unprocessable("MFA_NOT_STARTED",
                    "ابدأ إعداد التحقق بخطوة ثانية أولًا.",
                    "Start second-factor setup first.");
        }
        String secret = new String(user.getMfaSecret(), StandardCharsets.UTF_8);
        if (!TotpService.verify(secret, code, 1)) {
            throw AppException.unprocessable("INVALID_MFA_CODE",
                    "رمز التحقق غير صحيح.",
                    "The verification code is invalid.");
        }
        user.setMfaEnabled(true);
        repository.saveUser(user);
        principal.setMfaEnabled(true);
        audit.record(user.getId(), "MFA_ENABLED", "USER", user.getPublicId(), null, null, null, ip);
    }

    @Override
    public void disableMfa(AuthPrincipal principal, String password, String ip) {
        if (principal == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        User user = repository.findUserById(principal.getUserId());
        if (user == null) {
            throw AppException.unauthorized("AUTH_REQUIRED", "يلزم تسجيل الدخول.", "Authentication required.");
        }
        if (!PasswordHasher.verify(password, user.getPasswordSalt(),
                user.getPasswordIterations(), user.getPasswordHash())) {
            throw AppException.unprocessable("INVALID_CURRENT_PASSWORD",
                    "كلمة المرور الحالية غير صحيحة.",
                    "The current password is incorrect.");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        repository.saveUser(user);
        principal.setMfaEnabled(false);
        audit.record(user.getId(), "MFA_DISABLED", "USER", user.getPublicId(), null, null, null, ip);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String createSession(User user, String ip, String userAgent) {
        int maxConcurrent = intSetting("security.session.max_concurrent", 3);
        List<UserSession> open = repository.listOpenSessions(user.getId());
        while (open.size() >= maxConcurrent) {
            UserSession oldest = open.remove(0);
            repository.endSession(oldest, "REVOKED");
        }
        String token = SecurityTokens.newToken();
        repository.createSession(user.getId(), SecurityTokens.sha256Hex(token), ip, userAgent);
        return token;
    }

    private AuthPrincipal buildPrincipal(User user, String sessionHash) {
        AuthPrincipal principal = new AuthPrincipal();
        principal.setUserId(user.getId());
        principal.setPublicId(user.getPublicId());
        principal.setUsername(user.getUsername());
        principal.setDisplayNameAr(user.getDisplayNameAr());
        principal.setDisplayNameEn(user.getDisplayNameEn());
        principal.setSessionHash(sessionHash);
        principal.setRoles(new ArrayList<>(repository.findRoleCodesByUserId(user.getId())));
        principal.setPermissions(new ArrayList<>(repository.findPermissionCodesByUserId(user.getId())));
        List<ScopeView> scopes = new ArrayList<>();
        for (UserScope scope : repository.findScopesByUserId(user.getId())) {
            scopes.add(new ScopeView(scope.getScopeType(), scope.getScopeRefId()));
        }
        principal.setScopes(scopes);
        principal.setMustChangePassword(user.isMustChangePassword());
        principal.setMfaEnabled(user.isMfaEnabled());
        principal.setMfaVerified(true);
        return principal;
    }

    private void validatePassword(User user, String password) {
        int minLength = intSetting("security.password.min_length", 12);
        if (password == null || password.length() < minLength) {
            throw AppException.unprocessable("WEAK_PASSWORD",
                    "كلمة المرور يجب أن تكون " + minLength + " حرفًا على الأقل.",
                    "The password must be at least " + minLength + " characters.");
        }
        String lower = password.toLowerCase();
        if (lower.equals(user.getUsername().toLowerCase())
                || COMMON_PASSWORDS.contains(lower)) {
            throw AppException.unprocessable("WEAK_PASSWORD",
                    "كلمة المرور شائعة أو مطابقة لاسم المستخدم.",
                    "The password is too common or matches the username.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw AppException.unprocessable("WEAK_PASSWORD",
                    "كلمة المرور يجب أن تحتوي حروفًا وأرقامًا.",
                    "The password must contain letters and digits.");
        }
        int history = intSetting("security.password.history", 5);
        for (PasswordHashRecord record : repository.findPasswordHistory(user.getId(), history)) {
            if (PasswordHasher.verify(password, record.getSalt(), record.getIterations(), record.getHash())) {
                throw AppException.unprocessable("PASSWORD_REUSED",
                        "لا يمكن إعادة استخدام إحدى آخر " + history + " كلمات مرور.",
                        "One of the last " + history + " passwords cannot be reused.");
            }
        }
    }

    private int intSetting(String key, int defaultValue) {
        return settings.findValue(key)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    private static String json(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return JsonUtil.toJson(map);
    }
}
