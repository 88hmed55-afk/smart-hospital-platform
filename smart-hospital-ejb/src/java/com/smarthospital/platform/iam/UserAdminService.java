package com.smarthospital.platform.iam;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** User, role, session and login-attempt administration (FR-IAM-001..005). */
@Stateless
public class UserAdminService implements UserAdminServiceLocal {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,80}$");

    @EJB
    private IamRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private SettingsRepository settings;

    @Override
    public Map<String, Object> listUsers(String query, int page, int size) {
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 0);
        long total = repository.countUsers(query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (User user : repository.listUsers(query, safePage * safeSize, safeSize)) {
            items.add(userMap(user));
        }
        return pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> getUser(String publicId) {
        return userMap(requireUser(publicId));
    }

    @Override
    public Map<String, Object> createUser(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String username = required(body, "username").trim().toLowerCase();
        if (!USERNAME.matcher(username).matches()) {
            throw AppException.unprocessable("INVALID_USERNAME",
                    "اسم المستخدم يجب أن يكون 3–80 حرفًا لاتينيًا/رقميًا.",
                    "Username must be 3–80 latin letters, digits, dots, dashes or underscores.");
        }
        if (repository.findUserByUsername(username) != null) {
            throw AppException.conflict("USERNAME_TAKEN",
                    "اسم المستخدم مستخدم بالفعل.",
                    "This username is already in use.");
        }
        String displayNameAr = required(body, "displayNameAr");
        String displayNameEn = optional(body, "displayNameEn");
        String password = required(body, "password");
        validatePassword(password);

        byte[] salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt, PasswordHasher.DEFAULT_ITERATIONS);
        User user = new User(Ulid.next(), username, hash, salt,
                PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS,
                displayNameAr, displayNameEn);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setMustChangePassword(true);
        user.setEmail(optional(body, "email"));
        user.setPhone(optional(body, "phone"));
        user.setBranchId(toLong(body.get("branchId")));
        user.setDepartmentId(toLong(body.get("departmentId")));
        repository.saveUser(user);
        repository.addPasswordHistory(user.getId(), hash, salt,
                PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);

        List<Role> roles = resolveRoles(body.get("roleCodes"));
        repository.replaceUserRoles(user.getId(), idsOf(roles), actor.getUserId());
        List<UserScope> scopes = parseScopes(user.getId(), body.get("scopes"));
        repository.replaceUserScopes(user.getId(), scopes);

        audit.record(actor.getUserId(), "USER_CREATED", "USER", user.getPublicId(),
                null, userMapJson(user), null, ip);
        return userMap(user);
    }

    @Override
    public Map<String, Object> updateUser(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        User user = requireUser(publicId);
        String before = userMapJson(user);
        if (body.containsKey("displayNameAr")) {
            user.setDisplayNameAr(required(body, "displayNameAr"));
        }
        if (body.containsKey("displayNameEn")) {
            user.setDisplayNameEn(optional(body, "displayNameEn"));
        }
        if (body.containsKey("email")) {
            user.setEmail(optional(body, "email"));
        }
        if (body.containsKey("phone")) {
            user.setPhone(optional(body, "phone"));
        }
        if (body.containsKey("branchId")) {
            user.setBranchId(toLong(body.get("branchId")));
        }
        if (body.containsKey("departmentId")) {
            user.setDepartmentId(toLong(body.get("departmentId")));
        }
        repository.saveUser(user);

        if (body.containsKey("roleCodes")) {
            List<Role> roles = resolveRoles(body.get("roleCodes"));
            repository.replaceUserRoles(user.getId(), idsOf(roles), actor.getUserId());
        }
        if (body.containsKey("scopes")) {
            repository.replaceUserScopes(user.getId(), parseScopes(user.getId(), body.get("scopes")));
        }
        audit.record(actor.getUserId(), "USER_UPDATED", "USER", user.getPublicId(),
                before, userMapJson(user), null, ip);
        return userMap(user);
    }

    @Override
    public Map<String, Object> resetPassword(String publicId, String newPassword,
                                             AuthPrincipal actor, String ip) {
        User user = requireUser(publicId);
        validatePassword(newPassword);
        byte[] salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(newPassword, salt, PasswordHasher.DEFAULT_ITERATIONS);
        user.setPassword(hash, salt, PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);
        user.setMustChangePassword(true);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        repository.saveUser(user);
        repository.addPasswordHistory(user.getId(), hash, salt,
                PasswordHasher.ALGORITHM, PasswordHasher.DEFAULT_ITERATIONS);
        repository.endOtherSessions(user.getId(), null, "ADMIN");
        audit.record(actor.getUserId(), "PASSWORD_RESET", "USER", user.getPublicId(),
                null, null, "administrator reset", ip);
        return userMap(user);
    }

    @Override
    public Map<String, Object> setEnabled(String publicId, boolean enabled,
                                          AuthPrincipal actor, String ip) {
        User user = requireUser(publicId);
        if (user.getId().equals(actor.getUserId()) && !enabled) {
            throw AppException.unprocessable("SELF_DISABLE",
                    "لا يمكنك تعطيل حسابك الحالي.",
                    "You cannot disable your own account.");
        }
        String before = userMapJson(user);
        user.setStatus(enabled ? "ACTIVE" : "DISABLED");
        if (!enabled) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
        }
        repository.saveUser(user);
        if (!enabled) {
            repository.endOtherSessions(user.getId(), null, "ADMIN");
        }
        audit.record(actor.getUserId(), enabled ? "USER_ENABLED" : "USER_DISABLED",
                "USER", user.getPublicId(), before, userMapJson(user), null, ip);
        return userMap(user);
    }

    @Override
    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Role role : repository.listRoles()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("publicId", role.getPublicId());
            item.put("code", role.getCode());
            item.put("nameAr", role.getNameAr());
            item.put("nameEn", role.getNameEn());
            item.put("systemRole", role.isSystemRole());
            List<String> permissions = repository.findPermissionCodesByRoleId(role.getId());
            item.put("permissions", permissions);
            item.put("permissionCount", permissions.size());
            items.add(item);
        }
        return items;
    }

    @Override
    public List<Map<String, Object>> listPermissions() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Permission permission : repository.listPermissions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", permission.getCode());
            item.put("domain", permission.getDomain());
            item.put("action", permission.getAction());
            item.put("descriptionAr", permission.getDescriptionAr());
            items.add(item);
        }
        return items;
    }

    @Override
    public Map<String, Object> updateRolePermissions(String rolePublicId, List<String> permissionCodes,
                                                     AuthPrincipal actor, String ip) {
        Role role = repository.findRoleByPublicId(rolePublicId);
        if (role == null) {
            throw AppException.notFound("ROLE_NOT_FOUND",
                    "الدور غير موجود.",
                    "Role not found.");
        }
        List<String> before = repository.findPermissionCodesByRoleId(role.getId());
        List<Long> ids = new ArrayList<>();
        for (String code : permissionCodes) {
            Permission permission = repository.listPermissions().stream()
                    .filter(p -> p.getCode().equals(code))
                    .findFirst()
                    .orElseThrow(() -> AppException.unprocessable("UNKNOWN_PERMISSION",
                            "صلاحية غير معروفة: " + code,
                            "Unknown permission: " + code));
            ids.add(permission.getId());
        }
        repository.replaceRolePermissions(role.getId(), ids, actor.getUserId());
        audit.record(actor.getUserId(), "ROLE_PERMISSIONS_UPDATED", "ROLE", role.getPublicId(),
                JsonUtil.toJson(Map.of("permissions", before)),
                JsonUtil.toJson(Map.of("permissions", permissionCodes)), null, ip);
        return Map.of("publicId", role.getPublicId(), "code", role.getCode(),
                "permissions", permissionCodes);
    }

    @Override
    public Map<String, Object> listSessions(int page, int size) {
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 0);
        List<UserSession> all = repository.listActiveSessions();
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserSession session : all.subList(from, to)) {
            User user = repository.findUserById(session.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.getId());
            item.put("userPublicId", user == null ? null : user.getPublicId());
            item.put("username", user == null ? null : user.getUsername());
            item.put("displayNameAr", user == null ? null : user.getDisplayNameAr());
            item.put("ip", session.getIp());
            item.put("userAgent", session.getUserAgent());
            item.put("startedAt", session.getStartedAt() == null ? null : session.getStartedAt().toString());
            item.put("lastSeenAt", session.getLastSeenAt() == null ? null : session.getLastSeenAt().toString());
            items.add(item);
        }
        return pageResult(items, safePage, safeSize, all.size());
    }

    @Override
    public void revokeSession(Long sessionId, AuthPrincipal actor, String ip) {
        UserSession session = repository.findSessionById(sessionId);
        if (session == null || session.getEndedAt() != null) {
            throw AppException.notFound("SESSION_NOT_FOUND",
                    "الجلسة غير موجودة أو منتهية.",
                    "Session not found or already ended.");
        }
        repository.endSession(session, "ADMIN");
        audit.record(actor.getUserId(), "SESSION_REVOKED", "SESSION", null,
                JsonUtil.toJson(Map.of("sessionId", sessionId, "userId", session.getUserId())),
                null, "administrator revoke", ip);
    }

    @Override
    public Map<String, Object> listLoginAttempts(int page, int size) {
        int safeSize = clampSize(size);
        int safePage = Math.max(page, 0);
        long total = repository.countLoginAttempts();
        List<Map<String, Object>> items = new ArrayList<>();
        for (LoginAttempt attempt : repository.listLoginAttempts(safePage * safeSize, safeSize)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("username", attempt.getUsernameAttempted());
            item.put("userId", attempt.getUserId());
            item.put("ip", attempt.getIp());
            item.put("success", attempt.isSuccess());
            item.put("reason", attempt.getReason());
            item.put("attemptedAt", attempt.getAttemptedAt() == null ? null : attempt.getAttemptedAt().toString());
            items.add(item);
        }
        return pageResult(items, safePage, safeSize, total);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private User requireUser(String publicId) {
        User user = repository.findUserByPublicId(publicId);
        if (user == null) {
            throw AppException.notFound("USER_NOT_FOUND", "المستخدم غير موجود.", "User not found.");
        }
        return user;
    }

    private List<Role> resolveRoles(Object raw) {
        if (!(raw instanceof Collection<?> codes) || codes.isEmpty()) {
            throw AppException.unprocessable("ROLE_REQUIRED",
                    "اختر دورًا واحدًا على الأقل.",
                    "At least one role is required.");
        }
        List<Role> roles = new ArrayList<>();
        for (Object value : codes) {
            String code = String.valueOf(value);
            Role role = repository.findRoleByCode(code);
            if (role == null) {
                throw AppException.unprocessable("UNKNOWN_ROLE",
                        "دور غير معروف: " + code,
                        "Unknown role: " + code);
            }
            roles.add(role);
        }
        return roles;
    }

    private static List<Long> idsOf(List<Role> roles) {
        List<Long> ids = new ArrayList<>();
        for (Role role : roles) {
            ids.add(role.getId());
        }
        return ids;
    }

    private List<UserScope> parseScopes(Long userId, Object raw) {
        List<UserScope> scopes = new ArrayList<>();
        if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                String text = String.valueOf(value).trim().toUpperCase();
                String[] parts = text.split(":", 2);
                String type = parts[0];
                long ref = parts.length > 1 ? parseLong(parts[1]) : 0;
                scopes.add(new UserScope(userId, type, ref));
            }
        }
        if (scopes.isEmpty()) {
            scopes.add(new UserScope(userId, "ALL", 0));
        }
        return scopes;
    }

    private void validatePassword(String password) {
        int minLength = settings.findValue("security.password.min_length")
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        return 12;
                    }
                })
                .orElse(12);
        if (password == null || password.length() < minLength) {
            throw AppException.unprocessable("WEAK_PASSWORD",
                    "كلمة المرور يجب أن تكون " + minLength + " حرفًا على الأقل.",
                    "The password must be at least " + minLength + " characters.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw AppException.unprocessable("WEAK_PASSWORD",
                    "كلمة المرور يجب أن تحتوي حروفًا وأرقامًا.",
                    "The password must contain letters and digits.");
        }
    }

    private Map<String, Object> userMap(User user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("publicId", user.getPublicId());
        item.put("username", user.getUsername());
        item.put("displayNameAr", user.getDisplayNameAr());
        item.put("displayNameEn", user.getDisplayNameEn());
        item.put("email", user.getEmail());
        item.put("phone", user.getPhone());
        item.put("branchId", user.getBranchId());
        item.put("departmentId", user.getDepartmentId());
        item.put("status", user.getStatus());
        item.put("mustChangePassword", user.isMustChangePassword());
        item.put("mfaEnabled", user.isMfaEnabled());
        item.put("failedAttempts", user.getFailedAttempts());
        item.put("lockedUntil", user.getLockedUntil() == null ? null : user.getLockedUntil().toString());
        item.put("lastLoginAt", user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString());
        item.put("createdAt", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        item.put("roles", repository.findRoleCodesByUserId(user.getId()));
        List<String> scopes = new ArrayList<>();
        for (UserScope scope : repository.findScopesByUserId(user.getId())) {
            scopes.add(scope.getScopeType() + (scope.getScopeRefId() > 0 ? ":" + scope.getScopeRefId() : ""));
        }
        item.put("scopes", scopes);
        return item;
    }

    private String userMapJson(User user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("username", user.getUsername());
        value.put("displayNameAr", user.getDisplayNameAr());
        value.put("displayNameEn", user.getDisplayNameEn());
        value.put("status", user.getStatus());
        value.put("roles", repository.findRoleCodesByUserId(user.getId()));
        return JsonUtil.toJson(value);
    }

    private static Map<String, Object> pageResult(List<Map<String, Object>> items,
                                                  int page, int size, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        result.put("totalPages", size == 0 ? 0 : (int) Math.ceil((double) total / size));
        return result;
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الحقل مطلوب: " + key,
                    "Required field: " + key);
        }
        return String.valueOf(value).trim();
    }

    private static String optional(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static Long toLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return parseLong(String.valueOf(value));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
