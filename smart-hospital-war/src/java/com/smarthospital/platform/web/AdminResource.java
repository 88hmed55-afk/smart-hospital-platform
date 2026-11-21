package com.smarthospital.platform.web;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.platform.iam.BreakGlassServiceLocal;
import com.smarthospital.platform.iam.UserAdminServiceLocal;
import com.smarthospital.platform.settings.SettingsAdminServiceLocal;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** IAM, audit, sessions, Break-Glass and SYS settings administration. */
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class AdminResource {

    @Inject
    private UserAdminServiceLocal users;

    @Inject
    private BreakGlassServiceLocal breakGlass;

    @Inject
    private SettingsAdminServiceLocal settingsAdmin;

    @Inject
    private AuditServiceLocal audit;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    @GET
    @Path("/users")
    public Map<String, Object> listUsers(@QueryParam("q") String query,
                                         @QueryParam("page") @DefaultValue("0") int page,
                                         @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "IAM:READ");
        return users.listUsers(query, page, size);
    }

    @GET
    @Path("/users/{publicId}")
    public Map<String, Object> getUser(@PathParam("publicId") String publicId) {
        authz.require(principal(), "IAM:READ");
        return users.getUser(publicId);
    }

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createUser(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        return Response.status(Response.Status.CREATED)
                .entity(users.createUser(body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @PATCH
    @Path("/users/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateUser(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        return users.updateUser(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/users/{publicId}/reset-password")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> resetPassword(@PathParam("publicId") String publicId,
                                             ResetPasswordRequest body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:RESET");
        if (body == null || body.getNewPassword() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "كلمة المرور الجديدة مطلوبة.",
                    "The new password is required.");
        }
        return users.resetPassword(publicId, body.getNewPassword(), actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/users/{publicId}/disable")
    public Map<String, Object> disableUser(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        return users.setEnabled(publicId, false, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/users/{publicId}/enable")
    public Map<String, Object> enableUser(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        return users.setEnabled(publicId, true, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Roles and permissions
    // ------------------------------------------------------------------

    @GET
    @Path("/roles")
    public List<Map<String, Object>> listRoles() {
        authz.require(principal(), "IAM:READ");
        return users.listRoles();
    }

    @GET
    @Path("/permissions")
    public List<Map<String, Object>> listPermissions() {
        authz.require(principal(), "IAM:READ");
        return users.listPermissions();
    }

    @PUT
    @Path("/roles/{publicId}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateRolePermissions(@PathParam("publicId") String publicId,
                                                     RolePermissionsRequest body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        if (body == null || body.getPermissionCodes() == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قائمة الصلاحيات مطلوبة.",
                    "The permission list is required.");
        }
        return users.updateRolePermissions(publicId, body.getPermissionCodes(), actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Sessions and login attempts
    // ------------------------------------------------------------------

    @GET
    @Path("/sessions")
    public Map<String, Object> listSessions(@QueryParam("page") @DefaultValue("0") int page,
                                            @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "IAM:READ");
        return users.listSessions(page, size);
    }

    @DELETE
    @Path("/sessions/{id}")
    public Response revokeSession(@PathParam("id") Long id) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:WRITE");
        users.revokeSession(id, actor, WebCookies.clientIp(request));
        return Response.noContent().build();
    }

    @GET
    @Path("/login-attempts")
    public Map<String, Object> listLoginAttempts(@QueryParam("page") @DefaultValue("0") int page,
                                                 @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "AUDIT:READ");
        return users.listLoginAttempts(page, size);
    }

    // ------------------------------------------------------------------
    // Break-Glass
    // ------------------------------------------------------------------

    @POST
    @Path("/break-glass")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response requestBreakGlass(BreakGlassRequest body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:BREAK_GLASS");
        if (body == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "بيانات الطلب مطلوبة.",
                    "The request body is required.");
        }
        return Response.status(Response.Status.CREATED)
                .entity(breakGlass.request(actor, body.getPatientPublicId(), body.getReason(),
                        body.getMinutes(), WebCookies.clientIp(request)))
                .build();
    }

    @GET
    @Path("/break-glass")
    public List<Map<String, Object>> listBreakGlass(@QueryParam("status") String status) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "IAM:BREAK_GLASS", "AUDIT:READ", "IAM:WRITE");
        return breakGlass.list(actor, status);
    }

    @POST
    @Path("/break-glass/{publicId}/revoke")
    public Map<String, Object> revokeBreakGlass(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "IAM:BREAK_GLASS");
        return breakGlass.revoke(actor, publicId, WebCookies.clientIp(request));
    }

    @POST
    @Path("/break-glass/{publicId}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> reviewBreakGlass(@PathParam("publicId") String publicId,
                                                ReviewRequest body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "AUDIT:READ", "IAM:WRITE");
        return breakGlass.review(actor, publicId, body == null ? null : body.getNote(),
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------

    @GET
    @Path("/audit")
    public Map<String, Object> searchAudit(@QueryParam("action") String action,
                                           @QueryParam("entityType") String entityType,
                                           @QueryParam("entityPublicId") String entityPublicId,
                                           @QueryParam("from") String from,
                                           @QueryParam("to") String to,
                                           @QueryParam("page") @DefaultValue("0") int page,
                                           @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "AUDIT:READ");
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        LocalDateTime fromTime = parseTime(from);
        LocalDateTime toTime = parseTime(to);
        long total = audit.count(action, entityType, entityPublicId, fromTime, toTime);
        List<Map<String, Object>> items = audit.search(action, entityType, entityPublicId,
                fromTime, toTime, safePage * safeSize, safeSize);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("items", items);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", (int) Math.ceil((double) total / safeSize));
        return result;
    }

    @GET
    @Path("/audit/verify")
    public AuditServiceLocal.ChainVerification verifyAudit() {
        authz.require(principal(), "AUDIT:READ");
        return audit.verifyChain();
    }

    // ------------------------------------------------------------------
    // SYS settings
    // ------------------------------------------------------------------

    @GET
    @Path("/settings")
    public List<Map<String, Object>> listSettings() {
        authz.require(principal(), "SYS:CONFIG");
        return settingsAdmin.list();
    }

    @PUT
    @Path("/settings/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateSetting(@PathParam("key") String key,
                                             Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SYS:CONFIG");
        if (body == null || !body.containsKey("value")) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة الإعداد مطلوبة.",
                    "The setting value is required.");
        }
        Object raw = body.get("value");
        return settingsAdmin.update(key, raw == null ? null : String.valueOf(raw),
                actor.getUserId(), WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AuthPrincipal principal() {
        Object value = request.getAttribute(WebCookies.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthPrincipal principal) {
            return principal;
        }
        throw AppException.unauthorized("AUTH_REQUIRED",
                "يلزم تسجيل الدخول.",
                "Authentication required.");
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("INVALID_DATE",
                    "صيغة التاريخ غير صالحة. استخدم ISO-8601.",
                    "Invalid date format. Use ISO-8601.");
        }
    }

    // ------------------------------------------------------------------
    // Request bodies
    // ------------------------------------------------------------------

    public static class ResetPasswordRequest {
        private String newPassword;

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }

    public static class RolePermissionsRequest {
        private List<String> permissionCodes;

        public List<String> getPermissionCodes() {
            return permissionCodes;
        }

        public void setPermissionCodes(List<String> permissionCodes) {
            this.permissionCodes = permissionCodes;
        }
    }

    public static class BreakGlassRequest {
        private String patientPublicId;
        private String reason;
        private int minutes;

        public String getPatientPublicId() {
            return patientPublicId;
        }

        public void setPatientPublicId(String patientPublicId) {
            this.patientPublicId = patientPublicId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public int getMinutes() {
            return minutes;
        }

        public void setMinutes(int minutes) {
            this.minutes = minutes;
        }
    }

    public static class ReviewRequest {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
