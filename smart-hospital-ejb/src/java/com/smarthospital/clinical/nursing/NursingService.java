package com.smarthospital.clinical.nursing;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.clinical.encounter.EncounterServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.notify.OutboxServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.json.JsonUtil;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Local;
import jakarta.ejb.Stateless;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nursing ward work (FR-NUR-001..006).
 *
 * <p>Medication administration is the highest-risk action: the patient, drug
 * and batch barcodes must match before a GIVEN record is written, the batch is
 * locked with {@code FOR UPDATE}, and stock is decremented with a
 * {@code quantity_available >= ?} guard so a concurrent dispense can never
 * drive stock negative.
 */
@Stateless
@Local(NursingServiceLocal.class)
public class NursingService implements NursingServiceLocal {

    private static final Set<String> TASK_TYPES =
            Set.of("MEDICATION", "VITALS", "NOTE", "PROCEDURE", "OBSERVATION", "OTHER");
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "URGENT", "STAT");
    private static final Set<String> ADMIN_STATUSES = Set.of("GIVEN", "REFUSED", "HELD", "ERROR");
    private static final String DEFAULT_EWS =
            "{\"respiratory_rate\":{\"low\":8,\"high\":25},\"spo2\":{\"low\":92},"
                    + "\"systolic\":{\"low\":90,\"high\":180},\"heart_rate\":{\"low\":50,\"high\":120},"
                    + "\"temperature_c\":{\"low\":35.0,\"high\":38.5},\"risk_medium\":3,\"risk_high\":5}";

    @EJB
    private NursingRepository repository;

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
    // Tasks
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> searchTasks(String status, String taskType, String wardPublicId,
                                           String assignedToPublicId, String admissionPublicId,
                                           String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        Long assignedToId = null;
        if (assignedToPublicId != null && !assignedToPublicId.isBlank()) {
            Map<String, Object> user = repository.userByPublicId(assignedToPublicId);
            if (user == null) {
                throw AppException.notFound("USER_NOT_FOUND",
                        "المستخدم غير موجود.", "User not found.");
            }
            assignedToId = Sql.longValue(user, "id", 0);
        }
        Long admissionId = null;
        if (admissionPublicId != null && !admissionPublicId.isBlank()) {
            Map<String, Object> admission = repository.admissionByPublicId(admissionPublicId);
            if (admission == null) {
                throw AppException.notFound("ADMISSION_NOT_FOUND",
                        "التنويم غير موجود.", "Admission not found.");
            }
            admissionId = Sql.longValue(admission, "id", 0);
        }
        String statusFilter = normalize(status);
        String typeFilter = normalize(taskType);
        long total = repository.countTasks(statusFilter, typeFilter, wardPublicId, assignedToId,
                admissionId, query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.searchTasks(statusFilter, typeFilter, wardPublicId,
                assignedToId, admissionId, query, safePage * safeSize, safeSize)) {
            items.add(taskView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> task(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long admissionId = Sql.longValue(row, "admissionId", 0);
        Map<String, Object> result = taskView(row);
        if (admissionId > 0) {
            result.put("administrations", repository.administrationsForAdmission(admissionId));
        }
        return result;
    }

    @Override
    public Map<String, Object> createTask(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String taskType = ClinicalSupport.optional(body, "taskType");
        taskType = taskType == null ? "OTHER" : taskType.toUpperCase();
        if (!TASK_TYPES.contains(taskType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع المهمة غير صحيح.", "Invalid task type.");
        }
        String priority = ClinicalSupport.optional(body, "priority");
        priority = priority == null ? "NORMAL" : priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أولوية المهمة غير صحيحة.", "Invalid task priority.");
        }
        String title = ClinicalSupport.required(body, "title",
                "عنوان المهمة مطلوب.", "Task title is required.");
        if (title.length() > 200) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "عنوان المهمة أطول من الحد المسموح.",
                    "Task title exceeds 200 characters.");
        }
        String description = ClinicalSupport.optionalTrim(body, "description", 1000);

        Long patientId = null;
        Long encounterId = null;
        Long admissionId = null;
        Long wardId = null;
        Map<String, Object> admission = null;
        String admissionPublicId = ClinicalSupport.optional(body, "admissionPublicId");
        if (admissionPublicId != null) {
            admission = repository.admissionByPublicId(admissionPublicId);
            if (admission == null) {
                throw AppException.notFound("ADMISSION_NOT_FOUND",
                        "التنويم غير موجود.", "Admission not found.");
            }
            if (!Set.of("ADMITTED", "TRANSFERRED").contains(Sql.text(admission, "status"))) {
                throw AppException.conflict("ADMISSION_NOT_ACTIVE",
                        "لا يمكن إضافة مهمة لتنويم غير نشط.",
                        "Tasks can only be added to an active admission.");
            }
            patientId = Sql.longValue(admission, "patientId", 0);
            encounterId = Sql.longValue(admission, "encounterId");
            admissionId = Sql.longValue(admission, "id", 0);
            wardId = Sql.longValue(admission, "wardId");
        } else {
            String patientPublicId = ClinicalSupport.required(body, "patientPublicId",
                    "المريض مطلوب.", "Patient is required.");
            Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
            if (patient == null) {
                throw AppException.notFound("PATIENT_NOT_FOUND",
                        "المريض غير موجود.", "Patient not found.");
            }
            patientId = Sql.longValue(patient, "id", 0);
            String encounterPublicId = ClinicalSupport.optional(body, "encounterPublicId");
            if (encounterPublicId != null) {
                Map<String, Object> encounter = repository.encounterByPublicId(encounterPublicId);
                if (encounter == null
                        || Sql.longValue(encounter, "patientId", 0) != patientId) {
                    throw AppException.unprocessable("ENCOUNTER_PATIENT_MISMATCH",
                            "الزيارة لا تخص هذا المريض.",
                            "The encounter belongs to a different patient.");
                }
                encounterId = Sql.longValue(encounter, "id", 0);
            }
        }

        Long assignedTo = null;
        String assignedToPublicId = ClinicalSupport.optional(body, "assignedToPublicId");
        if (assignedToPublicId != null) {
            Map<String, Object> user = repository.userByPublicId(assignedToPublicId);
            if (user == null) {
                throw AppException.notFound("USER_NOT_FOUND",
                        "المستخدم غير موجود.", "User not found.");
            }
            assignedTo = Sql.longValue(user, "id", 0);
        }
        LocalDateTime dueAt = ClinicalSupport.dateTime(body, "dueAt", false);

        String publicId = Ulid.next();
        repository.insertTask(publicId, patientId, encounterId, admissionId, wardId, taskType,
                title, description, priority, dueAt, assignedTo, actor.getUserId());
        audit.record(actor.getUserId(), "NURSING_TASK_CREATED", "NURSING_TASK", publicId, null,
                ClinicalSupport.json("type", taskType, "title", title, "priority", priority,
                        "assignedTo", assignedToPublicId), null, ip);
        if (assignedTo != null) {
            outbox.enqueue("NURSING_TASK_ASSIGNED", "NURSING_TASK", publicId, ClinicalSupport.map(
                    "notifications", List.of(ClinicalSupport.map(
                            "users", List.of(assignedToPublicId),
                            "type", "NURSING_TASK_ASSIGNED",
                            "titleAr", "مهمة تمريضية جديدة",
                            "titleEn", "New nursing task",
                            "bodyAr", title,
                            "bodyEn", title,
                            "severity", "STAT".equals(priority) ? "CRITICAL" : "INFO",
                            "entityType", "NURSING_TASK",
                            "entityPublicId", publicId,
                            "dedupeKey", "NURSING_TASK_ASSIGNED:" + publicId))));
        }
        return taskView(repository.taskByPublicId(publicId));
    }

    @Override
    public Map<String, Object> updateTask(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long version = requireVersion(row, body);
        String title = ClinicalSupport.optional(body, "title");
        if (title == null) {
            title = Sql.text(row, "title");
        }
        if (title.length() > 200) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "عنوان المهمة أطول من الحد المسموح.",
                    "Task title exceeds 200 characters.");
        }
        String description = body.containsKey("description")
                ? ClinicalSupport.optionalTrim(body, "description", 1000)
                : Sql.text(row, "description");
        String priority = ClinicalSupport.optional(body, "priority");
        priority = priority == null ? Sql.text(row, "priority") : priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أولوية المهمة غير صحيحة.", "Invalid task priority.");
        }
        LocalDateTime dueAt = body.containsKey("dueAt")
                ? ClinicalSupport.dateTime(body, "dueAt", false)
                : parseDateTime(Sql.text(row, "dueAt"));
        Long assignedTo = Sql.longValue(row, "assignedTo");
        String assignedToPublicId = ClinicalSupport.optional(body, "assignedToPublicId");
        if (assignedToPublicId != null) {
            Map<String, Object> user = repository.userByPublicId(assignedToPublicId);
            if (user == null) {
                throw AppException.notFound("USER_NOT_FOUND",
                        "المستخدم غير موجود.", "User not found.");
            }
            assignedTo = Sql.longValue(user, "id", 0);
        }
        if (repository.updateTask(Sql.longValue(row, "id", 0), version, title, description, priority,
                dueAt, assignedTo, actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن تعديل مهمة مكتملة أو ملغاة.",
                    "A completed or cancelled task cannot be edited.");
        }
        audit.record(actor.getUserId(), "NURSING_TASK_UPDATED", "NURSING_TASK", publicId,
                ClinicalSupport.json("title", Sql.text(row, "title"),
                        "priority", Sql.text(row, "priority")),
                ClinicalSupport.json("title", title, "priority", priority,
                        "assignedTo", assignedToPublicId), null, ip);
        return taskView(repository.taskByPublicId(publicId));
    }

    @Override
    public Map<String, Object> assignTask(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long version = requireVersion(row, body);
        String assignedToPublicId = ClinicalSupport.required(body, "assignedToPublicId",
                "المستخدم المكلّف مطلوب.", "An assignee is required.");
        Map<String, Object> user = repository.userByPublicId(assignedToPublicId);
        if (user == null) {
            throw AppException.notFound("USER_NOT_FOUND",
                    "المستخدم غير موجود.", "User not found.");
        }
        if (repository.assignTask(Sql.longValue(row, "id", 0), version,
                Sql.longValue(user, "id", 0), actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إسناد مهمة مكتملة أو ملغاة.",
                    "A completed or cancelled task cannot be assigned.");
        }
        audit.record(actor.getUserId(), "NURSING_TASK_ASSIGNED", "NURSING_TASK", publicId,
                ClinicalSupport.json("assignedTo", row.get("assignedTo")),
                ClinicalSupport.json("assignedTo", assignedToPublicId), null, ip);
        outbox.enqueue("NURSING_TASK_ASSIGNED", "NURSING_TASK", publicId, ClinicalSupport.map(
                "notifications", List.of(ClinicalSupport.map(
                        "users", List.of(assignedToPublicId),
                        "type", "NURSING_TASK_ASSIGNED",
                        "titleAr", "تم إسناد مهمة إليك",
                        "titleEn", "A nursing task was assigned to you",
                        "bodyAr", Sql.text(row, "title"),
                        "bodyEn", Sql.text(row, "title"),
                        "severity", "INFO",
                        "entityType", "NURSING_TASK",
                        "entityPublicId", publicId,
                        "dedupeKey", "NURSING_TASK_ASSIGNED:" + publicId))));
        return taskView(repository.taskByPublicId(publicId));
    }

    @Override
    public Map<String, Object> startTask(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long version = requireVersion(row, body);
        if (repository.startTask(Sql.longValue(row, "id", 0), version, actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن بدء هذه المهمة في حالتها الحالية.",
                    "The task cannot be started from its current state.");
        }
        audit.record(actor.getUserId(), "NURSING_TASK_STARTED", "NURSING_TASK", publicId,
                ClinicalSupport.json("status", Sql.text(row, "status")),
                ClinicalSupport.json("status", "IN_PROGRESS"), null, ip);
        return taskView(repository.taskByPublicId(publicId));
    }

    @Override
    public Map<String, Object> completeTask(String publicId, Map<String, Object> body,
                                            AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long version = requireVersion(row, body);
        String note = ClinicalSupport.optionalTrim(body, "completionNote", 1000);
        if (repository.completeTask(Sql.longValue(row, "id", 0), version, note,
                actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إكمال مهمة مكتملة أو ملغاة.",
                    "A completed or cancelled task cannot be completed again.");
        }
        audit.record(actor.getUserId(), "NURSING_TASK_COMPLETED", "NURSING_TASK", publicId,
                ClinicalSupport.json("status", Sql.text(row, "status")),
                ClinicalSupport.json("status", "COMPLETED", "note", note), note, ip);
        return taskView(repository.taskByPublicId(publicId));
    }

    @Override
    public Map<String, Object> cancelTask(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireTask(publicId);
        long version = requireVersion(row, body);
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الإلغاء مطلوب.", "A cancellation reason is required.");
        if (repository.cancelTask(Sql.longValue(row, "id", 0), version, reason,
                actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إلغاء مهمة مكتملة أو ملغاة.",
                    "A completed or cancelled task cannot be cancelled again.");
        }
        audit.record(actor.getUserId(), "NURSING_TASK_CANCELLED", "NURSING_TASK", publicId,
                ClinicalSupport.json("status", Sql.text(row, "status")),
                ClinicalSupport.json("status", "CANCELLED"), reason, ip);
        return taskView(repository.taskByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Medication administration
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> administer(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String itemPublicId = ClinicalSupport.required(body, "prescriptionItemPublicId",
                "بند الوصفة مطلوب.", "The prescription item is required.");
        Map<String, Object> item = repository.prescriptionItemByPublicId(itemPublicId);
        if (item == null) {
            throw AppException.notFound("PRESCRIPTION_ITEM_NOT_FOUND",
                    "بند الوصفة غير موجود.", "Prescription item not found.");
        }
        if (!Set.of("ISSUED", "REVIEWED", "DISPENSED", "PARTIAL")
                .contains(Sql.text(item, "prescriptionStatus"))) {
            throw AppException.conflict("PRESCRIPTION_NOT_ACTIVE",
                    "الوصفة غير صالحة للإعطاء.", "The prescription is not active for administration.");
        }
        String status = ClinicalSupport.optional(body, "status");
        status = status == null ? "GIVEN" : status.toUpperCase();
        if (!ADMIN_STATUSES.contains(status)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة الإعطاء غير صحيحة.", "Invalid administration status.");
        }
        long patientId = Sql.longValue(item, "patientId", 0);
        long drugId = Sql.longValue(item, "drugId", 0);

        Map<String, Object> admission = null;
        String admissionPublicId = ClinicalSupport.optional(body, "admissionPublicId");
        if (admissionPublicId != null) {
            admission = repository.admissionByPublicId(admissionPublicId);
            if (admission == null) {
                throw AppException.notFound("ADMISSION_NOT_FOUND",
                        "التنويم غير موجود.", "Admission not found.");
            }
            if (Sql.longValue(admission, "patientId", 0) != patientId) {
                throw AppException.unprocessable("ADMISSION_PATIENT_MISMATCH",
                        "التنويم لا يخص صاحب الوصفة.",
                        "The admission belongs to a different patient.");
            }
        } else {
            admission = repository.activeAdmissionForPatient(patientId);
        }
        Long admissionId = admission == null ? null : Sql.longValue(admission, "id", 0);

        String barcodePatient = ClinicalSupport.optional(body, "barcodePatient");
        String barcodeDrug = ClinicalSupport.optional(body, "barcodeDrug");
        String barcodeBatch = ClinicalSupport.optional(body, "barcodeBatch");
        boolean verifiedPatient = matches(barcodePatient, Sql.text(item, "medicalNumber"),
                Sql.text(item, "patientPublicId"));
        boolean verifiedDrug = matches(barcodeDrug, Sql.text(item, "drugCode"),
                Sql.text(item, "drugPublicId"));
        boolean barcodeRequired = boolSetting("nur.med.barcode.required", true);
        if ("GIVEN".equals(status) && barcodeRequired
                && !(verifiedPatient && verifiedDrug)) {
            throw AppException.unprocessable("BARCODE_MISMATCH",
                    "فشل التحقق من باركود المريض أو الدواء.",
                    "Patient or drug barcode verification failed.");
        }

        Map<String, Object> batch;
        String batchPublicId = ClinicalSupport.optional(body, "batchPublicId");
        if (batchPublicId != null) {
            Map<String, Object> found = repository.batchByPublicId(batchPublicId);
            if (found == null) {
                throw AppException.notFound("BATCH_NOT_FOUND",
                        "الدفعة غير موجودة.", "Batch not found.");
            }
            batch = repository.batchByIdForUpdate(Sql.longValue(found, "id", 0));
        } else {
            List<Map<String, Object>> batches = repository.fefoBatches(drugId);
            batch = batches.isEmpty() ? null : batches.get(0);
        }
        if (batch == null) {
            throw AppException.conflict("NO_STOCK",
                    "لا يوجد مخزون صالح من هذا الدواء.",
                    "No valid stock is available for this drug.");
        }
        if (Sql.longValue(batch, "drugId", 0) != drugId) {
            throw AppException.unprocessable("BATCH_DRUG_MISMATCH",
                    "الدفعة لا تخص هذا الدواء.", "The batch belongs to a different drug.");
        }
        boolean verifiedBatch = matches(barcodeBatch, Sql.text(batch, "batchNumber"),
                Sql.text(batch, "publicId"));
        if ("GIVEN".equals(status) && barcodeRequired && !verifiedBatch) {
            throw AppException.unprocessable("BARCODE_MISMATCH",
                    "فشل التحقق من باركود الدفعة.",
                    "Batch barcode verification failed.");
        }

        String dose = ClinicalSupport.optional(body, "dose");
        dose = dose == null ? Sql.text(item, "dose") : dose;
        String route = ClinicalSupport.optional(body, "route");
        route = route == null ? Sql.text(item, "route") : route;
        if (dose == null || dose.isBlank() || route == null || route.isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الجرعة وطريق الإعطاء مطلوبان.",
                    "Dose and route are required.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);

        int administeredQuantity = 1;
        Long batchId = Sql.longValue(batch, "id", 0);
        long batchVersion = Sql.longValue(batch, "version", 0);
        if ("GIVEN".equals(status)) {
            int available = Sql.intValue(batch, "quantityAvailable", 0);
            if (available < administeredQuantity) {
                throw AppException.conflict("NO_STOCK",
                        "الكمية المتاحة في الدفعة لا تكفي.",
                        "The selected batch does not have enough stock.");
            }
        }

        String publicId = Ulid.next();
        repository.insertMedicationAdministration(publicId, patientId, admissionId,
                Sql.longValue(item, "id", 0), drugId, batchId, dose, route, status,
                barcodePatient, barcodeDrug, barcodeBatch, verifiedPatient, verifiedDrug,
                verifiedBatch, notes, actor.getUserId());

        Map<String, Object> movement = null;
        if ("GIVEN".equals(status)) {
            if (repository.decrementBatch(batchId, batchVersion, administeredQuantity,
                    actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّر مخزون الدفعة من مستخدم آخر. أعد المحاولة.",
                        "The batch stock changed by another user. Retry.");
            }
            if (Sql.intValue(batch, "quantityAvailable", 0) == administeredQuantity) {
                repository.depleteBatch(batchId, batchVersion + 1, actor.getUserId());
            }
            String movementPublicId = Ulid.next();
            repository.insertStockMovement(movementPublicId, drugId, batchId, "DISPENSE",
                    -administeredQuantity, "MEDICATION_ADMIN", publicId,
                    "Nursing administration", actor.getUserId());
            movement = ClinicalSupport.map("publicId", movementPublicId,
                    "quantityDelta", -administeredQuantity);
        }

        audit.record(actor.getUserId(), "MEDICATION_ADMINISTERED", "MEDICATION_ADMIN", publicId,
                null, ClinicalSupport.json("patient", Sql.text(item, "patientPublicId"),
                        "drug", Sql.text(item, "drugCode"), "batch", Sql.text(batch, "batchNumber"),
                        "status", status, "dose", dose, "route", route,
                        "verifiedPatient", verifiedPatient, "verifiedDrug", verifiedDrug,
                        "verifiedBatch", verifiedBatch), notes, ip);
        if ("ERROR".equals(status)) {
            outbox.enqueue("MEDICATION_ERROR", "MEDICATION_ADMIN", publicId, ClinicalSupport.map(
                    "notifications", List.of(ClinicalSupport.map(
                            "roles", List.of("DOCTOR", "NURSE"),
                            "type", "MEDICATION_ERROR",
                            "titleAr", "خطأ في إعطاء دواء",
                            "titleEn", "Medication administration error",
                            "bodyAr", Sql.text(item, "patientName") + " — "
                                    + Sql.text(item, "drugAr"),
                            "bodyEn", Sql.text(item, "patientName") + " — "
                                    + Sql.text(item, "drugEn"),
                            "severity", "CRITICAL",
                            "entityType", "MEDICATION_ADMIN",
                            "entityPublicId", publicId,
                            "dedupeKey", "MEDICATION_ERROR:" + publicId))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicId", publicId);
        result.put("status", status);
        result.put("dose", dose);
        result.put("route", route);
        result.put("patientPublicId", Sql.text(item, "patientPublicId"));
        result.put("patientName", Sql.text(item, "patientName"));
        result.put("drug", ClinicalSupport.map(
                "publicId", Sql.text(item, "drugPublicId"),
                "code", Sql.text(item, "drugCode"),
                "nameAr", Sql.text(item, "drugAr"),
                "nameEn", Sql.text(item, "drugEn")));
        result.put("batch", ClinicalSupport.map(
                "publicId", Sql.text(batch, "publicId"),
                "batchNumber", Sql.text(batch, "batchNumber"),
                "expiryDate", Sql.text(batch, "expiryDate"),
                "remaining", Sql.intValue(batch, "quantityAvailable", 0) - administeredQuantity));
        result.put("verified", ClinicalSupport.map(
                "patient", verifiedPatient, "drug", verifiedDrug, "batch", verifiedBatch));
        result.put("stockMovement", movement);
        result.put("admissionPublicId", admission == null ? null
                : Sql.text(admission, "publicId"));
        return result;
    }

    // ------------------------------------------------------------------
    // Vitals and EWS
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> recordVitals(String admissionPublicId, Map<String, Object> body,
                                            AuthPrincipal actor, String ip) {
        Map<String, Object> admission = requireAdmission(admissionPublicId);
        if (!Set.of("ADMITTED", "TRANSFERRED").contains(Sql.text(admission, "status"))) {
            throw AppException.conflict("ADMISSION_NOT_ACTIVE",
                    "لا يمكن تسجيل علامات حيوية لتنويم غير نشط.",
                    "Vitals can only be recorded for an active admission.");
        }
        String encounterPublicId = Sql.text(admission, "encounterPublicId");
        if (encounterPublicId == null) {
            throw AppException.conflict("ENCOUNTER_NOT_LINKED",
                    "التنويم غير مرتبط بزيارة طبية.",
                    "The admission is not linked to an encounter.");
        }
        Map<String, Object> vitals = encounters.addVitals(encounterPublicId, body, actor, ip);
        long encounterId = Sql.longValue(admission, "encounterId", 0);
        long patientId = Sql.longValue(admission, "patientId", 0);

        Map<String, Object> latest = repository.latestVitals(encounterId);
        Map<String, Object> ews = null;
        if (latest != null) {
            ews = calculateEws(encounterId, patientId, latest, actor);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admissionPublicId", admissionPublicId);
        result.put("vitals", vitals);
        result.put("ews", ews);
        return result;
    }

    private Map<String, Object> calculateEws(long encounterId, long patientId,
                                             Map<String, Object> vitals, AuthPrincipal actor) {
        JsonObject thresholds = readThresholds();
        List<Map<String, Object>> parameters = new ArrayList<>();
        int score = 0;
        score += evaluate(parameters, thresholds, "respiratory_rate", "respiratoryRate",
                number(vitals, "respiratoryRate"));
        score += evaluate(parameters, thresholds, "spo2", "spo2", number(vitals, "spo2"));
        score += evaluate(parameters, thresholds, "systolic", "systolic", number(vitals, "systolic"));
        score += evaluate(parameters, thresholds, "heart_rate", "heartRate",
                number(vitals, "heartRate"));
        score += evaluate(parameters, thresholds, "temperature_c", "temperatureC",
                number(vitals, "temperatureC"));

        int medium = intOr(thresholds, "risk_medium", 3);
        int high = intOr(thresholds, "risk_high", 5);
        String risk = score >= high ? "HIGH" : score >= medium ? "MEDIUM" : "LOW";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("score", score);
        payload.put("riskLevel", risk);
        payload.put("parameters", parameters);
        payload.put("vitalsRecordedAt", vitals.get("recordedAt"));
        String publicId = Ulid.next();
        repository.insertEws(publicId, encounterId, patientId, score, risk,
                JsonUtil.toJson(payload), actor.getUserId());
        audit.record(actor.getUserId(), "EWS_CALCULATED", "ENCOUNTER",
                String.valueOf(encounterId), null,
                ClinicalSupport.json("score", score, "risk", risk), null, null);
        if ("HIGH".equals(risk)) {
            outbox.enqueue("EWS_HIGH", "ENCOUNTER", String.valueOf(encounterId),
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "roles", List.of("DOCTOR", "NURSE"),
                            "type", "EWS_HIGH",
                            "titleAr", "إنذار مبكر مرتفع الخطورة",
                            "titleEn", "High-risk early warning score",
                            "bodyAr", "درجة EWS: " + score,
                            "bodyEn", "EWS score: " + score,
                            "severity", "CRITICAL",
                            "entityType", "ENCOUNTER",
                            "entityPublicId", String.valueOf(encounterId),
                            "dedupeKey", "EWS_HIGH:" + publicId))));
        }
        Map<String, Object> view = new LinkedHashMap<>(payload);
        view.put("publicId", publicId);
        view.put("calculatedAt", LocalDateTime.now().toString());
        return view;
    }

    private int evaluate(List<Map<String, Object>> parameters, JsonObject thresholds,
                         String thresholdKey, String field, Double value) {
        if (value == null) {
            return 0;
        }
        JsonObject bounds = thresholds.getJsonObject(thresholdKey);
        Double low = bounds == null || !bounds.containsKey("low") ? null : bounds.getJsonNumber("low").doubleValue();
        Double high = bounds == null || !bounds.containsKey("high") ? null : bounds.getJsonNumber("high").doubleValue();
        boolean abnormal = (low != null && value < low) || (high != null && value > high);
        parameters.add(ClinicalSupport.map("parameter", field, "value", value, "low", low,
                "high", high, "abnormal", abnormal));
        return abnormal ? 1 : 0;
    }

    private JsonObject readThresholds() {
        String raw = settings.findValue("nur.ews.thresholds").orElse(DEFAULT_EWS);
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            return reader.readObject();
        } catch (RuntimeException ex) {
            try (JsonReader reader = Json.createReader(new StringReader(DEFAULT_EWS))) {
                return reader.readObject();
            }
        }
    }

    private int intOr(JsonObject object, String key, int fallback) {
        if (object == null || !object.containsKey(key)) {
            return fallback;
        }
        try {
            return object.getInt(key);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Double number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return new BigDecimal(value.toString()).doubleValue();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public Map<String, Object> overview(String admissionPublicId, AuthPrincipal actor, String ip) {
        Map<String, Object> admission = requireAdmission(admissionPublicId);
        long admissionId = Sql.longValue(admission, "id", 0);
        long encounterId = Sql.longValue(admission, "encounterId", 0);
        long patientId = Sql.longValue(admission, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "ADMISSION", admissionPublicId, "READ", ip);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admission", ClinicalSupport.map(
                "publicId", admission.get("publicId"),
                "status", admission.get("status"),
                "admittedAt", admission.get("admittedAt"),
                "reason", admission.get("reason"),
                "ward", ClinicalSupport.map(
                        "publicId", admission.get("wardPublicId"),
                        "nameAr", admission.get("wardAr"),
                        "nameEn", admission.get("wardEn")),
                "bed", admission.get("bedPublicId") == null ? null : ClinicalSupport.map(
                        "publicId", admission.get("bedPublicId"),
                        "code", admission.get("bedCode")),
                "patient", ClinicalSupport.map(
                        "publicId", admission.get("patientPublicId"),
                        "medicalNumber", admission.get("medicalNumber"),
                        "fullName", admission.get("patientName"),
                        "gender", admission.get("patientGender"),
                        "birthDate", admission.get("patientBirthDate"),
                        "phone", admission.get("patientPhone")),
                "encounterPublicId", admission.get("encounterPublicId")));
        result.put("tasks", repository.tasksForAdmission(admissionId));
        result.put("administrations", repository.administrationsForAdmission(admissionId));
        if (encounterId > 0) {
            result.put("vitals", repository.vitalsHistory(encounterId, 50));
            result.put("ews", ewsView(repository.latestEws(encounterId)));
            result.put("ewsHistory", ewsViews(repository.ewsHistory(encounterId, 20)));
        } else {
            result.put("vitals", List.of());
            result.put("ews", null);
            result.put("ewsHistory", List.of());
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> ewsHistory(String admissionPublicId, AuthPrincipal actor,
                                                String ip) {
        Map<String, Object> admission = requireAdmission(admissionPublicId);
        long encounterId = Sql.longValue(admission, "encounterId", 0);
        long patientId = Sql.longValue(admission, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "ADMISSION", admissionPublicId, "READ", ip);
        return encounterId == 0 ? List.of() : ewsViews(repository.ewsHistory(encounterId, 50));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Map<String, Object>> ewsViews(List<Map<String, Object>> rows) {
        List<Map<String, Object>> views = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            views.add(ewsView(row));
        }
        return views;
    }

    private Map<String, Object> ewsView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("score", row.get("score"));
        view.put("riskLevel", row.get("riskLevel"));
        view.put("calculatedAt", row.get("calculatedAt"));
        view.put("calculatedBy", row.get("calculatedBy"));
        view.put("parameters", parameters(Sql.text(row, "parametersJson")));
        return view;
    }

    private List<Map<String, Object>> parameters(String json) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return parameters;
        }
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            JsonObject object = reader.readObject();
            jakarta.json.JsonArray array = object.getJsonArray("parameters");
            if (array == null) {
                return parameters;
            }
            for (jakarta.json.JsonValue value : array) {
                if (value.getValueType() != jakarta.json.JsonValue.ValueType.OBJECT) {
                    continue;
                }
                JsonObject item = value.asJsonObject();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("parameter", item.getString("parameter", null));
                entry.put("value", item.containsKey("value") && !item.isNull("value")
                        ? item.getJsonNumber("value").doubleValue() : null);
                entry.put("low", item.containsKey("low") && !item.isNull("low")
                        ? item.getJsonNumber("low").doubleValue() : null);
                entry.put("high", item.containsKey("high") && !item.isNull("high")
                        ? item.getJsonNumber("high").doubleValue() : null);
                entry.put("abnormal", item.getBoolean("abnormal", false));
                parameters.add(entry);
            }
        } catch (RuntimeException ex) {
            return parameters;
        }
        return parameters;
    }

    private Map<String, Object> requireTask(String publicId) {
        Map<String, Object> row = repository.taskByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("NURSING_TASK_NOT_FOUND",
                    "المهمة التمريضية غير موجودة.", "Nursing task not found.");
        }
        return row;
    }

    private Map<String, Object> requireAdmission(String publicId) {
        Map<String, Object> row = repository.admissionByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("ADMISSION_NOT_FOUND",
                    "التنويم غير موجود.", "Admission not found.");
        }
        return row;
    }

    private Map<String, Object> taskView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("taskType", row.get("taskType"));
        view.put("title", row.get("title"));
        view.put("description", row.get("description"));
        view.put("priority", row.get("priority"));
        view.put("status", row.get("status"));
        view.put("dueAt", row.get("dueAt"));
        view.put("assignedAt", row.get("assignedAt"));
        view.put("completedAt", row.get("completedAt"));
        view.put("completionNote", row.get("completionNote"));
        view.put("version", row.get("version"));
        view.put("assignedTo", row.get("assignedTo") == null ? null : ClinicalSupport.map(
                "publicId", row.get("assignedToPublicId"),
                "nameAr", row.get("assignedToAr"),
                "nameEn", row.get("assignedToEn")));
        view.put("completedBy", row.get("completedBy") == null ? null : ClinicalSupport.map(
                "nameAr", row.get("completedByAr"),
                "nameEn", row.get("completedByEn")));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender")));
        view.put("admissionPublicId", row.get("admissionPublicId"));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("ward", row.get("wardPublicId") == null ? null : ClinicalSupport.map(
                "publicId", row.get("wardPublicId"),
                "nameAr", row.get("wardAr"),
                "nameEn", row.get("wardEn")));
        view.put("bedCode", row.get("bedCode"));
        return view;
    }

    private boolean boolSetting(String key, boolean fallback) {
        return settings.findValue(key)
                .map(value -> "1".equals(value) || "true".equalsIgnoreCase(value))
                .orElse(fallback);
    }

    private static boolean matches(String barcode, String... candidates) {
        if (barcode == null || barcode.isBlank()) {
            return false;
        }
        String normalized = barcode.trim();
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
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

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
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
}
