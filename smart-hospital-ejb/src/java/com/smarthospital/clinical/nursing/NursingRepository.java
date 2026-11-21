package com.smarthospital.clinical.nursing;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for nursing tasks, medication administration and EWS. */
@Stateless
public class NursingRepository {

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

    public Map<String, Object> admissionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, e.status AS encounter_status, "
                        + "w.public_id AS ward_public_id, w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code "
                        + "FROM admissions a JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "WHERE a.public_id = ?", publicId);
    }

    public Map<String, Object> admissionById(long admissionId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, w.public_id AS ward_public_id, "
                        + "w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code "
                        + "FROM admissions a JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "WHERE a.id = ?", admissionId);
    }

    public Map<String, Object> encounterByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, patient_id, status, type FROM encounters WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> activeAdmissionForPatient(long patientId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, e.public_id AS encounter_public_id, "
                        + "w.public_id AS ward_public_id, w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code "
                        + "FROM admissions a JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "WHERE a.patient_id = ? AND a.status IN ('ADMITTED', 'TRANSFERRED') "
                        + "ORDER BY a.id DESC LIMIT 1", patientId);
    }

    public Map<String, Object> wardByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM wards WHERE public_id = ?", publicId);
    }

    public Map<String, Object> userByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, display_name_ar, display_name_en FROM users WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> userById(long id) {
        return Sql.one(em,
                "SELECT id, public_id, display_name_ar, display_name_en FROM users WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // Nursing tasks
    // ------------------------------------------------------------------

    public Map<String, Object> taskByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT t.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "a.public_id AS admission_public_id, e.public_id AS encounter_public_id, "
                        + "w.public_id AS ward_public_id, w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.code AS bed_code, "
                        + "au.public_id AS assigned_to_public_id, "
                        + "au.display_name_ar AS assigned_to_ar, au.display_name_en AS assigned_to_en, "
                        + "cu.display_name_ar AS completed_by_ar, cu.display_name_en AS completed_by_en "
                        + "FROM nursing_tasks t JOIN patients p ON p.id = t.patient_id "
                        + "LEFT JOIN admissions a ON a.id = t.admission_id "
                        + "LEFT JOIN encounters e ON e.id = t.encounter_id "
                        + "LEFT JOIN wards w ON w.id = t.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "LEFT JOIN users au ON au.id = t.assigned_to "
                        + "LEFT JOIN users cu ON cu.id = t.completed_by "
                        + "WHERE t.public_id = ?", publicId);
    }

    public List<Map<String, Object>> searchTasks(String status, String taskType, String wardPublicId,
                                                 Long assignedToId, Long admissionId, String query,
                                                 int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildTaskWhere(status, taskType, wardPublicId, assignedToId, admissionId,
                query, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT t.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, a.public_id AS admission_public_id, "
                        + "e.public_id AS encounter_public_id, w.public_id AS ward_public_id, "
                        + "w.name_ar AS ward_ar, w.name_en AS ward_en, b.code AS bed_code, "
                        + "au.public_id AS assigned_to_public_id, "
                        + "au.display_name_ar AS assigned_to_ar, au.display_name_en AS assigned_to_en "
                        + "FROM nursing_tasks t JOIN patients p ON p.id = t.patient_id "
                        + "LEFT JOIN admissions a ON a.id = t.admission_id "
                        + "LEFT JOIN encounters e ON e.id = t.encounter_id "
                        + "LEFT JOIN wards w ON w.id = t.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "LEFT JOIN users au ON au.id = t.assigned_to "
                        + where + " ORDER BY t.due_at IS NULL, t.due_at, t.id DESC LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long countTasks(String status, String taskType, String wardPublicId, Long assignedToId,
                           Long admissionId, String query) {
        List<Object> params = new ArrayList<>();
        String where = buildTaskWhere(status, taskType, wardPublicId, assignedToId, admissionId,
                query, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM nursing_tasks t "
                        + "JOIN patients p ON p.id = t.patient_id "
                        + "LEFT JOIN admissions a ON a.id = t.admission_id "
                        + "LEFT JOIN wards w ON w.id = t.ward_id "
                        + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildTaskWhere(String status, String taskType, String wardPublicId,
                                  Long assignedToId, Long admissionId, String query,
                                  List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND t.status = ?");
            params.add(status);
        }
        if (taskType != null && !taskType.isBlank()) {
            where.append(" AND t.task_type = ?");
            params.add(taskType);
        }
        if (wardPublicId != null && !wardPublicId.isBlank()) {
            where.append(" AND w.public_id = ?");
            params.add(wardPublicId);
        }
        if (assignedToId != null) {
            where.append(" AND t.assigned_to = ?");
            params.add(assignedToId);
        }
        if (admissionId != null) {
            where.append(" AND t.admission_id = ?");
            params.add(admissionId);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (t.title LIKE ? OR p.full_name LIKE ? OR p.medical_number LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public long insertTask(String publicId, long patientId, Long encounterId, Long admissionId,
                           Long wardId, String taskType, String title, String description,
                           String priority, java.time.LocalDateTime dueAt, Long assignedTo,
                           Long actorId) {
        if (assignedTo == null) {
            return Sql.insert(em,
                    "INSERT INTO nursing_tasks (public_id, patient_id, encounter_id, admission_id, "
                            + "ward_id, task_type, title, description, priority, status, due_at, "
                            + "created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                    publicId, patientId, encounterId, admissionId, wardId, taskType, title,
                    description, priority, dueAt, actorId);
        }
        return Sql.insert(em,
                "INSERT INTO nursing_tasks (public_id, patient_id, encounter_id, admission_id, ward_id, "
                        + "task_type, title, description, priority, status, due_at, assigned_to, "
                        + "assigned_by, assigned_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, ?, ?, NOW(6), ?)",
                publicId, patientId, encounterId, admissionId, wardId, taskType, title, description,
                priority, dueAt, assignedTo, actorId, actorId);
    }

    public int updateTask(long id, long version, String title, String description, String priority,
                          java.time.LocalDateTime dueAt, Long assignedTo, Long actorId) {
        if (assignedTo == null) {
            return Sql.update(em,
                    "UPDATE nursing_tasks SET title = ?, description = ?, priority = ?, due_at = ?, "
                            + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                            + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')",
                    title, description, priority, dueAt, actorId, id, version);
        }
        return Sql.update(em,
                "UPDATE nursing_tasks SET title = ?, description = ?, priority = ?, due_at = ?, "
                        + "assigned_to = ?, assigned_by = ?, assigned_at = NOW(6), status = 'ASSIGNED', "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')",
                title, description, priority, dueAt, assignedTo, actorId, actorId, id, version);
    }

    public int assignTask(long id, long version, long assignedTo, long actorId) {
        return Sql.update(em,
                "UPDATE nursing_tasks SET assigned_to = ?, assigned_by = ?, assigned_at = NOW(6), "
                        + "status = 'ASSIGNED', updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')",
                assignedTo, actorId, actorId, id, version);
    }

    public int startTask(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE nursing_tasks SET status = 'IN_PROGRESS', updated_at = NOW(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED')",
                actorId, id, version);
    }

    public int completeTask(long id, long version, String note, long actorId) {
        return Sql.update(em,
                "UPDATE nursing_tasks SET status = 'COMPLETED', completed_by = ?, completed_at = NOW(6), "
                        + "completion_note = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')",
                actorId, note, actorId, id, version);
    }

    public int cancelTask(long id, long version, String note, long actorId) {
        return Sql.update(em,
                "UPDATE nursing_tasks SET status = 'CANCELLED', completion_note = ?, "
                        + "completed_by = ?, completed_at = NOW(6), updated_at = NOW(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')",
                note, actorId, actorId, id, version);
    }

    public List<Map<String, Object>> tasksForAdmission(long admissionId) {
        return Sql.query(em,
                "SELECT t.*, au.display_name_ar AS assigned_to_ar, au.display_name_en AS assigned_to_en "
                        + "FROM nursing_tasks t LEFT JOIN users au ON au.id = t.assigned_to "
                        + "WHERE t.admission_id = ? ORDER BY t.due_at IS NULL, t.due_at, t.id DESC",
                admissionId);
    }

    // ------------------------------------------------------------------
    // Medication administration and FEFO stock
    // ------------------------------------------------------------------

    public Map<String, Object> prescriptionItemByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT pi.*, pr.public_id AS prescription_public_id, pr.status AS prescription_status, "
                        + "pr.patient_id, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, d.public_id AS drug_public_id, "
                        + "d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en "
                        + "FROM prescription_items pi "
                        + "JOIN prescriptions pr ON pr.id = pi.prescription_id "
                        + "JOIN patients p ON p.id = pr.patient_id "
                        + "JOIN drugs d ON d.id = pi.drug_id "
                        + "WHERE pi.public_id = ?", publicId);
    }

    public List<Map<String, Object>> fefoBatches(long drugId) {
        return Sql.query(em,
                "SELECT id, public_id, drug_id, batch_number, expiry_date, quantity_available, version, status "
                        + "FROM drug_batches WHERE drug_id = ? AND status = 'ACTIVE' "
                        + "AND quantity_available > 0 AND expiry_date >= CURDATE() "
                        + "ORDER BY expiry_date, id FOR UPDATE", drugId);
    }

    public Map<String, Object> batchByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT b.*, d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en "
                        + "FROM drug_batches b JOIN drugs d ON d.id = b.drug_id "
                        + "WHERE b.public_id = ?", publicId);
    }

    public Map<String, Object> batchByIdForUpdate(long batchId) {
        return Sql.one(em,
                "SELECT * FROM drug_batches WHERE id = ? FOR UPDATE", batchId);
    }

    public int decrementBatch(long batchId, long version, int quantity, long actorId) {
        return Sql.update(em,
                "UPDATE drug_batches SET quantity_available = quantity_available - ?, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND quantity_available >= ? "
                        + "AND status = 'ACTIVE'",
                quantity, actorId, batchId, version, quantity);
    }

    public int depleteBatch(long batchId, long version, long actorId) {
        return Sql.update(em,
                "UPDATE drug_batches SET status = 'DEPLETED', updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ? AND quantity_available = 0",
                actorId, batchId, version);
    }

    public long insertStockMovement(String publicId, long drugId, long batchId, String movementType,
                                    int quantityDelta, String referenceType, String referencePublicId,
                                    String reason, long actorId) {
        return Sql.insert(em,
                "INSERT INTO stock_movements (public_id, drug_id, batch_id, movement_type, "
                        + "quantity_delta, reference_type, reference_public_id, reason, performed_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, drugId, batchId, movementType, quantityDelta, referenceType,
                referencePublicId, reason, actorId);
    }

    public long insertMedicationAdministration(String publicId, long patientId, Long admissionId,
                                               long prescriptionItemId, long drugId, long batchId,
                                               String dose, String route, String status,
                                               String barcodePatient, String barcodeDrug,
                                               String barcodeBatch, boolean verifiedPatient,
                                               boolean verifiedDrug, boolean verifiedBatch,
                                               String notes, long actorId) {
        return Sql.insert(em,
                "INSERT INTO medication_administrations (public_id, patient_id, admission_id, "
                        + "prescription_item_id, drug_id, batch_id, dose, route, status, "
                        + "barcode_patient, barcode_drug, barcode_batch, verified_patient, "
                        + "verified_drug, verified_batch, administered_by, notes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, patientId, admissionId, prescriptionItemId, drugId, batchId, dose, route,
                status, barcodePatient, barcodeDrug, barcodeBatch,
                verifiedPatient ? 1 : 0, verifiedDrug ? 1 : 0, verifiedBatch ? 1 : 0,
                actorId, notes);
    }

    public List<Map<String, Object>> administrationsForAdmission(long admissionId) {
        return Sql.query(em,
                "SELECT m.*, d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "b.batch_number, u.display_name_ar AS administered_by_ar, "
                        + "u.display_name_en AS administered_by_en "
                        + "FROM medication_administrations m "
                        + "JOIN drugs d ON d.id = m.drug_id "
                        + "JOIN drug_batches b ON b.id = m.batch_id "
                        + "JOIN users u ON u.id = m.administered_by "
                        + "WHERE m.admission_id = ? ORDER BY m.administered_at DESC, m.id DESC LIMIT 100",
                admissionId);
    }

    // ------------------------------------------------------------------
    // Vitals and early warning score
    // ------------------------------------------------------------------

    public Map<String, Object> latestVitals(long encounterId) {
        return Sql.one(em,
                "SELECT * FROM vitals WHERE encounter_id = ? "
                        + "ORDER BY recorded_at DESC, id DESC LIMIT 1", encounterId);
    }

    public List<Map<String, Object>> vitalsHistory(long encounterId, int limit) {
        return Sql.query(em,
                "SELECT * FROM vitals WHERE encounter_id = ? "
                        + "ORDER BY recorded_at DESC, id DESC LIMIT ?", encounterId, limit);
    }

    public long insertEws(String publicId, long encounterId, long patientId, int score,
                          String riskLevel, String parametersJson, long actorId) {
        return Sql.insert(em,
                "INSERT INTO early_warning_scores (public_id, encounter_id, patient_id, score, "
                        + "risk_level, parameters_json, calculated_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, encounterId, patientId, score, riskLevel, parametersJson, actorId);
    }

    public Map<String, Object> latestEws(long encounterId) {
        return Sql.one(em,
                "SELECT * FROM early_warning_scores WHERE encounter_id = ? "
                        + "ORDER BY calculated_at DESC, id DESC LIMIT 1", encounterId);
    }

    public List<Map<String, Object>> ewsHistory(long encounterId, int limit) {
        return Sql.query(em,
                "SELECT * FROM early_warning_scores WHERE encounter_id = ? "
                        + "ORDER BY calculated_at DESC, id DESC LIMIT ?", encounterId, limit);
    }
}
