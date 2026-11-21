package com.smarthospital.clinical.lab;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for the laboratory workflow (FR-LAB). */
@Stateless
public class LabRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public Map<String, Object> patientByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, medical_number, full_name, gender, birth_date, status, phone "
                        + "FROM patients WHERE public_id = ?", publicId);
    }

    public Map<String, Object> encounterByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT e.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date "
                        + "FROM encounters e JOIN patients p ON p.id = e.patient_id "
                        + "WHERE e.public_id = ?", publicId);
    }

    public Map<String, Object> serviceOrderByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, encounter_id, order_type, status FROM service_orders "
                        + "WHERE public_id = ?", publicId);
    }

    public Map<String, Object> userByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, display_name_ar, display_name_en FROM users WHERE public_id = ?",
                publicId);
    }

    public List<Map<String, Object>> tests(String query, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (activeOnly) {
            where.append(" AND active = 1");
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (code LIKE ? OR name_ar LIKE ? OR name_en LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return Sql.query(em,
                "SELECT * FROM lab_tests " + where + " ORDER BY code", params.toArray());
    }

    public Map<String, Object> testByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM lab_tests WHERE public_id = ?", publicId);
    }

    public Map<String, Object> testByCode(String code) {
        return Sql.one(em, "SELECT * FROM lab_tests WHERE code = ?", code);
    }

    public long insertTest(String publicId, String code, String nameAr, String nameEn,
                           String specimenType, String unit, String resultType, BigDecimal refLow,
                           BigDecimal refHigh, String refText, BigDecimal criticalLow,
                           BigDecimal criticalHigh, int targetTatMinutes, Long serviceId,
                           long actorId) {
        return Sql.insert(em,
                "INSERT INTO lab_tests (public_id, code, name_ar, name_en, specimen_type, unit, "
                        + "result_type, ref_low, ref_high, ref_text, critical_low, critical_high, "
                        + "target_tat_minutes, service_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, code, nameAr, nameEn, specimenType, unit, resultType, refLow, refHigh,
                refText, criticalLow, criticalHigh, targetTatMinutes, serviceId, actorId);
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    public Map<String, Object> orderByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT o.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, e.type AS encounter_type, "
                        + "u.public_id AS ordered_by_public_id, "
                        + "u.display_name_ar AS ordered_by_ar, u.display_name_en AS ordered_by_en, "
                        + "so.public_id AS service_order_public_id "
                        + "FROM lab_orders o JOIN patients p ON p.id = o.patient_id "
                        + "LEFT JOIN encounters e ON e.id = o.encounter_id "
                        + "LEFT JOIN users u ON u.id = o.ordered_by "
                        + "LEFT JOIN service_orders so ON so.id = o.service_order_id "
                        + "WHERE o.public_id = ?", publicId);
    }

    public Map<String, Object> orderById(long id) {
        return Sql.one(em,
                "SELECT o.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, "
                        + "u.public_id AS ordered_by_public_id, "
                        + "u.display_name_ar AS ordered_by_ar, u.display_name_en AS ordered_by_en "
                        + "FROM lab_orders o JOIN patients p ON p.id = o.patient_id "
                        + "LEFT JOIN encounters e ON e.id = o.encounter_id "
                        + "LEFT JOIN users u ON u.id = o.ordered_by "
                        + "WHERE o.id = ?", id);
    }

    public List<Map<String, Object>> searchOrders(String status, String priority,
                                                  String patientPublicId, String query,
                                                  String date, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildOrderWhere(status, priority, patientPublicId, query, date, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT o.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "e.public_id AS encounter_public_id, "
                        + "(SELECT COUNT(*) FROM lab_order_items i WHERE i.lab_order_id = o.id) AS item_count, "
                        + "(SELECT COUNT(*) FROM lab_results r WHERE r.lab_order_id = o.id "
                        + " AND r.status IN ('RESULTED', 'VERIFIED', 'RELEASED', 'CORRECTED')) AS result_count "
                        + "FROM lab_orders o JOIN patients p ON p.id = o.patient_id "
                        + "LEFT JOIN encounters e ON e.id = o.encounter_id "
                        + where + " ORDER BY FIELD(o.priority, 'STAT', 'URGENT', 'ROUTINE'), "
                        + "o.ordered_at, o.id LIMIT ? OFFSET ?", params.toArray());
    }

    public long countOrders(String status, String priority, String patientPublicId, String query,
                            String date) {
        List<Object> params = new ArrayList<>();
        String where = buildOrderWhere(status, priority, patientPublicId, query, date, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM lab_orders o "
                        + "JOIN patients p ON p.id = o.patient_id " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildOrderWhere(String status, String priority, String patientPublicId,
                                   String query, String date, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND o.status = ?");
            params.add(status);
        }
        if (priority != null && !priority.isBlank()) {
            where.append(" AND o.priority = ?");
            params.add(priority);
        }
        if (patientPublicId != null && !patientPublicId.isBlank()) {
            where.append(" AND p.public_id = ?");
            params.add(patientPublicId);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (p.full_name LIKE ? OR p.medical_number LIKE ? OR o.public_id LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (date != null && !date.isBlank()) {
            where.append(" AND DATE(o.ordered_at) = ?");
            params.add(date);
        }
        return where.toString();
    }

    public long insertOrder(String publicId, long encounterId, long patientId, long orderedBy,
                            Long serviceOrderId, String priority, String clinicalNotes) {
        return Sql.insert(em,
                "INSERT INTO lab_orders (public_id, encounter_id, patient_id, ordered_by, "
                        + "service_order_id, priority, status, clinical_notes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ORDERED', ?)",
                publicId, encounterId, patientId, orderedBy, serviceOrderId, priority,
                clinicalNotes);
    }

    public long insertOrderItem(String publicId, long labOrderId, long labTestId) {
        return Sql.insert(em,
                "INSERT INTO lab_order_items (public_id, lab_order_id, lab_test_id, status) "
                        + "VALUES (?, ?, ?, 'ORDERED')", publicId, labOrderId, labTestId);
    }

    public List<Map<String, Object>> orderItems(long orderId) {
        return Sql.query(em,
                "SELECT i.*, t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "t.specimen_type, t.unit, t.result_type, t.ref_low, t.ref_high, t.ref_text, "
                        + "t.critical_low, t.critical_high, t.target_tat_minutes, "
                        + "r.public_id AS result_public_id, r.status AS result_status, "
                        + "r.value_numeric, r.value_text, r.flag, r.critical_flag, r.comment, "
                        + "r.verified_at, r.released_at "
                        + "FROM lab_order_items i JOIN lab_tests t ON t.id = i.lab_test_id "
                        + "LEFT JOIN lab_results r ON r.id = i.result_id "
                        + "WHERE i.lab_order_id = ? ORDER BY i.id", orderId);
    }

    public Map<String, Object> itemByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT i.*, t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "t.specimen_type, t.unit, t.result_type, t.ref_low, t.ref_high, t.ref_text, "
                        + "t.critical_low, t.critical_high, t.target_tat_minutes, o.patient_id, "
                        + "o.public_id AS order_public_id "
                        + "FROM lab_order_items i JOIN lab_tests t ON t.id = i.lab_test_id "
                        + "JOIN lab_orders o ON o.id = i.lab_order_id "
                        + "WHERE i.public_id = ?", publicId);
    }

    public Map<String, Object> itemByOrderAndTest(long orderId, long testId) {
        return Sql.one(em,
                "SELECT * FROM lab_order_items WHERE lab_order_id = ? AND lab_test_id = ?",
                orderId, testId);
    }

    public int setOrderStatus(long id, long version, String fromStatus, String toStatus,
                              long actorId) {
        return Sql.update(em,
                "UPDATE lab_orders SET status = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = ?",
                toStatus, actorId, id, version, fromStatus);
    }

    public int setOrderStatusFrom(long id, long version, String toStatus, List<String> fromStatuses,
                                  long actorId) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(fromStatuses.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(toStatus);
        params.add(actorId);
        params.add(id);
        params.add(version);
        params.addAll(fromStatuses);
        return Sql.update(em,
                "UPDATE lab_orders SET status = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status IN ("
                        + placeholders + ")", params.toArray());
    }

    public int cancelOrder(long id, long version, String reason, long actorId) {
        return Sql.update(em,
                "UPDATE lab_orders SET status = 'CANCELLED', cancel_reason = ?, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('ORDERED', 'COLLECTED', 'REJECTED')",
                reason, actorId, id, version);
    }

    public int updateItemsStatus(long orderId, String status) {
        return Sql.update(em,
                "UPDATE lab_order_items SET status = ? WHERE lab_order_id = ?", status, orderId);
    }

    public int updateItemsStatusFrom(long orderId, String status, List<String> fromStatuses) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(fromStatuses.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(status);
        params.add(orderId);
        params.addAll(fromStatuses);
        return Sql.update(em,
                "UPDATE lab_order_items SET status = ? WHERE lab_order_id = ? AND status IN ("
                        + placeholders + ")", params.toArray());
    }

    public int updateItemStatus(long itemId, String status, Long resultId) {
        if (resultId == null) {
            return Sql.update(em, "UPDATE lab_order_items SET status = ? WHERE id = ?",
                    status, itemId);
        }
        return Sql.update(em,
                "UPDATE lab_order_items SET status = ?, result_id = ? WHERE id = ?",
                status, resultId, itemId);
    }

    public long countItems(long orderId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM lab_order_items WHERE lab_order_id = ?", orderId);
        return Sql.longValue(row, "total", 0);
    }

    public long countResultsWithStatus(long orderId, List<String> statuses) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(statuses.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(orderId);
        params.addAll(statuses);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM lab_results WHERE lab_order_id = ? AND status IN ("
                        + placeholders + ")", params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    // ------------------------------------------------------------------
    // Specimens
    // ------------------------------------------------------------------

    public Map<String, Object> specimenByBarcode(String barcode) {
        return Sql.one(em, "SELECT * FROM lab_specimens WHERE barcode = ?", barcode);
    }

    public Map<String, Object> specimenByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT s.*, o.public_id AS order_public_id, o.patient_id, o.status AS order_status, "
                        + "p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number "
                        + "FROM lab_specimens s JOIN lab_orders o ON o.id = s.lab_order_id "
                        + "JOIN patients p ON p.id = o.patient_id "
                        + "WHERE s.public_id = ?", publicId);
    }

    public List<Map<String, Object>> specimensForOrder(long orderId) {
        return Sql.query(em,
                "SELECT * FROM lab_specimens WHERE lab_order_id = ? ORDER BY collected_at DESC, id DESC",
                orderId);
    }

    public long insertSpecimen(String publicId, long orderId, String barcode, String specimenType,
                               long actorId) {
        return Sql.insert(em,
                "INSERT INTO lab_specimens (public_id, lab_order_id, barcode, specimen_type, "
                        + "status, collected_by) VALUES (?, ?, ?, ?, 'COLLECTED', ?)",
                publicId, orderId, barcode, specimenType, actorId);
    }

    public int receiveSpecimen(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE lab_specimens SET status = 'RECEIVED', received_by = ?, received_at = NOW(6), "
                        + "version = version + 1 WHERE id = ? AND version = ? AND status = 'COLLECTED'",
                actorId, id, version);
    }

    public int rejectSpecimen(long id, long version, long actorId, String reason) {
        return Sql.update(em,
                "UPDATE lab_specimens SET status = 'REJECTED', rejected_by = ?, rejected_at = NOW(6), "
                        + "rejection_reason = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'COLLECTED'",
                actorId, reason, id, version);
    }

    public long insertSampleEvent(long specimenId, String fromStatus, String toStatus, long actorId,
                                  String note) {
        return Sql.insert(em,
                "INSERT INTO lab_sample_events (specimen_id, from_status, to_status, actor_id, note) "
                        + "VALUES (?, ?, ?, ?, ?)",
                specimenId, fromStatus, toStatus, actorId, note);
    }

    public List<Map<String, Object>> sampleEvents(long specimenId) {
        return Sql.query(em,
                "SELECT e.*, u.display_name_ar AS actor_ar, u.display_name_en AS actor_en "
                        + "FROM lab_sample_events e JOIN users u ON u.id = e.actor_id "
                        + "WHERE e.specimen_id = ? ORDER BY e.occurred_at, e.id", specimenId);
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    public Map<String, Object> resultByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT r.*, t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "t.unit AS test_unit, t.result_type, t.ref_text AS test_ref_text, "
                        + "t.ref_low AS test_ref_low, t.ref_high AS test_ref_high, "
                        + "t.critical_low AS test_critical_low, t.critical_high AS test_critical_high, "
                        + "p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.gender AS patient_gender, p.birth_date AS patient_birth_date, "
                        + "o.public_id AS order_public_id, o.status AS order_status, o.priority, "
                        + "o.ordered_at, o.ordered_by, "
                        + "u.public_id AS entered_by_public_id, "
                        + "u.display_name_ar AS entered_by_ar, u.display_name_en AS entered_by_en, "
                        + "v.display_name_ar AS verified_by_ar, v.display_name_en AS verified_by_en, "
                        + "l.display_name_ar AS released_by_ar, l.display_name_en AS released_by_en "
                        + "FROM lab_results r JOIN lab_tests t ON t.id = r.lab_test_id "
                        + "JOIN patients p ON p.id = r.patient_id "
                        + "JOIN lab_orders o ON o.id = r.lab_order_id "
                        + "JOIN users u ON u.id = r.entered_by "
                        + "LEFT JOIN users v ON v.id = r.verified_by "
                        + "LEFT JOIN users l ON l.id = r.released_by "
                        + "WHERE r.public_id = ?", publicId);
    }

    public Map<String, Object> resultByItem(long itemId) {
        return Sql.one(em, "SELECT * FROM lab_results WHERE lab_order_item_id = ?", itemId);
    }

    public long insertResult(String publicId, long orderId, long itemId, long testId, long patientId,
                             BigDecimal valueNumeric, String valueText, String unit,
                             BigDecimal refLow, BigDecimal refHigh, String refText, String flag,
                             boolean critical, String comment, long actorId) {
        return Sql.insert(em,
                "INSERT INTO lab_results (public_id, lab_order_id, lab_order_item_id, lab_test_id, "
                        + "patient_id, status, value_numeric, value_text, unit_snapshot, "
                        + "ref_low_snapshot, ref_high_snapshot, ref_text_snapshot, flag, critical_flag, "
                        + "comment, entered_by) "
                        + "VALUES (?, ?, ?, ?, ?, 'RESULTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, orderId, itemId, testId, patientId, valueNumeric, valueText, unit,
                refLow, refHigh, refText, flag, critical ? 1 : 0, comment, actorId);
    }

    public int updateResult(long id, long version, BigDecimal valueNumeric, String valueText,
                            String flag, boolean critical, String comment, long actorId) {
        return Sql.update(em,
                "UPDATE lab_results SET value_numeric = ?, value_text = ?, flag = ?, "
                        + "critical_flag = ?, comment = ?, "
                        + "status = CASE WHEN status = 'CORRECTED' THEN 'CORRECTED' ELSE 'RESULTED' END, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('DRAFT', 'RESULTED', 'CORRECTED')",
                valueNumeric, valueText, flag, critical ? 1 : 0, comment, actorId, id, version);
    }

    public int verifyResult(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE lab_results SET status = 'VERIFIED', verified_by = ?, verified_at = NOW(6), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('RESULTED', 'CORRECTED')",
                actorId, actorId, id, version);
    }

    public int releaseResult(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE lab_results SET status = 'RELEASED', released_by = ?, released_at = NOW(6), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'VERIFIED'",
                actorId, actorId, id, version);
    }

    public int markCorrected(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE lab_results SET status = 'CORRECTED', updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('VERIFIED', 'RELEASED')",
                actorId, id, version);
    }

    public int setResultCurrentVersion(long resultId, long versionId, long actorId) {
        return Sql.update(em,
                "UPDATE lab_results SET current_version_id = ?, updated_at = NOW(6), updated_by = ? "
                        + "WHERE id = ?", versionId, actorId, resultId);
    }

    public int maxResultVersion(long resultId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COALESCE(MAX(version_no), 0) AS max_no FROM lab_result_versions "
                        + "WHERE result_id = ?", resultId);
        return Sql.intValue(row, "maxNo", 0);
    }

    public long insertResultVersion(String publicId, long resultId, int versionNo,
                                    BigDecimal valueNumeric, String valueText, String flag,
                                    String comment, Long amendedFromId, String amendReason,
                                    long actorId) {
        return Sql.insert(em,
                "INSERT INTO lab_result_versions (public_id, result_id, version_no, value_numeric, "
                        + "value_text, flag, comment, amended_from_id, amend_reason, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, resultId, versionNo, valueNumeric, valueText, flag, comment,
                amendedFromId, amendReason, actorId);
    }

    public List<Map<String, Object>> resultVersions(long resultId) {
        return Sql.query(em,
                "SELECT v.*, u.display_name_ar AS created_by_ar, u.display_name_en AS created_by_en "
                        + "FROM lab_result_versions v JOIN users u ON u.id = v.created_by "
                        + "WHERE v.result_id = ? ORDER BY v.version_no DESC", resultId);
    }

    public List<Map<String, Object>> resultsForOrder(long orderId) {
        return Sql.query(em,
                "SELECT r.*, t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "t.unit AS test_unit, t.result_type, t.ref_text AS test_ref_text, "
                        + "t.ref_low AS test_ref_low, t.ref_high AS test_ref_high "
                        + "FROM lab_results r JOIN lab_tests t ON t.id = r.lab_test_id "
                        + "WHERE r.lab_order_id = ? ORDER BY r.id", orderId);
    }

    // ------------------------------------------------------------------
    // Critical alerts
    // ------------------------------------------------------------------

    public long insertCriticalAlert(String publicId, long resultId, long patientId, String notes) {
        return Sql.insert(em,
                "INSERT INTO critical_value_alerts (public_id, result_id, patient_id, status, notes) "
                        + "VALUES (?, ?, ?, 'OPEN', ?)",
                publicId, resultId, patientId, notes);
    }

    public List<Map<String, Object>> criticalAlerts(String status, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            params.add(status);
        }
        params.add(limit);
        return Sql.query(em,
                "SELECT a.*, r.public_id AS result_public_id, r.flag, r.value_numeric, r.value_text, "
                        + "t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number, o.public_id AS order_public_id "
                        + "FROM critical_value_alerts a "
                        + "JOIN lab_results r ON r.id = a.result_id "
                        + "JOIN lab_tests t ON t.id = r.lab_test_id "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "JOIN lab_orders o ON o.id = r.lab_order_id "
                        + where + " ORDER BY a.id DESC LIMIT ?", params.toArray());
    }

    public Map<String, Object> alertByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT a.*, r.public_id AS result_public_id, r.lab_order_id, r.patient_id, "
                        + "t.code AS test_code, t.name_ar AS test_name_ar, t.name_en AS test_name_en, "
                        + "p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number "
                        + "FROM critical_value_alerts a "
                        + "JOIN lab_results r ON r.id = a.result_id "
                        + "JOIN lab_tests t ON t.id = r.lab_test_id "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "WHERE a.public_id = ?", publicId);
    }

    public int acknowledgeAlert(long id, long version, long actorId, String notes) {
        return Sql.update(em,
                "UPDATE critical_value_alerts SET status = 'ACKNOWLEDGED', acknowledged_by = ?, "
                        + "acknowledged_at = NOW(6), notes = COALESCE(?, notes), "
                        + "version = version + 1 WHERE id = ? AND version = ? "
                        + "AND status IN ('OPEN', 'NOTIFIED')",
                actorId, notes, id, version);
    }

    public int markAlertNotified(long id, long userId) {
        return Sql.update(em,
                "UPDATE critical_value_alerts SET status = 'NOTIFIED', notified_user_id = ?, "
                        + "notified_at = NOW(6), version = version + 1 "
                        + "WHERE id = ? AND status = 'OPEN'", userId, id);
    }

    // ------------------------------------------------------------------
    // Worklist
    // ------------------------------------------------------------------

    public List<Map<String, Object>> worklist(String priority, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(
                "WHERE o.status IN ('COLLECTED', 'RECEIVED', 'IN_PROCESS', 'RESULTED')");
        if (priority != null && !priority.isBlank()) {
            where.append(" AND o.priority = ?");
            params.add(priority);
        }
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT o.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "e.public_id AS encounter_public_id, "
                        + "(SELECT COUNT(*) FROM lab_order_items i WHERE i.lab_order_id = o.id) AS item_count, "
                        + "(SELECT COUNT(*) FROM lab_results r WHERE r.lab_order_id = o.id "
                        + " AND r.status IN ('RESULTED', 'VERIFIED', 'RELEASED')) AS result_count, "
                        + "(SELECT COUNT(*) FROM lab_specimens s WHERE s.lab_order_id = o.id "
                        + " AND s.status = 'COLLECTED') AS pending_specimens "
                        + "FROM lab_orders o JOIN patients p ON p.id = o.patient_id "
                        + "LEFT JOIN encounters e ON e.id = o.encounter_id "
                        + where + " ORDER BY FIELD(o.priority, 'STAT', 'URGENT', 'ROUTINE'), "
                        + "o.ordered_at, o.id LIMIT ? OFFSET ?", params.toArray());
    }

    public List<Map<String, Object>> countByStatus() {
        return Sql.query(em,
                "SELECT status, COUNT(*) AS total FROM lab_orders GROUP BY status ORDER BY status");
    }
}
