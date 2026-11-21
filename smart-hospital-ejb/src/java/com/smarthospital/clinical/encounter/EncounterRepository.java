package com.smarthospital.clinical.encounter;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for encounters, notes, diagnoses, orders and prescriptions. */
@Stateless
public class EncounterRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    public Map<String, Object> encounterByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT e.*, p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.gender AS patient_gender, p.birth_date AS patient_birth_date, p.phone AS patient_phone, "
                        + "p.blood_group AS patient_blood_group, u.public_id AS provider_public_id, "
                        + "u.display_name_ar AS provider_ar, u.display_name_en AS provider_en, "
                        + "d.name_ar AS department_ar, d.name_en AS department_en, "
                        + "a.public_id AS appointment_public_id "
                        + "FROM encounters e JOIN patients p ON p.id = e.patient_id "
                        + "JOIN users u ON u.id = e.provider_id "
                        + "LEFT JOIN departments d ON d.id = e.department_id "
                        + "LEFT JOIN appointments a ON a.id = e.appointment_id "
                        + "WHERE e.public_id = ?", publicId);
    }

    public Map<String, Object> openEncounterForPatient(long patientId) {
        return Sql.one(em,
                "SELECT public_id FROM encounters WHERE patient_id = ? AND status = 'OPEN' "
                        + "ORDER BY started_at DESC LIMIT 1", patientId);
    }

    public Map<String, Object> encounterForAppointment(long appointmentId) {
        return Sql.one(em,
                "SELECT public_id FROM encounters WHERE appointment_id = ? "
                        + "ORDER BY started_at DESC LIMIT 1", appointmentId);
    }

    public List<Map<String, Object>> search(String q, String status, Long providerId, Long patientId,
                                            LocalDate date, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = where(q, status, providerId, patientId, date, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT e.public_id, e.started_at, e.status, e.type, e.chief_complaint, "
                        + "p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.gender AS patient_gender, p.birth_date AS patient_birth_date, "
                        + "u.public_id AS provider_public_id, u.display_name_ar AS provider_ar, "
                        + "u.display_name_en AS provider_en, "
                        + "(SELECT COUNT(*) FROM clinical_notes n WHERE n.encounter_id = e.id) AS note_count, "
                        + "(SELECT COUNT(*) FROM diagnoses dg WHERE dg.encounter_id = e.id) AS diagnosis_count "
                        + "FROM encounters e JOIN patients p ON p.id = e.patient_id "
                        + "JOIN users u ON u.id = e.provider_id " + where
                        + " ORDER BY e.started_at DESC, e.id DESC LIMIT ? OFFSET ?", params.toArray());
    }

    public long count(String q, String status, Long providerId, Long patientId, LocalDate date) {
        List<Object> params = new ArrayList<>();
        String where = where(q, status, providerId, patientId, date, params);
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM encounters e JOIN patients p ON p.id = e.patient_id "
                        + where, params.toArray()), "total", 0);
    }

    private String where(String q, String status, Long providerId, Long patientId, LocalDate date,
                         List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (status != null && !status.isBlank()) {
            where.append(" AND e.status = ? ");
            params.add(status);
        }
        if (providerId != null) {
            where.append(" AND e.provider_id = ? ");
            params.add(providerId);
        }
        if (patientId != null) {
            where.append(" AND e.patient_id = ? ");
            params.add(patientId);
        }
        if (date != null) {
            where.append(" AND e.started_at >= ? AND e.started_at < ? ");
            params.add(date.atStartOfDay());
            params.add(date.plusDays(1).atStartOfDay());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            where.append(" AND (p.full_name LIKE ? OR p.medical_number LIKE ? OR e.chief_complaint LIKE ?) ");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    public long insertEncounter(String publicId, long patientId, Long appointmentId, long providerId,
                                Long departmentId, String type, String chiefComplaint, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO encounters (public_id, patient_id, appointment_id, provider_id, department_id, "
                        + "type, status, chief_complaint, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)",
                publicId, patientId, appointmentId, providerId, departmentId, type, chiefComplaint, actorId);
    }

    public int updateEncounter(long id, long version, String chiefComplaint, String type,
                               Long departmentId, Long actorId) {
        return Sql.update(em,
                "UPDATE encounters SET chief_complaint = ?, type = ?, department_id = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                chiefComplaint, type, departmentId, actorId, id, version);
    }

    public int closeEncounter(long id, long version, Long actorId) {
        return Sql.update(em,
                "UPDATE encounters SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP(6), closed_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                actorId, actorId, id, version);
    }

    public int cancelEncounter(long id, long version, Long actorId) {
        return Sql.update(em,
                "UPDATE encounters SET status = 'CANCELLED', closed_at = CURRENT_TIMESTAMP(6), closed_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                actorId, actorId, id, version);
    }

    public int startAppointment(Long appointmentId, Long actorId) {
        if (appointmentId == null) {
            return 0;
        }
        return Sql.update(em,
                "UPDATE appointments SET status = 'CHECKED_IN', "
                        + "checked_in_at = COALESCE(checked_in_at, CURRENT_TIMESTAMP(6)), "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'BOOKED'",
                actorId, appointmentId);
    }

    public int completeAppointmentForEncounter(Long appointmentId, Long actorId) {
        if (appointmentId == null) {
            return 0;
        }
        return Sql.update(em,
                "UPDATE appointments SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND status IN ('BOOKED', 'CHECKED_IN')",
                actorId, appointmentId);
    }

    // ------------------------------------------------------------------
    // Vitals
    // ------------------------------------------------------------------

    public List<Map<String, Object>> vitals(long encounterId) {
        return Sql.query(em,
                "SELECT v.public_id, v.temperature_c, v.heart_rate, v.respiratory_rate, v.systolic, "
                        + "v.diastolic, v.spo2, v.weight_kg, v.height_cm, v.pain_score, v.recorded_at, "
                        + "u.display_name_ar AS recorded_by_ar, u.display_name_en AS recorded_by_en "
                        + "FROM vitals v JOIN users u ON u.id = v.recorded_by "
                        + "WHERE v.encounter_id = ? ORDER BY v.recorded_at DESC, v.id DESC", encounterId);
    }

    public Map<String, Object> latestVitals(long encounterId) {
        return Sql.one(em,
                "SELECT public_id, temperature_c, heart_rate, respiratory_rate, systolic, diastolic, "
                        + "spo2, weight_kg, height_cm, pain_score, recorded_at FROM vitals "
                        + "WHERE encounter_id = ? ORDER BY recorded_at DESC, id DESC LIMIT 1", encounterId);
    }

    public void insertVitals(String publicId, long encounterId, long patientId, BigDecimal temperature,
                             Integer heartRate, Integer respiratoryRate, Integer systolic, Integer diastolic,
                             Integer spo2, BigDecimal weight, BigDecimal height, Integer painScore,
                             Long actorId) {
        Sql.insert(em,
                "INSERT INTO vitals (public_id, encounter_id, patient_id, temperature_c, heart_rate, "
                        + "respiratory_rate, systolic, diastolic, spo2, weight_kg, height_cm, pain_score, "
                        + "recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, encounterId, patientId, temperature, heartRate, respiratoryRate, systolic,
                diastolic, spo2, weight, height, painScore, actorId);
    }

    // ------------------------------------------------------------------
    // Diagnoses
    // ------------------------------------------------------------------

    public List<Map<String, Object>> diagnoses(long encounterId) {
        return Sql.query(em,
                "SELECT public_id, code, description, diagnosis_type, primary_flag, created_at "
                        + "FROM diagnoses WHERE encounter_id = ? "
                        + "ORDER BY primary_flag DESC, id", encounterId);
    }

    public Map<String, Object> diagnosisByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM diagnoses WHERE public_id = ?", publicId);
    }

    public void clearPrimary(long encounterId) {
        Sql.update(em,
                "UPDATE diagnoses SET primary_flag = NULL, diagnosis_type = 'SECONDARY' "
                        + "WHERE encounter_id = ? AND primary_flag = 1", encounterId);
    }

    public void insertDiagnosis(String publicId, long encounterId, String code, String description,
                                String type, boolean primary, Long actorId) {
        Sql.insert(em,
                "INSERT INTO diagnoses (public_id, encounter_id, code, description, diagnosis_type, "
                        + "diagnosed_by, primary_flag) VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicId, encounterId, code, description, type, actorId, primary ? 1 : null);
    }

    public int deleteDiagnosis(long encounterId, String publicId) {
        return Sql.update(em,
                "DELETE FROM diagnoses WHERE encounter_id = ? AND public_id = ?", encounterId, publicId);
    }

    // ------------------------------------------------------------------
    // Clinical notes (immutable after signing)
    // ------------------------------------------------------------------

    public List<Map<String, Object>> notes(long encounterId) {
        return Sql.query(em,
                "SELECT n.public_id, n.note_type, n.status, n.created_at, n.updated_at, "
                        + "u.display_name_ar AS author_ar, u.display_name_en AS author_en, "
                        + "v.version_no, v.content_json, v.content_text, v.signed_at, "
                        + "su.display_name_ar AS signed_by_ar, su.display_name_en AS signed_by_en "
                        + "FROM clinical_notes n JOIN users u ON u.id = n.author_id "
                        + "LEFT JOIN clinical_note_versions v ON v.id = n.current_version_id "
                        + "LEFT JOIN users su ON su.id = v.signed_by "
                        + "WHERE n.encounter_id = ? ORDER BY n.created_at DESC, n.id DESC", encounterId);
    }

    public Map<String, Object> noteByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT n.*, v.version_no, v.content_json, v.content_text, v.signed_at "
                        + "FROM clinical_notes n LEFT JOIN clinical_note_versions v ON v.id = n.current_version_id "
                        + "WHERE n.public_id = ?", publicId);
    }

    public List<Map<String, Object>> noteVersions(long noteId) {
        return Sql.query(em,
                "SELECT public_id, version_no, content_json, content_text, amend_reason, signed_at, created_at "
                        + "FROM clinical_note_versions WHERE note_id = ? ORDER BY version_no", noteId);
    }

    public long insertNote(String publicId, long encounterId, String noteType, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO clinical_notes (public_id, encounter_id, note_type, author_id, status) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT')",
                publicId, encounterId, noteType, actorId);
    }

    public long insertNoteVersion(String publicId, long noteId, int versionNo, String contentJson,
                                  String contentText, Long amendsId, String amendReason,
                                  Long signedBy, LocalDateTime signedAt, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO clinical_note_versions (public_id, note_id, version_no, content_json, "
                        + "content_text, amends_id, amend_reason, signed_by, signed_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, noteId, versionNo, contentJson, contentText, amendsId, amendReason,
                signedBy, signedAt, actorId);
    }

    public int updateNoteDraft(long noteId, long version, long versionId, Long actorId) {
        return Sql.update(em,
                "UPDATE clinical_notes SET current_version_id = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'DRAFT'",
                versionId, actorId, noteId, version);
    }

    public int signNoteVersion(long versionId, Long actorId) {
        return Sql.update(em,
                "UPDATE clinical_note_versions SET signed_by = ?, signed_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE id = ? AND signed_at IS NULL",
                actorId, versionId);
    }

    public int signNote(long noteId, long version, long versionId, Long actorId) {
        return Sql.update(em,
                "UPDATE clinical_notes SET current_version_id = ?, status = 'SIGNED', "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'DRAFT'",
                versionId, actorId, noteId, version);
    }

    public int amendNote(long noteId, long version, long versionId, Long actorId) {
        return Sql.update(em,
                "UPDATE clinical_notes SET current_version_id = ?, status = 'AMENDED', "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('SIGNED', 'AMENDED')",
                versionId, actorId, noteId, version);
    }

    public int maxNoteVersion(long noteId) {
        return Sql.intValue(Sql.one(em,
                "SELECT COALESCE(MAX(version_no), 0) AS max_no FROM clinical_note_versions WHERE note_id = ?",
                noteId), "maxNo", 0);
    }

    /**
     * Serialises changes to one note by taking the parent row lock before any
     * version row is inserted. Two concurrent amendments then queue here and
     * the loser fails the optimistic version check with a clean 409 instead of
     * deadlocking on the version index.
     */
    public Map<String, Object> lockNote(long noteId) {
        return Sql.one(em,
                "SELECT id, version, status, current_version_id FROM clinical_notes "
                        + "WHERE id = ? FOR UPDATE", noteId);
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    public List<Map<String, Object>> orders(long encounterId) {
        return Sql.query(em,
                "SELECT o.public_id, o.order_type, o.status, o.priority, o.notes, o.created_at, "
                        + "u.display_name_ar AS ordered_by_ar, u.display_name_en AS ordered_by_en, "
                        + "(SELECT GROUP_CONCAT(s.name_ar ORDER BY i.id SEPARATOR '، ') "
                        + " FROM order_items i JOIN service_catalog s ON s.id = i.service_id "
                        + " WHERE i.order_id = o.id) AS items_ar, "
                        + "(SELECT GROUP_CONCAT(s.name_en ORDER BY i.id SEPARATOR ', ') "
                        + " FROM order_items i JOIN service_catalog s ON s.id = i.service_id "
                        + " WHERE i.order_id = o.id) AS items_en "
                        + "FROM service_orders o JOIN users u ON u.id = o.ordered_by "
                        + "WHERE o.encounter_id = ? ORDER BY o.created_at DESC, o.id DESC", encounterId);
    }

    public Map<String, Object> orderByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM service_orders WHERE public_id = ?", publicId);
    }

    public long insertOrder(String publicId, long encounterId, String type, String priority,
                            String notes, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO service_orders (public_id, encounter_id, order_type, ordered_by, status, "
                        + "priority, notes) VALUES (?, ?, ?, ?, 'ORDERED', ?, ?)",
                publicId, encounterId, type, actorId, priority, notes);
    }

    public void insertOrderItem(String publicId, long orderId, long serviceId, int quantity) {
        Sql.insert(em,
                "INSERT INTO order_items (public_id, order_id, service_id, quantity, status) "
                        + "VALUES (?, ?, ?, ?, 'ORDERED')",
                publicId, orderId, serviceId, quantity);
    }

    public int changeOrderStatus(long id, long version, String status, Long actorId) {
        return Sql.update(em,
                "UPDATE service_orders SET status = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                status, actorId, id, version);
    }

    // ------------------------------------------------------------------
    // Prescriptions
    // ------------------------------------------------------------------

    public List<Map<String, Object>> prescriptions(long encounterId) {
        return Sql.query(em,
                "SELECT pr.public_id, pr.status, pr.issued_at, pr.notes, pr.created_at, "
                        + "u.display_name_ar AS prescribed_by_ar, u.display_name_en AS prescribed_by_en, "
                        + "(SELECT COUNT(*) FROM prescription_items i WHERE i.prescription_id = pr.id) AS item_count "
                        + "FROM prescriptions pr JOIN users u ON u.id = pr.prescribed_by "
                        + "WHERE pr.encounter_id = ? ORDER BY pr.created_at DESC, pr.id DESC", encounterId);
    }

    public Map<String, Object> prescriptionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT pr.*, p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.birth_date AS patient_birth_date, p.gender AS patient_gender, "
                        + "u.public_id AS prescribed_by_public_id, u.display_name_ar AS prescribed_by_ar, "
                        + "u.display_name_en AS prescribed_by_en, e.public_id AS encounter_public_id "
                        + "FROM prescriptions pr JOIN patients p ON p.id = pr.patient_id "
                        + "JOIN users u ON u.id = pr.prescribed_by "
                        + "JOIN encounters e ON e.id = pr.encounter_id "
                        + "WHERE pr.public_id = ?", publicId);
    }

    public List<Map<String, Object>> prescriptionItems(long prescriptionId) {
        return Sql.query(em,
                "SELECT i.public_id, d.public_id AS drug_public_id, d.code AS drug_code, "
                        + "d.name_ar AS drug_ar, d.name_en AS drug_en, d.form, d.strength, "
                        + "i.dose, i.route, i.frequency, i.duration_days, i.quantity, i.instructions "
                        + "FROM prescription_items i JOIN drugs d ON d.id = i.drug_id "
                        + "WHERE i.prescription_id = ? ORDER BY i.id", prescriptionId);
    }

    public List<Map<String, Object>> patientPrescriptions(long patientId, int limit) {
        return Sql.query(em,
                "SELECT pr.public_id, pr.status, COALESCE(pr.issued_at, pr.created_at) AS at, "
                        + "u.display_name_ar AS prescribed_by_ar, u.display_name_en AS prescribed_by_en, "
                        + "(SELECT COUNT(*) FROM prescription_items i WHERE i.prescription_id = pr.id) AS item_count "
                        + "FROM prescriptions pr JOIN users u ON u.id = pr.prescribed_by "
                        + "WHERE pr.patient_id = ? AND pr.status IN ('ISSUED', 'REVIEWED', 'DISPENSED', 'PARTIAL') "
                        + "ORDER BY COALESCE(pr.issued_at, pr.created_at) DESC LIMIT ?", patientId, limit);
    }

    public long insertPrescription(String publicId, long encounterId, long patientId,
                                   String notes, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO prescriptions (public_id, encounter_id, patient_id, prescribed_by, status, notes) "
                        + "VALUES (?, ?, ?, ?, 'DRAFT', ?)",
                publicId, encounterId, patientId, actorId, notes);
    }

    public void insertPrescriptionItem(String publicId, long prescriptionId, long drugId, String dose,
                                       String route, String frequency, Integer durationDays,
                                       int quantity, String instructions) {
        Sql.insert(em,
                "INSERT INTO prescription_items (public_id, prescription_id, drug_id, dose, route, "
                        + "frequency, duration_days, quantity, instructions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, prescriptionId, drugId, dose, route, frequency, durationDays, quantity, instructions);
    }

    public int issuePrescription(long id, long version, Long actorId) {
        return Sql.update(em,
                "UPDATE prescriptions SET status = 'ISSUED', issued_at = CURRENT_TIMESTAMP(6), "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'DRAFT'",
                actorId, id, version);
    }

    public int cancelPrescription(long id, long version, Long actorId) {
        return Sql.update(em,
                "UPDATE prescriptions SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('DRAFT', 'ISSUED')",
                actorId, id, version);
    }

    // ------------------------------------------------------------------
    // Charge capture (ADR-016)
    // ------------------------------------------------------------------

    public List<Map<String, Object>> charges(long encounterId) {
        return Sql.query(em,
                "SELECT se.public_id, se.performed_at, se.price_snapshot, se.status, se.source_type, "
                        + "s.code AS service_code, s.name_ar AS service_ar, s.name_en AS service_en, "
                        + "u.display_name_ar AS performed_by_ar, u.display_name_en AS performed_by_en "
                        + "FROM service_executions se JOIN service_catalog s ON s.id = se.service_id "
                        + "JOIN users u ON u.id = se.performed_by "
                        + "WHERE se.encounter_id = ? ORDER BY se.performed_at DESC, se.id DESC", encounterId);
    }

    public Map<String, Object> serviceByCode(String code) {
        return Sql.one(em, "SELECT * FROM service_catalog WHERE code = ? AND active = 1", code);
    }

    public Map<String, Object> serviceByPublicId(String publicId) {
        return Sql.one(em, "SELECT id, code FROM service_catalog WHERE public_id = ? AND active = 1",
                publicId);
    }

    public Map<String, Object> drugByPublicId(String publicId) {
        return Sql.one(em, "SELECT id FROM drugs WHERE public_id = ? AND active = 1", publicId);
    }

    public Map<String, Object> userIdByPublicId(String publicId) {
        return Sql.one(em, "SELECT id FROM users WHERE public_id = ? AND status = 'ACTIVE'", publicId);
    }

    public long unsignedNoteCount(long encounterId) {
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM clinical_notes WHERE encounter_id = ? AND status = 'DRAFT'",
                encounterId), "total", 0);
    }

    public Map<String, Object> patientByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, medical_number, full_name, gender, birth_date, phone, status "
                        + "FROM patients WHERE public_id = ?", publicId);
    }

    public Map<String, Object> appointmentByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "u.public_id AS provider_public_id FROM appointments a "
                        + "JOIN patients p ON p.id = a.patient_id JOIN users u ON u.id = a.provider_id "
                        + "WHERE a.public_id = ?", publicId);
    }

    public Map<String, Object> departmentByPublicId(String publicId) {
        return Sql.one(em, "SELECT id, public_id, name_ar, name_en FROM departments WHERE public_id = ?",
                publicId);
    }

    public Map<String, Object> currentPrice(long serviceId) {
        return Sql.one(em,
                "SELECT amount, currency FROM service_prices WHERE service_id = ? "
                        + "AND valid_from <= CURRENT_DATE AND (valid_to IS NULL OR valid_to >= CURRENT_DATE) "
                        + "ORDER BY valid_from DESC, id DESC LIMIT 1", serviceId);
    }

    public void insertCharge(String publicId, long patientId, Long encounterId, long serviceId,
                             BigDecimal price, String sourceType, String sourcePublicId, Long actorId) {
        Sql.insert(em,
                "INSERT INTO service_executions (public_id, patient_id, encounter_id, service_id, "
                        + "performed_by, price_snapshot, status, source_type, source_public_id, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'EXECUTED', ?, ?, ?)",
                publicId, patientId, encounterId, serviceId, actorId, price, sourceType,
                sourcePublicId, actorId);
    }

    public long chargeCount(long encounterId) {
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM service_executions WHERE encounter_id = ?", encounterId),
                "total", 0);
    }

    // ------------------------------------------------------------------
    // Chart context
    // ------------------------------------------------------------------

    public List<Map<String, Object>> activeAllergies(long patientId) {
        return Sql.query(em,
                "SELECT public_id, substance, allergy_type, severity, reaction FROM patient_allergies "
                        + "WHERE patient_id = ? AND active = 1 "
                        + "ORDER BY FIELD(severity, 'LIFE_THREATENING', 'SEVERE', 'MODERATE', 'MILD')",
                patientId);
    }

    public List<Map<String, Object>> conditions(long patientId) {
        return Sql.query(em,
                "SELECT public_id, description, onset_date, status FROM patient_conditions "
                        + "WHERE patient_id = ? AND status = 'ACTIVE' ORDER BY id", patientId);
    }

    public List<Map<String, Object>> recentEncounters(long patientId, String excludePublicId, int limit) {
        return Sql.query(em,
                "SELECT e.public_id, e.started_at, e.type, e.status, e.chief_complaint, "
                        + "u.display_name_ar AS provider_ar, u.display_name_en AS provider_en "
                        + "FROM encounters e JOIN users u ON u.id = e.provider_id "
                        + "WHERE e.patient_id = ? AND e.public_id <> ? ORDER BY e.started_at DESC LIMIT ?",
                patientId, excludePublicId, limit);
    }
}
