package com.smarthospital.clinical.pharmacy;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pharmacy stock and dispensing (FR-PHR-001..012).
 *
 * <p>Dispensing is one JTA transaction: the prescription row is locked, every
 * batch is locked {@code FOR UPDATE}, and stock is decremented with a
 * {@code quantity_available >= ?} guard. A failure anywhere (including the
 * database going away mid-dispense) rolls the whole transaction back, so no
 * partial dispensing, movement or stock change survives.
 */
@Stateless
@Local(PharmacyServiceLocal.class)
public class PharmacyService implements PharmacyServiceLocal {

    private static final Set<String> BATCH_STATUSES =
            Set.of("ACTIVE", "QUARANTINED", "EXPIRED", "DEPLETED", "WRITTEN_OFF");
    private static final Set<String> DISPENSABLE_STATUSES =
            Set.of("ISSUED", "REVIEWED", "PARTIAL");
    private static final Set<String> MOVEMENT_TYPES =
            Set.of("RECEIPT", "DISPENSE", "RETURN", "ADJUSTMENT", "EXPIRED", "WASTAGE",
                    "TRANSFER_IN", "TRANSFER_OUT");

    @EJB
    private PharmacyRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private AccessLogServiceLocal accessLog;

    @EJB
    private OutboxServiceLocal outbox;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Drug catalog
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> drugs(String query, boolean activeOnly, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countDrugs(query, activeOnly);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.drugs(query, activeOnly,
                safePage * safeSize, safeSize)) {
            items.add(drugView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> createDrug(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String code = ClinicalSupport.required(body, "code",
                "رمز الدواء مطلوب.", "Drug code is required.").toUpperCase();
        if (code.length() > 30) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "رمز الدواء أطول من الحد المسموح.",
                    "Drug code exceeds 30 characters.");
        }
        if (repository.drugByCode(code) != null) {
            throw AppException.conflict("DRUG_CODE_EXISTS",
                    "رمز الدواء مستخدم بالفعل.", "Drug code already exists.");
        }
        String nameAr = ClinicalSupport.required(body, "nameAr",
                "اسم الدواء بالعربية مطلوب.", "Arabic drug name is required.");
        String nameEn = ClinicalSupport.optionalTrim(body, "nameEn", 150);
        String form = ClinicalSupport.required(body, "form",
                "شكل الدواء مطلوب.", "Drug form is required.");
        if (form.length() > 40) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "شكل الدواء أطول من الحد المسموح.",
                    "Drug form exceeds 40 characters.");
        }
        String strength = ClinicalSupport.optionalTrim(body, "strength", 40);
        String category = ClinicalSupport.optionalTrim(body, "category", 40);
        Integer reorderLevel = ClinicalSupport.optionalInt(body, "reorderLevel");
        if (reorderLevel == null) {
            reorderLevel = 0;
        }
        if (reorderLevel < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حد إعادة الطلب لا يمكن أن يكون سالبًا.",
                    "The reorder level cannot be negative.");
        }
        BigDecimal unitPrice = decimal(body, "unitPrice");
        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }
        if (unitPrice.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "سعر الوحدة لا يمكن أن يكون سالبًا.",
                    "The unit price cannot be negative.");
        }
        String publicId = Ulid.next();
        repository.insertDrug(publicId, code, nameAr, nameEn, form, strength, category,
                reorderLevel, unitPrice, actor.getUserId());
        audit.record(actor.getUserId(), "DRUG_CREATED", "DRUG", publicId, null,
                ClinicalSupport.json("code", code, "nameAr", nameAr, "form", form,
                        "reorderLevel", reorderLevel, "unitPrice", unitPrice), null, ip);
        return drugView(repository.drugByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> batches(String drugPublicId, String status, String query,
                                       int page, int size) {
        if (drugPublicId != null && !drugPublicId.isBlank()
                && repository.drugByPublicId(drugPublicId) == null) {
            throw AppException.notFound("DRUG_NOT_FOUND",
                    "الدواء غير موجود.", "Drug not found.");
        }
        String statusFilter = normalize(status);
        if (statusFilter != null && !BATCH_STATUSES.contains(statusFilter)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة الدفعة غير صحيحة.", "Invalid batch status.");
        }
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countBatches(drugPublicId, statusFilter, query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.batches(drugPublicId, statusFilter, query,
                safePage * safeSize, safeSize)) {
            items.add(batchView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> receiveBatch(Map<String, Object> body, AuthPrincipal actor,
                                            String ip) {
        String drugPublicId = ClinicalSupport.required(body, "drugPublicId",
                "الدواء مطلوب.", "Drug is required.");
        Map<String, Object> drug = repository.drugByPublicId(drugPublicId);
        if (drug == null) {
            throw AppException.notFound("DRUG_NOT_FOUND",
                    "الدواء غير موجود.", "Drug not found.");
        }
        if (!Sql.boolValue(drug, "active")) {
            throw AppException.conflict("DRUG_INACTIVE",
                    "لا يمكن استلام دفعة لدواء غير مفعّل.",
                    "A batch cannot be received for an inactive drug.");
        }
        String batchNumber = ClinicalSupport.required(body, "batchNumber",
                "رقم الدفعة مطلوب.", "Batch number is required.");
        if (batchNumber.length() > 60) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "رقم الدفعة أطول من الحد المسموح.",
                    "Batch number exceeds 60 characters.");
        }
        long drugId = Sql.longValue(drug, "id", 0);
        if (repository.batchByNumber(drugId, batchNumber) != null) {
            throw AppException.conflict("BATCH_EXISTS",
                    "رقم الدفعة مستخدم لهذا الدواء.",
                    "This batch number already exists for the drug.");
        }
        LocalDate expiryDate = ClinicalSupport.date(body, "expiryDate", true);
        if (!expiryDate.isAfter(LocalDate.now())) {
            throw AppException.unprocessable("BATCH_EXPIRED",
                    "تاريخ انتهاء الدفعة يجب أن يكون في المستقبل.",
                    "The batch expiry date must be in the future.");
        }
        Integer quantity = ClinicalSupport.optionalInt(body, "quantity");
        if (quantity == null || quantity <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "كمية الاستلام يجب أن تكون أكبر من صفر.",
                    "The received quantity must be greater than zero.");
        }
        BigDecimal unitCost = decimal(body, "unitCost");
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        if (unitCost.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تكلفة الوحدة لا يمكن أن تكون سالبة.",
                    "The unit cost cannot be negative.");
        }
        String supplier = ClinicalSupport.optionalTrim(body, "supplier", 150);
        String lotNumber = ClinicalSupport.optionalTrim(body, "lotNumber", 60);

        String publicId = Ulid.next();
        long batchId = repository.insertBatch(publicId, drugId, batchNumber, lotNumber,
                expiryDate, quantity, unitCost, supplier, actor.getUserId());
        repository.insertStockMovement(Ulid.next(), drugId, batchId, "RECEIPT", quantity,
                "BATCH", publicId, "Opening receipt", actor.getUserId());
        audit.record(actor.getUserId(), "DRUG_BATCH_RECEIVED", "DRUG_BATCH", publicId, null,
                ClinicalSupport.json("drug", Sql.text(drug, "code"), "batchNumber", batchNumber,
                        "expiryDate", expiryDate.toString(), "quantity", quantity,
                        "unitCost", unitCost, "supplier", supplier), null, ip);
        return batchView(repository.batchByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Queue and prescription review
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> queue(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countQueue();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.queue(safePage * safeSize, safeSize)) {
            items.add(queueView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> prescription(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePrescription(publicId);
        long patientId = Sql.longValue(row, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "PRESCRIPTION", publicId, "READ", ip);
        return prescriptionView(row, true);
    }

    @Override
    public Map<String, Object> review(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePrescription(publicId);
        long version = requireVersion(row, body);
        if (!"ISSUED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن مراجعة وصفة في حالتها الحالية.",
                    "The prescription cannot be reviewed in its current state.");
        }
        long prescriptionId = Sql.longValue(row, "id", 0);
        List<Map<String, Object>> items = repository.prescriptionItems(prescriptionId);
        List<Long> drugIds = new ArrayList<>();
        for (Map<String, Object> item : items) {
            drugIds.add(Sql.longValue(item, "drugId", 0));
        }
        List<Map<String, Object>> interactions = interactionViews(repository.interactions(drugIds));
        boolean contraindicated = false;
        for (Map<String, Object> interaction : interactions) {
            if ("CONTRAINDICATED".equals(Sql.text(interaction, "severity"))) {
                contraindicated = true;
            }
        }
        if (contraindicated && !ClinicalSupport.bool(body, "acknowledgeInteractions")) {
            throw AppException.conflict("DRUG_INTERACTION",
                    "توجد تفاعلات دوائية متعارضة. يلزم تأكيد المراجعة صراحةً.",
                    "Contraindicated drug interactions exist. Explicit acknowledgement is required.");
        }
        if (repository.reviewPrescription(prescriptionId, version, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الوصفة من مستخدم آخر. أعد التحميل.",
                    "The prescription changed by another user. Reload and retry.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);
        audit.record(actor.getUserId(), "PRESCRIPTION_REVIEWED", "PRESCRIPTION", publicId,
                ClinicalSupport.json("status", "ISSUED"),
                ClinicalSupport.json("status", "REVIEWED", "interactions", interactions.size()),
                notes, ip);
        Map<String, Object> view = prescriptionView(repository.prescriptionByPublicId(publicId),
                true);
        view.put("interactions", interactions);
        return view;
    }

    @Override
    public Map<String, Object> reject(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requirePrescription(publicId);
        long version = requireVersion(row, body);
        if (!"ISSUED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن رفض وصفة في حالتها الحالية.",
                    "The prescription cannot be rejected in its current state.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الرفض مطلوب.", "A rejection reason is required.");
        if (reason.length() > 500) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "سبب الرفض أطول من الحد المسموح.",
                    "The rejection reason exceeds 500 characters.");
        }
        if (repository.rejectPrescription(Sql.longValue(row, "id", 0), version,
                actor.getUserId(), reason) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الوصفة من مستخدم آخر. أعد التحميل.",
                    "The prescription changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "PRESCRIPTION_REJECTED", "PRESCRIPTION", publicId,
                ClinicalSupport.json("status", "ISSUED"),
                ClinicalSupport.json("status", "REJECTED", "reason", reason), reason, ip);
        String prescriber = Sql.text(row, "prescribedByPublicId");
        if (prescriber != null) {
            outbox.enqueue("PRESCRIPTION_REJECTED", "PRESCRIPTION", publicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "users", List.of(prescriber),
                            "type", "PRESCRIPTION_REJECTED",
                            "titleAr", "رفض وصفة دوائية",
                            "titleEn", "Prescription rejected",
                            "bodyAr", Sql.text(row, "patientName") + " — " + reason,
                            "bodyEn", Sql.text(row, "patientName") + " — " + reason,
                            "severity", "WARNING",
                            "entityType", "PRESCRIPTION",
                            "entityPublicId", publicId,
                            "dedupeKey", "PRESCRIPTION_REJECTED:" + publicId))));
        }
        return prescriptionView(repository.prescriptionByPublicId(publicId), true);
    }

    // ------------------------------------------------------------------
    // Dispensing (FEFO, all-or-nothing transaction)
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> dispense(String publicId, Map<String, Object> body,
                                        AuthPrincipal actor, String ip) {
        Map<String, Object> found = requirePrescription(publicId);
        long prescriptionId = Sql.longValue(found, "id", 0);
        Map<String, Object> locked = repository.lockPrescription(prescriptionId);
        if (locked == null) {
            throw AppException.notFound("PRESCRIPTION_NOT_FOUND",
                    "الوصفة غير موجودة.", "Prescription not found.");
        }
        long version = requireVersion(locked, body);
        String status = Sql.text(locked, "status");
        if (!DISPENSABLE_STATUSES.contains(status)) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن صرف وصفة في حالتها الحالية: " + status,
                    "The prescription cannot be dispensed in its current state: " + status);
        }
        long patientId = Sql.longValue(locked, "patientId", 0);
        List<Map<String, Object>> items = repository.prescriptionItems(prescriptionId);
        if (items.isEmpty()) {
            throw AppException.conflict("EMPTY_PRESCRIPTION",
                    "الوصفة لا تحتوي بنودًا.", "The prescription has no items.");
        }

        Map<String, Integer> requested = requestedQuantities(body);
        boolean partialAllowed = boolSetting("phr.dispense.partial.allowed", true);
        List<Allocation> allocations = new ArrayList<>();
        int totalAllocated = 0;

        for (Map<String, Object> item : items) {
            long itemId = Sql.longValue(item, "id", 0);
            String itemPublicId = Sql.text(item, "publicId");
            int quantity = Sql.intValue(item, "quantity", 0);
            int dispensed = Sql.intValue(item, "dispensedQuantity", 0);
            int remaining = quantity - dispensed;
            if (remaining <= 0) {
                continue;
            }
            Integer wanted = requested.get(itemPublicId);
            if (requested.isEmpty()) {
                wanted = remaining;
            } else if (wanted == null) {
                continue;
            }
            if (wanted == null || wanted <= 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "كمية الصرف يجب أن تكون أكبر من صفر.",
                        "The dispense quantity must be greater than zero.");
            }
            if (wanted > remaining) {
                throw AppException.unprocessable("QUANTITY_EXCEEDS_REMAINING",
                        "الكمية المطلوبة تتجاوز المتبقي من الوصفة.",
                        "The requested quantity exceeds the remaining prescription quantity.");
            }
            int allocatedForItem = allocate(allocations, item, wanted, partialAllowed);
            totalAllocated += allocatedForItem;
        }
        if (totalAllocated == 0) {
            throw AppException.conflict("NO_STOCK",
                    "لا يوجد مخزون صالح لصرف أي بند من الوصفة.",
                    "No valid stock is available to dispense any item of this prescription.");
        }

        String dispensingPublicId = Ulid.next();
        long dispensingId = repository.insertDispensing(dispensingPublicId, prescriptionId,
                patientId, actor.getUserId(), ClinicalSupport.optionalTrim(body, "notes", 1000));
        for (Allocation allocation : allocations) {
            if (repository.decrementBatch(allocation.batchId, allocation.batchVersion,
                    allocation.quantity, actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّر مخزون إحدى الدفعات من مستخدم آخر. أعد المحاولة.",
                        "Batch stock changed by another user. Retry the dispense.");
            }
            if (allocation.batchAvailable == allocation.quantity) {
                repository.depleteBatch(allocation.batchId, allocation.batchVersion + 1,
                        actor.getUserId());
            }
            long movementId = repository.insertStockMovement(Ulid.next(), allocation.drugId,
                    allocation.batchId, "DISPENSE", -allocation.quantity, "DISPENSING",
                    dispensingPublicId, "Prescription dispensing", actor.getUserId());
            repository.insertDispensingItem(Ulid.next(), dispensingId,
                    allocation.prescriptionItemId, allocation.drugId, allocation.batchId,
                    allocation.quantity, movementId);
            if (repository.addDispensedQuantity(allocation.prescriptionItemId,
                    allocation.quantity) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّرت كميات الوصفة من مستخدم آخر. أعد المحاولة.",
                        "Prescription quantities changed by another user. Retry the dispense.");
            }
        }

        String newStatus = computePrescriptionStatus(prescriptionId);
        if (repository.setPrescriptionStatus(prescriptionId, version, newStatus,
                actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الوصفة من مستخدم آخر. أعد المحاولة.",
                    "The prescription changed by another user. Retry the dispense.");
        }
        audit.record(actor.getUserId(), "PRESCRIPTION_DISPENSED", "PRESCRIPTION", publicId,
                ClinicalSupport.json("status", status),
                ClinicalSupport.json("status", newStatus, "dispensing", dispensingPublicId,
                        "lines", allocations.size(), "quantity", totalAllocated),
                ClinicalSupport.optionalTrim(body, "notes", 1000), ip);

        Map<String, Object> result = dispensingView(
                repository.dispensingByPublicId(dispensingPublicId), true);
        result.put("prescriptionStatus", newStatus);
        result.put("prescriptionPublicId", publicId);
        result.put("partial", "DISPENSED".equals(newStatus) ? Boolean.FALSE : Boolean.TRUE);
        return result;
    }

    private int allocate(List<Allocation> allocations, Map<String, Object> item, int wanted,
                         boolean partialAllowed) {
        long itemId = Sql.longValue(item, "id", 0);
        long drugId = Sql.longValue(item, "drugId", 0);
        int remaining = wanted;
        for (Map<String, Object> batch : repository.fefoBatches(drugId)) {
            if (remaining <= 0) {
                break;
            }
            int available = Sql.intValue(batch, "quantityAvailable", 0);
            if (available <= 0) {
                continue;
            }
            int take = Math.min(remaining, available);
            allocations.add(new Allocation(itemId, drugId, Sql.longValue(batch, "id", 0),
                    Sql.longValue(batch, "version", 0), available, take));
            remaining -= take;
        }
        if (remaining > 0 && !partialAllowed) {
            throw AppException.conflict("NO_STOCK",
                    "المخزون لا يكفي لصرف الكمية المطلوبة بالكامل.",
                    "Stock is not enough to dispense the full requested quantity.");
        }
        return wanted - remaining;
    }

    private Map<String, Integer> requestedQuantities(Map<String, Object> body) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        Object raw = body == null ? null : body.get("items");
        if (!(raw instanceof List<?> list)) {
            return requested;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object idValue = map.get("prescriptionItemPublicId");
            Object quantityValue = map.get("quantity");
            if (idValue == null || quantityValue == null) {
                continue;
            }
            try {
                requested.put(idValue.toString().trim(),
                        new BigDecimal(quantityValue.toString().trim()).intValueExact());
            } catch (ArithmeticException | NumberFormatException ex) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "كمية الصرف غير صحيحة.", "Invalid dispense quantity.");
            }
        }
        return requested;
    }

    private String computePrescriptionStatus(long prescriptionId) {
        List<Map<String, Object>> items = repository.prescriptionItems(prescriptionId);
        boolean anyDispensed = false;
        boolean allDispensed = true;
        for (Map<String, Object> item : items) {
            int quantity = Sql.intValue(item, "quantity", 0);
            int dispensed = Sql.intValue(item, "dispensedQuantity", 0);
            if (dispensed > 0) {
                anyDispensed = true;
            }
            if (dispensed < quantity) {
                allDispensed = false;
            }
        }
        if (allDispensed && !items.isEmpty()) {
            return "DISPENSED";
        }
        return anyDispensed ? "PARTIAL" : "REVIEWED";
    }

    // ------------------------------------------------------------------
    // Dispensing read and returns
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> dispensing(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireDispensing(publicId);
        accessLog.record(actor.getUserId(), Sql.longValue(row, "patientId", 0), "DISPENSING",
                publicId, "READ", ip);
        return dispensingView(row, true);
    }

    @Override
    public Map<String, Object> returnDispensing(String publicId, Map<String, Object> body,
                                                AuthPrincipal actor, String ip) {
        Map<String, Object> dispensing = requireDispensing(publicId);
        if (!Set.of("COMMITTED", "PARTIALLY_RETURNED").contains(Sql.text(dispensing, "status"))) {
            throw AppException.conflict("INVALID_STATE",
                    "لا يمكن إرجاع أصناف من عملية صرف في حالتها الحالية.",
                    "Items cannot be returned from a dispensing in its current state.");
        }
        long dispensingId = Sql.longValue(dispensing, "id", 0);
        long dispensingVersion = Sql.longValue(dispensing, "version", 0);
        enforceReturnWindow(dispensing);

        List<Map<String, Object>> requested = requestedReturns(body);
        if (requested.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أضف صنفًا واحدًا على الأقل للإرجاع.",
                    "Add at least one item to return.");
        }
        String defaultReason = ClinicalSupport.required(body, "reason",
                "سبب الإرجاع مطلوب.", "A return reason is required.");
        int returnedTotal = 0;
        for (Map<String, Object> entry : requested) {
            String itemPublicId = ClinicalSupport.required(entry, "dispensingItemPublicId",
                    "صنف الصرف مطلوب.", "The dispensing item is required.");
            Map<String, Object> item = repository.dispensingItemByPublicId(itemPublicId);
            if (item == null || Sql.longValue(item, "dispensingId", 0) != dispensingId) {
                throw AppException.notFound("DISPENSING_ITEM_NOT_FOUND",
                        "صنف الصرف غير موجود في هذه العملية.",
                        "The dispensing item does not belong to this dispensing.");
            }
            Integer quantity = ClinicalSupport.optionalInt(entry, "quantity");
            if (quantity == null || quantity <= 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "كمية الإرجاع يجب أن تكون أكبر من صفر.",
                        "The return quantity must be greater than zero.");
            }
            int alreadyReturned = Sql.intValue(item, "returnedQuantity", 0);
            int sold = Sql.intValue(item, "quantity", 0);
            if (alreadyReturned + quantity > sold) {
                throw AppException.unprocessable("RETURN_EXCEEDS_SOLD",
                        "كمية الإرجاع تتجاوز الكمية المصروفة.",
                        "The return quantity exceeds the dispensed quantity.");
            }
            String reason = ClinicalSupport.optional(entry, "reason");
            reason = reason == null ? defaultReason : reason;
            if (reason.length() > 500) {
                throw AppException.unprocessable("FIELD_TOO_LONG",
                        "سبب الإرجاع أطول من الحد المسموح.",
                        "The return reason exceeds 500 characters.");
            }
            long itemId = Sql.longValue(item, "id", 0);
            long batchId = Sql.longValue(item, "batchId", 0);
            long drugId = Sql.longValue(item, "drugId", 0);
            Map<String, Object> batch = repository.batchByIdForUpdate(batchId);
            if (batch == null) {
                throw AppException.notFound("BATCH_NOT_FOUND",
                        "الدفعة غير موجودة.", "Batch not found.");
            }
            if (repository.incrementBatch(batchId, Sql.longValue(batch, "version", 0), quantity,
                    actor.getUserId()) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّر مخزون الدفعة من مستخدم آخر. أعد المحاولة.",
                        "Batch stock changed by another user. Retry the return.");
            }
            long movementId = repository.insertStockMovement(Ulid.next(), drugId, batchId,
                    "RETURN", quantity, "DISPENSING_RETURN", publicId, reason, actor.getUserId());
            repository.insertReturn(Ulid.next(), dispensingId, itemId, quantity, reason,
                    actor.getUserId(), movementId);
            if (repository.addReturnedQuantity(itemId, quantity) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّرت كميات الصرف من مستخدم آخر. أعد المحاولة.",
                        "Dispensing quantities changed by another user. Retry the return.");
            }
            if (repository.subtractDispensedQuantity(
                    Sql.longValue(item, "prescriptionItemId", 0), quantity) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تغيّرت كميات الوصفة من مستخدم آخر. أعد المحاولة.",
                        "Prescription quantities changed by another user. Retry the return.");
            }
            returnedTotal += quantity;
        }

        String dispensingStatus = computeDispensingStatus(dispensingId);
        if (repository.updateDispensingStatus(dispensingId, dispensingVersion,
                dispensingStatus) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل عملية الصرف من مستخدم آخر. أعد المحاولة.",
                    "The dispensing changed by another user. Retry the return.");
        }
        long prescriptionId = Sql.longValue(dispensing, "prescriptionId", 0);
        Map<String, Object> prescription = repository.lockPrescription(prescriptionId);
        if (prescription != null) {
            String status = computePrescriptionStatus(prescriptionId);
            repository.setPrescriptionStatus(prescriptionId,
                    Sql.longValue(prescription, "version", 0), status, actor.getUserId());
        }
        audit.record(actor.getUserId(), "DISPENSING_RETURNED", "DISPENSING", publicId, null,
                ClinicalSupport.json("status", dispensingStatus, "quantity", returnedTotal,
                        "reason", defaultReason), defaultReason, ip);
        Map<String, Object> result = dispensingView(
                repository.dispensingByPublicId(publicId), true);
        result.put("returnedQuantity", returnedTotal);
        return result;
    }

    private void enforceReturnWindow(Map<String, Object> dispensing) {
        int windowHours = intSetting("phr.return.window_hours", 72);
        if (windowHours <= 0) {
            return;
        }
        String dispensedAt = Sql.text(dispensing, "dispensedAt");
        if (dispensedAt == null) {
            return;
        }
        try {
            LocalDateTime moment = LocalDateTime.parse(dispensedAt);
            if (LocalDateTime.now().isAfter(moment.plusHours(windowHours))) {
                throw AppException.conflict("RETURN_WINDOW_EXPIRED",
                        "انتهت مهلة الإرجاع المسموح بها.",
                        "The allowed return window has expired.");
            }
        } catch (DateTimeParseException ignored) {
            // Unparseable timestamp: keep the return allowed rather than blocking the user.
        }
    }

    private List<Map<String, Object>> requestedReturns(Map<String, Object> body) {
        List<Map<String, Object>> requested = new ArrayList<>();
        Object raw = body == null ? null : body.get("items");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> field : map.entrySet()) {
                        row.put(String.valueOf(field.getKey()), field.getValue());
                    }
                    requested.add(row);
                }
            }
            return requested;
        }
        String itemPublicId = ClinicalSupport.optional(body, "dispensingItemPublicId");
        if (itemPublicId != null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dispensingItemPublicId", itemPublicId);
            row.put("quantity", body.get("quantity"));
            row.put("reason", body.get("itemReason"));
            requested.add(row);
        }
        return requested;
    }

    private String computeDispensingStatus(long dispensingId) {
        List<Map<String, Object>> items = repository.dispensingItems(dispensingId);
        boolean anyReturned = false;
        boolean allReturned = true;
        for (Map<String, Object> item : items) {
            int quantity = Sql.intValue(item, "quantity", 0);
            int returned = Sql.intValue(item, "returnedQuantity", 0);
            if (returned > 0) {
                anyReturned = true;
            }
            if (returned < quantity) {
                allReturned = false;
            }
        }
        if (allReturned && !items.isEmpty()) {
            return "RETURNED";
        }
        return anyReturned ? "PARTIALLY_RETURNED" : "COMMITTED";
    }

    // ------------------------------------------------------------------
    // Stock movements and alerts
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> stockMovements(String drugPublicId, String batchPublicId,
                                              String movementType, String date, int page,
                                              int size) {
        String typeFilter = normalize(movementType);
        if (typeFilter != null && !MOVEMENT_TYPES.contains(typeFilter)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع الحركة غير صحيح.", "Invalid stock movement type.");
        }
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countStockMovements(drugPublicId, batchPublicId, typeFilter, date);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.stockMovements(drugPublicId, batchPublicId,
                typeFilter, date, safePage * safeSize, safeSize)) {
            items.add(movementView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> stockAlerts(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int expiryDays = intSetting("phr.stock.expiry.warning_days", 90);
        List<Map<String, Object>> lowStock = new ArrayList<>();
        for (Map<String, Object> row : repository.lowStock(safeLimit)) {
            lowStock.add(drugView(row));
        }
        List<Map<String, Object>> expiring = new ArrayList<>();
        for (Map<String, Object> row : repository.expiringBatches(expiryDays, safeLimit)) {
            expiring.add(batchView(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lowStock", lowStock);
        result.put("expiring", expiring);
        result.put("expiryWarningDays", expiryDays);
        result.put("generatedAt", LocalDateTime.now().toString());
        return result;
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private Map<String, Object> drugView(Map<String, Object> row) {
        int stock = Sql.intValue(row, "stockAvailable", 0);
        int reorder = Sql.intValue(row, "reorderLevel", 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("code", row.get("code"));
        view.put("nameAr", row.get("nameAr"));
        view.put("nameEn", row.get("nameEn"));
        view.put("form", row.get("form"));
        view.put("strength", row.get("strength"));
        view.put("category", row.get("category"));
        view.put("reorderLevel", reorder);
        view.put("unitPrice", row.get("unitPrice"));
        view.put("active", row.get("active"));
        view.put("stockAvailable", stock);
        view.put("batchCount", Sql.intValue(row, "batchCount", 0));
        view.put("lowStock", stock <= reorder);
        view.put("version", row.get("version"));
        return view;
    }

    private Map<String, Object> batchView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("batchNumber", row.get("batchNumber"));
        view.put("lotNumber", row.get("lotNumber"));
        view.put("expiryDate", row.get("expiryDate"));
        view.put("quantityReceived", row.get("quantityReceived"));
        view.put("quantityAvailable", row.get("quantityAvailable"));
        view.put("unitCost", row.get("unitCost"));
        view.put("supplier", row.get("supplier"));
        view.put("status", row.get("status"));
        view.put("receivedAt", row.get("receivedAt"));
        view.put("daysToExpiry", row.get("daysToExpiry"));
        view.put("version", row.get("version"));
        view.put("drug", ClinicalSupport.map(
                "publicId", row.get("drugPublicId"),
                "code", row.get("drugCode"),
                "nameAr", row.get("drugAr"),
                "nameEn", row.get("drugEn"),
                "form", row.get("form"),
                "strength", row.get("strength")));
        view.put("receivedBy", ClinicalSupport.map(
                "nameAr", row.get("receivedByAr"),
                "nameEn", row.get("receivedByEn")));
        return view;
    }

    private Map<String, Object> queueView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("issuedAt", row.get("issuedAt"));
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("prescribedBy", ClinicalSupport.map(
                "nameAr", row.get("prescribedByAr"),
                "nameEn", row.get("prescribedByEn")));
        view.put("itemCount", row.get("itemCount"));
        view.put("totalQuantity", row.get("totalQuantity"));
        view.put("dispensedQuantity", row.get("dispensedQuantity"));
        return view;
    }

    private Map<String, Object> prescriptionView(Map<String, Object> row, boolean full) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("issuedAt", row.get("issuedAt"));
        view.put("notes", row.get("notes"));
        view.put("rejectedReason", row.get("rejectedReason"));
        view.put("reviewedAt", row.get("reviewedAt"));
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "gender", row.get("patientGender"),
                "birthDate", row.get("patientBirthDate"),
                "phone", row.get("patientPhone")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("prescribedBy", ClinicalSupport.map(
                "publicId", row.get("prescribedByPublicId"),
                "nameAr", row.get("prescribedByAr"),
                "nameEn", row.get("prescribedByEn")));
        view.put("reviewedBy", ClinicalSupport.map(
                "nameAr", row.get("reviewedByAr"),
                "nameEn", row.get("reviewedByEn")));
        if (full) {
            long prescriptionId = Sql.longValue(row, "id", 0);
            long patientId = Sql.longValue(row, "patientId", 0);
            List<Map<String, Object>> items = new ArrayList<>();
            List<Long> drugIds = new ArrayList<>();
            for (Map<String, Object> item : repository.prescriptionItems(prescriptionId)) {
                drugIds.add(Sql.longValue(item, "drugId", 0));
                items.add(prescriptionItemView(item));
            }
            view.put("items", items);
            view.put("allergies", repository.patientAllergies(patientId));
            view.put("interactions", interactionViews(repository.interactions(drugIds)));
            view.put("dispensings", repository.dispensingForPrescription(prescriptionId));
        }
        return view;
    }

    private Map<String, Object> prescriptionItemView(Map<String, Object> row) {
        int quantity = Sql.intValue(row, "quantity", 0);
        int dispensed = Sql.intValue(row, "dispensedQuantity", 0);
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "dose", row.get("dose"),
                "route", row.get("route"),
                "frequency", row.get("frequency"),
                "durationDays", row.get("durationDays"),
                "quantity", quantity,
                "dispensedQuantity", dispensed,
                "remaining", Math.max(0, quantity - dispensed),
                "instructions", row.get("instructions"),
                "stockAvailable", row.get("stockAvailable"),
                "drug", ClinicalSupport.map(
                        "publicId", row.get("drugPublicId"),
                        "code", row.get("drugCode"),
                        "nameAr", row.get("drugAr"),
                        "nameEn", row.get("drugEn"),
                        "form", row.get("form"),
                        "strength", row.get("strength")));
    }

    private Map<String, Object> dispensingView(Map<String, Object> row, boolean full) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("status", row.get("status"));
        view.put("dispensedAt", row.get("dispensedAt"));
        view.put("notes", row.get("notes"));
        view.put("version", row.get("version"));
        view.put("prescriptionPublicId", row.get("prescriptionPublicId"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName")));
        view.put("dispensedBy", ClinicalSupport.map(
                "publicId", row.get("dispensedByPublicId"),
                "nameAr", row.get("dispensedByAr"),
                "nameEn", row.get("dispensedByEn")));
        if (full) {
            long dispensingId = Sql.longValue(row, "id", 0);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> item : repository.dispensingItems(dispensingId)) {
                items.add(ClinicalSupport.map(
                        "publicId", item.get("publicId"),
                        "quantity", item.get("quantity"),
                        "returnedQuantity", item.get("returnedQuantity"),
                        "dose", item.get("dose"),
                        "route", item.get("route"),
                        "frequency", item.get("frequency"),
                        "batchNumber", item.get("batchNumber"),
                        "expiryDate", item.get("expiryDate"),
                        "drug", ClinicalSupport.map(
                                "publicId", item.get("drugPublicId"),
                                "code", item.get("drugCode"),
                                "nameAr", item.get("drugAr"),
                                "nameEn", item.get("drugEn"))));
            }
            view.put("items", items);
            view.put("returns", repository.returnsForDispensing(dispensingId));
        }
        return view;
    }

    private List<Map<String, Object>> interactionViews(List<Map<String, Object>> rows) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            views.add(ClinicalSupport.map(
                    "severity", row.get("severity"),
                    "descriptionAr", row.get("descriptionAr"),
                    "descriptionEn", row.get("descriptionEn"),
                    "drug", ClinicalSupport.map(
                            "code", row.get("drugCode"),
                            "nameAr", row.get("drugAr"),
                            "nameEn", row.get("drugEn")),
                    "interactingDrug", ClinicalSupport.map(
                            "code", row.get("otherCode"),
                            "nameAr", row.get("otherAr"),
                            "nameEn", row.get("otherEn"))));
        }
        return views;
    }

    private Map<String, Object> movementView(Map<String, Object> row) {
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "movementType", row.get("movementType"),
                "quantityDelta", row.get("quantityDelta"),
                "referenceType", row.get("referenceType"),
                "referencePublicId", row.get("referencePublicId"),
                "reason", row.get("reason"),
                "performedAt", row.get("performedAt"),
                "drug", ClinicalSupport.map(
                        "publicId", row.get("drugPublicId"),
                        "code", row.get("drugCode"),
                        "nameAr", row.get("drugAr"),
                        "nameEn", row.get("drugEn")),
                "batch", ClinicalSupport.map(
                        "publicId", row.get("batchPublicId"),
                        "batchNumber", row.get("batchNumber"),
                        "expiryDate", row.get("expiryDate")),
                "performedBy", ClinicalSupport.map(
                        "publicId", row.get("performedByPublicId"),
                        "nameAr", row.get("performedByAr"),
                        "nameEn", row.get("performedByEn")));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> requirePrescription(String publicId) {
        Map<String, Object> row = repository.prescriptionByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PRESCRIPTION_NOT_FOUND",
                    "الوصفة غير موجودة.", "Prescription not found.");
        }
        return row;
    }

    private Map<String, Object> requireDispensing(String publicId) {
        Map<String, Object> row = repository.dispensingByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("DISPENSING_NOT_FOUND",
                    "عملية الصرف غير موجودة.", "Dispensing not found.");
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

    private boolean boolSetting(String key, boolean fallback) {
        return settings.findValue(key)
                .map(value -> "1".equals(value) || "true".equalsIgnoreCase(value))
                .orElse(fallback);
    }

    private int intSetting(String key, int fallback) {
        return settings.findValue(key)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        return fallback;
                    }
                })
                .orElse(fallback);
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

    /** One FEFO batch slice reserved for a prescription line. */
    private static final class Allocation {
        private final long prescriptionItemId;
        private final long drugId;
        private final long batchId;
        private final long batchVersion;
        private final int batchAvailable;
        private final int quantity;

        private Allocation(long prescriptionItemId, long drugId, long batchId, long batchVersion,
                           int batchAvailable, int quantity) {
            this.prescriptionItemId = prescriptionItemId;
            this.drugId = drugId;
            this.batchId = batchId;
            this.batchVersion = batchVersion;
            this.batchAvailable = batchAvailable;
            this.quantity = quantity;
        }
    }
}
