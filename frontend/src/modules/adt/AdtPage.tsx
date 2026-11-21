import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDateTime } from "../../shared/ui/format";
import { clinicalApi } from "../clinical/clinicalApi";
import type { PatientSummary } from "../patients/patientsApi";
import {
  ACTIVE_ADMISSION_STATUSES,
  ADMISSION_STATUSES,
  DISCHARGE_TYPES,
  adtApi,
  type AdmissionDetail,
  type BedRow,
  type WardRow,
} from "./adtApi";

function personName(
  locale: Locale,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return locale === "ar" ? ar || en || "—" : en || ar || "—";
}

function statusBadgeClass(status: string): string {
  switch (status) {
    case "ADMITTED":
    case "APPROVED":
      return "badge-success";
    case "REQUESTED":
    case "DISCHARGE_PENDING":
      return "badge-warn";
    case "REJECTED":
      return "badge-danger";
    default:
      return "badge-muted";
  }
}

function availableBeds(wards: { nameAr: string; nameEn: string; beds: BedRow[] }[]): {
  bed: BedRow;
  wardAr: string;
  wardEn: string;
}[] {
  const rows: { bed: BedRow; wardAr: string; wardEn: string }[] = [];
  for (const ward of wards) {
    for (const bed of ward.beds) {
      if (bed.status === "AVAILABLE") {
        rows.push({ bed, wardAr: ward.nameAr, wardEn: ward.nameEn });
      }
    }
  }
  return rows;
}

