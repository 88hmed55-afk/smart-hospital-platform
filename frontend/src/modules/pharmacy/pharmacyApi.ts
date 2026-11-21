import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface Drug {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  form: string;
  strength: string | null;
  category: string | null;
  reorderLevel: number;
  unitPrice: number | string | null;
  active: boolean;
  stockAvailable: number;
  batchCount: number;
  lowStock: boolean;
  version: number;
}

export interface DrugBatchDrug {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  form: string | null;
  strength: string | null;
}

export interface DrugBatch {
  publicId: string;
  batchNumber: string;
  lotNumber: string | null;
  expiryDate: string | null;
  quantityReceived: number;
  quantityAvailable: number;
  unitCost: number | string | null;
  supplier: string | null;
  status: string;
  receivedAt: string | null;
  daysToExpiry: number | null;
  version: number;
  drug: DrugBatchDrug;
  receivedBy: { nameAr: string | null; nameEn: string | null };
}

export interface PrescriptionPatient {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string | null;
  birthDate?: string | null;
  phone?: string | null;
}

export interface PrescriptionPerson {
  publicId?: string | null;
  nameAr: string | null;
  nameEn: string | null;
}

export interface QueueItem {
  publicId: string;
  status: string;
  issuedAt: string | null;
  version: number;
  patient: PrescriptionPatient;
  encounterPublicId: string | null;
  prescribedBy: PrescriptionPerson;
  itemCount: number | null;
  totalQuantity: number | null;
  dispensedQuantity: number | null;
}

export interface PrescriptionItem {
  publicId: string;
  dose: string | null;
  route: string | null;
  frequency: string | null;
  durationDays: number | null;
  quantity: number;
  dispensedQuantity: number;
  remaining: number;
  instructions: string | null;
  stockAvailable: number | null;
  drug: DrugBatchDrug;
}

export interface PrescriptionAllergy {
  publicId: string;
  substance: string;
  allergyType: string;
  severity: string;
  reaction: string | null;
}

export interface PrescriptionInteraction {
  severity: string;
  descriptionAr: string | null;
  descriptionEn: string | null;
  drug: { code: string; nameAr: string; nameEn: string | null };
  interactingDrug: { code: string; nameAr: string; nameEn: string | null };
}

export interface PrescriptionDispensing {
  publicId: string;
  prescriptionId: number;
  patientId: number;
  dispensedBy: number;
  dispensedAt: string | null;
  status: string;
  notes: string | null;
  version: number;
  dispensedByAr: string | null;
  dispensedByEn: string | null;
}

export interface PrescriptionDetail {
  publicId: string;
  status: string;
  issuedAt: string | null;
  notes: string | null;
  rejectedReason: string | null;
  reviewedAt: string | null;
  version: number;
  patient: PrescriptionPatient;
  encounterPublicId: string | null;
  prescribedBy: PrescriptionPerson;
  reviewedBy: PrescriptionPerson;
  items: PrescriptionItem[];
  allergies: PrescriptionAllergy[];
  interactions: PrescriptionInteraction[];
  dispensings: PrescriptionDispensing[];
}

export interface DispensingItem {
  publicId: string;
  quantity: number;
  returnedQuantity: number;
  dose: string | null;
  route: string | null;
  frequency: string | null;
  batchNumber: string;
  expiryDate: string | null;
  drug: { publicId: string; code: string; nameAr: string; nameEn: string | null };
}

export interface DispensingReturn {
  publicId: string;
  dispensingId: number;
  dispensingItemId: number;
  quantity: number;
  reason: string | null;
  returnedAt: string | null;
  drugCode: string;
  drugAr: string;
  drugEn: string | null;
  returnedByAr: string | null;
  returnedByEn: string | null;
}

export interface DispensingDetail {
  publicId: string;
  status: string;
  dispensedAt: string | null;
  notes: string | null;
  version: number;
  prescriptionPublicId: string;
  patient: { publicId: string; medicalNumber: string; fullName: string };
  dispensedBy: PrescriptionPerson;
  items: DispensingItem[];
  returns: DispensingReturn[];
  returnedQuantity?: number;
  prescriptionStatus?: string;
  partial?: boolean;
}

