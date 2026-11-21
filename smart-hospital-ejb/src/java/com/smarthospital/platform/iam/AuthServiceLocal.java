package com.smarthospital.platform.iam;

/** Local view of authentication and self-service account operations. */
public interface AuthServiceLocal {

    LoginResult login(String username, String password, String ip, String userAgent);

    LoginResult completeMfaLogin(Long userId, String ip, String userAgent);

    AuthPrincipal loadPrincipalByToken(String rawToken);

    UserProfile profileOf(AuthPrincipal principal);

    void logout(AuthPrincipal principal, String ip);

    void changePassword(AuthPrincipal principal, String currentPassword, String newPassword, String ip);

    boolean verifyMfa(AuthPrincipal principal, String code);

    String beginMfaSetup(AuthPrincipal principal);

    void enableMfa(AuthPrincipal principal, String code, String ip);

    void disableMfa(AuthPrincipal principal, String password, String ip);
}
