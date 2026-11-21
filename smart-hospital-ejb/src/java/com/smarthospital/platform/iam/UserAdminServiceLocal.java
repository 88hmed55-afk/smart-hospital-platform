package com.smarthospital.platform.iam;

import java.util.List;
import java.util.Map;

/** Local view of administrative IAM operations. */
public interface UserAdminServiceLocal {

    Map<String, Object> listUsers(String query, int page, int size);

    Map<String, Object> getUser(String publicId);

    Map<String, Object> createUser(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateUser(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> resetPassword(String publicId, String newPassword, AuthPrincipal actor, String ip);

    Map<String, Object> setEnabled(String publicId, boolean enabled, AuthPrincipal actor, String ip);

    List<Map<String, Object>> listRoles();

    List<Map<String, Object>> listPermissions();

    Map<String, Object> updateRolePermissions(String rolePublicId, List<String> permissionCodes,
                                              AuthPrincipal actor, String ip);

    Map<String, Object> listSessions(int page, int size);

    void revokeSession(Long sessionId, AuthPrincipal actor, String ip);

    Map<String, Object> listLoginAttempts(int page, int size);
}
