package com.smarthospital.clinical.adt;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.clinical.encounter.EncounterServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.notify.OutboxServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Local;
import jakarta.ejb.Stateless;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admissions and bed management (FR-ADT-001..009).
 *
 * <p>Guards: only an approved admission can take a bed, a bed can never have
 * two active assignments (DB unique key plus a row lock), and discharge always
 * ends the assignment and sends the bed to CLEANING.
 */
@Stateless
@Local(AdtServiceLocal.class)
public class AdtService implements AdtServiceLocal {

    private static final Set<String> DISCHARGE_TYPES =
            Set.of("HOME", "TRANSFER", "AGAINST_ADVICE", "DECEASED", "OTHER");
    private static final Set<String> ACTIVE_STATUSES =
            Set.of("REQUESTED", "APPROVED", "ADMITTED", "TRANSFERRED", "DISCHARGE_PENDING");

    @EJB
    private AdtRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @EJB
    private EncounterServiceLocal encounters;

    @EJB
    private OutboxServiceLocal outbox;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Reference data
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> wards() {
        return repository.wards();
    }

    // ------------------------------------------------------------------
    // Admission requests
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> search(String status, String wardPublicId, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.count(status, wardPublicId, query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.search(status, wardPublicId, query,
                safePage * safeSize, safeSize)) {
            items.add(admissionView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> get(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        accessLog.record(actor.getUserId(), Sql.longValue(row, "patientId", 0),
                "ADMISSION", publicId, "READ", ip);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admission", admissionView(row));
        result.put("bedHistory", repository.bedHistory(Sql.longValue(row, "id", 0)));
        result.put("hasSummary", repository.dischargeSummary(Sql.longValue(row, "id", 0)) != null);
        return result;
    }

    @Override
    public Map<String, Object> requestAdmission(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String patientPublicId = ClinicalSupport.required(body, "patientPublicId",
                "المريض مطلوب.", "Patient is required.");
        Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
        if (patient == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        if (!"ACTIVE".equals(Sql.text(patient, "status"))) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "لا يمكن تنويم مريض غير نشط.", "An inactive patient cannot be admitted.");
        }
        long patientId = Sql.longValue(patient, "id", 0);
        String encounterPublicId = ClinicalSupport.required(body, "encounterPublicId",
                "الزيارة مطلوبة.", "Encounter is required.");
        Map<String, Object> encounter = repository.encounterByPublicId(encounterPublicId);
        if (encounter == null) {
            throw AppException.notFound("ENCOUNTER_NOT_FOUND",
                    "الزيارة غير موجودة.", "Encounter not found.");
        }
        if (Sql.longValue(encounter, "patientId", 0) != patientId) {
            throw AppException.unprocessable("ENCOUNTER_PATIENT_MISMATCH",
                    "الزيارة لا تخص هذا المريض.", "The encounter belongs to a different patient.");
        }
        if (!"OPEN".equals(Sql.text(encounter, "status"))) {
            throw AppException.conflict("ENCOUNTER_CLOSED",
                    "لا يمكن طلب تنويم من زيارة مغلقة.",
                    "An admission cannot be requested from a closed encounter.");
        }
        Map<String, Object> active = repository.activeAdmissionForPatient(patientId);
        if (active != null) {
            throw AppException.conflict("ACTIVE_ADMISSION_EXISTS",
                    "يوجد تنويم نشط لهذا المريض.",
                    "This patient already has an active admission.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب التنويم مطلوب.", "An admission reason is required.");
        if (reason.length() > 1000) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "سبب التنويم أطول من الحد المسموح.",
                    "The admission reason is too long.");
        }
        Long admittingProviderId = null;
        String providerPublicId = ClinicalSupport.optional(body, "admittingProviderPublicId");
        if (providerPublicId != null) {
            Map<String, Object> provider = repository.userByPublicId(providerPublicId);
            if (provider == null) {
                throw AppException.notFound("PROVIDER_NOT_FOUND",
                        "الطبيب غير موجود.", "Provider not found.");
            }
            admittingProviderId = Sql.longValue(provider, "id", 0);
        }
        Long departmentId = null;
        String departmentPublicId = ClinicalSupport.optional(body, "departmentPublicId");
        if (departmentPublicId != null) {
            Map<String, Object> department = repository.departmentByPublicId(departmentPublicId);
            if (department == null) {
                throw AppException.notFound("DEPARTMENT_NOT_FOUND",
                        "القسم غير موجود.", "Department not found.");
            }
            departmentId = Sql.longValue(department, "id", 0);
        }
        Long wardId = null;
        String wardPublicId = ClinicalSupport.optional(body, "wardPublicId");
        if (wardPublicId != null) {
            Map<String, Object> ward = repository.wardByPublicId(wardPublicId);
            if (ward == null) {
                throw AppException.notFound("WARD_NOT_FOUND",
                        "الجناح غير موجود.", "Ward not found.");
            }
            wardId = Sql.longValue(ward, "id", 0);
        }

        String publicId = Ulid.next();
        long id = repository.insertAdmission(publicId, patientId, Sql.longValue(encounter, "id", 0),
                actor.getUserId(), reason, admittingProviderId, departmentId, wardId);
        audit.record(actor.getUserId(), "ADMISSION_REQUESTED", "ADMISSION", publicId, null,
                ClinicalSupport.json("patient", patientPublicId, "encounter", encounterPublicId,
                        "reason", reason), reason, ip);
        outbox.enqueue("ADMISSION_REQUESTED", "ADMISSION", publicId, ClinicalSupport.map(
                "notifications", List.of(ClinicalSupport.map(
                        "roles", List.of("HOSP_ADMIN", "DEPT_MANAGER"),
                        "type", "ADMISSION_REQUESTED",
                        "titleAr", "طلب تنويم جديد",
                        "titleEn", "New admission request",
                        "bodyAr", "المريض: " + Sql.text(patient, "fullName"),
                        "bodyEn", "Patient: " + Sql.text(patient, "fullName"),
                        "severity", "WARNING",
                        "entityType", "ADMISSION",
                        "entityPublicId", publicId,
                        "dedupeKey", "ADMISSION_REQUESTED:" + publicId))));
        return get(publicId, actor, ip);
    }

    @Override
    public Map<String, Object> approve(String publicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long version = requireVersion(row, body);
        if (repository.approve(Sql.longValue(row, "id", 0), version, actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن اعتماد طلب ليس في حالة الانتظار.",
                    "Only a requested admission can be approved.");
        }
        audit.record(actor.getUserId(), "ADMISSION_APPROVED", "ADMISSION", publicId,
                ClinicalSupport.json("status", "REQUESTED"),
                ClinicalSupport.json("status", "APPROVED"), null, ip);
        return get(publicId, actor, ip);
    }

    @Override
    public Map<String, Object> reject(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long version = requireVersion(row, body);
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الرفض مطلوب.", "A rejection reason is required.");
        if (repository.reject(Sql.longValue(row, "id", 0), version, actor.getUserId(), reason) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن رفض طلب ليس في حالة الانتظار.",
                    "Only a requested admission can be rejected.");
        }
        audit.record(actor.getUserId(), "ADMISSION_REJECTED", "ADMISSION", publicId,
                ClinicalSupport.json("status", "REQUESTED"),
                ClinicalSupport.json("status", "REJECTED"), reason, ip);
        return get(publicId, actor, ip);
    }

