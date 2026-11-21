package com.smarthospital.clinical.adt;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for admissions, beds and transfers (FR-ADT). */
@Stateless
public class AdtRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public Map<String, Object> patientByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, medical_number, full_name, gender, birth_date, status "
                        + "FROM patients WHERE public_id = ?", publicId);
    }

    public Map<String, Object> encounterByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, patient_id, status, type FROM encounters WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> userByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, display_name_ar, display_name_en FROM users WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> departmentByPublicId(String publicId) {
        return Sql.one(em, "SELECT id, public_id, code FROM departments WHERE public_id = ?", publicId);
    }

    public List<Map<String, Object>> wards() {
        return Sql.query(em,
                "SELECT w.*, d.name_ar AS department_ar, d.name_en AS department_en, "
                        + "(SELECT COUNT(*) FROM beds b WHERE b.ward_id = w.id AND b.active = 1) AS bed_count, "
                        + "(SELECT COUNT(*) FROM beds b WHERE b.ward_id = w.id AND b.active = 1 "
                        + " AND b.status = 'AVAILABLE') AS available_beds "
                        + "FROM wards w LEFT JOIN departments d ON d.id = w.department_id "
                        + "WHERE w.active = 1 ORDER BY w.code");
    }

    public Map<String, Object> wardByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM wards WHERE public_id = ?", publicId);
    }

    // ------------------------------------------------------------------
    // Admissions
    // ------------------------------------------------------------------

    public Map<String, Object> admissionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "e.public_id AS encounter_public_id, e.status AS encounter_status, "
                        + "req.display_name_ar AS requested_by_ar, req.display_name_en AS requested_by_en, "
                        + "apr.display_name_ar AS approved_by_ar, apr.display_name_en AS approved_by_en, "
                        + "w.public_id AS ward_public_id, w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code, b.status AS bed_status, "
                        + "bw.public_id AS bed_ward_public_id, bw.name_ar AS bed_ward_ar, bw.name_en AS bed_ward_en, "
                        + "ba.assigned_at AS bed_assigned_at "
                        + "FROM admissions a "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN users req ON req.id = a.requested_by "
                        + "LEFT JOIN users apr ON apr.id = a.approved_by "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "LEFT JOIN wards bw ON bw.id = b.ward_id "
                        + "WHERE a.public_id = ?", publicId);
    }

    public List<Map<String, Object>> search(String status, String wardPublicId, String query,
                                            int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, wardPublicId, query, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "e.public_id AS encounter_public_id, "
                        + "w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "b.code AS bed_code, bw.name_ar AS bed_ward_ar "
                        + "FROM admissions a "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "LEFT JOIN wards bw ON bw.id = b.ward_id "
                        + where + " ORDER BY a.requested_at DESC, a.id DESC LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long count(String status, String wardPublicId, String query) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(status, wardPublicId, query, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM admissions a "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "LEFT JOIN wards w ON w.id = a.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "LEFT JOIN beds b ON b.id = ba.bed_id "
                        + "LEFT JOIN wards bw ON bw.id = b.ward_id "
                        + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildWhere(String status, String wardPublicId, String query, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            params.add(status);
        }
        if (wardPublicId != null && !wardPublicId.isBlank()) {
            where.append(" AND (w.public_id = ? OR bw.public_id = ?)");
            params.add(wardPublicId);
            params.add(wardPublicId);
        }
        if (query != null && !query.isBlank()) {
            where.append(" AND (p.full_name LIKE ? OR p.medical_number LIKE ? OR a.public_id LIKE ?)");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public Map<String, Object> activeAdmissionForPatient(long patientId) {
        return Sql.one(em,
                "SELECT public_id, status FROM admissions WHERE patient_id = ? "
                        + "AND status IN ('REQUESTED', 'APPROVED', 'ADMITTED', 'TRANSFERRED', "
                        + "'DISCHARGE_PENDING') ORDER BY id DESC LIMIT 1", patientId);
    }

    public long insertAdmission(String publicId, long patientId, long encounterId, long requestedBy,
                                String reason, Long admittingProviderId, Long departmentId, Long wardId) {
        return Sql.insert(em,
                "INSERT INTO admissions (public_id, patient_id, encounter_id, requested_by, reason, "
                        + "admitting_provider_id, department_id, ward_id, status, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)",
                publicId, patientId, encounterId, requestedBy, reason,
                admittingProviderId, departmentId, wardId, requestedBy);
    }

    public int approve(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE admissions SET status = 'APPROVED', approved_by = ?, approved_at = NOW(6), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'REQUESTED'",
                actorId, actorId, id, version);
    }

    public int reject(long id, long version, long actorId, String reason) {
        return Sql.update(em,
                "UPDATE admissions SET status = 'REJECTED', rejected_by = ?, rejected_at = NOW(6), "
                        + "rejection_reason = ?, updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'REQUESTED'",
                actorId, reason, actorId, id, version);
    }

    public int markAdmitted(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE admissions SET status = 'ADMITTED', "
                        + "admitted_at = COALESCE(admitted_at, NOW(6)), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'APPROVED'",
                actorId, id, version);
    }

    public int setStatus(long id, long version, String status, long actorId) {
        return Sql.update(em,
                "UPDATE admissions SET status = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ? AND version = ?",
                status, actorId, id, version);
    }

    public int discharge(long id, long version, long actorId, String dischargeType, String notes) {
        return Sql.update(em,
                "UPDATE admissions SET status = 'DISCHARGED', discharged_at = NOW(6), "
                        + "discharge_type = ?, rejection_reason = COALESCE(?, rejection_reason), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('ADMITTED', 'TRANSFERRED', "
                        + "'DISCHARGE_PENDING')",
                dischargeType, notes, actorId, id, version);
    }

    // ------------------------------------------------------------------
    // Beds and assignments
    // ------------------------------------------------------------------

    public Map<String, Object> bedByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT b.*, w.public_id AS ward_public_id, w.name_ar AS ward_ar, w.name_en AS ward_en, "
                        + "w.gender_policy AS ward_gender_policy "
                        + "FROM beds b JOIN wards w ON w.id = b.ward_id WHERE b.public_id = ?", publicId);
    }

    public Map<String, Object> bedByIdForUpdate(long bedId) {
        return Sql.one(em,
                "SELECT b.*, w.public_id AS ward_public_id, w.gender_policy AS ward_gender_policy "
                        + "FROM beds b JOIN wards w ON w.id = b.ward_id WHERE b.id = ? FOR UPDATE", bedId);
    }

    public List<Map<String, Object>> bedsBoard() {
        return Sql.query(em,
                "SELECT b.*, w.public_id AS ward_public_id, w.code AS ward_code, "
                        + "w.name_ar AS ward_ar, w.name_en AS ward_en, w.gender_policy, "
                        + "p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number, a.public_id AS admission_public_id, "
                        + "ba.assigned_at "
                        + "FROM beds b "
                        + "JOIN wards w ON w.id = b.ward_id "
                        + "LEFT JOIN bed_assignments ba ON ba.bed_id = b.id AND ba.active_flag = 1 "
                        + "LEFT JOIN patients p ON p.id = ba.patient_id "
                        + "LEFT JOIN admissions a ON a.id = ba.admission_id "
                        + "WHERE b.active = 1 ORDER BY w.code, b.code");
    }

    public int updateBedStatus(long bedId, long version, String status, long actorId) {
        return Sql.update(em,
                "UPDATE beds SET status = ?, updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                status, actorId, bedId, version);
    }

    public Map<String, Object> activeAssignmentForAdmission(long admissionId) {
        return Sql.one(em,
                "SELECT ba.*, b.public_id AS bed_public_id, b.code AS bed_code, "
                        + "b.status AS bed_status, b.version AS bed_version, "
                        + "w.public_id AS ward_public_id "
                        + "FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id "
                        + "JOIN wards w ON w.id = b.ward_id "
                        + "WHERE ba.admission_id = ? AND ba.active_flag = 1", admissionId);
    }

    public Map<String, Object> activeAssignmentForPatient(long patientId) {
        return Sql.one(em,
                "SELECT ba.*, b.public_id AS bed_public_id, b.code AS bed_code "
                        + "FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id "
                        + "WHERE ba.patient_id = ? AND ba.active_flag = 1", patientId);
    }

    public long insertAssignment(String publicId, long admissionId, long bedId, long patientId,
                                 long actorId) {
        return Sql.insert(em,
                "INSERT INTO bed_assignments (public_id, admission_id, bed_id, patient_id, "
                        + "assigned_by, active_flag) VALUES (?, ?, ?, ?, ?, 1)",
                publicId, admissionId, bedId, patientId, actorId);
    }

    public int endAssignment(long assignmentId, long actorId, String reason) {
        return Sql.update(em,
                "UPDATE bed_assignments SET ended_at = NOW(6), ended_by = ?, end_reason = ?, "
                        + "active_flag = NULL, version = version + 1 "
                        + "WHERE id = ? AND active_flag = 1",
                actorId, reason, assignmentId);
    }

    public long insertTransfer(String publicId, long admissionId, long fromBedId, long toBedId,
                               String reason, long actorId) {
        return Sql.insert(em,
                "INSERT INTO patient_transfers (public_id, admission_id, from_bed_id, to_bed_id, "
                        + "reason, transferred_by) VALUES (?, ?, ?, ?, ?, ?)",
                publicId, admissionId, fromBedId, toBedId, reason, actorId);
    }

    // ------------------------------------------------------------------
    // Discharge summary
    // ------------------------------------------------------------------

    public Map<String, Object> dischargeSummary(long admissionId) {
        return Sql.one(em,
                "SELECT * FROM discharge_summaries WHERE admission_id = ?", admissionId);
    }

    public long upsertDischargeSummary(String publicId, long admissionId, String diagnoses,
                                       String medications, String instructions, String followUp,
                                       long actorId) {
        Map<String, Object> existing = dischargeSummary(admissionId);
        if (existing == null) {
            return Sql.insert(em,
                    "INSERT INTO discharge_summaries (public_id, admission_id, diagnoses_text, "
                            + "medications_text, instructions_text, follow_up_text, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    publicId, admissionId, diagnoses, medications, instructions, followUp, actorId);
        }
        Sql.update(em,
                "UPDATE discharge_summaries SET diagnoses_text = ?, medications_text = ?, "
                        + "instructions_text = ?, follow_up_text = ?, version = version + 1 "
                        + "WHERE admission_id = ?",
                diagnoses, medications, instructions, followUp, admissionId);
        return Sql.longValue(existing, "id", 0);
    }

    // ------------------------------------------------------------------
    // Ward view (FR-NUR-001)
    // ------------------------------------------------------------------

    public List<Map<String, Object>> wardPatients(String wardPublicId) {
        return Sql.query(em,
                "SELECT a.public_id AS admission_public_id, a.admitted_at, a.status AS admission_status, "
                        + "p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.gender, p.birth_date, p.phone, "
                        + "e.public_id AS encounter_public_id, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code, b.bed_type, "
                        + "ba.assigned_at, "
                        + "vt.temperature_c, vt.heart_rate, vt.respiratory_rate, vt.systolic, "
                        + "vt.diastolic, vt.spo2, vt.pain_score, vt.recorded_at AS vitals_at, "
                        + "ews.score AS ews_score, ews.risk_level AS ews_risk, "
                        + "(SELECT COUNT(*) FROM nursing_tasks t WHERE t.admission_id = a.id "
                        + " AND t.status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS')) AS open_tasks "
                        + "FROM admissions a "
                        + "JOIN patients p ON p.id = a.patient_id "
                        + "JOIN bed_assignments ba ON ba.admission_id = a.id AND ba.active_flag = 1 "
                        + "JOIN beds b ON b.id = ba.bed_id "
                        + "JOIN wards w ON w.id = b.ward_id "
                        + "LEFT JOIN encounters e ON e.id = a.encounter_id "
                        + "LEFT JOIN vitals vt ON vt.id = ("
                        + "  SELECT vv.id FROM vitals vv WHERE vv.encounter_id = a.encounter_id "
                        + "  ORDER BY vv.recorded_at DESC, vv.id DESC LIMIT 1) "
                        + "LEFT JOIN early_warning_scores ews ON ews.id = ("
                        + "  SELECT x.id FROM early_warning_scores x WHERE x.encounter_id = a.encounter_id "
                        + "  ORDER BY x.calculated_at DESC, x.id DESC LIMIT 1) "
                        + "WHERE w.public_id = ? AND a.status IN ('ADMITTED', 'TRANSFERRED') "
                        + "ORDER BY b.code", wardPublicId);
    }

    public long countActiveAdmissions() {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM admissions WHERE status IN ('ADMITTED', 'TRANSFERRED')");
        return Sql.longValue(row, "total", 0);
    }

    public Map<String, Object> activeAssignmentForBed(long bedId) {
        return Sql.one(em,
                "SELECT ba.*, p.public_id AS patient_public_id, p.full_name AS patient_name "
                        + "FROM bed_assignments ba JOIN patients p ON p.id = ba.patient_id "
                        + "WHERE ba.bed_id = ? AND ba.active_flag = 1", bedId);
    }

    public List<Map<String, Object>> bedHistory(long admissionId) {
        return Sql.query(em,
                "SELECT ba.public_id, ba.assigned_at, ba.ended_at, ba.end_reason, "
                        + "b.public_id AS bed_public_id, b.code AS bed_code, b.bed_type, "
                        + "w.name_ar AS ward_ar, w.name_en AS ward_en "
                        + "FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id "
                        + "JOIN wards w ON w.id = b.ward_id "
                        + "WHERE ba.admission_id = ? ORDER BY ba.assigned_at DESC, ba.id DESC",
                admissionId);
    }

    public List<Map<String, Object>> encounterDiagnoses(long encounterId) {
        return Sql.query(em,
                "SELECT public_id, code, description, diagnosis_type, created_at "
                        + "FROM diagnoses WHERE encounter_id = ? "
                        + "ORDER BY primary_flag DESC, id", encounterId);
    }

    public List<Map<String, Object>> encounterMedications(long encounterId) {
        return Sql.query(em,
                "SELECT pi.public_id, d.code AS drug_code, d.name_ar AS drug_ar, d.name_en AS drug_en, "
                        + "pi.dose, pi.route, pi.frequency, pi.duration_days, pi.quantity, "
                        + "pi.dispensed_quantity, pi.instructions "
                        + "FROM prescription_items pi "
                        + "JOIN prescriptions pr ON pr.id = pi.prescription_id "
                        + "JOIN drugs d ON d.id = pi.drug_id "
                        + "WHERE pr.encounter_id = ? AND pr.status IN "
                        + "('ISSUED', 'REVIEWED', 'DISPENSED', 'PARTIAL') "
                        + "ORDER BY pi.id", encounterId);
    }

    public String latestNoteText(long encounterId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT v.content_text FROM clinical_note_versions v "
                        + "JOIN clinical_notes n ON n.id = v.note_id "
                        + "WHERE n.encounter_id = ? AND n.status IN ('SIGNED', 'AMENDED') "
                        + "ORDER BY v.signed_at DESC, v.id DESC LIMIT 1", encounterId);
        return Sql.text(row, "contentText");
    }
}
