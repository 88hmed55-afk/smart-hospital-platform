package com.smarthospital.clinical.patient;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for the patient/EMPI tables. */
@Stateless
public class PatientRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public Map<String, Object> findByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT p.*, m.public_id AS merged_into_public_id, m.medical_number AS merged_into_medical_number "
                        + "FROM patients p LEFT JOIN patients m ON m.id = p.merged_into_id "
                        + "WHERE p.public_id = ?", publicId);
    }

    public Map<String, Object> findByMedicalNumber(String medicalNumber) {
        return Sql.one(em, "SELECT * FROM patients WHERE medical_number = ?", medicalNumber);
    }

    public Map<String, Object> findActiveByNationalId(String nationalId) {
        return Sql.one(em,
                "SELECT * FROM patients WHERE national_id = ? AND status = 'ACTIVE'", nationalId);
    }

    public Map<String, Object> findByPhone(String phone) {
        return Sql.one(em,
                "SELECT * FROM patients WHERE phone = ? AND status = 'ACTIVE' LIMIT 1", phone);
    }

    public List<Map<String, Object>> findPossibleMatches(String normalizedName, LocalDate birthDate) {
        return Sql.query(em,
                "SELECT p.id, p.public_id, p.medical_number, p.full_name, p.gender, p.birth_date, "
                        + "p.phone, p.national_id, p.status FROM patients p "
                        + "WHERE p.status = 'ACTIVE' AND p.name_normalized = ? "
                        + "AND (? IS NULL OR p.birth_date = ?) LIMIT 10",
                normalizedName, birthDate, birthDate);
    }

    public List<Map<String, Object>> search(String q, String status, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(q, status, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT p.id, p.public_id, p.medical_number, p.full_name, p.gender, p.birth_date, "
                        + "p.phone, p.national_id, p.status, p.created_at, "
                        + "(SELECT COUNT(*) FROM patient_allergies a WHERE a.patient_id = p.id AND a.active = 1) "
                        + "AS allergy_count "
                        + "FROM patients p " + where
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                params.toArray());
    }

    public long count(String q, String status) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(q, status, params);
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM patients p " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String buildWhere(String q, String status, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (status != null && !status.isBlank()) {
            where.append(" AND p.status = ? ");
            params.add(status);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            where.append(" AND (p.medical_number LIKE ? OR p.national_id LIKE ? "
                    + "OR p.phone LIKE ? OR p.full_name LIKE ? OR p.name_normalized LIKE ?) ");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public List<Map<String, Object>> identifiers(long patientId) {
        return Sql.query(em,
                "SELECT public_id, type, value, is_primary FROM patient_identifiers "
                        + "WHERE patient_id = ? ORDER BY is_primary DESC, id", patientId);
    }

    public List<Map<String, Object>> contacts(long patientId) {
        return Sql.query(em,
                "SELECT public_id, contact_type, full_name, relation, phone, is_primary "
                        + "FROM patient_contacts WHERE patient_id = ? ORDER BY is_primary DESC, id", patientId);
    }

    public List<Map<String, Object>> allergies(long patientId, boolean activeOnly) {
        String filter = activeOnly ? " AND active = 1 " : "";
        return Sql.query(em,
                "SELECT public_id, substance, allergy_type, severity, reaction, active, recorded_at "
                        + "FROM patient_allergies WHERE patient_id = ?" + filter
                        + " ORDER BY FIELD(severity, 'LIFE_THREATENING', 'SEVERE', 'MODERATE', 'MILD'), id",
                patientId);
    }

    public Map<String, Object> allergyByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM patient_allergies WHERE public_id = ?", publicId);
    }

    public List<Map<String, Object>> conditions(long patientId) {
        return Sql.query(em,
                "SELECT public_id, description, onset_date, status FROM patient_conditions "
                        + "WHERE patient_id = ? ORDER BY status, id", patientId);
    }

    public List<Map<String, Object>> flags(long patientId) {
        return Sql.query(em,
                "SELECT public_id, flag_type, note FROM patient_flags WHERE patient_id = ? AND active = 1",
                patientId);
    }

    public long countEncounters(long patientId) {
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM encounters WHERE patient_id = ?", patientId), "total", 0);
    }

    public long countAppointments(long patientId) {
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM appointments WHERE patient_id = ?", patientId), "total", 0);
    }

    public List<Map<String, Object>> appointmentsForTimeline(long patientId) {
        return Sql.query(em,
                "SELECT a.public_id, a.slot_start AS at, a.status, c.name_ar AS clinic_ar, "
                        + "c.name_en AS clinic_en, u.display_name_ar AS provider_ar, u.display_name_en AS provider_en "
                        + "FROM appointments a JOIN clinics c ON c.id = a.clinic_id "
                        + "JOIN users u ON u.id = a.provider_id "
                        + "WHERE a.patient_id = ? ORDER BY a.slot_start DESC LIMIT 50", patientId);
    }

    public List<Map<String, Object>> encountersForTimeline(long patientId) {
        return Sql.query(em,
                "SELECT e.public_id, e.started_at AS at, e.status, e.type, e.chief_complaint, "
                        + "u.display_name_ar AS provider_ar, u.display_name_en AS provider_en "
                        + "FROM encounters e JOIN users u ON u.id = e.provider_id "
                        + "WHERE e.patient_id = ? ORDER BY e.started_at DESC LIMIT 50", patientId);
    }

    public List<Map<String, Object>> prescriptionsForTimeline(long patientId) {
        return Sql.query(em,
                "SELECT pr.public_id, COALESCE(pr.issued_at, pr.created_at) AS at, pr.status, "
                        + "(SELECT COUNT(*) FROM prescription_items i WHERE i.prescription_id = pr.id) AS item_count "
                        + "FROM prescriptions pr WHERE pr.patient_id = ? "
                        + "ORDER BY COALESCE(pr.issued_at, pr.created_at) DESC LIMIT 50", patientId);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    public long insertPatient(String publicId, String medicalNumber, String fullName, String normalized,
                              String gender, LocalDate birthDate, boolean birthDateEstimated,
                              String nationalId, String phone, String email, String address,
                              String bloodGroup, String maritalStatus, String notes, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO patients (public_id, medical_number, full_name, name_normalized, gender, "
                        + "birth_date, birth_date_estimated, national_id, phone, email, address, blood_group, "
                        + "marital_status, status, notes, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                publicId, medicalNumber, fullName, normalized, gender, birthDate,
                birthDateEstimated ? 1 : 0, nationalId, phone, email, address, bloodGroup,
                maritalStatus, notes, actorId);
    }

    public int updatePatient(long id, long expectedVersion, String fullName, String normalized,
                             String gender, LocalDate birthDate, boolean birthDateEstimated,
                             String nationalId, String phone, String email, String address,
                             String bloodGroup, String maritalStatus, String notes, Long actorId) {
        return Sql.update(em,
                "UPDATE patients SET full_name = ?, name_normalized = ?, gender = ?, birth_date = ?, "
                        + "birth_date_estimated = ?, national_id = ?, phone = ?, email = ?, address = ?, "
                        + "blood_group = ?, marital_status = ?, notes = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                fullName, normalized, gender, birthDate, birthDateEstimated ? 1 : 0, nationalId,
                phone, email, address, bloodGroup, maritalStatus, notes, actorId, id, expectedVersion);
    }

    public void insertIdentifier(long patientId, String type, String value, boolean primary, Long actorId) {
        Sql.insert(em,
                "INSERT INTO patient_identifiers (public_id, patient_id, type, value, is_primary, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                com.smarthospital.shared.id.Ulid.next(), patientId, type, value, primary ? 1 : 0, actorId);
    }

    public void insertContact(long patientId, String type, String fullName, String relation,
                              String phone, boolean primary, Long actorId) {
        Sql.insert(em,
                "INSERT INTO patient_contacts (public_id, patient_id, contact_type, full_name, relation, "
                        + "phone, is_primary, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                com.smarthospital.shared.id.Ulid.next(), patientId, type, fullName, relation, phone,
                primary ? 1 : 0, actorId);
    }

    public void insertCondition(long patientId, String description, LocalDate onsetDate, Long actorId) {
        Sql.insert(em,
                "INSERT INTO patient_conditions (public_id, patient_id, description, onset_date, status, created_by) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                com.smarthospital.shared.id.Ulid.next(), patientId, description, onsetDate, actorId);
    }

    public long insertAllergy(long patientId, String substance, String type, String severity,
                              String reaction, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO patient_allergies (public_id, patient_id, substance, allergy_type, severity, "
                        + "reaction, active, recorded_by) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                com.smarthospital.shared.id.Ulid.next(), patientId, substance, type, severity, reaction, actorId);
    }

    public int deactivateAllergy(long patientId, String allergyPublicId, Long actorId) {
        return Sql.update(em,
                "UPDATE patient_allergies SET active = 0, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE patient_id = ? AND public_id = ? AND active = 1",
                actorId, patientId, allergyPublicId);
    }

    public int markMerged(long sourceId, long targetId, Long actorId) {
        return Sql.update(em,
                "UPDATE patients SET status = 'MERGED', merged_into_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'ACTIVE'",
                targetId, actorId, sourceId);
    }

    public void insertMerge(String publicId, long sourceId, long targetId, String reason, Long actorId) {
        Sql.insert(em,
                "INSERT INTO patient_merges (public_id, source_patient_id, target_patient_id, reason, merged_by) "
                        + "VALUES (?, ?, ?, ?, ?)",
                publicId, sourceId, targetId, reason, actorId);
    }

    public int nextMedicalNumber(String year) {
        return Sql.nextSequence(em, "MEDICAL_NUMBER", year);
    }
}