    // ------------------------------------------------------------------
    // Bed assignment
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> assignBed(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long admissionId = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        if (!"APPROVED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "يجب اعتماد طلب التنويم قبل تخصيص سرير.",
                    "The admission must be approved before a bed is assigned.");
        }
        String bedPublicId = ClinicalSupport.required(body, "bedPublicId",
                "السرير مطلوب.", "Bed is required.");
        Map<String, Object> bed = repository.bedByPublicId(bedPublicId);
        if (bed == null) {
            throw AppException.notFound("BED_NOT_FOUND", "السرير غير موجود.", "Bed not found.");
        }
        long bedId = Sql.longValue(bed, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);

        Map<String, Object> locked = repository.bedByIdForUpdate(bedId);
        String bedStatus = Sql.text(locked, "status");
        if (!Set.of("AVAILABLE", "RESERVED").contains(bedStatus)) {
            throw AppException.conflict("BED_NOT_AVAILABLE",
                    "السرير غير متاح للتخصيص.", "The bed is not available for assignment.");
        }
        if (repository.activeAssignmentForPatient(patientId) != null) {
            throw AppException.conflict("PATIENT_ALREADY_ASSIGNED",
                    "المريض لديه سرير فعّال بالفعل.", "The patient already has an active bed.");
        }
        String exceptionReason = ClinicalSupport.optional(body, "exceptionReason");
        requireGenderPolicy(locked, Sql.text(row, "patientGender"), exceptionReason);

