package com.smarthospital.clinical.pharmacy;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for pharmacy stock, dispensing and returns (FR-PHR). */
@Stateless
public class PharmacyRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Drug catalog
    // ------------------------------------------------------------------

    public List<Map<String, Object>> drugs(String query, boolean activeOnly, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildDrugWhere(query, activeOnly, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT d.*, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM drug_batches b "
                        + " WHERE b.drug_id = d.id AND b.status = 'ACTIVE' "
                        + " AND b.expiry_date >= CURDATE()) AS stock_available, "
                        + "(SELECT COUNT(*) FROM drug_batches b WHERE b.drug_id = d.id "
                        + " AND b.status = 'ACTIVE') AS batch_count "
                        + "FROM drugs d " + where + " ORDER BY d.name_ar, d.id LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long countDrugs(String query, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = buildDrugWhere(query, activeOnly, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM drugs d " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildDrugWhere(String query, boolean activeOnly, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (activeOnly) {
            where.append(" AND d.active = 1");
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (d.code LIKE ? OR d.name_ar LIKE ? OR d.name_en LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public Map<String, Object> drugByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT d.*, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM drug_batches b "
                        + " WHERE b.drug_id = d.id AND b.status = 'ACTIVE' "
                        + " AND b.expiry_date >= CURDATE()) AS stock_available "
                        + "FROM drugs d WHERE d.public_id = ?", publicId);
    }

    public Map<String, Object> drugByCode(String code) {
        return Sql.one(em, "SELECT * FROM drugs WHERE code = ?", code);
    }

    public long insertDrug(String publicId, String code, String nameAr, String nameEn, String form,
                           String strength, String category, int reorderLevel, BigDecimal unitPrice,
                           long actorId) {
        return Sql.insert(em,
                "INSERT INTO drugs (public_id, code, name_ar, name_en, form, strength, category, "
                        + "reorder_level, unit_price, active, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                publicId, code, nameAr, nameEn, form, strength, category, reorderLevel, unitPrice,
                actorId);
    }

    // ------------------------------------------------------------------
    // Batches
    // ------------------------------------------------------------------

    public List<Map<String, Object>> batches(String drugPublicId, String status, String query,
                                             int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildBatchWhere(drugPublicId, status, query, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT b.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, d.form, d.strength, "
                        + "u.display_name_ar AS received_by_ar, u.display_name_en AS received_by_en, "
                        + "DATEDIFF(b.expiry_date, CURDATE()) AS days_to_expiry "
                        + "FROM drug_batches b JOIN drugs d ON d.id = b.drug_id "
                        + "JOIN users u ON u.id = b.received_by "
                        + where + " ORDER BY b.expiry_date, b.id LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long countBatches(String drugPublicId, String status, String query) {
        List<Object> params = new ArrayList<>();
        String where = buildBatchWhere(drugPublicId, status, query, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM drug_batches b "
                        + "JOIN drugs d ON d.id = b.drug_id " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildBatchWhere(String drugPublicId, String status, String query,
                                   List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (drugPublicId != null && !drugPublicId.isBlank()) {
            where.append(" AND d.public_id = ?");
            params.add(drugPublicId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = ?");
            params.add(status);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (b.batch_number LIKE ? OR b.lot_number LIKE ? "
                    + "OR d.code LIKE ? OR d.name_ar LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public Map<String, Object> batchByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT b.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en "
                        + "FROM drug_batches b JOIN drugs d ON d.id = b.drug_id "
                        + "WHERE b.public_id = ?", publicId);
    }

    public Map<String, Object> batchByIdForUpdate(long batchId) {
        return Sql.one(em, "SELECT * FROM drug_batches WHERE id = ? FOR UPDATE", batchId);
    }

    public Map<String, Object> batchByNumber(long drugId, String batchNumber) {
        return Sql.one(em,
                "SELECT * FROM drug_batches WHERE drug_id = ? AND batch_number = ?",
                drugId, batchNumber);
    }

    public List<Map<String, Object>> fefoBatches(long drugId) {
        return Sql.query(em,
                "SELECT id, public_id, drug_id, batch_number, expiry_date, quantity_available, "
                        + "quantity_received, version, status FROM drug_batches "
                        + "WHERE drug_id = ? AND status = 'ACTIVE' AND quantity_available > 0 "
                        + "AND expiry_date >= CURDATE() ORDER BY expiry_date, id FOR UPDATE",
                drugId);
    }

    public long insertBatch(String publicId, long drugId, String batchNumber, String lotNumber,
                            java.time.LocalDate expiryDate, int quantity, BigDecimal unitCost,
                            String supplier, long actorId) {
        return Sql.insert(em,
                "INSERT INTO drug_batches (public_id, drug_id, batch_number, lot_number, expiry_date, "
                        + "quantity_received, quantity_available, unit_cost, supplier, status, "
                        + "received_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                publicId, drugId, batchNumber, lotNumber, expiryDate, quantity, quantity, unitCost,
                supplier, actorId);
    }

    public int decrementBatch(long batchId, long version, int quantity, long actorId) {
        return Sql.update(em,
                "UPDATE drug_batches SET quantity_available = quantity_available - ?, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND quantity_available >= ? "
                        + "AND status = 'ACTIVE'",
                quantity, actorId, batchId, version, quantity);
    }

    public int incrementBatch(long batchId, long version, int quantity, long actorId) {
        return Sql.update(em,
                "UPDATE drug_batches SET quantity_available = quantity_available + ?, "
                        + "status = CASE WHEN status = 'DEPLETED' THEN 'ACTIVE' ELSE status END, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND quantity_available + ? <= quantity_received "
                        + "AND status IN ('ACTIVE', 'DEPLETED')",
                quantity, actorId, batchId, version, quantity);
    }

    public int depleteBatch(long batchId, long version, long actorId) {
        return Sql.update(em,
                "UPDATE drug_batches SET status = 'DEPLETED', updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? "
                        + "AND quantity_available = 0",
                actorId, batchId, version);
    }

    // ------------------------------------------------------------------
    // Stock movements
    // ------------------------------------------------------------------

    public long insertStockMovement(String publicId, long drugId, long batchId, String movementType,
                                    int quantityDelta, String referenceType,
                                    String referencePublicId, String reason, long actorId) {
        return Sql.insert(em,
                "INSERT INTO stock_movements (public_id, drug_id, batch_id, movement_type, "
                        + "quantity_delta, reference_type, reference_public_id, reason, performed_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, drugId, batchId, movementType, quantityDelta, referenceType,
                referencePublicId, reason, actorId);
    }

    public List<Map<String, Object>> stockMovements(String drugPublicId, String batchPublicId,
                                                    String movementType, String date,
                                                    int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildMovementWhere(drugPublicId, batchPublicId, movementType, date, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT m.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "b.public_id AS batch_public_id, b.batch_number, b.expiry_date, "
                        + "u.public_id AS performed_by_public_id, "
                        + "u.display_name_ar AS performed_by_ar, "
                        + "u.display_name_en AS performed_by_en "
                        + "FROM stock_movements m JOIN drugs d ON d.id = m.drug_id "
                        + "JOIN drug_batches b ON b.id = m.batch_id "
                        + "JOIN users u ON u.id = m.performed_by "
                        + where + " ORDER BY m.performed_at DESC, m.id DESC LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long countStockMovements(String drugPublicId, String batchPublicId, String movementType,
                                    String date) {
        List<Object> params = new ArrayList<>();
        String where = buildMovementWhere(drugPublicId, batchPublicId, movementType, date, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM stock_movements m "
                        + "JOIN drugs d ON d.id = m.drug_id "
                        + "JOIN drug_batches b ON b.id = m.batch_id " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildMovementWhere(String drugPublicId, String batchPublicId, String movementType,
                                      String date, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (drugPublicId != null && !drugPublicId.isBlank()) {
            where.append(" AND d.public_id = ?");
            params.add(drugPublicId);
        }
        if (batchPublicId != null && !batchPublicId.isBlank()) {
            where.append(" AND b.public_id = ?");
            params.add(batchPublicId);
        }
        if (movementType != null && !movementType.isBlank()) {
            where.append(" AND m.movement_type = ?");
            params.add(movementType);
        }
        if (date != null && !date.isBlank()) {
            where.append(" AND DATE(m.performed_at) = ?");
            params.add(date);
        }
        return where.toString();
    }

    public List<Map<String, Object>> lowStock(int limit) {
        return Sql.query(em,
                "SELECT d.*, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM drug_batches b "
                        + " WHERE b.drug_id = d.id AND b.status = 'ACTIVE' "
                        + " AND b.expiry_date >= CURDATE()) AS stock_available "
                        + "FROM drugs d WHERE d.active = 1 AND "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM drug_batches b "
                        + " WHERE b.drug_id = d.id AND b.status = 'ACTIVE' "
                        + " AND b.expiry_date >= CURDATE()) <= d.reorder_level "
                        + "ORDER BY stock_available, d.name_ar LIMIT ?", limit);
    }

    public List<Map<String, Object>> expiringBatches(int days, int limit) {
        return Sql.query(em,
                "SELECT b.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "DATEDIFF(b.expiry_date, CURDATE()) AS days_to_expiry "
                        + "FROM drug_batches b JOIN drugs d ON d.id = b.drug_id "
                        + "WHERE b.status = 'ACTIVE' AND b.quantity_available > 0 "
                        + "AND b.expiry_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL ? DAY) "
                        + "ORDER BY b.expiry_date, b.id LIMIT ?", days, limit);
    }

    // ------------------------------------------------------------------
    // Prescriptions
    // ------------------------------------------------------------------

    public Map<String, Object> prescriptionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT pr.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, e.status AS encounter_status, "
                        + "u.public_id AS prescribed_by_public_id, "
                        + "u.display_name_ar AS prescribed_by_ar, "
                        + "u.display_name_en AS prescribed_by_en, "
                        + "rv.display_name_ar AS reviewed_by_ar, "
                        + "rv.display_name_en AS reviewed_by_en "
                        + "FROM prescriptions pr JOIN patients p ON p.id = pr.patient_id "
                        + "LEFT JOIN encounters e ON e.id = pr.encounter_id "
                        + "JOIN users u ON u.id = pr.prescribed_by "
                        + "LEFT JOIN users rv ON rv.id = pr.reviewed_by "
                        + "WHERE pr.public_id = ?", publicId);
    }

    public Map<String, Object> lockPrescription(long id) {
        return Sql.one(em, "SELECT * FROM prescriptions WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> prescriptionItems(long prescriptionId) {
        return Sql.query(em,
                "SELECT pi.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, d.form, d.strength, "
                        + "d.unit_price, "
                        + "(SELECT COALESCE(SUM(b.quantity_available), 0) FROM drug_batches b "
                        + " WHERE b.drug_id = d.id AND b.status = 'ACTIVE' "
                        + " AND b.expiry_date >= CURDATE()) AS stock_available "
                        + "FROM prescription_items pi JOIN drugs d ON d.id = pi.drug_id "
                        + "WHERE pi.prescription_id = ? ORDER BY pi.id", prescriptionId);
    }

    public List<Map<String, Object>> queue(int offset, int limit) {
        return Sql.query(em,
                "SELECT pr.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "e.public_id AS encounter_public_id, "
                        + "u.display_name_ar AS prescribed_by_ar, "
                        + "u.display_name_en AS prescribed_by_en, "
                        + "(SELECT COUNT(*) FROM prescription_items i "
                        + " WHERE i.prescription_id = pr.id) AS item_count, "
                        + "(SELECT COALESCE(SUM(i.quantity), 0) FROM prescription_items i "
                        + " WHERE i.prescription_id = pr.id) AS total_quantity, "
                        + "(SELECT COALESCE(SUM(i.dispensed_quantity), 0) FROM prescription_items i "
                        + " WHERE i.prescription_id = pr.id) AS dispensed_quantity "
                        + "FROM prescriptions pr JOIN patients p ON p.id = pr.patient_id "
                        + "LEFT JOIN encounters e ON e.id = pr.encounter_id "
                        + "JOIN users u ON u.id = pr.prescribed_by "
                        + "WHERE pr.status IN ('ISSUED', 'REVIEWED', 'PARTIAL') "
                        + "ORDER BY pr.issued_at, pr.id LIMIT ? OFFSET ?", limit, offset);
    }

    public long countQueue() {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM prescriptions "
                        + "WHERE status IN ('ISSUED', 'REVIEWED', 'PARTIAL')");
        return Sql.longValue(row, "total", 0);
    }

    public int reviewPrescription(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE prescriptions SET status = 'REVIEWED', reviewed_by = ?, "
                        + "reviewed_at = NOW(6), updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = 'ISSUED'",
                actorId, actorId, id, version);
    }

    public int rejectPrescription(long id, long version, long actorId, String reason) {
        return Sql.update(em,
                "UPDATE prescriptions SET status = 'REJECTED', rejected_reason = ?, "
                        + "reviewed_by = ?, reviewed_at = NOW(6), updated_at = NOW(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'ISSUED'",
                reason, actorId, actorId, id, version);
    }

    public int setPrescriptionStatus(long id, long version, String status, long actorId) {
        return Sql.update(em,
                "UPDATE prescriptions SET status = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                status, actorId, id, version);
    }

    public int addDispensedQuantity(long itemId, int quantity) {
        return Sql.update(em,
                "UPDATE prescription_items SET dispensed_quantity = dispensed_quantity + ? "
                        + "WHERE id = ? AND dispensed_quantity + ? <= quantity",
                quantity, itemId, quantity);
    }

    public int subtractDispensedQuantity(long itemId, int quantity) {
        return Sql.update(em,
                "UPDATE prescription_items SET dispensed_quantity = dispensed_quantity - ? "
                        + "WHERE id = ? AND dispensed_quantity >= ?",
                quantity, itemId, quantity);
    }

    public List<Map<String, Object>> patientAllergies(long patientId) {
        return Sql.query(em,
                "SELECT public_id, substance, allergy_type, severity, reaction "
                        + "FROM patient_allergies WHERE patient_id = ? AND active = 1 "
                        + "ORDER BY FIELD(severity, 'LIFE_THREATENING', 'SEVERE', 'MODERATE', 'MILD'), id",
                patientId);
    }

    public List<Map<String, Object>> interactions(List<Long> drugIds) {
        if (drugIds == null || drugIds.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(", ",
                Collections.nCopies(drugIds.size(), "?"));
        List<Object> params = new ArrayList<>(drugIds);
        return Sql.query(em,
                "SELECT di.*, d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "o.code AS other_code, o.name_ar AS other_ar, o.name_en AS other_en "
                        + "FROM drug_interactions di JOIN drugs d ON d.id = di.drug_id "
                        + "JOIN drugs o ON o.id = di.interacting_drug_id "
                        + "WHERE di.active = 1 AND di.drug_id IN (" + placeholders + ")",
                params.toArray());
    }

    // ------------------------------------------------------------------
    // Dispensing and returns
    // ------------------------------------------------------------------

    public long insertDispensing(String publicId, long prescriptionId, long patientId,
                                 long actorId, String notes) {
        return Sql.insert(em,
                "INSERT INTO dispensing_transactions (public_id, prescription_id, patient_id, "
                        + "dispensed_by, status, notes) VALUES (?, ?, ?, ?, 'COMMITTED', ?)",
                publicId, prescriptionId, patientId, actorId, notes);
    }

    public long insertDispensingItem(String publicId, long dispensingId, long prescriptionItemId,
                                     long drugId, long batchId, int quantity, long movementId) {
        return Sql.insert(em,
                "INSERT INTO dispensing_items (public_id, dispensing_id, prescription_item_id, "
                        + "drug_id, batch_id, quantity, stock_movement_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, dispensingId, prescriptionItemId, drugId, batchId, quantity, movementId);
    }

    public Map<String, Object> dispensingByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT d.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, pr.public_id AS prescription_public_id, "
                        + "u.public_id AS dispensed_by_public_id, "
                        + "u.display_name_ar AS dispensed_by_ar, "
                        + "u.display_name_en AS dispensed_by_en "
                        + "FROM dispensing_transactions d JOIN patients p ON p.id = d.patient_id "
                        + "JOIN prescriptions pr ON pr.id = d.prescription_id "
                        + "JOIN users u ON u.id = d.dispensed_by "
                        + "WHERE d.public_id = ?", publicId);
    }

    public List<Map<String, Object>> dispensingItems(long dispensingId) {
        return Sql.query(em,
                "SELECT di.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "b.batch_number, b.expiry_date, b.public_id AS batch_public_id, "
                        + "pi.dose, pi.route, pi.frequency "
                        + "FROM dispensing_items di JOIN drugs d ON d.id = di.drug_id "
                        + "JOIN drug_batches b ON b.id = di.batch_id "
                        + "JOIN prescription_items pi ON pi.id = di.prescription_item_id "
                        + "WHERE di.dispensing_id = ? ORDER BY di.id", dispensingId);
    }

    public List<Map<String, Object>> dispensingForPrescription(long prescriptionId) {
        return Sql.query(em,
                "SELECT d.*, u.display_name_ar AS dispensed_by_ar, "
                        + "u.display_name_en AS dispensed_by_en "
                        + "FROM dispensing_transactions d JOIN users u ON u.id = d.dispensed_by "
                        + "WHERE d.prescription_id = ? ORDER BY d.id DESC", prescriptionId);
    }

    public Map<String, Object> dispensingItemByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT di.*, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, b.batch_number "
                        + "FROM dispensing_items di JOIN drugs d ON d.id = di.drug_id "
                        + "JOIN drug_batches b ON b.id = di.batch_id "
                        + "WHERE di.public_id = ?", publicId);
    }

    public int updateDispensingStatus(long id, long version, String status) {
        return Sql.update(em,
                "UPDATE dispensing_transactions SET status = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                status, id, version);
    }

    public int addReturnedQuantity(long itemId, int quantity) {
        return Sql.update(em,
                "UPDATE dispensing_items SET returned_quantity = returned_quantity + ? "
                        + "WHERE id = ? AND returned_quantity + ? <= quantity",
                quantity, itemId, quantity);
    }

    public long insertReturn(String publicId, long dispensingId, long dispensingItemId,
                             int quantity, String reason, long actorId, long movementId) {
        return Sql.insert(em,
                "INSERT INTO dispensing_returns (public_id, dispensing_id, dispensing_item_id, "
                        + "quantity, reason, returned_by, stock_movement_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, dispensingId, dispensingItemId, quantity, reason, actorId, movementId);
    }

    public List<Map<String, Object>> returnsForDispensing(long dispensingId) {
        return Sql.query(em,
                "SELECT r.*, d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "u.display_name_ar AS returned_by_ar, "
                        + "u.display_name_en AS returned_by_en "
                        + "FROM dispensing_returns r "
                        + "JOIN dispensing_items di ON di.id = r.dispensing_item_id "
                        + "JOIN drugs d ON d.id = di.drug_id "
                        + "JOIN users u ON u.id = r.returned_by "
                        + "WHERE r.dispensing_id = ? ORDER BY r.returned_at DESC, r.id DESC",
                dispensingId);
    }
}
