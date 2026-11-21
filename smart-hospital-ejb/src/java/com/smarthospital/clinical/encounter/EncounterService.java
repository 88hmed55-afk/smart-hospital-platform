package com.smarthospital.clinical.encounter;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.json.JsonUtil;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.math.BigDecimal;
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
 * Encounter workspace and electronic medical record (FR-EMR-001..012).
 *
 * <p>Clinical notes are append-only in practice: a draft may be edited, but a
 * signed note can never be changed. Amendments create a new immutable version
 * linked to the previous one, with an explicit reason and a separate audit
 * entry (BR-EMR-004, ADR-008).
 */
@Stateless
public class EncounterService implements EncounterServiceLocal {

    private static final Set<String> TYPES = Set.of("OUTPATIENT", "EMERGENCY", "INPATIENT");
    private static final Set<String> STATUSES = Set.of("OPEN", "CLOSED", "CANCELLED");
    private static final Set<String> NOTE_TYPES = Set.of(
            "PROGRESS", "ADMISSION", "DISCHARGE", "NURSING", "CONSULTATION");
    private static final Set<String> ORDER_TYPES = Set.of("LAB", "RAD", "PROCEDURE");
    private static final Set<String> PRIORITIES = Set.of("ROUTINE", "URGENT", "STAT");
    private static final Set<String> ORDER_STATUSES = Set.of(
            "ORDERED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    @EJB
    private EncounterRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Search and workspace
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> search(String q, String status, String providerPublicId,
                                      String patientPublicId, String date, int page, int size) {
        int safeSize = ClinicalSupport.clampSize(size);
        int safePage = Math.max(page, 0);
        String safeStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        if (safeStatus != null && !STATUSES.contains(safeStatus)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة الزيارة غير صحيحة.", "Invalid encounter status.");
        }
        Long providerId = null;
        if (providerPublicId != null && !providerPublicId.isBlank()) {
            Map<String, Object> provider = repository.userIdByPublicId(providerPublicId);
            providerId = provider == null ? -1L : Sql.longValue(provider, "id");
        }
        Long patientId = null;
        if (patientPublicId != null && !patientPublicId.isBlank()) {
            Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
            patientId = patient == null ? -1L : Sql.longValue(patient, "id");
        }
        LocalDate day = parseDate(date);
        long total = repository.count(q, safeStatus, providerId, patientId, day);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.search(q, safeStatus, providerId, patientId, day,
                safePage * safeSize, safeSize)) {
            items.add(summary(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> get(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "ENCOUNTER", publicId, "READ", ip);
        return workspace(row);
    }

    private Map<String, Object> workspace(Map<String, Object> row) {
        long id = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        String status = Sql.text(row, "status");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounter", summary(row));
        result.put("editable", "OPEN".equals(status));
        result.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate"))),
                "phone", row.get("patientPhone"),
                "bloodGroup", row.get("patientBloodGroup")));
        result.put("vitals", repository.vitals(id));
        result.put("latestVitals", repository.latestVitals(id));
        result.put("diagnoses", repository.diagnoses(id));
        result.put("notes", repository.notes(id));
        result.put("orders", repository.orders(id));
        result.put("prescriptions", repository.prescriptions(id));
        result.put("charges", repository.charges(id));
        result.put("allergies", repository.activeAllergies(patientId));
        result.put("conditions", repository.conditions(patientId));
        result.put("history", repository.recentEncounters(patientId, Sql.text(row, "publicId"), 5));
        return result;
    }

    // ------------------------------------------------------------------
    // Encounter lifecycle
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> create(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> patient = repository.patientByPublicId(
                ClinicalSupport.required(body, "patientPublicId",
                        "المريض مطلوب.", "Patient is required."));
        if (patient == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        if (!"ACTIVE".equals(Sql.text(patient, "status"))) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "لا يمكن فتح زيارة لمريض غير نشط.", "An encounter cannot be opened for an inactive patient.");
        }
        long patientId = Sql.longValue(patient, "id", 0);
        Map<String, Object> existing = repository.openEncounterForPatient(patientId);
        if (existing != null) {
            throw AppException.conflict("OPEN_ENCOUNTER_EXISTS",
                    "يوجد زيارة مفتوحة لهذا المريض. أكملها أو ألغِها أولًا.",
                    "This patient already has an open encounter. Close or cancel it first.");
        }

        Long appointmentId = null;
        Long providerId = actor.getUserId();
        Long departmentId = null;
        String appointmentPublicId = ClinicalSupport.optional(body, "appointmentPublicId");
        if (appointmentPublicId != null) {
            Map<String, Object> appointment = repository.appointmentByPublicId(appointmentPublicId);
            if (appointment == null) {
                throw AppException.notFound("APPOINTMENT_NOT_FOUND",
                        "الموعد غير موجود.", "Appointment not found.");
            }
            if (Sql.longValue(appointment, "patientId", 0) != patientId) {
                throw AppException.unprocessable("APPOINTMENT_PATIENT_MISMATCH",
                        "الموعد لا يخص هذا المريض.", "The appointment belongs to a different patient.");
            }
            if (!Set.of("BOOKED", "CHECKED_IN").contains(Sql.text(appointment, "status"))) {
                throw AppException.conflict("APPOINTMENT_NOT_OPEN",
                        "لا يمكن فتح زيارة من موعد مكتمل أو ملغى.",
                        "An encounter cannot be opened from a completed or cancelled appointment.");
            }
            Map<String, Object> existingForAppointment =
                    repository.encounterForAppointment(Sql.longValue(appointment, "id", 0));
            if (existingForAppointment != null) {
                throw AppException.conflict("ENCOUNTER_EXISTS",
                        "توجد زيارة مرتبطة بهذا الموعد.",
                        "An encounter already exists for this appointment.");
            }
            appointmentId = Sql.longValue(appointment, "id", 0);
            providerId = Sql.longValue(appointment, "providerId", 0);
        } else {
            String providerPublicId = ClinicalSupport.optional(body, "providerPublicId");
            if (providerPublicId != null) {
                Map<String, Object> provider = repository.userIdByPublicId(providerPublicId);
                if (provider == null) {
                    throw AppException.notFound("PROVIDER_NOT_FOUND",
                            "الطبيب غير موجود.", "Provider not found.");
                }
                providerId = Sql.longValue(provider, "id", 0);
            }
        }
        String departmentPublicId = ClinicalSupport.optional(body, "departmentPublicId");
        if (departmentPublicId != null) {
            Map<String, Object> department = repository.departmentByPublicId(departmentPublicId);
            if (department == null) {
                throw AppException.notFound("DEPARTMENT_NOT_FOUND",
                        "القسم غير موجود.", "Department not found.");
            }
            departmentId = Sql.longValue(department, "id", 0);
        }
        String type = ClinicalSupport.optional(body, "type");
        type = type == null ? "OUTPATIENT" : type.toUpperCase();
        if (!TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الزيارة غير صحيح.", "Invalid encounter type.");
        }
        String chiefComplaint = ClinicalSupport.optionalTrim(body, "chiefComplaint", 500);

        String publicId = Ulid.next();
        long id = repository.insertEncounter(publicId, patientId, appointmentId, providerId,
                departmentId, type, chiefComplaint, actor.getUserId());
        if (appointmentId != null) {
            repository.startAppointment(appointmentId, actor.getUserId());
        }
        audit.record(actor.getUserId(), "ENCOUNTER_OPENED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("patient", patient.get("publicId"), "type", type,
                        "appointment", appointmentPublicId, "chiefComplaint", chiefComplaint),
                null, ip);
        return workspace(repository.encounterByPublicId(publicId));
    }

    @Override
    public Map<String, Object> update(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        String chiefComplaint = body.containsKey("chiefComplaint")
                ? ClinicalSupport.optionalTrim(body, "chiefComplaint", 500)
                : Sql.text(row, "chiefComplaint");
        String type = body.containsKey("type")
                ? ClinicalSupport.required(body, "type", "نوع الزيارة مطلوب.",
                "Encounter type is required.").toUpperCase()
                : Sql.text(row, "type");
        if (!TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الزيارة غير صحيح.", "Invalid encounter type.");
        }
        Long departmentId = Sql.longValue(row, "departmentId");
        if (body.containsKey("departmentPublicId")) {
            String departmentPublicId = ClinicalSupport.optional(body, "departmentPublicId");
            departmentId = null;
            if (departmentPublicId != null) {
                Map<String, Object> department = repository.departmentByPublicId(departmentPublicId);
                if (department == null) {
                    throw AppException.notFound("DEPARTMENT_NOT_FOUND",
                            "القسم غير موجود.", "Department not found.");
                }
                departmentId = Sql.longValue(department, "id", 0);
            }
        }
        if (repository.updateEncounter(id, version, chiefComplaint, type, departmentId,
                actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                    "The encounter changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "ENCOUNTER_UPDATED", "ENCOUNTER", publicId,
                ClinicalSupport.json("chiefComplaint", Sql.text(row, "chiefComplaint"),
                        "type", Sql.text(row, "type")),
                ClinicalSupport.json("chiefComplaint", chiefComplaint, "type", type), null, ip);
        return workspace(repository.encounterByPublicId(publicId));
    }

    @Override
    public Map<String, Object> close(String publicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        long patientId = Sql.longValue(row, "patientId", 0);
        Long appointmentId = Sql.longValue(row, "appointmentId");

        if (repository.unsignedNoteCount(id) > 0) {
            throw AppException.unprocessable("UNSIGNED_NOTES",
                    "لا يمكن إغلاق الزيارة قبل توقيع كل الملاحظات.",
                    "The encounter cannot be closed while clinical notes are still unsigned.");
        }
        if (repository.closeEncounter(id, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                    "The encounter changed by another user. Reload and retry.");
        }
        repository.completeAppointmentForEncounter(appointmentId, actor.getUserId());
        captureConsultationCharge(patientId, id, publicId, actor);
        audit.record(actor.getUserId(), "ENCOUNTER_CLOSED", "ENCOUNTER", publicId,
                ClinicalSupport.json("status", "OPEN"), ClinicalSupport.json("status", "CLOSED"),
                ClinicalSupport.optional(body, "reason"), ip);
        return workspace(repository.encounterByPublicId(publicId));
    }

    @Override
    public Map<String, Object> cancel(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long version = requireVersion(row, body);
        if (repository.cancelEncounter(id, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الزيارة من مستخدم آخر. أعد التحميل.",
                    "The encounter changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "ENCOUNTER_CANCELLED", "ENCOUNTER", publicId,
                ClinicalSupport.json("status", "OPEN"), ClinicalSupport.json("status", "CANCELLED"),
                ClinicalSupport.optional(body, "reason"), ip);
        return workspace(repository.encounterByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Vitals
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> addVitals(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);

        BigDecimal temperature = decimal(body, "temperatureC");
        Integer heartRate = ClinicalSupport.optionalInt(body, "heartRate");
        Integer respiratoryRate = ClinicalSupport.optionalInt(body, "respiratoryRate");
        Integer systolic = ClinicalSupport.optionalInt(body, "systolic");
        Integer diastolic = ClinicalSupport.optionalInt(body, "diastolic");
        Integer spo2 = ClinicalSupport.optionalInt(body, "spo2");
        BigDecimal weight = decimal(body, "weightKg");
        BigDecimal height = decimal(body, "heightCm");
        Integer painScore = ClinicalSupport.optionalInt(body, "painScore");

        if (temperature == null && heartRate == null && respiratoryRate == null
                && systolic == null && diastolic == null && spo2 == null
                && weight == null && height == null && painScore == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أدخل قيمة واحدة على الأقل من العلامات الحيوية.",
                    "Enter at least one vital sign value.");
        }
        requireRange("temperatureC", temperature, 25, 45, "درجة الحرارة", "Temperature");
        requireRange("heartRate", heartRate, 20, 300, "النبض", "Heart rate");
        requireRange("respiratoryRate", respiratoryRate, 4, 80, "معدل التنفس", "Respiratory rate");
        requireRange("systolic", systolic, 40, 300, "الضغط الانقباضي", "Systolic pressure");
        requireRange("diastolic", diastolic, 20, 200, "الضغط الانبساطي", "Diastolic pressure");
        requireRange("spo2", spo2, 0, 100, "تشبع الأكسجين", "Oxygen saturation");
        requireRange("weightKg", weight, 0.3, 400, "الوزن", "Weight");
        requireRange("heightCm", height, 20, 260, "الطول", "Height");
        requireRange("painScore", painScore, 0, 10, "درجة الألم", "Pain score");

        String vitalsPublicId = Ulid.next();
        repository.insertVitals(vitalsPublicId, id, patientId, temperature, heartRate,
                respiratoryRate, systolic, diastolic, spo2, weight, height, painScore,
                actor.getUserId());
        audit.record(actor.getUserId(), "VITALS_RECORDED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("vitalsId", vitalsPublicId, "temperatureC", temperature,
                        "heartRate", heartRate, "systolic", systolic, "diastolic", diastolic,
                        "spo2", spo2), null, ip);
        Map<String, Object> latest = repository.latestVitals(id);
        if (latest != null) {
            latest.put("publicId", vitalsPublicId);
        }
        return latest == null ? ClinicalSupport.map("publicId", vitalsPublicId) : latest;
    }

    // ------------------------------------------------------------------
    // Diagnoses
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> addDiagnosis(String publicId, Map<String, Object> body,
                                            AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        String description = ClinicalSupport.required(body, "description",
                "وصف التشخيص مطلوب.", "Diagnosis description is required.");
        if (description.length() > 255) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "وصف التشخيص أطول من الحد المسموح.",
                    "Diagnosis description exceeds 255 characters.");
        }
        String code = ClinicalSupport.optionalTrim(body, "code", 20);
        boolean primary = ClinicalSupport.bool(body, "primary");
        String type = primary ? "PRIMARY" : "SECONDARY";
        if (primary) {
            repository.clearPrimary(id);
        }
        String diagnosisPublicId = Ulid.next();
        repository.insertDiagnosis(diagnosisPublicId, id, code, description, type, primary,
                actor.getUserId());
        audit.record(actor.getUserId(), "DIAGNOSIS_ADDED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("diagnosisId", diagnosisPublicId, "code", code,
                        "description", description, "type", type), null, ip);
        return ClinicalSupport.map("publicId", diagnosisPublicId, "code", code,
                "description", description, "diagnosisType", type, "primary", primary);
    }

    @Override
    public Map<String, Object> deleteDiagnosis(String publicId, String diagnosisPublicId,
                                               AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        Map<String, Object> diagnosis = repository.diagnosisByPublicId(diagnosisPublicId);
        if (diagnosis == null || Sql.longValue(diagnosis, "encounterId", 0) != id) {
            throw AppException.notFound("DIAGNOSIS_NOT_FOUND",
                    "التشخيص غير موجود.", "Diagnosis not found.");
        }
        repository.deleteDiagnosis(id, diagnosisPublicId);
        audit.record(actor.getUserId(), "DIAGNOSIS_REMOVED", "ENCOUNTER", publicId,
                ClinicalSupport.json("description", Sql.text(diagnosis, "description")),
                null, null, ip);
        return ClinicalSupport.map("publicId", diagnosisPublicId, "deleted", true);
    }

    // ------------------------------------------------------------------
    // Clinical notes
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> createNote(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long encounterId = Sql.longValue(row, "id", 0);
        String noteType = ClinicalSupport.optional(body, "noteType");
        noteType = noteType == null ? "PROGRESS" : noteType.toUpperCase();
        if (!NOTE_TYPES.contains(noteType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الملاحظة غير صحيح.", "Invalid note type.");
        }
        Map<String, Object> content = contentMap(body);
        String contentJson = JsonUtil.toJson(content);
        String contentText = contentText(content);

        String notePublicId = Ulid.next();
        long noteId = repository.insertNote(notePublicId, encounterId, noteType, actor.getUserId());
        long versionId = repository.insertNoteVersion(Ulid.next(), noteId, 1, contentJson,
                contentText, null, null, null, null, actor.getUserId());
        repository.updateNoteDraft(noteId, 0, versionId, actor.getUserId());
        audit.record(actor.getUserId(), "NOTE_CREATED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("noteId", notePublicId, "noteType", noteType), null, ip);
        return noteView(repository.noteByPublicId(notePublicId));
    }

    @Override
    public Map<String, Object> updateNote(String publicId, String notePublicId,
                                          Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireOpenEncounter(publicId);
        Map<String, Object> note = requireNote(encounter, notePublicId);
        if (!"DRAFT".equals(Sql.text(note, "status"))) {
            throw AppException.conflict("NOTE_SIGNED",
                    "لا يمكن تعديل ملاحظة موقّعة. استخدم التعديل اللاحق (Amendment).",
                    "A signed note cannot be edited. Use a late amendment instead.");
        }
        long noteId = Sql.longValue(note, "id", 0);
        long version = Sql.longValue(note, "version", 0);
        Map<String, Object> content = contentMap(body);
        int versionNo = repository.maxNoteVersion(noteId);
        long versionId = repository.insertNoteVersion(Ulid.next(), noteId, versionNo + 1,
                JsonUtil.toJson(content), contentText(content), null, null, null, null,
                actor.getUserId());
        if (repository.updateNoteDraft(noteId, version, versionId, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الملاحظة من مستخدم آخر. أعد التحميل.",
                    "The note changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "NOTE_UPDATED", "ENCOUNTER", publicId,
                ClinicalSupport.json("noteId", notePublicId, "versionNo", versionNo),
                ClinicalSupport.json("noteId", notePublicId, "versionNo", versionNo + 1), null, ip);
        return noteView(repository.noteByPublicId(notePublicId));
    }

    @Override
    public Map<String, Object> signNote(String publicId, String notePublicId,
                                        Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> note = requireNote(encounter, notePublicId);
        if (!"DRAFT".equals(Sql.text(note, "status"))) {
            throw AppException.conflict("NOTE_ALREADY_SIGNED",
                    "الملاحظة موقّعة بالفعل.", "The note is already signed.");
        }
        long authorId = Sql.longValue(note, "authorId", 0);
        if (authorId != actor.getUserId() && !actor.hasPermission("EMR:READ_ALL")) {
            throw AppException.forbidden("NOT_NOTE_AUTHOR",
                    "يمكن لكاتب الملاحظة فقط توقيعها.",
                    "Only the note author can sign it.");
        }
        long noteId = Sql.longValue(note, "id", 0);
        long version = Sql.longValue(note, "version", 0);
        // Serialise sign vs. concurrent amend: the parent row lock is taken
        // before the version row is touched, so the loser sees a stale version
        // and fails cleanly instead of deadlocking on the version index.
        repository.lockNote(noteId);
        Long currentVersionId = Sql.longValue(note, "currentVersionId");
        if (currentVersionId == null) {
            throw AppException.unprocessable("NOTE_EMPTY",
                    "لا يمكن توقيع ملاحظة بلا محتوى.", "An empty note cannot be signed.");
        }
        int versionNo = repository.maxNoteVersion(noteId);
        if (repository.signNoteVersion(currentVersionId, actor.getUserId()) == 0) {
            throw AppException.conflict("NOTE_ALREADY_SIGNED",
                    "الملاحظة موقّعة بالفعل.", "The note is already signed.");
        }
        if (repository.signNote(noteId, version, currentVersionId, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الملاحظة من مستخدم آخر. أعد التحميل.",
                    "The note changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "NOTE_SIGNED", "ENCOUNTER", publicId,
                ClinicalSupport.json("noteId", notePublicId, "status", "DRAFT"),
                ClinicalSupport.json("noteId", notePublicId, "status", "SIGNED",
                        "versionNo", versionNo), null, ip);
        return noteView(repository.noteByPublicId(notePublicId));
    }

    @Override
    public Map<String, Object> amendNote(String publicId, String notePublicId,
                                         Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> note = requireNote(encounter, notePublicId);
        String status = Sql.text(note, "status");
        if (!"SIGNED".equals(status) && !"AMENDED".equals(status)) {
            throw AppException.conflict("NOTE_NOT_SIGNED",
                    "لا يمكن تعديل ملاحظة غير موقّعة. عدّلها كمسودة.",
                    "An unsigned note cannot be amended. Edit it as a draft.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب التعديل اللاحق مطلوب.", "Amendment reason is required.");
        LocalDateTime signedAt = parseDateTime(Sql.text(note, "signedAt"));
        long windowHours = longSetting("emr.note.amend.window_hours", 24);
        if (signedAt != null && Duration.between(signedAt, LocalDateTime.now()).toHours() > windowHours
                && !actor.hasPermission("EMR:AMEND_LATE")) {
            throw AppException.forbidden("AMEND_WINDOW_CLOSED",
                    "انتهت نافذة التعديل اللاحق. يلزم امتياز التعديل المتأخر.",
                    "The amendment window has closed. The late-amendment privilege is required.");
        }
        Map<String, Object> content = contentMap(body);
        long noteId = Sql.longValue(note, "id", 0);
        long version = requireVersion(note, body);
        // Take the parent row lock before inserting the new version: two
        // concurrent amendments of the same note queue here, and the loser
        // fails the optimistic check below with 409 instead of deadlocking.
        repository.lockNote(noteId);
        Long currentVersionId = Sql.longValue(note, "currentVersionId");
        int versionNo = repository.maxNoteVersion(noteId);
        long versionId;
        try {
            versionId = repository.insertNoteVersion(Ulid.next(), noteId, versionNo + 1,
                    JsonUtil.toJson(content), contentText(content), currentVersionId, reason,
                    actor.getUserId(), LocalDateTime.now(), actor.getUserId());
        } catch (RuntimeException ex) {
            if (isDuplicateKey(ex)) {
                throw AppException.conflict("STALE_VERSION",
                        "تم تعديل الملاحظة من مستخدم آخر. أعد التحميل.",
                        "The note changed by another user. Reload and retry.");
            }
            throw ex;
        }
        if (repository.amendNote(noteId, version, versionId, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الملاحظة من مستخدم آخر. أعد التحميل.",
                    "The note changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "NOTE_AMENDED", "ENCOUNTER", publicId,
                ClinicalSupport.json("noteId", notePublicId, "versionNo", versionNo),
                ClinicalSupport.json("noteId", notePublicId, "versionNo", versionNo + 1), reason, ip);
        return noteView(repository.noteByPublicId(notePublicId));
    }

    @Override
    public List<Map<String, Object>> noteVersions(String publicId, String notePublicId,
                                                  AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> note = requireNote(encounter, notePublicId);
        return repository.noteVersions(Sql.longValue(note, "id", 0));
    }

    private Map<String, Object> requireNote(Map<String, Object> encounter, String notePublicId) {
        Map<String, Object> note = repository.noteByPublicId(notePublicId);
        if (note == null
                || Sql.longValue(note, "encounterId", 0) != Sql.longValue(encounter, "id", 0)) {
            throw AppException.notFound("NOTE_NOT_FOUND",
                    "الملاحظة غير موجودة.", "Clinical note not found.");
        }
        return note;
    }

    private Map<String, Object> noteView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("noteType", row.get("noteType"));
        view.put("status", row.get("status"));
        view.put("version", row.get("version"));
        view.put("versionNo", row.get("versionNo"));
        view.put("content", row.get("contentJson"));
        view.put("contentText", row.get("contentText"));
        view.put("signedAt", row.get("signedAt"));
        view.put("createdAt", row.get("createdAt"));
        return view;
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> createOrder(String publicId, Map<String, Object> body,
                                           AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long encounterId = Sql.longValue(row, "id", 0);
        String type = ClinicalSupport.required(body, "orderType",
                "نوع الطلب مطلوب.", "Order type is required.").toUpperCase();
        if (!ORDER_TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الطلب غير صحيح.", "Invalid order type.");
        }
        String priority = ClinicalSupport.optional(body, "priority");
        priority = priority == null ? "ROUTINE" : priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أولوية الطلب غير صحيحة.", "Invalid order priority.");
        }
        if (!(body.get("services") instanceof List<?> services) || services.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أضف خدمة واحدة على الأقل للطلب.", "Add at least one service to the order.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);
        String orderPublicId = Ulid.next();
        long orderId = repository.insertOrder(orderPublicId, encounterId, type, priority, notes,
                actor.getUserId());
        List<String> codes = new ArrayList<>();
        for (Object item : services) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String servicePublicId = stringValue(map.get("servicePublicId"));
            if (servicePublicId == null) {
                continue;
            }
            Map<String, Object> service = repository.serviceByPublicId(servicePublicId);
            if (service == null) {
                throw AppException.notFound("SERVICE_NOT_FOUND",
                        "الخدمة غير موجودة.", "Service not found.");
            }
            int quantity = map.get("quantity") == null ? 1
                    : parseInt(map.get("quantity"), 1);
            if (quantity < 1 || quantity > 50) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "كمية الخدمة يجب أن تكون بين 1 و50.",
                        "Service quantity must be between 1 and 50.");
            }
            repository.insertOrderItem(Ulid.next(), orderId,
                    Sql.longValue(service, "id", 0), quantity);
            codes.add(Sql.text(service, "code"));
        }
        if (codes.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أضف خدمة واحدة على الأقل للطلب.", "Add at least one service to the order.");
        }
        audit.record(actor.getUserId(), "ORDER_CREATED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("orderId", orderPublicId, "orderType", type,
                        "priority", priority, "services", codes), null, ip);
        return ClinicalSupport.map("publicId", orderPublicId, "orderType", type,
                "priority", priority, "status", "ORDERED", "services", codes);
    }

    @Override
    public Map<String, Object> updateOrderStatus(String publicId, String orderPublicId,
                                                 Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> order = repository.orderByPublicId(orderPublicId);
        if (order == null
                || Sql.longValue(order, "encounterId", 0) != Sql.longValue(encounter, "id", 0)) {
            throw AppException.notFound("ORDER_NOT_FOUND",
                    "الطلب غير موجود.", "Service order not found.");
        }
        String status = ClinicalSupport.required(body, "status",
                "حالة الطلب مطلوبة.", "Order status is required.").toUpperCase();
        if (!ORDER_STATUSES.contains(status)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة الطلب غير صحيحة.", "Invalid order status.");
        }
        if (repository.changeOrderStatus(Sql.longValue(order, "id", 0),
                Sql.longValue(order, "version", 0), status, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الطلب من مستخدم آخر. أعد التحميل.",
                    "The order changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "ORDER_STATUS_CHANGED", "ENCOUNTER", publicId,
                ClinicalSupport.json("orderId", orderPublicId, "status", Sql.text(order, "status")),
                ClinicalSupport.json("orderId", orderPublicId, "status", status), null, ip);
        return ClinicalSupport.map("publicId", orderPublicId, "status", status);
    }

    // ------------------------------------------------------------------
    // Prescriptions
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> createPrescription(String publicId, Map<String, Object> body,
                                                  AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOpenEncounter(publicId);
        long encounterId = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        if (!(body.get("items") instanceof List<?> items) || items.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أضف دواءً واحدًا على الأقل للوصفة.", "Add at least one drug to the prescription.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);
        String prescriptionPublicId = Ulid.next();
        long prescriptionId = repository.insertPrescription(prescriptionPublicId, encounterId,
                patientId, notes, actor.getUserId());
        int count = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String drugPublicId = stringValue(map.get("drugPublicId"));
            String dose = stringValue(map.get("dose"));
            String route = stringValue(map.get("route"));
            String frequency = stringValue(map.get("frequency"));
            if (drugPublicId == null || dose == null || route == null || frequency == null) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "الدواء والجرعة والطريق والتكرار مطلوبة لكل بند.",
                        "Drug, dose, route and frequency are required for every item.");
            }
            Map<String, Object> drug = repository.drugByPublicId(drugPublicId);
            if (drug == null) {
                throw AppException.notFound("DRUG_NOT_FOUND",
                        "الدواء غير موجود.", "Drug not found.");
            }
            Integer durationDays = map.get("durationDays") == null
                    ? null : parseInt(map.get("durationDays"), 0);
            if (durationDays != null && durationDays <= 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "مدة الدواء يجب أن تكون أكبر من صفر.",
                        "Drug duration must be greater than zero.");
            }
            int quantity = map.get("quantity") == null ? 1 : parseInt(map.get("quantity"), 1);
            if (quantity <= 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "كمية الدواء يجب أن تكون أكبر من صفر.",
                        "Drug quantity must be greater than zero.");
            }
            repository.insertPrescriptionItem(Ulid.next(), prescriptionId,
                    Sql.longValue(drug, "id", 0), dose, route, frequency, durationDays, quantity,
                    stringValue(map.get("instructions")));
            count++;
        }
        if (count == 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أضف دواءً واحدًا على الأقل للوصفة.", "Add at least one drug to the prescription.");
        }
        audit.record(actor.getUserId(), "PRESCRIPTION_CREATED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("prescriptionId", prescriptionPublicId, "items", count),
                null, ip);
        return prescriptionView(repository.prescriptionByPublicId(prescriptionPublicId));
    }

    @Override
    public Map<String, Object> issuePrescription(String publicId, String prescriptionPublicId,
                                                 Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> prescription = requirePrescription(encounter, prescriptionPublicId);
        if (!"DRAFT".equals(Sql.text(prescription, "status"))) {
            throw AppException.conflict("PRESCRIPTION_NOT_DRAFT",
                    "لا يمكن إصدار وصفة في حالتها الحالية.",
                    "The prescription cannot be issued in its current status.");
        }
        if (repository.issuePrescription(Sql.longValue(prescription, "id", 0),
                Sql.longValue(prescription, "version", 0), actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الوصفة من مستخدم آخر. أعد التحميل.",
                    "The prescription changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "PRESCRIPTION_ISSUED", "ENCOUNTER", publicId,
                ClinicalSupport.json("prescriptionId", prescriptionPublicId, "status", "DRAFT"),
                ClinicalSupport.json("prescriptionId", prescriptionPublicId, "status", "ISSUED"),
                null, ip);
        return prescriptionView(repository.prescriptionByPublicId(prescriptionPublicId));
    }

    @Override
    public Map<String, Object> cancelPrescription(String publicId, String prescriptionPublicId,
                                                  Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> encounter = requireEncounter(publicId);
        Map<String, Object> prescription = requirePrescription(encounter, prescriptionPublicId);
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الإلغاء مطلوب.", "Cancellation reason is required.");
        if (repository.cancelPrescription(Sql.longValue(prescription, "id", 0),
                Sql.longValue(prescription, "version", 0), actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الوصفة من مستخدم آخر. أعد التحميل.",
                    "The prescription changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "PRESCRIPTION_CANCELLED", "ENCOUNTER", publicId,
                ClinicalSupport.json("prescriptionId", prescriptionPublicId,
                        "status", Sql.text(prescription, "status")),
                ClinicalSupport.json("prescriptionId", prescriptionPublicId, "status", "CANCELLED"),
                reason, ip);
        return prescriptionView(repository.prescriptionByPublicId(prescriptionPublicId));
    }

    @Override
    public Map<String, Object> prescription(String prescriptionPublicId,
                                             AuthPrincipal actor, String ip) {
        Map<String, Object> prescription = repository.prescriptionByPublicId(prescriptionPublicId);
        if (prescription == null) {
            throw AppException.notFound("PRESCRIPTION_NOT_FOUND",
                    "الوصفة غير موجودة.", "Prescription not found.");
        }
        accessLog.record(actor.getUserId(), Sql.longValue(prescription, "patientId", 0),
                "PRESCRIPTION", prescriptionPublicId, "READ", ip);
        return prescriptionView(prescription);
    }

    @Override
    public List<Map<String, Object>> patientPrescriptions(String patientPublicId,
                                                          AuthPrincipal actor, String ip) {
        Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
        if (patient == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        long patientId = Sql.longValue(patient, "id", 0);
        accessLog.record(actor.getUserId(), patientId, "PATIENT_PRESCRIPTIONS", patientPublicId,
                "READ", ip);
        return repository.patientPrescriptions(patientId, 50);
    }

    private Map<String, Object> requirePrescription(Map<String, Object> encounter,
                                                    String prescriptionPublicId) {
        Map<String, Object> prescription = repository.prescriptionByPublicId(prescriptionPublicId);
        if (prescription == null
                || Sql.longValue(prescription, "encounterId", 0) != Sql.longValue(encounter, "id", 0)) {
            throw AppException.notFound("PRESCRIPTION_NOT_FOUND",
                    "الوصفة غير موجودة.", "Prescription not found.");
        }
        return prescription;
    }

    private Map<String, Object> prescriptionView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("issuedAt", row.get("issuedAt"));
        view.put("notes", row.get("notes"));
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate")))));
        view.put("prescribedBy", ClinicalSupport.map(
                "publicId", row.get("prescribedByPublicId"),
                "nameAr", row.get("prescribedByAr"),
                "nameEn", row.get("prescribedByEn")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("items", repository.prescriptionItems(Sql.longValue(row, "id", 0)));
        return view;
    }

    // ------------------------------------------------------------------
    // Charge capture (ADR-016)
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> addCharge(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireEncounter(publicId);
        long encounterId = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        String servicePublicId = ClinicalSupport.required(body, "servicePublicId",
                "الخدمة مطلوبة.", "Service is required.");
        Map<String, Object> service = repository.serviceByPublicId(servicePublicId);
        if (service == null) {
            throw AppException.notFound("SERVICE_NOT_FOUND",
                    "الخدمة غير موجودة.", "Service not found.");
        }
        int quantity = ClinicalSupport.optionalInt(body, "quantity") == null
                ? 1 : ClinicalSupport.optionalInt(body, "quantity");
        if (quantity < 1 || quantity > 20) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الكمية يجب أن تكون بين 1 و20.", "Quantity must be between 1 and 20.");
        }
        BigDecimal price = priceFor(Sql.longValue(service, "id", 0));
        String sourceType = ClinicalSupport.optional(body, "sourceType");
        sourceType = sourceType == null ? "MANUAL" : sourceType.toUpperCase();
        if (!Set.of("MANUAL", "ORDER", "CONSULTATION", "PROCEDURE").contains(sourceType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مصدر الاحتساب غير صحيح.", "Invalid charge source.");
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            String chargePublicId = Ulid.next();
            repository.insertCharge(chargePublicId, patientId, encounterId,
                    Sql.longValue(service, "id", 0), price, sourceType, publicId, actor.getUserId());
            ids.add(chargePublicId);
        }
        audit.record(actor.getUserId(), "CHARGE_CAPTURED", "ENCOUNTER", publicId, null,
                ClinicalSupport.json("service", Sql.text(service, "code"), "quantity", quantity,
                        "priceSnapshot", price, "charges", ids), null, ip);
        return ClinicalSupport.map("serviceCode", Sql.text(service, "code"),
                "quantity", quantity, "priceSnapshot", price, "chargeIds", ids);
    }

    private void captureConsultationCharge(long patientId, long encounterId, String encounterPublicId,
                                           AuthPrincipal actor) {
        if (repository.chargeCount(encounterId) > 0) {
            return;
        }
        Map<String, Object> service = repository.serviceByCode("CONSULT-GEN");
        if (service == null) {
            return;
        }
        BigDecimal price = priceFor(Sql.longValue(service, "id", 0));
        String chargePublicId = Ulid.next();
        repository.insertCharge(chargePublicId, patientId, encounterId,
                Sql.longValue(service, "id", 0), price, "CONSULTATION", encounterPublicId,
                actor.getUserId());
        audit.record(actor.getUserId(), "CHARGE_CAPTURED", "ENCOUNTER", encounterPublicId, null,
                ClinicalSupport.json("service", "CONSULT-GEN", "quantity", 1,
                        "priceSnapshot", price, "charges", List.of(chargePublicId)), null, null);
    }

    private BigDecimal priceFor(long serviceId) {
        Map<String, Object> price = repository.currentPrice(serviceId);
        if (price == null) {
            throw AppException.unprocessable("SERVICE_PRICE_MISSING",
                    "لا يوجد سعر فعّال لهذه الخدمة. حدّث قائمة الأسعار.",
                    "No active price is configured for this service. Update the price list.");
        }
        return Sql.decimal(price, "amount");
    }

    // ------------------------------------------------------------------
    // Print payload
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> printPayload(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireEncounter(publicId);
        long id = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "ENCOUNTER_PRINT", publicId, "PRINT", ip);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounter", summary(row));
        result.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate"))),
                "phone", row.get("patientPhone"),
                "bloodGroup", row.get("patientBloodGroup")));
        result.put("vitals", repository.latestVitals(id));
        result.put("diagnoses", repository.diagnoses(id));
        result.put("notes", repository.notes(id));
        result.put("orders", repository.orders(id));
        List<Map<String, Object>> prescriptions = new ArrayList<>();
        for (Map<String, Object> prescription : repository.prescriptions(id)) {
            Map<String, Object> full = repository.prescriptionByPublicId(
                    String.valueOf(prescription.get("publicId")));
            if (full != null && "ISSUED".equals(Sql.text(full, "status"))) {
                prescriptions.add(prescriptionView(full));
            }
        }
        result.put("prescriptions", prescriptions);
        result.put("allergies", repository.activeAllergies(patientId));
        result.put("charges", repository.charges(id));
        result.put("printedAt", LocalDateTime.now().toString());
        result.put("printedBy", ClinicalSupport.map(
                "nameAr", actor.getDisplayNameAr(),
                "nameEn", actor.getDisplayNameEn()));
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requireEncounter(String publicId) {
        Map<String, Object> row = repository.encounterByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("ENCOUNTER_NOT_FOUND",
                    "الزيارة غير موجودة.", "Encounter not found.");
        }
        return row;
    }

    private Map<String, Object> requireOpenEncounter(String publicId) {
        Map<String, Object> row = requireEncounter(publicId);
        if (!"OPEN".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("ENCOUNTER_CLOSED",
                    "الزيارة مغلقة أو ملغاة ولا تقبل التعديل.",
                    "The encounter is closed or cancelled and cannot be modified.");
        }
        return row;
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

    private Map<String, Object> summary(Map<String, Object> row) {
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "patientPublicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "patientName", row.get("patientName"),
                "patientGender", row.get("patientGender"),
                "patientBirthDate", row.get("patientBirthDate"),
                "age", ClinicalSupport.age(parseDate(Sql.text(row, "patientBirthDate"))),
                "providerPublicId", row.get("providerPublicId"),
                "providerAr", row.get("providerAr"),
                "providerEn", row.get("providerEn"),
                "departmentAr", row.get("departmentAr"),
                "departmentEn", row.get("departmentEn"),
                "appointmentPublicId", row.get("appointmentPublicId"),
                "type", row.get("type"),
                "status", row.get("status"),
                "chiefComplaint", row.get("chiefComplaint"),
                "startedAt", row.get("startedAt"),
                "closedAt", row.get("closedAt"),
                "version", row.get("version"));
    }

    private Map<String, Object> contentMap(Map<String, Object> body) {
        Map<String, Object> content = new LinkedHashMap<>();
        Object raw = body.get("content");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                content.put(key, value == null ? "" : value);
            }
        } else {
            for (String key : List.of("subjective", "objective", "assessment", "plan")) {
                String value = ClinicalSupport.optional(body, key);
                if (value != null) {
                    content.put(key, value);
                }
            }
        }
        if (content.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "محتوى الملاحظة مطلوب.", "Clinical note content is required.");
        }
        return content;
    }

    private String contentText(Map<String, Object> content) {
        StringBuilder text = new StringBuilder();
        for (Object value : content.values()) {
            if (value == null) {
                continue;
            }
            String part = value.toString().trim();
            if (!part.isEmpty()) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(part);
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    private void requireRange(String field, Number value, double min, double max,
                              String labelAr, String labelEn) {
        if (value == null) {
            return;
        }
        double number = value.doubleValue();
        if (number < min || number > max) {
            throw AppException.unprocessable("VITALS_OUT_OF_RANGE",
                    labelAr + " خارج النطاق المقبول (" + trim(min) + "–" + trim(max) + ").",
                    labelEn + " is outside the accepted range (" + trim(min) + "–" + trim(max) + ").");
        }
    }

    private String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private BigDecimal decimal(Map<String, Object> body, String key) {
        String value = ClinicalSupport.optional(body, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية غير صحيحة في الحقل " + key + ".",
                    "Invalid numeric value for " + key + ".");
        }
    }

    private static int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية غير صحيحة.", "Invalid numeric value.");
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private long longSetting(String key, long fallback) {
        return settings.findValue(key)
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException ex) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