        String assignmentPublicId = Ulid.next();
        try {
            repository.insertAssignment(assignmentPublicId, admissionId, bedId, patientId,
                    actor.getUserId());
        } catch (IllegalStateException duplicate) {
            throw AppException.conflict("BED_OCCUPIED",
                    "تم تخصيص السرير لمريض آخر. حدّث اللوحة.",
                    "The bed was assigned to another patient. Refresh the board.");
        }
        if (repository.updateBedStatus(bedId, Sql.longValue(locked, "version", 0),
                "OCCUPIED", actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تغيّرت حالة السرير. حدّث اللوحة.",
                    "The bed changed by another user. Refresh the board.");
        }
        if (repository.markAdmitted(admissionId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل طلب التنويم من مستخدم آخر. أعد التحميل.",
                    "The admission changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "BED_ASSIGNED", "ADMISSION", publicId,
                ClinicalSupport.json("status", "APPROVED"),
                ClinicalSupport.json("status", "ADMITTED", "bed", bedPublicId,
                        "assignment", assignmentPublicId, "exceptionReason", exceptionReason),
                exceptionReason, ip);
        outbox.enqueue("PATIENT_ADMITTED", "ADMISSION", publicId, ClinicalSupport.map(
                "notifications", List.of(ClinicalSupport.map(
                        "roles", List.of("NURSE", "TRIAGE_NURSE"),
                        "type", "PATIENT_ADMITTED",
                        "titleAr", "مريض جديد في الجناح",
                        "titleEn", "New patient on the ward",
                        "bodyAr", Sql.text(row, "patientName") + " — سرير " + Sql.text(bed, "code"),
                        "bodyEn", Sql.text(row, "patientName") + " — bed " + Sql.text(bed, "code"),
                        "severity", "INFO",
                        "entityType", "ADMISSION",
                        "entityPublicId", publicId,
                        "dedupeKey", "PATIENT_ADMITTED:" + publicId))));
        return get(publicId, actor, ip);
    }

