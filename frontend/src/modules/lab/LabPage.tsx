import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDate, formatDateTime } from "../../shared/ui/format";
import { clinicalApi } from "../clinical/clinicalApi";
import type { PatientSummary } from "../patients/patientsApi";
import {
  LAB_PRIORITIES,
  LAB_STATUSES,
  SPECIMEN_TYPES,
  labApi,
  type LabAlert,
  type LabOrderItem,
  type LabReport,
  type LabResult,
  type LabSpecimen,
  type LabTest,
} from "./labApi";

function personName(
  locale: Locale,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return locale === "ar" ? ar || en || "—" : en || ar || "—";
}

function orderBadgeClass(status: string): string {
  switch (status) {
    case "RELEASED":
    case "VERIFIED":
      return "badge-success";
    case "RESULTED":
    case "IN_PROCESS":
      return "badge-info";
    case "COLLECTED":
    case "RECEIVED":
      return "badge-warn";
    case "REJECTED":
    case "CANCELLED":
      return "badge-danger";
    default:
      return "badge-muted";
  }
}

function priorityBadgeClass(priority: string): string {
  switch (priority) {
    case "STAT":
      return "badge-danger";
    case "URGENT":
      return "badge-warn";
    default:
      return "badge-muted";
  }
}

function flagBadgeClass(flag: string | null | undefined): string {
  switch (flag) {
    case "CRITICAL_HIGH":
    case "CRITICAL_LOW":
      return "badge-danger";
    case "HIGH":
    case "LOW":
    case "ABNORMAL":
      return "badge-warn";
    case "NORMAL":
      return "badge-success";
    default:
      return "badge-muted";
  }
}

function resultValue(result: LabResult | LabOrderItem): string {
  if (result.resultType === "TEXT") {
    return result.valueText ?? "—";
  }
  if (result.valueNumeric === null || result.valueNumeric === undefined) {
    return "—";
  }
  const unit = "unit" in result && result.unit ? ` ${result.unit}` : "";
  return `${result.valueNumeric}${unit}`;
}

