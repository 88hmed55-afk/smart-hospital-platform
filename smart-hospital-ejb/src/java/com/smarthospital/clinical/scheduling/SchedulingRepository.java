package com.smarthospital.clinical.scheduling;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for clinics, schedules, appointments and queues. */
@Stateless
public class SchedulingRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    public List<Map<String, Object>> clinics() {
        return Sql.query(em,
                "SELECT c.public_id, c.code, c.name_ar, c.name_en, c.location, "
                        + "d.name_ar AS department_ar, d.name_en AS department_en "
                        + "FROM clinics c JOIN departments d ON d.id = c.department_id "
                        + "WHERE c.active = 1 ORDER BY c.name_ar");
    }

    public Map<String, Object> clinicByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM clinics WHERE public_id = ? AND active = 1", publicId);
    }

    public List<Map<String, Object>> providers() {
        return Sql.query(em,
                "SELECT DISTINCT u.public_id, u.username, u.display_name_ar, u.display_name_en, "
                        + "u.department_id, d.name_ar AS department_ar, d.name_en AS department_en "
                        + "FROM users u JOIN user_roles ur ON ur.user_id = u.id "
                        + "JOIN roles r ON r.id = ur.role_id "
                        + "LEFT JOIN departments d ON d.id = u.department_id "
                        + "WHERE u.status = 'ACTIVE' AND r.code IN ('DOCTOR', 'CONSULTANT') "
                        + "ORDER BY u.display_name_ar");
    }

    public Map<String, Object> providerByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, username, display_name_ar, display_name_en, department_id "
                        + "FROM users WHERE public_id = ? AND status = 'ACTIVE'", publicId);
    }

    public boolean isProvider(long userId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.code IN ('DOCTOR', 'CONSULTANT')", userId);
        return Sql.longValue(row, "total", 0) > 0;
    }

    public List<Map<String, Object>> activeAppointmentsForProviderDay(long providerId, LocalDate date) {
        return Sql.query(em,
                "SELECT a.public_id, a.slot_start, a.slot_end, a.status, p.full_name AS patient_name "
                        + "FROM appointments a JOIN patients p ON p.id = a.patient_id "
                        + "WHERE a.provider_id = ? AND a.active_flag = 1 "
                        + "AND a.slot_start >= ? AND a.slot_start < ? ORDER BY a.slot_start",
                providerId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    public Map<String, Object> patientByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, medical_number, full_name, gender, birth_date, phone, status "
                        + "FROM patients WHERE public_id = ?", publicId);
    }

    public Map<String, Object> serviceByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM service_catalog WHERE public_id = ? AND active = 1", publicId);
    }

    public Map<String, Object> serviceByCode(String code) {
        return Sql.one(em, "SELECT * FROM service_catalog WHERE code = ? AND active = 1", code);
    }

    public Map<String, Object> drugByPublicId(String publicId) {
        return Sql.one(em, "SELECT * FROM drugs WHERE public_id = ? AND active = 1", publicId);
    }

    public List<Map<String, Object>> services(String category) {
        if (category == null || category.isBlank()) {
            return Sql.query(em,
                    "SELECT public_id, code, name_ar, name_en, category FROM service_catalog "
                            + "WHERE active = 1 ORDER BY category, name_ar");
        }
        return Sql.query(em,
                "SELECT public_id, code, name_ar, name_en, category FROM service_catalog "
                        + "WHERE active = 1 AND category = ? ORDER BY name_ar", category.toUpperCase());
    }

    public List<Map<String, Object>> drugs(String q) {
        if (q == null || q.isBlank()) {
            return Sql.query(em,
                    "SELECT public_id, code, name_ar, name_en, form, strength FROM drugs "
                            + "WHERE active = 1 ORDER BY name_en");
        }
        String like = "%" + q.trim() + "%";
        return Sql.query(em,
                "SELECT public_id, code, name_ar, name_en, form, strength FROM drugs "
                        + "WHERE active = 1 AND (name_ar LIKE ? OR name_en LIKE ? OR code LIKE ?) "
                        + "ORDER BY name_en", like, like, like);
    }

    public List<Map<String, Object>> schedules(String providerPublicId) {
        String filter = providerPublicId == null || providerPublicId.isBlank()
                ? "" : " AND u.public_id = ? ";
        List<Object> params = new ArrayList<>();
        if (!filter.isEmpty()) {
            params.add(providerPublicId);
        }
        return Sql.query(em,
                "SELECT s.public_id, s.day_of_week, s.start_time, s.end_time, s.slot_minutes, "
                        + "s.capacity, s.valid_from, s.valid_to, s.active, "
                        + "u.public_id AS provider_public_id, u.display_name_ar AS provider_ar, "
                        + "u.display_name_en AS provider_en, c.public_id AS clinic_public_id, "
                        + "c.name_ar AS clinic_ar, c.name_en AS clinic_en "
                        + "FROM provider_schedules s JOIN users u ON u.id = s.provider_id "
                        + "JOIN clinics c ON c.id = s.clinic_id WHERE s.active = 1" + filter
                        + " ORDER BY u.display_name_ar, s.day_of_week, s.start_time",
                params.toArray());
    }

    public long insertSchedule(String publicId, long providerId, long clinicId, int dayOfWeek,
                               LocalTime start, LocalTime end, int slotMinutes, Integer capacity,
                               LocalDate validFrom, LocalDate validTo, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO provider_schedules (public_id, provider_id, clinic_id, day_of_week, "
                        + "start_time, end_time, slot_minutes, capacity, valid_from, valid_to, active, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                publicId, providerId, clinicId, dayOfWeek, start, end, slotMinutes, capacity,
                validFrom, validTo, actorId);
    }

    public List<Map<String, Object>> schedulesForDay(long providerId, long clinicId, int dayOfWeek,
                                                     LocalDate date) {
        return Sql.query(em,
                "SELECT * FROM provider_schedules WHERE provider_id = ? AND clinic_id = ? "
                        + "AND day_of_week = ? AND active = 1 AND valid_from <= ? "
                        + "AND (valid_to IS NULL OR valid_to >= ?) ORDER BY start_time",
                providerId, clinicId, dayOfWeek, date, date);
    }

    public Map<String, Object> exceptionFor(long providerId, LocalDate date) {
        return Sql.one(em,
                "SELECT * FROM schedule_exceptions WHERE provider_id = ? AND exception_date = ? "
                        + "ORDER BY start_time IS NULL DESC LIMIT 1", providerId, date);
    }

    // ------------------------------------------------------------------
    // Appointments
    // ------------------------------------------------------------------

    public Map<String, Object> appointmentByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT a.*, p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.phone AS patient_phone, u.public_id AS provider_public_id, "
                        + "u.display_name_ar AS provider_ar, u.display_name_en AS provider_en, "
                        + "c.public_id AS clinic_public_id, c.name_ar AS clinic_ar, c.name_en AS clinic_en "
                        + "FROM appointments a JOIN patients p ON p.id = a.patient_id "
                        + "JOIN users u ON u.id = a.provider_id JOIN clinics c ON c.id = a.clinic_id "
                        + "WHERE a.public_id = ?", publicId);
    }

    public List<Map<String, Object>> searchAppointments(LocalDate date, Long providerId, Long clinicId,
                                                       String status, Long patientId,
                                                       int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = appointmentWhere(date, providerId, clinicId, status, patientId, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em,
                "SELECT a.public_id, a.slot_start, a.slot_end, a.status, a.source, a.checked_in_at, "
                        + "p.public_id AS patient_public_id, p.medical_number, p.full_name AS patient_name, "
                        + "p.phone AS patient_phone, u.public_id AS provider_public_id, "
                        + "u.display_name_ar AS provider_ar, u.display_name_en AS provider_en, "
                        + "c.public_id AS clinic_public_id, c.name_ar AS clinic_ar, c.name_en AS clinic_en "
                        + "FROM appointments a JOIN patients p ON p.id = a.patient_id "
                        + "JOIN users u ON u.id = a.provider_id JOIN clinics c ON c.id = a.clinic_id "
                        + where + " ORDER BY a.slot_start, c.name_ar LIMIT ? OFFSET ?", params.toArray());
    }

    public long countAppointments(LocalDate date, Long providerId, Long clinicId, String status,
                                  Long patientId) {
        List<Object> params = new ArrayList<>();
        String where = appointmentWhere(date, providerId, clinicId, status, patientId, params);
        return Sql.longValue(Sql.one(em,
                "SELECT COUNT(*) AS total FROM appointments a " + where, params.toArray()), "total", 0);
    }

    private String appointmentWhere(LocalDate date, Long providerId, Long clinicId, String status,
                                    Long patientId, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (date != null) {
            where.append(" AND a.slot_start >= ? AND a.slot_start < ? ");
            params.add(date.atStartOfDay());
            params.add(date.plusDays(1).atStartOfDay());
        }
        if (providerId != null) {
            where.append(" AND a.provider_id = ? ");
            params.add(providerId);
        }
        if (clinicId != null) {
            where.append(" AND a.clinic_id = ? ");
            params.add(clinicId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ? ");
            params.add(status);
        }
        if (patientId != null) {
            where.append(" AND a.patient_id = ? ");
            params.add(patientId);
        }
        return where.toString();
    }

    public void lockProvider(long providerId) {
        Sql.one(em, "SELECT id FROM users WHERE id = ? FOR UPDATE", providerId);
    }

    public long countConflicts(long providerId, LocalDateTime start, LocalDateTime end, Long excludeId) {
        String sql = "SELECT COUNT(*) AS total FROM appointments WHERE provider_id = ? "
                + "AND active_flag = 1 AND slot_start < ? AND slot_end > ? "
                + (excludeId == null ? "" : "AND id <> ?");
        List<Object> params = new ArrayList<>();
        params.add(providerId);
        params.add(end);
        params.add(start);
        if (excludeId != null) {
            params.add(excludeId);
        }
        return Sql.longValue(Sql.one(em, sql, params.toArray()), "total", 0);
    }

    public long insertAppointment(String publicId, long patientId, long providerId, long clinicId,
                                  LocalDateTime start, LocalDateTime end, String status, String source,
                                  Long actorId) {
        return Sql.insert(em,
                "INSERT INTO appointments (public_id, patient_id, provider_id, clinic_id, slot_start, "
                        + "slot_end, status, source, active_flag, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                publicId, patientId, providerId, clinicId, start, end, status, source, actorId);
    }

    public int reschedule(long id, long version, LocalDateTime start, LocalDateTime end,
                          Long actorId) {
        return Sql.update(em,
                "UPDATE appointments SET slot_start = ?, slot_end = ?, status = 'BOOKED', "
                        + "active_flag = 1, checked_in_at = NULL, cancel_reason = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status IN ('BOOKED', 'CHECKED_IN')",
                start, end, actorId, id, version);
    }

    public int changeStatus(long id, long version, String status, Integer activeFlag,
                            String cancelReason, LocalDateTime checkedInAt, Long actorId) {
        return Sql.update(em,
                "UPDATE appointments SET status = ?, active_flag = ?, cancel_reason = COALESCE(?, cancel_reason), "
                        + "checked_in_at = COALESCE(?, checked_in_at), updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                status, activeFlag, cancelReason, checkedInAt, actorId, id, version);
    }

    public void insertHistory(long appointmentId, String fromStatus, String toStatus,
                              Long actorId, String reason) {
        Sql.insert(em,
                "INSERT INTO appointment_status_history (appointment_id, from_status, to_status, "
                        + "changed_by, reason) VALUES (?, ?, ?, ?, ?)",
                appointmentId, fromStatus, toStatus, actorId, reason);
    }

    public List<Map<String, Object>> appointmentHistory(long appointmentId) {
        return Sql.query(em,
                "SELECT h.from_status, h.to_status, h.changed_at, h.reason, "
                        + "u.display_name_ar AS actor_ar, u.display_name_en AS actor_en "
                        + "FROM appointment_status_history h JOIN users u ON u.id = h.changed_by "
                        + "WHERE h.appointment_id = ? ORDER BY h.changed_at", appointmentId);
    }

    // ------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------

    public Map<String, Object> queueTicketByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT q.*, p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number, c.public_id AS clinic_public_id, c.name_ar AS clinic_ar "
                        + "FROM queue_tickets q JOIN patients p ON p.id = q.patient_id "
                        + "JOIN clinics c ON c.id = q.clinic_id WHERE q.public_id = ?", publicId);
    }

    public Map<String, Object> queueTicketForAppointment(long appointmentId) {
        return Sql.one(em,
                "SELECT * FROM queue_tickets WHERE appointment_id = ? ORDER BY id DESC LIMIT 1",
                appointmentId);
    }

    public List<Map<String, Object>> queue(long clinicId, LocalDate date) {
        return Sql.query(em,
                "SELECT q.public_id, q.ticket_no, q.priority, q.status, q.checked_in_at, q.called_at, "
                        + "q.served_at, p.public_id AS patient_public_id, p.full_name AS patient_name, "
                        + "p.medical_number, a.public_id AS appointment_public_id "
                        + "FROM queue_tickets q JOIN patients p ON p.id = q.patient_id "
                        + "LEFT JOIN appointments a ON a.id = q.appointment_id "
                        + "WHERE q.clinic_id = ? AND q.ticket_date = ? "
                        + "ORDER BY FIELD(q.status, 'CALLED', 'IN_SERVICE', 'WAITING', 'DONE', 'LEFT'), "
                        + "FIELD(q.priority, 'EMERGENCY', 'URGENT', 'NORMAL'), q.ticket_no",
                clinicId, date);
    }

    public void lockClinic(long clinicId) {
        Sql.one(em, "SELECT id FROM clinics WHERE id = ? FOR UPDATE", clinicId);
    }

    public int nextTicketNo(long clinicId, LocalDate date) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COALESCE(MAX(ticket_no), 0) + 1 AS next_no FROM queue_tickets "
                        + "WHERE clinic_id = ? AND ticket_date = ?", clinicId, date);
        return Sql.intValue(row, "nextNo", 1);
    }

    public long insertQueueTicket(String publicId, Long appointmentId, long patientId, long clinicId,
                                  LocalDate date, int ticketNo, String priority, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO queue_tickets (public_id, appointment_id, patient_id, clinic_id, ticket_date, "
                        + "ticket_no, priority, status, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING', ?)",
                publicId, appointmentId, patientId, clinicId, date, ticketNo, priority, actorId);
    }

    public int changeQueueStatus(long id, String status, String timestampColumn) {
        return Sql.update(em,
                "UPDATE queue_tickets SET status = ?, " + timestampColumn + " = CURRENT_TIMESTAMP(6), "
                        + "version = version + 1 WHERE id = ?", status, id);
    }

    // ------------------------------------------------------------------
    // Waitlist
    // ------------------------------------------------------------------

    public List<Map<String, Object>> waitlist(Long clinicId) {
        List<Object> params = new ArrayList<>();
        String filter = "";
        if (clinicId != null) {
            filter = " AND w.clinic_id = ? ";
            params.add(clinicId);
        }
        return Sql.query(em,
                "SELECT w.public_id, w.desired_from, w.desired_to, w.priority, w.status, w.notes, "
                        + "p.public_id AS patient_public_id, p.full_name AS patient_name, p.medical_number, "
                        + "u.public_id AS provider_public_id, u.display_name_ar AS provider_ar, "
                        + "c.public_id AS clinic_public_id, c.name_ar AS clinic_ar "
                        + "FROM waitlist_entries w JOIN patients p ON p.id = w.patient_id "
                        + "JOIN users u ON u.id = w.provider_id JOIN clinics c ON c.id = w.clinic_id "
                        + "WHERE w.status = 'WAITING'" + filter
                        + " ORDER BY FIELD(w.priority, 'EMERGENCY', 'URGENT', 'NORMAL'), w.desired_from",
                params.toArray());
    }

    public long insertWaitlist(String publicId, long patientId, long providerId, long clinicId,
                               LocalDate desiredFrom, LocalDate desiredTo, String priority,
                               String notes, Long actorId) {
        return Sql.insert(em,
                "INSERT INTO waitlist_entries (public_id, patient_id, provider_id, clinic_id, desired_from, "
                        + "desired_to, priority, status, notes, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'WAITING', ?, ?)",
                publicId, patientId, providerId, clinicId, desiredFrom, desiredTo, priority, notes, actorId);
    }

    public int updateWaitlistStatus(long id, String status, Long actorId) {
        return Sql.update(em,
                "UPDATE waitlist_entries SET status = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND status = 'WAITING'",
                status, actorId, id);
    }
}
