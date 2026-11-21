import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDate, formatDateTime, todayInput } from "../../shared/ui/format";
import type { PatientSummary } from "../patients/patientsApi";
import {
  INVOICE_STATUSES,
  PAYMENT_METHODS,
  billingApi,
  type InvoicePayment,
  type Money,
  type PriceItem,
} from "./billingApi";

function money(value: Money | null | undefined, currency: string): string {
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

function invoiceBadge(status: string): string {
  switch (status) {
    case "PAID":
      return "badge-success";
    case "PARTIALLY_PAID":
      return "badge-warn";
    case "CREDITED":
      return "badge-info";
    case "VOID":
      return "badge-danger";
    default:
      return "badge-muted";
  }
}

function sessionBadge(status: string): string {
  return status === "OPEN" ? "badge-success" : "badge-muted";
}

export function BillingPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<"invoices" | "cashier" | "prices" | "report">("invoices");
  const [term, setTerm] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [invoiceId, setInvoiceId] = useState<string | null>(null);
  const [newInvoiceOpen, setNewInvoiceOpen] = useState(false);
  const [newPriceOpen, setNewPriceOpen] = useState(false);
  const [sessionPage, setSessionPage] = useState(0);
  const [reportDate, setReportDate] = useState(todayInput());
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const canCreate = hasAnyPermission("BIL:CREATE");
  const canSession = hasAnyPermission("BIL:CASH_SESSION");
  const canPrice = hasAnyPermission("BIL:PRICE_LIST");

  const invoices = useQuery({
    queryKey: ["bil-invoices", term, status, page],
    queryFn: () =>
      billingApi.invoices({
        q: term.trim() || undefined,
        status: status || undefined,
        page,
        size: 20,
      }),
  });

  const sessions = useQuery({
    queryKey: ["bil-sessions", sessionPage],
    queryFn: () => billingApi.cashSessions({ page: sessionPage, size: 20 }),
    enabled: tab === "cashier",
  });

  const currentSession = useQuery({
    queryKey: ["bil-current-session"],
    queryFn: () => billingApi.currentCashSession(),
    enabled: tab === "cashier",
  });

  const prices = useQuery({
    queryKey: ["bil-prices"],
    queryFn: () => billingApi.priceList(),
    enabled: tab === "prices",
  });

  const services = useQuery({
    queryKey: ["bil-services"],
    queryFn: () => billingApi.services(),
    enabled: tab === "prices" && canPrice,
    staleTime: 120_000,
  });

  const report = useQuery({
    queryKey: ["bil-report", reportDate],
    queryFn: () => billingApi.dailyReport(reportDate || undefined),
    enabled: tab === "report",
  });

  const invalidateBilling = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["bil-invoices"] }),
      queryClient.invalidateQueries({ queryKey: ["bil-sessions"] }),
      queryClient.invalidateQueries({ queryKey: ["bil-current-session"] }),
      queryClient.invalidateQueries({ queryKey: ["bil-report"] }),
      queryClient.invalidateQueries({ queryKey: ["bil-prices"] }),
    ]);
  };

  const createInvoice = useMutation({
    mutationFn: (body: Parameters<typeof billingApi.createInvoice>[0]) =>
      billingApi.createInvoice(body),
    onSuccess: async () => {
      setNewInvoiceOpen(false);
      setNotice(t("bil.createdOk"));
      setActionError(null);
      await invalidateBilling();
    },
  });

  const createPrice = useMutation({
    mutationFn: (body: Parameters<typeof billingApi.createPrice>[0]) =>
      billingApi.createPrice(body),
    onSuccess: async () => {
      setNewPriceOpen(false);
      setNotice(t("common.saved"));
      setActionError(null);
      await invalidateBilling();
    },
  });

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("bil.title")}</h1>
          <p className="muted">{t("bil.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              void invoices.refetch();
              void sessions.refetch();
              void currentSession.refetch();
              void prices.refetch();
              void report.refetch();
            }}
          >
            {t("common.refresh")}
          </button>
          {canCreate && (
            <button
              type="button"
              className="button"
              onClick={() => {
                createInvoice.reset();
                setNewInvoiceOpen(true);
              }}
            >
              {t("bil.newInvoice")}
            </button>
          )}
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
        {(["invoices", "cashier", "prices", "report"] as const).map((key) => (
          <button
            key={key}
            type="button"
            className={`tab${tab === key ? " is-active" : ""}`}
            onClick={() => setTab(key)}
          >
            {t(`bil.tab.${key}`)}
          </button>
        ))}
      </div>

      {tab === "invoices" && (
        <div className="tab-content">
          <section className="card">
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
                  placeholder={t("bil.searchPlaceholder")}
                />
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
                  <option value="">{t("bil.allStatuses")}</option>
                  {INVOICE_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {t(`bilStatus.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            {invoices.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : invoices.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !invoices.data || invoices.data.items.length === 0 ? (
              <p className="empty-inline">{t("bil.noInvoices")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("bil.invoiceNumber")}</th>
                        <th>{t("bil.patient")}</th>
                        <th>{t("common.status")}</th>
                        <th>{t("bil.grandTotal")}</th>
                        <th>{t("bil.paid")}</th>
                        <th>{t("bil.balance")}</th>
                        <th>{t("bil.issuedAt")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {invoices.data.items.map((invoice) => (
                        <tr key={invoice.publicId}>
                          <td className="mono">{invoice.invoiceNumber}</td>
                          <td>
                            {invoice.patient.fullName}
                            <span className="muted mono">
                              {" "}
                              · {invoice.patient.medicalNumber}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${invoiceBadge(invoice.status)}`}>
                              {t(`bilStatus.${invoice.status}`)}
                            </span>
                          </td>
                          <td className="mono">
                            {money(invoice.grandTotal, invoice.currency)}
                          </td>
                          <td className="mono">
                            {money(invoice.paidTotal, invoice.currency)}
                          </td>
                          <td className="mono">
                            {money(invoice.balance, invoice.currency)}
                          </td>
                          <td>{formatDateTime(invoice.issuedAt, locale)}</td>
                          <td className="cell-actions">
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => {
                                setActionError(null);
                                setInvoiceId(invoice.publicId);
                              }}
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
                  page={invoices.data.page}
                  totalPages={invoices.data.totalPages}
                  total={invoices.data.total}
                  onChange={setPage}
                />
              </>
            )}
          </section>
        </div>
      )}

      {tab === "cashier" && (
        <div className="tab-content">
          <CurrentSessionCard
            data={currentSession.data?.session ?? null}
            loading={currentSession.isPending}
            canSession={canSession}
            onChanged={async (message) => {
              setNotice(message);
              setActionError(null);
              await invalidateBilling();
            }}
            onError={setActionError}
          />

          <section className="card">
            <header className="card-header">
              <h2>{t("bil.sessions")}</h2>
              <span className="badge badge-muted">
                {t("common.total")}: {sessions.data?.total ?? 0}
              </span>
            </header>
            {sessions.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : sessions.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !sessions.data || sessions.data.items.length === 0 ? (
              <p className="empty-inline">{t("bil.noSessions")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("bil.sessionNumber")}</th>
                        <th>{t("bil.cashierName")}</th>
                        <th>{t("common.status")}</th>
                        <th>{t("bil.openingFloat")}</th>
                        <th>{t("bil.expectedTotal")}</th>
                        <th>{t("bil.countedTotal")}</th>
                        <th>{t("bil.variance")}</th>
                        <th>{t("bil.openedAt")}</th>
                        <th>{t("bil.closedAt")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {sessions.data.items.map((session) => (
                        <tr key={session.publicId}>
                          <td className="mono">{session.sessionNumber}</td>
                          <td>
                            {personName(
                              locale,
                              session.cashierNameAr,
                              session.cashierNameEn,
                            )}
                          </td>
                          <td>
                            <span className={`badge ${sessionBadge(session.status)}`}>
                              {t(`bilSession.${session.status}`)}
                            </span>
                          </td>
                          <td className="mono">
                            {money(session.openingFloat, session.currency)}
                          </td>
                          <td className="mono">
                            {money(session.expectedTotal, session.currency)}
                          </td>
                          <td className="mono">
                            {money(session.countedTotal, session.currency)}
                          </td>
                          <td className="mono">
                            {money(session.variance, session.currency)}
                          </td>
                          <td>{formatDateTime(session.openedAt, locale)}</td>
                          <td>{formatDateTime(session.closedAt, locale)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={sessions.data.page}
                  totalPages={sessions.data.totalPages}
                  total={sessions.data.total}
                  onChange={setSessionPage}
                />
              </>
            )}
          </section>
        </div>
      )}

      {tab === "prices" && (
        <div className="tab-content">
          <section className="card">
            <header className="card-header">
              <h2>{t("bil.prices")}</h2>
              {canPrice && (
                <button
                  type="button"
                  className="button button-sm"
                  onClick={() => {
                    createPrice.reset();
                    setNewPriceOpen(true);
                  }}
                >
                  {t("bil.newPrice")}
                </button>
              )}
            </header>
            {prices.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : prices.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !prices.data || prices.data.items.length === 0 ? (
              <p className="empty-inline">{t("bil.noPrices")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("common.code")}</th>
                      <th>{t("bil.service")}</th>
                      <th>{t("bil.category")}</th>
                      <th>{t("bil.amount")}</th>
                      <th>{t("bil.currency")}</th>
                      <th>{t("bil.validFrom")}</th>
                      <th>{t("bil.validTo")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {prices.data.items.map((price) => (
                      <tr key={price.publicId}>
                        <td className="mono">{price.code}</td>
                        <td>{personName(locale, price.nameAr, price.nameEn)}</td>
                        <td>{price.category}</td>
                        <td className="mono">{money(price.amount, price.currency)}</td>
                        <td>{price.currency}</td>
                        <td>{formatDate(price.validFrom, locale)}</td>
                        <td>{formatDate(price.validTo, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "report" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("common.date")}</span>
                <input
                  type="date"
                  value={reportDate}
                  max={todayInput()}
                  onChange={(event) => setReportDate(event.target.value)}
                />
              </label>
            </div>
            {report.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : report.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !report.data ? null : (
              <>
                <div className="detail-grid">
                  <div>
                    <span className="muted">{t("bil.invoiceCount")}</span>
                    <strong>{report.data.invoiceCount}</strong>
                  </div>
                  <div>
                    <span className="muted">{t("bil.collected")}</span>
                    <strong>{money(report.data.collected, report.data.currency)}</strong>
                  </div>
                  <div>
                    <span className="muted">{t("bil.refunded")}</span>
                    <strong>{money(report.data.refunded, report.data.currency)}</strong>
                  </div>
                  <div>
                    <span className="muted">{t("bil.credited")}</span>
                    <strong>{money(report.data.credited, report.data.currency)}</strong>
                  </div>
                  <div>
                    <span className="muted">{t("bil.net")}</span>
                    <strong>{money(report.data.net, report.data.currency)}</strong>
                  </div>
                  <div>
                    <span className="muted">{t("bil.outstanding")}</span>
                    <strong>{money(report.data.outstanding, report.data.currency)}</strong>
                  </div>
                </div>
                <h3>{t("bil.byMethod")}</h3>
                {report.data.byMethod.length === 0 ? (
                  <p className="empty-inline">{t("bil.noCollections")}</p>
                ) : (
                  <div className="table-wrap">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>{t("bil.method")}</th>
                          <th>{t("bil.paymentCount")}</th>
                          <th>{t("bil.collected")}</th>
                          <th>{t("bil.refunded")}</th>
                          <th>{t("bil.net")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {report.data.byMethod.map((row) => (
                          <tr key={row.method}>
                            <td>{t(`bilMethod.${row.method}`)}</td>
                            <td className="mono">{row.paymentCount}</td>
                            <td className="mono">
                              {money(row.collected, report.data?.currency ?? "JOD")}
                            </td>
                            <td className="mono">
                              {money(row.refunded, report.data?.currency ?? "JOD")}
                            </td>
                            <td className="mono">
                              {money(row.net, report.data?.currency ?? "JOD")}
                            </td>
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
      )}

      {invoiceId && (
        <InvoiceModal
          publicId={invoiceId}
          onClose={() => setInvoiceId(null)}
          onChanged={async (message) => {
            setNotice(message);
            setActionError(null);
            await invalidateBilling();
          }}
          onError={setActionError}
        />
      )}

      {newInvoiceOpen && (
        <NewInvoiceModal
          pending={createInvoice.isPending}
          error={createInvoice.error}
          onClose={() => setNewInvoiceOpen(false)}
          onSubmit={(body) => createInvoice.mutate(body)}
        />
      )}

      {newPriceOpen && (
        <NewPriceModal
          services={services.data ?? []}
          pending={createPrice.isPending}
          error={createPrice.error}
          onClose={() => setNewPriceOpen(false)}
          onSubmit={(body) => createPrice.mutate(body)}
        />
      )}
    </div>
  );
}

function CurrentSessionCard({
  data,
  loading,
  canSession,
  onChanged,
  onError,
}: {
  data: import("./billingApi").CashSession | null;
  loading: boolean;
  canSession: boolean;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const [mode, setMode] = useState<"open" | "close" | null>(null);
  const [openingFloat, setOpeningFloat] = useState("0");
  const [counted, setCounted] = useState("");
  const [notes, setNotes] = useState("");

  const openSession = useMutation({
    mutationFn: () =>
      billingApi.openCashSession({
        openingFloat: Number(openingFloat) || 0,
        notes: notes.trim() || undefined,
      }),
    onSuccess: async () => {
      setMode(null);
      setNotes("");
      await onChanged(t("bil.sessionOpened"));
    },
    onError,
  });

  const closeSession = useMutation({
    mutationFn: () =>
      billingApi.closeCashSession(data?.publicId ?? "", {
        countedTotal: Number(counted) || 0,
        notes: notes.trim() || undefined,
      }),
    onSuccess: async () => {
      setMode(null);
      setNotes("");
      setCounted("");
      await onChanged(t("bil.sessionClosedOk"));
    },
    onError,
  });

  return (
    <section className="card">
      <header className="card-header">
        <h2>{t("bil.currentSession")}</h2>
        {data && (
          <span className={`badge ${sessionBadge(data.status)}`}>
            {t(`bilSession.${data.status}`)}
          </span>
        )}
      </header>
      {loading ? (
        <p className="muted">{t("common.loading")}</p>
      ) : !data ? (
        <p className="empty-inline">{t("bil.noSession")}</p>
      ) : (
        <div className="detail-grid">
          <div>
            <span className="muted">{t("bil.sessionNumber")}</span>
            <strong className="mono">{data.sessionNumber}</strong>
          </div>
          <div>
            <span className="muted">{t("bil.cashierName")}</span>
            <strong>{personName(locale, data.cashierNameAr, data.cashierNameEn)}</strong>
          </div>
          <div>
            <span className="muted">{t("bil.openingFloat")}</span>
            <strong>{money(data.openingFloat, data.currency)}</strong>
          </div>
          <div>
            <span className="muted">{t("bil.expectedTotal")}</span>
            <strong>{money(data.expectedTotal, data.currency)}</strong>
          </div>
          <div>
            <span className="muted">{t("bil.openedAt")}</span>
            <strong>{formatDateTime(data.openedAt, locale)}</strong>
          </div>
          <div>
            <span className="muted">{t("bil.closedAt")}</span>
            <strong>{formatDateTime(data.closedAt, locale)}</strong>
          </div>
        </div>
      )}

      {canSession && (
        <div className="form-actions form-actions-start">
          {!data && (
            <button
              type="button"
              className="button"
              onClick={() => {
                openSession.reset();
                setMode("open");
              }}
            >
              {t("bil.openSession")}
            </button>
          )}
          {data && data.status === "OPEN" && (
            <button
              type="button"
              className="button"
              onClick={() => {
                closeSession.reset();
                setCounted(
                  data.expectedTotal === null ? "" : String(data.expectedTotal),
                );
                setMode("close");
              }}
            >
              {t("bil.closeSession")}
            </button>
          )}
        </div>
      )}

      {mode && (
        <form
          className="stack-form"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (mode === "open") {
              openSession.mutate();
            } else {
              closeSession.mutate();
            }
          }}
        >
          <h3>{mode === "open" ? t("bil.openSessionTitle") : t("bil.closeSessionTitle")}</h3>
          {mode === "open" ? (
            <label className="field">
              <span>{t("bil.openingFloat")}</span>
              <input
                type="number"
                min={0}
                step="0.01"
                value={openingFloat}
                onChange={(event) => setOpeningFloat(event.target.value)}
              />
            </label>
          ) : (
            <label className="field">
              <span>{t("bil.countedTotal")}</span>
              <input
                type="number"
                min={0}
                step="0.01"
                value={counted}
                onChange={(event) => setCounted(event.target.value)}
                required
              />
            </label>
          )}
          <label className="field">
            <span>{t("common.notes")}</span>
            <textarea
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
              rows={2}
              maxLength={500}
            />
          </label>
          {(mode === "open" ? openSession.error : closeSession.error) ? (
            <p className="field-error" role="alert">
              {describeApiError(
                mode === "open" ? openSession.error : closeSession.error,
                locale,
                t("common.saveError"),
              )}
            </p>
          ) : null}
          <div className="form-actions">
            <button
              type="button"
              className="button button-ghost"
              onClick={() => setMode(null)}
            >
              {t("common.cancel")}
            </button>
            <button
              type="submit"
              className="button"
              disabled={openSession.isPending || closeSession.isPending}
            >
              {t("common.save")}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

type InvoicePanel =
  | { kind: "discount" }
  | { kind: "payment" }
  | { kind: "void" }
  | { kind: "credit" }
  | { kind: "refund"; payment: InvoicePayment }
  | { kind: "receipt"; paymentPublicId: string }
  | null;

function InvoiceModal({
  publicId,
  onClose,
  onChanged,
  onError,
}: {
  publicId: string;
  onClose: () => void;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();
  const [panel, setPanel] = useState<InvoicePanel>(null);

  const canDiscount = hasAnyPermission("BIL:DISCOUNT", "BIL:DISCOUNT_LIMITED");
  const canPay = hasAnyPermission("BIL:PAYMENT");
  const canVoid = hasAnyPermission("BIL:VOID");
  const canRefund = hasAnyPermission("BIL:REFUND");

  const detail = useQuery({
    queryKey: ["bil-invoice", publicId],
    queryFn: () => billingApi.invoice(publicId),
  });

  const refresh = async (message: string) => {
    await onChanged(message);
    await queryClient.invalidateQueries({ queryKey: ["bil-invoice", publicId] });
  };

  const discount = useMutation({
    mutationFn: (body: {
      mode: "percent" | "amount";
      value: number;
      reason: string;
    }) =>
      billingApi.discount(publicId, {
        version: detail.data?.version ?? 0,
        percent: body.mode === "percent" ? body.value : undefined,
        amount: body.mode === "amount" ? body.value : undefined,
        reason: body.reason,
      }),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("common.saved"));
    },
    onError,
  });

  const payment = useMutation({
    mutationFn: (body: {
      amount: number;
      method: string;
      reference?: string;
      notes?: string;
      idempotencyKey: string;
    }) => billingApi.addPayment(publicId, body),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("bil.paymentRecorded"));
    },
    onError,
  });

  const voidInvoice = useMutation({
    mutationFn: (reason: string) => billingApi.voidInvoice(publicId, { reason }),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("bil.voidedOk"));
    },
    onError,
  });

  const creditNote = useMutation({
    mutationFn: (body: { amount: number; reason: string }) =>
      billingApi.creditNote(publicId, body),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("bil.creditIssued"));
    },
    onError,
  });

  const refund = useMutation({
    mutationFn: (body: {
      paymentPublicId: string;
      amount: number;
      reason: string;
      approvedByPublicId?: string;
    }) =>
      billingApi.refund(body.paymentPublicId, {
        amount: body.amount,
        reason: body.reason,
        approvedByPublicId: body.approvedByPublicId,
      }),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("bil.refundedOk"));
    },
    onError,
  });

  const invoice = detail.data;

  return (
    <Modal title={invoice ? invoice.invoiceNumber : t("bil.invoice")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !invoice ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : panel?.kind === "discount" ? (
        <DiscountForm
          pending={discount.isPending}
          error={discount.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => discount.mutate(body)}
        />
      ) : panel?.kind === "payment" ? (
        <PaymentForm
          balance={invoice.balance}
          currency={invoice.currency}
          pending={payment.isPending}
          error={payment.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => payment.mutate(body)}
        />
      ) : panel?.kind === "void" ? (
        <ReasonForm
          title={t("bil.voidTitle")}
          hint={t("bil.voidHint")}
          reasonLabel={t("bil.voidReason")}
          pending={voidInvoice.isPending}
          error={voidInvoice.error}
          onBack={() => setPanel(null)}
          onSubmit={(reason) => voidInvoice.mutate(reason)}
        />
      ) : panel?.kind === "credit" ? (
        <CreditNoteForm
          creditable={Number(invoice.grandTotal) - Number(invoice.creditedTotal ?? 0)}
          currency={invoice.currency}
          pending={creditNote.isPending}
          error={creditNote.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => creditNote.mutate(body)}
        />
      ) : panel?.kind === "refund" ? (
        <RefundForm
          payment={panel.payment}
          currency={invoice.currency}
          requiresApprover={!hasAnyPermission("BIL:REFUND_APPROVE")}
          pending={refund.isPending}
          error={refund.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) =>
            refund.mutate({ ...body, paymentPublicId: panel.payment.publicId })
          }
        />
      ) : panel?.kind === "receipt" ? (
        <ReceiptPanel
          paymentPublicId={panel.paymentPublicId}
          onBack={() => setPanel(null)}
        />
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("bil.patient")}</span>
              <strong>{invoice.patient.fullName}</strong>
              <span className="muted mono"> · {invoice.patient.medicalNumber}</span>
            </div>
            <div>
              <span className="muted">{t("common.status")}</span>
              <span className={`badge ${invoiceBadge(invoice.status)}`}>
                {t(`bilStatus.${invoice.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("bil.issuedAt")}</span>
              <strong>{formatDateTime(invoice.issuedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.dueDate")}</span>
              <strong>{formatDate(invoice.dueDate, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.subtotal")}</span>
              <strong>{money(invoice.subtotal, invoice.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.discount")}</span>
              <strong>{money(invoice.discountTotal, invoice.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.tax")}</span>
              <strong>{money(invoice.taxTotal, invoice.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.grandTotal")}</span>
              <strong>{money(invoice.grandTotal, invoice.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.paid")}</span>
              <strong>{money(invoice.paidTotal, invoice.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.balance")}</span>
              <strong>{money(invoice.balance, invoice.currency)}</strong>
            </div>
          </div>

          {invoice.discountReason && (
            <p className="muted">
              {t("bil.discountReason")}: {invoice.discountReason}
            </p>
          )}
          {invoice.notes && (
            <p className="muted">
              {t("common.notes")}: {invoice.notes}
            </p>
          )}
          {invoice.voidReason && (
            <p className="field-error">
              {t("bil.voidReason")}: {invoice.voidReason}
            </p>
          )}

          {!invoice.closed && invoice.status !== "VOID" && (
            <div className="form-actions form-actions-start">
              {canDiscount && (
                <button
                  type="button"
                  className="button button-ghost"
                  onClick={() => {
                    discount.reset();
                    setPanel({ kind: "discount" });
                  }}
                >
                  {t("bil.discount")}
                </button>
              )}
              {canPay && Number(invoice.balance) > 0 && (
                <button
                  type="button"
                  className="button"
                  onClick={() => {
                    payment.reset();
                    setPanel({ kind: "payment" });
                  }}
                >
                  {t("bil.payment")}
                </button>
              )}
              {canVoid && (
                <>
                  <button
                    type="button"
                    className="button button-ghost"
                    onClick={() => {
                      creditNote.reset();
                      setPanel({ kind: "credit" });
                    }}
                  >
                    {t("bil.creditNote")}
                  </button>
                  <button
                    type="button"
                    className="button button-ghost"
                    onClick={() => {
                      voidInvoice.reset();
                      setPanel({ kind: "void" });
                    }}
                  >
                    {t("bil.voidInvoice")}
                  </button>
                </>
              )}
            </div>
          )}
          {invoice.closed && <p className="field-hint">{t("bil.closedHint")}</p>}

          <section>
            <h3>{t("bil.items")}</h3>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("common.code")}</th>
                    <th>{t("bil.description")}</th>
                    <th>{t("common.quantity")}</th>
                    <th>{t("bil.unitPrice")}</th>
                    <th>{t("bil.lineTotal")}</th>
                  </tr>
                </thead>
                <tbody>
                  {(invoice.items ?? []).map((item) => (
                    <tr key={item.publicId}>
                      <td className="mono">{item.serviceCode}</td>
                      <td>{personName(locale, item.descriptionAr, item.descriptionEn)}</td>
                      <td className="mono">{item.quantity}</td>
                      <td className="mono">{money(item.unitPrice, invoice.currency)}</td>
                      <td className="mono">{money(item.lineTotal, invoice.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h3>{t("bil.payments")}</h3>
            {(invoice.payments ?? []).length === 0 ? (
              <p className="empty-inline">{t("bil.noPayments")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("bil.receipt")}</th>
                      <th>{t("bil.amount")}</th>
                      <th>{t("bil.method")}</th>
                      <th>{t("bil.reference")}</th>
                      <th>{t("bil.receivedAt")}</th>
                      <th>{t("bil.receivedBy")}</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {(invoice.payments ?? []).map((row) => (
                      <tr key={row.publicId}>
                        <td className="mono">{row.receiptNumber}</td>
                        <td className="mono">{money(row.amount, invoice.currency)}</td>
                        <td>{t(`bilMethod.${row.method}`)}</td>
                        <td>{row.reference ?? "—"}</td>
                        <td>{formatDateTime(row.receivedAt, locale)}</td>
                        <td>{personName(locale, row.receivedByAr, row.receivedByEn)}</td>
                        <td className="cell-actions">
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => setPanel({ kind: "receipt", paymentPublicId: row.publicId })}
                          >
                            {t("bil.receipt")}
                          </button>
                          {canRefund && !invoice.closed && row.status === "POSTED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => {
                                refund.reset();
                                setPanel({ kind: "refund", payment: row });
                              }}
                            >
                              {t("bil.refund")}
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

          {(invoice.refunds ?? []).length > 0 && (
            <section>
              <h3>{t("bil.refunds")}</h3>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("bil.refundNumber")}</th>
                      <th>{t("bil.amount")}</th>
                      <th>{t("bil.method")}</th>
                      <th>{t("bil.reason")}</th>
                      <th>{t("bil.performedAt")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(invoice.refunds ?? []).map((row) => (
                      <tr key={row.publicId}>
                        <td className="mono">{row.refundNumber}</td>
                        <td className="mono">{money(row.amount, invoice.currency)}</td>
                        <td>{t(`bilMethod.${row.method}`)}</td>
                        <td>{row.reason}</td>
                        <td>{formatDateTime(row.performedAt, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {(invoice.creditNotes ?? []).length > 0 && (
            <section>
              <h3>{t("bil.creditNotes")}</h3>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("bil.creditNumber")}</th>
                      <th>{t("bil.amount")}</th>
                      <th>{t("bil.reason")}</th>
                      <th>{t("bil.issuedAt")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(invoice.creditNotes ?? []).map((row) => (
                      <tr key={row.publicId}>
                        <td className="mono">{row.creditNumber}</td>
                        <td className="mono">{money(row.amount, invoice.currency)}</td>
                        <td>{row.reason}</td>
                        <td>{formatDateTime(row.issuedAt, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
    </Modal>
  );
}

function NewInvoiceModal({
  pending,
  error,
  onClose,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: { patientPublicId: string; notes?: string; dueDate?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [notes, setNotes] = useState("");
  const [dueDate, setDueDate] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!patient) {
      return;
    }
    onSubmit({
      patientPublicId: patient.publicId,
      notes: notes.trim() || undefined,
      dueDate: dueDate || undefined,
    });
  }

  return (
    <Modal title={t("bil.newInvoiceTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("bil.patient")}</span>
          <PatientPicker selected={patient} onSelect={setPatient} />
        </label>
        <p className="field-hint">{t("bil.chargeHint")}</p>
        <label className="field">
          <span>{t("bil.dueDate")}</span>
          <input
            type="date"
            value={dueDate}
            onChange={(event) => setDueDate(event.target.value)}
          />
        </label>
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            rows={2}
            maxLength={1000}
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
          <button type="submit" className="button" disabled={!patient || pending}>
            {pending ? t("common.saving") : t("common.create")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function DiscountForm({
  pending,
  error,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: { mode: "percent" | "amount"; value: number; reason: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [mode, setMode] = useState<"percent" | "amount">("percent");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || !reason.trim()) {
      return;
    }
    onSubmit({ mode, value: numeric, reason: reason.trim() });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("bil.discountTitle")}</h3>
      <div className="field-row">
        <label className="field">
          <span>{t("bil.discountType")}</span>
          <select
            value={mode}
            onChange={(event) => setMode(event.target.value as "percent" | "amount")}
          >
            <option value="percent">{t("bil.discountPercent")}</option>
            <option value="amount">{t("bil.discountAmount")}</option>
          </select>
        </label>
        <label className="field">
          <span>{t("bil.amount")}</span>
          <input
            type="number"
            min={0}
            step="0.01"
            value={value}
            onChange={(event) => setValue(event.target.value)}
            required
          />
        </label>
      </div>
      <label className="field">
        <span>{t("bil.discountReason")}</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={2}
          maxLength={500}
          required
        />
      </label>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function PaymentForm({
  balance,
  currency,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  balance: Money;
  currency: string;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    amount: number;
    method: string;
    reference?: string;
    notes?: string;
    idempotencyKey: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [amount, setAmount] = useState(String(balance));
  const [method, setMethod] = useState("CASH");
  const [reference, setReference] = useState("");
  const [notes, setNotes] = useState("");
  const [idempotencyKey] = useState(() => `ui-${crypto.randomUUID()}`);

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(amount);
    if (!Number.isFinite(numeric) || numeric <= 0) {
      return;
    }
    onSubmit({
      amount: numeric,
      method,
      reference: reference.trim() || undefined,
      notes: notes.trim() || undefined,
      idempotencyKey,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("bil.paymentTitle")}</h3>
      <p className="field-hint">
        {t("bil.balance")}: {money(balance, currency)}
      </p>
      <div className="field-row">
        <label className="field">
          <span>{t("bil.amount")}</span>
          <input
            type="number"
            min={0.01}
            step="0.01"
            max={Number(balance)}
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            required
          />
        </label>
        <label className="field">
          <span>{t("bil.method")}</span>
          <select value={method} onChange={(event) => setMethod(event.target.value)}>
            {PAYMENT_METHODS.map((value) => (
              <option key={value} value={value}>
                {t(`bilMethod.${value}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label className="field">
        <span>{t("bil.reference")}</span>
        <input
          type="text"
          value={reference}
          onChange={(event) => setReference(event.target.value)}
          maxLength={120}
        />
      </label>
      <label className="field">
        <span>{t("common.notes")}</span>
        <textarea
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          rows={2}
          maxLength={500}
        />
      </label>
      <p className="field-hint">
        {t("bil.idempotency")}: <span className="mono">{idempotencyKey}</span>
      </p>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function CreditNoteForm({
  creditable,
  currency,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  creditable: number;
  currency: string;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: { amount: number; reason: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [amount, setAmount] = useState(creditable > 0 ? String(creditable.toFixed(2)) : "");
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(amount);
    if (!Number.isFinite(numeric) || numeric <= 0 || !reason.trim()) {
      return;
    }
    onSubmit({ amount: numeric, reason: reason.trim() });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("bil.creditNoteTitle")}</h3>
      <p className="field-hint">
        {t("bil.creditable")}: {money(creditable, currency)}
      </p>
      <label className="field">
        <span>{t("bil.amount")}</span>
        <input
          type="number"
          min={0.01}
          step="0.01"
          max={creditable}
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          required
        />
      </label>
      <label className="field">
        <span>{t("bil.creditReason")}</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={2}
          maxLength={500}
          required
        />
      </label>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function RefundForm({
  payment,
  currency,
  requiresApprover,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  payment: InvoicePayment;
  currency: string;
  requiresApprover: boolean;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    amount: number;
    reason: string;
    approvedByPublicId?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [amount, setAmount] = useState(String(payment.amount));
  const [reason, setReason] = useState("");
  const [approver, setApprover] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(amount);
    if (!Number.isFinite(numeric) || numeric <= 0 || !reason.trim()) {
      return;
    }
    onSubmit({
      amount: numeric,
      reason: reason.trim(),
      approvedByPublicId: approver.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("bil.refundTitle")}</h3>
      <p className="field-hint">
        {t("bil.receipt")}: <span className="mono">{payment.receiptNumber}</span> ·{" "}
        {money(payment.amount, currency)}
      </p>
      <label className="field">
        <span>{t("bil.refundAmount")}</span>
        <input
          type="number"
          min={0.01}
          step="0.01"
          max={Number(payment.amount)}
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          required
        />
      </label>
      <label className="field">
        <span>{t("bil.refundReason")}</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={2}
          maxLength={500}
          required
        />
      </label>
      {requiresApprover && (
        <label className="field">
          <span>{t("bil.approver")}</span>
          <input
            type="text"
            value={approver}
            onChange={(event) => setApprover(event.target.value)}
            maxLength={40}
          />
          <small className="field-hint">{t("bil.approverHint")}</small>
        </label>
      )}
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button
          type="submit"
          className="button"
          disabled={pending || (requiresApprover && !approver.trim())}
        >
          {pending ? t("common.saving") : t("bil.refund")}
        </button>
      </div>
    </form>
  );
}

function ReasonForm({
  title,
  hint,
  reasonLabel,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  title: string;
  hint: string;
  reasonLabel: string;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (reason: string) => void;
}) {
  const { t, locale } = useI18n();
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (reason.trim()) {
      onSubmit(reason.trim());
    }
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{title}</h3>
      <p className="field-hint">{hint}</p>
      <label className="field">
        <span>{reasonLabel}</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={3}
          maxLength={500}
          required
        />
      </label>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={pending || !reason.trim()}>
          {pending ? t("common.saving") : t("common.confirm")}
        </button>
      </div>
    </form>
  );
}

function ReceiptPanel({
  paymentPublicId,
  onBack,
}: {
  paymentPublicId: string;
  onBack: () => void;
}) {
  const { t, locale } = useI18n();
  const receipt = useQuery({
    queryKey: ["bil-receipt", paymentPublicId],
    queryFn: () => billingApi.receipt(paymentPublicId),
  });

  return (
    <div className="stack-form">
      <div className="form-actions form-actions-start">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="button" className="button" onClick={() => window.print()}>
          {t("common.print")}
        </button>
      </div>
      {receipt.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : receipt.isError || !receipt.data ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <>
          <div className="detail-grid">
            <div>
              <span className="muted">{t("bil.receipt")}</span>
              <strong className="mono">{receipt.data.receiptNumber}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.invoiceNumber")}</span>
              <strong className="mono">{receipt.data.invoice.invoiceNumber}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.patient")}</span>
              <strong>{receipt.data.patient.fullName}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.amount")}</span>
              <strong>{money(receipt.data.amount, receipt.data.currency)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.method")}</span>
              <strong>{t(`bilMethod.${receipt.data.method}`)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.receivedAt")}</span>
              <strong>{formatDateTime(receipt.data.receivedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("bil.balance")}</span>
              <strong>{money(receipt.data.invoice.balance, receipt.data.currency)}</strong>
            </div>
            {receipt.data.reference && (
              <div>
                <span className="muted">{t("bil.reference")}</span>
                <strong>{receipt.data.reference}</strong>
              </div>
            )}
          </div>
          {receipt.data.replayed && <p className="field-hint">{t("bil.replayed")}</p>}
        </>
      )}
    </div>
  );
}

function NewPriceModal({
  services,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  services: import("./billingApi").ServiceOption[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    servicePublicId: string;
    amount: number;
    currency?: string;
    validFrom?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [servicePublicId, setServicePublicId] = useState("");
  const [amount, setAmount] = useState("");
  const [validFrom, setValidFrom] = useState(todayInput());

  function submit(event: FormEvent) {
    event.preventDefault();
    const numeric = Number(amount);
    if (!servicePublicId || !Number.isFinite(numeric) || numeric < 0) {
      return;
    }
    onSubmit({ servicePublicId, amount: numeric, validFrom: validFrom || undefined });
  }

  return (
    <Modal title={t("bil.newPriceTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("bil.service")}</span>
          <select
            value={servicePublicId}
            onChange={(event) => setServicePublicId(event.target.value)}
            required
          >
            <option value="">{t("common.select")}</option>
            {services.map((service) => (
              <option key={service.publicId} value={service.publicId}>
                {service.code} · {locale === "ar" ? service.nameAr : service.nameEn || service.nameAr}
              </option>
            ))}
          </select>
        </label>
        <div className="field-row">
          <label className="field">
            <span>{t("bil.amount")}</span>
            <input
              type="number"
              min={0}
              step="0.01"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("bil.validFrom")}</span>
            <input
              type="date"
              value={validFrom}
              onChange={(event) => setValidFrom(event.target.value)}
            />
          </label>
        </div>
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

export type { PriceItem };
