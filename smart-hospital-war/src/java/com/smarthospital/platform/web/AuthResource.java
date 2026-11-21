package com.smarthospital.platform.web;

import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthServiceLocal;
import com.smarthospital.platform.iam.LoginResult;
import com.smarthospital.platform.iam.ScopeView;
import com.smarthospital.platform.iam.TotpService;
import com.smarthospital.platform.iam.UserProfile;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authentication, session, CSRF and self-service MFA endpoints. */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class AuthResource {

    @Inject
    private AuthServiceLocal authService;

    @Context
    private HttpServletRequest request;

    @Context
    private HttpServletResponse response;

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest body) {
        if (body == null || body.getUsername() == null || body.getPassword() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "اسم المستخدم وكلمة المرور مطلوبان.",
                    "Username and password are required.");
        }
        LoginResult result = authService.login(
                body.getUsername(),
                body.getPassword(),
                WebCookies.clientIp(request),
                WebCookies.userAgent(request));

        if (!result.isSuccess()) {
            throw new AppException(statusFor(result.getFailureCode()), result.getFailureCode(),
                    result.getMessageAr(), result.getMessageEn());
        }

        HttpSession session = rotateSession();
        String csrf = rotateCsrf(session);

        if (result.isMfaRequired()) {
            session.setAttribute(WebCookies.PENDING_MFA_ATTRIBUTE, result.getPrincipal());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mfaRequired", true);
            payload.put("user", profileMap(result.getPrincipal()));
            payload.put("csrfToken", csrf);
            return Response.ok(payload).build();
        }

        session.setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, result.getPrincipal());
        WebCookies.setSessionCookie(response, result.getSessionToken());
        return Response.ok(sessionPayload(result.getPrincipal(), csrf, false)).build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        Object value = request.getAttribute(WebCookies.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthPrincipal principal) {
            authService.logout(principal, WebCookies.clientIp(request));
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        WebCookies.clearSessionCookie(response);
        WebCookies.clearCsrfCookie(response);
        return Response.noContent().build();
    }

    @GET
    @Path("/me")
    public Response me() {
        AuthPrincipal principal = currentPrincipal();
        HttpSession session = request.getSession(true);
        String csrf = ensureCsrf(session);
        return Response.ok(sessionPayload(principal, csrf, principal.isMfaEnabled() && !principal.isMfaVerified()))
                .build();
    }

    @GET
    @Path("/csrf")
    public Response csrf() {
        HttpSession session = request.getSession(true);
        String token = rotateCsrf(session);
        return Response.ok(Map.of("csrfToken", token)).build();
    }

    @POST
    @Path("/change-password")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(ChangePasswordRequest body) {
        if (body == null || body.getCurrentPassword() == null || body.getNewPassword() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "كلمة المرور الحالية والجديدة مطلوبتان.",
                    "Current and new passwords are required.");
        }
        AuthPrincipal principal = currentPrincipal();
        authService.changePassword(principal, body.getCurrentPassword(), body.getNewPassword(),
                WebCookies.clientIp(request));
        HttpSession session = request.getSession(true);
        session.setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, principal);
        return Response.ok(sessionPayload(principal, ensureCsrf(session), false)).build();
    }

    @POST
    @Path("/mfa/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response verifyMfa(MfaCodeRequest body) {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(WebCookies.PENDING_MFA_ATTRIBUTE);
        if (!(pending instanceof AuthPrincipal principal)) {
            throw AppException.unauthorized("AUTH_REQUIRED",
                    "لا توجد عملية دخول معلّقة.",
                    "No pending login was found.");
        }
        if (body == null || body.getCode() == null || body.getCode().isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "رمز التحقق مطلوب.",
                    "The verification code is required.");
        }
        if (!authService.verifyMfa(principal, body.getCode())) {
            throw AppException.unauthorized("INVALID_MFA_CODE",
                    "رمز التحقق غير صحيح.",
                    "The verification code is invalid.");
        }

        LoginResult result = authService.completeMfaLogin(
                principal.getUserId(), WebCookies.clientIp(request), WebCookies.userAgent(request));
        HttpSession fresh = rotateSession();
        fresh.setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, result.getPrincipal());
        String csrf = rotateCsrf(fresh);
        WebCookies.setSessionCookie(response, result.getSessionToken());
        return Response.ok(sessionPayload(result.getPrincipal(), csrf, false)).build();
    }

    @POST
    @Path("/mfa/setup")
    public Response setupMfa() {
        AuthPrincipal principal = currentPrincipal();
        String secret = authService.beginMfaSetup(principal);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("secret", secret);
        payload.put("otpauthUrl", TotpService.otpauthUrl("Smart Hospital", principal.getUsername(), secret));
        return Response.ok(payload).build();
    }

    @POST
    @Path("/mfa/enable")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response enableMfa(MfaCodeRequest body) {
        if (body == null || body.getCode() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "رمز التحقق مطلوب.",
                    "The verification code is required.");
        }
        AuthPrincipal principal = currentPrincipal();
        authService.enableMfa(principal, body.getCode(), WebCookies.clientIp(request));
        request.getSession(true).setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, principal);
        return Response.ok(sessionPayload(principal, ensureCsrf(request.getSession(true)), false)).build();
    }

    @POST
    @Path("/mfa/disable")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response disableMfa(MfaPasswordRequest body) {
        if (body == null || body.getPassword() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "كلمة المرور مطلوبة.",
                    "The password is required.");
        }
        AuthPrincipal principal = currentPrincipal();
        authService.disableMfa(principal, body.getPassword(), WebCookies.clientIp(request));
        request.getSession(true).setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, principal);
        return Response.ok(sessionPayload(principal, ensureCsrf(request.getSession(true)), false)).build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AuthPrincipal currentPrincipal() {
        Object value = request.getAttribute(WebCookies.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthPrincipal principal) {
            return principal;
        }
        throw AppException.unauthorized("AUTH_REQUIRED",
                "يلزم تسجيل الدخول.",
                "Authentication required.");
    }

    private HttpSession rotateSession() {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        return request.getSession(true);
    }

    private String rotateCsrf(HttpSession session) {
        String token = WebCookies.newToken();
        session.setAttribute(WebCookies.CSRF_ATTRIBUTE, token);
        WebCookies.setCsrfCookie(response, token);
        return token;
    }

    private String ensureCsrf(HttpSession session) {
        Object value = session.getAttribute(WebCookies.CSRF_ATTRIBUTE);
        if (value != null) {
            return value.toString();
        }
        return rotateCsrf(session);
    }

    private Map<String, Object> sessionPayload(AuthPrincipal principal, String csrf, boolean mfaRequired) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", profileMap(principal));
        payload.put("csrfToken", csrf);
        payload.put("mfaRequired", mfaRequired);
        return payload;
    }

    private Map<String, Object> profileMap(AuthPrincipal principal) {
        UserProfile profile = authService.profileOf(principal);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("publicId", profile.getPublicId());
        user.put("username", profile.getUsername());
        user.put("displayNameAr", profile.getDisplayNameAr());
        user.put("displayNameEn", profile.getDisplayNameEn());
        user.put("roles", profile.getRoles());
        user.put("permissions", profile.getPermissions());
        List<Map<String, Object>> scopes = new ArrayList<>();
        for (ScopeView scope : profile.getScopes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", scope.getType());
            item.put("refId", scope.getRefId());
            scopes.add(item);
        }
        user.put("scopes", scopes);
        user.put("mustChangePassword", profile.isMustChangePassword());
        user.put("mfaEnabled", profile.isMfaEnabled());
        return user;
    }

    private static int statusFor(String code) {
        return switch (code == null ? "" : code) {
            case "ACCOUNT_LOCKED" -> 423;
            case "ACCOUNT_DISABLED" -> 403;
            default -> 401;
        };
    }

    // ------------------------------------------------------------------
    // Request bodies
    // ------------------------------------------------------------------

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;

        public String getCurrentPassword() {
            return currentPassword;
        }

        public void setCurrentPassword(String currentPassword) {
            this.currentPassword = currentPassword;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }

    public static class MfaCodeRequest {
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    public static class MfaPasswordRequest {
        private String password;

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
