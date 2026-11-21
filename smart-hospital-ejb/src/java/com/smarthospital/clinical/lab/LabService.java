package com.smarthospital.clinical.lab;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Laboratory workflow (FR-LAB-001..012).
 *
 * <p>Order state machine: ORDERED -> COLLECTED -> RECEIVED -> IN_PROCESS ->
 * RESULTED -> VERIFIED -> RELEASED, with REJECTED as a sample side exit and
 * CORRECTED for an amended released result. Verification and release are
 * guarded by {@code WHERE status = ... AND version = ?}, so the same result can
 * never be verified twice.
 */
@Stateless
@Local(LabServiceLocal.class)
public class LabService implements LabServiceLocal {

    private static final Set<String> SPECIMEN_TYPES =
            Set.of("BLOOD", "URINE", "STOOL", "SPUTUM", "SWAB", "CSF", "OTHER");
    private static final Set<String> PRIORITIES = Set.of("ROUTINE", "URGENT", "STAT");
    private static final Set<String> RESULT_TYPES = Set.of("NUMERIC", "TEXT");
    private static final List<String> RESULTED_STATUSES =
            List.of("RESULTED", "VERIFIED", "RELEASED");
    private static final List<String> VERIFIED_STATUSES = List.of("VERIFIED", "RELEASED");

    @EJB
    private LabRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @EJB
    private OutboxServiceLocal outbox;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> tests(String query, boolean activeOnly) {
        return repository.tests(query, activeOnly);
    }