export interface StockMovement {
  publicId: string;
  movementType: string;
  quantityDelta: number;
  referenceType: string | null;
  referencePublicId: string | null;
  reason: string | null;
  performedAt: string | null;
  drug: { publicId: string; code: string; nameAr: string; nameEn: string | null };
  batch: {
    publicId: string | null;
    batchNumber: string | null;
    expiryDate: string | null;
  };
  performedBy: PrescriptionPerson;
}

export interface StockAlerts {
  lowStock: Drug[];
  expiring: DrugBatch[];
  expiryWarningDays: number;
  generatedAt: string;
}

export interface DispenseLine {
  prescriptionItemPublicId: string;
  quantity: number;
}

export interface ReturnLine {
  dispensingItemPublicId: string;
  quantity: number;
  reason?: string;
}

export const pharmacyApi = {
  drugs(query?: string, page = 0, size = 20, activeOnly = true) {
    const params = new URLSearchParams({
      activeOnly: String(activeOnly),
      page: String(page),
      size: String(size),
    });
    if (query) params.set("q", query);
    return api<PageResult<Drug>>(`/pharmacy/drugs?${params.toString()}`);
  },
  createDrug(body: {
    code: string;
    nameAr: string;
    nameEn?: string;
    form: string;
    strength?: string;
    category?: string;
    reorderLevel?: number;
    unitPrice?: number;
  }) {
    return api<Drug>("/pharmacy/drugs", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  queue(page = 0, size = 20) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    return api<PageResult<QueueItem>>(`/pharmacy/queue?${params.toString()}`);
  },
  prescription(publicId: string) {
    return api<PrescriptionDetail>(`/prescriptions/${publicId}/pharmacy`);
  },
  review(
    publicId: string,
    body: { version: number; notes?: string; acknowledgeInteractions?: boolean },
  ) {
    return api<PrescriptionDetail>(`/prescriptions/${publicId}/review`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  reject(publicId: string, body: { version: number; reason: string }) {
    return api<PrescriptionDetail>(`/prescriptions/${publicId}/reject`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  dispense(
    publicId: string,
    body: {
      version: number;
      items: DispenseLine[];
      notes?: string;
      acknowledgeInteractions?: boolean;
    },
  ) {
    return api<DispensingDetail>(`/prescriptions/${publicId}/dispense`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  dispensing(publicId: string) {
    return api<DispensingDetail>(`/dispensings/${publicId}`);
  },
  returnItems(publicId: string, body: { reason: string; items: ReturnLine[] }) {
    return api<DispensingDetail>(`/dispensings/${publicId}/return`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  batches(filters: {
    drug?: string;
    status?: string;
    q?: string;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.drug) params.set("drug", filters.drug);
    if (filters.status) params.set("status", filters.status);
    if (filters.q) params.set("q", filters.q);
    return api<PageResult<DrugBatch>>(`/stock/batches?${params.toString()}`);
  },
  receiveBatch(body: {
    drugPublicId: string;
    batchNumber: string;
    expiryDate: string;
    quantity: number;
    unitCost?: number;
    supplier?: string;
    lotNumber?: string;
  }) {
    return api<DrugBatch>("/stock/batches", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  movements(filters: {
    drug?: string;
    batch?: string;
    type?: string;
    date?: string;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.drug) params.set("drug", filters.drug);
    if (filters.batch) params.set("batch", filters.batch);
    if (filters.type) params.set("type", filters.type);
    if (filters.date) params.set("date", filters.date);
    return api<PageResult<StockMovement>>(`/stock/movements?${params.toString()}`);
  },
  alerts(limit = 50) {
    return api<StockAlerts>(`/stock/alerts?limit=${limit}`);
  },
};

export const BATCH_STATUSES = [
  "ACTIVE",
  "QUARANTINED",
  "EXPIRED",
  "DEPLETED",
  "WRITTEN_OFF",
];

export const MOVEMENT_TYPES = [
  "RECEIPT",
  "DISPENSE",
  "RETURN",
  "ADJUSTMENT",
  "EXPIRED",
  "WASTAGE",
  "TRANSFER_IN",
  "TRANSFER_OUT",
];

export const DRUG_FORMS = [
  "TABLET",
  "CAPSULE",
  "SYRUP",
  "SUSPENSION",
  "INJECTION",
  "IV",
  "OINTMENT",
  "CREAM",
  "DROPS",
  "INHALER",
  "SUPPOSITORY",
];
