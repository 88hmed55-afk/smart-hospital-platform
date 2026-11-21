package com.smarthospital.clinical.patient;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Patient registration, duplicate control, EMPI and chart reads (FR-PAT-001..012). */
@Stateless
public class PatientService implements PatientServiceLocal {

    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "UNKNOWN");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "MERGED", "INACTIVE", "DECEASED");
    private static final Set<String> BLOOD_GROUPS = Set.of(
            "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    private static final Set<String> MARITAL = Set.of(
            "SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "UNKNOWN");
    private static final Set<String> ALLERGY_TYPES = Set.of("DRUG", "FOOD", "ENVIRONMENT", "OTHER");
    private static final Set<String> ALLERGY_SEVERITY = Set.of(
            "MILD", "MODERATE", "SEVERE", "LIFE_THREATENING");

    @EJB
    private PatientRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Search and chart
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> search(String q, String status, int page, int size) {
        int safeSize = ClinicalSupport.clampSize(size);
        int safePage = Math.max(page, 0);
        String safeStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (safeStatus != null && !STATUSES.contains(safeStatus)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة المريض غير صحيحة.", "Invalid patient status.");
        }
        long total = repository.count(q, safeStatus);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.search(q, safeStatus, safePage * safeSize, safeSize)) {
            items.add(summary(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> get(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        long id = Sql.longValue(row, "id", 0);
        accessLog.record(actor.getUserId(), id, "PATIENT", publicId, "READ", ip);
        return card(row);
    }

    @Override
    public List<Map<String, Object>> timeline(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        long id = Sql.longValue(row, "id", 0);
        accessLog.record(actor.getUserId(), id, "PATIENT_TIMELINE", publicId, "READ", ip);

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> item : repository.appointmentsForTimeline(id)) {
            events.add(ClinicalSupport.map(
                    "type", "APPOINTMENT",
                    "publicId", item.get("publicId"),
                    "at", item.get("at"),
                    "status", item.get("status"),
                    "titleAr", item.get("clinicAr"),
                    "titleEn", item.get("clinicEn"),
                    "subtitleAr", item.get("providerAr"),
                    "subtitleEn", item.get("providerEn")));
        }
        for (Map<String, Object> item : repository.encountersForTimeline(id)) {
            events.add(ClinicalSupport.map(
                    "type", "ENCOUNTER",
                    "publicId", item.get("publicId"),
                    "at", item.get("at"),
                    "status", item.get("status"),
                    "titleAr", item.get("chiefComplaint"),
                    "titleEn", item.get("chiefComplaint"),
                    "subtitleAr", item.get("providerAr"),
                    "subtitleEn", item.get("providerEn")));
        }
        for (Map<String, Object> item : repository.prescriptionsForTimeline(id)) {
            events.add(ClinicalSupport.map(
                    "type", "PRESCRIPTION",
                    "publicId", item.get("publicId"),
                    "at", item.get("at"),
                    "status", item.get("status"),
                    "titleAr", "وصفة طبية",
                    "titleEn", "Prescription",
                    "subtitleAr", item.get("itemCount") + " دواء",
                    "subtitleEn", item.get("itemCount") + " item(s)"));
        }
        for (Map<String, Object> item : repository.allergies(id, false)) {
            events.add(ClinicalSupport.map(
                    "type", "ALLERGY",
                    "publicId", item.get("publicId"),
                    "at", item.get("recordedAt"),
                    "status", item.get("active"),
                    "titleAr", item.get("substance"),
                    "titleEn", item.get("substance"),
                    "subtitleAr", item.get("severity"),
                    "subtitleEn", item.get("severity")));
        }
        events.sort(Comparator.comparing(
                (Map<String, Object> event) -> String.valueOf(event.get("at"))).reversed());
        return events;
    }

    // ------------------------------------------------------------------
    // Registration and update
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> create(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String fullName = ClinicalSupport.required(body, "fullName",
                "اسم المريض مطلوب.", "Patient full name is required.").trim();
        if (fullName.length() < 2 || fullName.length() > 200) {
            throw AppException.unprocessable("INVALID_NAME",
                    "اسم المريض يجب أن يكون بين حرفين و200 حرف.",
                    "Patient name must be between 2 and 200 characters.");
        }
        String gender = ClinicalSupport.required(body, "gender",
                "الجنس مطلوب.", "Gender is required.").toUpperCase();
        if (!GENDERS.contains(gender)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة الجنس غير صحيحة.", "Invalid gender value.");
        }
        LocalDate birthDate = ClinicalSupport.date(body, "birthDate", false);
        boolean birthEstimated = ClinicalSupport.bool(body, "birthDateEstimated");
        if (birthEstimated && birthDate == null) {
            throw AppException.unprocessable("BIRTH_DATE_REQUIRED",
                    "تاريخ الميلاد مطلوب عند تحديد أنه تقديري.",
                    "A birth date is required when it is marked as estimated.");
        }

        String nationalId = ClinicalSupport.optionalTrim(body, "nationalId", 40);
        String phone = ClinicalSupport.optionalTrim(body, "phone", 30);
        String email = ClinicalSupport.optionalTrim(body, "email", 190);
        String address = ClinicalSupport.optionalTrim(body, "address", 500);
        String bloodGroup = upperOptional(body, "bloodGroup");
        String marital = upperOptional(body, "maritalStatus");
        String notes = ClinicalSupport.optionalTrim(body, "notes", 2000);
        validateBloodGroup(bloodGroup);
        validateMarital(marital);

        if (nationalId != null && repository.findActiveByNationalId(nationalId) != null) {
            throw AppException.conflict("DUPLICATE_PATIENT_EXACT",
                    "يوجد مريض مسجّل بنفس رقم الهوية.",
                    "An active patient already has this national ID.");
        }

        Map<String, Object> duplicates = duplicateCheck(nationalId, phone,
                ClinicalSupport.normalizeName(fullName), birthDate);
        String duplicateStatus = String.valueOf(duplicates.get("status"));
        if ("POSSIBLE_MATCH".equals(duplicateStatus) && !ClinicalSupport.bool(body, "override")) {
            throw AppException.conflict("DUPLICATE_PATIENT_POSSIBLE",
                    "يوجد مريض مشابه. راجع النتائج وأكّد التسجيل مع سبب التجاوز.",
                    "A possible duplicate patient exists. Review the matches and confirm with an override reason.");
        }
        String overrideReason = null;
        if (ClinicalSupport.bool(body, "override")) {
            overrideReason = ClinicalSupport.required(body, "overrideReason",
                    "سبب تجاوز تحذير التكرار مطلوب.",
                    "An override reason is required when a duplicate warning is bypassed.");
        }

        int year = Year.now().getValue();
        String medicalNumber = String.format("P-%d-%06d", year, repository.nextMedicalNumber(String.valueOf(year)));
        String publicId = Ulid.next();
        long patientId = repository.insertPatient(publicId, medicalNumber, fullName,
                ClinicalSupport.normalizeName(fullName), gender, birthDate, birthEstimated,
                nationalId, phone, email, address, bloodGroup, marital, notes, actor.getUserId());

        repository.insertIdentifier(patientId, "MRN", medicalNumber, true, actor.getUserId());
        if (nationalId != null) {
            repository.insertIdentifier(patientId, "NATIONAL_ID", nationalId, false, actor.getUserId());
        }
        insertIdentifiers(patientId, body.get("identifiers"), actor.getUserId());
        insertEmergencyContact(patientId, body.get("emergencyContact"), actor.getUserId());
        insertAllergies(patientId, body.get("allergies"), actor.getUserId());
        insertConditions(patientId, body.get("conditions"), actor.getUserId());

        Map<String, Object> row = repository.findByPublicId(publicId);
        Map<String, Object> result = card(row);
        result.put("duplicateCheck", duplicates);
        audit.record(actor.getUserId(), "PATIENT_CREATED", "PATIENT", publicId,
                null, ClinicalSupport.json("medicalNumber", medicalNumber, "fullName", fullName,
                        "duplicateStatus", duplicateStatus), overrideReason, ip);
        return result;
    }

    @Override
    public Map<String, Object> update(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        if ("MERGED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("PATIENT_MERGED",
                    "لا يمكن تعديل مريض مدموج. عدّل السجل الهدف.",
                    "A merged patient cannot be edited. Update the target record instead.");
        }
        long id = Sql.longValue(row, "id", 0);
        long version = Sql.longValue(row, "version", 0);
        Long requestedVersion = ClinicalSupport.optionalLong(body, "version");
        if (requestedVersion != null && requestedVersion != version) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل السجل من مستخدم آخر. أعد تحميل البيانات.",
                    "The record was changed by another user. Reload and retry.");
        }

        String fullName = body.containsKey("fullName")
                ? ClinicalSupport.required(body, "fullName", "اسم المريض مطلوب.", "Patient name is required.")
                : Sql.text(row, "fullName");
        String gender = body.containsKey("gender")
                ? ClinicalSupport.required(body, "gender", "الجنس مطلوب.", "Gender is required.").toUpperCase()
                : Sql.text(row, "gender");
        if (!GENDERS.contains(gender)) {
            throw AppException.badRequest("VALIDATION_FAILED", "قيمة الجنس غير صحيحة.", "Invalid gender value.");
        }
        LocalDate birthDate = body.containsKey("birthDate")
                ? ClinicalSupport.date(body, "birthDate", false)
                : parseDate(Sql.text(row, "birthDate"));
        boolean birthEstimated = body.containsKey("birthDateEstimated")
                ? ClinicalSupport.bool(body, "birthDateEstimated")
                : Sql.boolValue(row, "birthDateEstimated");
        String nationalId = body.containsKey("nationalId")
                ? ClinicalSupport.optionalTrim(body, "nationalId", 40)
                : Sql.text(row, "nationalId");
        if (nationalId != null && !nationalId.equals(Sql.text(row, "nationalId"))) {
            Map<String, Object> other = repository.findActiveByNationalId(nationalId);
            if (other != null && Sql.longValue(other, "id", 0) != id) {
                throw AppException.conflict("DUPLICATE_PATIENT_EXACT",
                        "يوجد مريض آخر بنفس رقم الهوية.",
                        "Another active patient already has this national ID.");
            }
        }
        String phone = body.containsKey("phone")
                ? ClinicalSupport.optionalTrim(body, "phone", 30) : Sql.text(row, "phone");
        String email = body.containsKey("email")
                ? ClinicalSupport.optionalTrim(body, "email", 190) : Sql.text(row, "email");
        String address = body.containsKey("address")
                ? ClinicalSupport.optionalTrim(body, "address", 500) : Sql.text(row, "address");
        String bloodGroup = body.containsKey("bloodGroup")
                ? upperOptional(body, "bloodGroup") : Sql.text(row, "bloodGroup");
        String marital = body.containsKey("maritalStatus")
                ? upperOptional(body, "maritalStatus") : Sql.text(row, "maritalStatus");
        String notes = body.containsKey("notes")
                ? ClinicalSupport.optionalTrim(body, "notes", 2000) : Sql.text(row, "notes");
        validateBloodGroup(bloodGroup);
        validateMarital(marital);

        String before = ClinicalSupport.json("fullName", Sql.text(row, "fullName"),
                "phone", Sql.text(row, "phone"), "nationalId", Sql.text(row, "nationalId"));
        int updated = repository.updatePatient(id, version, fullName,
                ClinicalSupport.normalizeName(fullName), gender, birthDate, birthEstimated,
                nationalId, phone, email, address, bloodGroup, marital, notes, actor.getUserId());
        if (updated == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل السجل من مستخدم آخر. أعد تحميل البيانات.",
                    "The record was changed by another user. Reload and retry.");
        }
        Map<String, Object> fresh = repository.findByPublicId(publicId);
        audit.record(actor.getUserId(), "PATIENT_UPDATED", "PATIENT", publicId, before,
                ClinicalSupport.json("fullName", fullName, "phone", phone, "nationalId", nationalId), null, ip);
        return card(fresh);
    }

    // ------------------------------------------------------------------
    // Duplicate control and merge
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> checkDuplicates(Map<String, Object> body) {
        String nationalId = ClinicalSupport.optionalTrim(body, "nationalId", 40);
        String phone = ClinicalSupport.optionalTrim(body, "phone", 30);
        String fullName = ClinicalSupport.optional(body, "fullName");
        LocalDate birthDate = ClinicalSupport.date(body, "birthDate", false);
        return duplicateCheck(nationalId, phone,
                fullName == null ? "" : ClinicalSupport.normalizeName(fullName), birthDate);
    }

    @Override
    public Map<String, Object> merge(String sourcePublicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip) {
        String targetPublicId = ClinicalSupport.required(body, "targetPublicId",
                "معرّف المريض الهدف مطلوب.", "Target patient id is required.");
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الدمج مطلوب.", "Merge reason is required.");
        if (sourcePublicId.equals(targetPublicId)) {
            throw AppException.unprocessable("INVALID_MERGE",
                    "لا يمكن دمج المريض مع نفسه.", "A patient cannot be merged into itself.");
        }
        Map<String, Object> source = requirePatient(sourcePublicId);
        Map<String, Object> target = requirePatient(targetPublicId);
        if (!"ACTIVE".equals(Sql.text(source, "status"))) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "المريض المصدر غير نشط.", "The source patient is not active.");
        }
        if (!"ACTIVE".equals(Sql.text(target, "status"))) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "المريض الهدف غير نشط.", "The target patient is not active.");
        }
        long sourceId = Sql.longValue(source, "id", 0);
        long targetId = Sql.longValue(target, "id", 0);
        Map<String, Object> open = Sql.one(em,
                "SELECT public_id FROM encounters WHERE patient_id = ? AND status = 'OPEN' LIMIT 1", sourceId);
        if (open != null) {
            throw AppException.conflict("OPEN_ENCOUNTER",
                    "لا يمكن دمج مريض لديه زيارة مفتوحة.",
                    "A patient with an open encounter cannot be merged.");
        }
        int updated = repository.markMerged(sourceId, targetId, actor.getUserId());
        if (updated == 0) {
            throw AppException.conflict("PATIENT_NOT_ACTIVE",
                    "تغيّرت حالة المريض المصدر. أعد المحاولة.",
                    "The source patient changed state. Retry.");
        }
        repository.insertMerge(Ulid.next(), sourceId, targetId, reason, actor.getUserId());
        audit.record(actor.getUserId(), "PATIENT_MERGED", "PATIENT", sourcePublicId,
                ClinicalSupport.json("status", "ACTIVE", "mergedInto", null),
                ClinicalSupport.json("status", "MERGED", "mergedInto", targetPublicId),
                reason, ip);
        return card(repository.findByPublicId(targetPublicId));
    }

    // ------------------------------------------------------------------
    // Allergies
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> allergies(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        long id = Sql.longValue(row, "id", 0);
        accessLog.record(actor.getUserId(), id, "PATIENT_ALLERGIES", publicId, "READ", ip);
        return repository.allergies(id, false);
    }

    @Override
    public Map<String, Object> addAllergy(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        long id = Sql.longValue(row, "id", 0);
        String substance = ClinicalSupport.required(body, "substance",
                "مادة الحساسية مطلوبة.", "Allergy substance is required.");
        String type = ClinicalSupport.required(body, "allergyType",
                "نوع الحساسية مطلوب.", "Allergy type is required.").toUpperCase();
        String severity = ClinicalSupport.required(body, "severity",
                "شدة الحساسية مطلوبة.", "Allergy severity is required.").toUpperCase();
        if (!ALLERGY_TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الحساسية غير صحيح.", "Invalid allergy type.");
        }
        if (!ALLERGY_SEVERITY.contains(severity)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "شدة الحساسية غير صحيحة.", "Invalid allergy severity.");
        }
        String reaction = ClinicalSupport.optionalTrim(body, "reaction", 255);
        repository.insertAllergy(id, substance, type, severity, reaction, actor.getUserId());
        audit.record(actor.getUserId(), "ALLERGY_ADDED", "PATIENT", publicId, null,
                ClinicalSupport.json("substance", substance, "type", type, "severity", severity), null, ip);
        return repository.allergies(id, false).stream()
                .filter(item -> substance.equals(item.get("substance")))
                .findFirst()
                .orElse(ClinicalSupport.map("substance", substance));
    }

    @Override
    public Map<String, Object> removeAllergy(String publicId, String allergyPublicId,
                                             AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePatient(publicId);
        long id = Sql.longValue(row, "id", 0);
        Map<String, Object> allergy = repository.allergyByPublicId(allergyPublicId);
        if (allergy == null || Sql.longValue(allergy, "patientId", 0) != id) {
            throw AppException.notFound("ALLERGY_NOT_FOUND",
                    "الحساسية غير موجودة.", "Allergy record not found.");
        }
        if (repository.deactivateAllergy(id, allergyPublicId, actor.getUserId()) == 0) {
            throw AppException.conflict("ALLERGY_ALREADY_INACTIVE",
                    "الحساسية غير مفعّلة بالفعل.", "The allergy is already inactive.");
        }
        audit.record(actor.getUserId(), "ALLERGY_REMOVED", "PATIENT", publicId,
                ClinicalSupport.json("substance", Sql.text(allergy, "substance")), null,
                ClinicalSupport.optional(allergy, "substance"), ip);
        return ClinicalSupport.map("publicId", allergyPublicId, "active", false);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requirePatient(String publicId) {
        Map<String, Object> row = repository.findByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        return row;
    }

    private Map<String, Object> duplicateCheck(String nationalId, String phone,
                                               String normalizedName, LocalDate birthDate) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> matches = new ArrayList<>();
        String status = "NO_MATCH";
        int score = 0;

        if (nationalId != null && !nationalId.isBlank()) {
            Map<String, Object> exact = repository.findActiveByNationalId(nationalId);
            if (exact != null) {
                matches.add(match(exact, "NATIONAL_ID", 100));
                status = "EXACT_MATCH";
                score = 100;
            }
        }
        if (matches.isEmpty() && phone != null && !phone.isBlank()) {
            Map<String, Object> byPhone = repository.findByPhone(phone);
            if (byPhone != null) {
                matches.add(match(byPhone, "PHONE", 70));
                status = "POSSIBLE_MATCH";
                score = 70;
            }
        }
        if (!normalizedName.isBlank()) {
            for (Map<String, Object> row : repository.findPossibleMatches(normalizedName, birthDate)) {
                if (matches.stream().noneMatch(existing ->
                        String.valueOf(existing.get("publicId")).equals(row.get("public_id")))) {
                    matches.add(match(row, birthDate == null ? "NAME" : "NAME_BIRTH_DATE",
                            birthDate == null ? 45 : 60));
                    if (!"EXACT_MATCH".equals(status)) {
                        status = "POSSIBLE_MATCH";
                        score = Math.max(score, birthDate == null ? 45 : 60);
                    }
                }
            }
        }
        result.put("status", status);
        result.put("score", score);
        result.put("matches", matches);
        return result;
    }

    private Map<String, Object> match(Map<String, Object> row, String reason, int score) {
        return ClinicalSupport.map(
                "publicId", row.get("public_id"),
                "medicalNumber", row.get("medical_number"),
                "fullName", row.get("full_name"),
                "gender", row.get("gender"),
                "birthDate", row.get("birth_date"),
                "phone", row.get("phone"),
                "matchReason", reason,
                "score", score);
    }

    private Map<String, Object> summary(Map<String, Object> row) {
        LocalDate birthDate = parseDate(Sql.text(row, "birthDate"));
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("fullName"),
                "gender", row.get("gender"),
                "birthDate", row.get("birthDate"),
                "age", ClinicalSupport.age(birthDate),
                "phone", row.get("phone"),
                "nationalId", row.get("nationalId"),
                "status", row.get("status"),
                "allergyCount", row.get("allergyCount"),
                "createdAt", row.get("createdAt"));
    }

    private Map<String, Object> card(Map<String, Object> row) {
        long id = Sql.longValue(row, "id", 0);
        Map<String, Object> card = summary(row);
        card.put("email", row.get("email"));
        card.put("address", row.get("address"));
        card.put("bloodGroup", row.get("bloodGroup"));
        card.put("maritalStatus", row.get("maritalStatus"));
        card.put("notes", row.get("notes"));
        card.put("birthDateEstimated", Sql.boolValue(row, "birthDateEstimated"));
        card.put("version", row.get("version"));
        card.put("mergedIntoPublicId", row.get("mergedIntoPublicId"));
        card.put("mergedIntoMedicalNumber", row.get("mergedIntoMedicalNumber"));
        card.put("identifiers", repository.identifiers(id));
        card.put("contacts", repository.contacts(id));
        card.put("allergies", repository.allergies(id, true));
        card.put("conditions", repository.conditions(id));
        card.put("flags", repository.flags(id));
        card.put("encounterCount", repository.countEncounters(id));
        card.put("appointmentCount", repository.countAppointments(id));
        return card;
    }

    private void insertIdentifiers(long patientId, Object value, Long actorId) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String type = stringValue(map.get("type"));
            String identifier = stringValue(map.get("value"));
            if (type == null || identifier == null) {
                continue;
            }
            String normalizedType = type.toUpperCase();
            if (!Set.of("NATIONAL_ID", "PASSPORT", "INSURANCE", "MRN", "OTHER").contains(normalizedType)) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "نوع معرّف المريض غير صحيح.", "Invalid patient identifier type.");
            }
            repository.insertIdentifier(patientId, normalizedType, identifier,
                    Boolean.TRUE.equals(map.get("primary")), actorId);
        }
    }

    private void insertEmergencyContact(long patientId, Object value, Long actorId) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        String name = stringValue(map.get("name"));
        String phone = stringValue(map.get("phone"));
        if (name == null || phone == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "اسم وهاتف جهة الاتصال الطارئة مطلوبان.",
                    "Emergency contact name and phone are required.");
        }
        String relation = stringValue(map.get("relation"));
        repository.insertContact(patientId, "EMERGENCY", name, relation, phone, true, actorId);
    }

    private void insertAllergies(long patientId, Object value, Long actorId) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String substance = stringValue(map.get("substance"));
            if (substance == null) {
                continue;
            }
            String type = stringValue(map.get("allergyType"));
            String severity = stringValue(map.get("severity"));
            repository.insertAllergy(patientId, substance,
                    type == null ? "OTHER" : type.toUpperCase(),
                    severity == null ? "MILD" : severity.toUpperCase(),
                    stringValue(map.get("reaction")), actorId);
        }
    }

    private void insertConditions(long patientId, Object value, Long actorId) {
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String description = stringValue(map.get("description"));
            if (description != null) {
                repository.insertCondition(patientId, description, null, actorId);
            }
        }
    }

    private String upperOptional(Map<String, Object> body, String key) {
        String value = ClinicalSupport.optional(body, key);
        return value == null ? null : value.toUpperCase();
    }

    private void validateBloodGroup(String bloodGroup) {
        if (bloodGroup != null && !BLOOD_GROUPS.contains(bloodGroup)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "فصيلة الدم غير صحيحة.", "Invalid blood group.");
        }
    }

    private void validateMarital(String marital) {
        if (marital != null && !MARITAL.contains(marital)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الحالة الاجتماعية غير صحيحة.", "Invalid marital status.");
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
}