    @Override
    public Map<String, Object> createTest(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String code = ClinicalSupport.required(body, "code",
                "رمز الفحص مطلوب.", "Test code is required.").toUpperCase();
        if (code.length() > 40) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "رمز الفحص أطول من الحد المسموح.",
                    "Test code exceeds 40 characters.");
        }
        if (repository.testByCode(code) != null) {
            throw AppException.conflict("TEST_CODE_EXISTS",
                    "رمز الفحص مستخدم بالفعل.", "Test code already exists.");
        }
        String nameAr = ClinicalSupport.required(body, "nameAr",
                "اسم الفحص بالعربية مطلوب.", "Arabic test name is required.");
        String nameEn = ClinicalSupport.optionalTrim(body, "nameEn", 200);
        String specimenType = ClinicalSupport.optional(body, "specimenType");
        specimenType = specimenType == null ? "BLOOD" : specimenType.toUpperCase();
        if (!SPECIMEN_TYPES.contains(specimenType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع العينة غير صحيح.", "Invalid specimen type.");
        }
        String resultType = ClinicalSupport.optional(body, "resultType");
        resultType = resultType == null ? "NUMERIC" : resultType.toUpperCase();
        if (!RESULT_TYPES.contains(resultType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع النتيجة غير صحيح.", "Invalid result type.");
        }
        BigDecimal refLow = decimal(body, "refLow");
        BigDecimal refHigh = decimal(body, "refHigh");
        if (refLow != null && refHigh != null && refLow.compareTo(refHigh) > 0) {
            throw AppException.unprocessable("INVALID_RANGE",
                    "الحد الأدنى أكبر من الحد الأعلى.",
                    "The lower reference bound is greater than the upper bound.");
        }
        BigDecimal criticalLow = decimal(body, "criticalLow");
        BigDecimal criticalHigh = decimal(body, "criticalHigh");
        if (criticalLow != null && criticalHigh != null && criticalLow.compareTo(criticalHigh) > 0) {
            throw AppException.unprocessable("INVALID_RANGE",
                    "الحد الحرج الأدنى أكبر من الحد الأعلى.",
                    "The lower critical bound is greater than the upper bound.");
        }
        int tat = ClinicalSupport.optionalInt(body, "targetTatMinutes") == null
                ? 120 : ClinicalSupport.optionalInt(body, "targetTatMinutes");
        if (tat <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "زمن الإنجاز يجب أن يكون أكبر من صفر.",
                    "Target turnaround time must be greater than zero.");
        }
        String publicId = Ulid.next();
        repository.insertTest(publicId, code, nameAr, nameEn, specimenType,
                ClinicalSupport.optionalTrim(body, "unit", 40), resultType, refLow, refHigh,
                ClinicalSupport.optionalTrim(body, "refText", 255), criticalLow, criticalHigh,
                tat, null, actor.getUserId());
        audit.record(actor.getUserId(), "LAB_TEST_CREATED", "LAB_TEST", publicId, null,
                ClinicalSupport.json("code", code, "nameAr", nameAr), null, ip);
        return repository.testByPublicId(publicId);
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> searchOrders(String status, String priority, String patientPublicId,
                                            String query, String date, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String statusFilter = normalize(status);
        String priorityFilter = normalize(priority);
        long total = repository.countOrders(statusFilter, priorityFilter, patientPublicId, query,
                date);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.searchOrders(statusFilter, priorityFilter,
                patientPublicId, query, date, safePage * safeSize, safeSize)) {
            items.add(orderView(row, false));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> createOrder(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String encounterPublicId = ClinicalSupport.required(body, "encounterPublicId",
                "الزيارة مطلوبة.", "Encounter is required.");
        Map<String, Object> encounter = repository.encounterByPublicId(encounterPublicId);
        if (encounter == null) {
            throw AppException.notFound("ENCOUNTER_NOT_FOUND",
                    "الزيارة غير موجودة.", "Encounter not found.");
        }
        if (!"OPEN".equals(Sql.text(encounter, "status"))) {
            throw AppException.conflict("ENCOUNTER_CLOSED",
                    "لا يمكن طلب فحوصات من زيارة مغلقة.",
                    "Lab tests cannot be ordered from a closed encounter.");
        }
        long patientId = Sql.longValue(encounter, "patientId", 0);
        String priority = ClinicalSupport.optional(body, "priority");
        priority = priority == null ? "ROUTINE" : priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أولوية الطلب غير صحيحة.", "Invalid order priority.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "clinicalNotes", 1000);
        Long serviceOrderId = null;
        String serviceOrderPublicId = ClinicalSupport.optional(body, "serviceOrderPublicId");
        if (serviceOrderPublicId != null) {
            Map<String, Object> serviceOrder = repository.serviceOrderByPublicId(serviceOrderPublicId);
            if (serviceOrder == null) {
                throw AppException.notFound("SERVICE_ORDER_NOT_FOUND",
                        "أمر الخدمة غير موجود.", "Service order not found.");
            }
            serviceOrderId = Sql.longValue(serviceOrder, "id", 0);
        }

        LinkedHashSet<Long> testIds = new LinkedHashSet<>();
        List<String> testLabels = new ArrayList<>();
        collectTestIds(body.get("items"), testIds, testLabels);
        collectTestIds(body.get("labTestPublicIds"), testIds, testLabels);
        if (testIds.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "اختر فحصًا واحدًا على الأقل.", "Select at least one lab test.");
        }

        String publicId = Ulid.next();
        long orderId = repository.insertOrder(publicId, Sql.longValue(encounter, "id", 0),
                patientId, actor.getUserId(), serviceOrderId, priority, notes);
        for (Long testId : testIds) {
            repository.insertOrderItem(Ulid.next(), orderId, testId);
        }
        audit.record(actor.getUserId(), "LAB_ORDER_CREATED", "LAB_ORDER", publicId, null,
                ClinicalSupport.json("patient", Sql.text(encounter, "patientPublicId"),
                        "priority", priority, "tests", testLabels), notes, ip);
        outbox.enqueue("LAB_ORDER_CREATED", "LAB_ORDER", publicId, ClinicalSupport.map(
                "notifications", List.of(ClinicalSupport.map(
                        "roles", List.of("LAB_TECH", "LAB_SUPERVISOR"),
                        "type", "LAB_ORDER_CREATED",
                        "titleAr", "طلب مختبر جديد",
                        "titleEn", "New laboratory order",
                        "bodyAr", Sql.text(encounter, "patientName") + " — "
                                + String.join(", ", testLabels),
                        "bodyEn", Sql.text(encounter, "patientName") + " — "
                                + String.join(", ", testLabels),
                        "severity", "STAT".equals(priority) ? "CRITICAL" : "INFO",
                        "entityType", "LAB_ORDER",
                        "entityPublicId", publicId,
                        "dedupeKey", "LAB_ORDER_CREATED:" + publicId))));
        return order(publicId, actor, ip);
    }

    @Override
    public Map<String, Object> order(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireOrder(publicId);
        long orderId = Sql.longValue(row, "id", 0);
        long patientId = Sql.longValue(row, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "LAB_ORDER", publicId, "READ", ip);
        Map<String, Object> result = orderView(row, true);
        List<Map<String, Object>> specimens = new ArrayList<>();
        for (Map<String, Object> specimen : repository.specimensForOrder(orderId)) {
            Map<String, Object> view = new LinkedHashMap<>(specimen);
            view.put("events", repository.sampleEvents(Sql.longValue(specimen, "id", 0)));
            specimens.add(view);
        }
        result.put("specimens", specimens);
        result.put("results", resultViews(repository.resultsForOrder(orderId)));
        return result;
    }

    // ------------------------------------------------------------------
    // Specimen handling
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> collect(String orderPublicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip) {
        Map<String, Object> order = requireOrder(orderPublicId);
        long orderId = Sql.longValue(order, "id", 0);
        long version = requireVersion(order, body);
        String status = Sql.text(order, "status");
        if (!Set.of("ORDERED", "REJECTED").contains(status)) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن سحب عينة لطلب في هذه الحالة.",
                    "A specimen cannot be collected for an order in this state.");
        }
        List<Map<String, Object>> items = repository.orderItems(orderId);
        String specimenType = ClinicalSupport.optional(body, "specimenType");
        if (specimenType == null) {
            specimenType = items.isEmpty() ? "OTHER" : Sql.text(items.get(0), "specimenType");
        }
        specimenType = specimenType.toUpperCase();
        if (!SPECIMEN_TYPES.contains(specimenType)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع العينة غير صحيح.", "Invalid specimen type.");
        }
        String barcode = ClinicalSupport.optional(body, "barcode");
        if (barcode == null) {
            barcode = "SPC-" + Ulid.next();
        }
        if (barcode.length() > 80) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "باركود العينة أطول من الحد المسموح.",
                    "Specimen barcode exceeds 80 characters.");
        }
        if (repository.specimenByBarcode(barcode) != null) {
            throw AppException.conflict("BARCODE_EXISTS",
                    "باركود العينة مستخدم بالفعل.",
                    "This specimen barcode is already in use.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);

        String specimenPublicId = Ulid.next();
        long specimenId = repository.insertSpecimen(specimenPublicId, orderId, barcode,
                specimenType, actor.getUserId());
        if (repository.setOrderStatusFrom(orderId, version, "COLLECTED",
                List.of("ORDERED", "REJECTED"), actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الطلب من مستخدم آخر. أعد التحميل.",
                    "The order changed by another user. Reload and retry.");
        }
        repository.updateItemsStatusFrom(orderId, "COLLECTED", List.of("ORDERED", "REJECTED"));
        repository.insertSampleEvent(specimenId, null, "COLLECTED", actor.getUserId(), notes);
        audit.record(actor.getUserId(), "LAB_SPECIMEN_COLLECTED", "LAB_ORDER", orderPublicId,
                ClinicalSupport.json("status", status),
                ClinicalSupport.json("status", "COLLECTED", "specimen", specimenPublicId,
                        "barcode", barcode), notes, ip);
        return order(orderPublicId, actor, ip);
    }

    @Override
    public Map<String, Object> receiveSpecimen(String specimenPublicId, Map<String, Object> body,
                                               AuthPrincipal actor, String ip) {
        Map<String, Object> specimen = requireSpecimen(specimenPublicId);
        long specimenId = Sql.longValue(specimen, "id", 0);
        long version = requireVersion(specimen, body);
        if (!"COLLECTED".equals(Sql.text(specimen, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن استلام عينة في هذه الحالة.",
                    "The specimen cannot be received in its current state.");
        }
        if (repository.receiveSpecimen(specimenId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل العينة من مستخدم آخر. أعد التحميل.",
                    "The specimen changed by another user. Reload and retry.");
        }
        long orderId = Sql.longValue(specimen, "labOrderId", 0);
        Map<String, Object> order = repository.orderById(orderId);
        if (order != null && "COLLECTED".equals(Sql.text(order, "status"))) {
            repository.setOrderStatusFrom(orderId, Sql.longValue(order, "version", 0), "RECEIVED",
                    List.of("COLLECTED"), actor.getUserId());
            repository.updateItemsStatusFrom(orderId, "RECEIVED", List.of("COLLECTED"));
        }
        repository.insertSampleEvent(specimenId, "COLLECTED", "RECEIVED", actor.getUserId(),
                ClinicalSupport.optionalTrim(body, "notes", 500));
        audit.record(actor.getUserId(), "LAB_SPECIMEN_RECEIVED", "LAB_SPECIMEN",
                specimenPublicId, ClinicalSupport.json("status", "COLLECTED"),
                ClinicalSupport.json("status", "RECEIVED"), null, ip);
        return order(Sql.text(specimen, "orderPublicId"), actor, ip);
    }

    @Override
    public Map<String, Object> rejectSpecimen(String specimenPublicId, Map<String, Object> body,
                                              AuthPrincipal actor, String ip) {
        Map<String, Object> specimen = requireSpecimen(specimenPublicId);
        long specimenId = Sql.longValue(specimen, "id", 0);
        long version = requireVersion(specimen, body);
        if (!"COLLECTED".equals(Sql.text(specimen, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن رفض عينة في هذه الحالة.",
                    "The specimen cannot be rejected in its current state.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب رفض العينة مطلوب.", "A rejection reason is required.");
        if (repository.rejectSpecimen(specimenId, version, actor.getUserId(), reason) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل العينة من مستخدم آخر. أعد التحميل.",
                    "The specimen changed by another user. Reload and retry.");
        }
        long orderId = Sql.longValue(specimen, "labOrderId", 0);
        Map<String, Object> order = repository.orderById(orderId);
        if (order != null && Set.of("ORDERED", "COLLECTED").contains(Sql.text(order, "status"))) {
            repository.setOrderStatusFrom(orderId, Sql.longValue(order, "version", 0), "REJECTED",
                    List.of("ORDERED", "COLLECTED"), actor.getUserId());
            repository.updateItemsStatus(orderId, "REJECTED");
        }
        repository.insertSampleEvent(specimenId, "COLLECTED", "REJECTED", actor.getUserId(), reason);
        audit.record(actor.getUserId(), "LAB_SPECIMEN_REJECTED", "LAB_SPECIMEN",
                specimenPublicId, ClinicalSupport.json("status", "COLLECTED"),
                ClinicalSupport.json("status", "REJECTED"), reason, ip);
        outbox.enqueue("LAB_SPECIMEN_REJECTED", "LAB_ORDER", Sql.text(specimen, "orderPublicId"),
                ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                        "roles", List.of("LAB_TECH", "LAB_SUPERVISOR", "DOCTOR"),
                        "type", "LAB_SPECIMEN_REJECTED",
                        "titleAr", "رفض عينة مختبر",
                        "titleEn", "Laboratory specimen rejected",
                        "bodyAr", Sql.text(specimen, "patientName") + " — " + reason,
                        "bodyEn", Sql.text(specimen, "patientName") + " — " + reason,
                        "severity", "WARNING",
                        "entityType", "LAB_ORDER",
                        "entityPublicId", Sql.text(specimen, "orderPublicId"),
                        "dedupeKey", "LAB_SPECIMEN_REJECTED:" + specimenPublicId))));
        return order(Sql.text(specimen, "orderPublicId"), actor, ip);
    }

    @Override
    public Map<String, Object> startProcessing(String orderPublicId, Map<String, Object> body,
                                               AuthPrincipal actor, String ip) {
        Map<String, Object> order = requireOrder(orderPublicId);
        long orderId = Sql.longValue(order, "id", 0);
        long version = requireVersion(order, body);
        if (repository.setOrderStatusFrom(orderId, version, "IN_PROCESS", List.of("RECEIVED"),
                actor.getUserId()) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن بدء المعالجة في هذه الحالة.",
                    "Processing cannot start from the current state.");
        }
        repository.updateItemsStatusFrom(orderId, "IN_PROCESS", List.of("RECEIVED"));
        audit.record(actor.getUserId(), "LAB_PROCESSING_STARTED", "LAB_ORDER", orderPublicId,
                ClinicalSupport.json("status", "RECEIVED"),
                ClinicalSupport.json("status", "IN_PROCESS"), null, ip);
        return order(orderPublicId, actor, ip);
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> enterResult(String orderPublicId, Map<String, Object> body,
                                           AuthPrincipal actor, String ip) {
        Map<String, Object> order = requireOrder(orderPublicId);
        long orderId = Sql.longValue(order, "id", 0);
        long orderVersion = Sql.longValue(order, "version", 0);
        String orderStatus = Sql.text(order, "status");
        boolean editable = Set.of("RECEIVED", "IN_PROCESS", "RESULTED").contains(orderStatus);
        if (!editable && !Set.of("VERIFIED", "RELEASED").contains(orderStatus)) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إدخال نتيجة قبل استلام العينة.",
                    "A result cannot be entered before the specimen is received.");
        }
        Map<String, Object> item = resolveItem(orderId, body);
        long itemId = Sql.longValue(item, "id", 0);
        long testId = Sql.longValue(item, "labTestId", 0);
        long patientId = Sql.longValue(order, "patientId", 0);
        String resultType = Sql.text(item, "resultType");
        BigDecimal valueNumeric = decimal(body, "valueNumeric");
        String valueText = ClinicalSupport.optionalTrim(body, "valueText", 500);
        if ("NUMERIC".equals(resultType) && valueNumeric == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية مطلوبة لهذا الفحص.", "A numeric value is required for this test.");
        }
        if ("TEXT".equals(resultType) && valueText == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة نصية مطلوبة لهذا الفحص.", "A text value is required for this test.");
        }
        String comment = ClinicalSupport.optionalTrim(body, "comment", 1000);
        String flag = flagFor(item, valueNumeric, valueText);
        boolean critical = flag.startsWith("CRITICAL");

        Map<String, Object> existing = repository.resultByItem(itemId);
        if (existing != null) {
            String existingStatus = Sql.text(existing, "status");
            if (Set.of("VERIFIED", "RELEASED").contains(existingStatus)) {
                throw AppException.conflict("RESULT_VERIFIED",
                        "النتيجة معتمدة. استخدم التصحيح (Amendment).",
                        "The result is verified. Use the amendment endpoint instead.");
            }
        }
        if (!editable) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إدخال نتيجة قبل استلام العينة.",
                    "A result cannot be entered before the specimen is received.");
        }
        if ("RECEIVED".equals(orderStatus)
                && repository.setOrderStatusFrom(orderId, orderVersion, "IN_PROCESS",
                List.of("RECEIVED"), actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الطلب من مستخدم آخر. أعد التحميل.",
                    "The order changed by another user. Reload and retry.");
        }

        long resultId;
        String resultPublicId;
        if (existing == null) {
            resultPublicId = Ulid.next();
            resultId = repository.insertResult(resultPublicId, orderId, itemId, testId, patientId,
                    valueNumeric, valueText, Sql.text(item, "unit"),
                    Sql.decimal(item, "refLow"), Sql.decimal(item, "refHigh"),
                    Sql.text(item, "refText"), flag, critical, comment, actor.getUserId());
        } else {
            resultId = Sql.longValue(existing, "id", 0);
            resultPublicId = Sql.text(existing, "publicId");
            if (repository.updateResult(resultId, Sql.longValue(existing, "version", 0),
                    valueNumeric, valueText, flag, critical, comment, actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تم تعديل النتيجة من مستخدم آخر. أعد التحميل.",
                        "The result changed by another user. Reload and retry.");
            }
        }
        long versionId = repository.insertResultVersion(Ulid.next(), resultId,
                repository.maxResultVersion(resultId) + 1, valueNumeric, valueText, flag, comment,
                null, null, actor.getUserId());
        repository.setResultCurrentVersion(resultId, versionId, actor.getUserId());
        repository.updateItemStatus(itemId, "RESULTED", resultId);

        boolean newlyCritical = critical
                && (existing == null || !Sql.boolValue(existing, "criticalFlag"));
        if (newlyCritical) {
            String alertPublicId = Ulid.next();
            repository.insertCriticalAlert(alertPublicId, resultId, patientId,
                    "Critical value detected on result entry");
            outbox.enqueue("LAB_CRITICAL_VALUE", "LAB_RESULT", resultPublicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "users", order.get("orderedByPublicId") == null ? List.of()
                                    : List.of(Sql.text(order, "orderedByPublicId")),
                            "roles", List.of("LAB_SUPERVISOR", "DOCTOR"),
                            "type", "LAB_CRITICAL_VALUE",
                            "titleAr", "قيمة مختبرية حرجة",
                            "titleEn", "Critical laboratory value",
                            "bodyAr", Sql.text(order, "patientName") + " — "
                                    + Sql.text(item, "testNameAr") + ": "
                                    + (valueNumeric == null ? valueText : valueNumeric.toPlainString()),
                            "bodyEn", Sql.text(order, "patientName") + " — "
                                    + Sql.text(item, "testNameEn") + ": "
                                    + (valueNumeric == null ? valueText : valueNumeric.toPlainString()),
                            "severity", "CRITICAL",
                            "entityType", "LAB_RESULT",
                            "entityPublicId", resultPublicId,
                            "dedupeKey", "LAB_CRITICAL_VALUE:" + resultPublicId))));
        }
        refreshResultedStatus(orderId);
        audit.record(actor.getUserId(), "LAB_RESULT_ENTERED", "LAB_ORDER", orderPublicId, null,
                ClinicalSupport.json("item", Sql.text(item, "publicId"),
                        "test", Sql.text(item, "testCode"), "flag", flag,
                        "critical", critical), comment, ip);
        return order(orderPublicId, actor, ip);
    }

    @Override
    public Map<String, Object> verify(String resultPublicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> result = requireResult(resultPublicId);
        long resultId = Sql.longValue(result, "id", 0);
        long version = requireVersion(result, body);
        if (!Set.of("RESULTED", "CORRECTED").contains(Sql.text(result, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن اعتماد نتيجة في هذه الحالة.",
                    "The result cannot be verified in its current state.");
        }
        if (repository.verifyResult(resultId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("ALREADY_VERIFIED",
                    "تم اعتماد النتيجة من مستخدم آخر.",
                    "The result was already verified by another user.");
        }
        repository.updateItemStatus(Sql.longValue(result, "labOrderItemId", 0), "VERIFIED", null);
        refreshVerifiedStatus(Sql.longValue(result, "labOrderId", 0));

        if (Sql.boolValue(result, "criticalFlag")) {
            Map<String, Object> order = repository.orderById(Sql.longValue(result, "labOrderId", 0));
            outbox.enqueue("LAB_CRITICAL_VALUE_VERIFIED", "LAB_RESULT", resultPublicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "users", order == null || order.get("orderedByPublicId") == null ? List.of()
                                    : List.of(Sql.text(order, "orderedByPublicId")),
                            "roles", List.of("LAB_SUPERVISOR", "DOCTOR"),
                            "type", "LAB_CRITICAL_VALUE_VERIFIED",
                            "titleAr", "اعتماد قيمة مختبرية حرجة",
                            "titleEn", "Critical laboratory value verified",
                            "bodyAr", Sql.text(result, "patientName") + " — "
                                    + Sql.text(result, "testNameAr"),
                            "bodyEn", Sql.text(result, "patientName") + " — "
                                    + Sql.text(result, "testNameEn"),
                            "severity", "CRITICAL",
                            "entityType", "LAB_RESULT",
                            "entityPublicId", resultPublicId,
                            "dedupeKey", "LAB_CRITICAL_VERIFIED:" + resultPublicId))));
        }
        audit.record(actor.getUserId(), "LAB_RESULT_VERIFIED", "LAB_RESULT", resultPublicId,
                ClinicalSupport.json("status", Sql.text(result, "status")),
                ClinicalSupport.json("status", "VERIFIED"), null, ip);
        return resultView(repository.resultByPublicId(resultPublicId));
    }

    @Override
    public Map<String, Object> release(String resultPublicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip) {
        Map<String, Object> result = requireResult(resultPublicId);
        long resultId = Sql.longValue(result, "id", 0);
        long version = requireVersion(result, body);
        if (!"VERIFIED".equals(Sql.text(result, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إصدار نتيجة غير معتمدة.",
                    "Only a verified result can be released.");
        }
        if (repository.releaseResult(resultId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل النتيجة من مستخدم آخر. أعد التحميل.",
                    "The result changed by another user. Reload and retry.");
        }
        repository.updateItemStatus(Sql.longValue(result, "labOrderItemId", 0), "RELEASED", null);
        refreshReleasedStatus(Sql.longValue(result, "labOrderId", 0));
        Map<String, Object> order = repository.orderById(Sql.longValue(result, "labOrderId", 0));
        outbox.enqueue("LAB_RESULT_RELEASED", "LAB_RESULT", resultPublicId,
                ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                        "users", order == null || order.get("orderedByPublicId") == null ? List.of()
                                : List.of(Sql.text(order, "orderedByPublicId")),
                        "type", "LAB_RESULT_RELEASED",
                        "titleAr", "صدور نتيجة مختبرية",
                        "titleEn", "Laboratory result released",
                        "bodyAr", Sql.text(result, "patientName") + " — "
                                + Sql.text(result, "testNameAr"),
                        "bodyEn", Sql.text(result, "patientName") + " — "
                                + Sql.text(result, "testNameEn"),
                        "severity", "INFO",
                        "entityType", "LAB_RESULT",
                        "entityPublicId", resultPublicId,
                        "dedupeKey", "LAB_RESULT_RELEASED:" + resultPublicId))));
        audit.record(actor.getUserId(), "LAB_RESULT_RELEASED", "LAB_RESULT", resultPublicId,
                ClinicalSupport.json("status", "VERIFIED"),
                ClinicalSupport.json("status", "RELEASED"), null, ip);
        return resultView(repository.resultByPublicId(resultPublicId));
    }

    @Override
    public Map<String, Object> amend(String resultPublicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip) {
        Map<String, Object> result = requireResult(resultPublicId);
        long resultId = Sql.longValue(result, "id", 0);
        long version = requireVersion(result, body);
        if (!Set.of("VERIFIED", "RELEASED").contains(Sql.text(result, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن تصحيح نتيجة غير معتمدة.",
                    "Only a verified or released result can be amended.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب التصحيح مطلوب.", "An amendment reason is required.");
        BigDecimal valueNumeric = decimal(body, "valueNumeric");
        String valueText = ClinicalSupport.optionalTrim(body, "valueText", 500);
        if (valueNumeric == null && valueText == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "القيمة الجديدة مطلوبة.", "A new value is required.");
        }
        String comment = ClinicalSupport.optionalTrim(body, "comment", 1000);
        Map<String, Object> thresholds = ClinicalSupport.map(
                "resultType", result.get("resultType"),
                "refLow", result.get("testRefLow"),
                "refHigh", result.get("testRefHigh"),
                "refText", result.get("testRefText"),
                "criticalLow", result.get("testCriticalLow"),
                "criticalHigh", result.get("testCriticalHigh"));
        String flag = flagFor(thresholds, valueNumeric, valueText);
        boolean critical = flag.startsWith("CRITICAL");

        if (repository.markCorrected(resultId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل النتيجة من مستخدم آخر. أعد التحميل.",
                    "The result changed by another user. Reload and retry.");
        }
        long versionId = repository.insertResultVersion(Ulid.next(), resultId,
                repository.maxResultVersion(resultId) + 1, valueNumeric, valueText, flag, comment,
                Sql.longValue(result, "currentVersionId"), reason, actor.getUserId());
        repository.setResultCurrentVersion(resultId, versionId, actor.getUserId());
        repository.updateResult(resultId, version + 1, valueNumeric, valueText, flag, critical,
                comment, actor.getUserId());
        repository.updateItemStatus(Sql.longValue(result, "labOrderItemId", 0), "CORRECTED", null);
        Map<String, Object> order = repository.orderById(Sql.longValue(result, "labOrderId", 0));
        if (order != null) {
            repository.setOrderStatusFrom(Sql.longValue(order, "id", 0),
                    Sql.longValue(order, "version", 0), "CORRECTED",
                    List.of("RELEASED", "VERIFIED", "RESULTED"), actor.getUserId());
        }
        audit.record(actor.getUserId(), "LAB_RESULT_AMENDED", "LAB_RESULT", resultPublicId,
                ClinicalSupport.json("status", Sql.text(result, "status"),
                        "valueNumeric", result.get("valueNumeric"),
                        "valueText", result.get("valueText")),
                ClinicalSupport.json("status", "CORRECTED", "valueNumeric", valueNumeric,
                        "valueText", valueText, "flag", flag), reason, ip);
        return resultView(repository.resultByPublicId(resultPublicId));
    }

    // ------------------------------------------------------------------
    // Worklist, reports and alerts
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> worklist(String priority, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.worklist(normalize(priority),
                safePage * safeSize, safeSize)) {
            items.add(orderView(row, false));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : repository.countByStatus()) {
            counts.put(Sql.text(row, "status"), Sql.longValue(row, "total", 0));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("counts", counts);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("generatedAt", LocalDateTime.now().toString());
        return result;
    }

    @Override
    public Map<String, Object> report(String resultPublicId, AuthPrincipal actor, String ip) {
        Map<String, Object> result = requireResult(resultPublicId);
        return buildReport(Sql.text(result, "orderPublicId"), resultPublicId, actor, ip);
    }

    @Override
    public Map<String, Object> orderReport(String orderPublicId, AuthPrincipal actor, String ip) {
        return buildReport(orderPublicId, null, actor, ip);
    }

    private Map<String, Object> buildReport(String orderPublicId, String focusResultPublicId,
                                            AuthPrincipal actor, String ip) {
        Map<String, Object> order = requireOrder(orderPublicId);
        long orderId = Sql.longValue(order, "id", 0);
        long patientId = Sql.longValue(order, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "LAB_ORDER", orderPublicId, "PRINT", ip);
        List<Map<String, Object>> results = resultViews(repository.resultsForOrder(orderId));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("nameAr", settings.findValue("hospital.name_ar").orElse("المستشفى"));
        header.put("nameEn", settings.findValue("hospital.name_en").orElse("Hospital"));
        header.put("reportTitleAr", "تقرير نتائج المختبر");
        header.put("reportTitleEn", "Laboratory Result Report");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("header", header);
        result.put("order", orderView(order, false));
        result.put("patient", ClinicalSupport.map(
                "publicId", order.get("patientPublicId"),
                "medicalNumber", order.get("medicalNumber"),
                "fullName", order.get("patientName"),
                "gender", order.get("patientGender"),
                "birthDate", order.get("patientBirthDate"),
                "phone", order.get("patientPhone")));
        result.put("results", results);
        result.put("focusResultPublicId", focusResultPublicId);
        result.put("printedAt", LocalDateTime.now().toString());
        result.put("printedBy", ClinicalSupport.map(
                "publicId", actor.getPublicId(),
                "nameAr", actor.getDisplayNameAr(),
                "nameEn", actor.getDisplayNameEn()));
        return result;
    }

    @Override
    public Map<String, Object> alerts(String status, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> items = repository.criticalAlerts(normalize(status), safeLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("count", items.size());
        return result;
    }

    @Override
    public Map<String, Object> acknowledgeAlert(String alertPublicId, Map<String, Object> body,
                                                AuthPrincipal actor, String ip) {
        Map<String, Object> alert = repository.alertByPublicId(alertPublicId);
        if (alert == null) {
            throw AppException.notFound("ALERT_NOT_FOUND",
                    "تنبيه القيمة الحرجة غير موجود.", "Critical value alert not found.");
        }
        long version = requireVersion(alert, body);
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        if (repository.acknowledgeAlert(Sql.longValue(alert, "id", 0), version, actor.getUserId(),
                notes) == 0) {
            throw AppException.conflict("INVALID_STATE",
                    "تمت معالجة التنبيه بالفعل.",
                    "The alert was already acknowledged.");
        }
        audit.record(actor.getUserId(), "CRITICAL_ALERT_ACKNOWLEDGED", "CRITICAL_ALERT",
                alertPublicId, ClinicalSupport.json("status", Sql.text(alert, "status")),
                ClinicalSupport.json("status", "ACKNOWLEDGED"), notes, ip);
        return ClinicalSupport.map("publicId", alertPublicId, "status", "ACKNOWLEDGED");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requireOrder(String publicId) {
        Map<String, Object> row = repository.orderByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("LAB_ORDER_NOT_FOUND",
                    "طلب المختبر غير موجود.", "Laboratory order not found.");
        }
        return row;
    }

    private Map<String, Object> requireSpecimen(String publicId) {
        Map<String, Object> row = repository.specimenByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("SPECIMEN_NOT_FOUND",
                    "العينة غير موجودة.", "Specimen not found.");
        }
        return row;
    }

    private Map<String, Object> requireResult(String publicId) {
        Map<String, Object> row = repository.resultByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("LAB_RESULT_NOT_FOUND",
                    "نتيجة المختبر غير موجودة.", "Laboratory result not found.");
        }
        return row;
    }

    private Map<String, Object> resolveItem(long orderId, Map<String, Object> body) {
        String itemPublicId = ClinicalSupport.optional(body, "itemPublicId");
        if (itemPublicId != null) {
            Map<String, Object> item = repository.itemByPublicId(itemPublicId);
            if (item == null || Sql.longValue(item, "labOrderId", 0) != orderId) {
                throw AppException.notFound("LAB_ITEM_NOT_FOUND",
                        "بند الفحص غير موجود في هذا الطلب.",
                        "The test item does not belong to this order.");
            }
            return item;
        }
        Long testId = ClinicalSupport.optionalLong(body, "labTestId");
        String testCode = ClinicalSupport.optional(body, "testCode");
        if (testId == null && testCode != null) {
            Map<String, Object> test = repository.testByCode(testCode.toUpperCase());
            if (test != null) {
                testId = Sql.longValue(test, "id", 0);
            }
        }
        if (testId == null) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "بند الفحص أو رمز الفحص مطلوب.",
                    "A test item or test code is required.");
        }
        Map<String, Object> item = repository.itemByOrderAndTest(orderId, testId);
        if (item == null) {
            throw AppException.notFound("LAB_ITEM_NOT_FOUND",
                    "الفحص ليس ضمن هذا الطلب.", "The test is not part of this order.");
        }
        return repository.itemByPublicId(Sql.text(item, "publicId"));
    }

    private void collectTestIds(Object raw, LinkedHashSet<Long> ids, List<String> labels) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            String publicId = null;
            String code = null;
            if (entry instanceof Map<?, ?> map) {
                Object idValue = map.get("labTestPublicId");
                Object codeValue = map.get("code");
                publicId = idValue == null ? null : idValue.toString();
                code = codeValue == null ? null : codeValue.toString();
            } else if (entry != null) {
                String value = entry.toString().trim();
                if (value.length() == 26) {
                    publicId = value;
                } else {
                    code = value;
                }
            }
            Map<String, Object> test = null;
            if (publicId != null && !publicId.isBlank()) {
                test = repository.testByPublicId(publicId);
            } else if (code != null && !code.isBlank()) {
                test = repository.testByCode(code.toUpperCase());
            }
            if (test == null) {
                throw AppException.notFound("LAB_TEST_NOT_FOUND",
                        "الفحص المطلوب غير موجود: " + (publicId != null ? publicId : code),
                        "Requested lab test not found: " + (publicId != null ? publicId : code));
            }
            if (!Sql.boolValue(test, "active")) {
                throw AppException.conflict("LAB_TEST_INACTIVE",
                        "الفحص غير مفعّل: " + Sql.text(test, "code"),
                        "The lab test is inactive: " + Sql.text(test, "code"));
            }
            if (ids.add(Sql.longValue(test, "id", 0))) {
                labels.add(Sql.text(test, "code"));
            }
        }
    }

    private void refreshResultedStatus(long orderId) {
        Map<String, Object> order = repository.orderById(orderId);
        if (order == null || !"IN_PROCESS".equals(Sql.text(order, "status"))) {
            return;
        }
        long items = repository.countItems(orderId);
        long results = repository.countResultsWithStatus(orderId, RESULTED_STATUSES);
        if (items > 0 && results >= items) {
            repository.setOrderStatusFrom(orderId, Sql.longValue(order, "version", 0), "RESULTED",
                    List.of("IN_PROCESS"), Sql.longValue(order, "orderedBy", 0));
        }
    }

    private void refreshVerifiedStatus(long orderId) {
        Map<String, Object> order = repository.orderById(orderId);
        if (order == null || !Set.of("RESULTED", "CORRECTED").contains(Sql.text(order, "status"))) {
            return;
        }
        long items = repository.countItems(orderId);
        long verified = repository.countResultsWithStatus(orderId, VERIFIED_STATUSES);
        if (items > 0 && verified >= items) {
            repository.setOrderStatusFrom(orderId, Sql.longValue(order, "version", 0), "VERIFIED",
                    List.of("RESULTED", "CORRECTED"), Sql.longValue(order, "orderedBy", 0));
        }
    }

    private void refreshReleasedStatus(long orderId) {
        Map<String, Object> order = repository.orderById(orderId);
        if (order == null || !Set.of("VERIFIED", "CORRECTED").contains(Sql.text(order, "status"))) {
            return;
        }
        long items = repository.countItems(orderId);
        long released = repository.countResultsWithStatus(orderId, List.of("RELEASED"));
        if (items > 0 && released >= items) {
            repository.setOrderStatusFrom(orderId, Sql.longValue(order, "version", 0), "RELEASED",
                    List.of("VERIFIED", "CORRECTED"), Sql.longValue(order, "orderedBy", 0));
        }
    }

    private String flagFor(Map<String, Object> test, BigDecimal numeric, String text) {
        if ("TEXT".equals(Sql.text(test, "resultType"))) {
            String reference = Sql.text(test, "refText");
            if (reference != null && text != null && !reference.equalsIgnoreCase(text)) {
                return "ABNORMAL";
            }
            return "NORMAL";
        }
        if (numeric == null) {
            return "NORMAL";
        }
        BigDecimal criticalLow = Sql.decimal(test, "criticalLow");
        BigDecimal criticalHigh = Sql.decimal(test, "criticalHigh");
        if (criticalLow != null && numeric.compareTo(criticalLow) <= 0) {
            return "CRITICAL_LOW";
        }
        if (criticalHigh != null && numeric.compareTo(criticalHigh) >= 0) {
            return "CRITICAL_HIGH";
        }
        BigDecimal refLow = Sql.decimal(test, "refLow");
        BigDecimal refHigh = Sql.decimal(test, "refHigh");
        if (refLow != null && numeric.compareTo(refLow) < 0) {
            return "LOW";
        }
        if (refHigh != null && numeric.compareTo(refHigh) > 0) {
            return "HIGH";
        }
        return "NORMAL";
    }

    private Map<String, Object> orderView(Map<String, Object> row, boolean full) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("priority", row.get("priority"));
        view.put("orderedAt", row.get("orderedAt"));
        view.put("clinicalNotes", row.get("clinicalNotes"));
        view.put("cancelReason", row.get("cancelReason"));
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("orderedBy", ClinicalSupport.map(
                "publicId", row.get("orderedByPublicId"),
                "nameAr", row.get("orderedByAr"),
                "nameEn", row.get("orderedByEn")));
        view.put("itemCount", row.get("itemCount"));
        view.put("resultCount", row.get("resultCount"));
        if (full) {
            view.put("items", itemViews(repository.orderItems(Sql.longValue(row, "id", 0))));
        }
        return view;
    }

    private List<Map<String, Object>> itemViews(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(ClinicalSupport.map(
                    "publicId", row.get("publicId"),
                    "status", row.get("status"),
                    "testCode", row.get("testCode"),
                    "testNameAr", row.get("testNameAr"),
                    "testNameEn", row.get("testNameEn"),
                    "specimenType", row.get("specimenType"),
                    "unit", row.get("unit"),
                    "resultType", row.get("resultType"),
                    "refLow", row.get("refLow"),
                    "refHigh", row.get("refHigh"),
                    "refText", row.get("refText"),
                    "criticalLow", row.get("criticalLow"),
                    "criticalHigh", row.get("criticalHigh"),
                    "targetTatMinutes", row.get("targetTatMinutes"),
                    "resultPublicId", row.get("resultPublicId"),
                    "resultStatus", row.get("resultStatus"),
                    "valueNumeric", row.get("valueNumeric"),
                    "valueText", row.get("valueText"),
                    "flag", row.get("flag"),
                    "criticalFlag", row.get("criticalFlag"),
                    "comment", row.get("comment")));
        }
        return items;
    }

    private List<Map<String, Object>> resultViews(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(resultView(row));
        }
        return items;
    }

    private Map<String, Object> resultView(Map<String, Object> row) {
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "status", row.get("status"),
                "testCode", row.get("testCode"),
                "testNameAr", row.get("testNameAr"),
                "testNameEn", row.get("testNameEn"),
                "unit", row.get("unitSnapshot") == null ? row.get("testUnit") : row.get("unitSnapshot"),
                "resultType", row.get("resultType"),
                "valueNumeric", row.get("valueNumeric"),
                "valueText", row.get("valueText"),
                "refLow", row.get("refLowSnapshot") == null ? row.get("testRefLow") : row.get("refLowSnapshot"),
                "refHigh", row.get("refHighSnapshot") == null ? row.get("testRefHigh") : row.get("refHighSnapshot"),
                "refText", row.get("refTextSnapshot") == null ? row.get("testRefText") : row.get("refTextSnapshot"),
                "flag", row.get("flag"),
                "criticalFlag", row.get("criticalFlag"),
                "comment", row.get("comment"),
                "enteredBy", ClinicalSupport.map(
                        "publicId", row.get("enteredByPublicId"),
                        "nameAr", row.get("enteredByAr"),
                        "nameEn", row.get("enteredByEn")),
                "enteredAt", row.get("enteredAt"),
                "verifiedBy", ClinicalSupport.map(
                        "nameAr", row.get("verifiedByAr"), "nameEn", row.get("verifiedByEn")),
                "verifiedAt", row.get("verifiedAt"),
                "releasedBy", ClinicalSupport.map(
                        "nameAr", row.get("releasedByAr"), "nameEn", row.get("releasedByEn")),
                "releasedAt", row.get("releasedAt"),
                "version", row.get("version"),
                "orderPublicId", row.get("orderPublicId"));
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

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية غير صحيحة في الحقل " + key + ".",
                    "Invalid numeric value for " + key + ".");
        }
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
