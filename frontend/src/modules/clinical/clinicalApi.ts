import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface EncounterSummary {
  publicId: string;
  patientPublicId: string;
  medicalNumber: string;
  patientName: string;
  patientGender: string;
  patientBirthDate: string | null;
  age: number | null;
  providerPublicId: string;
  providerAr: string | null;
  providerEn: string | null;
  departmentAr: string | null;
  departmentEn: string | null;
  appointmentPublicId: string | null;
  type: string;
  status: string;
  chiefComplaint: string | null;
  startedAt: string | null;
  closedAt: string | null;
  version: number;
}

export interface EncounterPatient {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string;
  birthDate: string | null;
  age: number | null;
  phone: string | null;
  bloodGroup: string | null;
}

export interface VitalRow {
  publicId: string;
  temperatureC: number | string | null;
  heartRate: number | null;
  respiratoryRate: number | null;
  systolic: number | null;
  diastolic: number | null;
  spo2: number | null;
  weightKg: number | string | null;
  heightCm: number | string | null;
  painScore: number | null;
  recordedAt: string | null;
  recordedByAr?: string | null;
  recordedByEn?: string | null;
}

export interface DiagnosisRow {
  publicId: string;
  code: string | null;
  description: string;
  diagnosisType: string;
  primaryFlag: number | boolean | null;
  createdAt: string | null;
}

export interface NoteRow {
  publicId: string;
  noteType: string;
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
  authorAr: string | null;
  authorEn: string | null;
  versionNo: number | null;
  contentJson: string | null;
  contentText: string | null;
  signedAt: string | null;
  signedByAr: string | null;
  signedByEn: string | null;
}

export interface NoteVersionRow {
  publicId: string;
  versionNo: number;
  contentJson: string | null;
  contentText: string | null;
  amendReason: string | null;
  signedAt: string | null;
  createdAt: string | null;
}

export interface OrderRow {
  publicId: string;
  orderType: string;
  status: string;
  priority: string;
  notes: string | null;
  createdAt: string | null;
  orderedByAr: string | null;
  orderedByEn: string | null;
  itemsAr: string | null;
  itemsEn: string | null;
}

export interface PrescriptionSummaryRow {
  publicId: string;
  status: string;
  issuedAt: string | null;
  notes: string | null;
  createdAt: string | null;
  prescribedByAr: string | null;
  prescribedByEn: string | null;
  itemCount: number;
}

export interface PrescriptionItem {
  publicId: string;
  drugPublicId: string;
  drugCode: string;
  drugAr: string;
  drugEn: string | null;
  form: string | null;
  strength: string | null;
  dose: string;
  route: string;
  frequency: string;
  durationDays: number | null;
  quantity: number;
  instructions: string | null;
}

export interface Prescription {
  publicId: string;
  status: string;
  issuedAt: string | null;
  notes: string | null;
  version: number;
  patient: {
    publicId: string;
    medicalNumber: string;
    fullName: string;
    gender: string;
    birthDate: string | null;
    age: number | null;
  };
  prescribedBy: {
    publicId: string;
    nameAr: string | null;
    nameEn: string | null;
  };
  encounterPublicId: string;
  items: PrescriptionItem[];
}

export interface ChargeRow {
  publicId: string;
  performedAt: string | null;
  priceSnapshot: number | string | null;
  status: string;
  sourceType: string;
  serviceCode: string;
  serviceAr: string | null;
  serviceEn: string | null;
  performedByAr: string | null;
  performedByEn: string | null;
}

export interface EncounterHistoryRow {
  publicId: string;
  startedAt: string | null;
  type: string;
  status: string;
  chiefComplaint: string | null;
  providerAr: string | null;
  providerEn: string | null;
}

export interface EncounterWorkspace {
  encounter: EncounterSummary;
  editable: boolean;
  patient: EncounterPatient;
  vitals: VitalRow[];
  latestVitals: VitalRow | null;
  diagnoses: DiagnosisRow[];
  notes: NoteRow[];
  orders: OrderRow[];
  prescriptions: PrescriptionSummaryRow[];
  charges: ChargeRow[];
  allergies: {
    publicId: string;
    substance: string;
    allergyType: string;
    severity: string;
    reaction: string | null;
  }[];
  conditions: {
    publicId: string;
    description: string;
    onsetDate: string | null;
    status: string;
  }[];
  history: EncounterHistoryRow[];
}

export interface PrintPayload {
  encounter: EncounterSummary;
  patient: EncounterPatient;
  vitals: VitalRow | null;
  diagnoses: DiagnosisRow[];
  notes: NoteRow[];
  orders: OrderRow[];
  prescriptions: Prescription[];
  allergies: {
    publicId: string;
    substance: string;
    allergyType: string;
    severity: string;
    reaction: string | null;
  }[];
  charges: ChargeRow[];
  printedAt: string;
  printedBy: { nameAr: string | null; nameEn: string | null };
}

export interface VitalsInput {
  temperatureC?: number | null;
  heartRate?: number | null;
  respiratoryRate?: number | null;
  systolic?: number | null;
  diastolic?: number | null;
  spo2?: number | null;
  weightKg?: number | null;
  heightCm?: number | null;
  painScore?: number | null;
}

export interface PrescriptionItemInput {
  drugPublicId: string;
  dose: string;
  route: string;
  frequency: string;
  durationDays?: number | null;
  quantity?: number;
  instructions?: string | null;
}

export interface EncounterFilters {
  q?: string;
  status?: string;
  provider?: string;
  patient?: string;
  date?: string;
  page?: number;
  size?: number;
}