    @Override
    public Map<String, Object> transfer(String publicId, Map<String, Object> body,
                                        AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long admissionId = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        if (!Set.of("ADMITTED", "TRANSFERRED").contains(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "النقل متاح للمرضى المنوّمين فقط.",
                    "Only admitted patients can be transferred.");
        }
        Map<String, Object> current = repository.activeAssignmentForAdmission(admissionId);
        if (current == null) {
            throw AppException.conflict("NO_ACTIVE_BED",
                    "لا يوجد سرير فعّال لهذا التنويم.", "The admission has no active bed.");
        }
        String toBedPublicId = ClinicalSupport.required(body, "toBedPublicId",
                "السرير الجديد مطلوب.", "The target bed is required.");
        String reason = ClinicalSupport.required(body, "reason",
                "سبب النقل مطلوب.", "A transfer reason is required.");
        if (toBedPublicId.equals(Sql.text(current, "bedPublicId"))) {
            throw AppException.unprocessable("SAME_BED",
                    "السرير الجديد مطابق للسرير الحالي.", "The target bed is the current bed.");
        }
        Map<String, Object> toBed = repository.bedByPublicId(toBedPublicId);
        if (toBed == null) {
            throw AppException.notFound("BED_NOT_FOUND", "السرير غير موجود.", "Bed not found.");
        }
        long toBedId = Sql.longValue(toBed, "id", 0);
        Map<String, Object> locked = repository.bedByIdForUpdate(toBedId);
        if (!Set.of("AVAILABLE", "RESERVED").contains(Sql.text(locked, "status"))) {
            throw AppException.conflict("BED_NOT_AVAILABLE",
                    "السرير الجديد غير متاح.", "The target bed is not available.");
        }
        requireGenderPolicy(locked, Sql.text(row, "patientGender"),
                ClinicalSupport.optional(body, "exceptionReason"));

        long fromBedId = Sql.longValue(current, "bedId", 0);
        if (repository.endAssignment(Sql.longValue(current, "id", 0), actor.getUserId(), "TRANSFER") == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تغيّر تخصيص السرير. حدّث اللوحة.",
                    "The bed assignment changed. Refresh the board.");
        }
        if (repository.updateBedStatus(fromBedId, Sql.longValue(current, "bedVersion", 0),
                "CLEANING", actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تغيّرت حالة السرير السابق. حدّث اللوحة.",
                    "The previous bed changed. Refresh the board.");
        }
        String assignmentPublicId = Ulid.next();
        try {
            repository.insertAssignment(assignmentPublicId, admissionId, toBedId,
                    Sql.longValue(row, "patientId", 0), actor.getUserId());
        } catch (IllegalStateException duplicate) {
            throw AppException.conflict("BED_OCCUPIED",
                    "تم تخصيص السرير لمريض آخر. حدّث اللوحة.",
                    "The bed was assigned to another patient. Refresh the board.");
        }
        if (repository.updateBedStatus(toBedId, Sql.longValue(locked, "version", 0),
                "OCCUPIED", actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تغيّرت حالة السرير. حدّث اللوحة.",
                    "The bed changed by another user. Refresh the board.");
        }
        repository.insertTransfer(Ulid.next(), admissionId, fromBedId, toBedId, reason, actor.getUserId());
        // SM-ADMISSION: ADMITTED -> TRANSFERRED -> ADMITTED, atomic in one transaction.
        if (repository.setStatus(admissionId, version, "TRANSFERRED", actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل التنويم من مستخدم آخر. أعد التحميل.",
                    "The admission changed by another user. Reload and retry.");
        }
        if (repository.setStatus(admissionId, version + 1, "ADMITTED", actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل التنويم من مستخدم آخر. أعد التحميل.",
                    "The admission changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "PATIENT_TRANSFERRED", "ADMISSION", publicId,
                ClinicalSupport.json("bed", Sql.text(current, "bedPublicId")),
                ClinicalSupport.json("bed", toBedPublicId, "reason", reason), reason, ip);
        return get(publicId, actor, ip);
    }

