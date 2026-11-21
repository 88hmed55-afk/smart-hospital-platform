package com.smarthospital.clinical.er;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.clinical.adt.AdtServiceLocal;
import com.smarthospital.clinical.encounter.EncounterServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Local;
import jakarta.ejb.Stateless;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emergency department: quick arrival, configurable triage, live board and
 * disposition (FR-ER-001..006).
 *
 * <p>State machine (WF-18): ARRIVED -> TRIAGED -> IN_TREATMENT -> DISPOSED,
 * with LWBS and ENTERED_IN_ERROR as terminal side exits. Any other transition
 * is rejected with 409.
 */
@Stateless
@Local(ErServiceLocal.class)
public class ErService implements ErServiceLocal {

    private static final Set<String> ARRIVAL_MODES =
            Set.of("WALK_IN", "AMBULANCE", "POLICE", "TRANSFER", "OTHER");
    private static final Set<String> DISPOSITIONS =
            Set.of("DISCHARGE", "ADMIT", "TRANSFER_CLINIC", "TRANSFER_HOSPITAL", "LWBS", "ENTERED_IN_ERROR");

    @EJB
    private ErRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @EJB
    private EncounterServiceLocal encounters;

    @EJB
    private AdtServiceLocal admissions;

    // ------------------------------------------------------------------
    // Reference data
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> triageLevels() {
        return repository.triageLevels();
    }