export const clinicalApi = {
  search(filters: EncounterFilters) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.q) params.set("q", filters.q);
    if (filters.status) params.set("status", filters.status);
    if (filters.provider) params.set("provider", filters.provider);
    if (filters.patient) params.set("patient", filters.patient);
    if (filters.date) params.set("date", filters.date);
    return api<PageResult<EncounterSummary>>(`/encounters?${params.toString()}`);
  },
  create(body: {
    patientPublicId: string;
    appointmentPublicId?: string;
    providerPublicId?: string;
    type?: string;
    chiefComplaint?: string;
  }) {
    return api<EncounterWorkspace>("/encounters", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  get(publicId: string) {
    return api<EncounterWorkspace>(`/encounters/${publicId}`);
  },
  update(
    publicId: string,
    body: { chiefComplaint?: string | null; type?: string; version: number },
  ) {
    return api<EncounterWorkspace>(`/encounters/${publicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  close(publicId: string, body: { reason?: string; version: number }) {
    return api<EncounterWorkspace>(`/encounters/${publicId}/close`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  cancel(publicId: string, body: { reason?: string; version: number }) {
    return api<EncounterWorkspace>(`/encounters/${publicId}/cancel`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  addVitals(publicId: string, body: VitalsInput) {
    return api<VitalRow>(`/encounters/${publicId}/vitals`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  addDiagnosis(
    publicId: string,
    body: { code?: string; description: string; primary?: boolean },
  ) {
    return api<DiagnosisRow>(`/encounters/${publicId}/diagnoses`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  deleteDiagnosis(publicId: string, diagnosisPublicId: string) {
    return api<{ publicId: string; deleted: boolean }>(
      `/encounters/${publicId}/diagnoses/${diagnosisPublicId}`,
      { method: "DELETE" },
    );
  },
  createNote(
    publicId: string,
    body: {
      noteType?: string;
      content: {
        subjective?: string;
        objective?: string;
        assessment?: string;
        plan?: string;
      };
    },
  ) {
    return api<NoteRow>(`/encounters/${publicId}/notes`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateNote(
    publicId: string,
    notePublicId: string,
    body: {
      content: {
        subjective?: string;
        objective?: string;
        assessment?: string;
        plan?: string;
      };
    },
  ) {
    return api<NoteRow>(`/encounters/${publicId}/notes/${notePublicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  signNote(publicId: string, notePublicId: string) {
    return api<NoteRow>(`/encounters/${publicId}/notes/${notePublicId}/sign`, {
      method: "POST",
    });
  },
  amendNote(
    publicId: string,
    notePublicId: string,
    body: {
      reason: string;
      content: {
        subjective?: string;
        objective?: string;
        assessment?: string;
        plan?: string;
      };
    },
  ) {
    return api<NoteRow>(`/encounters/${publicId}/notes/${notePublicId}/amend`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  noteVersions(publicId: string, notePublicId: string) {
    return api<NoteVersionRow[]>(
      `/encounters/${publicId}/notes/${notePublicId}/versions`,
    );
  },
  createOrder(
    publicId: string,
    body: {
      orderType: string;
      priority?: string;
      notes?: string;
      services: { servicePublicId: string; quantity?: number }[];
    },
  ) {
    return api<{
      publicId: string;
      orderType: string;
      priority: string;
      status: string;
      services: string[];
    }>(`/encounters/${publicId}/orders`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateOrderStatus(publicId: string, orderPublicId: string, status: string) {
    return api<{ publicId: string; status: string }>(
      `/encounters/${publicId}/orders/${orderPublicId}`,
      { method: "PATCH", body: JSON.stringify({ status }) },
    );
  },
  createPrescription(
    publicId: string,
    body: { notes?: string; items: PrescriptionItemInput[] },
  ) {
    return api<Prescription>(`/encounters/${publicId}/prescriptions`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  issuePrescription(publicId: string, prescriptionPublicId: string) {
    return api<Prescription>(
      `/encounters/${publicId}/prescriptions/${prescriptionPublicId}/issue`,
      { method: "POST" },
    );
  },
  cancelPrescription(
    publicId: string,
    prescriptionPublicId: string,
    reason: string,
  ) {
    return api<Prescription>(
      `/encounters/${publicId}/prescriptions/${prescriptionPublicId}/cancel`,
      { method: "POST", body: JSON.stringify({ reason }) },
    );
  },
  getPrescription(prescriptionPublicId: string) {
    return api<Prescription>(`/prescriptions/${prescriptionPublicId}`);
  },
  addCharge(
    publicId: string,
    body: { servicePublicId: string; quantity?: number; sourceType?: string },
  ) {
    return api<{
      serviceCode: string;
      quantity: number;
      priceSnapshot: number | string;
      chargeIds: string[];
    }>(`/encounters/${publicId}/charges`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  print(publicId: string) {
    return api<PrintPayload>(`/encounters/${publicId}/print`);
  },
};

/** Parses the JSON content stored on a clinical note, tolerating bad data. */
export function noteContent(note: { contentJson?: string | null }): {
  subjective: string;
  objective: string;
  assessment: string;
  plan: string;
} {
  const empty = { subjective: "", objective: "", assessment: "", plan: "" };
  if (!note.contentJson) {
    return empty;
  }
  try {
    const parsed = JSON.parse(note.contentJson) as Record<string, unknown>;
    return {
      subjective: typeof parsed.subjective === "string" ? parsed.subjective : "",
      objective: typeof parsed.objective === "string" ? parsed.objective : "",
      assessment: typeof parsed.assessment === "string" ? parsed.assessment : "",
      plan: typeof parsed.plan === "string" ? parsed.plan : "",
    };
  } catch {
    return empty;
  }
}
