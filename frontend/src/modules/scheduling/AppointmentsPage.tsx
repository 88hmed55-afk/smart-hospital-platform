import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDate, formatDateTime, formatTime, todayInput } from "../../shared/ui/format";
import type { PatientSummary } from "../patients/patientsApi";
import {
  schedulingApi,
  type Appointment,
  type QueueTicket,
  type Slot,
} from "./schedulingApi";
import { clinicalApi } from "../clinical/clinicalApi";
import { statusTone } from "../patients/PatientsPage";

type TabId = "day" | "queue" | "waitlist" | "schedules";

const APPOINTMENT_STATUSES = ["BOOKED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"];
const SOURCES = ["RECEPTION", "PHONE", "WALK_IN", "PORTAL"];
const PRIORITIES = ["NORMAL", "URGENT", "EMERGENCY"];
const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];

export function AppointmentsPage() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const canCreate = hasPermission("SCH:CREATE");
  const canWrite = hasPermission("SCH:WRITE");
  const canCheckIn = hasPermission("SCH:CHECKIN");
  const canOverbook = hasPermission("SCH:OVERBOOK");
  const canOpenEncounter = hasPermission("EMR:CREATE");

  const [tab, setTab] = useState<TabId>("day");
  const [date, setDate] = useState(todayInput());
  const [clinicFilter, setClinicFilter] = useState("");
  const [providerFilter, setProviderFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [bookingOpen, setBookingOpen] = useState(false);
  const [bookingPatient, setBookingPatient] = useState<PatientSummary | null>(null);
  const [bookingClinic, setBookingClinic] = useState("");
  const [bookingProvider, setBookingProvider] = useState("");
  const [bookingDate, setBookingDate] = useState(todayInput());
  const [bookingSlot, setBookingSlot] = useState<Slot | null>(null);
  const [bookingSource, setBookingSource] = useState("RECEPTION");
  const [bookingOverbook, setBookingOverbook] = useState(false);
  const [bookingError, setBookingError] = useState<string | null>(null);

  const [rescheduleTarget, setRescheduleTarget] = useState<Appointment | null>(null);
  const [rescheduleDate, setRescheduleDate] = useState(todayInput());
  const [rescheduleSlot, setRescheduleSlot] = useState<Slot | null>(null);
  const [rescheduleError, setRescheduleError] = useState<string | null>(null);

  const [reasonAction, setReasonAction] = useState<
    { type: "cancel" | "noShow"; appointment: Appointment } | null
  >(null);
  const [reason, setReason] = useState("");

  const [queueClinic, setQueueClinic] = useState("");
  const [queueDate, setQueueDate] = useState(todayInput());
  const [waitlistClinic, setWaitlistClinic] = useState("");
  const [waitlistOpen, setWaitlistOpen] = useState(false);
  const [waitlistPatient, setWaitlistPatient] = useState<PatientSummary | null>(null);
  const [waitlistProvider, setWaitlistProvider] = useState("");
  const [waitlistClinicValue, setWaitlistClinicValue] = useState("");
  const [waitlistFrom, setWaitlistFrom] = useState(todayInput());
  const [waitlistTo, setWaitlistTo] = useState("");
  const [waitlistPriority, setWaitlistPriority] = useState("NORMAL");
  const [waitlistNotes, setWaitlistNotes] = useState("");

  const [scheduleProvider, setScheduleProvider] = useState("");
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [scheduleForm, setScheduleForm] = useState({
    providerPublicId: "",
    clinicPublicId: "",
    dayOfWeek: 1,
    startTime: "09:00",
    endTime: "13:00",
    slotMinutes: 20,
    capacity: "",
  });

  const clinics = useQuery({
    queryKey: ["scheduling-clinics"],
    queryFn: schedulingApi.clinics,
    staleTime: 300_000,
  });
  const providers = useQuery({
    queryKey: ["scheduling-providers"],
    queryFn: schedulingApi.providers,
    staleTime: 300_000,
  });

  const clinicList = clinics.data ?? [];
  const providerList = providers.data ?? [];

  useEffect(() => {
    if (!bookingClinic && clinicList.length > 0) {
      setBookingClinic(clinicList[0].publicId);
    }
  }, [bookingClinic, clinicList]);

  useEffect(() => {
    if (!bookingProvider && providerList.length > 0) {
      setBookingProvider(providerList[0].publicId);
    }
  }, [bookingProvider, providerList]);

  useEffect(() => {
    if (!queueClinic && clinicList.length > 0) {
      setQueueClinic(clinicList[0].publicId);
    }
  }, [queueClinic, clinicList]);

  const appointments = useQuery({
    queryKey: ["appointments", date, clinicFilter, providerFilter, statusFilter],
    queryFn: () =>
      schedulingApi.appointments({
        date,
        clinic: clinicFilter || undefined,
        provider: providerFilter || undefined,
        status: statusFilter || undefined,
        page: 0,
        size: 100,
      }),
  });

  const bookingSlots = useQuery({
    queryKey: ["booking-slots", bookingProvider, bookingClinic, bookingDate],
    queryFn: () => schedulingApi.slots(bookingProvider, bookingClinic, bookingDate),
    enabled:
      bookingOpen && bookingProvider.length > 0 && bookingClinic.length > 0,
  });

  const rescheduleSlots = useQuery({
    queryKey: [
      "reschedule-slots",
      rescheduleTarget?.provider.publicId,
      rescheduleTarget?.clinic.publicId,
      rescheduleDate,
    ],
    queryFn: () =>
      schedulingApi.slots(
        rescheduleTarget?.provider.publicId ?? "",
        rescheduleTarget?.clinic.publicId ?? "",
        rescheduleDate,
      ),
    enabled: rescheduleTarget !== null,
  });

  const queue = useQuery({
    queryKey: ["queue", queueClinic, queueDate],
    queryFn: () => schedulingApi.queue(queueClinic, queueDate),
    enabled: queueClinic.length > 0,
  });

  const waitlist = useQuery({
    queryKey: ["waitlist", waitlistClinic],
    queryFn: () => schedulingApi.waitlist(waitlistClinic || undefined),
  });

  const schedules = useQuery({
    queryKey: ["schedules", scheduleProvider],
    queryFn: () => schedulingApi.schedules(scheduleProvider || undefined),
  });

  function invalidateDay() {
    void queryClient.invalidateQueries({ queryKey: ["appointments"] });
    void queryClient.invalidateQueries({ queryKey: ["queue"] });
    void queryClient.invalidateQueries({ queryKey: ["waitlist"] });
    void queryClient.invalidateQueries({ queryKey: ["booking-slots"] });
    void queryClient.invalidateQueries({ queryKey: ["reschedule-slots"] });
  }

  const createBooking = useMutation({
    mutationFn: () =>
      schedulingApi.createAppointment({
        patientPublicId: bookingPatient?.publicId ?? "",
        providerPublicId: bookingProvider,
        clinicPublicId: bookingClinic,
        slotStart: bookingSlot?.start ?? "",
        source: bookingSource,
        overbook: bookingOverbook,
      }),
    onSuccess: () => {
      invalidateDay();
      setBookingOpen(false);
      setBookingPatient(null);
      setBookingSlot(null);
      setBookingOverbook(false);
    },
    onError: (cause) =>
      setBookingError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const doReschedule = useMutation({
    mutationFn: () =>
      schedulingApi.reschedule(
        rescheduleTarget?.publicId ?? "",
        rescheduleSlot?.start ?? "",
        false,
      ),
    onSuccess: () => {
      invalidateDay();
      setRescheduleTarget(null);
      setRescheduleSlot(null);
    },
    onError: (cause) =>
      setRescheduleError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const checkIn = useMutation({
    mutationFn: (appointment: Appointment) =>
      schedulingApi.checkIn(appointment.publicId, false),
    onSuccess: invalidateDay,
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const cancelAppointment = useMutation({
    mutationFn: () =>
      schedulingApi.cancel(reasonAction?.appointment.publicId ?? "", reason.trim()),
    onSuccess: () => {
      invalidateDay();
      setReasonAction(null);
      setReason("");
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const markNoShow = useMutation({
    mutationFn: () =>
      schedulingApi.noShow(reasonAction?.appointment.publicId ?? "", reason.trim() || undefined),
    onSuccess: () => {
      invalidateDay();
      setReasonAction(null);
      setReason("");
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const startEncounter = useMutation({
    mutationFn: (appointment: Appointment) =>
      clinicalApi.create({
        patientPublicId: appointment.patient.publicId,
        appointmentPublicId: appointment.publicId,
      }),
    onSuccess: (workspace) => navigate(`/app/clinical/${workspace.encounter.publicId}`),
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const queueCall = useMutation({
    mutationFn: (ticket: QueueTicket) => schedulingApi.callTicket(ticket.publicId),
    onSuccess: invalidateDay,
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });
  const queueServe = useMutation({
    mutationFn: (ticket: QueueTicket) => schedulingApi.serveTicket(ticket.publicId),
    onSuccess: invalidateDay,
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });
  const queueComplete = useMutation({
    mutationFn: (ticket: QueueTicket) => schedulingApi.completeTicket(ticket.publicId),
    onSuccess: invalidateDay,
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const addWaitlist = useMutation({
    mutationFn: () =>
      schedulingApi.addWaitlist({
        patientPublicId: waitlistPatient?.publicId ?? "",
        providerPublicId: waitlistProvider,
        clinicPublicId: waitlistClinicValue,
        desiredFrom: waitlistFrom,
        desiredTo: waitlistTo || undefined,
        priority: waitlistPriority,
        notes: waitlistNotes.trim() || undefined,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["waitlist"] });
      setWaitlistOpen(false);
      setWaitlistPatient(null);
      setWaitlistNotes("");
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const createSchedule = useMutation({
    mutationFn: () =>
      schedulingApi.createSchedule({
        providerPublicId: scheduleForm.providerPublicId,
        clinicPublicId: scheduleForm.clinicPublicId,
        dayOfWeek: scheduleForm.dayOfWeek,
        startTime: scheduleForm.startTime,
        endTime: scheduleForm.endTime,
        slotMinutes: scheduleForm.slotMinutes,
        capacity: scheduleForm.capacity ? Number(scheduleForm.capacity) : undefined,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["schedules"] });
      void queryClient.invalidateQueries({ queryKey: ["booking-slots"] });
      setScheduleOpen(false);
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  function openBooking() {
    setBookingPatient(null);
    setBookingSlot(null);
    setBookingOverbook(false);
    setBookingError(null);
    setBookingDate(date);
    setBookingOpen(true);
  }

  const availableSlots = useMemo(
    () => (bookingSlots.data?.slots ?? []).filter((slot) => slot.available),
    [bookingSlots.data],
  );
  const rescheduleAvailable = useMemo(
    () => (rescheduleSlots.data?.slots ?? []).filter((slot) => slot.available),
    [rescheduleSlots.data],
  );

  function providerName(provider: { nameAr: string | null; nameEn: string | null }): string {
    return (locale === "ar" ? provider.nameAr : provider.nameEn) ?? "—";
  }

  function clinicName(clinic: { nameAr: string | null; nameEn: string | null }): string {
    return (locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? "—";
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("scheduling.title")}</h1>
          <p className="muted">{t("scheduling.subtitle")}</p>
        </div>
        <div className="header-actions">
          {canCreate && (
            <button type="button" className="button" onClick={openBooking}>
              {t("scheduling.book")}
            </button>
          )}
        </div>
      </header>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="tabs" role="tablist">
        {(
          [
            ["day", "scheduling.tab.day"],
            ["queue", "scheduling.tab.queue"],
            ["waitlist", "scheduling.tab.waitlist"],
            ["schedules", "scheduling.tab.schedules"],
          ] as [TabId, string][]
        ).map(([id, key]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className={`tab${tab === id ? " is-active" : ""}`}
            onClick={() => setTab(id)}
          >
            {t(key)}
          </button>
        ))}
      </div>

      {tab === "day" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <div className="filter-row">
                <label className="field-inline">
                  <span>{t("scheduling.date")}</span>
                  <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
                </label>
                <label className="field-inline">
                  <span>{t("scheduling.clinic")}</span>
                  <select value={clinicFilter} onChange={(event) => setClinicFilter(event.target.value)}>
                    <option value="">{t("common.all")}</option>
                    {clinicList.map((clinic) => (
                      <option key={clinic.publicId} value={clinic.publicId}>
                        {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field-inline">
                  <span>{t("scheduling.provider")}</span>
                  <select value={providerFilter} onChange={(event) => setProviderFilter(event.target.value)}>
                    <option value="">{t("common.all")}</option>
                    {providerList.map((provider) => (
                      <option key={provider.publicId} value={provider.publicId}>
                        {(locale === "ar" ? provider.displayNameAr : provider.displayNameEn) ??
                          provider.username}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field-inline">
                  <span>{t("common.status")}</span>
                  <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
                    <option value="">{t("common.all")}</option>
                    {APPOINTMENT_STATUSES.map((item) => (
                      <option key={item} value={item}>
                        {t(`appointmentStatus.${item}`)}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <span className="muted">
                {appointments.data ? `${appointments.data.total} ${t("scheduling.appointmentsCount")}` : ""}
              </span>
            </div>

            {appointments.isPending ? (
              <p className="hint">{t("common.loading")}</p>
            ) : appointments.isError ? (
              <p className="field-error">{t("common.loadError")}</p>
            ) : appointments.data.items.length === 0 ? (
              <p className="empty-inline">{t("scheduling.noAppointments")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("scheduling.time")}</th>
                      <th>{t("scheduling.patient")}</th>
                      <th>{t("scheduling.clinic")}</th>
                      <th>{t("scheduling.provider")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {appointments.data.items.map((appointment) => (
                      <tr key={appointment.publicId}>
                        <td className="mono">{formatTime(appointment.slotStart, locale)}</td>
                        <td>
                          <strong>{appointment.patient.fullName}</strong>
                          <div className="muted mono">{appointment.patient.medicalNumber}</div>
                        </td>
                        <td>{clinicName(appointment.clinic)}</td>
                        <td>{providerName(appointment.provider)}</td>
                        <td>
                          <span className={`badge badge-${statusTone(appointment.status)}`}>
                            {t(`appointmentStatus.${appointment.status}`)}
                          </span>
                        </td>
                        <td className="row-actions">
                          {canCheckIn && appointment.status === "BOOKED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => checkIn.mutate(appointment)}
                              disabled={checkIn.isPending}
                            >
                              {t("scheduling.checkIn")}
                            </button>
                          )}
                          {canOpenEncounter &&
                            (appointment.status === "CHECKED_IN" ||
                              appointment.status === "BOOKED") && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() => startEncounter.mutate(appointment)}
                                disabled={startEncounter.isPending}
                              >
                                {t("scheduling.startEncounter")}
                              </button>
                            )}
                          {canWrite &&
                            (appointment.status === "BOOKED" ||
                              appointment.status === "CHECKED_IN") && (
                              <>
                                <button
                                  type="button"
                                  className="button button-ghost button-sm"
                                  onClick={() => {
                                    setRescheduleTarget(appointment);
                                    setRescheduleDate(appointment.slotStart.slice(0, 10));
                                    setRescheduleSlot(null);
                                    setRescheduleError(null);
                                  }}
                                >
                                  {t("scheduling.reschedule")}
                                </button>
                                <button
                                  type="button"
                                  className="button button-ghost button-sm"
                                  onClick={() => {
                                    setReason("");
                                    setReasonAction({ type: "cancel", appointment });
                                  }}
                                >
                                  {t("scheduling.cancel")}
                                </button>
                              </>
                            )}
                          {canWrite && appointment.status === "BOOKED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => {
                                setReason("");
                                setReasonAction({ type: "noShow", appointment });
                              }}
                            >
                              {t("scheduling.noShow")}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "queue" && (
        <div className="tab-content">
          <section className="card">
            <div className="filter-row">
              <label className="field-inline">
                <span>{t("scheduling.clinic")}</span>
                <select value={queueClinic} onChange={(event) => setQueueClinic(event.target.value)}>
                  {clinicList.map((clinic) => (
                    <option key={clinic.publicId} value={clinic.publicId}>
                      {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field-inline">
                <span>{t("scheduling.date")}</span>
                <input
                  type="date"
                  value={queueDate}
                  onChange={(event) => setQueueDate(event.target.value)}
                />
              </label>
              <button
                type="button"
                className="button button-ghost button-sm"
                onClick={() => void queue.refetch()}
              >
                {t("health.refresh")}
              </button>
            </div>

            {queue.isPending ? (
              <p className="hint">{t("common.loading")}</p>
            ) : !queue.data || queue.data.length === 0 ? (
              <p className="empty-inline">{t("scheduling.noQueue")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("scheduling.ticket")}</th>
                      <th>{t("scheduling.patient")}</th>
                      <th>{t("scheduling.priority")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("scheduling.checkedInAt")}</th>
                      <th>{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {queue.data.map((ticket) => (
                      <tr key={ticket.publicId}>
                        <td className="mono">#{ticket.ticketNo}</td>
                        <td>
                          <strong>{ticket.patientName}</strong>
                          <div className="muted mono">{ticket.medicalNumber}</div>
                        </td>
                        <td>{t(`priority.${ticket.priority}`)}</td>
                        <td>
                          <span className={`badge badge-${statusTone(ticket.status)}`}>
                            {t(`queueStatus.${ticket.status}`)}
                          </span>
                        </td>
                        <td>{formatDateTime(ticket.checkedInAt, locale)}</td>
                        <td className="row-actions">
                          {canCheckIn && ticket.status === "WAITING" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => queueCall.mutate(ticket)}
                            >
                              {t("scheduling.call")}
                            </button>
                          )}
                          {canCheckIn && ticket.status === "CALLED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => queueServe.mutate(ticket)}
                            >
                              {t("scheduling.serve")}
                            </button>
                          )}
                          {canCheckIn && ticket.status === "IN_SERVICE" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => queueComplete.mutate(ticket)}
                            >
                              {t("scheduling.complete")}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "waitlist" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field-inline">
                <span>{t("scheduling.clinic")}</span>
                <select
                  value={waitlistClinic}
                  onChange={(event) => setWaitlistClinic(event.target.value)}
                >
                  <option value="">{t("common.all")}</option>
                  {clinicList.map((clinic) => (
                    <option key={clinic.publicId} value={clinic.publicId}>
                      {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                    </option>
                  ))}
                </select>
              </label>
              {canCreate && (
                <button
                  type="button"
                  className="button button-ghost button-sm"
                  onClick={() => {
                    setWaitlistPatient(null);
                    setWaitlistProvider(providerList[0]?.publicId ?? "");
                    setWaitlistClinicValue(clinicList[0]?.publicId ?? "");
                    setWaitlistFrom(todayInput());
                    setWaitlistTo("");
                    setWaitlistPriority("NORMAL");
                    setWaitlistNotes("");
                    setWaitlistOpen(true);
                  }}
                >
                  {t("scheduling.addWaitlist")}
                </button>
              )}
            </div>

            {waitlist.isPending ? (
              <p className="hint">{t("common.loading")}</p>
            ) : !waitlist.data || waitlist.data.length === 0 ? (
              <p className="empty-inline">{t("scheduling.noWaitlist")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("scheduling.patient")}</th>
                      <th>{t("scheduling.provider")}</th>
                      <th>{t("scheduling.clinic")}</th>
                      <th>{t("scheduling.desiredFrom")}</th>
                      <th>{t("scheduling.priority")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {waitlist.data.map((entry) => (
                      <tr key={entry.publicId}>
                        <td>
                          <strong>{entry.patientName}</strong>
                          <div className="muted mono">{entry.medicalNumber}</div>
                        </td>
                        <td>{entry.providerAr ?? "—"}</td>
                        <td>{entry.clinicAr ?? "—"}</td>
                        <td>
                          {formatDate(entry.desiredFrom, locale)}
                          {entry.desiredTo ? ` → ${formatDate(entry.desiredTo, locale)}` : ""}
                        </td>
                        <td>{t(`priority.${entry.priority}`)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "schedules" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field-inline">
                <span>{t("scheduling.provider")}</span>
                <select
                  value={scheduleProvider}
                  onChange={(event) => setScheduleProvider(event.target.value)}
                >
                  <option value="">{t("common.all")}</option>
                  {providerList.map((provider) => (
                    <option key={provider.publicId} value={provider.publicId}>
                      {(locale === "ar" ? provider.displayNameAr : provider.displayNameEn) ??
                        provider.username}
                    </option>
                  ))}
                </select>
              </label>
              {hasPermission("SCH:WRITE") && (
                <button
                  type="button"
                  className="button button-ghost button-sm"
                  onClick={() => {
                    setScheduleForm({
                      providerPublicId: providerList[0]?.publicId ?? "",
                      clinicPublicId: clinicList[0]?.publicId ?? "",
                      dayOfWeek: 1,
                      startTime: "09:00",
                      endTime: "13:00",
                      slotMinutes: 20,
                      capacity: "",
                    });
                    setScheduleOpen(true);
                  }}
                >
                  {t("scheduling.addSchedule")}
                </button>
              )}
            </div>

            {schedules.isPending ? (
              <p className="hint">{t("common.loading")}</p>
            ) : !schedules.data || schedules.data.length === 0 ? (
              <p className="empty-inline">{t("scheduling.noSchedules")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("scheduling.provider")}</th>
                      <th>{t("scheduling.clinic")}</th>
                      <th>{t("scheduling.weekday")}</th>
                      <th>{t("scheduling.hours")}</th>
                      <th>{t("scheduling.slotMinutes")}</th>
                      <th>{t("scheduling.capacity")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {schedules.data.map((schedule) => (
                      <tr key={schedule.publicId}>
                        <td>
                          {(locale === "ar" ? schedule.providerAr : schedule.providerEn) ?? "—"}
                        </td>
                        <td>{(locale === "ar" ? schedule.clinicAr : schedule.clinicEn) ?? "—"}</td>
                        <td>{t(`weekday.${schedule.dayOfWeek}`)}</td>
                        <td className="mono">
                          {schedule.startTime.slice(0, 5)} – {schedule.endTime.slice(0, 5)}
                        </td>
                        <td>{schedule.slotMinutes}</td>
                        <td>{schedule.capacity ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {bookingOpen && (
        <Modal title={t("scheduling.bookTitle")} onClose={() => setBookingOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              setBookingError(null);
              if (!bookingPatient) {
                setBookingError(t("scheduling.patientRequired"));
                return;
              }
              if (!bookingSlot) {
                setBookingError(t("scheduling.slotRequired"));
                return;
              }
              createBooking.mutate();
            }}
          >
            {bookingError && <div className="alert alert-danger">{bookingError}</div>}
            <div className="field">
              <span>{t("scheduling.patient")} *</span>
              <PatientPicker selected={bookingPatient} onSelect={setBookingPatient} />
            </div>
            <div className="form-grid">
              <label className="field">
                <span>{t("scheduling.clinic")} *</span>
                <select
                  value={bookingClinic}
                  onChange={(event) => {
                    setBookingClinic(event.target.value);
                    setBookingSlot(null);
                  }}
                  required
                >
                  {clinicList.map((clinic) => (
                    <option key={clinic.publicId} value={clinic.publicId}>
                      {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.provider")} *</span>
                <select
                  value={bookingProvider}
                  onChange={(event) => {
                    setBookingProvider(event.target.value);
                    setBookingSlot(null);
                  }}
                  required
                >
                  {providerList.map((provider) => (
                    <option key={provider.publicId} value={provider.publicId}>
                      {(locale === "ar" ? provider.displayNameAr : provider.displayNameEn) ??
                        provider.username}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.date")} *</span>
                <input
                  type="date"
                  value={bookingDate}
                  onChange={(event) => {
                    setBookingDate(event.target.value);
                    setBookingSlot(null);
                  }}
                  required
                />
              </label>
              <label className="field">
                <span>{t("scheduling.source")}</span>
                <select
                  value={bookingSource}
                  onChange={(event) => setBookingSource(event.target.value)}
                >
                  {SOURCES.map((source) => (
                    <option key={source} value={source}>
                      {t(`source.${source}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="field">
              <span>{t("scheduling.availableSlots")}</span>
              {bookingSlots.isPending ? (
                <p className="hint">{t("common.loading")}</p>
              ) : !bookingSlots.data?.scheduleConfigured ? (
                <p className="notice">{t("scheduling.noScheduleConfigured")}</p>
              ) : availableSlots.length === 0 ? (
                <p className="empty-inline">{t("scheduling.noSlots")}</p>
              ) : (
                <div className="slot-grid">
                  {availableSlots.map((slot) => (
                    <button
                      key={slot.start}
                      type="button"
                      className={`slot${bookingSlot?.start === slot.start ? " is-selected" : ""}`}
                      onClick={() => setBookingSlot(slot)}
                    >
                      {formatTime(slot.start, locale)}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {canOverbook && (
              <label className="field-inline">
                <input
                  type="checkbox"
                  checked={bookingOverbook}
                  onChange={(event) => setBookingOverbook(event.target.checked)}
                />
                <span>{t("scheduling.overbook")}</span>
              </label>
            )}

            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setBookingOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className="button"
                disabled={createBooking.isPending || !bookingPatient || !bookingSlot}
              >
                {createBooking.isPending ? t("common.saving") : t("scheduling.book")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {rescheduleTarget && (
        <Modal title={t("scheduling.rescheduleTitle")} onClose={() => setRescheduleTarget(null)}>
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (rescheduleSlot) {
                doReschedule.mutate();
              }
            }}
          >
            {rescheduleError && <div className="alert alert-danger">{rescheduleError}</div>}
            <p className="muted">
              {rescheduleTarget.patient.fullName} · {clinicName(rescheduleTarget.clinic)} ·{" "}
              {providerName(rescheduleTarget.provider)}
            </p>
            <label className="field">
              <span>{t("scheduling.date")}</span>
              <input
                type="date"
                value={rescheduleDate}
                onChange={(event) => {
                  setRescheduleDate(event.target.value);
                  setRescheduleSlot(null);
                }}
              />
            </label>
            <div className="field">
              <span>{t("scheduling.availableSlots")}</span>
              {rescheduleSlots.isPending ? (
                <p className="hint">{t("common.loading")}</p>
              ) : rescheduleAvailable.length === 0 ? (
                <p className="empty-inline">{t("scheduling.noSlots")}</p>
              ) : (
                <div className="slot-grid">
                  {rescheduleAvailable.map((slot) => (
                    <button
                      key={slot.start}
                      type="button"
                      className={`slot${rescheduleSlot?.start === slot.start ? " is-selected" : ""}`}
                      onClick={() => setRescheduleSlot(slot)}
                    >
                      {formatTime(slot.start, locale)}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setRescheduleTarget(null)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={!rescheduleSlot || doReschedule.isPending}>
                {doReschedule.isPending ? t("common.saving") : t("scheduling.reschedule")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {reasonAction && (
        <Modal
          title={
            reasonAction.type === "cancel"
              ? t("scheduling.cancelTitle")
              : t("scheduling.noShowTitle")
          }
          onClose={() => setReasonAction(null)}
        >
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (reasonAction.type === "cancel") {
                cancelAppointment.mutate();
              } else {
                markNoShow.mutate();
              }
            }}
          >
            <p className="muted">{reasonAction.appointment.patient.fullName}</p>
            <label className="field">
              <span>
                {reasonAction.type === "cancel" ? t("scheduling.cancelReason") : t("scheduling.noShowReason")}
                {reasonAction.type === "cancel" ? " *" : ""}
              </span>
              <textarea
                rows={3}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                required={reasonAction.type === "cancel"}
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setReasonAction(null)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className="button button-danger"
                disabled={cancelAppointment.isPending || markNoShow.isPending}
              >
                {cancelAppointment.isPending || markNoShow.isPending
                  ? t("common.saving")
                  : t("common.confirm")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {waitlistOpen && (
        <Modal title={t("scheduling.addWaitlist")} onClose={() => setWaitlistOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (waitlistPatient) {
                addWaitlist.mutate();
              }
            }}
          >
            <div className="field">
              <span>{t("scheduling.patient")} *</span>
              <PatientPicker selected={waitlistPatient} onSelect={setWaitlistPatient} />
            </div>
            <div className="form-grid">
              <label className="field">
                <span>{t("scheduling.provider")} *</span>
                <select
                  value={waitlistProvider}
                  onChange={(event) => setWaitlistProvider(event.target.value)}
                  required
                >
                  {providerList.map((provider) => (
                    <option key={provider.publicId} value={provider.publicId}>
                      {(locale === "ar" ? provider.displayNameAr : provider.displayNameEn) ??
                        provider.username}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.clinic")} *</span>
                <select
                  value={waitlistClinicValue}
                  onChange={(event) => setWaitlistClinicValue(event.target.value)}
                  required
                >
                  {clinicList.map((clinic) => (
                    <option key={clinic.publicId} value={clinic.publicId}>
                      {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.desiredFrom")} *</span>
                <input
                  type="date"
                  value={waitlistFrom}
                  onChange={(event) => setWaitlistFrom(event.target.value)}
                  required
                />
              </label>
              <label className="field">
                <span>{t("scheduling.desiredTo")}</span>
                <input
                  type="date"
                  value={waitlistTo}
                  onChange={(event) => setWaitlistTo(event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("scheduling.priority")}</span>
                <select
                  value={waitlistPriority}
                  onChange={(event) => setWaitlistPriority(event.target.value)}
                >
                  {PRIORITIES.map((priority) => (
                    <option key={priority} value={priority}>
                      {t(`priority.${priority}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="field">
              <span>{t("scheduling.notes")}</span>
              <textarea
                rows={2}
                value={waitlistNotes}
                onChange={(event) => setWaitlistNotes(event.target.value)}
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setWaitlistOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className="button"
                disabled={!waitlistPatient || addWaitlist.isPending}
              >
                {addWaitlist.isPending ? t("common.saving") : t("common.create")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {scheduleOpen && (
        <Modal title={t("scheduling.addSchedule")} onClose={() => setScheduleOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              createSchedule.mutate();
            }}
          >
            <div className="form-grid">
              <label className="field">
                <span>{t("scheduling.provider")} *</span>
                <select
                  value={scheduleForm.providerPublicId}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, providerPublicId: event.target.value })
                  }
                  required
                >
                  {providerList.map((provider) => (
                    <option key={provider.publicId} value={provider.publicId}>
                      {(locale === "ar" ? provider.displayNameAr : provider.displayNameEn) ??
                        provider.username}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.clinic")} *</span>
                <select
                  value={scheduleForm.clinicPublicId}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, clinicPublicId: event.target.value })
                  }
                  required
                >
                  {clinicList.map((clinic) => (
                    <option key={clinic.publicId} value={clinic.publicId}>
                      {(locale === "ar" ? clinic.nameAr : clinic.nameEn) ?? clinic.code}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.weekday")}</span>
                <select
                  value={scheduleForm.dayOfWeek}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, dayOfWeek: Number(event.target.value) })
                  }
                >
                  {WEEKDAYS.map((day) => (
                    <option key={day} value={day}>
                      {t(`weekday.${day}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("scheduling.startTime")}</span>
                <input
                  type="time"
                  value={scheduleForm.startTime}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, startTime: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("scheduling.endTime")}</span>
                <input
                  type="time"
                  value={scheduleForm.endTime}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, endTime: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("scheduling.slotMinutes")}</span>
                <input
                  type="number"
                  min={5}
                  max={240}
                  value={scheduleForm.slotMinutes}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, slotMinutes: Number(event.target.value) })
                  }
                />
              </label>
              <label className="field">
                <span>{t("scheduling.capacity")}</span>
                <input
                  type="number"
                  min={1}
                  value={scheduleForm.capacity}
                  onChange={(event) =>
                    setScheduleForm({ ...scheduleForm, capacity: event.target.value })
                  }
                />
              </label>
            </div>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setScheduleOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={createSchedule.isPending}>
                {createSchedule.isPending ? t("common.saving") : t("common.create")}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
