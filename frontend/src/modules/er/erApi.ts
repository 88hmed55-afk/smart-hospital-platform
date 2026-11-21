import { api } from "../../shared/api/client";

export interface TriageLevel {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  rankNo: number;
  color: string;
  maxWaitMinutes: number | null;
}

export interface ErPatient {
  publicId: string;
  medicalNumber: string;
  fullName: string;
  gender: string;
  birthDate: string | null;
  age: number | null;
  phone: string | null;
}

export interface ErVitals {
  temperatureC: number | string | null;
  heartRate: number | null;
  respiratoryRate: number | null;
  systolic: number | null;
  diastolic: number | null;
  spo2: number | null;
  painScore: number | null;
  recordedAt: string | null;
}

export interface ErVisit {
  publicId: string;
  status: string;
  arrivalMode: string | null;
  chiefComplaint: string | null;
  arrivalAt: string | null;
  triageAt: string | null;
  firstAssessmentAt: string | null;
  disposition: string | null;
  dispositionAt: string | null;
  dispositionNotes: string | null;
  lwbsReason: string | null;
  enteredInErrorReason: string | null;
  waitMinutes: number;
  assessmentMinutes: number | null;
  slaBreach: boolean;
  maxWaitMinutes: number | null;
  version: number;
  patient: ErPatient;
  triageLevel: {
    code: string;
    nameAr: string;
    nameEn: string;
    color: string;
    rank: number;
  } | null;
  encounterPublicId: string | null;
  latestVitals: ErVitals | null;
}

export interface ErBoard {
  generatedAt: string;
  waiting: ErVisit[];
  inTreatment: ErVisit[];
  recent: ErVisit[];
  counts: {
    waiting: number;
    inTreatment: number;
    recent: number;
    slaBreaches: number;
  };
}

export interface ErVisitDetail extends ErVisit {
  history?: {
    publicId: string;
    arrivalAt: string | null;
    disposition: string | null;
    chiefComplaint: string | null;
  }[];
}

export interface VitalsInput {
  temperatureC?: number | null;
  heartRate?: number | null;
  respiratoryRate?: number | null;
  systolic?: number | null;
  diastolic?: number | null;
  spo2?: number | null;
  painScore?: number | null;
}

export const erApi = {
  triageLevels() {
    return api<TriageLevel[]>("/er/triage-levels");
  },
  board() {
    return api<ErBoard>("/er/board");
  },
  visit(publicId: string) {
    return api<ErVisitDetail>(`/er/visits/${publicId}`);
  },
  register(body: {
    patientPublicId: string;
    arrivalMode?: string;
    chiefComplaint?: string;
  }) {
    return api<ErVisit>("/er/visits", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  triage(
    publicId: string,
    body: {
      triageLevelCode: string;
      chiefComplaint?: string;
      version: number;
      vitals?: VitalsInput;
    },
  ) {
    return api<ErVisit>(`/er/visits/${publicId}/triage`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  assess(publicId: string, version: number) {
    return api<ErVisit>(`/er/visits/${publicId}/assess`, {
      method: "POST",
      body: JSON.stringify({ version }),
    });
  },
  disposition(
    publicId: string,
    body: {
      disposition: string;
      notes?: string;
      reason?: string;
      wardPublicId?: string;
      version: number;
    },
  ) {
    return api<ErVisit>(`/er/visits/${publicId}/disposition`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
};

export const ER_ARRIVAL_MODES = [
  "WALK_IN",
  "AMBULANCE",
  "POLICE",
  "TRANSFER",
  "OTHER",
];

export const ER_DISPOSITIONS = [
  "DISCHARGE",
  "ADMIT",
  "TRANSFER_CLINIC",
  "TRANSFER_HOSPITAL",
  "LWBS",
  "ENTERED_IN_ERROR",
];
