import { api } from "../../shared/api/client";

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface PatientSummary {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string;
  birthDate: string | null;
  age: number | null;
  phone: string | null;
  nationalId: string | null;
  status: string;
  allergyCount: number | null;
  createdAt: string | null;
}

export interface PatientIdentifier {
  publicId: string;
  type: string;
  value: string;
  isPrimary: boolean;
}

export interface PatientContact {
  publicId: string;
  contactType: string;
  fullName: string;
  relation: string | null;
  phone: string | null;
  isPrimary: boolean;
}

export interface PatientAllergy {
  publicId: string;
  substance: string;
  allergyType: string;
  severity: string;
  reaction: string | null;
  active: boolean;
  recordedAt: string | null;
}

export interface PatientCondition {
  publicId: string;
  description: string;
  onsetDate: string | null;
  status: string;
}

export interface PatientFlag {
  publicId: string;
  flagType: string;
  note: string | null;
}

export interface DuplicateMatch {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string;
  birthDate: string | null;
  phone: string | null;
  matchReason: string;
  score: number;
}

export interface DuplicateCheck {
  status: "NO_MATCH" | "POSSIBLE_MATCH" | "EXACT_MATCH";
  score: number;
  matches: DuplicateMatch[];
}

export interface PatientCard extends PatientSummary {
  email: string | null;
  address: string | null;
  bloodGroup: string | null;
  maritalStatus: string | null;
  notes: string | null;
  birthDateEstimated: boolean;
  version: number;
  mergedIntoPublicId: string | null;
  mergedIntoMedicalNumber: string | null;
  identifiers: PatientIdentifier[];
  contacts: PatientContact[];
  allergies: PatientAllergy[];
  conditions: PatientCondition[];
  flags: PatientFlag[];
  encounterCount: number;
  appointmentCount: number;
  duplicateCheck?: DuplicateCheck;
}

export interface TimelineEvent {
  type: string;
  publicId: string;
  at: string | null;
  status: string | null;
  titleAr: string | null;
  titleEn: string | null;
  subtitleAr: string | null;
  subtitleEn: string | null;
}

export interface PatientPrescription {
  publicId: string;
  status: string;
  at: string | null;
  prescribedByAr: string | null;
  prescribedByEn: string | null;
  itemCount: number;
}

export interface PatientInput {
  fullName: string;
  gender: string;
  birthDate?: string | null;
  birthDateEstimated?: boolean;
  nationalId?: string | null;
  phone?: string | null;
  email?: string | null;
  address?: string | null;
  bloodGroup?: string | null;
  maritalStatus?: string | null;
  notes?: string | null;
  override?: boolean;
  overrideReason?: string | null;
  emergencyContact?: {
    name: string;
    phone: string;
    relation?: string | null;
  } | null;
}

export interface AllergyInput {
  substance: string;
  allergyType: string;
  severity: string;
  reaction?: string | null;
}

export const patientsApi = {
  search(query: string, status: string | undefined, page: number, size = 20) {
    const params = new URLSearchParams({
      q: query,
      page: String(page),
      size: String(size),
    });
    if (status) {
      params.set("status", status);
    }
    return api<PageResult<PatientSummary>>(`/patients?${params.toString()}`);
  },
  checkDuplicates(body: {
    fullName?: string;
    nationalId?: string;
    phone?: string;
    birthDate?: string | null;
  }) {
    return api<DuplicateCheck>("/patients/check-duplicates", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  create(body: PatientInput) {
    return api<PatientCard>("/patients", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  get(publicId: string) {
    return api<PatientCard>(`/patients/${publicId}`);
  },
  update(publicId: string, body: Partial<PatientInput> & { version: number }) {
    return api<PatientCard>(`/patients/${publicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  merge(publicId: string, targetPublicId: string, reason: string) {
    return api<PatientCard>(`/patients/${publicId}/merge`, {
      method: "POST",
      body: JSON.stringify({ targetPublicId, reason }),
    });
  },
  timeline(publicId: string) {
    return api<TimelineEvent[]>(`/patients/${publicId}/timeline`);
  },
  allergies(publicId: string) {
    return api<PatientAllergy[]>(`/patients/${publicId}/allergies`);
  },
  addAllergy(publicId: string, body: AllergyInput) {
    return api<PatientAllergy>(`/patients/${publicId}/allergies`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  removeAllergy(publicId: string, allergyPublicId: string) {
    return api<{ publicId: string; active: boolean }>(
      `/patients/${publicId}/allergies/${allergyPublicId}`,
      { method: "DELETE" },
    );
  },
  prescriptions(publicId: string) {
    return api<PatientPrescription[]>(`/prescriptions/patient/${publicId}`);
  },
};

export function patientName(
  patient: { fullName?: string | null } | null | undefined,
): string {
  return patient?.fullName?.trim() || "—";
}
