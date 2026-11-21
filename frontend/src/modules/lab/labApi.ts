import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface LabTest {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  specimenType: string;
  resultType: string;
  unit: string | null;
  refLow: number | string | null;
  refHigh: number | string | null;
  refText: string | null;
  criticalLow: number | string | null;
  criticalHigh: number | string | null;
  targetTatMinutes: number | null;
}

export interface LabOrderPatient {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string | null;
  birthDate: string | null;
  phone?: string | null;
}

export interface LabOrderPerson {
  publicId: string | null;
  nameAr: string | null;
  nameEn: string | null;
}

export interface LabOrder {
  publicId: string;
  status: string;
  priority: string;
  orderedAt: string | null;
  clinicalNotes: string | null;
  cancelReason: string | null;
  version: number;
  patient: LabOrderPatient;
  encounterPublicId: string | null;
  orderedBy: LabOrderPerson;
  itemCount: number | null;
  resultCount: number | null;
}

export interface LabOrderItem {
  publicId: string;
  status: string;
  testCode: string;
  testNameAr: string;
  testNameEn: string | null;
  specimenType: string;
  unit: string | null;
  resultType: string;
  refLow: number | string | null;
  refHigh: number | string | null;
  refText: string | null;
  criticalLow: number | string | null;
  criticalHigh: number | string | null;
  targetTatMinutes: number | null;
  resultPublicId: string | null;
  resultStatus: string | null;
  valueNumeric: number | string | null;
  valueText: string | null;
  flag: string | null;
  criticalFlag: boolean | null;
  comment: string | null;
}

export interface LabSampleEvent {
  publicId: string;
  specimenId: number;
  fromStatus: string | null;
  toStatus: string;
  actorId: number;
  note: string | null;
  occurredAt: string | null;
  actorAr: string | null;
  actorEn: string | null;
}

export interface LabSpecimen {
  publicId: string;
  labOrderId: number;
  barcode: string;
  specimenType: string;
  status: string;
  collectedBy: number | null;
  collectedAt: string | null;
  receivedBy: number | null;
  receivedAt: string | null;
  rejectedBy: number | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  notes: string | null;
  version: number;
  events: LabSampleEvent[];
}

export interface LabResult {
  publicId: string;
  status: string;
  testCode: string;
  testNameAr: string;
  testNameEn: string | null;
  unit: string | null;
  resultType: string;
  valueNumeric: number | string | null;
  valueText: string | null;
  refLow: number | string | null;
  refHigh: number | string | null;
  refText: string | null;
  flag: string | null;
  criticalFlag: boolean | null;
  comment: string | null;
  enteredBy: LabOrderPerson;
  enteredAt: string | null;
  verifiedBy: { nameAr: string | null; nameEn: string | null } | null;
  verifiedAt: string | null;
  releasedBy: { nameAr: string | null; nameEn: string | null } | null;
  releasedAt: string | null;
  version: number;
  orderPublicId: string;
}

export interface LabOrderDetail extends LabOrder {
  items: LabOrderItem[];
  specimens: LabSpecimen[];
  results: LabResult[];
}

export interface LabWorklist {
  items: LabOrder[];
  counts: Record<string, number>;
  page: number;
  size: number;
  generatedAt: string;
}

export interface LabAlert {
  publicId: string;
  resultId: number;
  patientId: number;
  status: string;
  notes: string | null;
  createdAt: string | null;
  acknowledgedAt: string | null;
  acknowledgedBy: number | null;
  version: number;
  resultPublicId: string;
  flag: string;
  valueNumeric: number | string | null;
  valueText: string | null;
  testCode: string;
  testNameAr: string;
  testNameEn: string | null;
  patientPublicId: string;
  patientName: string;
  medicalNumber: string;
  orderPublicId: string;
}

export interface LabAlerts {
  items: LabAlert[];
  count: number;
}

export interface LabReport {
  header: {
    nameAr: string;
    nameEn: string;
    reportTitleAr: string;
    reportTitleEn: string;
  };
  order: LabOrder;
  patient: LabOrderPatient;
  results: LabResult[];
  focusResultPublicId: string | null;
  printedAt: string;
  printedBy: LabOrderPerson;
}

