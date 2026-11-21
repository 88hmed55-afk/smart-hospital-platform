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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Break-Glass emergency access (FR-IAM-009, ADR-009).
 *
 * <p>A grant is time-boxed to at most 60 minutes by the service and by a
 * database CHECK constraint. Every request, revocation and review is audited.
 * Phase 2 has no patient table yet, so the grant stores the patient public id
 * that will be used once the patient module lands.
 */
@Stateless
public class BreakGlassService implements BreakGlassServiceLocal {

    @EJB
    private IamRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private SettingsRepository settings;

    @Override
    public Map<String, Object> request(AuthPrincipal actor, String patientPublicId,
                                       String reason, int minutes, String ip) {
        if (patientPublicId == null || patientPublicId.trim().length() != 26) {
            throw AppException.unprocessable("INVALID_PATIENT_ID",
                    "معرّف المريض غير صالح.",
                    "Invalid patient identifier.");
        }
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.length() < 10) {
            throw AppException.unprocessable("REASON_TOO_SHORT",
                    "سبب الوصول الطارئ يجب أن يكون 10 أحرف على الأقل.",
                    "The emergency reason must be at least 10 characters.");
        }
        int maxMinutes = Math.min(60, intSetting("security.break_glass.max_minutes", 60));
        int duration = minutes <= 0 ? maxMinutes : Math.min(minutes, maxMinutes);
        if (duration < 1) {
            throw AppException.unprocessable("INVALID_DURATION",
                    "مدة الوصول الطارئ يجب أن تكون بين دقيقة و" + maxMinutes + " دقيقة.",
                    "The emergency duration must be between 1 and " + maxMinutes + " minutes.");
        }

        LocalDateTime grantedAt = LocalDateTime.now();
        LocalDateTime expiresAt = grantedAt.plusMinutes(duration);
        BreakGlassGrant grant = new BreakGlassGrant(
                Ulid.next(), actor.getUserId(), patientPublicId.trim(), safeReason, grantedAt, expiresAt);
        repository.saveBreakGlass(grant);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("patientPublicId", grant.getPatientPublicId());
        after.put("reason", safeReason);
        after.put("minutes", duration);
        after.put("expiresAt", expiresAt.toString());
        audit.record(actor.getUserId(), "BREAK_GLASS_GRANTED", "BREAK_GLASS", grant.getPublicId(),
                null, JsonUtil.toJson(after), safeReason, ip);
        return map(grant);
    }

    @Override
    public Map<String, Object> revoke(AuthPrincipal actor, String publicId, String ip) {
        BreakGlassGrant grant = requireGrant(publicId);
        boolean own = grant.getRequesterUserId().equals(actor.getUserId());
        if (!own && !actor.hasPermission("IAM:WRITE") && !actor.hasPermission("AUDIT:READ")) {
            throw AppException.forbidden("FORBIDDEN",
                    "لا تملك صلاحية إلغاء هذا التصريح.",
                    "You cannot revoke this grant.");
        }
        if (!"GRANTED".equals(grant.getStatus())) {
            throw AppException.unprocessable("INVALID_GRANT_STATE",
                    "لا يمكن إلغاء تصريح غير فعّال.",
                    "Only an active grant can be revoked.");
        }
        grant.revoke();
        repository.saveBreakGlass(grant);
        audit.record(actor.getUserId(), "BREAK_GLASS_REVOKED", "BREAK_GLASS", grant.getPublicId(),
                JsonUtil.toJson(Map.of("status", "GRANTED")),
                JsonUtil.toJson(Map.of("status", "REVOKED")), null, ip);
        return map(grant);
    }

    @Override
    public Map<String, Object> review(AuthPrincipal actor, String publicId, String note, String ip) {
        if (!actor.hasPermission("AUDIT:READ") && !actor.hasPermission("IAM:WRITE")) {
            throw AppException.forbidden("FORBIDDEN",
                    "مراجعة Break-Glass تتطلب صلاحية تدقيق أو إدارة.",
                    "Reviewing Break-Glass requires audit or administration permission.");
        }
        BreakGlassGrant grant = requireGrant(publicId);
        if ("REVIEWED".equals(grant.getStatus())) {
            throw AppException.conflict("ALREADY_REVIEWED",
                    "تمت مراجعة هذا التصريح بالفعل.",
                    "This grant has already been reviewed.");
        }
        grant.review(actor.getUserId(), note);
        repository.saveBreakGlass(grant);
        audit.record(actor.getUserId(), "BREAK_GLASS_REVIEWED", "BREAK_GLASS", grant.getPublicId(),
                null, JsonUtil.toJson(Map.of("status", "REVIEWED", "note", note == null ? "" : note)),
                note, ip);
        return map(grant);
    }

    @Override
    public List<Map<String, Object>> list(AuthPrincipal actor, String status) {
        repository.expireBreakGlass(LocalDateTime.now());
        boolean all = actor.hasPermission("AUDIT:READ") || actor.hasPermission("IAM:WRITE");
        List<Map<String, Object>> items = new ArrayList<>();
        for (BreakGlassGrant grant : repository.listBreakGlass(all, actor.getUserId(), status)) {
            items.add(map(grant));
        }
        return items;
    }

    private BreakGlassGrant requireGrant(String publicId) {
        BreakGlassGrant grant = repository.findBreakGlassByPublicId(publicId);
        if (grant == null) {
            throw AppException.notFound("GRANT_NOT_FOUND",
                    "تصريح الوصول غير موجود.",
                    "Break-Glass grant not found.");
        }
        return grant;
    }

    private Map<String, Object> map(BreakGlassGrant grant) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("publicId", grant.getPublicId());
        item.put("patientPublicId", grant.getPatientPublicId());
        item.put("requesterUserId", grant.getRequesterUserId());
        item.put("reason", grant.getReason());
        item.put("status", grant.getStatus());
        item.put("grantedAt", grant.getGrantedAt() == null ? null : grant.getGrantedAt().toString());
        item.put("expiresAt", grant.getExpiresAt() == null ? null : grant.getExpiresAt().toString());
        item.put("revokedAt", grant.getRevokedAt() == null ? null : grant.getRevokedAt().toString());
        item.put("reviewedAt", grant.getReviewedAt() == null ? null : grant.getReviewedAt().toString());
        item.put("reviewedBy", grant.getReviewedBy());
        item.put("reviewNote", grant.getReviewNote());
        return item;
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
}
