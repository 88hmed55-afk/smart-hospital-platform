import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { formatDate, formatDateTime, todayInput } from "../../shared/ui/format";
import {
  BATCH_STATUSES,
  DRUG_FORMS,
  MOVEMENT_TYPES,
  pharmacyApi,
  type DispensingDetail,
  type Drug,
  type PrescriptionItem,
} from "./pharmacyApi";

function personName(
  locale: Locale,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return locale === "ar" ? ar || en || "—" : en || ar || "—";
}

function prescriptionBadge(status: string): string {
  switch (status) {
    case "DISPENSED":
      return "badge-success";
    case "PARTIAL":
    case "REVIEWED":
      return "badge-warn";
    case "REJECTED":
    case "CANCELLED":
      return "badge-danger";
    case "ISSUED":
      return "badge-info";
    default:
      return "badge-muted";
  }
}

function batchBadge(status: string): string {
  switch (status) {
    case "ACTIVE":
      return "badge-success";
    case "QUARANTINED":
      return "badge-warn";
    case "EXPIRED":
    case "WRITTEN_OFF":
      return "badge-danger";
    default:
      return "badge-muted";
  }
}

function interactionBadge(severity: string): string {
  switch (severity) {
    case "CONTRAINDICATED":
    case "MAJOR":
      return "badge-danger";
    case "MODERATE":
      return "badge-warn";
    default:
      return "badge-muted";
  }
}

function deltaLabel(value: number): string {
  return value > 0 ? `+${value}` : String(value);
}