    // ------------------------------------------------------------------
    // Discharge
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> discharge(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long admissionId = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        String status = Sql.text(row, "status");
        if (!Set.of("ADMITTED", "TRANSFERRED", "DISCHARGE_PENDING").contains(status)) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن تسجيل خروج في الحالة الحالية.",
                    "Discharge is not allowed from the current state.");
        }
        String dischargeType = ClinicalSupport.required(body, "dischargeType",
                "نوع الخروج مطلوب.", "A discharge type is required.").toUpperCase();
        if (!DISCHARGE_TYPES.contains(dischargeType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الخروج غير صحيح.", "Invalid discharge type.");
        }
        if (requireBillingClearance() && !Sql.boolValue(row, "billingCleared")) {
            throw AppException.conflict("BILLING_NOT_CLEARED",
                    "لا يمكن الخروج قبل تسوية الفاتورة.",
                    "The patient cannot be discharged before the bill is cleared.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);

        Map<String, Object> assignment = repository.activeAssignmentForAdmission(admissionId);
        if (assignment != null) {
            repository.endAssignment(Sql.longValue(assignment, "id", 0), actor.getUserId(), "DISCHARGE");
            if (repository.updateBedStatus(Sql.longValue(assignment, "bedId", 0),
                    Sql.longValue(assignment, "bedVersion", 0), "CLEANING", actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّرت حالة السرير. حدّث اللوحة.",
                        "The bed changed by another user. Refresh the board.");
            }
        }
        if (repository.discharge(admissionId, version, actor.getUserId(), dischargeType, notes) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل التنويم من مستخدم آخر. أعد التحميل.",
                    "The admission changed by another user. Reload and retry.");
        }
        String encounterPublicId = Sql.text(row, "encounterPublicId");
        if (encounterPublicId != null) {
            Map<String, Object> encounter = repository.encounterByPublicId(encounterPublicId);
            if (encounter != null && "OPEN".equals(Sql.text(encounter, "status"))) {
                encounters.close(encounterPublicId,
                        ClinicalSupport.map("reason", notes == null ? "ADMISSION_DISCHARGE" : notes),
                        actor, ip);
            }
        }
        audit.record(actor.getUserId(), "ADMISSION_DISCHARGED", "ADMISSION", publicId,
                ClinicalSupport.json("status", status),
                ClinicalSupport.json("status", "DISCHARGED", "dischargeType", dischargeType),
                notes, ip);
        return get(publicId, actor, ip);
    }

    // ------------------------------------------------------------------
    // Discharge summary
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> dischargeSummary(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        long admissionId = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        long encounterId = Sql.longValue(row, "encounterId", 0);
        accessLog.record(actor.getUserId(), patientId, "ADMISSION_SUMMARY", publicId, "READ", ip);
        Map<String, Object> stored = repository.dischargeSummary(admissionId);
        List<Map<String, Object>> diagnoses = repository.encounterDiagnoses(encounterId);
        List<Map<String, Object>> medications = repository.encounterMedications(encounterId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admission", admissionView(row));
        result.put("stored", stored);
        result.put("diagnoses", diagnoses);
        result.put("medications", medications);
        result.put("instructions", repository.latestNoteText(encounterId));
        result.put("los", lengthOfStay(row));
        return result;
    }

    @Override
    public Map<String, Object> saveDischargeSummary(String publicId, Map<String, Object> body,
                                                    AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAdmission(publicId);
        String diagnoses = ClinicalSupport.required(body, "diagnosesText",
                "التشخيصات مطلوبة في الملخص.", "Diagnoses are required in the summary.");
        String instructions = ClinicalSupport.required(body, "instructionsText",
                "تعليمات الخروج مطلوبة.", "Discharge instructions are required.");
        String medications = ClinicalSupport.optionalTrim(body, "medicationsText", 2000);
        String followUp = ClinicalSupport.optionalTrim(body, "followUpText", 1000);
        repository.upsertDischargeSummary(Ulid.next(), Sql.longValue(row, "id", 0),
                diagnoses, medications, instructions, followUp, actor.getUserId());
        audit.record(actor.getUserId(), "DISCHARGE_SUMMARY_SAVED", "ADMISSION", publicId,
                null, ClinicalSupport.json("diagnoses", diagnoses, "followUp", followUp), null, ip);
        return dischargeSummary(publicId, actor, ip);
    }

    // ------------------------------------------------------------------
    // Bed board and housekeeping
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> bedBoard() {
        List<Map<String, Object>> rows = repository.bedsBoard();
        Map<String, Map<String, Object>> wards = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("AVAILABLE", "RESERVED", "OCCUPIED", "CLEANING", "MAINTENANCE")) {
            counts.put(status.toLowerCase(), 0);
        }
        for (Map<String, Object> row : rows) {
            String wardPublicId = Sql.text(row, "wardPublicId");
            Map<String, Object> ward = wards.computeIfAbsent(wardPublicId, key -> ClinicalSupport.map(
                    "publicId", wardPublicId,
                    "code", row.get("wardCode"),
                    "nameAr", row.get("wardAr"),
                    "nameEn", row.get("wardEn"),
                    "genderPolicy", row.get("genderPolicy"),
                    "beds", new ArrayList<Map<String, Object>>()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> beds = (List<Map<String, Object>>) ward.get("beds");
            beds.add(ClinicalSupport.map(
                    "publicId", row.get("publicId"),
                    "code", row.get("code"),
                    "bedType", row.get("bedType"),
                    "status", row.get("status"),
                    "version", row.get("version"),
                    "patientPublicId", row.get("patientPublicId"),
                    "patientName", row.get("patientName"),
                    "medicalNumber", row.get("medicalNumber"),
                    "admissionPublicId", row.get("admissionPublicId"),
                    "assignedAt", row.get("assignedAt"),
                    "notes", row.get("notes")));
            String key = Sql.text(row, "status").toLowerCase();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wards", new ArrayList<>(wards.values()));
        result.put("counts", counts);
        result.put("total", rows.size());
        return result;
    }

    @Override
    public Map<String, Object> bedStatus(String bedPublicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> bed = repository.bedByPublicId(bedPublicId);
        if (bed == null) {
            throw AppException.notFound("BED_NOT_FOUND", "السرير غير موجود.", "Bed not found.");
        }
        long version = requireVersion(bed, body);
        String target = ClinicalSupport.required(body, "status",
                "حالة السرير مطلوبة.", "The bed status is required.").toUpperCase();
        if (!Set.of("AVAILABLE", "CLEANING", "MAINTENANCE").contains(target)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "لا يمكن تعيين هذه الحالة مباشرة.", "This bed status cannot be set directly.");
        }
        String current = Sql.text(bed, "status");
        boolean allowed = switch (target) {
            case "AVAILABLE" -> Set.of("CLEANING", "MAINTENANCE", "RESERVED").contains(current);
            case "CLEANING" -> "OCCUPIED".equals(current);
            case "MAINTENANCE" -> Set.of("AVAILABLE", "CLEANING").contains(current);
            default -> false;
        };
        if (!allowed) {
            throw AppException.conflict("INVALID_STATE",
                    "انتقال حالة السرير غير مسموح.",
                    "The requested bed status transition is not allowed.");
        }
        if ("AVAILABLE".equals(target) && repository.activeAssignmentForBed(Sql.longValue(bed, "id", 0)) != null) {
            throw AppException.conflict("BED_OCCUPIED",
                    "لا يمكن تحرير سرير ما زال مخصصًا لمريض.",
                    "A bed with an active assignment cannot be released.");
        }
        if (repository.updateBedStatus(Sql.longValue(bed, "id", 0), version, target,
                actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تغيّرت حالة السرير. حدّث اللوحة.",
                    "The bed changed by another user. Refresh the board.");
        }
        String reason = ClinicalSupport.optional(body, "reason");
        audit.record(actor.getUserId(), "BED_STATUS_CHANGED", "BED", bedPublicId,
                ClinicalSupport.json("status", current),
                ClinicalSupport.json("status", target), reason, ip);
        return ClinicalSupport.map("publicId", bedPublicId, "status", target,
                "previousStatus", current);
    }

    @Override
    public Map<String, Object> wardPatients(String wardPublicId, AuthPrincipal actor, String ip) {
        Map<String, Object> ward = repository.wardByPublicId(wardPublicId);
        if (ward == null) {
            throw AppException.notFound("WARD_NOT_FOUND", "الجناح غير موجود.", "Ward not found.");
        }
        List<Map<String, Object>> patients = new ArrayList<>();
        for (Map<String, Object> row : repository.wardPatients(wardPublicId)) {
            patients.add(ClinicalSupport.map(
                    "admissionPublicId", row.get("admissionPublicId"),
                    "admittedAt", row.get("admittedAt"),
                    "status", row.get("admissionStatus"),
                    "patientPublicId", row.get("patientPublicId"),
                    "medicalNumber", row.get("medicalNumber"),
                    "patientName", row.get("patientName"),
                    "gender", row.get("gender"),
                    "birthDate", row.get("birthDate"),
                    "age", ClinicalSupport.age(parseDate(Sql.text(row, "birthDate"))),
                    "phone", row.get("phone"),
                    "encounterPublicId", row.get("encounterPublicId"),
                    "bedPublicId", row.get("bedPublicId"),
                    "bedCode", row.get("bedCode"),
                    "bedType", row.get("bedType"),
                    "assignedAt", row.get("assignedAt"),
                    "ewsScore", row.get("ewsScore"),
                    "ewsRisk", row.get("ewsRisk"),
                    "openTasks", row.get("openTasks"),
                    "latestVitals", row.get("vitalsAt") == null ? null : ClinicalSupport.map(
                            "temperatureC", row.get("temperatureC"),
                            "heartRate", row.get("heartRate"),
                            "respiratoryRate", row.get("respiratoryRate"),
                            "systolic", row.get("systolic"),
                            "diastolic", row.get("diastolic"),
                            "spo2", row.get("spo2"),
                            "painScore", row.get("painScore"),
                            "recordedAt", row.get("vitalsAt"))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ward", ClinicalSupport.map(
                "publicId", ward.get("publicId"),
                "code", ward.get("code"),
                "nameAr", ward.get("nameAr"),
                "nameEn", ward.get("nameEn"),
                "genderPolicy", ward.get("genderPolicy")));
        result.put("patients", patients);
        result.put("count", patients.size());
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requireAdmission(String publicId) {
        Map<String, Object> row = repository.admissionByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("ADMISSION_NOT_FOUND",
                    "التنويم غير موجود.", "Admission not found.");
        }
        return row;
    }

    private void requireGenderPolicy(Map<String, Object> bed, String patientGender, String exceptionReason) {
        String policy = Sql.text(bed, "wardGenderPolicy");
        if (policy == null || "MIXED".equals(policy)) {
            return;
        }
        if (policy.equals(patientGender)) {
            return;
        }
        if (exceptionReason == null || exceptionReason.isBlank()) {
            throw AppException.unprocessable("GENDER_POLICY_EXCEPTION_REQUIRED",
                    "الجناح مخصص لجنس آخر. يلزم سبب استثناء موثق.",
                    "The ward serves another gender. A documented exception reason is required.");
        }
    }

    private boolean requireBillingClearance() {
        return settings.findValue("adt.discharge.require_billing_clear")
                .map(value -> "1".equals(value) || "true".equalsIgnoreCase(value))
                .orElse(false);
    }

    private Map<String, Object> admissionView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("reason", row.get("reason"));
        view.put("requestedAt", row.get("requestedAt"));
        view.put("approvedAt", row.get("approvedAt"));
        view.put("rejectedAt", row.get("rejectedAt"));
        view.put("rejectionReason", row.get("rejectionReason"));
        view.put("admittedAt", row.get("admittedAt"));
        view.put("dischargedAt", row.get("dischargedAt"));
        view.put("dischargeType", row.get("dischargeType"));
        view.put("billingCleared", row.get("billingCleared"));
        view.put("version", row.get("version"));
        view.put("los", lengthOfStay(row));
        view.put("requestedBy", ClinicalSupport.map(
                "nameAr", row.get("requestedByAr"), "nameEn", row.get("requestedByEn")));
        view.put("approvedBy", ClinicalSupport.map(
                "nameAr", row.get("approvedByAr"), "nameEn", row.get("approvedByEn")));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate"))),
                "phone", row.get("patientPhone")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("requestedWard", row.get("wardPublicId") == null ? null : ClinicalSupport.map(
                "publicId", row.get("wardPublicId"),
                "nameAr", row.get("wardAr"),
                "nameEn", row.get("wardEn")));
        view.put("bed", row.get("bedPublicId") == null ? null : ClinicalSupport.map(
                "publicId", row.get("bedPublicId"),
                "code", row.get("bedCode"),
                "status", row.get("bedStatus"),
                "assignedAt", row.get("bedAssignedAt"),
                "wardPublicId", row.get("bedWardPublicId"),
                "wardAr", row.get("bedWardAr"),
                "wardEn", row.get("bedWardEn")));
        return view;
    }

    private Map<String, Object> lengthOfStay(Map<String, Object> row) {
        LocalDateTime admitted = parseDateTime(Sql.text(row, "admittedAt"));
        LocalDateTime discharged = parseDateTime(Sql.text(row, "dischargedAt"));
        if (admitted == null) {
            return ClinicalSupport.map("hours", 0, "days", 0, "active", false);
        }
        LocalDateTime end = discharged == null ? LocalDateTime.now() : discharged;
        long hours = Math.max(0, Duration.between(admitted, end).toHours());
        return ClinicalSupport.map("hours", hours, "days", hours / 24,
                "active", discharged == null);
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

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
