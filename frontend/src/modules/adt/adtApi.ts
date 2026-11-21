import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface WardRow {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  genderPolicy: string | null;
  departmentAr: string | null;
  departmentEn: string | null;
  bedCount: number | null;
  availableBeds: number | null;
}

export interface BedRow {
  publicId: string;
  code: string;
  bedType: string;
  status: string;
  version: number;
  patientPublicId: string | null;
  patientName: string | null;
  medicalNumber: string | null;
  admissionPublicId: string | null;
  assignedAt: string | null;
  notes: string | null;
}

export interface BedWard {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  genderPolicy: string | null;
  beds: BedRow[];
}

export interface BedBoard {
  wards: BedWard[];
  counts: Record<string, number>;
  total: number;
}

export interface AdmissionPatient {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string;
  birthDate: string | null;
  age: number | null;
  phone: string | null;
}

export interface AdmissionWardRef {
  publicId: string;
  nameAr: string;
  nameEn: string | null;
}

export interface AdmissionBedRef {
  publicId: string;
  code: string;
  status: string;
  assignedAt: string | null;
  wardPublicId: string | null;
  wardAr: string | null;
  wardEn: string | null;
}

export interface Admission {
  publicId: string;
  status: string;
  reason: string;
  requestedAt: string | null;
  approvedAt: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  admittedAt: string | null;
  dischargedAt: string | null;
  dischargeType: string | null;
  billingCleared: boolean;
  version: number;
  los: { hours: number; days: number; active: boolean };
  requestedBy: { nameAr: string | null; nameEn: string | null };
  approvedBy: { nameAr: string | null; nameEn: string | null };
  patient: AdmissionPatient;
  encounterPublicId: string | null;
  requestedWard: AdmissionWardRef | null;
  bed: AdmissionBedRef | null;
}

export interface BedHistoryRow {
  publicId: string;
  assignedAt: string | null;
  endedAt: string | null;
  endReason: string | null;
  bedPublicId: string;
  bedCode: string;
  bedType: string;
  wardAr: string;
  wardEn: string | null;
}

export interface AdmissionDetail {
  admission: Admission;
  bedHistory: BedHistoryRow[];
  hasSummary: boolean;
}

export interface DischargeSummaryRow {
  publicId: string;
  admissionId: number;
  diagnosesText: string | null;
  medicationsText: string | null;
  instructionsText: string | null;
  followUpText: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface DischargeSummary {
  admission: Admission;
  stored: DischargeSummaryRow | null;
  diagnoses: {
    publicId: string;
    code: string | null;
    description: string;
    diagnosisType: string;
    createdAt: string | null;
  }[];
  medications: {
    publicId: string;
    drugCode: string;
    drugAr: string;
    drugEn: string | null;
    dose: string;
    route: string | null;
    frequency: string | null;
    durationDays: number | null;
    quantity: number | null;
    dispensedQuantity: number | null;
    instructions: string | null;
  }[];
  instructions: string | null;
  los: { hours: number; days: number; active: boolean };
}

export interface AdmissionSearchParams {
  status?: string;
  ward?: string;
  q?: string;
  page?: number;
  size?: number;
}

export const adtApi = {
  wards() {
    return api<WardRow[]>("/wards");
  },
  bedBoard() {
    return api<BedBoard>("/beds/board");
  },
  bedStatus(publicId: string, body: { status: string; version: number; reason?: string }) {
    return api<{ publicId: string; status: string; previousStatus: string }>(
      `/beds/${publicId}/status`,
      { method: "POST", body: JSON.stringify(body) },
    );
  },
  search(filters: AdmissionSearchParams) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    if (filters.ward) params.set("ward", filters.ward);
    if (filters.q) params.set("q", filters.q);
    return api<PageResult<Admission>>(`/admissions?${params.toString()}`);
  },
  get(publicId: string) {
    return api<AdmissionDetail>(`/admissions/${publicId}`);
  },
  request(body: {
    patientPublicId: string;
    encounterPublicId: string;
    reason: string;
    wardPublicId?: string;
    departmentPublicId?: string;
    admittingProviderPublicId?: string;
  }) {
    return api<Admission>("/admissions", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  approve(publicId: string, body: { version: number; reason?: string }) {
    return api<AdmissionDetail>(`/admissions/${publicId}/approve`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  reject(publicId: string, body: { version: number; reason: string }) {
    return api<AdmissionDetail>(`/admissions/${publicId}/reject`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  assignBed(
    publicId: string,
    body: { bedPublicId: string; version: number; exceptionReason?: string },
  ) {
    return api<AdmissionDetail>(`/admissions/${publicId}/assign-bed`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  transfer(
    publicId: string,
    body: {
      toBedPublicId: string;
      reason: string;
      version: number;
      exceptionReason?: string;
    },
  ) {
    return api<AdmissionDetail>(`/admissions/${publicId}/transfer`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  discharge(
    publicId: string,
    body: { dischargeType: string; notes?: string; version: number },
  ) {
    return api<AdmissionDetail>(`/admissions/${publicId}/discharge`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  summary(publicId: string) {
    return api<DischargeSummary>(`/admissions/${publicId}/summary`);
  },
  saveSummary(
    publicId: string,
    body: {
      diagnosesText: string;
      instructionsText: string;
      medicationsText?: string;
      followUpText?: string;
    },
  ) {
    return api<DischargeSummary>(`/admissions/${publicId}/summary`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
};

export const ADMISSION_STATUSES = [
  "REQUESTED",
  "APPROVED",
  "ADMITTED",
  "TRANSFERRED",
  "DISCHARGE_PENDING",
  "DISCHARGED",
  "REJECTED",
];

export const DISCHARGE_TYPES = [
  "HOME",
  "TRANSFER",
  "AGAINST_ADVICE",
  "DECEASED",
  "OTHER",
];

export const ACTIVE_ADMISSION_STATUSES = ["ADMITTED", "TRANSFERRED"];