export function PharmacyPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<"queue" | "drugs" | "batches" | "movements" | "alerts">(
    "queue",
  );
  const [queuePage, setQueuePage] = useState(0);
  const [drugTerm, setDrugTerm] = useState("");
  const [drugPage, setDrugPage] = useState(0);
  const [batchDrug, setBatchDrug] = useState("");
  const [batchStatus, setBatchStatus] = useState("");
  const [batchTerm, setBatchTerm] = useState("");
  const [batchPage, setBatchPage] = useState(0);
  const [movementDrug, setMovementDrug] = useState("");
  const [movementType, setMovementType] = useState("");
  const [movementDate, setMovementDate] = useState("");
  const [movementPage, setMovementPage] = useState(0);
  const [prescriptionId, setPrescriptionId] = useState<string | null>(null);
  const [receiveOpen, setReceiveOpen] = useState(false);
  const [newDrugOpen, setNewDrugOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const canWrite = hasAnyPermission("PHR:WRITE");
  const canReceive = hasAnyPermission("PHR:RECEIVE");

  const queue = useQuery({
    queryKey: ["phr-queue", queuePage],
    queryFn: () => pharmacyApi.queue(queuePage, 20),
    refetchInterval: 30_000,
  });
  const drugs = useQuery({
    queryKey: ["phr-drugs", drugTerm, drugPage],
    queryFn: () => pharmacyApi.drugs(drugTerm.trim() || undefined, drugPage, 20),
  });
  const drugOptions = useQuery({
    queryKey: ["phr-drug-options"],
    queryFn: () => pharmacyApi.drugs(undefined, 0, 200),
    staleTime: 120_000,
  });
  const batches = useQuery({
    queryKey: ["phr-batches", batchDrug, batchStatus, batchTerm, batchPage],
    queryFn: () =>
      pharmacyApi.batches({
        drug: batchDrug || undefined,
        status: batchStatus || undefined,
        q: batchTerm.trim() || undefined,
        page: batchPage,
        size: 20,
      }),
  });
  const movements = useQuery({
    queryKey: ["phr-movements", movementDrug, movementType, movementDate, movementPage],
    queryFn: () =>
      pharmacyApi.movements({
        drug: movementDrug || undefined,
        type: movementType || undefined,
        date: movementDate || undefined,
        page: movementPage,
        size: 20,
      }),
  });
  const alerts = useQuery({
    queryKey: ["phr-alerts"],
    queryFn: () => pharmacyApi.alerts(50),
    refetchInterval: 60_000,
  });

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["phr-queue"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-drugs"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-drug-options"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-batches"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-movements"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-alerts"] }),
      queryClient.invalidateQueries({ queryKey: ["phr-prescription"] }),
    ]);
  };

  const receiveBatch = useMutation({
    mutationFn: (body: Parameters<typeof pharmacyApi.receiveBatch>[0]) =>
      pharmacyApi.receiveBatch(body),
    onSuccess: async () => {
      setReceiveOpen(false);
      setNotice(t("phr.receivedOk"));
      setActionError(null);
      await invalidate();
    },
  });

  const createDrug = useMutation({
    mutationFn: (body: Parameters<typeof pharmacyApi.createDrug>[0]) =>
      pharmacyApi.createDrug(body),
    onSuccess: async () => {
      setNewDrugOpen(false);
      setNotice(t("phr.drugCreated"));
      setActionError(null);
      await invalidate();
    },
  });

  const openBatchesFor = (drug: Drug) => {
    setBatchDrug(drug.publicId);
    setBatchPage(0);
    setTab("batches");
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("phr.title")}</h1>
          <p className="muted">{t("phr.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              void queue.refetch();
              void drugs.refetch();
              void batches.refetch();
              void movements.refetch();
              void alerts.refetch();
            }}
          >
            {t("common.refresh")}
          </button>
          {canReceive && (
            <button
              type="button"
              className="button button-ghost"
              onClick={() => {
                receiveBatch.reset();
                setReceiveOpen(true);
              }}
            >
              {t("phr.receive")}
            </button>
          )}
          {canWrite && (
            <button
              type="button"
              className="button"
              onClick={() => {
                createDrug.reset();
                setNewDrugOpen(true);
              }}
            >
              {t("phr.newDrug")}
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
          className={`tab${tab === "queue" ? " is-active" : ""}`}
          onClick={() => setTab("queue")}
        >
          {t("phr.tab.queue")}
        </button>
        <button
          type="button"
          className={`tab${tab === "drugs" ? " is-active" : ""}`}
          onClick={() => setTab("drugs")}
        >
          {t("phr.tab.drugs")}
        </button>
        <button
          type="button"
          className={`tab${tab === "batches" ? " is-active" : ""}`}
          onClick={() => setTab("batches")}
        >
          {t("phr.tab.batches")}
        </button>
        <button
          type="button"
          className={`tab${tab === "movements" ? " is-active" : ""}`}
          onClick={() => setTab("movements")}
        >
          {t("phr.tab.movements")}
        </button>
        <button
          type="button"
          className={`tab${tab === "alerts" ? " is-active" : ""}`}
          onClick={() => setTab("alerts")}
        >
          {t("phr.tab.alerts")}
          {(alerts.data?.lowStock.length ?? 0) + (alerts.data?.expiring.length ?? 0) > 0
            ? ` (${(alerts.data?.lowStock.length ?? 0) + (alerts.data?.expiring.length ?? 0)})`
            : ""}
        </button>
      </div>

      {tab === "queue" && (
        <div className="tab-content">
          <section className="card">
            <header className="card-header">
              <h2>{t("phr.queue")}</h2>
              <span className="badge badge-muted">
                {t("common.total")}: {queue.data?.total ?? 0}
              </span>
            </header>
            {queue.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : queue.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !queue.data || queue.data.items.length === 0 ? (
              <p className="empty-inline">{t("phr.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("phr.patient")}</th>
                        <th>{t("common.status")}</th>
                        <th>{t("phr.issuedAt")}</th>
                        <th>{t("phr.prescriber")}</th>
                        <th>{t("phr.itemCount")}</th>
                        <th>{t("phr.dispensed")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {queue.data.items.map((row) => (
                        <tr key={row.publicId}>
                          <td>
                            {row.patient.fullName}
                            <span className="muted mono">
                              {" "}
                              · {row.patient.medicalNumber}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${prescriptionBadge(row.status)}`}>
                              {t(`phrStatus.${row.status}`)}
                            </span>
                          </td>
                          <td>{formatDateTime(row.issuedAt, locale)}</td>
                          <td>
                            {personName(
                              locale,
                              row.prescribedBy.nameAr,
                              row.prescribedBy.nameEn,
                            )}
                          </td>
                          <td>{row.itemCount ?? 0}</td>
                          <td className="mono">
                            {row.dispensedQuantity ?? 0} / {row.totalQuantity ?? 0}
                          </td>
                          <td className="cell-actions">
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => {
                                setActionError(null);
                                setPrescriptionId(row.publicId);
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
                {queue.data && (
                  <Pagination
                    page={queue.data.page}
                    totalPages={queue.data.totalPages}
                    total={queue.data.total}
                    onChange={setQueuePage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "drugs" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("common.search")}</span>
                <input
                  type="search"
                  value={drugTerm}
                  onChange={(event) => {
                    setDrugTerm(event.target.value);
                    setDrugPage(0);
                  }}
                  placeholder={t("phr.drug")}
                />
              </label>
            </div>
            {drugs.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : drugs.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !drugs.data || drugs.data.items.length === 0 ? (
              <p className="empty-inline">{t("phr.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("common.code")}</th>
                        <th>{t("phr.drug")}</th>
                        <th>{t("phr.form")}</th>
                        <th>{t("phr.stock")}</th>
                        <th>{t("phr.reorderLevel")}</th>
                        <th>{t("phr.batches")}</th>
                        <th>{t("phr.unitPrice")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {drugs.data.items.map((drug) => (
                        <tr key={drug.publicId}>
                          <td className="mono">{drug.code}</td>
                          <td>
                            {personName(locale, drug.nameAr, drug.nameEn)}
                            {drug.lowStock && (
                              <span className="badge badge-danger">
                                {" "}
                                {t("phr.lowStock")}
                              </span>
                            )}
                          </td>
                          <td>
                            {drug.form}
                            {drug.strength ? ` · ${drug.strength}` : ""}
                          </td>
                          <td className="mono">{drug.stockAvailable}</td>
                          <td className="mono">{drug.reorderLevel}</td>
                          <td className="mono">{drug.batchCount}</td>
                          <td className="mono">{drug.unitPrice ?? "—"}</td>
                          <td className="cell-actions">
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => openBatchesFor(drug)}
                            >
                              {t("phr.batches")}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {drugs.data && (
                  <Pagination
                    page={drugs.data.page}
                    totalPages={drugs.data.totalPages}
                    total={drugs.data.total}
                    onChange={setDrugPage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "batches" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("phr.drug")}</span>
                <select
                  value={batchDrug}
                  onChange={(event) => {
                    setBatchDrug(event.target.value);
                    setBatchPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {drugOptions.data?.items.map((drug) => (
                    <option key={drug.publicId} value={drug.publicId}>
                      {drug.code} · {drug.nameAr}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("common.status")}</span>
                <select
                  value={batchStatus}
                  onChange={(event) => {
                    setBatchStatus(event.target.value);
                    setBatchPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {BATCH_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {t(`batchStatus.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("common.search")}</span>
                <input
                  type="search"
                  value={batchTerm}
                  onChange={(event) => {
                    setBatchTerm(event.target.value);
                    setBatchPage(0);
                  }}
                  placeholder={t("phr.batchNumber")}
                />
              </label>
            </div>
            {batches.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : batches.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !batches.data || batches.data.items.length === 0 ? (
              <p className="empty-inline">{t("phr.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("phr.drug")}</th>
                        <th>{t("phr.batchNumber")}</th>
                        <th>{t("phr.expiry")}</th>
                        <th>{t("phr.available")}</th>
                        <th>{t("phr.received")}</th>
                        <th>{t("phr.supplier")}</th>
                        <th>{t("common.status")}</th>
                        <th>{t("phr.expiringSoon")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {batches.data.items.map((batch) => (
                        <tr key={batch.publicId}>
                          <td>
                            {personName(locale, batch.drug.nameAr, batch.drug.nameEn)}
                            <span className="muted mono"> · {batch.drug.code}</span>
                          </td>
                          <td className="mono">{batch.batchNumber}</td>
                          <td>{formatDate(batch.expiryDate, locale)}</td>
                          <td className="mono">{batch.quantityAvailable}</td>
                          <td className="mono">{batch.quantityReceived}</td>
                          <td>{batch.supplier ?? "—"}</td>
                          <td>
                            <span className={`badge ${batchBadge(batch.status)}`}>
                              {t(`batchStatus.${batch.status}`)}
                            </span>
                          </td>
                          <td className="mono">
                            {batch.daysToExpiry === null || batch.daysToExpiry === undefined
                              ? "—"
                              : `${batch.daysToExpiry} ${t("phr.days")}`}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {batches.data && (
                  <Pagination
                    page={batches.data.page}
                    totalPages={batches.data.totalPages}
                    total={batches.data.total}
                    onChange={setBatchPage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {tab === "movements" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("phr.drug")}</span>
                <select
                  value={movementDrug}
                  onChange={(event) => {
                    setMovementDrug(event.target.value);
                    setMovementPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {drugOptions.data?.items.map((drug) => (
                    <option key={drug.publicId} value={drug.publicId}>
                      {drug.code} · {drug.nameAr}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("phr.movementType")}</span>
                <select
                  value={movementType}
                  onChange={(event) => {
                    setMovementType(event.target.value);
                    setMovementPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {MOVEMENT_TYPES.map((value) => (
                    <option key={value} value={value}>
                      {t(`movementType.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("common.date")}</span>
                <input
                  type="date"
                  value={movementDate}
                  max={todayInput()}
                  onChange={(event) => {
                    setMovementDate(event.target.value);
                    setMovementPage(0);
                  }}
                />
              </label>
            </div>
            {movements.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : movements.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !movements.data || movements.data.items.length === 0 ? (
              <p className="empty-inline">{t("phr.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("phr.performedAt")}</th>
                        <th>{t("phr.drug")}</th>
                        <th>{t("phr.batch")}</th>
                        <th>{t("phr.movementType")}</th>
                        <th>{t("phr.delta")}</th>
                        <th>{t("phr.reason")}</th>
                        <th>{t("phr.performedBy")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {movements.data.items.map((movement) => (
                        <tr key={movement.publicId}>
                          <td>{formatDateTime(movement.performedAt, locale)}</td>
                          <td>
                            {personName(locale, movement.drug.nameAr, movement.drug.nameEn)}
                            <span className="muted mono"> · {movement.drug.code}</span>
                          </td>
                          <td className="mono">{movement.batch.batchNumber ?? "—"}</td>
                          <td>
                            <span className="badge badge-muted">
                              {t(`movementType.${movement.movementType}`)}
                            </span>
                          </td>
                          <td className="mono">{deltaLabel(movement.quantityDelta)}</td>
                          <td>{movement.reason ?? "—"}</td>
                          <td>
                            {personName(
                              locale,
                              movement.performedBy.nameAr,
                              movement.performedBy.nameEn,
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {movements.data && (
                  <Pagination
                    page={movements.data.page}
                    totalPages={movements.data.totalPages}
                    total={movements.data.total}
                    onChange={setMovementPage}
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
              <h2>{t("phr.lowStock")}</h2>
              <span className="badge badge-danger">
                {alerts.data?.lowStock.length ?? 0}
              </span>
            </header>
            {alerts.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : alerts.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : !alerts.data || alerts.data.lowStock.length === 0 ? (
              <p className="empty-inline">{t("phr.noAlerts")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("phr.drug")}</th>
                      <th>{t("phr.stock")}</th>
                      <th>{t("phr.reorderLevel")}</th>
                      <th>{t("phr.batches")}</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.data.lowStock.map((drug) => (
                      <tr key={drug.publicId}>
                        <td>
                          {personName(locale, drug.nameAr, drug.nameEn)}
                          <span className="muted mono"> · {drug.code}</span>
                        </td>
                        <td className="mono">{drug.stockAvailable}</td>
                        <td className="mono">{drug.reorderLevel}</td>
                        <td className="mono">{drug.batchCount}</td>
                        <td className="cell-actions">
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => openBatchesFor(drug)}
                          >
                            {t("phr.batches")}
                          </button>
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
              <h2>{t("phr.expiring")}</h2>
              <span className="badge badge-warn">
                {t("phr.expiryWarningDays")}: {alerts.data?.expiryWarningDays ?? 0}
              </span>
            </header>
            {alerts.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : !alerts.data || alerts.data.expiring.length === 0 ? (
              <p className="empty-inline">{t("phr.noAlerts")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("phr.drug")}</th>
                      <th>{t("phr.batchNumber")}</th>
                      <th>{t("phr.expiry")}</th>
                      <th>{t("phr.available")}</th>
                      <th>{t("phr.expiringSoon")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {alerts.data.expiring.map((batch) => (
                      <tr key={batch.publicId}>
                        <td>
                          {personName(locale, batch.drug.nameAr, batch.drug.nameEn)}
                          <span className="muted mono"> · {batch.drug.code}</span>
                        </td>
                        <td className="mono">{batch.batchNumber}</td>
                        <td>{formatDate(batch.expiryDate, locale)}</td>
                        <td className="mono">{batch.quantityAvailable}</td>
                        <td className="mono">
                          {batch.daysToExpiry === null || batch.daysToExpiry === undefined
                            ? "—"
                            : `${batch.daysToExpiry} ${t("phr.days")}`}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {prescriptionId && (
        <PrescriptionModal
          publicId={prescriptionId}
          onClose={() => setPrescriptionId(null)}
          onChanged={async (message) => {
            setNotice(message);
            setActionError(null);
            await invalidate();
          }}
          onError={(error) => setActionError(error)}
        />
      )}

      {receiveOpen && (
        <ReceiveBatchModal
          drugs={drugOptions.data?.items ?? []}
          pending={receiveBatch.isPending}
          error={receiveBatch.error}
          onClose={() => setReceiveOpen(false)}
          onSubmit={(body) => receiveBatch.mutate(body)}
        />
      )}

      {newDrugOpen && (
        <NewDrugModal
          pending={createDrug.isPending}
          error={createDrug.error}
          onClose={() => setNewDrugOpen(false)}
          onSubmit={(body) => createDrug.mutate(body)}
        />
      )}
    </div>
  );
}

type PrescriptionPanel =
  | { kind: "review" }
  | { kind: "reject" }
  | { kind: "dispense" }
  | { kind: "dispensing"; dispensingId: string }
  | null;

function PrescriptionModal({
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
  const [panel, setPanel] = useState<PrescriptionPanel>(null);

  const canReview = hasAnyPermission("PHR:REVIEW");
  const canDispense = hasAnyPermission("PHR:DISPENSE");

  const detail = useQuery({
    queryKey: ["phr-prescription", publicId],
    queryFn: () => pharmacyApi.prescription(publicId),
  });

  const refresh = async (message: string) => {
    await onChanged(message);
    await queryClient.invalidateQueries({ queryKey: ["phr-prescription", publicId] });
  };

  const review = useMutation({
    mutationFn: (body: { notes?: string; acknowledgeInteractions?: boolean }) =>
      pharmacyApi.review(publicId, {
        version: detail.data?.version ?? 0,
        notes: body.notes,
        acknowledgeInteractions: body.acknowledgeInteractions,
      }),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("phr.reviewed"));
    },
    onError,
  });
  const reject = useMutation({
    mutationFn: (reason: string) =>
      pharmacyApi.reject(publicId, { version: detail.data?.version ?? 0, reason }),
    onSuccess: () => {
      setPanel(null);
      void refresh(t("phr.rejected"));
    },
    onError,
  });
  const dispense = useMutation({
    mutationFn: (body: { items: { prescriptionItemPublicId: string; quantity: number }[]; notes?: string }) =>
      pharmacyApi.dispense(publicId, {
        version: detail.data?.version ?? 0,
        items: body.items,
        notes: body.notes,
      }),
    onSuccess: (created) => {
      setPanel({ kind: "dispensing", dispensingId: created.publicId });
      void refresh(t("phr.dispensedOk"));
    },
    onError,
  });

  const prescription = detail.data;
  const contraindicated =
    prescription?.interactions.some((row) => row.severity === "CONTRAINDICATED") ?? false;

  return (
    <Modal title={t("phr.prescription")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !prescription ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : panel?.kind === "review" ? (
        <ReviewForm
          contraindicated={contraindicated}
          pending={review.isPending}
          error={review.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => review.mutate(body)}
        />
      ) : panel?.kind === "reject" ? (
        <RejectForm
          pending={reject.isPending}
          error={reject.error}
          onBack={() => setPanel(null)}
          onSubmit={(reason) => reject.mutate(reason)}
        />
      ) : panel?.kind === "dispense" ? (
        <DispenseForm
          items={prescription.items}
          pending={dispense.isPending}
          error={dispense.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => dispense.mutate(body)}
        />
      ) : panel?.kind === "dispensing" ? (
        <DispensingPanel
          dispensingId={panel.dispensingId}
          onBack={() => setPanel(null)}
          onChanged={refresh}
          onError={onError}
        />
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("phr.patient")}</span>
              <strong>{prescription.patient.fullName}</strong>
              <span className="muted mono"> · {prescription.patient.medicalNumber}</span>
            </div>
            <div>
              <span className="muted">{t("common.status")}</span>
              <span className={`badge ${prescriptionBadge(prescription.status)}`}>
                {t(`phrStatus.${prescription.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("phr.issuedAt")}</span>
              <strong>{formatDateTime(prescription.issuedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("phr.prescriber")}</span>
              <strong>
                {personName(
                  locale,
                  prescription.prescribedBy.nameAr,
                  prescription.prescribedBy.nameEn,
                )}
              </strong>
            </div>
            <div>
              <span className="muted">{t("common.version")}</span>
              <strong>{prescription.version}</strong>
            </div>
          </div>

          {prescription.notes && (
            <p className="muted">
              {t("common.notes")}: {prescription.notes}
            </p>
          )}
          {prescription.rejectedReason && (
            <p className="field-error">
              {t("phr.rejectReason")}: {prescription.rejectedReason}
            </p>
          )}

          {prescription.allergies.length > 0 && (
            <div className="alert alert-danger">
              <strong>{t("phr.allergiesAlert")}</strong>
              <ul className="check-list">
                {prescription.allergies.map((allergy) => (
                  <li key={allergy.publicId}>
                    {allergy.substance} · {t(`severity.${allergy.severity}`)}
                    {allergy.reaction ? ` · ${allergy.reaction}` : ""}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {prescription.interactions.length > 0 && (
            <section>
              <h3>{t("phr.interactions")}</h3>
              <ul className="board-list">
                {prescription.interactions.map((interaction, index) => (
                  <li
                    className="board-item"
                    key={`${interaction.drug.code}-${interaction.interactingDrug.code}-${index}`}
                  >
                    <div className="board-item-head">
                      <span className={`badge ${interactionBadge(interaction.severity)}`}>
                        {t(`interactionSeverity.${interaction.severity}`)}
                      </span>
                      <strong>
                        {personName(
                          locale,
                          interaction.drug.nameAr,
                          interaction.drug.nameEn,
                        )}
                      </strong>
                      <span className="muted">×</span>
                      <strong>
                        {personName(
                          locale,
                          interaction.interactingDrug.nameAr,
                          interaction.interactingDrug.nameEn,
                        )}
                      </strong>
                    </div>
                    <p className="muted">
                      {personName(
                        locale,
                        interaction.descriptionAr,
                        interaction.descriptionEn,
                      )}
                    </p>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <div className="form-actions form-actions-start">
            {prescription.status === "ISSUED" && canReview && (
              <>
                <button
                  type="button"
                  className="button"
                  onClick={() => {
                    review.reset();
                    setPanel({ kind: "review" });
                  }}
                >
                  {t("phr.review")}
                </button>
                <button
                  type="button"
                  className="button button-ghost"
                  onClick={() => {
                    reject.reset();
                    setPanel({ kind: "reject" });
                  }}
                >
                  {t("phr.reject")}
                </button>
              </>
            )}
            {["ISSUED", "REVIEWED", "PARTIAL"].includes(prescription.status) &&
              canDispense && (
                <button
                  type="button"
                  className="button"
                  onClick={() => {
                    dispense.reset();
                    setPanel({ kind: "dispense" });
                  }}
                >
                  {t("phr.dispense")}
                </button>
              )}
          </div>

          <section>
            <h3>{t("phr.items")}</h3>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("phr.drug")}</th>
                    <th>{t("phr.quantity")}</th>
                    <th>{t("phr.dispensed")}</th>
                    <th>{t("phr.remaining")}</th>
                    <th>{t("phr.stock")}</th>
                  </tr>
                </thead>
                <tbody>
                  {prescription.items.map((item) => (
                    <tr key={item.publicId}>
                      <td>
                        <strong>
                          {personName(locale, item.drug.nameAr, item.drug.nameEn)}
                        </strong>
                        <span className="muted mono"> · {item.drug.code}</span>
                        <div className="muted">
                          {[item.dose, item.route, item.frequency]
                            .filter(Boolean)
                            .join(" · ")}
                          {item.durationDays ? ` · ${item.durationDays}` : ""}
                        </div>
                      </td>
                      <td className="mono">{item.quantity}</td>
                      <td className="mono">{item.dispensedQuantity}</td>
                      <td className="mono">{item.remaining}</td>
                      <td className="mono">
                        {item.stockAvailable === null || item.stockAvailable === undefined
                          ? "—"
                          : item.stockAvailable}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h3>{t("phr.dispensingHistory")}</h3>
            {prescription.dispensings.length === 0 ? (
              <p className="empty-inline">{t("phr.noDispensings")}</p>
            ) : (
              <ul className="board-list">
                {prescription.dispensings.map((dispensing) => (
                  <li className="board-item" key={dispensing.publicId}>
                    <div className="board-item-head">
                      <span className={`badge ${prescriptionBadge(dispensing.status)}`}>
                        {t(`dispensingStatus.${dispensing.status}`)}
                      </span>
                      <strong>{formatDateTime(dispensing.dispensedAt, locale)}</strong>
                      <span className="muted">
                        {personName(
                          locale,
                          dispensing.dispensedByAr,
                          dispensing.dispensedByEn,
                        )}
                      </span>
                    </div>
                    {dispensing.notes && <p className="muted">{dispensing.notes}</p>}
                    <div className="board-item-actions">
                      <button
                        type="button"
                        className="button button-ghost button-sm"
                        onClick={() =>
                          setPanel({ kind: "dispensing", dispensingId: dispensing.publicId })
                        }
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
    </Modal>
  );
}

function ReviewForm({
  contraindicated,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  contraindicated: boolean;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: { notes?: string; acknowledgeInteractions?: boolean }) => void;
}) {
  const { t, locale } = useI18n();
  const [notes, setNotes] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      notes: notes.trim() || undefined,
      acknowledgeInteractions: acknowledged,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("phr.review")}</h3>
      <label className="field">
        <span>{t("common.notes")}</span>
        <textarea
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          rows={3}
          maxLength={500}
        />
      </label>
      {contraindicated && (
        <label className="field-checkbox">
          <input
            type="checkbox"
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
          />
          <span>{t("phr.acknowledgeInteractions")}</span>
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
          disabled={pending || (contraindicated && !acknowledged)}
        >
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function RejectForm({
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
      <h3>{t("phr.reject")}</h3>
      <label className="field">
        <span>{t("phr.rejectReason")}</span>
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

function DispenseForm({
  items,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  items: PrescriptionItem[];
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    items: { prescriptionItemPublicId: string; quantity: number }[];
    notes?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [notes, setNotes] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const item of items) {
      initial[item.publicId] = item.remaining > 0 ? String(item.remaining) : "";
    }
    return initial;
  });

  const dispenseItems = items.filter((item) => item.remaining > 0);
  const lines = dispenseItems
    .map((item) => ({
      prescriptionItemPublicId: item.publicId,
      quantity: Number(quantities[item.publicId] ?? 0),
    }))
    .filter((line) => Number.isFinite(line.quantity) && line.quantity > 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (lines.length === 0) {
      return;
    }
    onSubmit({ items: lines, notes: notes.trim() || undefined });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("phr.dispenseTitle")}</h3>
      {dispenseItems.length === 0 ? (
        <p className="empty-inline">{t("phr.noStock")}</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("phr.drug")}</th>
                <th>{t("phr.remaining")}</th>
                <th>{t("phr.stock")}</th>
                <th>{t("phr.quantity")}</th>
              </tr>
            </thead>
            <tbody>
              {dispenseItems.map((item) => (
                <tr key={item.publicId}>
                  <td>
                    <strong>{personName(locale, item.drug.nameAr, item.drug.nameEn)}</strong>
                    <span className="muted mono"> · {item.drug.code}</span>
                  </td>
                  <td className="mono">{item.remaining}</td>
                  <td className="mono">{item.stockAvailable ?? "—"}</td>
                  <td>
                    <input
                      type="number"
                      min={0}
                      max={item.remaining}
                      step={1}
                      value={quantities[item.publicId] ?? ""}
                      onChange={(event) =>
                        setQuantities((current) => ({
                          ...current,
                          [item.publicId]: event.target.value,
                        }))
                      }
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <label className="field">
        <span>{t("common.notes")}</span>
        <textarea
          value={notes}
          onChange={(event) => setNotes(event.target.value)}
          rows={2}
          maxLength={1000}
        />
      </label>
      <p className="field-hint">{t("phr.partial")}</p>
      {error ? (
        <p className="field-error" role="alert">
          {describeApiError(error, locale, t("common.saveError"))}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={pending || lines.length === 0}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function DispensingPanel({
  dispensingId,
  onBack,
  onChanged,
  onError,
}: {
  dispensingId: string;
  onBack: () => void;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();
  const [returnOpen, setReturnOpen] = useState(false);

  const canReturn = hasAnyPermission("PHR:RETURN");

  const detail = useQuery({
    queryKey: ["phr-dispensing", dispensingId],
    queryFn: () => pharmacyApi.dispensing(dispensingId),
  });

  const returnItems = useMutation({
    mutationFn: (body: {
      reason: string;
      items: { dispensingItemPublicId: string; quantity: number }[];
    }) => pharmacyApi.returnItems(dispensingId, body),
    onSuccess: async () => {
      setReturnOpen(false);
      await onChanged(t("common.saved"));
      await queryClient.invalidateQueries({
        queryKey: ["phr-dispensing", dispensingId],
      });
    },
    onError,
  });

  const dispensing = detail.data;

  if (returnOpen && dispensing) {
    return (
      <ReturnForm
        dispensing={dispensing}
        pending={returnItems.isPending}
        error={returnItems.error}
        onBack={() => setReturnOpen(false)}
        onSubmit={(body) => returnItems.mutate(body)}
      />
    );
  }

  return (
    <div className="stack-form">
      <div className="form-actions form-actions-start">
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
      </div>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !dispensing ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <>
          <div className="detail-grid">
            <div>
              <span className="muted">{t("phr.patient")}</span>
              <strong>{dispensing.patient.fullName}</strong>
              <span className="muted mono"> · {dispensing.patient.medicalNumber}</span>
            </div>
            <div>
              <span className="muted">{t("common.status")}</span>
              <span className={`badge ${prescriptionBadge(dispensing.status)}`}>
                {t(`dispensingStatus.${dispensing.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("phr.dispensed")}</span>
              <strong>{formatDateTime(dispensing.dispensedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("phr.performedBy")}</span>
              <strong>
                {personName(
                  locale,
                  dispensing.dispensedBy.nameAr,
                  dispensing.dispensedBy.nameEn,
                )}
              </strong>
            </div>
          </div>

          {dispensing.notes && <p className="muted">{dispensing.notes}</p>}
          {dispensing.returnedQuantity !== undefined && (
            <p className="muted">
              {t("phr.return")}: {dispensing.returnedQuantity}
            </p>
          )}

          <div className="form-actions form-actions-start">
            {canReturn &&
              ["COMMITTED", "PARTIALLY_RETURNED"].includes(dispensing.status) && (
                <button
                  type="button"
                  className="button"
                  onClick={() => {
                    returnItems.reset();
                    setReturnOpen(true);
                  }}
                >
                  {t("phr.return")}
                </button>
              )}
          </div>

          <section>
            <h3>{t("phr.items")}</h3>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("phr.drug")}</th>
                    <th>{t("phr.batchNumber")}</th>
                    <th>{t("phr.expiry")}</th>
                    <th>{t("phr.quantity")}</th>
                    <th>{t("phr.return")}</th>
                  </tr>
                </thead>
                <tbody>
                  {dispensing.items.map((item) => (
                    <tr key={item.publicId}>
                      <td>
                        <strong>{personName(locale, item.drug.nameAr, item.drug.nameEn)}</strong>
                        <span className="muted mono"> · {item.drug.code}</span>
                      </td>
                      <td className="mono">{item.batchNumber}</td>
                      <td>{formatDate(item.expiryDate, locale)}</td>
                      <td className="mono">{item.quantity}</td>
                      <td className="mono">{item.returnedQuantity}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h3>{t("phr.returnTitle")}</h3>
            {dispensing.returns.length === 0 ? (
              <p className="empty-inline">{t("phr.empty")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("common.date")}</th>
                      <th>{t("phr.drug")}</th>
                      <th>{t("phr.quantity")}</th>
                      <th>{t("phr.returnReason")}</th>
                      <th>{t("phr.performedBy")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {dispensing.returns.map((row) => (
                      <tr key={row.publicId}>
                        <td>{formatDateTime(row.returnedAt, locale)}</td>
                        <td>
                          {personName(locale, row.drugAr, row.drugEn)}
                          <span className="muted mono"> · {row.drugCode}</span>
                        </td>
                        <td className="mono">{row.quantity}</td>
                        <td>{row.reason ?? "—"}</td>
                        <td>{personName(locale, row.returnedByAr, row.returnedByEn)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function ReturnForm({
  dispensing,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  dispensing: DispensingDetail;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    reason: string;
    items: { dispensingItemPublicId: string; quantity: number }[];
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [reason, setReason] = useState("");
  const [quantities, setQuantities] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const item of dispensing.items) {
      initial[item.publicId] = "";
    }
    return initial;
  });

  const lines = dispensing.items
    .map((item) => ({
      dispensingItemPublicId: item.publicId,
      quantity: Number(quantities[item.publicId] ?? 0),
    }))
    .filter((line) => Number.isFinite(line.quantity) && line.quantity > 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!reason.trim() || lines.length === 0) {
      return;
    }
    onSubmit({ reason: reason.trim(), items: lines });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("phr.returnTitle")}</h3>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>{t("phr.drug")}</th>
              <th>{t("phr.batchNumber")}</th>
              <th>{t("phr.quantity")}</th>
              <th>{t("phr.remaining")}</th>
              <th>{t("phr.return")}</th>
            </tr>
          </thead>
          <tbody>
            {dispensing.items.map((item) => {
              const returnable = item.quantity - item.returnedQuantity;
              return (
                <tr key={item.publicId}>
                  <td>
                    <strong>{personName(locale, item.drug.nameAr, item.drug.nameEn)}</strong>
                    <span className="muted mono"> · {item.drug.code}</span>
                  </td>
                  <td className="mono">{item.batchNumber}</td>
                  <td className="mono">{item.quantity}</td>
                  <td className="mono">{returnable}</td>
                  <td>
                    <input
                      type="number"
                      min={0}
                      max={returnable}
                      step={1}
                      value={quantities[item.publicId] ?? ""}
                      disabled={returnable <= 0}
                      onChange={(event) =>
                        setQuantities((current) => ({
                          ...current,
                          [item.publicId]: event.target.value,
                        }))
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
        <span>{t("phr.returnReason")}</span>
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
        <button
          type="submit"
          className="button"
          disabled={pending || !reason.trim() || lines.length === 0}
        >
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function ReceiveBatchModal({
  drugs,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  drugs: Drug[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    drugPublicId: string;
    batchNumber: string;
    expiryDate: string;
    quantity: number;
    unitCost?: number;
    supplier?: string;
    lotNumber?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [drugId, setDrugId] = useState("");
  const [batchNumber, setBatchNumber] = useState("");
  const [expiryDate, setExpiryDate] = useState("");
  const [quantity, setQuantity] = useState("");
  const [unitCost, setUnitCost] = useState("");
  const [supplier, setSupplier] = useState("");
  const [lotNumber, setLotNumber] = useState("");

  const valid =
    drugId !== "" &&
    batchNumber.trim() !== "" &&
    expiryDate !== "" &&
    Number(quantity) > 0;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) {
      return;
    }
    onSubmit({
      drugPublicId: drugId,
      batchNumber: batchNumber.trim(),
      expiryDate,
      quantity: Number(quantity),
      unitCost: unitCost.trim() === "" ? undefined : Number(unitCost),
      supplier: supplier.trim() || undefined,
      lotNumber: lotNumber.trim() || undefined,
    });
  }

  return (
    <Modal title={t("phr.receiveTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("phr.drug")}</span>
          <select value={drugId} onChange={(event) => setDrugId(event.target.value)} required>
            <option value="">{t("common.select")}</option>
            {drugs.map((drug) => (
              <option key={drug.publicId} value={drug.publicId}>
                {drug.code} · {drug.nameAr}
              </option>
            ))}
          </select>
        </label>
        <div className="form-grid">
          <label className="field">
            <span>{t("phr.batchNumber")}</span>
            <input
              type="text"
              value={batchNumber}
              onChange={(event) => setBatchNumber(event.target.value)}
              maxLength={60}
              required
            />
          </label>
          <label className="field">
            <span>{t("phr.expiry")}</span>
            <input
              type="date"
              value={expiryDate}
              min={todayInput()}
              onChange={(event) => setExpiryDate(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("phr.quantity")}</span>
            <input
              type="number"
              min={1}
              step={1}
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>{t("phr.unitCost")}</span>
            <input
              type="number"
              min={0}
              step="any"
              value={unitCost}
              onChange={(event) => setUnitCost(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("phr.supplier")}</span>
            <input
              type="text"
              value={supplier}
              onChange={(event) => setSupplier(event.target.value)}
              maxLength={150}
            />
          </label>
          <label className="field">
            <span>{t("phr.batch")}</span>
            <input
              type="text"
              value={lotNumber}
              onChange={(event) => setLotNumber(event.target.value)}
              maxLength={60}
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
          <button type="submit" className="button" disabled={!valid || pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function NewDrugModal({
  pending,
  error,
  onClose,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    code: string;
    nameAr: string;
    nameEn?: string;
    form: string;
    strength?: string;
    category?: string;
    reorderLevel?: number;
    unitPrice?: number;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [code, setCode] = useState("");
  const [nameAr, setNameAr] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [form, setForm] = useState(DRUG_FORMS[0]);
  const [strength, setStrength] = useState("");
  const [category, setCategory] = useState("");
  const [reorderLevel, setReorderLevel] = useState("");
  const [unitPrice, setUnitPrice] = useState("");

  const valid = code.trim() !== "" && nameAr.trim() !== "" && form.trim() !== "";

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) {
      return;
    }
    onSubmit({
      code: code.trim(),
      nameAr: nameAr.trim(),
      nameEn: nameEn.trim() || undefined,
      form,
      strength: strength.trim() || undefined,
      category: category.trim() || undefined,
      reorderLevel: reorderLevel.trim() === "" ? undefined : Number(reorderLevel),
      unitPrice: unitPrice.trim() === "" ? undefined : Number(unitPrice),
    });
  }

  return (
    <Modal title={t("phr.newDrugTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <div className="form-grid">
          <label className="field">
            <span>{t("phr.drugCode")}</span>
            <input
              type="text"
              value={code}
              onChange={(event) => setCode(event.target.value)}
              maxLength={30}
              required
            />
          </label>
          <label className="field">
            <span>{t("phr.drugNameAr")}</span>
            <input
              type="text"
              value={nameAr}
              onChange={(event) => setNameAr(event.target.value)}
              maxLength={150}
              required
            />
          </label>
          <label className="field">
            <span>{t("phr.drugNameEn")}</span>
            <input
              type="text"
              value={nameEn}
              onChange={(event) => setNameEn(event.target.value)}
              maxLength={150}
            />
          </label>
          <label className="field">
            <span>{t("phr.form")}</span>
            <input
              type="text"
              list="phr-drug-forms"
              value={form}
              onChange={(event) => setForm(event.target.value)}
              maxLength={40}
              required
            />
            <datalist id="phr-drug-forms">
              {DRUG_FORMS.map((value) => (
                <option key={value} value={value} />
              ))}
            </datalist>
          </label>
          <label className="field">
            <span>{t("phr.strength")}</span>
            <input
              type="text"
              value={strength}
              onChange={(event) => setStrength(event.target.value)}
              maxLength={40}
            />
          </label>
          <label className="field">
            <span>{t("phr.category")}</span>
            <input
              type="text"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              maxLength={40}
            />
          </label>
          <label className="field">
            <span>{t("phr.reorderLevel")}</span>
            <input
              type="number"
              min={0}
              step={1}
              value={reorderLevel}
              onChange={(event) => setReorderLevel(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("phr.unitPrice")}</span>
            <input
              type="number"
              min={0}
              step="any"
              value={unitPrice}
              onChange={(event) => setUnitPrice(event.target.value)}
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
          <button type="submit" className="button" disabled={!valid || pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}