    // ------------------------------------------------------------------
    // Arrival
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> createVisit(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String patientPublicId = ClinicalSupport.required(body, "patientPublicId",
                "المريض مطلوب.", "Patient is required.");
        Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
        if (patient == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        if (!"ACTIVE".equals(Sql.text(patient, "status"))) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "لا يمكن تسجيل وصول طارئ لمريض غير نشط.",
                    "An emergency visit cannot be opened for an inactive patient.");
        }
        long patientId = Sql.longValue(patient, "id", 0);
        Map<String, Object> existing = repository.activeVisitForPatient(patientId);
        if (existing != null) {
            throw AppException.conflict("ER_VISIT_EXISTS",
                    "يوجد وصول طوارئ نشط لهذا المريض.",
                    "This patient already has an active emergency visit.");
        }
        String arrivalMode = ClinicalSupport.optional(body, "arrivalMode");
        arrivalMode = arrivalMode == null ? "WALK_IN" : arrivalMode.toUpperCase();
        if (!ARRIVAL_MODES.contains(arrivalMode)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "طريقة الوصول غير صحيحة.", "Invalid arrival mode.");
        }
        String chiefComplaint = ClinicalSupport.optionalTrim(body, "chiefComplaint", 500);

        Long encounterId = null;
        String encounterPublicId = null;
        Map<String, Object> open = repository.openEncounterForPatient(patientId);
        if (open != null) {
            encounterId = Sql.longValue(open, "id", 0);
            encounterPublicId = Sql.text(open, "publicId");
        } else {
            Map<String, Object> department = repository.departmentByCode("ER");
            encounters.create(ClinicalSupport.map(
                    "patientPublicId", patientPublicId,
                    "type", "EMERGENCY",
                    "departmentPublicId", department == null ? null : department.get("publicId"),
                    "chiefComplaint", chiefComplaint), actor, ip);
            Map<String, Object> created = repository.openEncounterForPatient(patientId);
            if (created == null) {
                throw AppException.unprocessable("ENCOUNTER_NOT_CREATED",
                        "تعذر ربط زيارة الطوارئ بالسجل الطبي.",
                        "The emergency visit could not be linked to a medical record.");
            }
            encounterId = Sql.longValue(created, "id", 0);
            encounterPublicId = Sql.text(created, "publicId");
        }

        String publicId = Ulid.next();
        repository.insertVisit(publicId, patientId, encounterId, null, arrivalMode,
                chiefComplaint, actor.getUserId());
        audit.record(actor.getUserId(), "ER_VISIT_CREATED", "ER_VISIT", publicId, null,
                ClinicalSupport.json("patient", patientPublicId, "arrivalMode", arrivalMode,
                        "chiefComplaint", chiefComplaint, "encounter", encounterPublicId), null, ip);
        Map<String, Object> row = repository.visitByPublicId(publicId);
        Map<String, Object> result = visitView(row);
        result.put("encounterPublicId", encounterPublicId);
        return result;
    }

    // ------------------------------------------------------------------
    // Board
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> board() {
        List<Map<String, Object>> rows = repository.board("'ARRIVED', 'TRIAGED', 'IN_TREATMENT'");
        List<Map<String, Object>> waiting = new ArrayList<>();
        List<Map<String, Object>> inTreatment = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> view = visitView(row);
            if ("IN_TREATMENT".equals(Sql.text(row, "status"))) {
                inTreatment.add(view);
            } else {
                waiting.add(view);
            }
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> row : repository.recentDispositions(20)) {
            recent.add(visitView(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now().toString());
        result.put("waiting", waiting);
        result.put("inTreatment", inTreatment);
        result.put("recent", recent);
        result.put("counts", ClinicalSupport.map(
                "waiting", waiting.size(),
                "inTreatment", inTreatment.size(),
                "recent", recent.size(),
                "slaBreaches", waiting.stream().filter(row -> Boolean.TRUE.equals(row.get("slaBreach"))).count()));
        return result;
    }

    @Override
    public Map<String, Object> visit(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireVisit(publicId);
        accessLog.record(actor.getUserId(), Sql.longValue(row, "patientId", 0),
                "ER_VISIT", publicId, "READ", ip);
        Map<String, Object> result = visitView(row);
        result.put("history", repository.visitsForPatient(Sql.longValue(row, "patientId", 0)));
        return result;
    }

    // ------------------------------------------------------------------
    // Triage and assessment
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> triage(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireVisit(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        if (!"ARRIVED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن فرز زيارة ليست في حالة الوصول.",
                    "Only an arrived visit can be triaged.");
        }
        Map<String, Object> level = resolveTriageLevel(body);
        long levelId = Sql.longValue(level, "id", 0);
        String chiefComplaint = ClinicalSupport.optionalTrim(body, "chiefComplaint", 500);

        if (repository.triage(id, version, levelId, chiefComplaint, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                    "The visit changed by another user. Reload and retry.");
        }
        Object vitals = body.get("vitals");
        String encounterPublicId = Sql.text(row, "encounterPublicId");
        if (vitals instanceof Map<?, ?> map && encounterPublicId != null) {
            encounters.addVitals(encounterPublicId, cast(map), actor, ip);
        }
        long waitMinutes = minutesBetween(parseDateTime(Sql.text(row, "arrivalAt")), LocalDateTime.now());
        audit.record(actor.getUserId(), "ER_TRIAGED", "ER_VISIT", publicId,
                ClinicalSupport.json("status", "ARRIVED"),
                ClinicalSupport.json("status", "TRIAGED", "level", Sql.text(level, "code"),
                        "waitMinutes", waitMinutes), null, ip);
        return visitView(repository.visitByPublicId(publicId));
    }

    @Override
    public Map<String, Object> assess(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireVisit(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        if (repository.startTreatment(id, version, actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن بدء التقييم في الحالة الحالية.",
                    "Treatment cannot be started from the current state.");
        }
        audit.record(actor.getUserId(), "ER_TREATMENT_STARTED", "ER_VISIT", publicId,
                ClinicalSupport.json("status", Sql.text(row, "status")),
                ClinicalSupport.json("status", "IN_TREATMENT"), null, ip);
        return visitView(repository.visitByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Disposition
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> disposition(String publicId, Map<String, Object> body,
                                           AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireVisit(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        String disposition = ClinicalSupport.required(body, "disposition",
                "قرار الطوارئ مطلوب.", "A disposition is required.").toUpperCase();
        if (!DISPOSITIONS.contains(disposition)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قرار الطوارئ غير صحيح.", "Invalid disposition.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        String status = Sql.text(row, "status");
        String encounterPublicId = Sql.text(row, "encounterPublicId");

        if ("LWBS".equals(disposition)) {
            if (Sql.text(row, "firstAssessmentAt") != null) {
                throw AppException.conflict("LWBS_AFTER_ASSESSMENT",
                        "لا يمكن تسجيل التخلي بعد بدء التقييم الطبي.",
                        "Left-without-being-seen cannot be recorded after assessment started.");
            }
            String reason = ClinicalSupport.required(body, "reason",
                    "سبب التخلي مطلوب.", "A reason is required for LWBS.");
            if (repository.markLwbs(id, version, reason, actor.getUserId()) == 0) {
                throw AppException.conflict("INVALID_STATE",
                        "لا يمكن تسجيل التخلي في الحالة الحالية.",
                        "LWBS cannot be recorded from the current state.");
            }
            if (encounterPublicId != null) {
                encounters.cancel(encounterPublicId,
                        ClinicalSupport.map("reason", reason), actor, ip);
            }
            audit.record(actor.getUserId(), "ER_LWBS", "ER_VISIT", publicId,
                    ClinicalSupport.json("status", status),
                    ClinicalSupport.json("status", "LWBS", "reason", reason), reason, ip);
            return visitView(repository.visitByPublicId(publicId));
        }

        if ("ENTERED_IN_ERROR".equals(disposition)) {
            String reason = ClinicalSupport.required(body, "reason",
                    "سبب الإلغاء مطلوب.", "A reason is required.");
            if (repository.markEnteredInError(id, version, reason, actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                        "The visit changed by another user. Reload and retry.");
            }
            if (encounterPublicId != null) {
                encounters.cancel(encounterPublicId,
                        ClinicalSupport.map("reason", reason), actor, ip);
            }
            audit.record(actor.getUserId(), "ER_ENTERED_IN_ERROR", "ER_VISIT", publicId,
                    ClinicalSupport.json("status", status),
                    ClinicalSupport.json("status", "ENTERED_IN_ERROR"), reason, ip);
            return visitView(repository.visitByPublicId(publicId));
        }

        if (!Set.of("ARRIVED", "TRIAGED", "IN_TREATMENT").contains(status)) {
            throw AppException.conflict("INVALID_STATE",
                    "الزيارة مغلقة ولا تقبل قرارًا جديدًا.",
                    "The visit is already closed and cannot receive a new disposition.");
        }

        if ("ADMIT".equals(disposition)) {
            String reason = ClinicalSupport.required(body, "reason",
                    "سبب التنويم مطلوب.", "An admission reason is required.");
            admissions.requestAdmission(ClinicalSupport.map(
                    "patientPublicId", Sql.text(row, "patientPublicId"),
                    "encounterPublicId", encounterPublicId,
                    "reason", reason,
                    "wardPublicId", ClinicalSupport.optional(body, "wardPublicId"),
                    "departmentPublicId", ClinicalSupport.optional(body, "departmentPublicId"),
                    "admittingProviderPublicId", ClinicalSupport.optional(body, "admittingProviderPublicId")),
                    actor, ip);
        }

        if (repository.dispose(id, version, disposition, notes, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                    "The visit changed by another user. Reload and retry.");
        }

        if (encounterPublicId != null && !"ADMIT".equals(disposition)) {
            String reason = notes == null ? disposition : notes;
            if ("DISCHARGE".equals(disposition) || "TRANSFER_CLINIC".equals(disposition)
                    || "TRANSFER_HOSPITAL".equals(disposition)) {
                encounters.close(encounterPublicId, ClinicalSupport.map("reason", reason), actor, ip);
            }
        }

        audit.record(actor.getUserId(), "ER_DISPOSED", "ER_VISIT", publicId,
                ClinicalSupport.json("status", status),
                ClinicalSupport.json("status", "DISPOSED", "disposition", disposition,
                        "notes", notes), notes, ip);
        return visitView(repository.visitByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requireVisit(String publicId) {
        Map<String, Object> row = repository.visitByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("ER_VISIT_NOT_FOUND",
                    "زيارة الطوارئ غير موجودة.", "Emergency visit not found.");
        }
        return row;
    }

    private Map<String, Object> resolveTriageLevel(Map<String, Object> body) {
        String code = ClinicalSupport.optional(body, "triageLevelCode");
        if (code != null) {
            Map<String, Object> level = repository.triageLevelByCode(code.toUpperCase());
            if (level == null) {
                throw AppException.notFound("TRIAGE_LEVEL_NOT_FOUND",
                        "مستوى الفرز غير موجود.", "Triage level not found.");
            }
            return level;
        }
        Long id = ClinicalSupport.optionalLong(body, "triageLevelId");
        if (id == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مستوى الفرز مطلوب.", "A triage level is required.");
        }
        Map<String, Object> level = repository.triageLevelById(id);
        if (level == null) {
            throw AppException.notFound("TRIAGE_LEVEL_NOT_FOUND",
                    "مستوى الفرز غير موجود.", "Triage level not found.");
        }
        return level;
    }

    private Map<String, Object> visitView(Map<String, Object> row) {
        LocalDateTime arrival = parseDateTime(Sql.text(row, "arrivalAt"));
        LocalDateTime triaged = parseDateTime(Sql.text(row, "triageAt"));
        LocalDateTime assessed = parseDateTime(Sql.text(row, "firstAssessmentAt"));
        LocalDateTime now = LocalDateTime.now();
        long waitMinutes = minutesBetween(arrival, triaged != null ? triaged : now);
        Long assessmentMinutes = assessed == null ? null : minutesBetween(arrival, assessed);
        int maxWaitValue = Sql.intValue(row, "triageMaxWait", -1);
        Integer maxWait = maxWaitValue < 0 ? null : maxWaitValue;
        boolean waiting = Set.of("ARRIVED", "TRIAGED").contains(Sql.text(row, "status"));
        boolean slaBreach = waiting && maxWait != null && waitMinutes > maxWait;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("arrivalMode", row.get("arrivalMode"));
        view.put("chiefComplaint", row.get("chiefComplaint"));
        view.put("arrivalAt", row.get("arrivalAt"));
        view.put("triageAt", row.get("triageAt"));
        view.put("firstAssessmentAt", row.get("firstAssessmentAt"));
        view.put("disposition", row.get("disposition"));
        view.put("dispositionAt", row.get("dispositionAt"));
        view.put("dispositionNotes", row.get("dispositionNotes"));
        view.put("lwbsReason", row.get("lwbsReason"));
        view.put("enteredInErrorReason", row.get("enteredInErrorReason"));
        view.put("waitMinutes", waitMinutes);
        view.put("assessmentMinutes", assessmentMinutes);
        view.put("slaBreach", slaBreach);
        view.put("maxWaitMinutes", maxWait);
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate"))),
                "phone", row.get("patientPhone")));
        view.put("triageLevel", row.get("triageCode") == null ? null : ClinicalSupport.map(
                "code", row.get("triageCode"),
                "nameAr", row.get("triageNameAr"),
                "nameEn", row.get("triageNameEn"),
                "color", row.get("triageColor"),
                "rank", row.get("triageRank")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("latestVitals", row.get("vitalsAt") == null ? null : ClinicalSupport.map(
                "temperatureC", row.get("temperatureC"),
                "heartRate", row.get("heartRate"),
                "respiratoryRate", row.get("respiratoryRate"),
                "systolic", row.get("systolic"),
                "diastolic", row.get("diastolic"),
                "spo2", row.get("spo2"),
                "painScore", row.get("painScore"),
                "recordedAt", row.get("vitalsAt")));
        return view;
    }

    private long requireVersion(Map<String, Object> row, Map<String, Object> body) {
        long version = Sql.longValue(row, "version", 0);
        Long requested = ClinicalSupport.optionalLong(body, "version");
        if (requested != null && requested != version) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل السجل من مستخدم آخر. أعد التحميل.",
                    "The record changed by another user. Reload and retry.");
        }
        return version;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).toMinutes());
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
