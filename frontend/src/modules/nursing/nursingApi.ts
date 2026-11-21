import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface NursingTask {
  publicId: string;
  taskType: string;
  title: string;
  description: string | null;
  priority: string;
  status: string;
  dueAt: string | null;
  assignedAt: string | null;
  completedAt: string | null;
  completionNote: string | null;
  version: number;
  assignedTo: { publicId: string; nameAr: string | null; nameEn: string | null } | null;
  completedBy: { nameAr: string | null; nameEn: string | null } | null;
  patient: {
    publicId: string;
    medicalNumber: string;
    fullName: string;
    gender: string | null;
  };
  admissionPublicId: string | null;
  encounterPublicId: string | null;
  ward: { publicId: string; nameAr: string; nameEn: string | null } | null;
  bedCode: string | null;
}

export interface AdmissionTaskRow {
  publicId: string;
  taskType: string;
  title: string;
  description: string | null;
  priority: string;
  status: string;
  dueAt: string | null;
  assignedAt: string | null;
  completedAt: string | null;
  completionNote: string | null;
  version: number;
  assignedTo: number | null;
  assignedToAr: string | null;
  assignedToEn: string | null;
}

export interface MedicationAdministrationRow {
  publicId: string;
  status: string;
  dose: string;
  route: string;
  notes: string | null;
  administeredAt: string | null;
  drugCode: string;
  drugAr: string;
  drugEn: string | null;
  batchNumber: string;
  administeredByAr: string | null;
  administeredByEn: string | null;
  barcodePatient: string | null;
  barcodeDrug: string | null;
  barcodeBatch: string | null;
}

export interface VitalsRow {
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
}

export interface EwsRow {
  publicId: string;
  score: number;
  riskLevel: string;
  parametersJson: string | null;
  calculatedAt: string | null;
}

export interface AdmissionOverview {
  admission: {
    publicId: string;
    status: string;
    admittedAt: string | null;
    reason: string | null;
    ward: { publicId: string | null; nameAr: string | null; nameEn: string | null } | null;
    bed: { publicId: string; code: string } | null;
    patient: {
      publicId: string;
      medicalNumber: string;
      fullName: string;
      gender: string | null;
      birthDate: string | null;
      phone: string | null;
    };
    encounterPublicId: string | null;
  };
  tasks: AdmissionTaskRow[];
  administrations: MedicationAdministrationRow[];
  vitals: VitalsRow[];
  ews: EwsRow | null;
  ewsHistory: EwsRow[];
}

export interface WardPatient {
  admissionPublicId: string;
  admittedAt: string | null;
  status: string;
  patientPublicId: string;
  medicalNumber: string;
  patientName: string;
  gender: string | null;
  birthDate: string | null;
  age: number | null;
  phone: string | null;
  encounterPublicId: string | null;
  bedPublicId: string | null;
  bedCode: string | null;
  bedType: string | null;
  assignedAt: string | null;
  ewsScore: number | null;
  ewsRisk: string | null;
  openTasks: number | null;
  latestVitals: {
    temperatureC: number | string | null;
    heartRate: number | null;
    respiratoryRate: number | null;
    systolic: number | null;
    diastolic: number | null;
    spo2: number | null;
    painScore: number | null;
    recordedAt: string | null;
  } | null;
}

export interface WardPatients {
  ward: {
    publicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    genderPolicy: string | null;
    departmentAr: string | null;
    departmentEn: string | null;
  };
  patients: WardPatient[];
  count: number;
}

export interface PatientPrescriptionRow {
  publicId: string;
  status: string;
  at: string | null;
  prescribedByAr: string | null;
  prescribedByEn: string | null;
  itemCount: number;
}

export interface PrescriptionItemRow {
  publicId: string;
  drugPublicId: string;
  drugCode: string;
  drugAr: string;
  drugEn: string | null;
  form: string | null;
  strength: string | null;
  dose: string;
  route: string | null;
  frequency: string | null;
  durationDays: number | null;
  quantity: number | null;
  instructions: string | null;
}

