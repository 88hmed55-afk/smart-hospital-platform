package com.smarthospital.clinical.inventory;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit SQL gateway for general inventory (FR-INV-001..010).
 *
 * <p>Stock rows are locked with {@code FOR UPDATE} on issue, transfer, write-off
 * and count posting (ADR-008). Movements are append-only and every batch change
 * writes exactly one ledger row in the same JTA transaction.
 */
@Stateless
public class InventoryRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Warehouses
    // ------------------------------------------------------------------

    public List<Map<String, Object>> warehouses(String query, boolean activeOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT w.id, w.public_id, w.code, w.name_ar, w.name_en, w.warehouse_type, "
                        + "w.location, w.active, w.version, "
                        + "(SELECT COUNT(*) FROM inventory_batches b "
                        + " WHERE b.warehouse_id = w.id AND b.quantity_available > 0) AS batch_count, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM inventory_batches b "
                        + " WHERE b.warehouse_id = w.id) AS total_quantity "
                        + "FROM warehouses w WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append("AND (w.code LIKE ? OR w.name_ar LIKE ? OR w.name_en LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (activeOnly) {
            sql.append("AND w.active = 1 ");
        }
        sql.append("ORDER BY w.code");
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public Map<String, Object> warehouseByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, code, name_ar, name_en, warehouse_type, location, "
                        + "active, version FROM warehouses WHERE public_id = ?", publicId);
    }

    public Map<String, Object> warehouseByCode(String code) {
        return Sql.one(em, "SELECT id, public_id FROM warehouses WHERE code = ?",
                code.toUpperCase());
    }

    public long insertWarehouse(String publicId, String code, String nameAr, String nameEn,
                                String type, String location, long actorId) {
        return Sql.insert(em,
                "INSERT INTO warehouses (public_id, code, name_ar, name_en, warehouse_type, "
                        + "location, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, code, nameAr, nameEn, type, location, actorId);
    }

    public int updateWarehouse(long id, long version, String nameAr, String nameEn,
                               String type, String location, boolean active, long actorId) {
        return Sql.update(em,
                "UPDATE warehouses SET name_ar = ?, name_en = ?, warehouse_type = ?, "
                        + "location = ?, active = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                nameAr, nameEn, type, location, active ? 1 : 0, actorId, id, version);
    }

    public long stockInWarehouse(long warehouseId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COALESCE(SUM(quantity_available), 0) AS total FROM inventory_batches "
                        + "WHERE warehouse_id = ?", warehouseId);
        return Sql.longValue(row, "total", 0);
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    public List<Map<String, Object>> items(String query, String category, boolean activeOnly,
                                           int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = itemWhere(query, category, activeOnly, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT i.id, i.public_id, i.code, i.sku, i.name_ar, i.name_en, i.category, "
                        + "i.base_unit, i.track_expiry, i.unit_cost, i.barcode, i.active, "
                        + "i.version, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM inventory_batches b "
                        + " WHERE b.item_id = i.id) AS stock_on_hand, "
                        + "(SELECT COALESCE(SUM(r.reorder_level), 0) FROM inventory_reorder_points r "
                        + " WHERE r.item_id = i.id AND r.active = 1) AS reorder_level "
                        + "FROM inventory_items i " + where
                        + "ORDER BY i.code LIMIT ? OFFSET ?", params.toArray());
    }

    public long countItems(String query, String category, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = itemWhere(query, category, activeOnly, params);
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM inventory_items i " + where, params.toArray()),
                "total", 0);
    }

    private String itemWhere(String query, String category, boolean activeOnly,
                             List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        if (query != null && !query.isBlank()) {
            where.append("AND (i.code LIKE ? OR i.sku LIKE ? OR i.name_ar LIKE ? "
                    + "OR i.name_en LIKE ? OR i.barcode LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (category != null && !category.isBlank()) {
            where.append("AND i.category = ? ");
            params.add(category);
        }
        if (activeOnly) {
            where.append("AND i.active = 1 ");
        }
        return where.toString();
    }

    public Map<String, Object> itemByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, code, sku, name_ar, name_en, category, base_unit, "
                        + "track_expiry, unit_cost, barcode, active, version "
                        + "FROM inventory_items WHERE public_id = ?", publicId);
    }

    public Map<String, Object> itemBySku(String sku) {
        return Sql.one(em, "SELECT id, public_id FROM inventory_items WHERE sku = ?", sku);
    }

    public Map<String, Object> itemByCode(String code) {
        return Sql.one(em, "SELECT id, public_id FROM inventory_items WHERE code = ?",
                code.toUpperCase());
    }

    public Map<String, Object> itemByBarcode(String barcode) {
        return Sql.one(em, "SELECT id, public_id FROM inventory_items WHERE barcode = ?", barcode);
    }

    public long insertItem(String publicId, String code, String sku, String nameAr,
                           String nameEn, String category, String baseUnit, boolean trackExpiry,
                           BigDecimal unitCost, String barcode, long actorId) {
        return Sql.insert(em,
                "INSERT INTO inventory_items (public_id, code, sku, name_ar, name_en, category, "
                        + "base_unit, track_expiry, unit_cost, barcode, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, code, sku, nameAr, nameEn, category, baseUnit, trackExpiry ? 1 : 0,
                unitCost, barcode, actorId);
    }

    public int updateItem(long id, long version, String nameAr, String nameEn, String category,
                          String baseUnit, boolean trackExpiry, BigDecimal unitCost,
                          String barcode, boolean active, long actorId) {
        return Sql.update(em,
                "UPDATE inventory_items SET name_ar = ?, name_en = ?, category = ?, "
                        + "base_unit = ?, track_expiry = ?, unit_cost = ?, barcode = ?, active = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                nameAr, nameEn, category, baseUnit, trackExpiry ? 1 : 0, unitCost, barcode,
                active ? 1 : 0, actorId, id, version);
    }

    public void insertReorderPoint(String publicId, long itemId, long warehouseId,
                                   int reorderLevel, int reorderQuantity) {
        Sql.insert(em,
                "INSERT INTO inventory_reorder_points (public_id, item_id, warehouse_id, "
                        + "reorder_level, reorder_quantity) VALUES (?, ?, ?, ?, ?)",
                publicId, itemId, warehouseId, reorderLevel, reorderQuantity);
    }

    public List<Map<String, Object>> reorderPoints(long itemId) {
        return Sql.query(em,
                "SELECT r.public_id, r.reorder_level, r.reorder_quantity, r.active, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code, "
                        + "w.name_ar AS warehouse_ar, w.name_en AS warehouse_en "
                        + "FROM inventory_reorder_points r JOIN warehouses w ON w.id = r.warehouse_id "
                        + "WHERE r.item_id = ? ORDER BY w.code", itemId);
    }

    // ------------------------------------------------------------------
    // Suppliers
    // ------------------------------------------------------------------

    public List<Map<String, Object>> suppliers(String query, boolean activeOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, public_id, code, name_ar, name_en, phone, email, address, "
                        + "contact_person, active, version FROM suppliers WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append("AND (code LIKE ? OR name_ar LIKE ? OR name_en LIKE ? OR phone LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (activeOnly) {
            sql.append("AND active = 1 ");
        }
        sql.append("ORDER BY code");
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public Map<String, Object> supplierByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, code, name_ar, name_en, phone, email, address, "
                        + "contact_person, active, version FROM suppliers WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> supplierByCode(String code) {
        return Sql.one(em, "SELECT id, public_id FROM suppliers WHERE code = ?", code);
    }

    public long insertSupplier(String publicId, String code, String nameAr, String nameEn,
                               String phone, String email, String address, String contact,
                               long actorId) {
        return Sql.insert(em,
                "INSERT INTO suppliers (public_id, code, name_ar, name_en, phone, email, "
                        + "address, contact_person, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, code, nameAr, nameEn, phone, email, address, contact, actorId);
    }

    public int updateSupplier(long id, long version, String nameAr, String nameEn, String phone,
                              String email, String address, String contact, boolean active,
                              long actorId) {
        return Sql.update(em,
                "UPDATE suppliers SET name_ar = ?, name_en = ?, phone = ?, email = ?, "
                        + "address = ?, contact_person = ?, active = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                nameAr, nameEn, phone, email, address, contact, active ? 1 : 0, actorId, id,
                version);
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    public List<Map<String, Object>> batches(String query, String itemPublicId,
                                             String warehousePublicId, String status,
                                             LocalDate expiryBefore, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = batchWhere(query, itemPublicId, warehousePublicId, status, expiryBefore,
                params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT b.id, b.public_id, b.batch_number, b.lot_number, b.expiry_date, "
                        + "b.quantity_received, b.quantity_available, b.unit_cost, b.status, "
                        + "b.received_at, b.version, i.public_id AS item_public_id, i.code, "
                        + "i.name_ar, i.name_en, i.base_unit, i.track_expiry, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code, "
                        + "w.name_ar AS warehouse_ar, w.name_en AS warehouse_en, "
                        + "s.name_ar AS supplier_ar, s.name_en AS supplier_en "
                        + "FROM inventory_batches b "
                        + "JOIN inventory_items i ON i.id = b.item_id "
                        + "JOIN warehouses w ON w.id = b.warehouse_id "
                        + "LEFT JOIN suppliers s ON s.id = b.supplier_id "
                        + where + "ORDER BY (b.expiry_date IS NULL), b.expiry_date, b.id "
                        + "LIMIT ? OFFSET ?", params.toArray());
    }

    public long countBatches(String query, String itemPublicId, String warehousePublicId,
                             String status, LocalDate expiryBefore) {
        List<Object> params = new ArrayList<>();
        String where = batchWhere(query, itemPublicId, warehousePublicId, status, expiryBefore,
                params);
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM inventory_batches b "
                        + "JOIN inventory_items i ON i.id = b.item_id " + where,
                params.toArray()), "total", 0);
    }

    private String batchWhere(String query, String itemPublicId, String warehousePublicId,
                              String status, LocalDate expiryBefore, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        if (query != null && !query.isBlank()) {
            where.append("AND (b.batch_number LIKE ? OR i.code LIKE ? OR i.name_ar LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (itemPublicId != null && !itemPublicId.isBlank()) {
            where.append("AND i.public_id = ? ");
            params.add(itemPublicId);
        }
        if (warehousePublicId != null && !warehousePublicId.isBlank()) {
            where.append("AND b.warehouse_id = (SELECT id FROM warehouses WHERE public_id = ?) ");
            params.add(warehousePublicId);
        }
        if (status != null && !status.isBlank()) {
            where.append("AND b.status = ? ");
            params.add(status);
        }
        if (expiryBefore != null) {
            where.append("AND b.expiry_date IS NOT NULL AND b.expiry_date <= ? ");
            params.add(expiryBefore);
        }
        return where.toString();
    }

    public Map<String, Object> batchByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT b.id, b.public_id, b.item_id, b.warehouse_id, b.batch_number, "
                        + "b.lot_number, b.expiry_date, b.quantity_received, b.quantity_available, "
                        + "b.unit_cost, b.status, b.version, i.public_id AS item_public_id, "
                        + "i.code, i.name_ar, i.name_en, i.track_expiry, i.base_unit, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code "
                        + "FROM inventory_batches b "
                        + "JOIN inventory_items i ON i.id = b.item_id "
                        + "JOIN warehouses w ON w.id = b.warehouse_id "
                        + "WHERE b.public_id = ?", publicId);
    }

    public Map<String, Object> batchByNumber(long itemId, long warehouseId, String batchNumber) {
        return Sql.one(em,
                "SELECT id, public_id, quantity_available, version FROM inventory_batches "
                        + "WHERE item_id = ? AND warehouse_id = ? AND batch_number = ?",
                itemId, warehouseId, batchNumber);
    }

    /**
     * Current (locking) read of the batch slot. Two concurrent receipts or
     * transfers of the same batch number must serialise here: a plain read runs
     * on the transaction snapshot and can miss a batch that another transaction
     * committed after our snapshot, which then collides on the unique key.
     */
    public Map<String, Object> lockBatchByNumber(long itemId, long warehouseId,
                                                 String batchNumber) {
        return Sql.one(em,
                "SELECT id, public_id, quantity_available, version FROM inventory_batches "
                        + "WHERE item_id = ? AND warehouse_id = ? AND batch_number = ? FOR UPDATE",
                itemId, warehouseId, batchNumber);
    }

    public Map<String, Object> lockBatch(long id) {
        return Sql.one(em,
                "SELECT id, public_id, item_id, warehouse_id, batch_number, expiry_date, "
                        + "quantity_received, quantity_available, unit_cost, status, version "
                        + "FROM inventory_batches WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> fefoBatches(long itemId, long warehouseId) {
        return Sql.query(em,
                "SELECT id, public_id, batch_number, lot_number, supplier_id, expiry_date, "
                        + "quantity_available, unit_cost, status, version FROM inventory_batches "
                        + "WHERE item_id = ? AND warehouse_id = ? AND status = 'ACTIVE' "
                        + "AND quantity_available > 0 "
                        + "ORDER BY (expiry_date IS NULL), expiry_date, id FOR UPDATE",
                itemId, warehouseId);
    }

    public long insertBatch(String publicId, long itemId, long warehouseId, Long supplierId,
                            String batchNumber, String lotNumber, LocalDate expiryDate,
                            int quantity, BigDecimal unitCost, long actorId) {
        return Sql.insert(em,
                "INSERT INTO inventory_batches (public_id, item_id, warehouse_id, supplier_id, "
                        + "batch_number, lot_number, expiry_date, quantity_received, "
                        + "quantity_available, unit_cost, received_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, itemId, warehouseId, supplierId, batchNumber, lotNumber, expiryDate,
                quantity, quantity, unitCost, actorId);
    }

    public int updateBatchQuantity(long id, long version, int available, String status,
                                   long actorId) {
        return Sql.update(em,
                "UPDATE inventory_batches SET quantity_available = ?, status = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                available, status, actorId, id, version);
    }

    public long stockForItem(long itemId, long warehouseId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COALESCE(SUM(quantity_available), 0) AS total FROM inventory_batches "
                        + "WHERE item_id = ? AND warehouse_id = ? AND status = 'ACTIVE'",
                itemId, warehouseId);
        return Sql.longValue(row, "total", 0);
    }

    /** Adds stock to an existing batch (transfer-in / additional receipt). */
    public int incrementBatch(long id, long version, int delta, long actorId) {
        return Sql.update(em,
                "UPDATE inventory_batches SET quantity_received = quantity_received + ?, "
                        + "quantity_available = quantity_available + ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                delta, delta, actorId, id, version);
    }

    public List<Map<String, Object>> batchesForItem(long itemId, long warehouseId) {
        return Sql.query(em,
                "SELECT id, public_id, batch_number, lot_number, expiry_date, "
                        + "quantity_available, unit_cost, status, version "
                        + "FROM inventory_batches WHERE item_id = ? AND warehouse_id = ? "
                        + "ORDER BY (expiry_date IS NULL), expiry_date, id",
                itemId, warehouseId);
    }

    // ------------------------------------------------------------------
    // Movements
    // ------------------------------------------------------------------

    public void insertMovement(String publicId, long itemId, long batchId, long warehouseId,
                               String type, int quantityDelta, BigDecimal unitCost,
                               String referenceType, String referencePublicId, String reason,
                               long actorId) {
        Sql.insert(em,
                "INSERT INTO inventory_movements (public_id, item_id, batch_id, warehouse_id, "
                        + "movement_type, quantity_delta, unit_cost, reference_type, "
                        + "reference_public_id, reason, performed_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, itemId, batchId, warehouseId, type, quantityDelta, unitCost,
                referenceType, referencePublicId, reason, actorId);
    }

    public List<Map<String, Object>> movements(String itemPublicId, String warehousePublicId,
                                               String type, LocalDate from, LocalDate to,
                                               int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = movementWhere(itemPublicId, warehousePublicId, type, from, to, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT m.id, m.public_id, m.movement_type, m.quantity_delta, m.unit_cost, "
                        + "m.reference_type, m.reference_public_id, m.reason, m.performed_at, "
                        + "i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "b.public_id AS batch_public_id, b.batch_number, b.expiry_date, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code, "
                        + "u.display_name_ar AS performed_by_ar, "
                        + "u.display_name_en AS performed_by_en "
                        + "FROM inventory_movements m "
                        + "JOIN inventory_items i ON i.id = m.item_id "
                        + "JOIN inventory_batches b ON b.id = m.batch_id "
                        + "JOIN warehouses w ON w.id = m.warehouse_id "
                        + "JOIN users u ON u.id = m.performed_by "
                        + where + "ORDER BY m.performed_at DESC, m.id DESC LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long countMovements(String itemPublicId, String warehousePublicId, String type,
                               LocalDate from, LocalDate to) {
        List<Object> params = new ArrayList<>();
        String where = movementWhere(itemPublicId, warehousePublicId, type, from, to, params);
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM inventory_movements m "
                        + "JOIN inventory_items i ON i.id = m.item_id " + where,
                params.toArray()), "total", 0);
    }

    private String movementWhere(String itemPublicId, String warehousePublicId, String type,
                                 LocalDate from, LocalDate to, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        if (itemPublicId != null && !itemPublicId.isBlank()) {
            where.append("AND i.public_id = ? ");
            params.add(itemPublicId);
        }
        if (warehousePublicId != null && !warehousePublicId.isBlank()) {
            where.append("AND m.warehouse_id = (SELECT id FROM warehouses WHERE public_id = ?) ");
            params.add(warehousePublicId);
        }
        if (type != null && !type.isBlank()) {
            where.append("AND m.movement_type = ? ");
            params.add(type);
        }
        if (from != null) {
            where.append("AND m.performed_at >= ? ");
            params.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append("AND m.performed_at < ? ");
            params.add(to.plusDays(1).atStartOfDay());
        }
        return where.toString();
    }

    // ------------------------------------------------------------------
    // Transfers
    // ------------------------------------------------------------------

    public long insertTransfer(String publicId, String number, long fromWarehouseId,
                               long toWarehouseId, String documentRef, String reason,
                               long actorId) {
        return Sql.insert(em,
                "INSERT INTO stock_transfers (public_id, transfer_number, from_warehouse_id, "
                        + "to_warehouse_id, document_ref, reason, transferred_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, number, fromWarehouseId, toWarehouseId, documentRef, reason, actorId);
    }

    public void insertTransferItem(String publicId, long transferId, long itemId,
                                   long sourceBatchId, Long targetBatchId, int quantity,
                                   BigDecimal unitCost) {
        Sql.insert(em,
                "INSERT INTO stock_transfer_items (public_id, transfer_id, item_id, "
                        + "source_batch_id, target_batch_id, quantity, unit_cost) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, transferId, itemId, sourceBatchId, targetBatchId, quantity, unitCost);
    }

    public Map<String, Object> transferByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT t.id, t.public_id, t.transfer_number, t.document_ref, t.reason, "
                        + "t.status, t.transferred_at, t.version, "
                        + "fw.public_id AS from_public_id, fw.code AS from_code, "
                        + "fw.name_ar AS from_ar, fw.name_en AS from_en, "
                        + "tw.public_id AS to_public_id, tw.code AS to_code, "
                        + "tw.name_ar AS to_ar, tw.name_en AS to_en, "
                        + "u.display_name_ar AS by_ar, u.display_name_en AS by_en "
                        + "FROM stock_transfers t "
                        + "JOIN warehouses fw ON fw.id = t.from_warehouse_id "
                        + "JOIN warehouses tw ON tw.id = t.to_warehouse_id "
                        + "JOIN users u ON u.id = t.transferred_by "
                        + "WHERE t.public_id = ?", publicId);
    }

    public List<Map<String, Object>> transferItems(long transferId) {
        return Sql.query(em,
                "SELECT ti.public_id, ti.quantity, ti.unit_cost, "
                        + "i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "sb.batch_number AS source_batch_number, "
                        + "sb.expiry_date AS source_expiry_date, "
                        + "tb.batch_number AS target_batch_number "
                        + "FROM stock_transfer_items ti "
                        + "JOIN inventory_items i ON i.id = ti.item_id "
                        + "JOIN inventory_batches sb ON sb.id = ti.source_batch_id "
                        + "LEFT JOIN inventory_batches tb ON tb.id = ti.target_batch_id "
                        + "WHERE ti.transfer_id = ? ORDER BY ti.id", transferId);
    }

    public List<Map<String, Object>> transfers(int offset, int limit) {
        return Sql.query(em,
                "SELECT t.id, t.public_id, t.transfer_number, t.document_ref, t.status, "
                        + "t.transferred_at, fw.code AS from_code, tw.code AS to_code, "
                        + "u.display_name_ar AS by_ar, u.display_name_en AS by_en "
                        + "FROM stock_transfers t "
                        + "JOIN warehouses fw ON fw.id = t.from_warehouse_id "
                        + "JOIN warehouses tw ON tw.id = t.to_warehouse_id "
                        + "JOIN users u ON u.id = t.transferred_by "
                        + "ORDER BY t.transferred_at DESC, t.id DESC LIMIT ? OFFSET ?",
                limit, offset);
    }

    public long countTransfers() {
        return Sql.longValue(Sql.one(em, "SELECT COUNT(*) AS total FROM stock_transfers"),
                "total", 0);
    }

    // ------------------------------------------------------------------
    // Stock counts
    // ------------------------------------------------------------------

    public long insertCount(String publicId, String number, long warehouseId, String reason,
                            long actorId) {
        return Sql.insert(em,
                "INSERT INTO stock_counts (public_id, count_number, warehouse_id, reason, "
                        + "counted_by) VALUES (?, ?, ?, ?, ?)",
                publicId, number, warehouseId, reason, actorId);
    }

    public void insertCountLine(String publicId, long countId, long itemId, long batchId,
                                int systemQuantity) {
        Sql.insert(em,
                "INSERT INTO stock_count_lines (public_id, count_id, item_id, batch_id, "
                        + "system_quantity) VALUES (?, ?, ?, ?, ?)",
                publicId, countId, itemId, batchId, systemQuantity);
    }

    public Map<String, Object> countByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT c.id, c.public_id, c.count_number, c.status, c.reason, c.counted_at, "
                        + "c.posted_at, c.version, w.public_id AS warehouse_public_id, "
                        + "w.code AS warehouse_code, w.name_ar AS warehouse_ar, "
                        + "w.name_en AS warehouse_en, "
                        + "u.display_name_ar AS counted_by_ar, u.display_name_en AS counted_by_en "
                        + "FROM stock_counts c JOIN warehouses w ON w.id = c.warehouse_id "
                        + "JOIN users u ON u.id = c.counted_by WHERE c.public_id = ?", publicId);
    }

    public Map<String, Object> lockCount(long id) {
        return Sql.one(em,
                "SELECT id, public_id, count_number, warehouse_id, status, reason, version "
                        + "FROM stock_counts WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> countLines(long countId) {
        return Sql.query(em,
                "SELECT l.id, l.public_id, l.item_id, l.batch_id, l.system_quantity, "
                        + "l.counted_quantity, l.variance, "
                        + "b.public_id AS batch_public_id, b.batch_number, b.expiry_date, "
                        + "i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, "
                        + "i.base_unit FROM stock_count_lines l "
                        + "JOIN inventory_batches b ON b.id = l.batch_id "
                        + "JOIN inventory_items i ON i.id = l.item_id "
                        + "WHERE l.count_id = ? ORDER BY i.code, b.expiry_date, b.id", countId);
    }

    public Map<String, Object> countLine(long countId, String linePublicId) {
        return Sql.one(em,
                "SELECT l.id, l.public_id, l.batch_id, l.item_id, l.system_quantity, "
                        + "l.counted_quantity FROM stock_count_lines l "
                        + "WHERE l.count_id = ? AND l.public_id = ?", countId, linePublicId);
    }

    public int updateCountLine(long lineId, int countedQuantity) {
        return Sql.update(em,
                "UPDATE stock_count_lines SET counted_quantity = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                countedQuantity, lineId);
    }

    public int updateCountStatus(long countId, long version, String status, String reason,
                                 Long actorId) {
        return Sql.update(em,
                "UPDATE stock_counts SET status = ?, reason = COALESCE(?, reason), "
                        + "posted_by = ?, posted_at = CURRENT_TIMESTAMP(6), "
                        + "updated_at = CURRENT_TIMESTAMP(6), version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                status, reason, actorId, countId, version);
    }

    public List<Map<String, Object>> counts(String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.public_id, c.count_number, c.status, c.reason, c.counted_at, "
                        + "c.posted_at, c.version, w.code AS warehouse_code, "
                        + "w.name_ar AS warehouse_ar, w.name_en AS warehouse_en, "
                        + "u.display_name_ar AS counted_by_ar, u.display_name_en AS counted_by_en "
                        + "FROM stock_counts c JOIN warehouses w ON w.id = c.warehouse_id "
                        + "JOIN users u ON u.id = c.counted_by WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND c.status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY c.counted_at DESC, c.id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public long countCounts(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total FROM stock_counts c WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND c.status = ? ");
            params.add(status);
        }
        return Sql.longValue(Sql.one(em, sql.toString(), params.toArray()), "total", 0);
    }

    public List<Map<String, Object>> batchesForCount(long warehouseId) {
        return Sql.query(em,
                "SELECT id, public_id, item_id, batch_number, expiry_date, quantity_available "
                        + "FROM inventory_batches WHERE warehouse_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY item_id, expiry_date, id", warehouseId);
    }

    // ------------------------------------------------------------------
    // Purchase requests
    // ------------------------------------------------------------------

    public long insertPurchaseRequest(String publicId, String number, long warehouseId,
                                      String status, String notes, long actorId) {
        return Sql.insert(em,
                "INSERT INTO purchase_requests (public_id, request_number, warehouse_id, "
                        + "status, notes, requested_by) VALUES (?, ?, ?, ?, ?, ?)",
                publicId, number, warehouseId, status, notes, actorId);
    }

    public void insertPurchaseRequestItem(String publicId, long requestId, long itemId,
                                          int quantity, String notes) {
        Sql.insert(em,
                "INSERT INTO purchase_request_items (public_id, request_id, item_id, quantity, "
                        + "notes) VALUES (?, ?, ?, ?, ?)",
                publicId, requestId, itemId, quantity, notes);
    }

    public Map<String, Object> purchaseRequestByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT r.id, r.public_id, r.request_number, r.status, r.notes, r.requested_at, "
                        + "r.approved_at, r.version, w.public_id AS warehouse_public_id, "
                        + "w.code AS warehouse_code, w.name_ar AS warehouse_ar, "
                        + "w.name_en AS warehouse_en, u.display_name_ar AS by_ar, "
                        + "u.display_name_en AS by_en "
                        + "FROM purchase_requests r JOIN warehouses w ON w.id = r.warehouse_id "
                        + "JOIN users u ON u.id = r.requested_by WHERE r.public_id = ?", publicId);
    }

    public Map<String, Object> lockPurchaseRequest(long id) {
        return Sql.one(em,
                "SELECT id, public_id, request_number, warehouse_id, status, notes, version "
                        + "FROM purchase_requests WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> purchaseRequestItems(long requestId) {
        return Sql.query(em,
                "SELECT ri.public_id, ri.quantity, ri.notes, i.public_id AS item_public_id, "
                        + "i.code, i.name_ar, i.name_en, i.base_unit "
                        + "FROM purchase_request_items ri "
                        + "JOIN inventory_items i ON i.id = ri.item_id "
                        + "WHERE ri.request_id = ? ORDER BY i.code", requestId);
    }

    public int updatePurchaseRequestStatus(long id, long version, String status, Long actorId,
                                           boolean approve) {
        return Sql.update(em,
                "UPDATE purchase_requests SET status = ?, "
                        + "approved_by = CASE WHEN ? THEN ? ELSE approved_by END, "
                        + "approved_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE approved_at END, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                status, approve, actorId, approve, actorId, id, version);
    }

    public List<Map<String, Object>> purchaseRequests(String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.public_id, r.request_number, r.status, r.requested_at, "
                        + "r.approved_at, r.version, w.code AS warehouse_code, "
                        + "w.name_ar AS warehouse_ar, w.name_en AS warehouse_en, "
                        + "(SELECT COUNT(*) FROM purchase_request_items ri "
                        + " WHERE ri.request_id = r.id) AS line_count "
                        + "FROM purchase_requests r JOIN warehouses w ON w.id = r.warehouse_id "
                        + "WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND r.status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY r.requested_at DESC, r.id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public long countPurchaseRequests(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total FROM purchase_requests r WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND r.status = ? ");
            params.add(status);
        }
        return Sql.longValue(Sql.one(em, sql.toString(), params.toArray()), "total", 0);
    }

    // ------------------------------------------------------------------
    // Purchase orders and goods receipts
    // ------------------------------------------------------------------

    public long insertPurchaseOrder(String publicId, String number, long supplierId,
                                    long warehouseId, Long requestId, String status,
                                    LocalDate expectedAt, BigDecimal total, String notes,
                                    long actorId) {
        return Sql.insert(em,
                "INSERT INTO purchase_orders (public_id, po_number, supplier_id, warehouse_id, "
                        + "purchase_request_id, status, expected_at, total_amount, notes, "
                        + "ordered_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, number, supplierId, warehouseId, requestId, status, expectedAt, total,
                notes, actorId);
    }

    public void insertPurchaseOrderItem(String publicId, long poId, long itemId, int quantity,
                                        BigDecimal unitCost) {
        Sql.insert(em,
                "INSERT INTO purchase_order_items (public_id, purchase_order_id, item_id, "
                        + "quantity_ordered, unit_cost) VALUES (?, ?, ?, ?, ?)",
                publicId, poId, itemId, quantity, unitCost);
    }

    public Map<String, Object> purchaseOrderByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT o.id, o.public_id, o.po_number, o.status, o.ordered_at, o.expected_at, "
                        + "o.total_amount, o.notes, o.version, s.public_id AS supplier_public_id, "
                        + "s.code AS supplier_code, s.name_ar AS supplier_ar, "
                        + "s.name_en AS supplier_en, w.public_id AS warehouse_public_id, "
                        + "w.code AS warehouse_code, w.name_ar AS warehouse_ar, "
                        + "w.name_en AS warehouse_en, pr.public_id AS request_public_id, "
                        + "pr.request_number AS request_number "
                        + "FROM purchase_orders o JOIN suppliers s ON s.id = o.supplier_id "
                        + "JOIN warehouses w ON w.id = o.warehouse_id "
                        + "LEFT JOIN purchase_requests pr ON pr.id = o.purchase_request_id "
                        + "WHERE o.public_id = ?", publicId);
    }

    public Map<String, Object> lockPurchaseOrder(long id) {
        return Sql.one(em,
                "SELECT id, public_id, po_number, supplier_id, warehouse_id, status, version "
                        + "FROM purchase_orders WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> purchaseOrderItems(long poId) {
        return Sql.query(em,
                "SELECT oi.id, oi.public_id, oi.quantity_ordered, oi.quantity_received, "
                        + "oi.unit_cost, i.public_id AS item_public_id, i.code, i.name_ar, "
                        + "i.name_en, i.base_unit, i.track_expiry "
                        + "FROM purchase_order_items oi "
                        + "JOIN inventory_items i ON i.id = oi.item_id "
                        + "WHERE oi.purchase_order_id = ? ORDER BY i.code", poId);
    }

    public Map<String, Object> purchaseOrderItem(long poId, String itemPublicId) {
        return Sql.one(em,
                "SELECT oi.id, oi.item_id, oi.quantity_ordered, oi.quantity_received, oi.unit_cost "
                        + "FROM purchase_order_items oi JOIN inventory_items i ON i.id = oi.item_id "
                        + "WHERE oi.purchase_order_id = ? AND i.public_id = ?", poId, itemPublicId);
    }

    public int addReceivedQuantity(long poItemId, int quantity) {
        return Sql.update(em,
                "UPDATE purchase_order_items SET quantity_received = quantity_received + ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?", quantity, poItemId);
    }

    public int updatePurchaseOrderStatus(long id, long version, String status, long actorId) {
        return Sql.update(em,
                "UPDATE purchase_orders SET status = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                status, actorId, id, version);
    }

    public List<Map<String, Object>> purchaseOrders(String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.id, o.public_id, o.po_number, o.status, o.ordered_at, o.expected_at, "
                        + "o.total_amount, o.version, s.code AS supplier_code, "
                        + "s.name_ar AS supplier_ar, s.name_en AS supplier_en, "
                        + "w.code AS warehouse_code, w.name_ar AS warehouse_ar, "
                        + "w.name_en AS warehouse_en "
                        + "FROM purchase_orders o JOIN suppliers s ON s.id = o.supplier_id "
                        + "JOIN warehouses w ON w.id = o.warehouse_id WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND o.status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY o.ordered_at DESC, o.id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public long countPurchaseOrders(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total FROM purchase_orders o WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND o.status = ? ");
            params.add(status);
        }
        return Sql.longValue(Sql.one(em, sql.toString(), params.toArray()), "total", 0);
    }

    public long insertGoodsReceipt(String publicId, String number, long poId, long warehouseId,
                                   String notes, long actorId) {
        return Sql.insert(em,
                "INSERT INTO goods_receipts (public_id, grn_number, purchase_order_id, "
                        + "warehouse_id, notes, received_by) VALUES (?, ?, ?, ?, ?, ?)",
                publicId, number, poId, warehouseId, notes, actorId);
    }

    public void insertGoodsReceiptItem(String publicId, long receiptId, long poItemId,
                                       long itemId, long batchId, String batchNumber,
                                       LocalDate expiryDate, int quantity, BigDecimal unitCost) {
        Sql.insert(em,
                "INSERT INTO goods_receipt_items (public_id, goods_receipt_id, "
                        + "purchase_order_item_id, item_id, batch_id, batch_number, expiry_date, "
                        + "quantity, unit_cost) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, receiptId, poItemId, itemId, batchId, batchNumber, expiryDate,
                quantity, unitCost);
    }

    public List<Map<String, Object>> goodsReceipts(long poId) {
        return Sql.query(em,
                "SELECT public_id, grn_number, status, received_at, notes FROM goods_receipts "
                        + "WHERE purchase_order_id = ? ORDER BY received_at, id", poId);
    }

    public Map<String, Object> userByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, username, display_name_ar, display_name_en, status "
                        + "FROM users WHERE public_id = ?", publicId);
    }

    public boolean userHasPermission(long userId, String permissionCode) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM user_roles ur "
                        + "JOIN role_permissions rp ON rp.role_id = ur.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE ur.user_id = ? AND p.code = ?", userId, permissionCode);
        return Sql.longValue(row, "total", 0) > 0;
    }

    public int nextNumber(String sequence, String period) {
        return Sql.nextSequence(em, sequence, period);
    }

    // ------------------------------------------------------------------
    // Alerts and reports
    // ------------------------------------------------------------------

    public List<Map<String, Object>> lowStock(int limit) {
        return Sql.query(em,
                "SELECT i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code, "
                        + "w.name_ar AS warehouse_ar, w.name_en AS warehouse_en, "
                        + "r.reorder_level, r.reorder_quantity, "
                        + "COALESCE((SELECT SUM(b.quantity_available) FROM inventory_batches b "
                        + " WHERE b.item_id = i.id AND b.warehouse_id = w.id "
                        + " AND b.status = 'ACTIVE'), 0) AS available "
                        + "FROM inventory_reorder_points r "
                        + "JOIN inventory_items i ON i.id = r.item_id "
                        + "JOIN warehouses w ON w.id = r.warehouse_id "
                        + "WHERE r.active = 1 AND i.active = 1 AND w.active = 1 "
                        + "HAVING available <= r.reorder_level "
                        + "ORDER BY (r.reorder_level - available) DESC LIMIT ?", limit);
    }

    public List<Map<String, Object>> expiringBatches(LocalDate before, int limit) {
        return Sql.query(em,
                "SELECT b.public_id, b.batch_number, b.expiry_date, b.quantity_available, "
                        + "i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "w.code AS warehouse_code, w.name_ar AS warehouse_ar, "
                        + "w.name_en AS warehouse_en, DATEDIFF(b.expiry_date, CURRENT_DATE) AS days_left "
                        + "FROM inventory_batches b JOIN inventory_items i ON i.id = b.item_id "
                        + "JOIN warehouses w ON w.id = b.warehouse_id "
                        + "WHERE b.status = 'ACTIVE' AND b.quantity_available > 0 "
                        + "AND b.expiry_date IS NOT NULL AND b.expiry_date <= ? "
                        + "ORDER BY b.expiry_date, b.id LIMIT ?", before, limit);
    }

    public long expiredBatchCount() {
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM inventory_batches WHERE status = 'ACTIVE' "
                        + "AND quantity_available > 0 AND expiry_date IS NOT NULL "
                        + "AND expiry_date < CURRENT_DATE"), "total", 0);
    }

    public List<Map<String, Object>> valuation(String warehousePublicId) {
        StringBuilder sql = new StringBuilder(
                "SELECT i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "w.public_id AS warehouse_public_id, w.code AS warehouse_code, "
                        + "SUM(b.quantity_available) AS quantity, "
                        + "ROUND(SUM(b.quantity_available * b.unit_cost), 2) AS value, "
                        + "ROUND(CASE WHEN SUM(b.quantity_available) = 0 THEN 0 "
                        + "ELSE SUM(b.quantity_available * b.unit_cost) / SUM(b.quantity_available) END, 2) "
                        + "AS average_cost "
                        + "FROM inventory_batches b "
                        + "JOIN inventory_items i ON i.id = b.item_id "
                        + "JOIN warehouses w ON w.id = b.warehouse_id "
                        + "WHERE b.quantity_available > 0 ");
        List<Object> params = new ArrayList<>();
        if (warehousePublicId != null && !warehousePublicId.isBlank()) {
            sql.append("AND w.public_id = ? ");
            params.add(warehousePublicId);
        }
        sql.append("GROUP BY i.public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                + "w.public_id, w.code ORDER BY i.code, w.code");
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> consumption(LocalDate from, LocalDate to,
                                                  String itemPublicId) {
        StringBuilder sql = new StringBuilder(
                "SELECT i.public_id AS item_public_id, i.code, i.name_ar, i.name_en, i.base_unit, "
                        + "COALESCE(SUM(CASE WHEN m.movement_type = 'ISSUE' "
                        + "THEN -m.quantity_delta END), 0) AS issued, "
                        + "COALESCE(SUM(CASE WHEN m.movement_type = 'WRITE_OFF' "
                        + "THEN -m.quantity_delta END), 0) AS written_off, "
                        + "COALESCE(SUM(CASE WHEN m.movement_type = 'TRANSFER_OUT' "
                        + "THEN -m.quantity_delta END), 0) AS transferred_out, "
                        + "COUNT(*) AS movement_count "
                        + "FROM inventory_movements m "
                        + "JOIN inventory_items i ON i.id = m.item_id "
                        + "WHERE m.performed_at >= ? AND m.performed_at < ? "
                        + "AND m.movement_type IN ('ISSUE', 'WRITE_OFF', 'TRANSFER_OUT') ");
        List<Object> params = new ArrayList<>();
        params.add(from.atStartOfDay());
        params.add(to.plusDays(1).atStartOfDay());
        if (itemPublicId != null && !itemPublicId.isBlank()) {
            sql.append("AND i.public_id = ? ");
            params.add(itemPublicId);
        }
        sql.append("GROUP BY i.public_id, i.code, i.name_ar, i.name_en, i.base_unit "
                + "ORDER BY i.code");
        return Sql.query(em, sql.toString(), params.toArray());
    }
}
