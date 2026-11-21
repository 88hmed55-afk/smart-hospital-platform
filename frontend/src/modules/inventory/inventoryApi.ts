import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export type Money = number | string;

export interface Warehouse {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  warehouseType: string;
  location: string | null;
  active: boolean;
  batchCount: number;
  totalQuantity: number;
  version: number;
}

export interface ReorderPoint {
  publicId: string;
  warehousePublicId: string;
  warehouseCode: string;
  reorderLevel: number;
  reorderQuantity: number;
  active: boolean;
}

export interface InventoryItem {
  publicId: string;
  code: string;
  sku: string;
  nameAr: string;
  nameEn: string | null;
  category: string;
  baseUnit: string;
  trackExpiry: boolean;
  unitCost: Money | null;
  barcode: string | null;
  active: boolean;
  stockOnHand: number;
  reorderLevel: number;
  version: number;
  reorderPoints?: ReorderPoint[];
  batches?: InventoryBatch[];
}

export interface Supplier {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  contactPerson: string | null;
  active: boolean;
  version: number;
}

export interface InventoryBatch {
  publicId: string;
  batchNumber: string;
  lotNumber: string | null;
  expiryDate: string | null;
  quantityReceived: number;
  quantityAvailable: number;
  unitCost: Money | null;
  status: string;
  receivedAt: string;
  version: number;
  itemPublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  baseUnit: string;
  trackExpiry: boolean;
  warehousePublicId: string;
  warehouseCode: string;
  warehouseNameAr: string;
  warehouseNameEn: string | null;
  supplierNameAr: string | null;
  supplierNameEn: string | null;
}

export interface InventoryMovement {
  publicId: string;
  movementType: string;
  quantityDelta: number;
  unitCost: Money | null;
  referenceType: string | null;
  referencePublicId: string | null;
  reason: string | null;
  performedAt: string;
  itemPublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  baseUnit: string;
  batchPublicId: string | null;
  batchNumber: string | null;
  expiryDate: string | null;
  warehousePublicId: string;
  warehouseCode: string;
  performedByAr: string | null;
  performedByEn: string | null;
}

export interface StockTransfer {
  publicId: string;
  transferNumber: string;
  documentRef: string | null;
  reason: string | null;
  status: string;
  transferredAt: string;
  fromWarehousePublicId: string;
  fromWarehouseCode: string;
  fromWarehouseNameAr: string;
  fromWarehouseNameEn: string | null;
  toWarehousePublicId: string;
  toWarehouseCode: string;
  toWarehouseNameAr: string;
  toWarehouseNameEn: string | null;
  transferredByAr: string | null;
  transferredByEn: string | null;
  version: number;
}

export interface StockCountLine {
  publicId: string;
  systemQuantity: number;
  countedQuantity: number | null;
  variance: number | null;
  batchPublicId: string;
  batchNumber: string;
  expiryDate: string | null;
  itemPublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  baseUnit: string;
}

export interface StockCount {
  publicId: string;
  countNumber: string;
  status: string;
  reason: string | null;
  countedAt: string;
  postedAt: string | null;
  warehousePublicId: string;
  warehouseCode: string;
  warehouseNameAr: string;
  warehouseNameEn: string | null;
  countedByAr: string | null;
  countedByEn: string | null;
  lineCount?: number;
  version: number;
  lines?: StockCountLine[];
}

export interface PurchaseRequestItem {
  publicId: string;
  quantity: number;
  notes: string | null;
  itemPublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  baseUnit: string;
}

export interface PurchaseRequest {
  publicId: string;
  requestNumber: string;
  status: string;
  notes: string | null;
  requestedAt: string;
  approvedAt: string | null;
  warehousePublicId: string;
  warehouseCode: string;
  warehouseNameAr: string;
  warehouseNameEn: string | null;
  requestedByAr: string | null;
  requestedByEn: string | null;
  lineCount?: number;
  version: number;
  items?: PurchaseRequestItem[];
}

