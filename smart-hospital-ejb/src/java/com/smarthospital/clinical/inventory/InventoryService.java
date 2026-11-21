package com.smarthospital.clinical.inventory;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.notify.OutboxServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * General inventory: warehouses, items, suppliers, batches, issues, transfers,
 * stock counts, purchase requests/orders and goods receipts
 * (FR-INV-001..010).
 *
 * <p>Stock invariants:
 * <ul>
 *   <li>Every quantity change writes exactly one append-only movement in the
 *       same JTA transaction.</li>
 *   <li>Stock can never go negative: batches are locked with {@code FOR UPDATE}
 *       and consumed FEFO before any row is updated.</li>
 *   <li>A transfer is one transaction with a document reference and creates the
 *       destination batch (or adds to it) plus both movement legs.</li>
 *   <li>Expired batches are refused when {@code inventory.expiry.block} is on.</li>
 *   <li>Goods receipts are only accepted against an order and never beyond the
 *       outstanding ordered quantity.</li>
 * </ul>
 */
@Stateless
public class InventoryService implements InventoryServiceLocal {

    private static final Set<String> CATEGORIES = Set.of("SUPPLY", "CONSUMABLE", "INSTRUMENT",
            "LINEN", "STATIONERY", "FOOD", "OTHER");
    private static final Set<String> WAREHOUSE_TYPES = Set.of("MAIN", "PHARMACY", "ER", "WARD",
            "LAB", "OTHER");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    @EJB
    private InventoryRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private OutboxServiceLocal outbox;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Warehouses
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listWarehouses(String query, boolean activeOnly) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.warehouses(query, activeOnly)) {
            items.add(warehouseView(row));
        }
        return ClinicalSupport.map("items", items, "total", items.size());
    }

    @Override
    public Map<String, Object> createWarehouse(Map<String, Object> body, AuthPrincipal actor,
                                               String ip) {
        String code = ClinicalSupport.required(body, "code",
                "رمز المستودع مطلوب.", "Warehouse code is required.");
        if (code.length() > 30) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "رمز المستودع أطول من الحد المسموح.", "Warehouse code exceeds 30 characters.");
        }
        String nameAr = ClinicalSupport.required(body, "nameAr",
                "اسم المستودع بالعربية مطلوب.", "The Arabic warehouse name is required.");
        String nameEn = ClinicalSupport.optionalTrim(body, "nameEn", 150);
        String type = upper(ClinicalSupport.optional(body, "warehouseType"), "MAIN");
        if (!WAREHOUSE_TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع المستودع غير صحيح.", "Invalid warehouse type.");
        }
        String location = ClinicalSupport.optionalTrim(body, "location", 200);
        if (repository.warehouseByCode(code) != null) {
            throw AppException.conflict("WAREHOUSE_CODE_EXISTS",
                    "رمز المستودع مستخدم بالفعل.", "The warehouse code already exists.");
        }
        String publicId = Ulid.next();
        repository.insertWarehouse(publicId, code.toUpperCase(), nameAr, nameEn, type, location,
                actor.getUserId());
        audit.record(actor.getUserId(), "WAREHOUSE_CREATED", "WAREHOUSE", publicId, null,
                ClinicalSupport.json("code", code, "nameAr", nameAr, "type", type), null, ip);
        return warehouseView(repository.warehouseByPublicId(publicId));
    }

    @Override
    public Map<String, Object> updateWarehouse(String publicId, Map<String, Object> body,
                                               AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireWarehouse(publicId);
        long version = requireVersion(current, body);
        String nameAr = ClinicalSupport.optional(body, "nameAr");
        nameAr = nameAr == null ? Sql.text(current, "nameAr") : nameAr;
        String nameEn = body != null && body.containsKey("nameEn")
                ? ClinicalSupport.optionalTrim(body, "nameEn", 150)
                : Sql.text(current, "nameEn");
        String type = ClinicalSupport.optional(body, "warehouseType");
        type = type == null ? Sql.text(current, "warehouseType") : type.toUpperCase();
        if (!WAREHOUSE_TYPES.contains(type)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "نوع المستودع غير صحيح.", "Invalid warehouse type.");
        }
        String location = body != null && body.containsKey("location")
                ? ClinicalSupport.optionalTrim(body, "location", 200)
                : Sql.text(current, "location");
        boolean active = body == null || !body.containsKey("active")
                ? Sql.boolValue(current, "active") : ClinicalSupport.bool(body, "active");
        if (!active && repository.stockInWarehouse(Sql.longValue(current, "id", 0)) > 0) {
            throw AppException.conflict("WAREHOUSE_HAS_STOCK",
                    "لا يمكن تعطيل مستودع يحتوي كميات متاحة.",
                    "A warehouse that still holds stock cannot be deactivated.");
        }
        if (repository.updateWarehouse(Sql.longValue(current, "id", 0), version, nameAr, nameEn,
                type, location, active, actor.getUserId()) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "WAREHOUSE_UPDATED", "WAREHOUSE", publicId,
                ClinicalSupport.json("nameAr", Sql.text(current, "nameAr"),
                        "active", Sql.boolValue(current, "active")),
                ClinicalSupport.json("nameAr", nameAr, "type", type, "active", active), null, ip);
        return warehouseView(repository.warehouseByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listItems(String query, String category, boolean activeOnly,
                                         int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(category, null);
        long total = repository.countItems(query, normalized, activeOnly);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.items(query, normalized, activeOnly,
                safePage * safeSize, safeSize)) {
            items.add(itemView(row, false));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> item(String publicId) {
        Map<String, Object> row = requireItem(publicId);
        return itemView(row, true);
    }

    @Override
    public Map<String, Object> createItem(Map<String, Object> body, AuthPrincipal actor,
                                          String ip) {
        String code = ClinicalSupport.required(body, "code",
                "رمز الصنف مطلوب.", "Item code is required.");
        String sku = ClinicalSupport.required(body, "sku",
                "رمز SKU مطلوب.", "SKU is required.");
        String nameAr = ClinicalSupport.required(body, "nameAr",
                "اسم الصنف بالعربية مطلوب.", "The Arabic item name is required.");
        String nameEn = ClinicalSupport.optionalTrim(body, "nameEn", 200);
        String category = upper(ClinicalSupport.optional(body, "category"), "SUPPLY");
        if (!CATEGORIES.contains(category)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تصنيف الصنف غير صحيح.", "Invalid item category.");
        }
        String baseUnit = ClinicalSupport.required(body, "baseUnit",
                "وحدة القياس مطلوبة.", "A base unit is required.");
        if (baseUnit.length() > 20) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "وحدة القياس أطول من الحد المسموح.", "The base unit exceeds 20 characters.");
        }
        boolean trackExpiry = body == null || !body.containsKey("trackExpiry")
                || ClinicalSupport.bool(body, "trackExpiry");
        BigDecimal unitCost = decimal(body, "unitCost");
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        if (unitCost.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تكلفة الوحدة لا يمكن أن تكون سالبة.", "The unit cost cannot be negative.");
        }
        String barcode = ClinicalSupport.optionalTrim(body, "barcode", 80);
        if (repository.itemBySku(sku) != null) {
            throw AppException.conflict("SKU_EXISTS",
                    "رمز SKU مستخدم بالفعل.", "The SKU already exists.");
        }
        if (repository.itemByCode(code) != null) {
            throw AppException.conflict("ITEM_CODE_EXISTS",
                    "رمز الصنف مستخدم بالفعل.", "The item code already exists.");
        }
        if (barcode != null && repository.itemByBarcode(barcode) != null) {
            throw AppException.conflict("BARCODE_EXISTS",
                    "الباركود مستخدم بالفعل.", "The barcode already exists.");
        }
        String publicId = Ulid.next();
        repository.insertItem(publicId, code.toUpperCase(), sku, nameAr, nameEn, category,
                baseUnit.toUpperCase(), trackExpiry, money(unitCost), barcode, actor.getUserId());
        audit.record(actor.getUserId(), "INVENTORY_ITEM_CREATED", "INVENTORY_ITEM", publicId,
                null, ClinicalSupport.json("code", code, "sku", sku, "category", category,
                        "baseUnit", baseUnit, "trackExpiry", trackExpiry), null, ip);
        return itemView(repository.itemByPublicId(publicId), true);
    }

    @Override
    public Map<String, Object> updateItem(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireItem(publicId);
        long version = requireVersion(current, body);
        String nameAr = ClinicalSupport.optional(body, "nameAr");
        nameAr = nameAr == null ? Sql.text(current, "nameAr") : nameAr;
        String nameEn = body != null && body.containsKey("nameEn")
                ? ClinicalSupport.optionalTrim(body, "nameEn", 200)
                : Sql.text(current, "nameEn");
        String category = upper(ClinicalSupport.optional(body, "category"),
                Sql.text(current, "category"));
        if (!CATEGORIES.contains(category)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تصنيف الصنف غير صحيح.", "Invalid item category.");
        }
        String baseUnit = ClinicalSupport.optional(body, "baseUnit");
        baseUnit = baseUnit == null ? Sql.text(current, "baseUnit") : baseUnit.toUpperCase();
        boolean trackExpiry = body == null || !body.containsKey("trackExpiry")
                ? Sql.boolValue(current, "trackExpiry") : ClinicalSupport.bool(body, "trackExpiry");
        BigDecimal unitCost = decimal(body, "unitCost");
        if (unitCost == null) {
            unitCost = Sql.decimal(current, "unitCost");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تكلفة الوحدة غير صحيحة.", "Invalid unit cost.");
        }
        String barcode = body != null && body.containsKey("barcode")
                ? ClinicalSupport.optionalTrim(body, "barcode", 80)
                : Sql.text(current, "barcode");
        if (barcode != null && !barcode.equals(Sql.text(current, "barcode"))) {
            Map<String, Object> other = repository.itemByBarcode(barcode);
            if (other != null) {
                throw AppException.conflict("BARCODE_EXISTS",
                        "الباركود مستخدم بالفعل.", "The barcode already exists.");
            }
        }
        boolean active = body == null || !body.containsKey("active")
                ? Sql.boolValue(current, "active") : ClinicalSupport.bool(body, "active");
        if (repository.updateItem(Sql.longValue(current, "id", 0), version, nameAr, nameEn,
                category, baseUnit, trackExpiry, money(unitCost), barcode, active,
                actor.getUserId()) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "INVENTORY_ITEM_UPDATED", "INVENTORY_ITEM", publicId,
                ClinicalSupport.json("nameAr", Sql.text(current, "nameAr"),
                        "active", Sql.boolValue(current, "active")),
                ClinicalSupport.json("nameAr", nameAr, "category", category,
                        "unitCost", unitCost, "active", active), null, ip);
        return itemView(repository.itemByPublicId(publicId), true);
    }

    // ------------------------------------------------------------------
    // Suppliers
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listSuppliers(String query, boolean activeOnly) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.suppliers(query, activeOnly)) {
            items.add(supplierView(row));
        }
        return ClinicalSupport.map("items", items, "total", items.size());
    }

    @Override
    public Map<String, Object> createSupplier(Map<String, Object> body, AuthPrincipal actor,
                                              String ip) {
        String code = ClinicalSupport.required(body, "code",
                "رمز المورد مطلوب.", "Supplier code is required.");
        String nameAr = ClinicalSupport.required(body, "nameAr",
                "اسم المورد بالعربية مطلوب.", "The Arabic supplier name is required.");
        if (repository.supplierByCode(code) != null) {
            throw AppException.conflict("SUPPLIER_CODE_EXISTS",
                    "رمز المورد مستخدم بالفعل.", "The supplier code already exists.");
        }
        String publicId = Ulid.next();
        repository.insertSupplier(publicId, code.toUpperCase(), nameAr,
                ClinicalSupport.optionalTrim(body, "nameEn", 200),
                ClinicalSupport.optionalTrim(body, "phone", 40),
                ClinicalSupport.optionalTrim(body, "email", 150),
                ClinicalSupport.optionalTrim(body, "address", 500),
                ClinicalSupport.optionalTrim(body, "contactPerson", 150), actor.getUserId());
        audit.record(actor.getUserId(), "SUPPLIER_CREATED", "SUPPLIER", publicId, null,
                ClinicalSupport.json("code", code, "nameAr", nameAr), null, ip);
        return supplierView(repository.supplierByPublicId(publicId));
    }

    @Override
    public Map<String, Object> updateSupplier(String publicId, Map<String, Object> body,
                                              AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireSupplier(publicId);
        long version = requireVersion(current, body);
        String nameAr = ClinicalSupport.optional(body, "nameAr");
        nameAr = nameAr == null ? Sql.text(current, "nameAr") : nameAr;
        String nameEn = body != null && body.containsKey("nameEn")
                ? ClinicalSupport.optionalTrim(body, "nameEn", 200)
                : Sql.text(current, "nameEn");
        String phone = body != null && body.containsKey("phone")
                ? ClinicalSupport.optionalTrim(body, "phone", 40) : Sql.text(current, "phone");
        String email = body != null && body.containsKey("email")
                ? ClinicalSupport.optionalTrim(body, "email", 150) : Sql.text(current, "email");
        String address = body != null && body.containsKey("address")
                ? ClinicalSupport.optionalTrim(body, "address", 500) : Sql.text(current, "address");
        String contact = body != null && body.containsKey("contactPerson")
                ? ClinicalSupport.optionalTrim(body, "contactPerson", 150)
                : Sql.text(current, "contactPerson");
        boolean active = body == null || !body.containsKey("active")
                ? Sql.boolValue(current, "active") : ClinicalSupport.bool(body, "active");
        if (repository.updateSupplier(Sql.longValue(current, "id", 0), version, nameAr, nameEn,
                phone, email, address, contact, active, actor.getUserId()) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "SUPPLIER_UPDATED", "SUPPLIER", publicId,
                ClinicalSupport.json("nameAr", Sql.text(current, "nameAr")),
                ClinicalSupport.json("nameAr", nameAr, "active", active), null, ip);
        return supplierView(repository.supplierByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Batches and receipts
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listBatches(String query, String itemPublicId,
                                           String warehousePublicId, String status,
                                           LocalDate expiryBefore, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(status, null);
        long total = repository.countBatches(query, itemPublicId, warehousePublicId, normalized,
                expiryBefore);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.batches(query, itemPublicId, warehousePublicId,
                normalized, expiryBefore, safePage * safeSize, safeSize)) {
            items.add(batchView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> receiveBatch(Map<String, Object> body, AuthPrincipal actor,
                                            String ip) {
        Map<String, Object> item = requireItem(ClinicalSupport.required(body, "itemPublicId",
                "الصنف مطلوب.", "Item is required."));
        Map<String, Object> warehouse = requireWarehouse(ClinicalSupport.required(body,
                "warehousePublicId", "المستودع مطلوب.", "Warehouse is required."));
        String batchNumber = ClinicalSupport.required(body, "batchNumber",
                "رقم الدفعة مطلوب.", "Batch number is required.");
        int quantity = requirePositiveInt(body, "quantity",
                "الكمية يجب أن تكون أكبر من صفر.", "Quantity must be greater than zero.");
        BigDecimal unitCost = decimal(body, "unitCost");
        if (unitCost == null) {
            unitCost = Sql.decimal(item, "unitCost");
        }
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        if (unitCost.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تكلفة الوحدة لا يمكن أن تكون سالبة.", "The unit cost cannot be negative.");
        }
        LocalDate expiryDate = ClinicalSupport.date(body, "expiryDate", false);
        checkExpiry(item, expiryDate);
        Long supplierId = null;
        String supplierPublicId = ClinicalSupport.optional(body, "supplierPublicId");
        if (supplierPublicId != null) {
            Map<String, Object> supplier = requireSupplier(supplierPublicId);
            supplierId = Sql.longValue(supplier, "id", 0);
        }
        long itemId = Sql.longValue(item, "id", 0);
        long warehouseId = Sql.longValue(warehouse, "id", 0);
        String lotNumber = ClinicalSupport.optionalTrim(body, "lotNumber", 60);
        String reason = ClinicalSupport.optionalTrim(body, "reason", 500);
        String publicId;
        Map<String, Object> existing = repository.lockBatchByNumber(itemId, warehouseId, batchNumber);
        if (existing != null) {
            Map<String, Object> locked = repository.lockBatch(Sql.longValue(existing, "id", 0));
            if (repository.incrementBatch(Sql.longValue(locked, "id", 0),
                    Sql.longValue(locked, "version", 0), quantity, actor.getUserId()) == 0) {
                throw staleVersion();
            }
            publicId = Sql.text(locked, "publicId");
        } else {
            publicId = Ulid.next();
            repository.insertBatch(publicId, itemId, warehouseId, supplierId, batchNumber,
                    lotNumber, expiryDate, quantity, money(unitCost), actor.getUserId());
        }
        repository.insertMovement(Ulid.next(), itemId,
                Sql.longValue(repository.batchByPublicId(publicId), "id", 0), warehouseId,
                "RECEIPT", quantity, money(unitCost), "BATCH", publicId,
                reason == null ? "Manual receipt" : reason, actor.getUserId());
        audit.record(actor.getUserId(), "INVENTORY_RECEIVED", "INVENTORY_BATCH", publicId, null,
                ClinicalSupport.json("item", Sql.text(item, "code"),
                        "warehouse", Sql.text(warehouse, "code"), "batchNumber", batchNumber,
                        "quantity", quantity, "expiryDate",
                        expiryDate == null ? null : expiryDate.toString(),
                        "unitCost", unitCost), reason, ip);
        return batchView(repository.batchByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Issues, write-offs and transfers
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> issue(Map<String, Object> body, AuthPrincipal actor, String ip) {
        String documentRef = ClinicalSupport.optionalTrim(body, "documentRef", 120);
        if (documentRef == null && boolSetting("inventory.transfer.document.required", true)) {
            throw AppException.badRequest("DOCUMENT_REQUIRED",
                    "مرجع المستند مطلوب لصرف المخزون.",
                    "A document reference is required to issue stock.");
        }
        String reason = ClinicalSupport.optionalTrim(body, "reason", 500);
        List<StockLine> lines = parseLines(body);
        String documentPublicId = Ulid.next();
        List<Map<String, Object>> consumedLines = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockLine line : lines) {
            Map<String, Object> item = requireItem(line.itemPublicId());
            Map<String, Object> warehouse = requireWarehouse(line.warehousePublicId());
            long itemId = Sql.longValue(item, "id", 0);
            long warehouseId = Sql.longValue(warehouse, "id", 0);
            if (repository.stockForItem(itemId, warehouseId) < line.quantity()) {
                throw AppException.unprocessable("INSUFFICIENT_STOCK",
                        "الكمية المتاحة من " + Sql.text(item, "code") + " غير كافية.",
                        "Insufficient stock for item " + Sql.text(item, "code") + ".");
            }
            List<Map<String, Object>> batches = new ArrayList<>();
            BigDecimal value = consume(itemId, warehouseId, line.quantity(), "ISSUE", "ISSUE",
                    documentPublicId, documentRef == null ? reason : "Doc: " + documentRef
                            + (reason == null ? "" : " - " + reason), actor.getUserId(), batches);
            totalValue = totalValue.add(value);
            consumedLines.add(ClinicalSupport.map("itemPublicId", line.itemPublicId(),
                    "code", Sql.text(item, "code"), "nameAr", Sql.text(item, "nameAr"),
                    "nameEn", Sql.text(item, "nameEn"), "baseUnit", Sql.text(item, "baseUnit"),
                    "warehousePublicId", line.warehousePublicId(),
                    "warehouseCode", Sql.text(warehouse, "code"),
                    "quantity", line.quantity(), "value", value, "batches", batches));
        }
        audit.record(actor.getUserId(), "INVENTORY_ISSUED", "STOCK_ISSUE", documentPublicId, null,
                ClinicalSupport.json("documentRef", documentRef, "reason", reason,
                        "lines", consumedLines.size(), "totalValue", money(totalValue)), reason, ip);
        return ClinicalSupport.map("documentPublicId", documentPublicId, "documentRef", documentRef,
                "reason", reason, "items", consumedLines, "totalValue", money(totalValue),
                "issuedAt", LocalDateTime.now().toString());
    }

    @Override
    public Map<String, Object> writeOff(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> item = requireItem(ClinicalSupport.required(body, "itemPublicId",
                "الصنف مطلوب.", "Item is required."));
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الشطب مطلوب.", "A write-off reason is required.");
        int quantity = requirePositiveInt(body, "quantity",
                "الكمية يجب أن تكون أكبر من صفر.", "Quantity must be greater than zero.");
        long itemId = Sql.longValue(item, "id", 0);
        long warehouseId;
        String batchPublicId = ClinicalSupport.optional(body, "batchPublicId");
        List<Map<String, Object>> batches = new ArrayList<>();
        BigDecimal value;
        if (batchPublicId != null) {
            Map<String, Object> batch = repository.batchByPublicId(batchPublicId);
            if (batch == null) {
                throw AppException.notFound("BATCH_NOT_FOUND",
                        "الدفعة غير موجودة.", "Batch not found.");
            }
            if (Sql.longValue(batch, "itemId", 0) != itemId) {
                throw AppException.unprocessable("BATCH_ITEM_MISMATCH",
                        "الدفعة لا تخص هذا الصنف.", "The batch does not belong to this item.");
            }
            warehouseId = Sql.longValue(batch, "warehouseId", 0);
            Map<String, Object> locked = repository.lockBatch(Sql.longValue(batch, "id", 0));
            int available = Sql.intValue(locked, "quantityAvailable", 0);
            if (available < quantity) {
                throw AppException.unprocessable("INSUFFICIENT_STOCK",
                        "الكمية المتاحة في الدفعة غير كافية.",
                        "The batch does not hold enough stock.");
            }
            value = decrementLocked(itemId, warehouseId, locked, quantity, "WRITE_OFF",
                    "WRITE_OFF", null, reason, actor.getUserId(), batches);
        } else {
            warehouseId = Sql.longValue(requireWarehouse(ClinicalSupport.required(body,
                    "warehousePublicId", "المستودع مطلوب.", "Warehouse is required.")), "id", 0);
            if (repository.stockForItem(itemId, warehouseId) < quantity) {
                throw AppException.unprocessable("INSUFFICIENT_STOCK",
                        "الكمية المتاحة غير كافية للشطب.", "Insufficient stock for write-off.");
            }
            value = consume(itemId, warehouseId, quantity, "WRITE_OFF", "WRITE_OFF", null,
                    reason, actor.getUserId(), batches);
        }
        audit.record(actor.getUserId(), "INVENTORY_WRITTEN_OFF", "INVENTORY_ITEM",
                Sql.text(item, "publicId"), null,
                ClinicalSupport.json("quantity", quantity, "reason", reason,
                        "value", value, "batches", batches), reason, ip);
        return ClinicalSupport.map("itemPublicId", Sql.text(item, "publicId"),
                "code", Sql.text(item, "code"), "quantity", quantity, "reason", reason,
                "value", money(value), "batches", batches);
    }

    @Override
    public Map<String, Object> transfer(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> from = requireWarehouse(ClinicalSupport.required(body,
                "fromWarehousePublicId", "المستودع المصدر مطلوب.",
                "The source warehouse is required."));
        Map<String, Object> to = requireWarehouse(ClinicalSupport.required(body,
                "toWarehousePublicId", "المستودع الهدف مطلوب.",
                "The destination warehouse is required."));
        if (Sql.longValue(from, "id", 0) == Sql.longValue(to, "id", 0)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "لا يمكن التحويل إلى المستودع نفسه.",
                    "A transfer needs two different warehouses.");
        }
        String documentRef = ClinicalSupport.optionalTrim(body, "documentRef", 120);
        if (documentRef == null && boolSetting("inventory.transfer.document.required", true)) {
            throw AppException.badRequest("DOCUMENT_REQUIRED",
                    "مرجع المستند مطلوب للتحويل بين المستودعات.",
                    "A document reference is required for a stock transfer.");
        }
        String reason = ClinicalSupport.optionalTrim(body, "reason", 500);
        List<StockLine> lines = parseLines(body);
        long fromId = Sql.longValue(from, "id", 0);
        long toId = Sql.longValue(to, "id", 0);

        for (StockLine line : lines) {
            Map<String, Object> item = requireItem(line.itemPublicId());
            if (repository.stockForItem(Sql.longValue(item, "id", 0), fromId) < line.quantity()) {
                throw AppException.unprocessable("INSUFFICIENT_STOCK",
                        "الكمية المتاحة من " + Sql.text(item, "code") + " غير كافية للتحويل.",
                        "Insufficient stock for item " + Sql.text(item, "code")
                                + " in the source warehouse.");
            }
        }

        String publicId = Ulid.next();
        String number = number("STOCK_TRANSFER", "TRF");
        long transferId = repository.insertTransfer(publicId, number, fromId, toId,
                documentRef == null ? number : documentRef, reason, actor.getUserId());
        List<Map<String, Object>> moved = new ArrayList<>();
        for (StockLine line : lines) {
            Map<String, Object> item = requireItem(line.itemPublicId());
            long itemId = Sql.longValue(item, "id", 0);
            List<Map<String, Object>> sourceBatches = repository.fefoBatches(itemId, fromId);
            int remaining = line.quantity();
            for (Map<String, Object> source : sourceBatches) {
                if (remaining <= 0) {
                    break;
                }
                int available = Sql.intValue(source, "quantityAvailable", 0);
                int take = Math.min(available, remaining);
                if (take <= 0) {
                    continue;
                }
                int left = available - take;
                if (repository.updateBatchQuantity(Sql.longValue(source, "id", 0),
                        Sql.longValue(source, "version", 0), left,
                        left == 0 ? "DEPLETED" : "ACTIVE", actor.getUserId()) == 0) {
                    throw staleVersion();
                }
                repository.insertMovement(Ulid.next(), itemId, Sql.longValue(source, "id", 0),
                        fromId, "TRANSFER_OUT", -take, Sql.decimal(source, "unitCost"),
                        "STOCK_TRANSFER", publicId, documentRef, actor.getUserId());

                String batchNumber = Sql.text(source, "batchNumber");
                LocalDate expiry = source.get("expiryDate") == null ? null
                        : LocalDate.parse(source.get("expiryDate").toString());
                Map<String, Object> target = repository.lockBatchByNumber(itemId, toId, batchNumber);
                String targetPublicId;
                if (target != null) {
                    Map<String, Object> lockedTarget =
                            repository.lockBatch(Sql.longValue(target, "id", 0));
                    if (repository.incrementBatch(Sql.longValue(lockedTarget, "id", 0),
                            Sql.longValue(lockedTarget, "version", 0), take,
                            actor.getUserId()) == 0) {
                        throw staleVersion();
                    }
                    targetPublicId = Sql.text(lockedTarget, "publicId");
                } else {
                    targetPublicId = Ulid.next();
                    Long supplierId = source.get("supplierId") == null ? null
                            : Sql.longValue(source, "supplierId");
                    repository.insertBatch(targetPublicId, itemId, toId, supplierId, batchNumber,
                            Sql.text(source, "lotNumber"), expiry, take,
                            Sql.decimal(source, "unitCost"), actor.getUserId());
                }
                repository.insertMovement(Ulid.next(), itemId,
                        Sql.longValue(repository.batchByPublicId(targetPublicId), "id", 0), toId,
                        "TRANSFER_IN", take, Sql.decimal(source, "unitCost"), "STOCK_TRANSFER",
                        publicId, documentRef, actor.getUserId());
                repository.insertTransferItem(Ulid.next(), transferId, itemId,
                        Sql.longValue(source, "id", 0),
                        Sql.longValue(repository.batchByPublicId(targetPublicId), "id", 0), take,
                        Sql.decimal(source, "unitCost"));
                moved.add(ClinicalSupport.map("itemPublicId", line.itemPublicId(),
                        "code", Sql.text(item, "code"), "batchNumber", batchNumber,
                        "quantity", take, "unitCost", Sql.decimal(source, "unitCost")));
                remaining -= take;
            }
            if (remaining > 0) {
                throw AppException.unprocessable("INSUFFICIENT_STOCK",
                        "الكمية المتاحة غير كافية لإتمام التحويل.",
                        "Insufficient stock to complete the transfer.");
            }
        }
        audit.record(actor.getUserId(), "INVENTORY_TRANSFERRED", "STOCK_TRANSFER", publicId, null,
                ClinicalSupport.json("from", Sql.text(from, "code"), "to", Sql.text(to, "code"),
                        "documentRef", documentRef, "lines", moved.size()), reason, ip);
        Map<String, Object> view = transferView(repository.transferByPublicId(publicId));
        view.put("items", repository.transferItems(transferId));
        return view;
    }

    @Override
    public Map<String, Object> listTransfers(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countTransfers();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.transfers(safePage * safeSize, safeSize)) {
            items.add(transferView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> listMovements(String itemPublicId, String warehousePublicId,
                                             String type, LocalDate from, LocalDate to,
                                             int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(type, null);
        long total = repository.countMovements(itemPublicId, warehousePublicId, normalized,
                from, to);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.movements(itemPublicId, warehousePublicId,
                normalized, from, to, safePage * safeSize, safeSize)) {
            items.add(movementView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    // ------------------------------------------------------------------
    // Alerts and reports
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> alerts() {
        int warningDays = intSetting("inventory.expiry.warning_days", 60);
        List<Map<String, Object>> low = new ArrayList<>();
        for (Map<String, Object> row : repository.lowStock(100)) {
            low.add(ClinicalSupport.map("itemPublicId", row.get("itemPublicId"),
                    "code", row.get("code"), "nameAr", row.get("nameAr"),
                    "nameEn", row.get("nameEn"), "baseUnit", row.get("baseUnit"),
                    "warehousePublicId", row.get("warehousePublicId"),
                    "warehouseCode", row.get("warehouseCode"),
                    "warehouseNameAr", row.get("warehouseAr"),
                    "warehouseNameEn", row.get("warehouseEn"),
                    "available", row.get("available"), "reorderLevel", row.get("reorderLevel"),
                    "reorderQuantity", row.get("reorderQuantity"),
                    "shortage", Sql.longValue(row, "reorderLevel", 0)
                            - Sql.longValue(row, "available", 0)));
        }
        List<Map<String, Object>> expiring = new ArrayList<>();
        for (Map<String, Object> row : repository.expiringBatches(
                LocalDate.now().plusDays(warningDays), 100)) {
            expiring.add(ClinicalSupport.map("batchPublicId", row.get("publicId"),
                    "batchNumber", row.get("batchNumber"), "expiryDate", row.get("expiryDate"),
                    "quantityAvailable", row.get("quantityAvailable"),
                    "itemPublicId", row.get("itemPublicId"), "code", row.get("code"),
                    "nameAr", row.get("nameAr"), "nameEn", row.get("nameEn"),
                    "warehouseCode", row.get("warehouseCode"),
                    "warehouseNameAr", row.get("warehouseAr"),
                    "warehouseNameEn", row.get("warehouseEn"),
                    "daysLeft", row.get("daysLeft")));
        }
        return ClinicalSupport.map("lowStock", low, "expiring", expiring,
                "expiredCount", repository.expiredBatchCount(),
                "warningDays", warningDays,
                "generatedAt", LocalDateTime.now().toString());
    }

    @Override
    public Map<String, Object> valuation(String warehousePublicId) {
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : repository.valuation(warehousePublicId)) {
            BigDecimal value = Sql.decimal(row, "value");
            if (value != null) {
                total = total.add(value);
            }
            items.add(ClinicalSupport.map("itemPublicId", row.get("itemPublicId"),
                    "code", row.get("code"), "nameAr", row.get("nameAr"),
                    "nameEn", row.get("nameEn"), "baseUnit", row.get("baseUnit"),
                    "warehousePublicId", row.get("warehousePublicId"),
                    "warehouseCode", row.get("warehouseCode"),
                    "quantity", row.get("quantity"), "value", value,
                    "averageCost", row.get("averageCost")));
        }
        return ClinicalSupport.map("items", items, "totalValue", money(total),
                "method", setting("inventory.valuation.method", "BATCH_COST"),
                "currency", setting("billing.currency", "JOD"));
    }

    @Override
    public Map<String, Object> consumption(LocalDate from, LocalDate to, String itemPublicId) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "تاريخ النهاية قبل تاريخ البداية.", "The end date precedes the start date.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        long issued = 0;
        long writtenOff = 0;
        long transferred = 0;
        for (Map<String, Object> row : repository.consumption(start, end, itemPublicId)) {
            long rowIssued = Sql.longValue(row, "issued", 0);
            long rowWritten = Sql.longValue(row, "writtenOff", 0);
            long rowTransferred = Sql.longValue(row, "transferredOut", 0);
            issued += rowIssued;
            writtenOff += rowWritten;
            transferred += rowTransferred;
            items.add(ClinicalSupport.map("itemPublicId", row.get("itemPublicId"),
                    "code", row.get("code"), "nameAr", row.get("nameAr"),
                    "nameEn", row.get("nameEn"), "baseUnit", row.get("baseUnit"),
                    "issued", rowIssued, "writtenOff", rowWritten,
                    "transferredOut", rowTransferred,
                    "movementCount", Sql.longValue(row, "movementCount", 0)));
        }
        return ClinicalSupport.map("from", start.toString(), "to", end.toString(),
                "items", items, "totalIssued", issued, "totalWrittenOff", writtenOff,
                "totalTransferredOut", transferred);
    }

    // ------------------------------------------------------------------
    // Stock counts
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listCounts(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(status, null);
        long total = repository.countCounts(normalized);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.counts(normalized, safePage * safeSize,
                safeSize)) {
            items.add(countView(row, false));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> count(String publicId) {
        return countView(requireCount(publicId), true);
    }

    @Override
    public Map<String, Object> createCount(Map<String, Object> body, AuthPrincipal actor,
                                           String ip) {
        Map<String, Object> warehouse = requireWarehouse(ClinicalSupport.required(body,
                "warehousePublicId", "المستودع مطلوب.", "Warehouse is required."));
        long warehouseId = Sql.longValue(warehouse, "id", 0);
        List<Map<String, Object>> batches = repository.batchesForCount(warehouseId);
        if (batches.isEmpty()) {
            throw AppException.unprocessable("NO_STOCK_TO_COUNT",
                    "لا توجد دفعات نشطة في هذا المستودع.",
                    "There are no active batches in this warehouse.");
        }
        String reason = ClinicalSupport.optionalTrim(body, "reason", 500);
        String publicId = Ulid.next();
        String number = number("STOCK_COUNT", "CNT");
        long countId = repository.insertCount(publicId, number, warehouseId, reason,
                actor.getUserId());
        for (Map<String, Object> batch : batches) {
            repository.insertCountLine(Ulid.next(), countId,
                    Sql.longValue(batch, "itemId", 0), Sql.longValue(batch, "id", 0),
                    Sql.intValue(batch, "quantityAvailable", 0));
        }
        audit.record(actor.getUserId(), "INVENTORY_COUNT_STARTED", "STOCK_COUNT", publicId, null,
                ClinicalSupport.json("countNumber", number, "warehouse",
                        Sql.text(warehouse, "code"), "lines", batches.size()), reason, ip);
        return countView(repository.countByPublicId(publicId), true);
    }

    @Override
    public Map<String, Object> updateCountLine(String countPublicId, String linePublicId,
                                               Map<String, Object> body, AuthPrincipal actor) {
        Map<String, Object> count = requireCount(countPublicId);
        if (!"COUNTING".equals(Sql.text(count, "status"))) {
            throw AppException.conflict("COUNT_NOT_OPEN",
                    "لا يمكن تعديل جرد منتهٍ.", "A posted count cannot be edited.");
        }
        Map<String, Object> line = repository.countLine(Sql.longValue(count, "id", 0),
                linePublicId);
        if (line == null) {
            throw AppException.notFound("COUNT_LINE_NOT_FOUND",
                    "سطر الجرد غير موجود.", "Count line not found.");
        }
        int counted = requireNonNegativeInt(body, "countedQuantity",
                "الكمية المعدودة مطلوبة ولا يمكن أن تكون سالبة.",
                "The counted quantity is required and cannot be negative.");
        repository.updateCountLine(Sql.longValue(line, "id", 0), counted);
        return countView(repository.countByPublicId(countPublicId), true);
    }

    @Override
    public Map<String, Object> postCount(String countPublicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip) {
        Map<String, Object> found = requireCount(countPublicId);
        long countId = Sql.longValue(found, "id", 0);
        Map<String, Object> count = repository.lockCount(countId);
        if (!"COUNTING".equals(Sql.text(count, "status"))) {
            throw AppException.conflict("COUNT_NOT_OPEN",
                    "الجرد مرحّل مسبقًا أو ملغى.", "The count is already posted or cancelled.");
        }
        List<Map<String, Object>> lines = repository.countLines(countId);
        boolean hasVariance = false;
        for (Map<String, Object> line : lines) {
            if (line.get("variance") != null && Sql.longValue(line, "variance", 0) != 0) {
                hasVariance = true;
                break;
            }
        }
        String reason = ClinicalSupport.optional(body, "reason");
        if (hasVariance && reason == null
                && boolSetting("inventory.count.variance.require_reason", true)) {
            throw AppException.badRequest("REASON_REQUIRED",
                    "سبب الفرق في الجرد مطلوب.", "A reason for the count variance is required.");
        }
        long approverId = actor.getUserId();
        if (!actor.hasPermission("INV:ADJUST")) {
            String approverPublicId = ClinicalSupport.optional(body, "approvedByPublicId");
            if (approverPublicId == null) {
                throw AppException.forbidden("ADJUST_APPROVAL_REQUIRED",
                        "ترحيل الجرد يتطلب موافقة مسؤول مخزون.",
                        "Posting a count requires inventory-manager approval.");
            }
            Map<String, Object> approver = repository.userByPublicId(approverPublicId);
            if (approver == null || !"ACTIVE".equals(Sql.text(approver, "status"))) {
                throw AppException.unprocessable("APPROVER_NOT_FOUND",
                        "المستخدم المعتمد غير موجود أو غير مفعّل.",
                        "The approving user is missing or inactive.");
            }
            approverId = Sql.longValue(approver, "id", 0);
            if (!repository.userHasPermission(approverId, "INV:ADJUST")) {
                throw AppException.forbidden("APPROVER_NOT_AUTHORIZED",
                        "المستخدم المحدد لا يملك صلاحية تعديل المخزون.",
                        "The selected user cannot adjust stock.");
            }
        }
        List<Map<String, Object>> adjustments = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            if (line.get("countedQuantity") == null) {
                continue;
            }
            int counted = Sql.intValue(line, "countedQuantity", 0);
            Map<String, Object> batch = repository.lockBatch(
                    Sql.longValue(line, "batchId", 0));
            int current = Sql.intValue(batch, "quantityAvailable", 0);
            int delta = counted - current;
            if (delta == 0) {
                continue;
            }
            if (repository.updateBatchQuantity(Sql.longValue(batch, "id", 0),
                    Sql.longValue(batch, "version", 0), counted,
                    counted == 0 ? "DEPLETED" : "ACTIVE", approverId) == 0) {
                throw staleVersion();
            }
            repository.insertMovement(Ulid.next(), Sql.longValue(line, "itemId", 0),
                    Sql.longValue(batch, "id", 0), Sql.longValue(count, "warehouseId", 0),
                    "ADJUSTMENT", delta, Sql.decimal(batch, "unitCost"), "STOCK_COUNT",
                    countPublicId, reason == null ? "Stock count adjustment" : reason,
                    approverId);
            adjustments.add(ClinicalSupport.map("batchPublicId", line.get("batchPublicId"),
                    "batchNumber", line.get("batchNumber"), "code", line.get("code"),
                    "systemQuantity", line.get("systemQuantity"), "countedQuantity", counted,
                    "variance", delta));
        }
        if (repository.updateCountStatus(countId, Sql.longValue(count, "version", 0), "POSTED",
                reason, approverId) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "INVENTORY_COUNT_POSTED", "STOCK_COUNT", countPublicId,
                ClinicalSupport.json("status", "COUNTING"),
                ClinicalSupport.json("status", "POSTED", "adjustments", adjustments.size(),
                        "approvedBy", approverId, "reason", reason), reason, ip);
        return countView(repository.countByPublicId(countPublicId), true);
    }

    // ------------------------------------------------------------------
    // Purchase requests and orders
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listPurchaseRequests(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(status, null);
        long total = repository.countPurchaseRequests(normalized);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.purchaseRequests(normalized,
                safePage * safeSize, safeSize)) {
            items.add(ClinicalSupport.map("publicId", row.get("publicId"),
                    "requestNumber", row.get("requestNumber"), "status", row.get("status"),
                    "requestedAt", row.get("requestedAt"), "approvedAt", row.get("approvedAt"),
                    "warehouseCode", row.get("warehouseCode"),
                    "warehouseNameAr", row.get("warehouseAr"),
                    "warehouseNameEn", row.get("warehouseEn"),
                    "lineCount", row.get("lineCount"), "version", row.get("version")));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> createPurchaseRequest(Map<String, Object> body,
                                                     AuthPrincipal actor, String ip) {
        Map<String, Object> warehouse = requireWarehouse(ClinicalSupport.required(body,
                "warehousePublicId", "المستودع مطلوب.", "Warehouse is required."));
        String status = upper(ClinicalSupport.optional(body, "status"), "SUBMITTED");
        if (!"DRAFT".equals(status) && !"SUBMITTED".equals(status)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة طلب الشراء غير صحيحة.", "Invalid purchase request status.");
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        List<StockLine> lines = parseLines(body);
        String publicId = Ulid.next();
        String number = number("PURCHASE_REQUEST", "PR");
        long requestId = repository.insertPurchaseRequest(publicId, number,
                Sql.longValue(warehouse, "id", 0), status, notes, actor.getUserId());
        for (StockLine line : lines) {
            repository.insertPurchaseRequestItem(Ulid.next(), requestId,
                    itemIdOf(line.itemPublicId()), line.quantity(), line.notes());
        }
        audit.record(actor.getUserId(), "PURCHASE_REQUEST_CREATED", "PURCHASE_REQUEST", publicId,
                null, ClinicalSupport.json("requestNumber", number, "status", status,
                        "warehouse", Sql.text(warehouse, "code"), "lines", lines.size()),
                notes, ip);
        return purchaseRequestView(repository.purchaseRequestByPublicId(publicId));
    }

    @Override
    public Map<String, Object> approvePurchaseRequest(String publicId, Map<String, Object> body,
                                                      AuthPrincipal actor, String ip) {
        Map<String, Object> found = requirePurchaseRequest(publicId);
        long requestId = Sql.longValue(found, "id", 0);
        Map<String, Object> request = repository.lockPurchaseRequest(requestId);
        String currentStatus = Sql.text(request, "status");
        if (!"SUBMITTED".equals(currentStatus)) {
            throw AppException.conflict("REQUEST_NOT_SUBMITTED",
                    "لا يمكن اعتماد طلب في حالته الحالية.",
                    "Only a submitted request can be approved or rejected.");
        }
        String decision = upper(ClinicalSupport.optional(body, "decision"), "APPROVE");
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "القرار يجب أن يكون APPROVE أو REJECT.",
                    "The decision must be APPROVE or REJECT.");
        }
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        if (repository.updatePurchaseRequestStatus(requestId,
                Sql.longValue(request, "version", 0), status, actor.getUserId(),
                "APPROVED".equals(status)) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "PURCHASE_REQUEST_" + status, "PURCHASE_REQUEST",
                publicId, ClinicalSupport.json("status", currentStatus),
                ClinicalSupport.json("status", status, "notes", notes), notes, ip);
        if ("APPROVED".equals(status)) {
            outbox.enqueue("PURCHASE_REQUEST_APPROVED", "PURCHASE_REQUEST", publicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "roles", List.of("STOREKEEPER", "FIN_MANAGER"),
                            "type", "PURCHASE_REQUEST_APPROVED",
                            "titleAr", "اعتماد طلب شراء",
                            "titleEn", "Purchase request approved",
                            "bodyAr", "تم اعتماد الطلب " + Sql.text(request, "requestNumber"),
                            "bodyEn", "Request " + Sql.text(request, "requestNumber")
                                    + " was approved",
                            "severity", "INFO",
                            "entityType", "PURCHASE_REQUEST",
                            "entityPublicId", publicId,
                            "dedupeKey", "PURCHASE_REQUEST_APPROVED:" + publicId))));
        }
        return purchaseRequestView(repository.purchaseRequestByPublicId(publicId));
    }

    @Override
    public Map<String, Object> listPurchaseOrders(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        String normalized = upper(status, null);
        long total = repository.countPurchaseOrders(normalized);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.purchaseOrders(normalized,
                safePage * safeSize, safeSize)) {
            items.add(ClinicalSupport.map("publicId", row.get("publicId"),
                    "poNumber", row.get("poNumber"), "status", row.get("status"),
                    "orderedAt", row.get("orderedAt"), "expectedAt", row.get("expectedAt"),
                    "totalAmount", row.get("totalAmount"),
                    "supplierCode", row.get("supplierCode"),
                    "supplierNameAr", row.get("supplierAr"),
                    "supplierNameEn", row.get("supplierEn"),
                    "warehouseCode", row.get("warehouseCode"),
                    "warehouseNameAr", row.get("warehouseAr"),
                    "warehouseNameEn", row.get("warehouseEn"),
                    "version", row.get("version")));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> purchaseOrder(String publicId) {
        return purchaseOrderView(requirePurchaseOrder(publicId), true);
    }

    @Override
    public Map<String, Object> createPurchaseOrder(Map<String, Object> body, AuthPrincipal actor,
                                                   String ip) {
        Map<String, Object> supplier = requireSupplier(ClinicalSupport.required(body,
                "supplierPublicId", "المورد مطلوب.", "Supplier is required."));
        Map<String, Object> warehouse = requireWarehouse(ClinicalSupport.required(body,
                "warehousePublicId", "المستودع مطلوب.", "Warehouse is required."));
        String requestPublicId = ClinicalSupport.optional(body, "purchaseRequestPublicId");
        Map<String, Object> request = null;
        if (requestPublicId != null) {
            request = requirePurchaseRequest(requestPublicId);
            Map<String, Object> locked = repository.lockPurchaseRequest(
                    Sql.longValue(request, "id", 0));
            if (!"APPROVED".equals(Sql.text(locked, "status"))) {
                throw AppException.conflict("REQUEST_NOT_APPROVED",
                        "طلب الشراء يجب أن يكون معتمدًا قبل تحويله لأمر شراء.",
                        "The purchase request must be approved before ordering.");
            }
            request = locked;
        } else if (boolSetting("inventory.po.require_request", true)) {
            throw AppException.unprocessable("PURCHASE_REQUEST_REQUIRED",
                    "أمر الشراء يتطلب طلب شراء معتمدًا.",
                    "A purchase order requires an approved purchase request.");
        }

        List<StockLine> lines = parseLines(body);
        if (lines.isEmpty() && request != null) {
            List<StockLine> copied = new ArrayList<>();
            for (Map<String, Object> row : repository.purchaseRequestItems(
                    Sql.longValue(request, "id", 0))) {
                copied.add(new StockLine(Sql.text(row, "itemPublicId"), null,
                        Sql.intValue(row, "quantity", 0), null, null, null));
            }
            lines = copied;
        }
        if (lines.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "أمر الشراء يحتاج بندًا واحدًا على الأقل.",
                    "A purchase order needs at least one line.");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> prepared = new ArrayList<>();
        for (StockLine line : lines) {
            Map<String, Object> item = requireItem(line.itemPublicId());
            BigDecimal unitCost = line.unitCost();
            if (unitCost == null) {
                unitCost = Sql.decimal(item, "unitCost");
            }
            if (unitCost == null) {
                unitCost = BigDecimal.ZERO;
            }
            if (unitCost.signum() < 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "تكلفة الوحدة لا يمكن أن تكون سالبة.",
                        "The unit cost cannot be negative.");
            }
            total = total.add(unitCost.multiply(BigDecimal.valueOf(line.quantity())));
            prepared.add(ClinicalSupport.map("itemId", Sql.longValue(item, "id", 0),
                    "itemPublicId", line.itemPublicId(), "quantity", line.quantity(),
                    "unitCost", money(unitCost)));
        }
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        LocalDate expectedAt = ClinicalSupport.date(body, "expectedAt", false);
        String publicId = Ulid.next();
        String number = number("PURCHASE_ORDER", "PO");
        long poId = repository.insertPurchaseOrder(publicId, number,
                Sql.longValue(supplier, "id", 0), Sql.longValue(warehouse, "id", 0),
                request == null ? null : Sql.longValue(request, "id", 0), "ORDERED", expectedAt,
                money(total), notes, actor.getUserId());
        for (Map<String, Object> line : prepared) {
            repository.insertPurchaseOrderItem(Ulid.next(), poId,
                    Sql.longValue(line, "itemId", 0), Sql.intValue(line, "quantity", 0),
                    Sql.decimal(line, "unitCost"));
        }
        if (request != null) {
            repository.updatePurchaseRequestStatus(Sql.longValue(request, "id", 0),
                    Sql.longValue(request, "version", 0), "CONVERTED", actor.getUserId(), false);
        }
        audit.record(actor.getUserId(), "PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", publicId,
                null, ClinicalSupport.json("poNumber", number, "supplier",
                        Sql.text(supplier, "code"), "warehouse", Sql.text(warehouse, "code"),
                        "total", money(total), "lines", prepared.size(),
                        "request", requestPublicId), notes, ip);
        return purchaseOrderView(repository.purchaseOrderByPublicId(publicId), true);
    }

    @Override
    public Map<String, Object> receivePurchaseOrder(String publicId, Map<String, Object> body,
                                                    AuthPrincipal actor, String ip) {
        Map<String, Object> found = requirePurchaseOrder(publicId);
        long poId = Sql.longValue(found, "id", 0);
        Map<String, Object> order = repository.lockPurchaseOrder(poId);
        String status = Sql.text(order, "status");
        if ("CANCELLED".equals(status) || "RECEIVED".equals(status)) {
            throw AppException.conflict("PO_NOT_RECEIVABLE",
                    "أمر الشراء مغلق ولا يمكن استلام كميات إضافية.",
                    "The purchase order is closed and cannot receive more stock.");
        }
        List<StockLine> lines = parseLines(body);
        long warehouseId = Sql.longValue(order, "warehouseId", 0);
        Long supplierId = Sql.longValue(order, "supplierId");
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        String grnPublicId = Ulid.next();
        String grnNumber = number("GOODS_RECEIPT", "GRN");
        long receiptId = repository.insertGoodsReceipt(grnPublicId, grnNumber, poId,
                warehouseId, notes, actor.getUserId());
        List<Map<String, Object>> received = new ArrayList<>();
        for (StockLine line : lines) {
            Map<String, Object> item = requireItem(line.itemPublicId());
            long itemId = Sql.longValue(item, "id", 0);
            Map<String, Object> poItem = repository.purchaseOrderItem(poId, line.itemPublicId());
            if (poItem == null) {
                throw AppException.unprocessable("ITEM_NOT_ON_ORDER",
                        "الصنف " + Sql.text(item, "code") + " غير موجود في أمر الشراء.",
                        "Item " + Sql.text(item, "code") + " is not on the purchase order.");
            }
            int ordered = Sql.intValue(poItem, "quantityOrdered", 0);
            int already = Sql.intValue(poItem, "quantityReceived", 0);
            if (line.quantity() > ordered - already) {
                throw AppException.unprocessable("OVER_RECEIPT",
                        "الكمية المستلمة تتجاوز المتبقي من أمر الشراء للصنف "
                                + Sql.text(item, "code") + ".",
                        "The received quantity exceeds the outstanding ordered quantity for item "
                                + Sql.text(item, "code") + ".");
            }
            Map<String, Object> raw = line.raw() == null ? Map.of() : line.raw();
            String batchNumber = ClinicalSupport.optional(raw, "batchNumber");
            if (batchNumber == null) {
                batchNumber = grnNumber + "-" + Sql.text(item, "code");
            }
            String lotNumber = ClinicalSupport.optionalTrim(raw, "lotNumber", 60);
            LocalDate expiryDate = ClinicalSupport.date(raw, "expiryDate", false);
            checkExpiry(item, expiryDate);
            BigDecimal unitCost = Sql.decimal(poItem, "unitCost");
            if (unitCost == null) {
                unitCost = BigDecimal.ZERO;
            }
            String batchPublicId;
            Map<String, Object> existingBatch =
                    repository.lockBatchByNumber(itemId, warehouseId, batchNumber);
            if (existingBatch != null) {
                Map<String, Object> locked = repository.lockBatch(
                        Sql.longValue(existingBatch, "id", 0));
                if (repository.incrementBatch(Sql.longValue(locked, "id", 0),
                        Sql.longValue(locked, "version", 0), line.quantity(),
                        actor.getUserId()) == 0) {
                    throw staleVersion();
                }
                batchPublicId = Sql.text(locked, "publicId");
            } else {
                batchPublicId = Ulid.next();
                repository.insertBatch(batchPublicId, itemId, warehouseId, supplierId, batchNumber,
                        lotNumber, expiryDate, line.quantity(), money(unitCost),
                        actor.getUserId());
            }
            long batchId = Sql.longValue(repository.batchByPublicId(batchPublicId), "id", 0);
            repository.insertGoodsReceiptItem(Ulid.next(), receiptId,
                    Sql.longValue(poItem, "id", 0), itemId, batchId, batchNumber, expiryDate,
                    line.quantity(), money(unitCost));
            repository.addReceivedQuantity(Sql.longValue(poItem, "id", 0), line.quantity());
            repository.insertMovement(Ulid.next(), itemId, batchId, warehouseId, "RECEIPT",
                    line.quantity(), money(unitCost), "GOODS_RECEIPT", grnPublicId, grnNumber,
                    actor.getUserId());
            received.add(ClinicalSupport.map("itemPublicId", line.itemPublicId(),
                    "code", Sql.text(item, "code"), "nameAr", Sql.text(item, "nameAr"),
                    "quantity", line.quantity(), "batchNumber", batchNumber,
                    "expiryDate", expiryDate == null ? null : expiryDate.toString(),
                    "unitCost", money(unitCost)));
        }
        boolean complete = true;
        for (Map<String, Object> poItem : repository.purchaseOrderItems(poId)) {
            if (Sql.intValue(poItem, "quantityReceived", 0)
                    < Sql.intValue(poItem, "quantityOrdered", 0)) {
                complete = false;
                break;
            }
        }
        String newStatus = complete ? "RECEIVED" : "PARTIALLY_RECEIVED";
        if (repository.updatePurchaseOrderStatus(poId, Sql.longValue(order, "version", 0),
                newStatus, actor.getUserId()) == 0) {
            throw staleVersion();
        }
        audit.record(actor.getUserId(), "GOODS_RECEIVED", "PURCHASE_ORDER", publicId, null,
                ClinicalSupport.json("grnNumber", grnNumber, "status", newStatus,
                        "lines", received.size()), notes, ip);
        Map<String, Object> view = purchaseOrderView(
                repository.purchaseOrderByPublicId(publicId), true);
        view.put("receipt", ClinicalSupport.map("publicId", grnPublicId, "grnNumber", grnNumber,
                "items", received, "receivedAt", LocalDateTime.now().toString()));
        return view;
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    private Map<String, Object> warehouseView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return ClinicalSupport.map("publicId", row.get("publicId"), "code", row.get("code"),
                "nameAr", row.get("nameAr"), "nameEn", row.get("nameEn"),
                "warehouseType", row.get("warehouseType"), "location", row.get("location"),
                "active", row.get("active"), "batchCount", row.get("batchCount"),
                "totalQuantity", row.get("totalQuantity"), "version", row.get("version"));
    }

    private Map<String, Object> itemView(Map<String, Object> row, boolean details) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = ClinicalSupport.map("publicId", row.get("publicId"),
                "code", row.get("code"), "sku", row.get("sku"), "nameAr", row.get("nameAr"),
                "nameEn", row.get("nameEn"), "category", row.get("category"),
                "baseUnit", row.get("baseUnit"), "trackExpiry", row.get("trackExpiry"),
                "unitCost", row.get("unitCost"), "barcode", row.get("barcode"),
                "active", row.get("active"), "stockOnHand", row.get("stockOnHand"),
                "reorderLevel", row.get("reorderLevel"), "version", row.get("version"));
        if (details) {
            long itemId = Sql.longValue(row, "id", 0);
            view.put("reorderPoints", repository.reorderPoints(itemId));
            List<Map<String, Object>> batches = new ArrayList<>();
            for (Map<String, Object> batch : repository.batches(null,
                    Sql.text(row, "publicId"), null, null, null, 0, 50)) {
                batches.add(batchView(batch));
            }
            view.put("batches", batches);
        }
        return view;
    }

    private Map<String, Object> supplierView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return ClinicalSupport.map("publicId", row.get("publicId"), "code", row.get("code"),
                "nameAr", row.get("nameAr"), "nameEn", row.get("nameEn"),
                "phone", row.get("phone"), "email", row.get("email"),
                "address", row.get("address"), "contactPerson", row.get("contactPerson"),
                "active", row.get("active"), "version", row.get("version"));
    }

    private Map<String, Object> batchView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return ClinicalSupport.map("publicId", row.get("publicId"),
                "batchNumber", row.get("batchNumber"), "lotNumber", row.get("lotNumber"),
                "expiryDate", row.get("expiryDate"),
                "quantityReceived", row.get("quantityReceived"),
                "quantityAvailable", row.get("quantityAvailable"),
                "unitCost", row.get("unitCost"), "status", row.get("status"),
                "receivedAt", row.get("receivedAt"), "version", row.get("version"),
                "itemPublicId", row.get("itemPublicId"), "code", row.get("code"),
                "nameAr", row.get("nameAr"), "nameEn", row.get("nameEn"),
                "baseUnit", row.get("baseUnit"), "trackExpiry", row.get("trackExpiry"),
                "warehousePublicId", row.get("warehousePublicId"),
                "warehouseCode", row.get("warehouseCode"),
                "warehouseNameAr", row.get("warehouseAr"),
                "warehouseNameEn", row.get("warehouseEn"),
                "supplierNameAr", row.get("supplierAr"),
                "supplierNameEn", row.get("supplierEn"));
    }

    private Map<String, Object> movementView(Map<String, Object> row) {
        return ClinicalSupport.map("publicId", row.get("publicId"),
                "movementType", row.get("movementType"),
                "quantityDelta", row.get("quantityDelta"), "unitCost", row.get("unitCost"),
                "referenceType", row.get("referenceType"),
                "referencePublicId", row.get("referencePublicId"), "reason", row.get("reason"),
                "performedAt", row.get("performedAt"),
                "itemPublicId", row.get("itemPublicId"), "code", row.get("code"),
                "nameAr", row.get("nameAr"), "nameEn", row.get("nameEn"),
                "baseUnit", row.get("baseUnit"),
                "batchPublicId", row.get("batchPublicId"),
                "batchNumber", row.get("batchNumber"), "expiryDate", row.get("expiryDate"),
                "warehousePublicId", row.get("warehousePublicId"),
                "warehouseCode", row.get("warehouseCode"),
                "performedByAr", row.get("performedByAr"),
                "performedByEn", row.get("performedByEn"));
    }

    private Map<String, Object> transferView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        return ClinicalSupport.map("publicId", row.get("publicId"),
                "transferNumber", row.get("transferNumber"),
                "documentRef", row.get("documentRef"), "reason", row.get("reason"),
                "status", row.get("status"), "transferredAt", row.get("transferredAt"),
                "fromWarehousePublicId", row.get("fromPublicId"),
                "fromWarehouseCode", row.get("fromCode"),
                "fromWarehouseNameAr", row.get("fromAr"),
                "fromWarehouseNameEn", row.get("fromEn"),
                "toWarehousePublicId", row.get("toPublicId"),
                "toWarehouseCode", row.get("toCode"),
                "toWarehouseNameAr", row.get("toAr"),
                "toWarehouseNameEn", row.get("toEn"),
                "transferredByAr", row.get("byAr"), "transferredByEn", row.get("byEn"),
                "version", row.get("version"));
    }

    private Map<String, Object> countView(Map<String, Object> row, boolean details) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = ClinicalSupport.map("publicId", row.get("publicId"),
                "countNumber", row.get("countNumber"), "status", row.get("status"),
                "reason", row.get("reason"), "countedAt", row.get("countedAt"),
                "postedAt", row.get("postedAt"),
                "warehousePublicId", row.get("warehousePublicId"),
                "warehouseCode", row.get("warehouseCode"),
                "warehouseNameAr", row.get("warehouseAr"),
                "warehouseNameEn", row.get("warehouseEn"),
                "countedByAr", row.get("countedByAr"), "countedByEn", row.get("countedByEn"),
                "version", row.get("version"));
        if (details) {
            view.put("lines", repository.countLines(Sql.longValue(row, "id", 0)));
        }
        return view;
    }

    private Map<String, Object> purchaseRequestView(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = ClinicalSupport.map("publicId", row.get("publicId"),
                "requestNumber", row.get("requestNumber"), "status", row.get("status"),
                "notes", row.get("notes"), "requestedAt", row.get("requestedAt"),
                "approvedAt", row.get("approvedAt"),
                "warehousePublicId", row.get("warehousePublicId"),
                "warehouseCode", row.get("warehouseCode"),
                "warehouseNameAr", row.get("warehouseAr"),
                "warehouseNameEn", row.get("warehouseEn"),
                "requestedByAr", row.get("byAr"), "requestedByEn", row.get("byEn"),
                "version", row.get("version"));
        view.put("items", repository.purchaseRequestItems(Sql.longValue(row, "id", 0)));
        return view;
    }

    private Map<String, Object> purchaseOrderView(Map<String, Object> row, boolean details) {
        if (row == null) {
            return null;
        }
        Map<String, Object> view = ClinicalSupport.map("publicId", row.get("publicId"),
                "poNumber", row.get("poNumber"), "status", row.get("status"),
                "orderedAt", row.get("orderedAt"), "expectedAt", row.get("expectedAt"),
                "totalAmount", row.get("totalAmount"), "notes", row.get("notes"),
                "supplierPublicId", row.get("supplierPublicId"),
                "supplierCode", row.get("supplierCode"),
                "supplierNameAr", row.get("supplierAr"),
                "supplierNameEn", row.get("supplierEn"),
                "warehousePublicId", row.get("warehousePublicId"),
                "warehouseCode", row.get("warehouseCode"),
                "warehouseNameAr", row.get("warehouseAr"),
                "warehouseNameEn", row.get("warehouseEn"),
                "purchaseRequestPublicId", row.get("requestPublicId"),
                "purchaseRequestNumber", row.get("requestNumber"),
                "version", row.get("version"));
        if (details) {
            long poId = Sql.longValue(row, "id", 0);
            view.put("items", repository.purchaseOrderItems(poId));
            view.put("receipts", repository.goodsReceipts(poId));
        }
        return view;
    }

    // ------------------------------------------------------------------
    // Stock engine
    // ------------------------------------------------------------------

    /** Consumes stock FEFO and returns the value taken. Throws if stock is short. */
    private BigDecimal consume(long itemId, long warehouseId, int quantity, String movementType,
                               String referenceType, String referencePublicId, String reason,
                               long actorId, List<Map<String, Object>> lines) {
        List<Map<String, Object>> batches = repository.fefoBatches(itemId, warehouseId);
        int remaining = quantity;
        BigDecimal value = BigDecimal.ZERO;
        for (Map<String, Object> batch : batches) {
            if (remaining <= 0) {
                break;
            }
            int available = Sql.intValue(batch, "quantityAvailable", 0);
            int take = Math.min(available, remaining);
            if (take <= 0) {
                continue;
            }
            value = value.add(decrementLocked(itemId, warehouseId, batch, take, movementType,
                    referenceType, referencePublicId, reason, actorId, lines));
            remaining -= take;
        }
        if (remaining > 0) {
            throw AppException.unprocessable("INSUFFICIENT_STOCK",
                    "الكمية المتاحة غير كافية.", "Insufficient stock.");
        }
        return money(value);
    }

    private BigDecimal decrementLocked(long itemId, long warehouseId, Map<String, Object> batch,
                                       int take, String movementType, String referenceType,
                                       String referencePublicId, String reason, long actorId,
                                       List<Map<String, Object>> lines) {
        int available = Sql.intValue(batch, "quantityAvailable", 0);
        int left = available - take;
        if (repository.updateBatchQuantity(Sql.longValue(batch, "id", 0),
                Sql.longValue(batch, "version", 0), left,
                left == 0 ? "DEPLETED" : "ACTIVE", actorId) == 0) {
            throw staleVersion();
        }
        BigDecimal unitCost = Sql.decimal(batch, "unitCost");
        if (unitCost == null) {
            unitCost = BigDecimal.ZERO;
        }
        repository.insertMovement(Ulid.next(), itemId, Sql.longValue(batch, "id", 0),
                warehouseId, movementType, -take, unitCost, referenceType, referencePublicId,
                reason, actorId);
        lines.add(ClinicalSupport.map("batchPublicId", batch.get("publicId"),
                "batchNumber", batch.get("batchNumber"), "expiryDate", batch.get("expiryDate"),
                "quantity", take, "unitCost", unitCost));
        return unitCost.multiply(BigDecimal.valueOf(take));
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private void checkExpiry(Map<String, Object> item, LocalDate expiryDate) {
        if (Sql.boolValue(item, "trackExpiry") && expiryDate == null) {
            throw AppException.badRequest("EXPIRY_REQUIRED",
                    "تاريخ انتهاء الصلاحية مطلوب لهذا الصنف.",
                    "An expiry date is required for this item.");
        }
        if (expiryDate != null && expiryDate.isBefore(LocalDate.now())
                && boolSetting("inventory.expiry.block", true)) {
            throw AppException.unprocessable("BATCH_EXPIRED",
                    "لا يمكن استلام دفعة منتهية الصلاحية.",
                    "An expired batch cannot be received.");
        }
    }

    private List<StockLine> parseLines(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "يجب تحديد بند واحد على الأقل.", "At least one line is required.");
        }
        List<StockLine> lines = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "صيغة البنود غير صحيحة.", "Invalid line format.");
            }
            Map<String, Object> line = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                line.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            String itemPublicId = ClinicalSupport.required(line, "itemPublicId",
                    "الصنف مطلوب في كل بند.", "Each line needs an item.");
            String warehousePublicId = ClinicalSupport.optional(line, "warehousePublicId");
            Integer quantity = ClinicalSupport.optionalInt(line, "quantity");
            if (quantity == null || quantity <= 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "الكمية في البنود يجب أن تكون أكبر من صفر.",
                        "Each line quantity must be greater than zero.");
            }
            lines.add(new StockLine(itemPublicId, warehousePublicId, quantity,
                    decimal(line, "unitCost"), ClinicalSupport.optionalTrim(line, "notes", 500),
                    line));
        }
        return lines;
    }

    private long itemIdOf(String itemPublicId) {
        return Sql.longValue(requireItem(itemPublicId), "id", 0);
    }

    private Map<String, Object> requireWarehouse(String publicId) {
        Map<String, Object> row = repository.warehouseByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("WAREHOUSE_NOT_FOUND",
                    "المستودع غير موجود.", "Warehouse not found.");
        }
        return row;
    }

    private Map<String, Object> requireItem(String publicId) {
        Map<String, Object> row = repository.itemByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("ITEM_NOT_FOUND",
                    "الصنف غير موجود.", "Item not found.");
        }
        return row;
    }

    private Map<String, Object> requireSupplier(String publicId) {
        Map<String, Object> row = repository.supplierByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("SUPPLIER_NOT_FOUND",
                    "المورد غير موجود.", "Supplier not found.");
        }
        return row;
    }

    private Map<String, Object> requireCount(String publicId) {
        Map<String, Object> row = repository.countByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("COUNT_NOT_FOUND",
                    "الجرد غير موجود.", "Stock count not found.");
        }
        return row;
    }

    private Map<String, Object> requirePurchaseRequest(String publicId) {
        Map<String, Object> row = repository.purchaseRequestByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PURCHASE_REQUEST_NOT_FOUND",
                    "طلب الشراء غير موجود.", "Purchase request not found.");
        }
        return row;
    }

    private Map<String, Object> requirePurchaseOrder(String publicId) {
        Map<String, Object> row = repository.purchaseOrderByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PURCHASE_ORDER_NOT_FOUND",
                    "أمر الشراء غير موجود.", "Purchase order not found.");
        }
        return row;
    }

    private long requireVersion(Map<String, Object> row, Map<String, Object> body) {
        Long requested = ClinicalSupport.optionalLong(body, "version");
        long current = Sql.longValue(row, "version", 0);
        if (requested != null && requested != current) {
            throw staleVersion();
        }
        return current;
    }

    private int requirePositiveInt(Map<String, Object> body, String key, String messageAr,
                                   String messageEn) {
        Integer value = ClinicalSupport.optionalInt(body, key);
        if (value == null || value <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED", messageAr, messageEn);
        }
        return value;
    }

    private int requireNonNegativeInt(Map<String, Object> body, String key, String messageAr,
                                      String messageEn) {
        Integer value = ClinicalSupport.optionalInt(body, key);
        if (value == null || value < 0) {
            throw AppException.badRequest("VALIDATION_FAILED", messageAr, messageEn);
        }
        return value;
    }

    private AppException staleVersion() {
        return AppException.conflict("STALE_VERSION",
                "تم تعديل السجل من مستخدم آخر. أعد التحميل.",
                "The record changed by another user. Reload and retry.");
    }

    private String number(String sequence, String prefix) {
        String period = LocalDate.now().format(DAY);
        int value = repository.nextNumber(sequence, period);
        return prefix + "-" + period + "-" + String.format(Locale.ROOT, "%04d", value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
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

    private String setting(String key, String fallback) {
        return settings.findValue(key).orElse(fallback);
    }

    private int intSetting(String key, int fallback) {
        return settings.findValue(key).map(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private boolean boolSetting(String key, boolean fallback) {
        return settings.findValue(key)
                .map(value -> "1".equals(value) || "true".equalsIgnoreCase(value))
                .orElse(fallback);
    }

    private static String upper(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase();
    }

    /** One requested stock line before resolution to internal ids. */
    private record StockLine(String itemPublicId, String warehousePublicId, int quantity,
                             BigDecimal unitCost, String notes, Map<String, Object> raw) {
    }
}
