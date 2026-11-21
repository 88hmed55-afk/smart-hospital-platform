package com.smarthospital.clinical.scheduling;

import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Clinics, schedules, appointment booking and the reception queue (FR-SCH-001..010). */
@Stateless
public class SchedulingService implements SchedulingServiceLocal {

    private static final Set<String> APPOINTMENT_STATUSES = Set.of(
            "BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW");
    private static final Set<String> SOURCES = Set.of("RECEPTION", "PHONE", "WALK_IN", "PORTAL");
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "URGENT", "EMERGENCY");

    @EJB
    private SchedulingRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private SettingsRepository settings;

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> clinics() {
        return repository.clinics();
    }

    @Override
    public List<Map<String, Object>> providers() {
        return repository.providers();
    }

    @Override
    public List<Map<String, Object>> schedules(String providerPublicId) {
        return repository.schedules(providerPublicId);
    }

    @Override
    public List<Map<String, Object>> services(String category) {
        return repository.services(category);
    }

    @Override
    public List<Map<String, Object>> drugs(String q) {
        return repository.drugs(q);
    }

    @Override
    public Map<String, Object> createSchedule(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> provider = requireProvider(
                ClinicalSupport.required(body, "providerPublicId",
                        "الطبيب مطلوب.", "Provider is required."));
        Map<String, Object> clinic = requireClinic(
                ClinicalSupport.required(body, "clinicPublicId",
                        "العيادة مطلوبة.", "Clinic is required."));
        int dayOfWeek = ClinicalSupport.optionalInt(body, "dayOfWeek") == null
                ? 0 : ClinicalSupport.optionalInt(body, "dayOfWeek");
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "يوم الأسبوع يجب أن يكون بين 1 و7.", "Day of week must be between 1 and 7.");
        }
        LocalTime start = parseTime(ClinicalSupport.required(body, "startTime",
                "وقت البداية مطلوب.", "Start time is required."));
        LocalTime end = parseTime(ClinicalSupport.required(body, "endTime",
                "وقت النهاية مطلوب.", "End time is required."));
        if (!end.isAfter(start)) {
            throw AppException.unprocessable("INVALID_SCHEDULE",
                    "وقت النهاية يجب أن يكون بعد البداية.", "End time must be after start time.");
        }
        int slotMinutes = ClinicalSupport.optionalInt(body, "slotMinutes") == null
                ? intSetting("scheduling.slot.minutes", 20)
                : ClinicalSupport.optionalInt(body, "slotMinutes");
        if (slotMinutes < 5 || slotMinutes > 240) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مدة الفترة يجب أن تكون بين 5 و240 دقيقة.",
                    "Slot length must be between 5 and 240 minutes.");
        }
        LocalDate validFrom = ClinicalSupport.date(body, "validFrom", false);
        if (validFrom == null) {
            validFrom = LocalDate.now();
        }
        LocalDate validTo = ClinicalSupport.date(body, "validTo", false);
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw AppException.unprocessable("INVALID_SCHEDULE",
                    "نهاية الصلاحية قبل بدايتها.", "Valid-to date is before valid-from.");
        }
        long id = repository.insertSchedule(Ulid.next(),
                Sql.longValue(provider, "id", 0), Sql.longValue(clinic, "id", 0), dayOfWeek,
                start, end, slotMinutes, ClinicalSupport.optionalInt(body, "capacity"),
                validFrom, validTo, actor.getUserId());
        audit.record(actor.getUserId(), "SCHEDULE_CREATED", "PROVIDER_SCHEDULE", null, null,
                ClinicalSupport.json("provider", provider.get("publicId"), "clinic", clinic.get("publicId"),
                        "dayOfWeek", dayOfWeek, "start", start.toString(), "end", end.toString()),
                null, ip);
        return ClinicalSupport.map("id", id, "dayOfWeek", dayOfWeek,
                "startTime", start.toString(), "endTime", end.toString(), "slotMinutes", slotMinutes);
    }

    // ------------------------------------------------------------------
    // Availability
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> slots(String providerPublicId, String clinicPublicId, String date) {
        Map<String, Object> provider = requireProvider(providerPublicId);
        Map<String, Object> clinic = requireClinic(clinicPublicId);
        LocalDate day = parseDate(date, true);
        long providerId = Sql.longValue(provider, "id", 0);
        long clinicId = Sql.longValue(clinic, "id", 0);

        List<Map<String, Object>> schedules = repository.schedulesForDay(
                providerId, clinicId, day.getDayOfWeek().getValue(), day);
        Map<String, Object> exception = repository.exceptionFor(providerId, day);
        List<Map<String, Object>> booked = repository.activeAppointmentsForProviderDay(providerId, day);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("provider", providerView(provider));
        result.put("clinic", clinicView(clinic));
        result.put("scheduleConfigured", !schedules.isEmpty());
        List<Map<String, Object>> slots = new ArrayList<>();
        int slotMinutes = intSetting("scheduling.slot.minutes", 20);
        if (!schedules.isEmpty()) {
            slotMinutes = Sql.intValue(schedules.get(0), "slotMinutes", slotMinutes);
        }
        for (Map<String, Object> schedule : schedules) {
            LocalTime start = parseTime(Sql.text(schedule, "startTime"));
            LocalTime end = parseTime(Sql.text(schedule, "endTime"));
            int minutes = Sql.intValue(schedule, "slotMinutes", slotMinutes);
            if (minutes <= 0) {
                minutes = 20;
            }
            // Count minutes by offset instead of advancing a LocalTime: LocalTime
            // wraps at midnight, so a schedule ending at 23:59 would otherwise
            // never satisfy "cursor + slot <= end" and loop forever.
            long totalMinutes = Duration.between(start, end).toMinutes();
            if (totalMinutes <= 0) {
                totalMinutes += 24 * 60;
            }
            totalMinutes = Math.min(totalMinutes, 24 * 60);
            for (long offset = 0; offset + minutes <= totalMinutes; offset += minutes) {
                LocalDateTime slotStart = day.atTime(start).plusMinutes(offset);
                LocalDateTime slotEnd = slotStart.plusMinutes(minutes);
                LocalTime slotTime = slotStart.toLocalTime();
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("start", slotStart.toString());
                slot.put("end", slotEnd.toString());
                slot.put("available", true);
                if (exception != null && blockedByException(exception, slotTime, slotEnd.toLocalTime())) {
                    slot.put("available", false);
                    slot.put("reason", "EXCEPTION");
                }
                for (Map<String, Object> appointment : booked) {
                    if (String.valueOf(appointment.get("slotStart")).equals(slotStart.toString())) {
                        slot.put("available", false);
                        slot.put("reason", "BOOKED");
                        slot.put("appointmentPublicId", appointment.get("publicId"));
                        slot.put("patientName", appointment.get("patientName"));
                        slot.put("status", appointment.get("status"));
                    }
                }
                slots.add(slot);
            }
        }
        slots.sort((a, b) -> String.valueOf(a.get("start")).compareTo(String.valueOf(b.get("start"))));
        result.put("slotMinutes", slotMinutes);
        result.put("slots", slots);
        return result;
    }

    // ------------------------------------------------------------------
    // Appointments
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> searchAppointments(String date, String providerPublicId,
                                                  String clinicPublicId, String status,
                                                  String patientPublicId, int page, int size) {
        int safeSize = ClinicalSupport.clampSize(size);
        int safePage = Math.max(page, 0);
        LocalDate day = parseDate(date, false);
        Long providerId = resolveId(providerPublicId, true);
        Long clinicId = resolveId(clinicPublicId, false);
        Long patientId = patientPublicId == null || patientPublicId.isBlank()
                ? null : Sql.longValue(requirePatient(patientPublicId), "id");
        String safeStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        if (safeStatus != null && !APPOINTMENT_STATUSES.contains(safeStatus)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حالة الموعد غير صحيحة.", "Invalid appointment status.");
        }
        long total = repository.countAppointments(day, providerId, clinicId, safeStatus, patientId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.searchAppointments(
                day, providerId, clinicId, safeStatus, patientId, safePage * safeSize, safeSize)) {
            items.add(appointmentView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> createAppointment(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> patient = requirePatient(
                ClinicalSupport.required(body, "patientPublicId",
                        "المريض مطلوب.", "Patient is required."));
        Map<String, Object> provider = requireProvider(
                ClinicalSupport.required(body, "providerPublicId",
                        "الطبيب مطلوب.", "Provider is required."));
        Map<String, Object> clinic = requireClinic(
                ClinicalSupport.required(body, "clinicPublicId",
                        "العيادة مطلوبة.", "Clinic is required."));
        LocalDateTime start = ClinicalSupport.dateTime(body, "slotStart", true);
        String source = ClinicalSupport.optional(body, "source");
        source = source == null ? "RECEPTION" : source.toUpperCase();
        if (!SOURCES.contains(source)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مصدر الموعد غير صحيح.", "Invalid appointment source.");
        }
        boolean overbook = ClinicalSupport.bool(body, "overbook")
                && actor.hasPermission("SCH:OVERBOOK");
        long providerId = Sql.longValue(provider, "id", 0);
        long clinicId = Sql.longValue(clinic, "id", 0);
        long patientId = Sql.longValue(patient, "id", 0);
        SlotWindow window = resolveWindow(providerId, clinicId, start, overbook);
        LocalDateTime end = window.end;

        repository.lockProvider(providerId);
        if (repository.countConflicts(providerId, start, end, null) > 0) {
            throw AppException.conflict("APPOINTMENT_CONFLICT",
                    "الطبيب لديه موعد فعّال في هذه الفترة.",
                    "The provider already has an active appointment in this slot.");
        }

        String publicId = Ulid.next();
        try {
            repository.insertAppointment(publicId, patientId, providerId, clinicId, start, end,
                    "BOOKED", source, actor.getUserId());
        } catch (RuntimeException ex) {
            // Two concurrent bookings can both pass the conflict count under MySQL's
            // REPEATABLE READ snapshot; the unique provider/slot key is the final guard.
            if (isDuplicateKey(ex)) {
                throw AppException.conflict("APPOINTMENT_CONFLICT",
                        "الطبيب لديه موعد فعّال في هذه الفترة.",
                        "The provider already has an active appointment in this slot.");
            }
            throw ex;
        }
        Map<String, Object> row = repository.appointmentByPublicId(publicId);
        repository.insertHistory(Sql.longValue(row, "id", 0), null, "BOOKED",
                actor.getUserId(), "CREATED");
        audit.record(actor.getUserId(), "APPOINTMENT_CREATED", "APPOINTMENT", publicId, null,
                ClinicalSupport.json("patient", patient.get("publicId"), "provider", provider.get("publicId"),
                        "clinic", clinic.get("publicId"), "slotStart", start.toString(),
                        "slotEnd", end.toString(), "overbook", overbook),
                null, ip);
        return appointmentView(row);
    }

    @Override
    public Map<String, Object> reschedule(String publicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAppointment(publicId);
        String status = Sql.text(row, "status");
        if (!"BOOKED".equals(status) && !"CHECKED_IN".equals(status)) {
            throw AppException.conflict("INVALID_STATUS",
                    "لا يمكن إعادة جدولة موعد في حالته الحالية.",
                    "The appointment cannot be rescheduled in its current status.");
        }
        LocalDateTime start = ClinicalSupport.dateTime(body, "slotStart", true);
        long providerId = Sql.longValue(row, "providerId", 0);
        long clinicId = Sql.longValue(row, "clinicId", 0);
        boolean overbook = ClinicalSupport.bool(body, "overbook")
                && actor.hasPermission("SCH:OVERBOOK");
        SlotWindow window = resolveWindow(providerId, clinicId, start, overbook);
        long id = Sql.longValue(row, "id", 0);
        long version = Sql.longValue(row, "version", 0);

        repository.lockProvider(providerId);
        if (repository.countConflicts(providerId, start, window.end, id) > 0) {
            throw AppException.conflict("APPOINTMENT_CONFLICT",
                    "الطبيب لديه موعد فعّال في هذه الفترة.",
                    "The provider already has an active appointment in this slot.");
        }
        if (repository.reschedule(id, version, start, window.end, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الموعد من مستخدم آخر. أعد التحميل.",
                    "The appointment changed by another user. Reload and retry.");
        }
        repository.insertHistory(id, status, "BOOKED", actor.getUserId(), "RESCHEDULED");
        audit.record(actor.getUserId(), "APPOINTMENT_RESCHEDULED", "APPOINTMENT", publicId,
                ClinicalSupport.json("slotStart", Sql.text(row, "slotStart")),
                ClinicalSupport.json("slotStart", start.toString(), "slotEnd", window.end.toString()),
                ClinicalSupport.optional(body, "reason"), ip);
        return appointmentView(repository.appointmentByPublicId(publicId));
    }

    @Override
    public Map<String, Object> cancel(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAppointment(publicId);
        String status = Sql.text(row, "status");
        if (!"BOOKED".equals(status) && !"CHECKED_IN".equals(status)) {
            throw AppException.conflict("INVALID_STATUS",
                    "لا يمكن إلغاء موعد في حالته الحالية.",
                    "The appointment cannot be cancelled in its current status.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الإلغاء مطلوب.", "Cancellation reason is required.");
        long id = Sql.longValue(row, "id", 0);
        long version = Sql.longValue(row, "version", 0);
        if (repository.changeStatus(id, version, "CANCELLED", null, reason, null,
                actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الموعد من مستخدم آخر. أعد التحميل.",
                    "The appointment changed by another user. Reload and retry.");
        }
        repository.insertHistory(id, status, "CANCELLED", actor.getUserId(), reason);
        Map<String, Object> ticket = repository.queueTicketForAppointment(id);
        if (ticket != null && Set.of("WAITING", "CALLED").contains(Sql.text(ticket, "status"))) {
            repository.changeQueueStatus(Sql.longValue(ticket, "id", 0), "LEFT", "served_at");
        }
        audit.record(actor.getUserId(), "APPOINTMENT_CANCELLED", "APPOINTMENT", publicId,
                ClinicalSupport.json("status", status), ClinicalSupport.json("status", "CANCELLED"),
                reason, ip);
        return appointmentView(repository.appointmentByPublicId(publicId));
    }

    @Override
    public Map<String, Object> checkIn(String publicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAppointment(publicId);
        if (!"BOOKED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATUS",
                    "تسجيل الوصول متاح للمواعيد المحجوزة فقط.",
                    "Check-in is only allowed for booked appointments.");
        }
        boolean force = ClinicalSupport.bool(body, "force") && actor.hasPermission("SCH:OVERBOOK");
        LocalDateTime start = parseDateTime(Sql.text(row, "slotStart"));
        LocalDateTime now = LocalDateTime.now();
        long early = intSetting("scheduling.checkin.early_minutes", 60);
        long late = intSetting("scheduling.checkin.late_minutes", 30);
        long minutes = Duration.between(now, start).toMinutes();
        if (!force && minutes > early) {
            throw AppException.unprocessable("CHECKIN_TOO_EARLY",
                    "لا يمكن تسجيل الوصول قبل " + early + " دقيقة من الموعد.",
                    "Check-in opens " + early + " minutes before the appointment.");
        }
        if (!force && minutes < -late) {
            throw AppException.unprocessable("CHECKIN_TOO_LATE",
                    "انتهت مهلة تسجيل الوصول (" + late + " دقيقة).",
                    "The check-in window closed " + late + " minutes after the slot.");
        }
        long id = Sql.longValue(row, "id", 0);
        long version = Sql.longValue(row, "version", 0);
        if (repository.changeStatus(id, version, "CHECKED_IN", 1, null, now, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الموعد من مستخدم آخر. أعد التحميل.",
                    "The appointment changed by another user. Reload and retry.");
        }
        repository.insertHistory(id, "BOOKED", "CHECKED_IN", actor.getUserId(), null);

        Map<String, Object> ticket = repository.queueTicketForAppointment(id);
        if (ticket == null) {
            long patientId = Sql.longValue(row, "patientId", 0);
            long clinicId = Sql.longValue(row, "clinicId", 0);
            String priority = ClinicalSupport.optional(body, "priority");
            priority = priority == null ? "NORMAL" : priority.toUpperCase();
            if (!PRIORITIES.contains(priority)) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "أولوية الطابور غير صحيحة.", "Invalid queue priority.");
            }
            repository.lockClinic(clinicId);
            int ticketNo = repository.nextTicketNo(clinicId, LocalDate.now());
            repository.insertQueueTicket(Ulid.next(), id, patientId, clinicId,
                    LocalDate.now(), ticketNo, priority, actor.getUserId());
            ticket = repository.queueTicketForAppointment(id);
        }
        audit.record(actor.getUserId(), "APPOINTMENT_CHECKED_IN", "APPOINTMENT", publicId,
                ClinicalSupport.json("status", "BOOKED"),
                ClinicalSupport.json("status", "CHECKED_IN", "ticketNo", Sql.text(ticket, "ticketNo")),
                null, ip);
        Map<String, Object> result = appointmentView(repository.appointmentByPublicId(publicId));
        result.put("queueTicket", ticketView(ticket));
        return result;
    }

    @Override
    public Map<String, Object> noShow(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip) {
        Map<String, Object> row = requireAppointment(publicId);
        if (!"BOOKED".equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATUS",
                    "تسجيل عدم الحضور متاح للمواعيد المحجوزة فقط.",
                    "No-show is only allowed for booked appointments.");
        }
        long id = Sql.longValue(row, "id", 0);
        long version = Sql.longValue(row, "version", 0);
        if (repository.changeStatus(id, version, "NO_SHOW", null,
                ClinicalSupport.optional(body, "reason"), null, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الموعد من مستخدم آخر. أعد التحميل.",
                    "The appointment changed by another user. Reload and retry.");
        }
        repository.insertHistory(id, "BOOKED", "NO_SHOW", actor.getUserId(),
                ClinicalSupport.optional(body, "reason"));
        audit.record(actor.getUserId(), "APPOINTMENT_NO_SHOW", "APPOINTMENT", publicId,
                ClinicalSupport.json("status", "BOOKED"), ClinicalSupport.json("status", "NO_SHOW"),
                ClinicalSupport.optional(body, "reason"), ip);
        return appointmentView(repository.appointmentByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> queue(String clinicPublicId, String date) {
        Map<String, Object> clinic = requireClinic(clinicPublicId);
        LocalDate day = parseDate(date, false);
        if (day == null) {
            day = LocalDate.now();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.queue(Sql.longValue(clinic, "id", 0), day)) {
            items.add(ticketView(row));
        }
        return items;
    }

    @Override
    public Map<String, Object> callTicket(String publicId, AuthPrincipal actor, String ip) {
        return transitionTicket(publicId, "WAITING", "CALLED", "called_at",
                "QUEUE_CALLED", actor, ip);
    }

    @Override
    public Map<String, Object> serveTicket(String publicId, AuthPrincipal actor, String ip) {
        return transitionTicket(publicId, "CALLED", "IN_SERVICE", "served_at",
                "QUEUE_SERVED", actor, ip);
    }

    @Override
    public Map<String, Object> completeTicket(String publicId, AuthPrincipal actor, String ip) {
        return transitionTicket(publicId, "IN_SERVICE", "DONE", "served_at",
                "QUEUE_DONE", actor, ip);
    }

    private Map<String, Object> transitionTicket(String publicId, String expected, String target,
                                                 String timestampColumn, String action,
                                                 AuthPrincipal actor, String ip) {
        Map<String, Object> row = repository.queueTicketByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("TICKET_NOT_FOUND",
                    "تذكرة الطابور غير موجودة.", "Queue ticket not found.");
        }
        if (!expected.equals(Sql.text(row, "status"))) {
            throw AppException.conflict("INVALID_STATUS",
                    "لا يمكن تنفيذ هذا الإجراء على تذكرة بحالتها الحالية.",
                    "The action is not allowed for the ticket's current status.");
        }
        repository.changeQueueStatus(Sql.longValue(row, "id", 0), target, timestampColumn);
        audit.record(actor.getUserId(), action, "QUEUE_TICKET", publicId,
                ClinicalSupport.json("status", expected), ClinicalSupport.json("status", target), null, ip);
        return ticketView(repository.queueTicketByPublicId(publicId));
    }

    // ------------------------------------------------------------------
    // Waitlist
    // ------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> waitlist(String clinicPublicId) {
        Long clinicId = resolveId(clinicPublicId, false);
        return repository.waitlist(clinicId);
    }

    @Override
    public Map<String, Object> addWaitlist(Map<String, Object> body, AuthPrincipal actor, String ip) {
        Map<String, Object> patient = requirePatient(
                ClinicalSupport.required(body, "patientPublicId",
                        "المريض مطلوب.", "Patient is required."));
        Map<String, Object> provider = requireProvider(
                ClinicalSupport.required(body, "providerPublicId",
                        "الطبيب مطلوب.", "Provider is required."));
        Map<String, Object> clinic = requireClinic(
                ClinicalSupport.required(body, "clinicPublicId",
                        "العيادة مطلوبة.", "Clinic is required."));
        LocalDate from = ClinicalSupport.date(body, "desiredFrom", true);
        LocalDate to = ClinicalSupport.date(body, "desiredTo", false);
        if (to != null && to.isBefore(from)) {
            throw AppException.unprocessable("INVALID_WAITLIST",
                    "نهاية الفترة قبل بدايتها.", "Desired-to date is before desired-from.");
        }
        String priority = ClinicalSupport.optional(body, "priority");
        priority = priority == null ? "NORMAL" : priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الأولوية غير صحيحة.", "Invalid priority.");
        }
        String publicId = Ulid.next();
        repository.insertWaitlist(publicId, Sql.longValue(patient, "id", 0),
                Sql.longValue(provider, "id", 0), Sql.longValue(clinic, "id", 0),
                from, to, priority, ClinicalSupport.optionalTrim(body, "notes", 255), actor.getUserId());
        audit.record(actor.getUserId(), "WAITLIST_ADDED", "WAITLIST_ENTRY", publicId, null,
                ClinicalSupport.json("patient", patient.get("publicId"), "provider", provider.get("publicId"),
                        "desiredFrom", from.toString(), "priority", priority), null, ip);
        return ClinicalSupport.map("publicId", publicId, "status", "WAITING",
                "desiredFrom", from.toString(), "priority", priority);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SlotWindow resolveWindow(long providerId, long clinicId, LocalDateTime start,
                                     boolean overbook) {
        LocalDate day = start.toLocalDate();
        LocalTime time = start.toLocalTime();
        List<Map<String, Object>> schedules = repository.schedulesForDay(
                providerId, clinicId, day.getDayOfWeek().getValue(), day);
        int defaultMinutes = intSetting("scheduling.slot.minutes", 20);
        Map<String, Object> chosen = null;
        for (Map<String, Object> schedule : schedules) {
            LocalTime scheduleStart = parseTime(Sql.text(schedule, "startTime"));
            LocalTime scheduleEnd = parseTime(Sql.text(schedule, "endTime"));
            if (!time.isBefore(scheduleStart) && time.isBefore(scheduleEnd)) {
                chosen = schedule;
                break;
            }
        }
        int minutes = chosen == null ? defaultMinutes : Sql.intValue(chosen, "slotMinutes", defaultMinutes);
        if (chosen == null && !overbook) {
            throw AppException.unprocessable("OUTSIDE_SCHEDULE",
                    "الفترة المختارة خارج جدول الطبيب. استخدم الحجز فوق الطاقة عند الحاجة.",
                    "The selected slot is outside the provider schedule. Use overbooking if required.");
        }
        if (chosen != null && start.plusMinutes(minutes).toLocalTime().isAfter(
                parseTime(Sql.text(chosen, "endTime")))) {
            throw AppException.unprocessable("OUTSIDE_SCHEDULE",
                    "الفترة المختارة تتجاوز نهاية جدول الطبيب.",
                    "The selected slot exceeds the provider schedule.");
        }
        Map<String, Object> exception = repository.exceptionFor(providerId, day);
        if (exception != null && !overbook
                && blockedByException(exception, time, time.plusMinutes(minutes))) {
            throw AppException.unprocessable("SCHEDULE_EXCEPTION",
                    "الطبيب غير متاح في هذا الوقت (استثناء في الجدول).",
                    "The provider is unavailable at this time (schedule exception).");
        }
        return new SlotWindow(start.plusMinutes(minutes));
    }

    private boolean blockedByException(Map<String, Object> exception, LocalTime start, LocalTime end) {
        String from = Sql.text(exception, "startTime");
        String to = Sql.text(exception, "endTime");
        if (from == null || to == null) {
            return true;
        }
        LocalTime exceptionStart = parseTime(from);
        LocalTime exceptionEnd = parseTime(to);
        return start.isBefore(exceptionEnd) && end.isAfter(exceptionStart);
    }

    private Map<String, Object> appointmentView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("slotStart", row.get("slotStart"));
        view.put("slotEnd", row.get("slotEnd"));
        view.put("status", row.get("status"));
        view.put("source", row.get("source"));
        view.put("cancelReason", row.get("cancelReason"));
        view.put("checkedInAt", row.get("checkedInAt"));
        view.put("version", row.get("version"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("patientName"),
                "phone", row.get("patientPhone")));
        view.put("provider", ClinicalSupport.map(
                "publicId", row.get("providerPublicId"),
                "nameAr", row.get("providerAr"),
                "nameEn", row.get("providerEn")));
        view.put("clinic", ClinicalSupport.map(
                "publicId", row.get("clinicPublicId"),
                "nameAr", row.get("clinicAr"),
                "nameEn", row.get("clinicEn")));
        return view;
    }

    private Map<String, Object> ticketView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("ticketNo", row.get("ticketNo"));
        view.put("priority", row.get("priority"));
        view.put("status", row.get("status"));
        view.put("checkedInAt", row.get("checkedInAt"));
        view.put("calledAt", row.get("calledAt"));
        view.put("servedAt", row.get("servedAt"));
        view.put("patientPublicId", row.get("patientPublicId"));
        view.put("patientName", row.get("patientName"));
        view.put("medicalNumber", row.get("medicalNumber"));
        view.put("appointmentPublicId", row.get("appointmentPublicId"));
        view.put("clinicNameAr", row.get("clinicAr"));
        return view;
    }

    private Map<String, Object> providerView(Map<String, Object> row) {
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "nameAr", row.get("displayNameAr"),
                "nameEn", row.get("displayNameEn"));
    }

    private Map<String, Object> clinicView(Map<String, Object> row) {
        return ClinicalSupport.map(
                "publicId", row.get("publicId"),
                "nameAr", row.get("nameAr"),
                "nameEn", row.get("nameEn"));
    }

    private Map<String, Object> requireAppointment(String publicId) {
        Map<String, Object> row = repository.appointmentByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("APPOINTMENT_NOT_FOUND",
                    "الموعد غير موجود.", "Appointment not found.");
        }
        return row;
    }

    private Map<String, Object> requirePatient(String publicId) {
        Map<String, Object> row = repository.patientByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        return row;
    }

    private Map<String, Object> requireProvider(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الطبيب مطلوب.", "Provider is required.");
        }
        Map<String, Object> row = repository.providerByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("PROVIDER_NOT_FOUND",
                    "الطبيب غير موجود.", "Provider not found.");
        }
        if (!repository.isProvider(Sql.longValue(row, "id", 0))) {
            throw AppException.unprocessable("NOT_A_PROVIDER",
                    "المستخدم المحدد ليس طبيبًا.", "The selected user is not a provider.");
        }
        return row;
    }

    private Map<String, Object> requireClinic(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "العيادة مطلوبة.", "Clinic is required.");
        }
        Map<String, Object> row = repository.clinicByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("CLINIC_NOT_FOUND",
                    "العيادة غير موجودة.", "Clinic not found.");
        }
        return row;
    }

    private Long resolveId(String publicId, boolean provider) {
        if (publicId == null || publicId.isBlank()) {
            return null;
        }
        Map<String, Object> row = provider
                ? repository.providerByPublicId(publicId)
                : repository.clinicByPublicId(publicId);
        return row == null ? null : Sql.longValue(row, "id");
    }

    private static LocalDate parseDate(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "التاريخ مطلوب.", "Date is required.");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة التاريخ غير صحيحة.", "Invalid date format.");
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة الوقت غير صحيحة.", "Invalid date-time format.");
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة الوقت غير صحيحة.", "Invalid time format.");
        }
    }

    private int intSetting(String key, int fallback) {
        return settings.findValue(key)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException ex) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private record SlotWindow(LocalDateTime end) {
    }

    private static boolean isDuplicateKey(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