export interface PurchaseOrderItem {
  publicId: string;
  quantityOrdered: number;
  quantityReceived: number;
  unitCost: Money;
  itemPublicId: string;
  code: string;
  nameAr: string;
  nameEn: string | null;
  baseUnit: string;
  trackExpiry: boolean;
}

export interface GoodsReceipt {
  publicId: string;
  grnNumber: string;
  status: string;
  receivedAt: string;
  notes: string | null;
}

export interface PurchaseOrder {
  publicId: string;
  poNumber: string;
  status: string;
  orderedAt: string;
  expectedAt: string | null;
  totalAmount: Money;
  notes: string | null;
  supplierPublicId: string;
  supplierCode: string;
  supplierNameAr: string;
  supplierNameEn: string | null;
  warehousePublicId: string;
  warehouseCode: string;
  warehouseNameAr: string;
  warehouseNameEn: string | null;
  purchaseRequestPublicId: string | null;
  purchaseRequestNumber: string | null;
  version: number;
  items?: PurchaseOrderItem[];
  receipts?: GoodsReceipt[];
  receipt?: {
    publicId: string;
    grnNumber: string;
    items: {
      itemPublicId: string;
      code: string;
      nameAr: string;
      quantity: number;
      batchNumber: string;
      expiryDate: string | null;
      unitCost: Money;
    }[];
    receivedAt: string;
  };
}

export interface StockAlerts {
  lowStock: {
    itemPublicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    baseUnit: string;
    warehousePublicId: string;
    warehouseCode: string;
    warehouseNameAr: string;
    warehouseNameEn: string | null;
    available: number;
    reorderLevel: number;
    reorderQuantity: number;
    shortage: number;
  }[];
  expiring: {
    batchPublicId: string;
    batchNumber: string;
    expiryDate: string;
    quantityAvailable: number;
    itemPublicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    warehouseCode: string;
    warehouseNameAr: string;
    warehouseNameEn: string | null;
    daysLeft: number;
  }[];
  expiredCount: number;
  warningDays: number;
  generatedAt: string;
}

export interface ValuationReport {
  items: {
    itemPublicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    baseUnit: string;
    warehousePublicId: string;
    warehouseCode: string;
    quantity: number;
    value: Money;
    averageCost: Money;
  }[];
  totalValue: Money;
  method: string;
  currency: string;
}

export interface ConsumptionReport {
  from: string;
  to: string;
  items: {
    itemPublicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    baseUnit: string;
    issued: number;
    writtenOff: number;
    transferredOut: number;
    movementCount: number;
  }[];
  totalIssued: number;
  totalWrittenOff: number;
  totalTransferredOut: number;
}

export interface StockLineInput {
  itemPublicId: string;
  warehousePublicId?: string;
  quantity: number;
  unitCost?: number;
  notes?: string;
  batchNumber?: string;
  lotNumber?: string;
  expiryDate?: string;
}

export interface IssueResult {
  documentPublicId: string;
  documentRef: string | null;
  reason: string | null;
  items: {
    itemPublicId: string;
    code: string;
    nameAr: string;
    nameEn: string | null;
    baseUnit: string;
    warehousePublicId: string;
    warehouseCode: string;
    quantity: number;
    value: Money;
    batches: Record<string, unknown>[];
  }[];
  totalValue: Money;
  issuedAt: string;
}

export interface WriteOffResult {
  itemPublicId: string;
  code: string;
  quantity: number;
  reason: string;
  value: Money;
  batches: Record<string, unknown>[];
}

export const ITEM_CATEGORIES = [
  "SUPPLY",
  "CONSUMABLE",
  "EQUIPMENT",
  "LINEN",
  "FOOD",
  "OTHER",
];

export const BASE_UNITS = [
  "BOX",
  "PACK",
  "PIECE",
  "BOTTLE",
  "VIAL",
  "TUBE",
  "ROLL",
  "LITRE",
  "KG",
  "SET",
];

export const WAREHOUSE_TYPES = ["MAIN", "PHARMACY", "ER", "WARD", "LAB", "OTHER"];

