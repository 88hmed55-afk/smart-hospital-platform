package com.smarthospital.clinical.er;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for emergency visits and triage (FR-ER). */
@Stateless
public class ErRepository {

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

    public List<Map<String, Object>> triageLevels() {
        return Sql.query(em,
                "SELECT * FROM triage_levels WHERE active = 1 ORDER BY rank_no");
    }

    public Map<String, Object> triageLevelByCode(String code) {
        return Sql.one(em,
                "SELECT * FROM triage_levels WHERE code = ? AND active = 1", code);
    }

    public Map<String, Object> triageLevelById(long id) {
        return Sql.one(em, "SELECT * FROM triage_levels WHERE id = ?", id);
    }

    public Map<String, Object> openEncounterForPatient(long patientId) {
        return Sql.one(em,
                "SELECT id, public_id, status FROM encounters "
                        + "WHERE patient_id = ? AND status = 'OPEN' ORDER BY id DESC LIMIT 1",
                patientId);
    }

    public Map<String, Object> departmentByCode(String code) {
        return Sql.one(em, "SELECT id, public_id FROM departments WHERE code = ?", code);
    }

    // ------------------------------------------------------------------
    // Visits
    // ------------------------------------------------------------------

    public Map<String, Object> visitByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT v.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "t.code AS triage_code, t.name_ar AS triage_name_ar, "
                        + "t.name_en AS triage_name_en, t.color AS triage_color, "
                        + "t.rank_no AS triage_rank, t.max_wait_minutes AS triage_max_wait, "
                        + "e.public_id AS encounter_public_id, e.status AS encounter_status "
                        + "FROM emergency_visits v "
                        + "JOIN patients p ON p.id = v.patient_id "
                        + "LEFT JOIN triage_levels t ON t.id = v.triage_level_id "
                        + "LEFT JOIN encounters e ON e.id = v.encounter_id "
                        + "WHERE v.public_id = ?", publicId);
    }

    public Map<String, Object> activeVisitForPatient(long patientId) {
        return Sql.one(em,
                "SELECT public_id, status FROM emergency_visits "
                        + "WHERE patient_id = ? AND status IN ('ARRIVED', 'TRIAGED', 'IN_TREATMENT') "
                        + "ORDER BY id DESC LIMIT 1", patientId);
    }

    public long insertVisit(String publicId, long patientId, Long encounterId, Long triageLevelId,
                            String arrivalMode, String chiefComplaint, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO emergency_visits (public_id, patient_id, encounter_id, triage_level_id, "
                        + "status, arrival_mode, chief_complaint, created_by) "
                        + "VALUES (?, ?, ?, ?, 'ARRIVED', ?, ?, ?)",
                publicId, patientId, encounterId, triageLevelId, arrivalMode, chiefComplaint, actorId);
    }

    public int triage(long id, long version, long triageLevelId, String chiefComplaint, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET triage_level_id = ?, chief_complaint = COALESCE(?, chief_complaint), "
                        + "status = 'TRIAGED', triage_at = NOW(6), triaged_by = ?, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'ARRIVED'",
                triageLevelId, chiefComplaint, actorId, actorId, id, version);
    }

    public int startTreatment(long id, long version, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET status = 'IN_TREATMENT', "
                        + "first_assessment_at = COALESCE(first_assessment_at, NOW(6)), "
                        + "assessed_by = COALESCE(assessed_by, ?), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('ARRIVED', 'TRIAGED')",
                actorId, actorId, id, version);
    }

    public int dispose(long id, long version, String disposition, String notes, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET status = 'DISPOSED', disposition = ?, disposition_notes = ?, "
                        + "disposition_at = NOW(6), disposition_by = ?, "
                        + "first_assessment_at = COALESCE(first_assessment_at, NOW(6)), "
                        + "assessed_by = COALESCE(assessed_by, ?), "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('ARRIVED', 'TRIAGED', 'IN_TREATMENT')",
                disposition, notes, actorId, actorId, actorId, id, version);
    }

    public int markLwbs(long id, long version, String reason, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET status = 'LWBS', disposition = 'LWBS', "
                        + "lwbs_at = NOW(6), lwbs_reason = ?, disposition_at = NOW(6), "
                        + "disposition_by = ?, updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('ARRIVED', 'TRIAGED')",
                reason, actorId, actorId, id, version);
    }

    public int markEnteredInError(long id, long version, String reason, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET status = 'ENTERED_IN_ERROR', disposition = 'ENTERED_IN_ERROR', "
                        + "entered_in_error_reason = ?, disposition_at = NOW(6), disposition_by = ?, "
                        + "updated_at = NOW(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                reason, actorId, actorId, id, version);
    }

    public int linkEncounter(long id, long encounterId, long actorId) {
        return Sql.update(em,
                "UPDATE emergency_visits SET encounter_id = ?, updated_at = NOW(6), updated_by = ?, "
                        + "version = version + 1 WHERE id = ?",
                encounterId, actorId, id);
    }

    // ------------------------------------------------------------------
    // Board
    // ------------------------------------------------------------------

    public List<Map<String, Object>> board(String statuses) {
        return Sql.query(em,
                "SELECT v.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, p.gender AS patient_gender, "
                        + "p.birth_date AS patient_birth_date, "
                        + "t.code AS triage_code, t.name_ar AS triage_name_ar, "
                        + "t.name_en AS triage_name_en, t.color AS triage_color, "
                        + "t.rank_no AS triage_rank, t.max_wait_minutes AS triage_max_wait, "
                        + "e.public_id AS encounter_public_id, e.status AS encounter_status, "
                        + "vt.temperature_c, vt.heart_rate, vt.respiratory_rate, "
                        + "vt.systolic, vt.diastolic, vt.spo2, vt.pain_score, vt.recorded_at AS vitals_at "
                        + "FROM emergency_visits v "
                        + "JOIN patients p ON p.id = v.patient_id "
                        + "LEFT JOIN triage_levels t ON t.id = v.triage_level_id "
                        + "LEFT JOIN encounters e ON e.id = v.encounter_id "
                        + "LEFT JOIN vitals vt ON vt.id = ("
                        + "  SELECT vv.id FROM vitals vv WHERE vv.encounter_id = v.encounter_id "
                        + "  ORDER BY vv.recorded_at DESC, vv.id DESC LIMIT 1) "
                        + "WHERE v.status IN (" + statuses + ") "
                        + "ORDER BY COALESCE(t.rank_no, 99), v.arrival_at");
    }

    public List<Map<String, Object>> recentDispositions(int limit) {
        return Sql.query(em,
                "SELECT v.*, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name AS patient_name, t.code AS triage_code, "
                        + "t.name_ar AS triage_name_ar, t.name_en AS triage_name_en, t.color AS triage_color "
                        + "FROM emergency_visits v "
                        + "JOIN patients p ON p.id = v.patient_id "
                        + "LEFT JOIN triage_levels t ON t.id = v.triage_level_id "
                        + "WHERE v.status IN ('DISPOSED', 'LWBS', 'ENTERED_IN_ERROR') "
                        + "ORDER BY v.disposition_at DESC, v.id DESC LIMIT ?", limit);
    }

    public List<Map<String, Object>> visitsForPatient(long patientId) {
        return Sql.query(em,
                "SELECT v.*, t.code AS triage_code, t.name_ar AS triage_name_ar, "
                        + "t.name_en AS triage_name_en, t.color AS triage_color "
                        + "FROM emergency_visits v "
                        + "LEFT JOIN triage_levels t ON t.id = v.triage_level_id "
                        + "WHERE v.patient_id = ? ORDER BY v.arrival_at DESC LIMIT 20", patientId);
    }
}
