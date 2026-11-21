import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export type Money = number | string;

export interface InvoicePatientRef {
  publicId: string;
  medicalNumber: string;
  fullName: string;
}

export interface InvoiceItem {
  publicId: string;
  serviceExecutionId: string | null;
  serviceId: number;
  descriptionAr: string | null;
  descriptionEn: string | null;
  quantity: number;
  unitPrice: Money;
  lineTotal: Money;
  serviceCode: string;
  serviceCategory: string;
}

export interface InvoicePayment {
  publicId: string;
  receiptNumber: string;
  amount: Money;
  method: string;
  reference: string | null;
  status: string;
  receivedAt: string;
  receivedByAr: string | null;
  receivedByEn: string | null;
}

export interface InvoiceRefund {
  publicId: string;
  refundNumber: string;
  amount: Money;
  method: string;
  reason: string;
  status: string;
  performedAt: string;
  receiptNumber: string;
  paymentPublicId: string;
}

export interface InvoiceCreditNote {
  publicId: string;
  creditNumber: string;
  amount: Money;
  currency: string;
  reason: string;
  status: string;
  issuedAt: string;
}

export interface Invoice {
  publicId: string;
  invoiceNumber: string;
  status: string;
  patient: InvoicePatientRef;
  encounterPublicId: string | null;
  cashSession: { publicId: string; status: string } | null;
  subtotal: Money;
  discountTotal: Money;
  taxTotal: Money;
  grandTotal: Money;
  paidTotal: Money;
  refundedTotal: Money;
  creditedTotal: Money;
  balance: Money;
  currency: string;
  discountReason: string | null;
  notes: string | null;
  issuedAt: string;
  dueDate: string | null;
  closedAt: string | null;
  voidedAt: string | null;
  voidReason: string | null;
  version: number;
  closed: boolean;
  items?: InvoiceItem[];
  payments?: InvoicePayment[];
  refunds?: InvoiceRefund[];
  creditNotes?: InvoiceCreditNote[];
}

export interface PaymentResult {
  publicId: string;
  receiptNumber: string;
  amount: Money;
  currency: string;
  method: string;
  reference: string | null;
  status: string;
  notes: string | null;
  receivedAt: string;
  invoice: {
    publicId: string;
    invoiceNumber: string;
    grandTotal: Money;
    balance: Money;
  };
  patient: InvoicePatientRef;
  cashSessionPublicId: string | null;
  replayed: boolean;
}

export interface ReceiptDetail extends PaymentResult {
  invoiceItems?: InvoiceItem[];
}

export interface RefundResult {
  publicId: string;
  refundNumber: string;
  amount: Money;
  currency: string;
  method: string;
  reason: string;
  status: string;
  paymentPublicId: string;
  refundedTotal?: Money;
}

export interface CreditNoteResult {
  publicId: string;
  creditNumber: string;
  amount: Money;
  currency: string;
  reason: string;
  status: string;
  invoicePublicId: string;
  invoiceStatus: string;
  creditableRemaining: Money;
}

export interface CashSession {
  publicId: string;
  sessionNumber: string;
  cashierPublicId: string;
  cashierNameAr: string | null;
  cashierNameEn: string | null;
  openingFloat: Money;
  expectedTotal: Money | null;
  countedTotal: Money | null;
  variance: Money | null;
  currency: string;
  status: string;
  openedAt: string;
  closedAt: string | null;
  notes: string | null;
  version: number;
}

export interface DailyReport {
  date: string;
  currency: string;
  invoiceCount: number;
  collected: Money;
  refunded: Money;
  credited: Money;
  net: Money;
  outstanding: Money;
  byMethod: {
    method: string;
    paymentCount: number;
    collected: Money;
    refunded: Money;
    net: Money;
  }[];
}

export interface PriceItem {
  publicId: string;
  servicePublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  category: string;
  taxable: boolean;
  active: boolean;
  amount: Money;
  currency: string;
  validFrom: string;
  validTo: string | null;
}

export interface ServiceOption {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  category: string;
}

export const INVOICE_STATUSES = [
  "ISSUED",
  "PARTIALLY_PAID",
  "PAID",
  "CREDITED",
  "VOID",
];

export const PAYMENT_METHODS = ["CASH", "CARD", "TRANSFER", "INSURANCE", "CHEQUE"];

export const billingApi = {
  invoices(filters: {
    q?: string;
    status?: string;
    patient?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.q) params.set("q", filters.q);
    if (filters.status) params.set("status", filters.status);
    if (filters.patient) params.set("patient", filters.patient);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    return api<PageResult<Invoice>>(`/billing/invoices?${params.toString()}`);
  },
  invoice(publicId: string) {
    return api<Invoice>(`/billing/invoices/${publicId}`);
  },
  createInvoice(body: {
    patientPublicId: string;
    encounterPublicId?: string;
    notes?: string;
    dueDate?: string;
  }) {
    return api<Invoice>("/billing/invoices", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  discount(
    publicId: string,
    body: { version: number; percent?: number; amount?: number; reason: string },
  ) {
    return api<Invoice>(`/billing/invoices/${publicId}/discount`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  voidInvoice(publicId: string, body: { reason: string }) {
    return api<Invoice>(`/billing/invoices/${publicId}/void`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  creditNote(publicId: string, body: { amount: number; reason: string }) {
    return api<CreditNoteResult>(`/billing/invoices/${publicId}/credit-notes`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  addPayment(
    publicId: string,
    body: {
      amount: number;
      method: string;
      reference?: string;
      notes?: string;
      idempotencyKey: string;
    },
  ) {
    return api<PaymentResult>(`/billing/invoices/${publicId}/payments`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  receipt(paymentPublicId: string) {
    return api<ReceiptDetail>(`/billing/payments/${paymentPublicId}/receipt`);
  },
  refund(
    paymentPublicId: string,
    body: {
      amount: number;
      reason: string;
      method?: string;
      approvedByPublicId?: string;
    },
  ) {
    return api<RefundResult>(`/billing/payments/${paymentPublicId}/refunds`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  cashSessions(filters: { status?: string; page?: number; size?: number }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    return api<PageResult<CashSession>>(`/billing/cash-sessions?${params.toString()}`);
  },
  currentCashSession() {
    return api<{ session: CashSession | null }>("/billing/cash-sessions/current");
  },
  openCashSession(body: { openingFloat: number; notes?: string }) {
    return api<CashSession>("/billing/cash-sessions", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  closeCashSession(publicId: string, body: { countedTotal: number; notes?: string }) {
    return api<CashSession>(`/billing/cash-sessions/${publicId}/close`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  dailyReport(date?: string) {
    const suffix = date ? `?date=${encodeURIComponent(date)}` : "";
    return api<DailyReport>(`/billing/reports/daily${suffix}`);
  },
  priceList(query?: string) {
    const suffix = query ? `?q=${encodeURIComponent(query)}` : "";
    return api<{ items: PriceItem[]; total: number }>(
      `/billing/service-prices${suffix}`,
    );
  },
  createPrice(body: {
    servicePublicId: string;
    amount: number;
    currency?: string;
    validFrom?: string;
  }) {
    return api<PriceItem>("/billing/service-prices", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  services() {
    return api<ServiceOption[]>("/scheduling/services");
  },
};