export interface LabOrderFilters {
  status?: string;
  priority?: string;
  patient?: string;
  q?: string;
  date?: string;
  page?: number;
  size?: number;
}

export const labApi = {
  tests(query?: string) {
    const params = new URLSearchParams({ activeOnly: "true" });
    if (query) params.set("q", query);
    return api<LabTest[]>(`/lab/tests?${params.toString()}`);
  },
  worklist(priority?: string, page = 0, size = 25) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    if (priority) params.set("priority", priority);
    return api<LabWorklist>(`/lab/worklist?${params.toString()}`);
  },
  orders(filters: LabOrderFilters) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    if (filters.priority) params.set("priority", filters.priority);
    if (filters.patient) params.set("patient", filters.patient);
    if (filters.q) params.set("q", filters.q);
    if (filters.date) params.set("date", filters.date);
    return api<PageResult<LabOrder>>(`/lab/orders?${params.toString()}`);
  },
  order(publicId: string) {
    return api<LabOrderDetail>(`/lab/orders/${publicId}`);
  },
  createOrder(body: {
    encounterPublicId: string;
    priority?: string;
    clinicalNotes?: string;
    labTestPublicIds: string[];
  }) {
    return api<LabOrderDetail>("/lab/orders", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  collect(
    publicId: string,
    body: { version: number; specimenType?: string; barcode?: string; notes?: string },
  ) {
    return api<LabOrderDetail>(`/lab/orders/${publicId}/collect`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  process(publicId: string, body: { version: number }) {
    return api<LabOrderDetail>(`/lab/orders/${publicId}/process`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  enterResult(
    publicId: string,
    body: {
      labTestPublicId?: string;
      itemPublicId?: string;
      valueNumeric?: number;
      valueText?: string;
      comment?: string;
    },
  ) {
    return api<LabOrderDetail>(`/lab/orders/${publicId}/result`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  receiveSpecimen(publicId: string, body: { version: number; notes?: string }) {
    return api<LabOrderDetail>(`/lab/specimens/${publicId}/receive`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  rejectSpecimen(publicId: string, body: { version: number; reason: string }) {
    return api<LabOrderDetail>(`/lab/specimens/${publicId}/reject`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  verify(resultPublicId: string, body: { version: number }) {
    return api<LabResult>(`/lab/results/${resultPublicId}/verify`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  release(resultPublicId: string, body: { version: number }) {
    return api<LabResult>(`/lab/results/${resultPublicId}/release`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  amend(
    resultPublicId: string,
    body: {
      version: number;
      reason: string;
      valueNumeric?: number;
      valueText?: string;
      comment?: string;
    },
  ) {
    return api<LabResult>(`/lab/results/${resultPublicId}/amend`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  alerts(status?: string, limit = 50) {
    const params = new URLSearchParams({ limit: String(limit) });
    if (status) params.set("status", status);
    return api<LabAlerts>(`/lab/alerts?${params.toString()}`);
  },
  acknowledgeAlert(publicId: string, body: { version: number; notes?: string }) {
    return api<{ publicId: string; status: string }>(
      `/lab/alerts/${publicId}/acknowledge`,
      { method: "POST", body: JSON.stringify(body) },
    );
  },
  orderReport(publicId: string) {
    return api<LabReport>(`/lab/orders/${publicId}/report`);
  },
};

export const LAB_STATUSES = [
  "ORDERED",
  "COLLECTED",
  "RECEIVED",
  "IN_PROCESS",
  "RESULTED",
  "VERIFIED",
  "RELEASED",
  "CORRECTED",
  "CANCELLED",
  "REJECTED",
];

export const LAB_PRIORITIES = ["ROUTINE", "URGENT", "STAT"];

export const SPECIMEN_TYPES = ["BLOOD", "URINE", "STOOL", "SPUTUM", "SWAB", "CSF", "OTHER"];
