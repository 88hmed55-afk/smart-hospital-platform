import { api } from "../../shared/api/client";

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface Clinic {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  location: string | null;
  departmentAr: string | null;
  departmentEn: string | null;
}

export interface Provider {
  publicId: string;
  username: string;
  displayNameAr: string;
  displayNameEn: string | null;
  departmentId: number | null;
  departmentAr: string | null;
  departmentEn: string | null;
}

export interface ServiceItem {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  category: string;
}

export interface DrugItem {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  form: string | null;
  strength: string | null;
}

export interface ScheduleItem {
  publicId: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
  slotMinutes: number;
  capacity: number | null;
  validFrom: string | null;
  validTo: string | null;
  active: boolean;
  providerPublicId: string;
  providerAr: string | null;
  providerEn: string | null;
  clinicPublicId: string;
  clinicAr: string | null;
  clinicEn: string | null;
}

export interface Slot {
  start: string;
  end: string;
  available: boolean;
  reason?: string;
  appointmentPublicId?: string;
  patientName?: string;
  status?: string;
}

export interface SlotResult {
  date: string;
  provider: { publicId: string; nameAr: string | null; nameEn: string | null };
  clinic: { publicId: string; nameAr: string | null; nameEn: string | null };
  scheduleConfigured: boolean;
  slotMinutes: number;
  slots: Slot[];
}

export interface Appointment {
  publicId: string;
  slotStart: string;
  slotEnd: string;
  status: string;
  source: string | null;
  cancelReason: string | null;
  checkedInAt: string | null;
  version: number;
  patient: {
    publicId: string;
    medicalNumber: string;
    fullName: string;
    phone: string | null;
  };
  provider: { publicId: string; nameAr: string | null; nameEn: string | null };
  clinic: { publicId: string; nameAr: string | null; nameEn: string | null };
  queueTicket?: QueueTicket;
}

export interface QueueTicket {
  publicId: string;
  ticketNo: number;
  priority: string;
  status: string;
  checkedInAt: string | null;
  calledAt: string | null;
  servedAt: string | null;
  patientPublicId: string;
  patientName: string;
  medicalNumber: string;
  appointmentPublicId: string | null;
  clinicNameAr: string | null;
}

export interface WaitlistEntry {
  publicId: string;
  desiredFrom: string;
  desiredTo: string | null;
  priority: string;
  status: string;
  notes: string | null;
  patientPublicId: string;
  patientName: string;
  medicalNumber: string;
  providerPublicId: string;
  providerAr: string | null;
  clinicPublicId: string;
  clinicAr: string | null;
}

export interface AppointmentFilters {
  date?: string;
  provider?: string;
  clinic?: string;
  status?: string;
  patient?: string;
  page?: number;
  size?: number;
}

export const schedulingApi = {
  clinics() {
    return api<Clinic[]>("/scheduling/clinics");
  },
  providers() {
    return api<Provider[]>("/scheduling/providers");
  },
  services(category?: string) {
    const suffix = category ? `?category=${encodeURIComponent(category)}` : "";
    return api<ServiceItem[]>(`/scheduling/services${suffix}`);
  },
  drugs(query?: string) {
    const suffix = query ? `?q=${encodeURIComponent(query)}` : "";
    return api<DrugItem[]>(`/scheduling/drugs${suffix}`);
  },
  schedules(provider?: string) {
    const suffix = provider ? `?provider=${encodeURIComponent(provider)}` : "";
    return api<ScheduleItem[]>(`/scheduling/schedules${suffix}`);
  },
  createSchedule(body: {
    providerPublicId: string;
    clinicPublicId: string;
    dayOfWeek: number;
    startTime: string;
    endTime: string;
    slotMinutes: number;
    capacity?: number;
    validFrom?: string;
    validTo?: string;
  }) {
    return api<{ id: number; dayOfWeek: number; startTime: string; endTime: string; slotMinutes: number }>(
      "/scheduling/schedules",
      { method: "POST", body: JSON.stringify(body) },
    );
  },
  slots(provider: string, clinic: string, date: string) {
    const params = new URLSearchParams({ provider, clinic, date });
    return api<SlotResult>(`/scheduling/slots?${params.toString()}`);
  },
  appointments(filters: AppointmentFilters) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 50),
    });
    if (filters.date) params.set("date", filters.date);
    if (filters.provider) params.set("provider", filters.provider);
    if (filters.clinic) params.set("clinic", filters.clinic);
    if (filters.status) params.set("status", filters.status);
    if (filters.patient) params.set("patient", filters.patient);
    return api<PageResult<Appointment>>(`/scheduling/appointments?${params.toString()}`);
  },
  createAppointment(body: {
    patientPublicId: string;
    providerPublicId: string;
    clinicPublicId: string;
    slotStart: string;
    source?: string;
    overbook?: boolean;
  }) {
    return api<Appointment>("/scheduling/appointments", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  reschedule(publicId: string, slotStart: string, overbook?: boolean) {
    return api<Appointment>(`/scheduling/appointments/${publicId}/reschedule`, {
      method: "POST",
      body: JSON.stringify({ slotStart, overbook }),
    });
  },
  cancel(publicId: string, reason: string) {
    return api<Appointment>(`/scheduling/appointments/${publicId}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  checkIn(publicId: string, force?: boolean) {
    return api<Appointment>(`/scheduling/appointments/${publicId}/check-in`, {
      method: "POST",
      body: JSON.stringify({ force: Boolean(force) }),
    });
  },
  noShow(publicId: string, reason?: string) {
    return api<Appointment>(`/scheduling/appointments/${publicId}/no-show`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
  queue(clinic: string, date: string) {
    const params = new URLSearchParams({ clinic, date });
    return api<QueueTicket[]>(`/scheduling/queue?${params.toString()}`);
  },
  callTicket(publicId: string) {
    return api<QueueTicket>(`/scheduling/queue/${publicId}/call`, { method: "POST" });
  },
  serveTicket(publicId: string) {
    return api<QueueTicket>(`/scheduling/queue/${publicId}/serve`, { method: "POST" });
  },
  completeTicket(publicId: string) {
    return api<QueueTicket>(`/scheduling/queue/${publicId}/complete`, { method: "POST" });
  },
  waitlist(clinic?: string) {
    const suffix = clinic ? `?clinic=${encodeURIComponent(clinic)}` : "";
    return api<WaitlistEntry[]>(`/scheduling/waitlist${suffix}`);
  },
  addWaitlist(body: {
    patientPublicId: string;
    providerPublicId: string;
    clinicPublicId: string;
    desiredFrom: string;
    desiredTo?: string;
    priority?: string;
    notes?: string;
  }) {
    return api<{ publicId: string; status: string; desiredFrom: string; priority: string }>(
      "/scheduling/waitlist",
      { method: "POST", body: JSON.stringify(body) },
    );
  },
};

export const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;
