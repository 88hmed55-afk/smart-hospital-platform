import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { formatDate, formatDateTime, todayInput } from "../../shared/ui/format";
import {
  BASE_UNITS,
  INVENTORY_MOVEMENT_TYPES,
  ITEM_CATEGORIES,
  WAREHOUSE_TYPES,
  inventoryApi,
  type InventoryBatch,
  type InventoryItem,
  type InventoryMovement,
  type Money,
  type PurchaseOrder,
  type PurchaseRequest,
  type StockCountLine,
  type StockLineInput,
  type Supplier,
  type Warehouse,
} from "./inventoryApi";

type Tab =
  | "items"
  | "warehouses"
  | "stock"
  | "movements"
  | "counts"
  | "purchases"
  | "alerts"
  | "reports";

const TABS: Tab[] = [
  "items",
  "warehouses",
  "stock",
  "movements",
  "counts",
  "purchases",
  "alerts",
  "reports",
];

function money(value: Money | null | undefined, currency = "JOD"): string {
  if (value === null || value === undefined || value === "") {
    return "—";
  }
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) {
    return "—";
  }
  return `${numeric.toFixed(2)} ${currency}`;
}

function personName(
  locale: Locale,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return locale === "ar" ? ar || en || "—" : en || ar || "—";
}

function inventoryBadge(status: string): string {
  switch (status) {
    case "ACTIVE":
    case "APPROVED":
    case "RECEIVED":
    case "POSTED":
      return "badge-success";
    case "COUNTING":
    case "SUBMITTED":
    case "ORDERED":
    case "PARTIALLY_RECEIVED":
    case "PARTIAL":
      return "badge-warn";
    case "DEPLETED":
    case "REJECTED":
    case "CANCELLED":
    case "EXPIRED":
      return "badge-danger";
    case "DRAFT":
    case "CONVERTED":
      return "badge-info";
    default:
      return "badge-muted";
  }
}

function firstOfMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`;
}

interface TabProps {
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}

function useInventoryOptions(enabled: boolean) {
  const itemsQuery = useQuery({
    queryKey: ["inv", "items", "options"],
    queryFn: () => inventoryApi.items({ activeOnly: true, size: 200 }),
    enabled,
    staleTime: 120_000,
  });
  const warehousesQuery = useQuery({
    queryKey: ["inv", "warehouses", "options"],
    queryFn: () => inventoryApi.warehouses({ activeOnly: true }),
    enabled,
    staleTime: 120_000,
  });
  const suppliersQuery = useQuery({
    queryKey: ["inv", "suppliers", "options"],
    queryFn: () => inventoryApi.suppliers({ activeOnly: true }),
    enabled,
    staleTime: 120_000,
  });
  return {
    items: itemsQuery.data?.items ?? [],
    warehouses: warehousesQuery.data?.items ?? [],
    suppliers: suppliersQuery.data?.items ?? [],
  };
}

function itemLabel(locale: Locale, item: InventoryItem): string {
  return `${item.code} · ${personName(locale, item.nameAr, item.nameEn)}`;
}

interface LineDraft {
  key: string;
  itemPublicId: string;
  quantity: string;
  unitCost: string;
  notes: string;
}

function newLine(): LineDraft {
  return {
    key: crypto.randomUUID(),
    itemPublicId: "",
    quantity: "1",
    unitCost: "",
    notes: "",
  };
}

function linesToPayload(
  lines: LineDraft[],
  options: { cost?: boolean; notes?: boolean } = {},
): StockLineInput[] {
  const payload: StockLineInput[] = [];
  for (const line of lines) {
    const quantity = Number(line.quantity);
    if (!line.itemPublicId || !Number.isFinite(quantity) || quantity <= 0) {
      continue;
    }
    const entry: StockLineInput = { itemPublicId: line.itemPublicId, quantity };
    if (options.cost && line.unitCost.trim()) {
      entry.unitCost = Number(line.unitCost);
    }
    if (options.notes && line.notes.trim()) {
      entry.notes = line.notes.trim();
    }
    payload.push(entry);
  }
  return payload;
}

function LinesEditor({
  items,
  lines,
  onChange,
  showCost,
  showNotes,
}: {
  items: InventoryItem[];
  lines: LineDraft[];
  onChange: (lines: LineDraft[]) => void;
  showCost?: boolean;
  showNotes?: boolean;
}) {
  const { t, locale } = useI18n();

  const update = (key: string, patch: Partial<LineDraft>) => {
    onChange(lines.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  };

  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>{t("inv.item")}</th>
            <th>{t("common.quantity")}</th>
            {showCost && <th>{t("inv.unitCost")}</th>}
            {showNotes && <th>{t("common.notes")}</th>}
            <th />
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.key}>
              <td>
                <select
                  value={line.itemPublicId}
                  onChange={(event) => update(line.key, { itemPublicId: event.target.value })}
                >
                  <option value="">{t("common.select")}</option>
                  {items.map((item) => (
                    <option key={item.publicId} value={item.publicId}>
                      {itemLabel(locale, item)}
                    </option>
                  ))}
                </select>
              </td>
              <td>
                <input
                  type="number"
                  min={1}
                  step={1}
                  value={line.quantity}
                  onChange={(event) => update(line.key, { quantity: event.target.value })}
                />
              </td>
              {showCost && (
                <td>
                  <input
                    type="number"
                    min={0}
                    step="0.01"
                    value={line.unitCost}
                    onChange={(event) => update(line.key, { unitCost: event.target.value })}
                  />
                </td>
              )}
              {showNotes && (
                <td>
                  <input
                    type="text"
                    value={line.notes}
                    maxLength={500}
                    onChange={(event) => update(line.key, { notes: event.target.value })}
                  />
                </td>
              )}
              <td className="cell-actions">
                <button
                  type="button"
                  className="button button-ghost button-sm"
                  onClick={() => onChange(lines.filter((row) => row.key !== line.key))}
                >
                  {t("common.remove")}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="form-actions form-actions-start">
        <button
          type="button"
          className="button button-ghost button-sm"
          onClick={() => onChange([...lines, newLine()])}
        >
          {t("inv.addLine")}
        </button>
      </div>
    </div>
  );
}

export function InventoryPage() {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<Tab>("items");
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const onSuccess = (message: string) => {
    setNotice(message);
    setActionError(null);
    void queryClient.invalidateQueries({ queryKey: ["inv"] });
  };
  const onError = (error: unknown) => {
    setNotice(null);
    setActionError(error);
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("inv.title")}</h1>
          <p className="muted">{t("inv.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              setNotice(null);
              setActionError(null);
              void queryClient.invalidateQueries({ queryKey: ["inv"] });
            }}
          >
            {t("common.refresh")}
          </button>
        </div>
      </header>

      {notice && (
        <div className="alert alert-success" role="status">
          {notice}
        </div>
      )}
      {actionError ? (
        <div className="alert alert-danger" role="alert">
          {describeApiError(actionError, locale, t("common.saveError"))}
        </div>
      ) : null}

      <div className="tabs" role="tablist">
        {TABS.map((key) => (
          <button
            key={key}
            type="button"
            className={`tab${tab === key ? " is-active" : ""}`}
            onClick={() => {
              setTab(key);
              setNotice(null);
              setActionError(null);
            }}
          >
            {t(`inv.tab.${key}`)}
          </button>
        ))}
      </div>

      {tab === "items" && <ItemsTab onSuccess={onSuccess} onError={onError} />}
      {tab === "warehouses" && <WarehousesTab onSuccess={onSuccess} onError={onError} />}
      {tab === "stock" && <StockTab onSuccess={onSuccess} onError={onError} />}
      {tab === "movements" && <MovementsTab />}
      {tab === "counts" && <CountsTab onSuccess={onSuccess} onError={onError} />}
      {tab === "purchases" && <PurchasesTab onSuccess={onSuccess} onError={onError} />}
      {tab === "alerts" && <AlertsTab />}
      {tab === "reports" && <ReportsTab />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Items
// ---------------------------------------------------------------------------

function ItemsTab({ onSuccess, onError }: TabProps) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("INV:WRITE");
  const [term, setTerm] = useState("");
  const [category, setCategory] = useState("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);

  const items = useQuery({
    queryKey: ["inv", "items", term, category, page],
    queryFn: () =>
      inventoryApi.items({
        q: term.trim() || undefined,
        category: category || undefined,
        page,
        size: 20,
      }),
  });

  const createItem = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createItem>[0]) =>
      inventoryApi.createItem(body),
    onSuccess: () => {
      setCreateOpen(false);
      onSuccess(t("inv.itemCreated"));
    },
    onError,
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.items")}</h2>
          {canWrite && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createItem.reset();
                setCreateOpen(true);
              }}
            >
              {t("inv.newItem")}
            </button>
          )}
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.search")}</span>
            <input
              type="search"
              value={term}
              onChange={(event) => {
                setTerm(event.target.value);
                setPage(0);
              }}
              placeholder={t("inv.searchPlaceholder")}
            />
          </label>
          <label className="field field-inline">
            <span>{t("inv.category")}</span>
            <select
              value={category}
              onChange={(event) => {
                setCategory(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              {ITEM_CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {t(`invCategory.${value}`)}
                </option>
              ))}
            </select>
          </label>
        </div>
        {items.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : items.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !items.data || items.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noItems")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("common.code")}</th>
                    <th>{t("inv.item")}</th>
                    <th>{t("inv.sku")}</th>
                    <th>{t("inv.category")}</th>
                    <th>{t("inv.baseUnit")}</th>
                    <th>{t("inv.stockOnHand")}</th>
                    <th>{t("inv.reorderLevel")}</th>
                    <th>{t("inv.unitCost")}</th>
                    <th>{t("common.status")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {items.data.items.map((item) => (
                    <tr key={item.publicId}>
                      <td className="mono">{item.code}</td>
                      <td>{personName(locale, item.nameAr, item.nameEn)}</td>
                      <td className="mono">{item.sku}</td>
                      <td>{t(`invCategory.${item.category}`)}</td>
                      <td>{item.baseUnit}</td>
                      <td className="mono">{item.stockOnHand}</td>
                      <td className="mono">{item.reorderLevel}</td>
                      <td className="mono">{money(item.unitCost)}</td>
                      <td>
                        <span
                          className={`badge ${item.active ? "badge-success" : "badge-muted"}`}
                        >
                          {item.active ? t("common.yes") : t("common.no")}
                        </span>
                      </td>
                      <td className="cell-actions">
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => setDetailId(item.publicId)}
                        >
                          {t("common.view")}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={items.data.page}
              totalPages={items.data.totalPages}
              total={items.data.total}
              onChange={setPage}
            />
          </>
        )}
      </section>

      {createOpen && (
        <NewItemModal
          pending={createItem.isPending}
          error={createItem.error}
          onClose={() => setCreateOpen(false)}
          onSubmit={(body) => createItem.mutate(body)}
        />
      )}

      {detailId && (
        <ItemDetailModal
          publicId={detailId}
          canWrite={canWrite}
          onClose={() => setDetailId(null)}
          onSuccess={onSuccess}
          onError={onError}
        />
      )}
    </div>
  );
}

function NewItemModal({
  pending,
  error,
  onClose,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: Parameters<typeof inventoryApi.createItem>[0]) => void;
}) {
  const { t, locale } = useI18n();
  const [code, setCode] = useState("");
  const [sku, setSku] = useState("");
  const [nameAr, setNameAr] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [category, setCategory] = useState("SUPPLY");
  const [baseUnit, setBaseUnit] = useState("PIECE");
  const [trackExpiry, setTrackExpiry] = useState(true);
  const [unitCost, setUnitCost] = useState("0");
  const [barcode, setBarcode] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!code.trim() || !sku.trim() || !nameAr.trim() || !baseUnit.trim()) {
      return;
    }
    onSubmit({
      code: code.trim(),
      sku: sku.trim(),
      nameAr: nameAr.trim(),
      nameEn: nameEn.trim() || undefined,
      category,
      baseUnit: baseUnit.trim(),
      trackExpiry,
      unitCost: Number(unitCost) || 0,
      barcode: barcode.trim() || undefined,
    });
  }

  return (
    <Modal title={t("inv.newItem")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <div className="field-row">
          <label className="field">
            <span>{t("common.code")}</span>
            <input
              type="text"
              value={code}
              maxLength={40}
              onChange={(event) => setCode(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("inv.sku")}</span>
            <input
              type="text"
              value={sku}
              maxLength={60}
              onChange={(event) => setSku(event.target.value)}
              required
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.nameAr")}</span>
            <input
              type="text"
              value={nameAr}
              maxLength={200}
              onChange={(event) => setNameAr(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("inv.nameEn")}</span>
            <input
              type="text"
              value={nameEn}
              maxLength={200}
              onChange={(event) => setNameEn(event.target.value)}
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.category")}</span>
            <select value={category} onChange={(event) => setCategory(event.target.value)}>
              {ITEM_CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {t(`invCategory.${value}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("inv.baseUnit")}</span>
            <input
              type="text"
              value={baseUnit}
              list="inv-base-units"
              maxLength={20}
              onChange={(event) => setBaseUnit(event.target.value)}
              required
            />
            <datalist id="inv-base-units">
              {BASE_UNITS.map((value) => (
                <option key={value} value={value} />
              ))}
            </datalist>
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.unitCost")}</span>
            <input
              type="number"
              min={0}
              step="0.01"
              value={unitCost}
              onChange={(event) => setUnitCost(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("inv.barcode")}</span>
            <input
              type="text"
              value={barcode}
              maxLength={80}
              onChange={(event) => setBarcode(event.target.value)}
            />
          </label>
        </div>
        <label className="field field-inline">
          <input
            type="checkbox"
            checked={trackExpiry}
            onChange={(event) => setTrackExpiry(event.target.checked)}
          />
          <span>{t("inv.trackExpiry")}</span>
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.create")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ItemDetailModal({
  publicId,
  canWrite,
  onClose,
  onSuccess,
  onError,
}: {
  publicId: string;
  canWrite: boolean;
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const detail = useQuery({
    queryKey: ["inv", "item", publicId],
    queryFn: () => inventoryApi.item(publicId),
  });

  const updateItem = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.updateItem>[1]) =>
      inventoryApi.updateItem(publicId, body),
    onSuccess: () => onSuccess(t("common.saved")),
    onError,
  });

  const item = detail.data;

  return (
    <Modal title={item ? itemLabel(locale, item) : t("inv.item")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !item ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("inv.sku")}</span>
              <strong className="mono">{item.sku}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.category")}</span>
              <strong>{t(`invCategory.${item.category}`)}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.baseUnit")}</span>
              <strong>{item.baseUnit}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.stockOnHand")}</span>
              <strong className="mono">{item.stockOnHand}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.unitCost")}</span>
              <strong className="mono">{money(item.unitCost)}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.barcode")}</span>
              <strong className="mono">{item.barcode ?? "—"}</strong>
            </div>
          </div>

          <section>
            <h3>{t("inv.batches")}</h3>
            {(item.batches ?? []).length === 0 ? (
              <p className="empty-inline">{t("inv.noBatches")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("inv.batchNumber")}</th>
                      <th>{t("inv.warehouse")}</th>
                      <th>{t("inv.expiryDate")}</th>
                      <th>{t("inv.available")}</th>
                      <th>{t("common.status")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(item.batches ?? []).map((batch) => (
                      <tr key={batch.publicId}>
                        <td className="mono">{batch.batchNumber}</td>
                        <td>
                          {batch.warehouseCode} ·{" "}
                          {personName(
                            locale,
                            batch.warehouseNameAr,
                            batch.warehouseNameEn,
                          )}
                        </td>
                        <td>{formatDate(batch.expiryDate, locale)}</td>
                        <td className="mono">{batch.quantityAvailable}</td>
                        <td>
                          <span className={`badge ${inventoryBadge(batch.status)}`}>
                            {batch.status}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section>
            <h3>{t("inv.reorderPoints")}</h3>
            {(item.reorderPoints ?? []).length === 0 ? (
              <p className="empty-inline">{t("inv.noReorderPoints")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("inv.warehouse")}</th>
                      <th>{t("inv.reorderLevel")}</th>
                      <th>{t("inv.reorderQuantity")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(item.reorderPoints ?? []).map((point) => (
                      <tr key={point.publicId}>
                        <td className="mono">{point.warehouseCode}</td>
                        <td className="mono">{point.reorderLevel}</td>
                        <td className="mono">{point.reorderQuantity}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {canWrite && (
            <EditItemForm
              key={item.version}
              item={item}
              pending={updateItem.isPending}
              error={updateItem.error}
              onSubmit={(body) => updateItem.mutate(body)}
            />
          )}
        </div>
      )}
    </Modal>
  );
}

function EditItemForm({
  item,
  pending,
  error,
  onSubmit,
}: {
  item: InventoryItem;
  pending: boolean;
  error: unknown;
  onSubmit: (body: Parameters<typeof inventoryApi.updateItem>[1]) => void;
}) {
  const { t, locale } = useI18n();
  const [nameAr, setNameAr] = useState(item.nameAr);
  const [nameEn, setNameEn] = useState(item.nameEn ?? "");
  const [category, setCategory] = useState(item.category);
  const [baseUnit, setBaseUnit] = useState(item.baseUnit);
  const [trackExpiry, setTrackExpiry] = useState(item.trackExpiry);
  const [unitCost, setUnitCost] = useState(String(item.unitCost ?? "0"));
  const [barcode, setBarcode] = useState(item.barcode ?? "");
  const [active, setActive] = useState(item.active);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      version: item.version,
      nameAr: nameAr.trim(),
      nameEn: nameEn.trim(),
      category,
      baseUnit: baseUnit.trim(),
      trackExpiry,
      unitCost: Number(unitCost) || 0,
      barcode: barcode.trim(),
      active,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("common.edit")}</h3>
      <div className="field-row">
        <label className="field">
          <span>{t("inv.nameAr")}</span>
          <input
            type="text"
            value={nameAr}
            maxLength={200}
            onChange={(event) => setNameAr(event.target.value)}
            required
          />
        </label>
        <label className="field">
          <span>{t("inv.nameEn")}</span>
          <input
            type="text"
            value={nameEn}
            maxLength={200}
            onChange={(event) => setNameEn(event.target.value)}
          />
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>{t("inv.category")}</span>
          <select value={category} onChange={(event) => setCategory(event.target.value)}>
            {ITEM_CATEGORIES.map((value) => (
              <option key={value} value={value}>
                {t(`invCategory.${value}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("inv.baseUnit")}</span>
          <input
            type="text"
            value={baseUnit}
            maxLength={20}
            onChange={(event) => setBaseUnit(event.target.value)}
            required
          />
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>{t("inv.unitCost")}</span>
          <input
            type="number"
            min={0}
            step="0.01"
            value={unitCost}
            onChange={(event) => setUnitCost(event.target.value)}
          />
        </label>
        <label className="field">
          <span>{t("inv.barcode")}</span>
          <input
            type="text"
            value={barcode}
            maxLength={80}
            onChange={(event) => setBarcode(event.target.value)}
          />
        </label>
      </div>
      <div className="field-row">
        <label className="field field-inline">
          <input
            type="checkbox"
            checked={trackExpiry}
            onChange={(event) => setTrackExpiry(event.target.checked)}
          />
          <span>{t("inv.trackExpiry")}</span>
        </label>
        <label className="field field-inline">
          <input
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />
          <span>{t("inv.active")}</span>
        </label>
      </div>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="submit" className="button" disabled={pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------------------
// Warehouses and suppliers
// ---------------------------------------------------------------------------

function WarehousesTab({ onSuccess, onError }: TabProps) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("INV:WRITE");
  const canSupplier = hasPermission("INV:SUPPLIER");
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Warehouse | null>(null);
  const [supplierOpen, setSupplierOpen] = useState(false);
  const [supplierTarget, setSupplierTarget] = useState<Supplier | null>(null);

  const warehouses = useQuery({
    queryKey: ["inv", "warehouses", "list"],
    queryFn: () => inventoryApi.warehouses(),
  });
  const suppliers = useQuery({
    queryKey: ["inv", "suppliers", "list"],
    queryFn: () => inventoryApi.suppliers(),
  });

  const createWarehouse = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createWarehouse>[0]) =>
      inventoryApi.createWarehouse(body),
    onSuccess: () => {
      setCreateOpen(false);
      onSuccess(t("inv.warehouseCreated"));
    },
    onError,
  });

  const updateWarehouse = useMutation({
    mutationFn: (args: {
      publicId: string;
      body: Parameters<typeof inventoryApi.updateWarehouse>[1];
    }) => inventoryApi.updateWarehouse(args.publicId, args.body),
    onSuccess: () => {
      setEditTarget(null);
      onSuccess(t("common.saved"));
    },
    onError,
  });

  const createSupplier = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createSupplier>[0]) =>
      inventoryApi.createSupplier(body),
    onSuccess: () => {
      setSupplierOpen(false);
      onSuccess(t("inv.supplierCreated"));
    },
    onError,
  });

  const updateSupplier = useMutation({
    mutationFn: (args: {
      publicId: string;
      body: Parameters<typeof inventoryApi.updateSupplier>[1];
    }) => inventoryApi.updateSupplier(args.publicId, args.body),
    onSuccess: () => {
      setSupplierTarget(null);
      onSuccess(t("common.saved"));
    },
    onError,
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.warehouses")}</h2>
          {canWrite && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createWarehouse.reset();
                setCreateOpen(true);
              }}
            >
              {t("inv.newWarehouse")}
            </button>
          )}
        </header>
        {warehouses.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : warehouses.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !warehouses.data || warehouses.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noWarehouses")}</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("common.code")}</th>
                  <th>{t("common.name")}</th>
                  <th>{t("inv.warehouseType")}</th>
                  <th>{t("inv.location")}</th>
                  <th>{t("inv.batches")}</th>
                  <th>{t("common.quantity")}</th>
                  <th>{t("common.status")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {warehouses.data.items.map((warehouse) => (
                  <tr key={warehouse.publicId}>
                    <td className="mono">{warehouse.code}</td>
                    <td>{personName(locale, warehouse.nameAr, warehouse.nameEn)}</td>
                    <td>{t(`warehouseType.${warehouse.warehouseType}`)}</td>
                    <td>{warehouse.location ?? "—"}</td>
                    <td className="mono">{warehouse.batchCount}</td>
                    <td className="mono">{warehouse.totalQuantity}</td>
                    <td>
                      <span
                        className={`badge ${
                          warehouse.active ? "badge-success" : "badge-muted"
                        }`}
                      >
                        {warehouse.active ? t("inv.active") : t("inv.inactive")}
                      </span>
                    </td>
                    <td className="cell-actions">
                      {canWrite && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => {
                            updateWarehouse.reset();
                            setEditTarget(warehouse);
                          }}
                        >
                          {t("common.edit")}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <header className="card-header">
          <h2>{t("inv.suppliers")}</h2>
          {canSupplier && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createSupplier.reset();
                setSupplierOpen(true);
              }}
            >
              {t("inv.newSupplier")}
            </button>
          )}
        </header>
        {suppliers.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : suppliers.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !suppliers.data || suppliers.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noSuppliers")}</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("common.code")}</th>
                  <th>{t("common.name")}</th>
                  <th>{t("inv.phone")}</th>
                  <th>{t("inv.contactPerson")}</th>
                  <th>{t("common.status")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {suppliers.data.items.map((supplier) => (
                  <tr key={supplier.publicId}>
                    <td className="mono">{supplier.code}</td>
                    <td>{personName(locale, supplier.nameAr, supplier.nameEn)}</td>
                    <td className="mono">{supplier.phone ?? "—"}</td>
                    <td>{supplier.contactPerson ?? "—"}</td>
                    <td>
                      <span
                        className={`badge ${supplier.active ? "badge-success" : "badge-muted"}`}
                      >
                        {supplier.active ? t("inv.active") : t("inv.inactive")}
                      </span>
                    </td>
                    <td className="cell-actions">
                      {canSupplier && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => {
                            updateSupplier.reset();
                            setSupplierTarget(supplier);
                          }}
                        >
                          {t("common.edit")}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {createOpen && (
        <WarehouseFormModal
          title={t("inv.newWarehouse")}
          pending={createWarehouse.isPending}
          error={createWarehouse.error}
          onClose={() => setCreateOpen(false)}
          onSubmit={(body) =>
            createWarehouse.mutate({
              code: body.code ?? "",
              nameAr: body.nameAr ?? "",
              nameEn: body.nameEn,
              warehouseType: body.warehouseType,
              location: body.location,
            })
          }
        />
      )}

      {editTarget && (
        <WarehouseFormModal
          title={t("common.edit")}
          initial={editTarget}
          pending={updateWarehouse.isPending}
          error={updateWarehouse.error}
          onClose={() => setEditTarget(null)}
          onSubmit={(body) =>
            updateWarehouse.mutate({ publicId: editTarget.publicId, body })
          }
        />
      )}

      {supplierOpen && (
        <SupplierFormModal
          title={t("inv.newSupplier")}
          pending={createSupplier.isPending}
          error={createSupplier.error}
          onClose={() => setSupplierOpen(false)}
          onSubmit={(body) =>
            createSupplier.mutate({
              code: body.code ?? "",
              nameAr: body.nameAr ?? "",
              nameEn: body.nameEn,
              phone: body.phone,
              email: body.email,
              address: body.address,
              contactPerson: body.contactPerson,
            })
          }
        />
      )}

      {supplierTarget && (
        <SupplierFormModal
          title={t("common.edit")}
          initial={supplierTarget}
          pending={updateSupplier.isPending}
          error={updateSupplier.error}
          onClose={() => setSupplierTarget(null)}
          onSubmit={(body) =>
            updateSupplier.mutate({ publicId: supplierTarget.publicId, body })
          }
        />
      )}
    </div>
  );
}

function WarehouseFormModal({
  title,
  initial,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  title: string;
  initial?: Warehouse;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: Parameters<typeof inventoryApi.updateWarehouse>[1] & { code?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [code, setCode] = useState(initial?.code ?? "");
  const [nameAr, setNameAr] = useState(initial?.nameAr ?? "");
  const [nameEn, setNameEn] = useState(initial?.nameEn ?? "");
  const [warehouseType, setWarehouseType] = useState(initial?.warehouseType ?? "MAIN");
  const [location, setLocation] = useState(initial?.location ?? "");
  const [active, setActive] = useState(initial?.active ?? true);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!nameAr.trim()) {
      return;
    }
    const body: Parameters<typeof inventoryApi.updateWarehouse>[1] & { code?: string } = {
      nameAr: nameAr.trim(),
      nameEn: nameEn.trim(),
      warehouseType,
      location: location.trim(),
      active,
    };
    if (initial) {
      body.version = initial.version;
    } else {
      body.code = code.trim();
    }
    onSubmit(body);
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        {!initial && (
          <label className="field">
            <span>{t("common.code")}</span>
            <input
              type="text"
              value={code}
              maxLength={20}
              onChange={(event) => setCode(event.target.value)}
              required
            />
          </label>
        )}
        <div className="field-row">
          <label className="field">
            <span>{t("inv.nameAr")}</span>
            <input
              type="text"
              value={nameAr}
              maxLength={120}
              onChange={(event) => setNameAr(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("inv.nameEn")}</span>
            <input
              type="text"
              value={nameEn}
              maxLength={120}
              onChange={(event) => setNameEn(event.target.value)}
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.warehouseType")}</span>
            <select
              value={warehouseType}
              onChange={(event) => setWarehouseType(event.target.value)}
            >
              {WAREHOUSE_TYPES.map((value) => (
                <option key={value} value={value}>
                  {t(`warehouseType.${value}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("inv.location")}</span>
            <input
              type="text"
              value={location}
              maxLength={200}
              onChange={(event) => setLocation(event.target.value)}
            />
          </label>
        </div>
        <label className="field field-inline">
          <input
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />
          <span>{t("inv.active")}</span>
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function SupplierFormModal({
  title,
  initial,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  title: string;
  initial?: Supplier;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: Parameters<typeof inventoryApi.updateSupplier>[1] & { code?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [code, setCode] = useState(initial?.code ?? "");
  const [nameAr, setNameAr] = useState(initial?.nameAr ?? "");
  const [nameEn, setNameEn] = useState(initial?.nameEn ?? "");
  const [phone, setPhone] = useState(initial?.phone ?? "");
  const [email, setEmail] = useState(initial?.email ?? "");
  const [address, setAddress] = useState(initial?.address ?? "");
  const [contactPerson, setContactPerson] = useState(initial?.contactPerson ?? "");
  const [active, setActive] = useState(initial?.active ?? true);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!nameAr.trim()) {
      return;
    }
    const body: Parameters<typeof inventoryApi.updateSupplier>[1] & { code?: string } = {
      nameAr: nameAr.trim(),
      nameEn: nameEn.trim(),
      phone: phone.trim(),
      email: email.trim(),
      address: address.trim(),
      contactPerson: contactPerson.trim(),
      active,
    };
    if (initial) {
      body.version = initial.version;
    } else {
      body.code = code.trim();
    }
    onSubmit(body);
  }

  return (
    <Modal title={title} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        {!initial && (
          <label className="field">
            <span>{t("common.code")}</span>
            <input
              type="text"
              value={code}
              maxLength={20}
              onChange={(event) => setCode(event.target.value)}
              required
            />
          </label>
        )}
        <div className="field-row">
          <label className="field">
            <span>{t("inv.nameAr")}</span>
            <input
              type="text"
              value={nameAr}
              maxLength={200}
              onChange={(event) => setNameAr(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("inv.nameEn")}</span>
            <input
              type="text"
              value={nameEn}
              maxLength={200}
              onChange={(event) => setNameEn(event.target.value)}
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.phone")}</span>
            <input
              type="text"
              value={phone}
              maxLength={40}
              onChange={(event) => setPhone(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("inv.email")}</span>
            <input
              type="email"
              value={email}
              maxLength={120}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
        </div>
        <label className="field">
          <span>{t("inv.address")}</span>
          <input
            type="text"
            value={address}
            maxLength={300}
            onChange={(event) => setAddress(event.target.value)}
          />
        </label>
        <label className="field">
          <span>{t("inv.contactPerson")}</span>
          <input
            type="text"
            value={contactPerson}
            maxLength={120}
            onChange={(event) => setContactPerson(event.target.value)}
          />
        </label>
        <label className="field field-inline">
          <input
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />
          <span>{t("inv.active")}</span>
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Stock (batches, receive, issue, write-off, transfer)
// ---------------------------------------------------------------------------

type StockPanel =
  | { kind: "receive" }
  | { kind: "issue" }
  | { kind: "writeOff"; batch?: InventoryBatch }
  | { kind: "transfer" }
  | null;

function StockTab({ onSuccess, onError }: TabProps) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canReceive = hasPermission("INV:RECEIVE");
  const canIssue = hasPermission("INV:ISSUE");
  const canWriteOff = hasPermission("INV:WRITE_OFF");
  const canTransfer = hasPermission("INV:TRANSFER");
  const [term, setTerm] = useState("");
  const [warehouse, setWarehouse] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [panel, setPanel] = useState<StockPanel>(null);

  const options = useInventoryOptions(true);

  const batches = useQuery({
    queryKey: ["inv", "batches", term, warehouse, status, page],
    queryFn: () =>
      inventoryApi.batches({
        q: term.trim() || undefined,
        warehouse: warehouse || undefined,
        status: status || undefined,
        page,
        size: 20,
      }),
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.batches")}</h2>
          <div className="header-actions">
            {canReceive && (
              <button
                type="button"
                className="button button-sm"
                onClick={() => setPanel({ kind: "receive" })}
              >
                {t("inv.receive")}
              </button>
            )}
            {canIssue && (
              <button
                type="button"
                className="button button-ghost button-sm"
                onClick={() => setPanel({ kind: "issue" })}
              >
                {t("inv.issue")}
              </button>
            )}
            {canTransfer && (
              <button
                type="button"
                className="button button-ghost button-sm"
                onClick={() => setPanel({ kind: "transfer" })}
              >
                {t("inv.transfer")}
              </button>
            )}
            {canWriteOff && (
              <button
                type="button"
                className="button button-ghost button-sm"
                onClick={() => setPanel({ kind: "writeOff" })}
              >
                {t("inv.writeOff")}
              </button>
            )}
          </div>
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.search")}</span>
            <input
              type="search"
              value={term}
              onChange={(event) => {
                setTerm(event.target.value);
                setPage(0);
              }}
              placeholder={t("inv.batchSearchPlaceholder")}
            />
          </label>
          <label className="field field-inline">
            <span>{t("inv.warehouse")}</span>
            <select
              value={warehouse}
              onChange={(event) => {
                setWarehouse(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              {options.warehouses.map((row) => (
                <option key={row.publicId} value={row.publicId}>
                  {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                </option>
              ))}
            </select>
          </label>
          <label className="field field-inline">
            <span>{t("common.status")}</span>
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              <option value="ACTIVE">{t("invBatchStatus.ACTIVE")}</option>
              <option value="DEPLETED">{t("invBatchStatus.DEPLETED")}</option>
              <option value="EXPIRED">{t("invBatchStatus.EXPIRED")}</option>
            </select>
          </label>
        </div>
        {batches.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : batches.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !batches.data || batches.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noBatches")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("inv.item")}</th>
                    <th>{t("inv.batchNumber")}</th>
                    <th>{t("inv.warehouse")}</th>
                    <th>{t("inv.expiryDate")}</th>
                    <th>{t("inv.available")}</th>
                    <th>{t("inv.received")}</th>
                    <th>{t("inv.unitCost")}</th>
                    <th>{t("common.status")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {batches.data.items.map((batch) => (
                    <tr key={batch.publicId}>
                      <td>
                        <span className="mono">{batch.code}</span> ·{" "}
                        {personName(locale, batch.nameAr, batch.nameEn)}
                      </td>
                      <td className="mono">{batch.batchNumber}</td>
                      <td className="mono">{batch.warehouseCode}</td>
                      <td>{formatDate(batch.expiryDate, locale)}</td>
                      <td className="mono">{batch.quantityAvailable}</td>
                      <td className="mono">{batch.quantityReceived}</td>
                      <td className="mono">{money(batch.unitCost)}</td>
                      <td>
                        <span className={`badge ${inventoryBadge(batch.status)}`}>
                          {t(`invBatchStatus.${batch.status}`)}
                        </span>
                      </td>
                      <td className="cell-actions">
                        {canWriteOff && batch.quantityAvailable > 0 && (
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => setPanel({ kind: "writeOff", batch })}
                          >
                            {t("inv.writeOff")}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={batches.data.page}
              totalPages={batches.data.totalPages}
              total={batches.data.total}
              onChange={setPage}
            />
          </>
        )}
      </section>

      {panel?.kind === "receive" && (
        <ReceiveModal
          items={options.items}
          warehouses={options.warehouses}
          suppliers={options.suppliers}
          onClose={() => setPanel(null)}
          onSuccess={(message) => {
            setPanel(null);
            onSuccess(message);
          }}
          onError={onError}
        />
      )}

      {panel?.kind === "issue" && (
        <IssueModal
          items={options.items}
          warehouses={options.warehouses}
          onClose={() => setPanel(null)}
          onSuccess={(message) => {
            setPanel(null);
            onSuccess(message);
          }}
          onError={onError}
        />
      )}

      {panel?.kind === "transfer" && (
        <TransferModal
          items={options.items}
          warehouses={options.warehouses}
          onClose={() => setPanel(null)}
          onSuccess={(message) => {
            setPanel(null);
            onSuccess(message);
          }}
          onError={onError}
        />
      )}

      {panel?.kind === "writeOff" && (
        <WriteOffModal
          items={options.items}
          warehouses={options.warehouses}
          batch={panel.batch}
          onClose={() => setPanel(null)}
          onSuccess={(message) => {
            setPanel(null);
            onSuccess(message);
          }}
          onError={onError}
        />
      )}
    </div>
  );
}

function ReceiveModal({
  items,
  warehouses,
  suppliers,
  onClose,
  onSuccess,
  onError,
}: {
  items: InventoryItem[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const [itemPublicId, setItemPublicId] = useState("");
  const [warehousePublicId, setWarehousePublicId] = useState("");
  const [batchNumber, setBatchNumber] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [expiryDate, setExpiryDate] = useState("");
  const [unitCost, setUnitCost] = useState("");
  const [supplierPublicId, setSupplierPublicId] = useState("");
  const [lotNumber, setLotNumber] = useState("");
  const [reason, setReason] = useState("");

  const selected = items.find((item) => item.publicId === itemPublicId) ?? null;

  const receive = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.receiveBatch>[0]) =>
      inventoryApi.receiveBatch(body),
    onSuccess: () => onSuccess(t("inv.receivedOk")),
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(quantity);
    if (!itemPublicId || !warehousePublicId || !batchNumber.trim() || numeric <= 0) {
      return;
    }
    receive.mutate({
      itemPublicId,
      warehousePublicId,
      batchNumber: batchNumber.trim(),
      quantity: numeric,
      expiryDate: expiryDate || undefined,
      unitCost: unitCost.trim() ? Number(unitCost) : undefined,
      supplierPublicId: supplierPublicId || undefined,
      lotNumber: lotNumber.trim() || undefined,
      reason: reason.trim() || undefined,
    });
  }

  return (
    <Modal title={t("inv.receiveTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("inv.item")}</span>
          <select
            value={itemPublicId}
            onChange={(event) => setItemPublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {items.map((item) => (
              <option key={item.publicId} value={item.publicId}>
                {itemLabel(locale, item)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("inv.warehouse")}</span>
          <select
            value={warehousePublicId}
            onChange={(event) => setWarehousePublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {warehouses.map((row) => (
              <option key={row.publicId} value={row.publicId}>
                {row.code} · {personName(locale, row.nameAr, row.nameEn)}
              </option>
            ))}
          </select>
        </label>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.batchNumber")}</span>
            <input
              type="text"
              value={batchNumber}
              maxLength={60}
              onChange={(event) => setBatchNumber(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("common.quantity")}</span>
            <input
              type="number"
              min={1}
              step={1}
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              required
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>
              {t("inv.expiryDate")}
              {selected?.trackExpiry ? ` (${t("common.required")})` : ""}
            </span>
            <input
              type="date"
              value={expiryDate}
              onChange={(event) => setExpiryDate(event.target.value)}
              required={selected?.trackExpiry ?? false}
            />
          </label>
          <label className="field">
            <span>{t("inv.unitCost")}</span>
            <input
              type="number"
              min={0}
              step="0.01"
              value={unitCost}
              onChange={(event) => setUnitCost(event.target.value)}
              placeholder={
                selected?.unitCost !== null && selected?.unitCost !== undefined
                  ? String(selected.unitCost)
                  : undefined
              }
            />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.supplier")}</span>
            <select
              value={supplierPublicId}
              onChange={(event) => setSupplierPublicId(event.target.value)}
            >
              <option value="">{t("common.none")}</option>
              {suppliers.map((supplier) => (
                <option key={supplier.publicId} value={supplier.publicId}>
                  {supplier.code} · {personName(locale, supplier.nameAr, supplier.nameEn)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("inv.lotNumber")}</span>
            <input
              type="text"
              value={lotNumber}
              maxLength={60}
              onChange={(event) => setLotNumber(event.target.value)}
            />
          </label>
        </div>
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={reason}
            rows={2}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        {receive.error ? (
          <p className="field-error" role="alert">
            {describeApiError(receive.error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={receive.isPending}>
            {receive.isPending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function IssueModal({
  items,
  warehouses,
  onClose,
  onSuccess,
  onError,
}: {
  items: InventoryItem[];
  warehouses: Warehouse[];
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const [warehousePublicId, setWarehousePublicId] = useState("");
  const [documentRef, setDocumentRef] = useState("");
  const [reason, setReason] = useState("");
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);

  const issue = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.issue>[0]) =>
      inventoryApi.issue(body),
    onSuccess: () => onSuccess(t("inv.issuedOk")),
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload = linesToPayload(lines).map((line) => ({
      ...line,
      warehousePublicId,
    }));
    if (!warehousePublicId || payload.length === 0 || !documentRef.trim()) {
      return;
    }
    issue.mutate({
      documentRef: documentRef.trim(),
      reason: reason.trim() || undefined,
      items: payload,
    });
  }

  return (
    <Modal title={t("inv.issueTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("inv.warehouse")}</span>
          <select
            value={warehousePublicId}
            onChange={(event) => setWarehousePublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {warehouses.map((row) => (
              <option key={row.publicId} value={row.publicId}>
                {row.code} · {personName(locale, row.nameAr, row.nameEn)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("inv.documentRef")}</span>
          <input
            type="text"
            value={documentRef}
            maxLength={120}
            onChange={(event) => setDocumentRef(event.target.value)}
            required
          />
        </label>
        <LinesEditor items={items} lines={lines} onChange={setLines} />
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={reason}
            rows={2}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        {issue.error ? (
          <p className="field-error" role="alert">
            {describeApiError(issue.error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={issue.isPending}>
            {issue.isPending ? t("common.saving") : t("inv.issue")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function TransferModal({
  items,
  warehouses,
  onClose,
  onSuccess,
  onError,
}: {
  items: InventoryItem[];
  warehouses: Warehouse[];
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const [fromWarehousePublicId, setFrom] = useState("");
  const [toWarehousePublicId, setTo] = useState("");
  const [documentRef, setDocumentRef] = useState("");
  const [reason, setReason] = useState("");
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);

  const transfer = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.transfer>[0]) =>
      inventoryApi.transfer(body),
    onSuccess: () => onSuccess(t("inv.transferredOk")),
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload = linesToPayload(lines);
    if (
      !fromWarehousePublicId ||
      !toWarehousePublicId ||
      fromWarehousePublicId === toWarehousePublicId ||
      !documentRef.trim() ||
      payload.length === 0
    ) {
      return;
    }
    transfer.mutate({
      fromWarehousePublicId,
      toWarehousePublicId,
      documentRef: documentRef.trim(),
      reason: reason.trim() || undefined,
      items: payload,
    });
  }

  return (
    <Modal title={t("inv.transferTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.fromWarehouse")}</span>
            <select
              value={fromWarehousePublicId}
              onChange={(event) => setFrom(event.target.value)}
              required
            >
              <option value="">{t("common.select")}</option>
              {warehouses.map((row) => (
                <option key={row.publicId} value={row.publicId}>
                  {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("inv.toWarehouse")}</span>
            <select
              value={toWarehousePublicId}
              onChange={(event) => setTo(event.target.value)}
              required
            >
              <option value="">{t("common.select")}</option>
              {warehouses
                .filter((row) => row.publicId !== fromWarehousePublicId)
                .map((row) => (
                  <option key={row.publicId} value={row.publicId}>
                    {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                  </option>
                ))}
            </select>
          </label>
        </div>
        <label className="field">
          <span>{t("inv.documentRef")}</span>
          <input
            type="text"
            value={documentRef}
            maxLength={120}
            onChange={(event) => setDocumentRef(event.target.value)}
            required
          />
        </label>
        <LinesEditor items={items} lines={lines} onChange={setLines} />
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={reason}
            rows={2}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        {transfer.error ? (
          <p className="field-error" role="alert">
            {describeApiError(transfer.error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={transfer.isPending}>
            {transfer.isPending ? t("common.saving") : t("inv.transfer")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function WriteOffModal({
  items,
  warehouses,
  batch,
  onClose,
  onSuccess,
  onError,
}: {
  items: InventoryItem[];
  warehouses: Warehouse[];
  batch?: InventoryBatch;
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const [itemPublicId, setItemPublicId] = useState(batch?.itemPublicId ?? "");
  const [warehousePublicId, setWarehousePublicId] = useState(
    batch?.warehousePublicId ?? "",
  );
  const [quantity, setQuantity] = useState("1");
  const [reason, setReason] = useState("");

  const writeOff = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.writeOff>[0]) =>
      inventoryApi.writeOff(body),
    onSuccess: () => onSuccess(t("inv.writtenOffOk")),
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(quantity);
    if (!itemPublicId || numeric <= 0 || !reason.trim()) {
      return;
    }
    if (!batch && !warehousePublicId) {
      return;
    }
    writeOff.mutate({
      itemPublicId,
      quantity: numeric,
      reason: reason.trim(),
      warehousePublicId: batch ? undefined : warehousePublicId,
      batchPublicId: batch?.publicId,
    });
  }

  return (
    <Modal title={t("inv.writeOffTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        {batch ? (
          <p className="field-hint">
            {t("inv.batchNumber")}: <span className="mono">{batch.batchNumber}</span> ·{" "}
            {personName(locale, batch.nameAr, batch.nameEn)} ·{" "}
            {t("inv.available")}: <span className="mono">{batch.quantityAvailable}</span>
          </p>
        ) : (
          <>
            <label className="field">
              <span>{t("inv.item")}</span>
              <select
                value={itemPublicId}
                onChange={(event) => setItemPublicId(event.target.value)}
                required
              >
                <option value="">{t("common.select")}</option>
                {items.map((item) => (
                  <option key={item.publicId} value={item.publicId}>
                    {itemLabel(locale, item)}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{t("inv.warehouse")}</span>
              <select
                value={warehousePublicId}
                onChange={(event) => setWarehousePublicId(event.target.value)}
                required
              >
                <option value="">{t("common.select")}</option>
                {warehouses.map((row) => (
                  <option key={row.publicId} value={row.publicId}>
                    {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                  </option>
                ))}
              </select>
            </label>
          </>
        )}
        <label className="field">
          <span>{t("common.quantity")}</span>
          <input
            type="number"
            min={1}
            step={1}
            max={batch?.quantityAvailable}
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            required
          />
        </label>
        <label className="field">
          <span>{t("inv.reason")}</span>
          <textarea
            value={reason}
            rows={2}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
            required
          />
        </label>
        {writeOff.error ? (
          <p className="field-error" role="alert">
            {describeApiError(writeOff.error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={writeOff.isPending}>
            {writeOff.isPending ? t("common.saving") : t("inv.writeOff")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Movements
// ---------------------------------------------------------------------------

function MovementsTab() {
  const { t, locale } = useI18n();
  const options = useInventoryOptions(true);
  const [warehouse, setWarehouse] = useState("");
  const [type, setType] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);

  const movements = useQuery({
    queryKey: ["inv", "movements", warehouse, type, from, to, page],
    queryFn: () =>
      inventoryApi.movements({
        warehouse: warehouse || undefined,
        type: type || undefined,
        from: from || undefined,
        to: to || undefined,
        page,
        size: 20,
      }),
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.movements")}</h2>
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("inv.warehouse")}</span>
            <select
              value={warehouse}
              onChange={(event) => {
                setWarehouse(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              {options.warehouses.map((row) => (
                <option key={row.publicId} value={row.publicId}>
                  {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                </option>
              ))}
            </select>
          </label>
          <label className="field field-inline">
            <span>{t("common.type")}</span>
            <select
              value={type}
              onChange={(event) => {
                setType(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              {INVENTORY_MOVEMENT_TYPES.map((value) => (
                <option key={value} value={value}>
                  {t(`invMove.${value}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="field field-inline">
            <span>{t("inv.from")}</span>
            <input
              type="date"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="field field-inline">
            <span>{t("inv.to")}</span>
            <input
              type="date"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
                setPage(0);
              }}
            />
          </label>
        </div>
        {movements.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : movements.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !movements.data || movements.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noMovements")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("inv.performedAt")}</th>
                    <th>{t("common.type")}</th>
                    <th>{t("inv.item")}</th>
                    <th>{t("inv.batchNumber")}</th>
                    <th>{t("inv.warehouse")}</th>
                    <th>{t("inv.quantityDelta")}</th>
                    <th>{t("inv.reference")}</th>
                    <th>{t("inv.performedBy")}</th>
                  </tr>
                </thead>
                <tbody>
                  {movements.data.items.map((row: InventoryMovement) => (
                    <tr key={row.publicId}>
                      <td>{formatDateTime(row.performedAt, locale)}</td>
                      <td>{t(`invMove.${row.movementType}`)}</td>
                      <td>
                        <span className="mono">{row.code}</span> ·{" "}
                        {personName(locale, row.nameAr, row.nameEn)}
                      </td>
                      <td className="mono">{row.batchNumber ?? "—"}</td>
                      <td className="mono">{row.warehouseCode}</td>
                      <td className="mono">{row.quantityDelta}</td>
                      <td>{row.referenceType ?? row.reason ?? "—"}</td>
                      <td>{personName(locale, row.performedByAr, row.performedByEn)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={movements.data.page}
              totalPages={movements.data.totalPages}
              total={movements.data.total}
              onChange={setPage}
            />
          </>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Stock counts
// ---------------------------------------------------------------------------

function CountsTab({ onSuccess, onError }: TabProps) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canCount = hasPermission("INV:COUNT");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const options = useInventoryOptions(createOpen);

  const counts = useQuery({
    queryKey: ["inv", "counts", status, page],
    queryFn: () =>
      inventoryApi.counts({ status: status || undefined, page, size: 20 }),
  });

  const createCount = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createCount>[0]) =>
      inventoryApi.createCount(body),
    onSuccess: (created) => {
      setCreateOpen(false);
      setDetailId(created.publicId);
      onSuccess(t("inv.countCreated"));
    },
    onError,
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.counts")}</h2>
          {canCount && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createCount.reset();
                setCreateOpen(true);
              }}
            >
              {t("inv.newCount")}
            </button>
          )}
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.status")}</span>
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              <option value="COUNTING">{t("invCountStatus.COUNTING")}</option>
              <option value="POSTED">{t("invCountStatus.POSTED")}</option>
              <option value="CANCELLED">{t("invCountStatus.CANCELLED")}</option>
            </select>
          </label>
        </div>
        {counts.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : counts.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !counts.data || counts.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noCounts")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("inv.countNumber")}</th>
                    <th>{t("inv.warehouse")}</th>
                    <th>{t("common.status")}</th>
                    <th>{t("inv.countedAt")}</th>
                    <th>{t("inv.postedAt")}</th>
                    <th>{t("inv.countedBy")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {counts.data.items.map((count) => (
                    <tr key={count.publicId}>
                      <td className="mono">{count.countNumber}</td>
                      <td>
                        {count.warehouseCode} ·{" "}
                        {personName(locale, count.warehouseNameAr, count.warehouseNameEn)}
                      </td>
                      <td>
                        <span className={`badge ${inventoryBadge(count.status)}`}>
                          {t(`invCountStatus.${count.status}`)}
                        </span>
                      </td>
                      <td>{formatDateTime(count.countedAt, locale)}</td>
                      <td>{formatDateTime(count.postedAt, locale)}</td>
                      <td>{personName(locale, count.countedByAr, count.countedByEn)}</td>
                      <td className="cell-actions">
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => setDetailId(count.publicId)}
                        >
                          {t("common.view")}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={counts.data.page}
              totalPages={counts.data.totalPages}
              total={counts.data.total}
              onChange={setPage}
            />
          </>
        )}
      </section>

      {createOpen && (
        <NewCountModal
          warehouses={options.warehouses}
          pending={createCount.isPending}
          error={createCount.error}
          onClose={() => setCreateOpen(false)}
          onSubmit={(body) => createCount.mutate(body)}
        />
      )}

      {detailId && (
        <CountDetailModal
          publicId={detailId}
          canCount={canCount}
          onClose={() => setDetailId(null)}
          onSuccess={onSuccess}
          onError={onError}
        />
      )}
    </div>
  );
}

function NewCountModal({
  warehouses,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  warehouses: Warehouse[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: { warehousePublicId: string; reason?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [warehousePublicId, setWarehousePublicId] = useState("");
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!warehousePublicId) {
      return;
    }
    onSubmit({ warehousePublicId, reason: reason.trim() || undefined });
  }

  return (
    <Modal title={t("inv.newCount")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("inv.warehouse")}</span>
          <select
            value={warehousePublicId}
            onChange={(event) => setWarehousePublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {warehouses.map((row) => (
              <option key={row.publicId} value={row.publicId}>
                {row.code} · {personName(locale, row.nameAr, row.nameEn)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={reason}
            rows={2}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.create")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function CountDetailModal({
  publicId,
  canCount,
  onClose,
  onSuccess,
  onError,
}: {
  publicId: string;
  canCount: boolean;
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [reason, setReason] = useState("");
  const [approver, setApprover] = useState("");

  const detail = useQuery({
    queryKey: ["inv", "count", publicId],
    queryFn: () => inventoryApi.count(publicId),
  });

  const saveLine = useMutation({
    mutationFn: (args: { linePublicId: string; countedQuantity: number }) =>
      inventoryApi.updateCountLine(publicId, args.linePublicId, {
        countedQuantity: args.countedQuantity,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["inv", "count", publicId] });
      await queryClient.invalidateQueries({ queryKey: ["inv", "counts"] });
    },
    onError,
  });

  const post = useMutation({
    mutationFn: (body: { reason?: string; approvedByPublicId?: string }) =>
      inventoryApi.postCount(publicId, body),
    onSuccess: async () => {
      onSuccess(t("inv.countPosted"));
      await queryClient.invalidateQueries({ queryKey: ["inv", "count", publicId] });
    },
    onError,
  });

  const count = detail.data;
  const open = count?.status === "COUNTING";
  const hasVariance = (count?.lines ?? []).some(
    (line) => line.variance !== null && line.variance !== 0,
  );

  function submitPost(event: FormEvent) {
    event.preventDefault();
    post.mutate({
      reason: reason.trim() || undefined,
      approvedByPublicId: hasPermission("INV:ADJUST")
        ? undefined
        : approver.trim() || undefined,
    });
  }

  return (
    <Modal title={count ? count.countNumber : t("inv.count")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !count ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("inv.warehouse")}</span>
              <strong>
                {count.warehouseCode} ·{" "}
                {personName(locale, count.warehouseNameAr, count.warehouseNameEn)}
              </strong>
            </div>
            <div>
              <span className="muted">{t("common.status")}</span>
              <span className={`badge ${inventoryBadge(count.status)}`}>
                {t(`invCountStatus.${count.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("inv.countedAt")}</span>
              <strong>{formatDateTime(count.countedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("inv.postedAt")}</span>
              <strong>{formatDateTime(count.postedAt, locale)}</strong>
            </div>
          </div>

          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("inv.item")}</th>
                  <th>{t("inv.batchNumber")}</th>
                  <th>{t("inv.expiryDate")}</th>
                  <th>{t("inv.systemQuantity")}</th>
                  <th>{t("inv.countedQuantity")}</th>
                  <th>{t("inv.variance")}</th>
                  {open && canCount && <th />}
                </tr>
              </thead>
              <tbody>
                {(count.lines ?? []).map((line: StockCountLine) => {
                  const value =
                    edits[line.publicId] ??
                    (line.countedQuantity === null ? "" : String(line.countedQuantity));
                  return (
                    <tr key={line.publicId}>
                      <td>
                        <span className="mono">{line.code}</span> ·{" "}
                        {personName(locale, line.nameAr, line.nameEn)}
                      </td>
                      <td className="mono">{line.batchNumber}</td>
                      <td>{formatDate(line.expiryDate, locale)}</td>
                      <td className="mono">{line.systemQuantity}</td>
                      <td className="mono">
                        {open && canCount ? (
                          <input
                            type="number"
                            min={0}
                            step={1}
                            value={value}
                            onChange={(event) =>
                              setEdits((current) => ({
                                ...current,
                                [line.publicId]: event.target.value,
                              }))
                            }
                          />
                        ) : (
                          line.countedQuantity ?? "—"
                        )}
                      </td>
                      <td className="mono">
                        {line.variance === null ? "—" : line.variance}
                      </td>
                      {open && canCount && (
                        <td className="cell-actions">
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            disabled={saveLine.isPending || !value.trim()}
                            onClick={() => {
                              const numeric = Number(value);
                              if (Number.isFinite(numeric) && numeric >= 0) {
                                saveLine.mutate({
                                  linePublicId: line.publicId,
                                  countedQuantity: numeric,
                                });
                              }
                            }}
                          >
                            {t("common.save")}
                          </button>
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {saveLine.error ? (
            <p className="field-error" role="alert">
              {describeApiError(saveLine.error, locale, t("common.saveError"))}
            </p>
          ) : null}

          {open && canCount && (
            <form className="stack-form" onSubmit={submitPost}>
              <h3>{t("inv.postCount")}</h3>
              {hasVariance && <p className="field-hint">{t("inv.varianceHint")}</p>}
              <label className="field">
                <span>{t("inv.varianceReason")}</span>
                <textarea
                  value={reason}
                  rows={2}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                  required={hasVariance}
                />
              </label>
              {!hasPermission("INV:ADJUST") && (
                <label className="field">
                  <span>{t("inv.approver")}</span>
                  <input
                    type="text"
                    value={approver}
                    maxLength={40}
                    onChange={(event) => setApprover(event.target.value)}
                    required
                  />
                  <small className="field-hint">{t("inv.approverHint")}</small>
                </label>
              )}
              {post.error ? (
                <p className="field-error" role="alert">
                  {describeApiError(post.error, locale, t("common.saveError"))}
                </p>
              ) : null}
              <div className="form-actions form-actions-start">
                <button type="submit" className="button" disabled={post.isPending}>
                  {post.isPending ? t("common.saving") : t("inv.postCount")}
                </button>
              </div>
            </form>
          )}
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Purchasing
// ---------------------------------------------------------------------------

function PurchasesTab({ onSuccess, onError }: TabProps) {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canRequest = hasPermission("INV:REQUEST");
  const canApprove = hasPermission("INV:APPROVE");
  const canOrder = hasPermission("INV:PO_CREATE");
  const canReceive = hasPermission("INV:RECEIVE");
  const options = useInventoryOptions(true);

  const [requestStatus, setRequestStatus] = useState("");
  const [requestPage, setRequestPage] = useState(0);
  const [orderStatus, setOrderStatus] = useState("");
  const [orderPage, setOrderPage] = useState(0);
  const [requestOpen, setRequestOpen] = useState(false);
  const [orderOpen, setOrderOpen] = useState(false);
  const [receiveTarget, setReceiveTarget] = useState<PurchaseOrder | null>(null);

  const requests = useQuery({
    queryKey: ["inv", "purchase-requests", requestStatus, requestPage],
    queryFn: () =>
      inventoryApi.purchaseRequests({
        status: requestStatus || undefined,
        page: requestPage,
        size: 20,
      }),
  });

  const orders = useQuery({
    queryKey: ["inv", "purchase-orders", orderStatus, orderPage],
    queryFn: () =>
      inventoryApi.purchaseOrders({
        status: orderStatus || undefined,
        page: orderPage,
        size: 20,
      }),
  });

  const createRequest = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createPurchaseRequest>[0]) =>
      inventoryApi.createPurchaseRequest(body),
    onSuccess: () => {
      setRequestOpen(false);
      onSuccess(t("inv.requestCreated"));
    },
    onError,
  });

  const decide = useMutation({
    mutationFn: (args: {
      publicId: string;
      decision: "APPROVE" | "REJECT";
    }) =>
      inventoryApi.approvePurchaseRequest(args.publicId, { decision: args.decision }),
    onSuccess: () => onSuccess(t("common.saved")),
    onError,
  });

  const createOrder = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.createPurchaseOrder>[0]) =>
      inventoryApi.createPurchaseOrder(body),
    onSuccess: () => {
      setOrderOpen(false);
      onSuccess(t("inv.orderCreated"));
    },
    onError,
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.purchaseRequests")}</h2>
          {canRequest && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createRequest.reset();
                setRequestOpen(true);
              }}
            >
              {t("inv.newRequest")}
            </button>
          )}
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.status")}</span>
            <select
              value={requestStatus}
              onChange={(event) => {
                setRequestStatus(event.target.value);
                setRequestPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              <option value="DRAFT">{t("invReqStatus.DRAFT")}</option>
              <option value="SUBMITTED">{t("invReqStatus.SUBMITTED")}</option>
              <option value="APPROVED">{t("invReqStatus.APPROVED")}</option>
              <option value="REJECTED">{t("invReqStatus.REJECTED")}</option>
              <option value="CONVERTED">{t("invReqStatus.CONVERTED")}</option>
            </select>
          </label>
        </div>
        {requests.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : requests.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !requests.data || requests.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noRequests")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("inv.requestNumber")}</th>
                    <th>{t("inv.warehouse")}</th>
                    <th>{t("common.status")}</th>
                    <th>{t("inv.requestedAt")}</th>
                    <th>{t("inv.requestedBy")}</th>
                    <th>{t("inv.lines")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {requests.data.items.map((request: PurchaseRequest) => (
                    <tr key={request.publicId}>
                      <td className="mono">{request.requestNumber}</td>
                      <td>
                        {request.warehouseCode} ·{" "}
                        {personName(locale, request.warehouseNameAr, request.warehouseNameEn)}
                      </td>
                      <td>
                        <span className={`badge ${inventoryBadge(request.status)}`}>
                          {t(`invReqStatus.${request.status}`)}
                        </span>
                      </td>
                      <td>{formatDateTime(request.requestedAt, locale)}</td>
                      <td>{personName(locale, request.requestedByAr, request.requestedByEn)}</td>
                      <td className="mono">{request.items?.length ?? 0}</td>
                      <td className="cell-actions">
                        {canApprove && request.status === "SUBMITTED" && (
                          <>
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              disabled={decide.isPending}
                              onClick={() =>
                                decide.mutate({
                                  publicId: request.publicId,
                                  decision: "APPROVE",
                                })
                              }
                            >
                              {t("inv.approve")}
                            </button>
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              disabled={decide.isPending}
                              onClick={() =>
                                decide.mutate({
                                  publicId: request.publicId,
                                  decision: "REJECT",
                                })
                              }
                            >
                              {t("inv.reject")}
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={requests.data.page}
              totalPages={requests.data.totalPages}
              total={requests.data.total}
              onChange={setRequestPage}
            />
          </>
        )}
        {decide.error ? (
          <p className="field-error" role="alert">
            {describeApiError(decide.error, locale, t("common.saveError"))}
          </p>
        ) : null}
      </section>

      <section className="card">
        <header className="card-header">
          <h2>{t("inv.purchaseOrders")}</h2>
          {canOrder && (
            <button
              type="button"
              className="button button-sm"
              onClick={() => {
                createOrder.reset();
                setOrderOpen(true);
              }}
            >
              {t("inv.newOrder")}
            </button>
          )}
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.status")}</span>
            <select
              value={orderStatus}
              onChange={(event) => {
                setOrderStatus(event.target.value);
                setOrderPage(0);
              }}
            >
              <option value="">{t("common.all")}</option>
              <option value="ORDERED">{t("invPoStatus.ORDERED")}</option>
              <option value="PARTIALLY_RECEIVED">{t("invPoStatus.PARTIALLY_RECEIVED")}</option>
              <option value="RECEIVED">{t("invPoStatus.RECEIVED")}</option>
              <option value="CANCELLED">{t("invPoStatus.CANCELLED")}</option>
            </select>
          </label>
        </div>
        {orders.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : orders.isError ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : !orders.data || orders.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noOrders")}</p>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("inv.poNumber")}</th>
                    <th>{t("inv.supplier")}</th>
                    <th>{t("inv.warehouse")}</th>
                    <th>{t("common.status")}</th>
                    <th>{t("inv.totalAmount")}</th>
                    <th>{t("inv.expectedAt")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {orders.data.items.map((order) => (
                    <tr key={order.publicId}>
                      <td className="mono">{order.poNumber}</td>
                      <td>{personName(locale, order.supplierNameAr, order.supplierNameEn)}</td>
                      <td className="mono">{order.warehouseCode}</td>
                      <td>
                        <span className={`badge ${inventoryBadge(order.status)}`}>
                          {t(`invPoStatus.${order.status}`)}
                        </span>
                      </td>
                      <td className="mono">{money(order.totalAmount)}</td>
                      <td>{formatDate(order.expectedAt, locale)}</td>
                      <td className="cell-actions">
                        {canReceive &&
                          order.status !== "RECEIVED" &&
                          order.status !== "CANCELLED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => setReceiveTarget(order)}
                            >
                              {t("inv.receive")}
                            </button>
                          )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={orders.data.page}
              totalPages={orders.data.totalPages}
              total={orders.data.total}
              onChange={setOrderPage}
            />
          </>
        )}
      </section>

      {requestOpen && (
        <NewPurchaseRequestModal
          warehouses={options.warehouses}
          items={options.items}
          pending={createRequest.isPending}
          error={createRequest.error}
          onClose={() => setRequestOpen(false)}
          onSubmit={(body) => createRequest.mutate(body)}
        />
      )}

      {orderOpen && (
        <NewPurchaseOrderModal
          warehouses={options.warehouses}
          suppliers={options.suppliers}
          items={options.items}
          pending={createOrder.isPending}
          error={createOrder.error}
          onClose={() => setOrderOpen(false)}
          onSubmit={(body) => createOrder.mutate(body)}
        />
      )}

      {receiveTarget && (
        <ReceiveOrderModal
          order={receiveTarget}
          onClose={() => setReceiveTarget(null)}
          onSuccess={(message) => {
            setReceiveTarget(null);
            onSuccess(message);
          }}
          onError={onError}
        />
      )}
    </div>
  );
}

function NewPurchaseRequestModal({
  warehouses,
  items,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  warehouses: Warehouse[];
  items: InventoryItem[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: Parameters<typeof inventoryApi.createPurchaseRequest>[0]) => void;
}) {
  const { t, locale } = useI18n();
  const [warehousePublicId, setWarehousePublicId] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload = linesToPayload(lines, { notes: true });
    if (!warehousePublicId || payload.length === 0) {
      return;
    }
    onSubmit({
      warehousePublicId,
      notes: notes.trim() || undefined,
      items: payload,
    });
  }

  return (
    <Modal title={t("inv.newRequest")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("inv.warehouse")}</span>
          <select
            value={warehousePublicId}
            onChange={(event) => setWarehousePublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {warehouses.map((row) => (
              <option key={row.publicId} value={row.publicId}>
                {row.code} · {personName(locale, row.nameAr, row.nameEn)}
              </option>
            ))}
          </select>
        </label>
        <LinesEditor items={items} lines={lines} onChange={setLines} showNotes />
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={notes}
            rows={2}
            maxLength={1000}
            onChange={(event) => setNotes(event.target.value)}
          />
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.create")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function NewPurchaseOrderModal({
  warehouses,
  suppliers,
  items,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  warehouses: Warehouse[];
  suppliers: Supplier[];
  items: InventoryItem[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: Parameters<typeof inventoryApi.createPurchaseOrder>[0]) => void;
}) {
  const { t, locale } = useI18n();
  const [supplierPublicId, setSupplierPublicId] = useState("");
  const [warehousePublicId, setWarehousePublicId] = useState("");
  const [requestPublicId, setRequestPublicId] = useState("");
  const [expectedAt, setExpectedAt] = useState("");
  const [notes, setNotes] = useState("");
  const [lines, setLines] = useState<LineDraft[]>([newLine()]);

  const approvedRequests = useQuery({
    queryKey: ["inv", "purchase-requests", "approved-options"],
    queryFn: () => inventoryApi.purchaseRequests({ status: "APPROVED", size: 100 }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload = requestPublicId ? [] : linesToPayload(lines, { cost: true });
    if (!supplierPublicId || !warehousePublicId || (!requestPublicId && payload.length === 0)) {
      return;
    }
    onSubmit({
      supplierPublicId,
      warehousePublicId,
      purchaseRequestPublicId: requestPublicId || undefined,
      expectedAt: expectedAt || undefined,
      notes: notes.trim() || undefined,
      items: payload.length > 0 ? payload : undefined,
    });
  }

  return (
    <Modal title={t("inv.newOrder")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <div className="field-row">
          <label className="field">
            <span>{t("inv.supplier")}</span>
            <select
              value={supplierPublicId}
              onChange={(event) => setSupplierPublicId(event.target.value)}
              required
            >
              <option value="">{t("common.select")}</option>
              {suppliers.map((supplier) => (
                <option key={supplier.publicId} value={supplier.publicId}>
                  {supplier.code} · {personName(locale, supplier.nameAr, supplier.nameEn)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("inv.warehouse")}</span>
            <select
              value={warehousePublicId}
              onChange={(event) => setWarehousePublicId(event.target.value)}
              required
            >
              <option value="">{t("common.select")}</option>
              {warehouses.map((row) => (
                <option key={row.publicId} value={row.publicId}>
                  {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <label className="field">
          <span>{t("inv.approvedRequest")}</span>
          <select
            value={requestPublicId}
            onChange={(event) => setRequestPublicId(event.target.value)}
          >
            <option value="">{t("common.none")}</option>
            {(approvedRequests.data?.items ?? []).map((request) => (
              <option key={request.publicId} value={request.publicId}>
                {request.requestNumber} · {request.warehouseCode}
              </option>
            ))}
          </select>
          <small className="field-hint">{t("inv.approvedRequestHint")}</small>
        </label>
        {!requestPublicId && (
          <LinesEditor items={items} lines={lines} onChange={setLines} showCost />
        )}
        <label className="field">
          <span>{t("inv.expectedAt")}</span>
          <input
            type="date"
            value={expectedAt}
            onChange={(event) => setExpectedAt(event.target.value)}
          />
        </label>
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={notes}
            rows={2}
            maxLength={1000}
            onChange={(event) => setNotes(event.target.value)}
          />
        </label>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.create")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

interface ReceiveLineDraft {
  quantity: string;
  batchNumber: string;
  lotNumber: string;
  expiryDate: string;
}

function ReceiveOrderModal({
  order,
  onClose,
  onSuccess,
  onError,
}: {
  order: PurchaseOrder;
  onClose: () => void;
  onSuccess: (message: string) => void;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const detail = useQuery({
    queryKey: ["inv", "purchase-order", order.publicId],
    queryFn: () => inventoryApi.purchaseOrder(order.publicId),
  });
  const [drafts, setDrafts] = useState<Record<string, ReceiveLineDraft>>({});
  const [notes, setNotes] = useState("");

  const receive = useMutation({
    mutationFn: (body: Parameters<typeof inventoryApi.receivePurchaseOrder>[1]) =>
      inventoryApi.receivePurchaseOrder(order.publicId, body),
    onSuccess: () => onSuccess(t("inv.orderReceived")),
    onError,
  });

  const items = detail.data?.items ?? [];

  function draftFor(itemPublicId: string, remaining: number): ReceiveLineDraft {
    return (
      drafts[itemPublicId] ?? {
        quantity: remaining > 0 ? String(remaining) : "0",
        batchNumber: "",
        lotNumber: "",
        expiryDate: "",
      }
    );
  }

  function setDraft(itemPublicId: string, patch: Partial<ReceiveLineDraft>) {
    const found = items.find((item) => item.itemPublicId === itemPublicId);
    const remaining = found
      ? found.quantityOrdered - found.quantityReceived
      : 0;
    const current = draftFor(itemPublicId, remaining);
    setDrafts((all) => ({ ...all, [itemPublicId]: { ...current, ...patch } }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload: StockLineInput[] = [];
    for (const item of items) {
      const remaining = item.quantityOrdered - item.quantityReceived;
      const draft = draftFor(item.itemPublicId, remaining);
      const quantity = Number(draft.quantity);
      if (!Number.isFinite(quantity) || quantity <= 0) {
        continue;
      }
      payload.push({
        itemPublicId: item.itemPublicId,
        quantity,
        batchNumber: draft.batchNumber.trim() || undefined,
        lotNumber: draft.lotNumber.trim() || undefined,
        expiryDate: draft.expiryDate || undefined,
      });
    }
    if (payload.length === 0) {
      return;
    }
    receive.mutate({ notes: notes.trim() || undefined, items: payload });
  }

  return (
    <Modal title={`${t("inv.receive")} · ${order.poNumber}`} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !detail.data ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <form className="stack-form" onSubmit={submit}>
          <p className="field-hint">
            {t("inv.supplier")}:{" "}
            {personName(locale, order.supplierNameAr, order.supplierNameEn)} ·{" "}
            {t("inv.warehouse")}: <span className="mono">{order.warehouseCode}</span>
          </p>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("inv.item")}</th>
                  <th>{t("inv.ordered")}</th>
                  <th>{t("inv.alreadyReceived")}</th>
                  <th>{t("inv.receiveNow")}</th>
                  <th>{t("inv.batchNumber")}</th>
                  <th>{t("inv.expiryDate")}</th>
                  <th>{t("inv.lotNumber")}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const remaining = item.quantityOrdered - item.quantityReceived;
                  const draft = draftFor(item.itemPublicId, remaining);
                  return (
                    <tr key={item.publicId}>
                      <td>
                        <span className="mono">{item.code}</span> ·{" "}
                        {personName(locale, item.nameAr, item.nameEn)}
                      </td>
                      <td className="mono">{item.quantityOrdered}</td>
                      <td className="mono">{item.quantityReceived}</td>
                      <td>
                        <input
                          type="number"
                          min={0}
                          max={remaining}
                          step={1}
                          value={draft.quantity}
                          onChange={(event) =>
                            setDraft(item.itemPublicId, { quantity: event.target.value })
                          }
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          maxLength={60}
                          value={draft.batchNumber}
                          onChange={(event) =>
                            setDraft(item.itemPublicId, { batchNumber: event.target.value })
                          }
                        />
                      </td>
                      <td>
                        <input
                          type="date"
                          value={draft.expiryDate}
                          required={item.trackExpiry && Number(draft.quantity) > 0}
                          onChange={(event) =>
                            setDraft(item.itemPublicId, { expiryDate: event.target.value })
                          }
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          maxLength={60}
                          value={draft.lotNumber}
                          onChange={(event) =>
                            setDraft(item.itemPublicId, { lotNumber: event.target.value })
                          }
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <label className="field">
            <span>{t("common.notes")}</span>
            <textarea
              value={notes}
              rows={2}
              maxLength={1000}
              onChange={(event) => setNotes(event.target.value)}
            />
          </label>
          {receive.error ? (
            <p className="field-error" role="alert">
              {describeApiError(receive.error, locale, t("common.saveError"))}
            </p>
          ) : null}
          <div className="form-actions">
            <button type="button" className="button button-ghost" onClick={onClose}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="button" disabled={receive.isPending}>
              {receive.isPending ? t("common.saving") : t("inv.receive")}
            </button>
          </div>
        </form>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Alerts and reports
// ---------------------------------------------------------------------------

function AlertsTab() {
  const { t, locale } = useI18n();
  const alerts = useQuery({
    queryKey: ["inv", "alerts"],
    queryFn: () => inventoryApi.alerts(),
  });

  return (
    <div className="tab-content">
      {alerts.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : alerts.isError || !alerts.data ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <>
          <section className="card">
            <header className="card-header">
              <h2>{t("inv.lowStock")}</h2>
              <span className="badge badge-warn">{alerts.data.lowStock.length}</span>
            </header>
            {alerts.data.lowStock.length === 0 ? (
              <p className="empty-inline">{t("inv.noLowStock")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("inv.item")}</th>
                      <th>{t("inv.warehouse")}</th>
                      <th>{t("inv.available")}</th>
                      <th>{t("inv.reorderLevel")}</th>
                      <th>{t("inv.shortage")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.data.lowStock.map((row) => (
                      <tr key={`${row.itemPublicId}-${row.warehousePublicId}`}>
                        <td>
                          <span className="mono">{row.code}</span> ·{" "}
                          {personName(locale, row.nameAr, row.nameEn)}
                        </td>
                        <td className="mono">{row.warehouseCode}</td>
                        <td className="mono">{row.available}</td>
                        <td className="mono">{row.reorderLevel}</td>
                        <td className="mono">{row.shortage}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="card">
            <header className="card-header">
              <h2>{t("inv.expiring")}</h2>
              <span className="badge badge-warn">{alerts.data.expiring.length}</span>
            </header>
            {alerts.data.expiring.length === 0 ? (
              <p className="empty-inline">{t("inv.noExpiring")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("inv.item")}</th>
                      <th>{t("inv.batchNumber")}</th>
                      <th>{t("inv.warehouse")}</th>
                      <th>{t("inv.expiryDate")}</th>
                      <th>{t("inv.daysLeft")}</th>
                      <th>{t("inv.available")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.data.expiring.map((row) => (
                      <tr key={row.batchPublicId}>
                        <td>
                          <span className="mono">{row.code}</span> ·{" "}
                          {personName(locale, row.nameAr, row.nameEn)}
                        </td>
                        <td className="mono">{row.batchNumber}</td>
                        <td className="mono">{row.warehouseCode}</td>
                        <td>{formatDate(row.expiryDate, locale)}</td>
                        <td className="mono">{row.daysLeft}</td>
                        <td className="mono">{row.quantityAvailable}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="card">
            <header className="card-header">
              <h2>{t("inv.expiredBatches")}</h2>
              <span className="badge badge-danger">{alerts.data.expiredCount}</span>
            </header>
            <p className="field-hint">
              {t("inv.expiryWarning")}: {alerts.data.warningDays} {t("common.days")}
            </p>
          </section>
        </>
      )}
    </div>
  );
}

function ReportsTab() {
  const { t, locale } = useI18n();
  const [warehouse, setWarehouse] = useState("");
  const [from, setFrom] = useState(firstOfMonth());
  const [to, setTo] = useState(todayInput());
  const [itemPublicId, setItemPublicId] = useState("");
  const options = useInventoryOptions(true);

  const valuation = useQuery({
    queryKey: ["inv", "valuation", warehouse],
    queryFn: () => inventoryApi.valuation(warehouse || undefined),
  });

  const consumption = useQuery({
    queryKey: ["inv", "consumption", from, to, itemPublicId],
    queryFn: () =>
      inventoryApi.consumption({
        from: from || undefined,
        to: to || undefined,
        item: itemPublicId || undefined,
      }),
  });

  return (
    <div className="tab-content">
      <section className="card">
        <header className="card-header">
          <h2>{t("inv.valuation")}</h2>
          <span className="badge badge-info">
            {valuation.data ? money(valuation.data.totalValue, valuation.data.currency) : "—"}
          </span>
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("inv.warehouse")}</span>
            <select value={warehouse} onChange={(event) => setWarehouse(event.target.value)}>
              <option value="">{t("common.all")}</option>
              {options.warehouses.map((row) => (
                <option key={row.publicId} value={row.publicId}>
                  {row.code} · {personName(locale, row.nameAr, row.nameEn)}
                </option>
              ))}
            </select>
          </label>
        </div>
        {valuation.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : valuation.isError || !valuation.data ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : valuation.data.items.length === 0 ? (
          <p className="empty-inline">{t("inv.noStock")}</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("inv.item")}</th>
                  <th>{t("inv.warehouse")}</th>
                  <th>{t("common.quantity")}</th>
                  <th>{t("inv.averageCost")}</th>
                  <th>{t("inv.value")}</th>
                </tr>
              </thead>
              <tbody>
                {valuation.data.items.map((row) => (
                  <tr key={`${row.itemPublicId}-${row.warehousePublicId}`}>
                    <td>
                      <span className="mono">{row.code}</span> ·{" "}
                      {personName(locale, row.nameAr, row.nameEn)}
                    </td>
                    <td className="mono">{row.warehouseCode}</td>
                    <td className="mono">{row.quantity}</td>
                    <td className="mono">
                      {money(row.averageCost, valuation.data?.currency)}
                    </td>
                    <td className="mono">{money(row.value, valuation.data?.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <header className="card-header">
          <h2>{t("inv.consumption")}</h2>
        </header>
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("inv.from")}</span>
            <input
              type="date"
              value={from}
              max={to || undefined}
              onChange={(event) => setFrom(event.target.value)}
            />
          </label>
          <label className="field field-inline">
            <span>{t("inv.to")}</span>
            <input
              type="date"
              value={to}
              min={from || undefined}
              onChange={(event) => setTo(event.target.value)}
            />
          </label>
          <label className="field field-inline">
            <span>{t("inv.item")}</span>
            <select
              value={itemPublicId}
              onChange={(event) => setItemPublicId(event.target.value)}
            >
              <option value="">{t("common.all")}</option>
              {options.items.map((item) => (
                <option key={item.publicId} value={item.publicId}>
                  {itemLabel(locale, item)}
                </option>
              ))}
            </select>
          </label>
        </div>
        {consumption.isPending ? (
          <p className="muted">{t("common.loading")}</p>
        ) : consumption.isError || !consumption.data ? (
          <div className="alert alert-danger">{t("common.loadError")}</div>
        ) : (
          <>
            <div className="detail-grid">
              <div>
                <span className="muted">{t("inv.totalIssued")}</span>
                <strong className="mono">{consumption.data.totalIssued}</strong>
              </div>
              <div>
                <span className="muted">{t("inv.totalWrittenOff")}</span>
                <strong className="mono">{consumption.data.totalWrittenOff}</strong>
              </div>
              <div>
                <span className="muted">{t("inv.totalTransferredOut")}</span>
                <strong className="mono">{consumption.data.totalTransferredOut}</strong>
              </div>
            </div>
            {consumption.data.items.length === 0 ? (
              <p className="empty-inline">{t("inv.noConsumption")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("inv.item")}</th>
                      <th>{t("inv.issued")}</th>
                      <th>{t("inv.writtenOff")}</th>
                      <th>{t("inv.transferredOut")}</th>
                      <th>{t("inv.movementCount")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {consumption.data.items.map((row) => (
                      <tr key={row.itemPublicId}>
                        <td>
                          <span className="mono">{row.code}</span> ·{" "}
                          {personName(locale, row.nameAr, row.nameEn)}
                        </td>
                        <td className="mono">{row.issued}</td>
                        <td className="mono">{row.writtenOff}</td>
                        <td className="mono">{row.transferredOut}</td>
                        <td className="mono">{row.movementCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