export interface PrescriptionDetail {
  publicId: string;
  status: string;
  issuedAt: string | null;
  notes: string | null;
  version: number;
  patient: {
    publicId: string;
    medicalNumber: string;
    fullName: string;
    gender: string | null;
    birthDate: string | null;
    age: number | null;
  };
  prescribedBy: { publicId: string; nameAr: string | null; nameEn: string | null };
  encounterPublicId: string | null;
  items: PrescriptionItemRow[];
}

export interface VitalsInput {
  temperatureC?: number;
  heartRate?: number;
  respiratoryRate?: number;
  systolic?: number;
  diastolic?: number;
  spo2?: number;
  weightKg?: number;
  heightCm?: number;
  painScore?: number;
}

export interface TaskSearchParams {
  status?: string;
  type?: string;
  ward?: string;
  assignedTo?: string;
  admission?: string;
  q?: string;
  page?: number;
  size?: number;
}

export const nursingApi = {
  tasks(filters: TaskSearchParams) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    if (filters.type) params.set("type", filters.type);
    if (filters.ward) params.set("ward", filters.ward);
    if (filters.assignedTo) params.set("assignedTo", filters.assignedTo);
    if (filters.admission) params.set("admission", filters.admission);
    if (filters.q) params.set("q", filters.q);
    return api<PageResult<NursingTask>>(`/nursing/tasks?${params.toString()}`);
  },
  createTask(body: {
    taskType: string;
    priority: string;
    title: string;
    description?: string;
    admissionPublicId?: string;
    patientPublicId?: string;
    encounterPublicId?: string;
    assignedToPublicId?: string;
    dueAt?: string;
  }) {
    return api<NursingTask>("/nursing/tasks", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  assignTask(publicId: string, body: { version: number; assignedToPublicId: string }) {
    return api<NursingTask>(`/nursing/tasks/${publicId}/assign`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  startTask(publicId: string, body: { version: number }) {
    return api<NursingTask>(`/nursing/tasks/${publicId}/start`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  completeTask(publicId: string, body: { version: number; completionNote?: string }) {
    return api<NursingTask>(`/nursing/tasks/${publicId}/complete`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  cancelTask(publicId: string, body: { version: number; reason: string }) {
    return api<NursingTask>(`/nursing/tasks/${publicId}/cancel`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  overview(admissionPublicId: string) {
    return api<AdmissionOverview>(`/nursing/admissions/${admissionPublicId}/overview`);
  },
  wardPatients(wardPublicId: string) {
    return api<WardPatients>(`/wards/${wardPublicId}/patients`);
  },
  recordVitals(admissionPublicId: string, body: VitalsInput) {
    return api<{ admissionPublicId: string; vitals: VitalsRow; ews: EwsRow | null }>(
      `/nursing/admissions/${admissionPublicId}/vitals`,
      { method: "POST", body: JSON.stringify(body) },
    );
  },
  administer(body: {
    prescriptionItemPublicId: string;
    status: string;
    admissionPublicId?: string;
    barcodePatient?: string;
    barcodeDrug?: string;
    barcodeBatch?: string;
    notes?: string;
  }) {
    return api<{
      publicId: string;
      status: string;
      dose: string;
      route: string;
      patientPublicId: string;
      patientName: string;
      drug: {
        publicId: string;
        code: string;
        nameAr: string;
        nameEn: string | null;
      };
      batch: {
        publicId: string;
        batchNumber: string;
        expiryDate: string;
        remaining: number;
      };
      verified: { patient: boolean; drug: boolean; batch: boolean };
      admissionPublicId: string | null;
    }>("/medication-administrations", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  patientPrescriptions(patientPublicId: string) {
    return api<PatientPrescriptionRow[]>(`/prescriptions/patient/${patientPublicId}`);
  },
  prescription(publicId: string) {
    return api<PrescriptionDetail>(`/prescriptions/${publicId}`);
  },
};

export const TASK_TYPES = [
  "MEDICATION",
  "VITALS",
  "NOTE",
  "PROCEDURE",
  "OBSERVATION",
  "OTHER",
];

export const TASK_STATUSES = [
  "PENDING",
  "ASSIGNED",
  "IN_PROGRESS",
  "COMPLETED",
  "CANCELLED",
];

export const ADMIN_STATUSES = ["GIVEN", "REFUSED", "HELD", "ERROR"];