export function AdtPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<"beds" | "admissions">("beds");
  const [status, setStatus] = useState("");
  const [wardFilter, setWardFilter] = useState("");
  const [term, setTerm] = useState("");
  const [page, setPage] = useState(0);
  const [requestOpen, setRequestOpen] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const canApprove = hasAnyPermission("ADT:APPROVE");
  const canAssign = hasAnyPermission("ADT:ASSIGN", "ADT:WRITE");
  const canWrite = hasAnyPermission("ADT:WRITE");
  const canRequest = hasAnyPermission("ADT:REQUEST", "ADT:WRITE");
  const canHousekeeping = hasAnyPermission("ADT:HOUSEKEEPING", "ADT:BED_STATUS");

  const board = useQuery({
    queryKey: ["bed-board"],
    queryFn: () => adtApi.bedBoard(),
    refetchInterval: 30_000,
  });
  const wards = useQuery({
    queryKey: ["wards"],
    queryFn: () => adtApi.wards(),
    staleTime: 300_000,
  });
  const admissions = useQuery({
    queryKey: ["admissions", status, wardFilter, term, page],
    queryFn: () =>
      adtApi.search({
        status: status || undefined,
        ward: wardFilter || undefined,
        q: term.trim() || undefined,
        page,
        size: 20,
      }),
  });

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["bed-board"] }),
      queryClient.invalidateQueries({ queryKey: ["admissions"] }),
      queryClient.invalidateQueries({ queryKey: ["admission"] }),
    ]);
  };

  const bedStatus = useMutation({
    mutationFn: (payload: { bed: BedRow; status: string }) =>
      adtApi.bedStatus(payload.bed.publicId, {
        status: payload.status,
        version: payload.bed.version,
      }),
    onSuccess: async () => {
      setActionError(null);
      setNotice(t("common.saved"));
      await invalidate();
    },
    onError: (error) => setActionError(error),
  });

  const request = useMutation({
    mutationFn: (body: {
      patientPublicId: string;
      encounterPublicId: string;
      reason: string;
      wardPublicId?: string;
    }) => adtApi.request(body),
    onSuccess: async () => {
      setRequestOpen(false);
      setNotice(t("adt.requested"));
      setTab("admissions");
      setStatus("REQUESTED");
      await invalidate();
    },
  });

  const beds = availableBeds(board.data?.wards ?? []);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("adt.title")}</h1>
          <p className="muted">{t("adt.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              void board.refetch();
              void admissions.refetch();
            }}
          >
            {t("common.refresh")}
          </button>
          {canRequest && (
            <button
              type="button"
              className="button"
              onClick={() => {
                request.reset();
                setRequestOpen(true);
              }}
            >
              {t("adt.newRequest")}
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
          className={`tab${tab === "beds" ? " is-active" : ""}`}
          onClick={() => setTab("beds")}
        >
          {t("adt.tab.beds")}
        </button>
        <button
          type="button"
          className={`tab${tab === "admissions" ? " is-active" : ""}`}
          onClick={() => setTab("admissions")}
        >
          {t("adt.tab.admissions")}
        </button>
      </div>

      {tab === "beds" && (
        <div className="tab-content">
          <section className="kpi-grid">
            {[
              ["AVAILABLE", "adt.bedsAvailable"],
              ["RESERVED", "adt.bedsReserved"],
              ["OCCUPIED", "adt.bedsOccupied"],
              ["CLEANING", "adt.bedsCleaning"],
              ["MAINTENANCE", "adt.bedsMaintenance"],
            ].map(([key, label]) => (
              <div className="kpi-card" key={key}>
                <span className="kpi-label">{t(label)}</span>
                <strong className="kpi-value">
                  {board.data?.counts[key.toLowerCase()] ?? 0}
                </strong>
              </div>
            ))}
          </section>

          {board.isPending ? (
            <p className="muted">{t("common.loading")}</p>
          ) : board.isError ? (
            <div className="alert alert-danger">{t("common.loadError")}</div>
          ) : board.data && board.data.wards.length === 0 ? (
            <p className="empty-inline">{t("adt.noBeds")}</p>
          ) : (
            board.data?.wards.map((ward) => (
              <section className="card" key={ward.publicId}>
                <header className="card-header">
                  <div>
                    <h2>{personName(locale, ward.nameAr, ward.nameEn)}</h2>
                    <span className="muted">
                      {ward.code}
                      {ward.genderPolicy ? ` · ${ward.genderPolicy}` : ""}
                    </span>
                  </div>
                  <span className="badge badge-muted">
                    {ward.beds.filter((bed) => bed.status === "OCCUPIED").length} /{" "}
                    {ward.beds.length}
                  </span>
                </header>
                <div className="bed-grid">
                  {ward.beds.map((bed) => {
                    const transitions: { label: string; status: string }[] = [];
                    if (canHousekeeping) {
                      if (bed.status === "OCCUPIED") {
                        transitions.push({
                          label: t("adt.markCleaning"),
                          status: "CLEANING",
                        });
                      }
                      if (bed.status === "CLEANING" || bed.status === "MAINTENANCE") {
                        transitions.push({
                          label: t("adt.markAvailable"),
                          status: "AVAILABLE",
                        });
                      }
                      if (bed.status === "CLEANING" || bed.status === "AVAILABLE") {
                        transitions.push({
                          label: t("adt.markMaintenance"),
                          status: "MAINTENANCE",
                        });
                      }
                      if (bed.status === "RESERVED") {
                        transitions.push({
                          label: t("adt.markAvailable"),
                          status: "AVAILABLE",
                        });
                      }
                    }
                    return (
                      <article
                        className="bed-tile"
                        data-status={bed.status}
                        key={bed.publicId}
                      >
                        <div className="bed-tile-head">
                          <strong className="mono">{bed.code}</strong>
                          <span className={`badge ${bedBadgeClass(bed.status)}`}>
                            {t(`bedStatus.${bed.status}`)}
                          </span>
                        </div>
                        <span className="muted">{t(`bedType.${bed.bedType}`)}</span>
                        {bed.patientName ? (
                          <button
                            type="button"
                            className="table-link bed-tile-patient"
                            onClick={() => {
                              if (bed.admissionPublicId) {
                                setDetailId(bed.admissionPublicId);
                              }
                            }}
                          >
                            {bed.patientName}
                            {bed.medicalNumber ? ` · ${bed.medicalNumber}` : ""}
                          </button>
                        ) : (
                          <span className="muted">—</span>
                        )}
                        {transitions.length > 0 && (
                          <div className="bed-tile-actions">
                            {transitions.map((transition) => (
                              <button
                                key={transition.status}
                                type="button"
                                className="button button-ghost button-sm"
                                disabled={bedStatus.isPending}
                                onClick={() => {
                                  setActionError(null);
                                  bedStatus.mutate({ bed, status: transition.status });
                                }}
                              >
                                {transition.label}
                              </button>
                            ))}
                          </div>
                        )}
                      </article>
                    );
                  })}
                </div>
              </section>
            ))
          )}
        </div>
      )}

      {tab === "admissions" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("adt.status")}</span>
                <select
                  value={status}
                  onChange={(event) => {
                    setStatus(event.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {ADMISSION_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {t(`admissionStatus.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("adt.ward")}</span>
                <select
                  value={wardFilter}
                  onChange={(event) => {
                    setWardFilter(event.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {wards.data?.map((ward) => (
                    <option key={ward.publicId} value={ward.publicId}>
                      {personName(locale, ward.nameAr, ward.nameEn)}
                    </option>
                  ))}
                </select>
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

            {admissions.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : admissions.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : admissions.data && admissions.data.items.length === 0 ? (
              <p className="empty-inline">{t("adt.empty")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("adt.patient")}</th>
                        <th>{t("adt.status")}</th>
                        <th>{t("adt.reason")}</th>
                        <th>{t("adt.requestedAt")}</th>
                        <th>{t("adt.bed")}</th>
                        <th>{t("adt.los")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {admissions.data?.items.map((row) => (
                        <tr key={row.publicId}>
                          <td>
                            {row.patient.fullName}
                            <span className="muted mono">
                              {" "}
                              · {row.patient.medicalNumber}
                            </span>
                          </td>
                          <td>
                            <span className={`badge ${statusBadgeClass(row.status)}`}>
                              {t(`admissionStatus.${row.status}`)}
                            </span>
                          </td>
                          <td className="cell-wrap">{row.reason}</td>
                          <td>{formatDateTime(row.requestedAt, locale)}</td>
                          <td>
                            {row.bed
                              ? `${row.bed.code} · ${personName(
                                  locale,
                                  row.bed.wardAr,
                                  row.bed.wardEn,
                                )}`
                              : row.requestedWard
                                ? personName(
                                    locale,
                                    row.requestedWard.nameAr,
                                    row.requestedWard.nameEn,
                                  )
                                : "—"}
                          </td>
                          <td>
                            {row.los.days > 0
                              ? `${row.los.days} ${t("adt.days")}`
                              : `${row.los.hours} ${t("adt.hours")}`}
                          </td>
                          <td className="cell-actions">
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => setDetailId(row.publicId)}
                            >
                              {t("common.view")}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {admissions.data && (
                  <Pagination
                    page={admissions.data.page}
                    totalPages={admissions.data.totalPages}
                    total={admissions.data.total}
                    onChange={setPage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {requestOpen && (
        <RequestModal
          wards={wards.data ?? []}
          pending={request.isPending}
          error={request.error}
          onClose={() => setRequestOpen(false)}
          onSubmit={(body) => request.mutate(body)}
        />
      )}

      {detailId && (
        <AdmissionDetailModal
          publicId={detailId}
          beds={beds}
          canApprove={canApprove}
          canAssign={canAssign}
          canWrite={canWrite}
          onClose={() => setDetailId(null)}
          onChanged={async (message) => {
            setNotice(message);
            setActionError(null);
            await invalidate();
          }}
          onError={(error) => setActionError(error)}
        />
      )}
    </div>
  );
}

function bedBadgeClass(status: string): string {
  switch (status) {
    case "AVAILABLE":
      return "badge-success";
    case "OCCUPIED":
      return "badge-info";
    case "CLEANING":
      return "badge-warn";
    case "MAINTENANCE":
      return "badge-danger";
    default:
      return "badge-muted";
  }
}

function RequestModal({
  wards,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  wards: WardRow[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    patientPublicId: string;
    encounterPublicId: string;
    reason: string;
    wardPublicId?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [encounterId, setEncounterId] = useState("");
  const [reason, setReason] = useState("");
  const [wardId, setWardId] = useState("");

  const encounters = useQuery({
    queryKey: ["patient-open-encounters", patient?.publicId],
    queryFn: () =>
      clinicalApi.search({
        patient: patient?.publicId,
        status: "OPEN",
        size: 10,
      }),
    enabled: Boolean(patient),
  });

  const options = encounters.data?.items ?? [];

  useEffect(() => {
    if (encounters.data && encounters.data.items.length === 1) {
      setEncounterId(encounters.data.items[0].publicId);
    }
  }, [encounters.data]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!patient || !encounterId) {
      return;
    }
    onSubmit({
      patientPublicId: patient.publicId,
      encounterPublicId: encounterId,
      reason: reason.trim(),
      wardPublicId: wardId || undefined,
    });
  }

  return (
    <Modal title={t("adt.newRequestTitle")} onClose={onClose}>
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
            {options.map((encounter) => (
              <option key={encounter.publicId} value={encounter.publicId}>
                {encounter.type} · {formatDateTime(encounter.startedAt, locale)}
              </option>
            ))}
          </select>
          {patient && !encounters.isPending && options.length === 0 && (
            <span className="field-hint">{t("adt.noOpenEncounter")}</span>
          )}
        </label>
        <label className="field">
          <span>{t("adt.reason")}</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={3}
            maxLength={1000}
            required
          />
        </label>
        <label className="field">
          <span>{t("adt.requestedWard")}</span>
          <select value={wardId} onChange={(event) => setWardId(event.target.value)}>
            <option value="">{t("common.none")}</option>
            {wards.map((ward) => (
              <option key={ward.publicId} value={ward.publicId}>
                {personName(locale, ward.nameAr, ward.nameEn)}
              </option>
            ))}
          </select>
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
          <button
            type="submit"
            className="button"
            disabled={!patient || !encounterId || !reason.trim() || pending}
          >
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

type DetailAction = "reject" | "assign" | "transfer" | "discharge" | "summary";

function AdmissionDetailModal({
  publicId,
  beds,
  canApprove,
  canAssign,
  canWrite,
  onClose,
  onChanged,
  onError,
}: {
  publicId: string;
  beds: { bed: BedRow; wardAr: string; wardEn: string }[];
  canApprove: boolean;
  canAssign: boolean;
  canWrite: boolean;
  onClose: () => void;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [action, setAction] = useState<DetailAction | null>(null);

  const detail = useQuery({
    queryKey: ["admission", publicId],
    queryFn: () => adtApi.get(publicId),
  });

  const finish = async (message: string) => {
    setAction(null);
    await onChanged(message);
    await queryClient.invalidateQueries({ queryKey: ["admission", publicId] });
  };

  const approve = useMutation({
    mutationFn: (version: number) => adtApi.approve(publicId, { version }),
    onSuccess: () => void finish(t("adt.approved")),
    onError,
  });
  const reject = useMutation({
    mutationFn: (body: { version: number; reason: string }) =>
      adtApi.reject(publicId, body),
    onSuccess: () => void finish(t("adt.rejected")),
    onError,
  });
  const assign = useMutation({
    mutationFn: (body: { bedPublicId: string; version: number; exceptionReason?: string }) =>
      adtApi.assignBed(publicId, body),
    onSuccess: () => void finish(t("adt.assigned")),
    onError,
  });
  const transfer = useMutation({
    mutationFn: (body: {
      toBedPublicId: string;
      reason: string;
      version: number;
      exceptionReason?: string;
    }) => adtApi.transfer(publicId, body),
    onSuccess: () => void finish(t("adt.transferred")),
    onError,
  });
  const discharge = useMutation({
    mutationFn: (body: { dischargeType: string; notes?: string; version: number }) =>
      adtApi.discharge(publicId, body),
    onSuccess: () => void finish(t("adt.discharged")),
    onError,
  });
  const summary = useMutation({
    mutationFn: (body: {
      diagnosesText: string;
      instructionsText: string;
      medicationsText?: string;
      followUpText?: string;
    }) => adtApi.saveSummary(publicId, body),
    onSuccess: () => void finish(t("adt.summarySaved")),
    onError,
  });

  const admission = detail.data?.admission;
  const active = admission ? ACTIVE_ADMISSION_STATUSES.includes(admission.status) : false;

  return (
    <Modal title={t("adt.tab.admissions")} onClose={onClose}>
      {detail.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : detail.isError || !detail.data || !admission ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : action === "summary" ? (
        <SummaryForm
          detail={detail.data}
          pending={summary.isPending}
          error={summary.error}
          onBack={() => setAction(null)}
          onSubmit={(body) => summary.mutate(body)}
        />
      ) : action === "assign" || action === "transfer" ? (
        <BedForm
          mode={action}
          version={admission.version}
          beds={beds}
          pending={action === "assign" ? assign.isPending : transfer.isPending}
          error={action === "assign" ? assign.error : transfer.error}
          onBack={() => setAction(null)}
          onSubmit={(body) =>
            action === "assign"
              ? assign.mutate({
                  bedPublicId: body.bedPublicId,
                  version: admission.version,
                  exceptionReason: body.exceptionReason,
                })
              : transfer.mutate({
                  toBedPublicId: body.bedPublicId,
                  reason: body.reason,
                  version: admission.version,
                  exceptionReason: body.exceptionReason,
                })
          }
        />
      ) : action === "reject" ? (
        <ReasonForm
          title={t("adt.reject")}
          label={t("adt.rejectionReason")}
          pending={reject.isPending}
          error={reject.error}
          onBack={() => setAction(null)}
          onSubmit={(reason) => reject.mutate({ version: admission.version, reason })}
        />
      ) : action === "discharge" ? (
        <DischargeForm
          pending={discharge.isPending}
          error={discharge.error}
          onBack={() => setAction(null)}
          onSubmit={(body) =>
            discharge.mutate({
              dischargeType: body.dischargeType,
              notes: body.notes,
              version: admission.version,
            })
          }
        />
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("adt.patient")}</span>
              <strong>{admission.patient.fullName}</strong>
              <span className="muted mono"> · {admission.patient.medicalNumber}</span>
            </div>
            <div>
              <span className="muted">{t("adt.status")}</span>
              <span className={`badge ${statusBadgeClass(admission.status)}`}>
                {t(`admissionStatus.${admission.status}`)}
              </span>
            </div>
            <div>
              <span className="muted">{t("adt.reason")}</span>
              <strong>{admission.reason}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.requestedAt")}</span>
              <strong>{formatDateTime(admission.requestedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.admittedAt")}</span>
              <strong>{formatDateTime(admission.admittedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.dischargedAt")}</span>
              <strong>{formatDateTime(admission.dischargedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.ward")}</span>
              <strong>
                {admission.bed
                  ? personName(locale, admission.bed.wardAr, admission.bed.wardEn)
                  : admission.requestedWard
                    ? personName(
                        locale,
                        admission.requestedWard.nameAr,
                        admission.requestedWard.nameEn,
                      )
                    : "—"}
              </strong>
            </div>
            <div>
              <span className="muted">{t("adt.bed")}</span>
              <strong>{admission.bed ? admission.bed.code : "—"}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.los")}</span>
              <strong>
                {admission.los.days} {t("adt.days")} · {admission.los.hours}{" "}
                {t("adt.hours")}
              </strong>
            </div>
            <div>
              <span className="muted">{t("adt.billingCleared")}</span>
              <strong>
                {admission.billingCleared
                  ? t("common.yes")
                  : t("adt.billingNotCleared")}
              </strong>
            </div>
          </div>

          {admission.rejectionReason && (
            <p className="field-error">
              {t("adt.rejectionReason")}: {admission.rejectionReason}
            </p>
          )}

          <div className="form-actions form-actions-start">
            {admission.status === "REQUESTED" && canApprove && (
              <button
                type="button"
                className="button"
                disabled={approve.isPending}
                onClick={() => approve.mutate(admission.version)}
              >
                {t("adt.approve")}
              </button>
            )}
            {admission.status === "REQUESTED" && canApprove && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setAction("reject")}
              >
                {t("adt.reject")}
              </button>
            )}
            {admission.status === "APPROVED" && canAssign && (
              <button type="button" className="button" onClick={() => setAction("assign")}>
                {t("adt.assignBed")}
              </button>
            )}
            {active && canWrite && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setAction("transfer")}
              >
                {t("adt.transfer")}
              </button>
            )}
            {(active || admission.status === "DISCHARGE_PENDING") && canWrite && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setAction("discharge")}
              >
                {t("adt.discharge")}
              </button>
            )}
            <button
              type="button"
              className="button button-ghost"
              onClick={() => setAction("summary")}
            >
              {t("adt.summary")}
            </button>
            {detail.data.hasSummary && (
              <span className="badge badge-success">{t("adt.hasSummary")}</span>
            )}
          </div>

          <section>
            <h3>{t("adt.bedHistory")}</h3>
            {detail.data.bedHistory.length === 0 ? (
              <p className="empty-inline">{t("adt.noBedHistory")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("adt.bed")}</th>
                      <th>{t("adt.ward")}</th>
                      <th>{t("adt.since")}</th>
                      <th>{t("common.notes")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.data.bedHistory.map((row) => (
                      <tr key={row.publicId}>
                        <td className="mono">{row.bedCode}</td>
                        <td>{personName(locale, row.wardAr, row.wardEn)}</td>
                        <td>{formatDateTime(row.assignedAt, locale)}</td>
                        <td>
                          {row.endedAt
                            ? `${formatDateTime(row.endedAt, locale)}${
                                row.endReason ? ` · ${row.endReason}` : ""
                              }`
                            : "—"}
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
    </Modal>
  );
}

function BedForm({
  mode,
  version,
  beds,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  mode: "assign" | "transfer";
  version: number;
  beds: { bed: BedRow; wardAr: string; wardEn: string }[];
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    bedPublicId: string;
    reason: string;
    exceptionReason?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [bedId, setBedId] = useState("");
  const [reason, setReason] = useState("");
  const [exceptionReason, setExceptionReason] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!bedId) {
      return;
    }
    onSubmit({
      bedPublicId: bedId,
      reason: reason.trim(),
      exceptionReason: exceptionReason.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <p className="muted">{t("common.version")}: {version}</p>
      <label className="field">
        <span>{t("adt.bed")}</span>
        <select value={bedId} onChange={(event) => setBedId(event.target.value)} required>
          <option value="">{t("common.select")}</option>
          {beds.map((row) => (
            <option key={row.bed.publicId} value={row.bed.publicId}>
              {row.bed.code} · {personName(locale, row.wardAr, row.wardEn)} ·{" "}
              {t(`bedType.${row.bed.bedType}`)}
            </option>
          ))}
        </select>
        {beds.length === 0 && (
          <span className="field-hint">{t("adt.bedRequired")}</span>
        )}
      </label>
      {mode === "transfer" && (
        <label className="field">
          <span>{t("adt.reason")}</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={2}
            maxLength={500}
            required
          />
        </label>
      )}
      <label className="field">
        <span>{t("adt.exceptionReason")}</span>
        <input
          type="text"
          value={exceptionReason}
          onChange={(event) => setExceptionReason(event.target.value)}
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
        <button
          type="submit"
          className="button"
          disabled={!bedId || (mode === "transfer" && !reason.trim()) || pending}
        >
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function ReasonForm({
  title,
  label,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  title: string;
  label: string;
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
      <h3>{title}</h3>
      <label className="field">
        <span>{label}</span>
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

function DischargeForm({
  pending,
  error,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: { dischargeType: string; notes?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [dischargeType, setDischargeType] = useState("HOME");
  const [notes, setNotes] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({ dischargeType, notes: notes.trim() || undefined });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("adt.dischargeTitle")}</h3>
      <label className="field">
        <span>{t("adt.dischargeType")}</span>
        <select
          value={dischargeType}
          onChange={(event) => setDischargeType(event.target.value)}
        >
          {DISCHARGE_TYPES.map((value) => (
            <option key={value} value={value}>
              {t(`dischargeType.${value}`)}
            </option>
          ))}
        </select>
      </label>
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

function SummaryForm({
  detail,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  detail: AdmissionDetail;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    diagnosesText: string;
    instructionsText: string;
    medicationsText?: string;
    followUpText?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const summary = useQuery({
    queryKey: ["admission-summary", detail.admission.publicId],
    queryFn: () => adtApi.summary(detail.admission.publicId),
  });

  const [diagnoses, setDiagnoses] = useState("");
  const [medications, setMedications] = useState("");
  const [instructions, setInstructions] = useState("");
  const [followUp, setFollowUp] = useState("");
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!summary.data || loaded) {
      return;
    }
    const stored = summary.data.stored;
    setDiagnoses(
      stored?.diagnosesText ??
        summary.data.diagnoses
          .map((row) => row.description)
          .filter(Boolean)
          .join("\n"),
    );
    setMedications(
      stored?.medicationsText ??
        summary.data.medications
          .map(
            (row) =>
              `${row.drugAr} · ${row.dose}${
                row.frequency ? ` · ${row.frequency}` : ""
              }`,
          )
          .join("\n"),
    );
    setInstructions(stored?.instructionsText ?? summary.data.instructions ?? "");
    setFollowUp(stored?.followUpText ?? "");
    setLoaded(true);
  }, [summary.data, loaded]);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      diagnosesText: diagnoses.trim(),
      instructionsText: instructions.trim(),
      medicationsText: medications.trim() || undefined,
      followUpText: followUp.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("adt.summaryTitle")}</h3>
      {summary.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : summary.isError ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <>
          <label className="field">
            <span>{t("adt.diagnosesText")}</span>
            <textarea
              value={diagnoses}
              onChange={(event) => setDiagnoses(event.target.value)}
              rows={4}
              maxLength={4000}
              required
            />
          </label>
          <label className="field">
            <span>{t("adt.medicationsText")}</span>
            <textarea
              value={medications}
              onChange={(event) => setMedications(event.target.value)}
              rows={3}
              maxLength={2000}
            />
          </label>
          <label className="field">
            <span>{t("adt.instructionsText")}</span>
            <textarea
              value={instructions}
              onChange={(event) => setInstructions(event.target.value)}
              rows={4}
              maxLength={4000}
              required
            />
          </label>
          <label className="field">
            <span>{t("adt.followUpText")}</span>
            <textarea
              value={followUp}
              onChange={(event) => setFollowUp(event.target.value)}
              rows={2}
              maxLength={1000}
            />
          </label>
        </>
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
          disabled={pending || !diagnoses.trim() || !instructions.trim()}
        >
          {pending ? t("common.saving") : t("adt.saveSummary")}
        </button>
      </div>
    </form>
  );
}