export function LabPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<"worklist" | "orders" | "alerts">("worklist");
  const [worklistPriority, setWorklistPriority] = useState("");
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [term, setTerm] = useState("");
  const [date, setDate] = useState("");
  const [page, setPage] = useState(0);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [newOrderOpen, setNewOrderOpen] = useState(false);
  const [alertAction, setAlertAction] = useState<LabAlert | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const canOrder = hasAnyPermission("LAB:ORDER");
  const canAck = hasAnyPermission("LAB:VERIFY", "LAB:RESULT");

  const worklist = useQuery({
    queryKey: ["lab-worklist", worklistPriority],
    queryFn: () => labApi.worklist(worklistPriority || undefined, 0, 50),
    refetchInterval: 30_000,
  });
  const orders = useQuery({
    queryKey: ["lab-orders", status, priority, term, date, page],
    queryFn: () =>
      labApi.orders({
        status: status || undefined,
        priority: priority || undefined,
        q: term.trim() || undefined,
        date: date || undefined,
        page,
        size: 20,
      }),
  });
  const alerts = useQuery({
    queryKey: ["lab-alerts"],
    queryFn: () => labApi.alerts("OPEN", 50),
    refetchInterval: 30_000,
  });

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["lab-worklist"] }),
      queryClient.invalidateQueries({ queryKey: ["lab-orders"] }),
      queryClient.invalidateQueries({ queryKey: ["lab-alerts"] }),
      queryClient.invalidateQueries({ queryKey: ["lab-order"] }),
    ]);
  };

  const acknowledge = useMutation({
    mutationFn: (payload: { alert: LabAlert; notes?: string }) =>
      labApi.acknowledgeAlert(payload.alert.publicId, {
        version: payload.alert.version,
        notes: payload.notes,
      }),
    onSuccess: async () => {
      setAlertAction(null);
      setActionError(null);
      setNotice(t("lab.alertAcknowledged"));
      await invalidate();
    },
    onError: (error) => setActionError(error),
  });

  const createOrder = useMutation({
    mutationFn: (body: {
      encounterPublicId: string;
      priority?: string;
      clinicalNotes?: string;
      labTestPublicIds: string[];
    }) => labApi.createOrder(body),
    onSuccess: async (created) => {
      setNewOrderOpen(false);
      setNotice(t("lab.saved"));
      setDetailId(created.publicId);
      setTab("orders");
      await invalidate();
    },
  });

  const counts = worklist.data?.counts ?? {};

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("lab.title")}</h1>
          <p className="muted">{t("lab.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              void worklist.refetch();
              void orders.refetch();
              void alerts.refetch();
            }}
          >
            {t("common.refresh")}
          </button>
          {canOrder && (
            <button
              type="button"
              className="button"
              onClick={() => {
                createOrder.reset();
                setNewOrderOpen(true);
              }}
            >
              {t("lab.newOrder")}
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
        <button
          type="button"
          className={`tab${tab === "worklist" ? " is-active" : ""}`}
          onClick={() => setTab("worklist")}
        >
          {t("lab.tab.worklist")}
        </button>
        <button
          type="button"
          className={`tab${tab === "orders" ? " is-active" : ""}`}
          onClick={() => setTab("orders")}
        >
          {t("lab.tab.orders")}
        </button>
        <button
          type="button"
          className={`tab${tab === "alerts" ? " is-active" : ""}`}
          onClick={() => setTab("alerts")}
        >
          {t("lab.tab.alerts")}
          {(alerts.data?.count ?? 0) > 0 ? ` (${alerts.data?.count})` : ""}
        </button>
      </div>

      {tab === "worklist" && (
        <div className="tab-content">
          <section className="kpi-grid">
            {[
              "ORDERED",
              "COLLECTED",
              "RECEIVED",
              "IN_PROCESS",
              "RESULTED",
              "VERIFIED",
            ].map((key) => (
              <div className="kpi-card" key={key}>
                <span className="kpi-label">{t(`labStatus.${key}`)}</span>
                <strong className="kpi-value">{counts[key] ?? 0}</strong>
              </div>
            ))}
          </section>

          <section className="card">
            <header className="card-header">
              <h2>{t("lab.worklist")}</h2>
              <label className="field field-inline">
                <span>{t("common.priority")}</span>
                <select
                  value={worklistPriority}
                  onChange={(event) => setWorklistPriority(event.target.value)}
                >
                  <option value="">{t("common.all")}</option>
                  {LAB_PRIORITIES.map((value) => (
                    <option key={value} value={value}>
                      {t(`orderPriority.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
            </header>

            {worklist.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : worklist.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !worklist.data || worklist.data.items.length === 0 ? (
              <p className="empty-inline">{t("lab.empty")}</p>
            ) : (
              <ul className="board-list">
                {worklist.data.items.map((order) => (
                  <li className="board-item" key={order.publicId}>
                    <div className="board-item-head">
                      <strong>{order.patient.fullName}</strong>
                      <span className="muted mono">
                        {order.patient.medicalNumber}
                      </span>
                      <span className={`badge ${orderBadgeClass(order.status)}`}>
                        {t(`labStatus.${order.status}`)}
                      </span>
                      <span className={`badge ${priorityBadgeClass(order.priority)}`}>
                        {t(`orderPriority.${order.priority}`)}
                      </span>
                    </div>
                    <p className="muted">
                      {t("lab.orderedAt")}: {formatDateTime(order.orderedAt, locale)} ·{" "}
                      {t("lab.items")}: {order.itemCount ?? 0} · {t("lab.results")}:{" "}
                      {order.resultCount ?? 0}
                    </p>
                    {order.clinicalNotes && <p>{order.clinicalNotes}</p>}
                    <div className="board-item-actions">
                      <button
                        type="button"
                        className="button button-sm"
                        onClick={() => setDetailId(order.publicId)}
                      >
                        {t("common.view")}
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {tab === "orders" && (
        <div className="tab-content">
          <section className="card">
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
                  {LAB_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {t(`labStatus.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("common.priority")}</span>
                <select
                  value={priority}
                  onChange={(event) => {
                    setPriority(event.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {LAB_PRIORITIES.map((value) => (
                    <option key={value} value={value}>
                      {t(`orderPriority.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("common.date")}</span>
                <input
                  type="date"
                  value={date}
                  onChange={(event) => {
                    setDate(event.target.value);
                    setPage(0);
                  }}
                />
              </label>
              <label className="field field-inline">
                <span>{t("common.search")}</span>
                <input
                  type="search"
                  value={term}
                  onChange={(event) => {
                    setTerm(event.target.value);
                    setPage(0);
                  }}
                  placeholder={t("adt.patient")}
                />
              </label>
            </div>

            {orders.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : orders.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : orders.data && orders.data.items.length === 0 ? (
              <p className="empty-inline">{t("lab.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("adt.patient")}</th>
                        <th>{t("common.status")}</th>
                        <th>{t("common.priority")}</th>
                        <th>{t("lab.orderedAt")}</th>
                        <th>{t("lab.orderedBy")}</th>
                        <th>{t("lab.items")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {orders.data?.items.map((order) => (
                        <tr key={order.publicId}>
                          <td>
                            {order.patient.fullName}
                            <span className="muted mono">
                              {" "}
                              · {order.patient.medicalNumber}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${orderBadgeClass(order.status)}`}>
                              {t(`labStatus.${order.status}`)}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${priorityBadgeClass(order.priority)}`}>
                              {t(`orderPriority.${order.priority}`)}
                            </span>
                          </td>
                          <td>{formatDateTime(order.orderedAt, locale)}</td>
                          <td>
                            {personName(
                              locale,
                              order.orderedBy.nameAr,
                              order.orderedBy.nameEn,
                            )}
                          </td>
                          <td>
                            {order.resultCount ?? 0} / {order.itemCount ?? 0}
                          </td>
                          <td className="cell-actions">
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => setDetailId(order.publicId)}
                            >
                              {t("common.view")}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {orders.data && (
                  <Pagination
                    page={orders.data.page}
                    totalPages={orders.data.totalPages}
                    total={orders.data.total}
                    onChange={setPage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "alerts" && (
        <div className="tab-content">
          <section className="card">
            <header className="card-header">
              <h2>{t("lab.alerts")}</h2>
              <span className="badge badge-danger">
                {alerts.data?.count ?? 0} {t("lab.alertsCount")}
              </span>
            </header>
            {alerts.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : alerts.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !alerts.data || alerts.data.items.length === 0 ? (
              <p className="empty-inline">{t("lab.noAlerts")}</p>
            ) : (
              <ul className="board-list">
                {alerts.data.items.map((alert) => (
                  <li className="board-item" key={alert.publicId}>
                    <div className="board-item-head">
                      <strong>{alert.patientName}</strong>
                      <span className="muted mono">{alert.medicalNumber}</span>
                      <span className="badge badge-danger">
                        {t(`resultFlag.${alert.flag}`)}
                      </span>
                      <span className="muted">
                        {personName(locale, alert.testNameAr, alert.testNameEn)}:{" "}
                        {alert.valueNumeric ?? alert.valueText ?? "—"}
                      </span>
                    </div>
                    <p className="muted">
                      {t("common.date")}: {formatDateTime(alert.createdAt, locale)}
                    </p>
                    {alert.notes && <p>{alert.notes}</p>}
                    <div className="board-item-actions">
                      <button
                        type="button"
                        className="button button-ghost button-sm"
                        onClick={() => setDetailId(alert.orderPublicId)}
                      >
                        {t("lab.orderDetails")}
                      </button>
                      {alert.status === "OPEN" && canAck && (
                        <button
                          type="button"
                          className="button button-sm"
                          onClick={() => {
                            acknowledge.reset();
                            setAlertAction(alert);
                          }}
                        >
                          {t("lab.acknowledge")}
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {newOrderOpen && (
        <NewOrderModal
          pending={createOrder.isPending}
          error={createOrder.error}
          onClose={() => setNewOrderOpen(false)}
          onSubmit={(body) => createOrder.mutate(body)}
        />
      )}

      {detailId && (
        <OrderDetailModal
          publicId={detailId}
          onClose={() => setDetailId(null)}
          onChanged={async (message) => {
            setNotice(message);
            setActionError(null);
            await invalidate();
          }}
          onError={(error) => setActionError(error)}
        />
      )}

      {alertAction && (
        <AcknowledgeModal
          pending={acknowledge.isPending}
          error={acknowledge.error}
          onClose={() => setAlertAction(null)}
          onSubmit={(notes) =>
            acknowledge.mutate({ alert: alertAction, notes: notes || undefined })
          }
        />
      )}
    </div>
  );
}

type DetailPanel =
  | { kind: "collect" }
  | { kind: "result"; item: LabOrderItem }
  | { kind: "amend"; result: LabResult }
  | { kind: "reject"; specimen: LabSpecimen }
  | null;

function OrderDetailModal({
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
  const [panel, setPanel] = useState<DetailPanel>(null);
  const [reportOpen, setReportOpen] = useState(false);

  const canCollect = hasAnyPermission("LAB:COLLECT");
  const canReceive = hasAnyPermission("LAB:RECEIVE");
  const canProcess = hasAnyPermission("LAB:PROCESS");
  const canResult = hasAnyPermission("LAB:RESULT_ENTER", "LAB:RESULT");
  const canVerify = hasAnyPermission("LAB:VERIFY");
  const canRelease = hasAnyPermission("LAB:REPORT_RELEASE", "LAB:VERIFY");
  const canAmend = hasAnyPermission("LAB:AMEND", "LAB:VERIFY");
  const canPrint = hasAnyPermission("LAB:PRINT", "LAB:READ");

  const detail = useQuery({
    queryKey: ["lab-order", publicId],
    queryFn: () => labApi.order(publicId),
  });
  const report = useQuery({
    queryKey: ["lab-report", publicId],
    queryFn: () => labApi.orderReport(publicId),
    enabled: reportOpen,
  });

  const refresh = async (message: string) => {
    setPanel(null);
    await onChanged(message);
    await queryClient.invalidateQueries({ queryKey: ["lab-order", publicId] });
  };

  const collect = useMutation({
    mutationFn: (body: {
      version: number;
      specimenType?: string;
      barcode?: string;
      notes?: string;
    }) => labApi.collect(publicId, body),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const process = useMutation({
    mutationFn: (version: number) => labApi.process(publicId, { version }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const enterResult = useMutation({
    mutationFn: (body: {
      item: LabOrderItem;
      valueNumeric?: number;
      valueText?: string;
      comment?: string;
    }) =>
      labApi.enterResult(publicId, {
        labTestPublicId: body.item.publicId,
        valueNumeric: body.valueNumeric,
        valueText: body.valueText,
        comment: body.comment,
      }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const verify = useMutation({
    mutationFn: (result: LabResult) =>
      labApi.verify(result.publicId, { version: result.version }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const release = useMutation({
    mutationFn: (result: LabResult) =>
      labApi.release(result.publicId, { version: result.version }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const amend = useMutation({
    mutationFn: (body: {
      result: LabResult;
      reason: string;
      valueNumeric?: number;
      valueText?: string;
      comment?: string;
    }) =>
      labApi.amend(body.result.publicId, {
        version: body.result.version,
        reason: body.reason,
        valueNumeric: body.valueNumeric,
        valueText: body.valueText,
        comment: body.comment,
      }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const receive = useMutation({
    mutationFn: (specimen: LabSpecimen) =>
      labApi.receiveSpecimen(specimen.publicId, { version: specimen.version }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });
  const reject = useMutation({
    mutationFn: (body: { specimen: LabSpecimen; reason: string }) =>
      labApi.rejectSpecimen(body.specimen.publicId, {
        version: body.specimen.version,
        reason: body.reason,
      }),
    onSuccess: () => void refresh(t("lab.saved")),
    onError,
  });

  const order = detail.data;

  return (
    <Modal title={t("lab.orderDetails")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !order ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : panel?.kind === "collect" ? (
        <CollectForm
          pending={collect.isPending}
          error={collect.error}
          defaultType={order.items[0]?.specimenType ?? "BLOOD"}
          onBack={() => setPanel(null)}
          onSubmit={(body) => collect.mutate({ ...body, version: order.version })}
        />
      ) : panel?.kind === "result" ? (
        <ResultForm
          item={panel.item}
          pending={enterResult.isPending}
          error={enterResult.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => enterResult.mutate({ item: panel.item, ...body })}
        />
      ) : panel?.kind === "amend" ? (
        <AmendForm
          result={panel.result}
          pending={amend.isPending}
          error={amend.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => amend.mutate({ result: panel.result, ...body })}
        />
      ) : panel?.kind === "reject" ? (
        <RejectSpecimenForm
          pending={reject.isPending}
          error={reject.error}
          onBack={() => setPanel(null)}
          onSubmit={(reason) => reject.mutate({ specimen: panel.specimen, reason })}
        />
      ) : reportOpen ? (
        <div className="stack-form">
          <div className="form-actions form-actions-start print-hide">
            <button
              type="button"
              className="button button-ghost"
              onClick={() => setReportOpen(false)}
            >
              {t("common.back")}
            </button>
            <button type="button" className="button" onClick={() => window.print()}>
              {t("common.print")}
            </button>
          </div>
          {report.isPending ? (
            <p className="muted">{t("common.loading")}</p>
          ) : report.isError || !report.data ? (
            <div className="alert alert-danger">{t("common.loadError")}</div>
          ) : (
            <LabReportSheet report={report.data} />
          )}
        </div>
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("adt.patient")}</span>
              <strong>{order.patient.fullName}</strong>
              <span className="muted mono"> · {order.patient.medicalNumber}</span>
            </div>
            <div>
              <span className="muted">{t("common.status")}</span>
              <span className={`badge ${orderBadgeClass(order.status)}`}>
                {t(`labStatus.${order.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("common.priority")}</span>
              <span className={`badge ${priorityBadgeClass(order.priority)}`}>
                {t(`orderPriority.${order.priority}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("lab.orderedAt")}</span>
              <strong>{formatDateTime(order.orderedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("lab.orderedBy")}</span>
              <strong>
                {personName(locale, order.orderedBy.nameAr, order.orderedBy.nameEn)}
              </strong>
            </div>
            <div>
              <span className="muted">{t("common.version")}</span>
              <strong>{order.version}</strong>
            </div>
          </div>

          {order.clinicalNotes && (
            <p className="muted">
              {t("lab.clinicalNotes")}: {order.clinicalNotes}
            </p>
          )}

          <div className="form-actions form-actions-start">
            {(order.status === "ORDERED" || order.status === "REJECTED") && canCollect && (
              <button
                type="button"
                className="button"
                onClick={() => setPanel({ kind: "collect" })}
              >
                {t("lab.collect")}
              </button>
            )}
            {order.status === "RECEIVED" && canProcess && (
              <button
                type="button"
                className="button"
                disabled={process.isPending}
                onClick={() => process.mutate(order.version)}
              >
                {t("lab.process")}
              </button>
            )}
            {canPrint && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setReportOpen(true)}
              >
                {t("lab.report")}
              </button>
            )}
          </div>

          <section>
            <h3>{t("lab.items")}</h3>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("lab.tests")}</th>
                    <th>{t("lab.flag")}</th>
                    <th>{t("lab.value")}</th>
                    <th>{t("lab.reference")}</th>
                    <th>{t("common.status")}</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {order.items.map((item) => {
                    const result = order.results.find(
                      (row) => row.publicId === item.resultPublicId,
                    );
                    const reference =
                      item.resultType === "TEXT"
                        ? item.refText ?? "—"
                        : `${item.refLow ?? "—"} – ${item.refHigh ?? "—"}${
                            item.unit ? ` ${item.unit}` : ""
                          }`;
                    const editable =
                      canResult &&
                      !["VERIFIED", "RELEASED"].includes(item.resultStatus ?? "");
                    return (
                      <tr key={item.publicId}>
                        <td>
                          <strong>
                            {personName(locale, item.testNameAr, item.testNameEn)}
                          </strong>
                          <span className="muted mono"> · {item.testCode}</span>
                        </td>
                        <td>
                          {item.flag ? (
                            <span className={`badge ${flagBadgeClass(item.flag)}`}>
                              {t(`resultFlag.${item.flag}`)}
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td className="mono">{resultValue(item)}</td>
                        <td className="muted">{reference}</td>
                        <td>
                          {item.resultStatus ? (
                            <span className="badge badge-muted">
                              {t(`labStatus.${item.resultStatus}`)}
                            </span>
                          ) : (
                            <span className="muted">—</span>
                          )}
                        </td>
                        <td className="cell-actions">
                          {editable && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => setPanel({ kind: "result", item })}
                            >
                              {t("lab.enterResult")}
                            </button>
                          )}
                          {result && result.status === "RESULTED" && canVerify && (
                            <button
                              type="button"
                              className="button button-sm"
                              disabled={verify.isPending}
                              onClick={() => verify.mutate(result)}
                            >
                              {t("lab.verify")}
                            </button>
                          )}
                          {result && result.status === "VERIFIED" && canRelease && (
                            <button
                              type="button"
                              className="button button-sm"
                              disabled={release.isPending}
                              onClick={() => release.mutate(result)}
                            >
                              {t("lab.release")}
                            </button>
                          )}
                          {result &&
                            ["VERIFIED", "RELEASED"].includes(result.status) &&
                            canAmend && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() => setPanel({ kind: "amend", result })}
                              >
                                {t("lab.amend")}
                              </button>
                            )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h3>{t("lab.specimens")}</h3>
            {order.specimens.length === 0 ? (
              <p className="empty-inline">{t("lab.noResults")}</p>
            ) : (
              <ul className="board-list">
                {order.specimens.map((specimen) => (
                  <li className="board-item" key={specimen.publicId}>
                    <div className="board-item-head">
                      <strong className="mono">{specimen.barcode}</strong>
                      <span className="muted">
                        {t(`specimenType.${specimen.specimenType}`)}
                      </span>
                      <span className="badge badge-muted">
                        {t(`specimenStatus.${specimen.status}`)}
                      </span>
                    </div>
                    <p className="muted">
                      {t("common.date")}: {formatDateTime(specimen.collectedAt, locale)}
                    </p>
                    {specimen.rejectionReason && (
                      <p className="field-error">{specimen.rejectionReason}</p>
                    )}
                    {specimen.status === "COLLECTED" && canReceive && (
                      <div className="board-item-actions">
                        <button
                          type="button"
                          className="button button-sm"
                          disabled={receive.isPending}
                          onClick={() => receive.mutate(specimen)}
                        >
                          {t("lab.receive")}
                        </button>
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => setPanel({ kind: "reject", specimen })}
                        >
                          {t("lab.rejectSpecimen")}
                        </button>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <h3>{t("lab.results")}</h3>
            {order.results.length === 0 ? (
              <p className="empty-inline">{t("lab.noResults")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("lab.tests")}</th>
                      <th>{t("lab.value")}</th>
                      <th>{t("lab.flag")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("lab.printedBy")}</th>
                      <th>{t("common.date")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {order.results.map((result) => (
                      <tr key={result.publicId}>
                        <td>
                          {personName(locale, result.testNameAr, result.testNameEn)}
                        </td>
                        <td className="mono">{resultValue(result)}</td>
                        <td>
                          {result.flag ? (
                            <span className={`badge ${flagBadgeClass(result.flag)}`}>
                              {t(`resultFlag.${result.flag}`)}
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td>
                          <span className="badge badge-muted">
                            {t(`labStatus.${result.status}`)}
                          </span>
                        </td>
                        <td>
                          {personName(
                            locale,
                            result.enteredBy.nameAr,
                            result.enteredBy.nameEn,
                          )}
                        </td>
                        <td>{formatDateTime(result.enteredAt, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}
    </Modal>
  );
}

function CollectForm({
  pending,
  error,
  defaultType,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  defaultType: string;
  onBack: () => void;
  onSubmit: (body: { specimenType?: string; barcode?: string; notes?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [specimenType, setSpecimenType] = useState(defaultType);
  const [barcode, setBarcode] = useState("");
  const [notes, setNotes] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      specimenType,
      barcode: barcode.trim() || undefined,
      notes: notes.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("lab.collectTitle")}</h3>
      <label className="field">
        <span>{t("lab.specimenType")}</span>
        <select
          value={specimenType}
          onChange={(event) => setSpecimenType(event.target.value)}
        >
          {SPECIMEN_TYPES.map((value) => (
            <option key={value} value={value}>
              {t(`specimenType.${value}`)}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>{t("lab.barcode")}</span>
        <input
          type="text"
          value={barcode}
          onChange={(event) => setBarcode(event.target.value)}
          maxLength={80}
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

function ResultForm({
  item,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  item: LabOrderItem;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    valueNumeric?: number;
    valueText?: string;
    comment?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [numeric, setNumeric] = useState(
    item.valueNumeric === null || item.valueNumeric === undefined
      ? ""
      : String(item.valueNumeric),
  );
  const [text, setText] = useState(item.valueText ?? "");
  const [comment, setComment] = useState(item.comment ?? "");
  const isText = item.resultType === "TEXT";

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      valueNumeric: isText ? undefined : Number(numeric),
      valueText: isText ? text.trim() : undefined,
      comment: comment.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("lab.resultTitle")}</h3>
      <p className="muted">
        {personName(locale, item.testNameAr, item.testNameEn)} ·{" "}
        <span className="mono">{item.testCode}</span>
        {item.unit ? ` · ${item.unit}` : ""}
      </p>
      {isText ? (
        <label className="field">
          <span>{t("lab.valueText")}</span>
          <input
            type="text"
            value={text}
            onChange={(event) => setText(event.target.value)}
            maxLength={500}
            required
          />
        </label>
      ) : (
        <label className="field">
          <span>{t("lab.value")}</span>
          <input
            type="number"
            step="any"
            value={numeric}
            onChange={(event) => setNumeric(event.target.value)}
            required
          />
        </label>
      )}
      <label className="field">
        <span>{t("lab.comment")}</span>
        <textarea
          value={comment}
          onChange={(event) => setComment(event.target.value)}
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
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button
          type="submit"
          className="button"
          disabled={pending || (isText ? !text.trim() : numeric.trim() === "")}
        >
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function AmendForm({
  result,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  result: LabResult;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    reason: string;
    valueNumeric?: number;
    valueText?: string;
    comment?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const isText = result.resultType === "TEXT";
  const [numeric, setNumeric] = useState(
    result.valueNumeric === null || result.valueNumeric === undefined
      ? ""
      : String(result.valueNumeric),
  );
  const [text, setText] = useState(result.valueText ?? "");
  const [comment, setComment] = useState(result.comment ?? "");
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      reason: reason.trim(),
      valueNumeric: isText ? undefined : Number(numeric),
      valueText: isText ? text.trim() : undefined,
      comment: comment.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("lab.amend")}</h3>
      <p className="muted">
        {personName(locale, result.testNameAr, result.testNameEn)} ·{" "}
        <span className="mono">{result.testCode}</span>
      </p>
      {isText ? (
        <label className="field">
          <span>{t("lab.valueText")}</span>
          <input
            type="text"
            value={text}
            onChange={(event) => setText(event.target.value)}
            maxLength={500}
            required
          />
        </label>
      ) : (
        <label className="field">
          <span>{t("lab.value")}</span>
          <input
            type="number"
            step="any"
            value={numeric}
            onChange={(event) => setNumeric(event.target.value)}
            required
          />
        </label>
      )}
      <label className="field">
        <span>{t("lab.comment")}</span>
        <textarea
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          rows={2}
          maxLength={1000}
        />
      </label>
      <label className="field">
        <span>{t("lab.amendReason")}</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={3}
          maxLength={1000}
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
        <button type="submit" className="button" disabled={!reason.trim() || pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function RejectSpecimenForm({
  pending,
  error,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (reason: string) => void;
}) {
  const { t, locale } = useI18n();
  const [reason, setReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit(reason.trim());
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("lab.rejectSpecimen")}</h3>
      <label className="field">
        <span>{t("lab.rejectReason")}</span>
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
        <button type="submit" className="button" disabled={!reason.trim() || pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function NewOrderModal({
  pending,
  error,
  onClose,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    encounterPublicId: string;
    priority?: string;
    clinicalNotes?: string;
    labTestPublicIds: string[];
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [encounterId, setEncounterId] = useState("");
  const [priority, setPriority] = useState("ROUTINE");
  const [notes, setNotes] = useState("");
  const [selected, setSelected] = useState<string[]>([]);

  const encounters = useQuery({
    queryKey: ["patient-open-encounters", patient?.publicId],
    queryFn: () =>
      clinicalApi.search({ patient: patient?.publicId, status: "OPEN", size: 10 }),
    enabled: Boolean(patient),
  });
  const tests = useQuery({
    queryKey: ["lab-tests"],
    queryFn: () => labApi.tests(),
    staleTime: 300_000,
  });

  useEffect(() => {
    if (encounters.data && encounters.data.items.length === 1) {
      setEncounterId(encounters.data.items[0].publicId);
    }
  }, [encounters.data]);

  function toggle(test: LabTest) {
    setSelected((current) =>
      current.includes(test.publicId)
        ? current.filter((value) => value !== test.publicId)
        : [...current, test.publicId],
    );
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!encounterId || selected.length === 0) {
      return;
    }
    onSubmit({
      encounterPublicId: encounterId,
      priority,
      clinicalNotes: notes.trim() || undefined,
      labTestPublicIds: selected,
    });
  }

  return (
    <Modal title={t("lab.newOrderTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("adt.patient")}</span>
          <PatientPicker
            selected={patient}
            onSelect={(value) => {
              setPatient(value);
              setEncounterId("");
            }}
          />
        </label>
        <label className="field">
          <span>{t("adt.encounter")}</span>
          <select
            value={encounterId}
            onChange={(event) => setEncounterId(event.target.value)}
            disabled={!patient || encounters.isPending}
            required
          >
            <option value="">{t("common.select")}</option>
            {encounters.data?.items.map((encounter) => (
              <option key={encounter.publicId} value={encounter.publicId}>
                {encounter.type} · {formatDateTime(encounter.startedAt, locale)}
              </option>
            ))}
          </select>
          {patient && !encounters.isPending && (encounters.data?.items.length ?? 0) === 0 && (
            <span className="field-hint">{t("lab.encounterRequired")}</span>
          )}
        </label>
        <div className="form-grid">
          <label className="field">
            <span>{t("common.priority")}</span>
            <select
              value={priority}
              onChange={(event) => setPriority(event.target.value)}
            >
              {LAB_PRIORITIES.map((value) => (
                <option key={value} value={value}>
                  {t(`orderPriority.${value}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("lab.clinicalNotes")}</span>
            <input
              type="text"
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
              maxLength={1000}
            />
          </label>
        </div>
        <div className="field">
          <span>{t("lab.tests")}</span>
          {tests.isPending ? (
            <p className="muted">{t("common.loading")}</p>
          ) : tests.isError ? (
            <div className="alert alert-danger">{t("common.loadError")}</div>
          ) : (
            <div className="check-list">
              {tests.data?.map((test) => (
                <label className="field-checkbox" key={test.publicId}>
                  <input
                    type="checkbox"
                    checked={selected.includes(test.publicId)}
                    onChange={() => toggle(test)}
                  />
                  <span>
                    {personName(locale, test.nameAr, test.nameEn)}
                    <span className="muted mono"> · {test.code}</span>
                    <span className="muted">
                      {" "}
                      · {t(`specimenType.${test.specimenType}`)}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          )}
          {selected.length === 0 && (
            <span className="field-hint">{t("lab.selectTest")}</span>
          )}
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
          <button
            type="submit"
            className="button"
            disabled={!encounterId || selected.length === 0 || pending}
          >
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function AcknowledgeModal({
  pending,
  error,
  onClose,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (notes: string) => void;
}) {
  const { t, locale } = useI18n();
  const [notes, setNotes] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit(notes.trim());
  }

  return (
    <Modal title={t("lab.acknowledge")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("common.notes")}</span>
          <textarea
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            rows={3}
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
          <button type="submit" className="button" disabled={pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function LabReportSheet({ report }: { report: LabReport }) {
  const { t, locale } = useI18n();
  const title =
    locale === "ar" ? report.header.reportTitleAr : report.header.reportTitleEn;
  const hospital =
    locale === "ar" ? report.header.nameAr : report.header.nameEn;

  return (
    <div className="print-sheet">
      <header className="print-header">
        <div>
          <h2>{title}</h2>
          <p>{hospital}</p>
        </div>
        <div className="print-meta">
          <span>
            {t("lab.printedAt")}: {formatDateTime(report.printedAt, locale)}
          </span>
          <span>
            {t("lab.printedBy")}:{" "}
            {personName(locale, report.printedBy.nameAr, report.printedBy.nameEn)}
          </span>
        </div>
      </header>

      <section className="print-block">
        <h3>{t("adt.patient")}</h3>
        <p>
          <strong>{report.patient.fullName}</strong> ·{" "}
          <span className="mono">{report.patient.medicalNumber}</span>
        </p>
        <p className="muted">
          {t("lab.order")}: {report.order.publicId} · {t("lab.orderedAt")}:{" "}
          {formatDateTime(report.order.orderedAt, locale)}
        </p>
      </section>

      <section className="print-block">
        <h3>{t("lab.results")}</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th>{t("lab.tests")}</th>
              <th>{t("lab.value")}</th>
              <th>{t("lab.unit")}</th>
              <th>{t("lab.reference")}</th>
              <th>{t("lab.flag")}</th>
            </tr>
          </thead>
          <tbody>
            {report.results.map((result) => (
              <tr key={result.publicId}>
                <td>
                  {personName(locale, result.testNameAr, result.testNameEn)}
                  <span className="muted mono"> · {result.testCode}</span>
                </td>
                <td className="mono">
                  {result.valueNumeric ?? result.valueText ?? "—"}
                </td>
                <td>{result.unit ?? "—"}</td>
                <td>
                  {result.resultType === "TEXT"
                    ? result.refText ?? "—"
                    : `${result.refLow ?? "—"} – ${result.refHigh ?? "—"}`}
                </td>
                <td>
                  {result.flag ? t(`resultFlag.${result.flag}`) : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <footer className="print-footer">
        <span>{formatDate(report.printedAt, locale)}</span>
        <span> · {hospital}</span>
      </footer>
    </div>
  );
}