export const INVENTORY_MOVEMENT_TYPES = [
  "RECEIPT",
  "ISSUE",
  "WRITE_OFF",
  "ADJUSTMENT",
  "TRANSFER_IN",
  "TRANSFER_OUT",
];

export const inventoryApi = {
  warehouses(filters: { q?: string; activeOnly?: boolean } = {}) {
    const params = new URLSearchParams({
      activeOnly: String(filters.activeOnly ?? false),
    });
    if (filters.q) params.set("q", filters.q);
    return api<{ items: Warehouse[]; total: number }>(
      `/inventory/warehouses?${params.toString()}`,
    );
  },
  createWarehouse(body: {
    code: string;
    nameAr: string;
    nameEn?: string;
    warehouseType?: string;
    location?: string;
  }) {
    return api<Warehouse>("/inventory/warehouses", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateWarehouse(
    publicId: string,
    body: {
      version?: number;
      nameAr?: string;
      nameEn?: string;
      warehouseType?: string;
      location?: string;
      active?: boolean;
    },
  ) {
    return api<Warehouse>(`/inventory/warehouses/${publicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  items(filters: {
    q?: string;
    category?: string;
    activeOnly?: boolean;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
      activeOnly: String(filters.activeOnly ?? false),
    });
    if (filters.q) params.set("q", filters.q);
    if (filters.category) params.set("category", filters.category);
    return api<PageResult<InventoryItem>>(`/inventory/items?${params.toString()}`);
  },
  item(publicId: string) {
    return api<InventoryItem>(`/inventory/items/${publicId}`);
  },
  createItem(body: {
    code: string;
    sku: string;
    nameAr: string;
    nameEn?: string;
    category?: string;
    baseUnit: string;
    trackExpiry?: boolean;
    unitCost?: number;
    barcode?: string;
    reorderLevel?: number;
    reorderWarehousePublicId?: string;
  }) {
    return api<InventoryItem>("/inventory/items", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateItem(
    publicId: string,
    body: {
      version?: number;
      nameAr?: string;
      nameEn?: string;
      category?: string;
      baseUnit?: string;
      trackExpiry?: boolean;
      unitCost?: number;
      barcode?: string;
      active?: boolean;
    },
  ) {
    return api<InventoryItem>(`/inventory/items/${publicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  suppliers(filters: { q?: string; activeOnly?: boolean } = {}) {
    const params = new URLSearchParams({
      activeOnly: String(filters.activeOnly ?? false),
    });
    if (filters.q) params.set("q", filters.q);
    return api<{ items: Supplier[]; total: number }>(
      `/inventory/suppliers?${params.toString()}`,
    );
  },
  createSupplier(body: {
    code: string;
    nameAr: string;
    nameEn?: string;
    phone?: string;
    email?: string;
    address?: string;
    contactPerson?: string;
  }) {
    return api<Supplier>("/inventory/suppliers", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateSupplier(
    publicId: string,
    body: {
      version?: number;
      nameAr?: string;
      nameEn?: string;
      phone?: string;
      email?: string;
      address?: string;
      contactPerson?: string;
      active?: boolean;
    },
  ) {
    return api<Supplier>(`/inventory/suppliers/${publicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  batches(filters: {
    q?: string;
    item?: string;
    warehouse?: string;
    status?: string;
    expiryBefore?: string;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.q) params.set("q", filters.q);
    if (filters.item) params.set("item", filters.item);
    if (filters.warehouse) params.set("warehouse", filters.warehouse);
    if (filters.status) params.set("status", filters.status);
    if (filters.expiryBefore) params.set("expiryBefore", filters.expiryBefore);
    return api<PageResult<InventoryBatch>>(`/inventory/batches?${params.toString()}`);
  },
  receiveBatch(body: {
    itemPublicId: string;
    warehousePublicId: string;
    batchNumber: string;
    quantity: number;
    expiryDate?: string;
    unitCost?: number;
    supplierPublicId?: string;
    lotNumber?: string;
    reason?: string;
  }) {
    return api<InventoryBatch>("/inventory/batches", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  issue(body: {
    documentRef?: string;
    reason?: string;
    items: StockLineInput[];
  }) {
    return api<IssueResult>("/inventory/issues", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  writeOff(body: {
    itemPublicId: string;
    quantity: number;
    reason: string;
    warehousePublicId?: string;
    batchPublicId?: string;
  }) {
    return api<WriteOffResult>("/inventory/write-offs", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  transfer(body: {
    fromWarehousePublicId: string;
    toWarehousePublicId: string;
    documentRef?: string;
    reason?: string;
    items: StockLineInput[];
  }) {
    return api<StockTransfer>("/inventory/transfers", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  transfers(filters: { page?: number; size?: number } = {}) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    return api<PageResult<StockTransfer>>(`/inventory/transfers?${params.toString()}`);
  },
  movements(filters: {
    item?: string;
    warehouse?: string;
    type?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.item) params.set("item", filters.item);
    if (filters.warehouse) params.set("warehouse", filters.warehouse);
    if (filters.type) params.set("type", filters.type);
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    return api<PageResult<InventoryMovement>>(`/inventory/movements?${params.toString()}`);
  },
  alerts() {
    return api<StockAlerts>("/inventory/alerts");
  },
  counts(filters: { status?: string; page?: number; size?: number }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    return api<PageResult<StockCount>>(`/inventory/counts?${params.toString()}`);
  },
  count(publicId: string) {
    return api<StockCount>(`/inventory/counts/${publicId}`);
  },
  createCount(body: { warehousePublicId: string; reason?: string }) {
    return api<StockCount>("/inventory/counts", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  updateCountLine(
    countPublicId: string,
    linePublicId: string,
    body: { countedQuantity: number },
  ) {
    return api<StockCount>(`/inventory/counts/${countPublicId}/lines/${linePublicId}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  },
  postCount(
    countPublicId: string,
    body: { reason?: string; approvedByPublicId?: string },
  ) {
    return api<StockCount>(`/inventory/counts/${countPublicId}/post`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  purchaseRequests(filters: { status?: string; page?: number; size?: number }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    return api<PageResult<PurchaseRequest>>(
      `/inventory/purchase-requests?${params.toString()}`,
    );
  },
  createPurchaseRequest(body: {
    warehousePublicId: string;
    status?: string;
    notes?: string;
    items: StockLineInput[];
  }) {
    return api<PurchaseRequest>("/inventory/purchase-requests", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  approvePurchaseRequest(
    publicId: string,
    body: { decision: "APPROVE" | "REJECT"; notes?: string },
  ) {
    return api<PurchaseRequest>(`/inventory/purchase-requests/${publicId}/approve`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  purchaseOrders(filters: { status?: string; page?: number; size?: number }) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.status) params.set("status", filters.status);
    return api<PageResult<PurchaseOrder>>(
      `/inventory/purchase-orders?${params.toString()}`,
    );
  },
  purchaseOrder(publicId: string) {
    return api<PurchaseOrder>(`/inventory/purchase-orders/${publicId}`);
  },
  createPurchaseOrder(body: {
    supplierPublicId: string;
    warehousePublicId: string;
    purchaseRequestPublicId?: string;
    expectedAt?: string;
    notes?: string;
    items?: StockLineInput[];
  }) {
    return api<PurchaseOrder>("/inventory/purchase-orders", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  receivePurchaseOrder(
    publicId: string,
    body: { notes?: string; items: StockLineInput[] },
  ) {
    return api<PurchaseOrder>(`/inventory/purchase-orders/${publicId}/receive`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  valuation(warehouse?: string) {
    const suffix = warehouse ? `?warehouse=${encodeURIComponent(warehouse)}` : "";
    return api<ValuationReport>(`/inventory/reports/valuation${suffix}`);
  },
  consumption(filters: { from?: string; to?: string; item?: string }) {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.item) params.set("item", filters.item);
    const suffix = params.toString() ? `?${params.toString()}` : "";
    return api<ConsumptionReport>(`/inventory/reports/consumption${suffix}`);
  },
};
