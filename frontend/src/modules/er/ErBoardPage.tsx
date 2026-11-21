import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDateTime } from "../../shared/ui/format";
import type { PatientSummary } from "../patients/patientsApi";
import {
  ER_ARRIVAL_MODES,
  ER_DISPOSITIONS,
  erApi,
  type ErVisit,
  type VitalsInput,
} from "./erApi";

function levelLabel(
  visit: ErVisit,
  locale: "ar" | "en",
): string {
  if (!visit.triageLevel) {
    return "—";
  }
  return locale === "ar"
    ? visit.triageLevel.nameAr
    : visit.triageLevel.nameEn || visit.triageLevel.nameAr;
}

function visitName(visit: ErVisit): string {
  return visit.patient.fullName;
}

export function ErBoardPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission } = useAuth();
  const queryClient = useQueryClient();

  const board = useQuery({
    queryKey: ["er-board"],
    queryFn: () => erApi.board(),
    refetchInterval: 20_000,
  });
  const levels = useQuery({
    queryKey: ["er-triage-levels"],
    queryFn: () => erApi.triageLevels(),
    staleTime: 300_000,
  });

  const [registerOpen, setRegisterOpen] = useState(false);
  const [triageVisit, setTriageVisit] = useState<ErVisit | null>(null);
  const [disposeVisit, setDisposeVisit] = useState<ErVisit | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const canCreate = hasAnyPermission("ER:CREATE", "ER:WRITE");
  const canTriage = hasAnyPermission("ER:TRIAGE");
  const canWrite = hasAnyPermission("ER:WRITE");

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["er-board"] });

  const register = useMutation({
    mutationFn: (body: {
      patientPublicId: string;
      arrivalMode?: string;
      chiefComplaint?: string;
    }) => erApi.register(body),
    onSuccess: async () => {
      setRegisterOpen(false);
      setNotice(t("er.registered"));
      await invalidate();
    },
  });

  const triage = useMutation({
    mutationFn: (payload: {
      publicId: string;
      body: {
        triageLevelCode: string;
        chiefComplaint?: string;
        version: number;
        vitals?: VitalsInput;
      };
    }) => erApi.triage(payload.publicId, payload.body),
    onSuccess: async () => {
      setTriageVisit(null);
      setNotice(t("er.triaged"));
      await invalidate();
    },
  });

  const assess = useMutation({
    mutationFn: (visit: ErVisit) => erApi.assess(visit.publicId, visit.version),
    onSuccess: async () => {
      setNotice(t("er.treatmentStarted"));
      await invalidate();
    },
  });

  const dispose = useMutation({
    mutationFn: (payload: {
      publicId: string;
      body: {
        disposition: string;
        notes?: string;
        reason?: string;
        version: number;
      };
    }) => erApi.disposition(payload.publicId, payload.body),
    onSuccess: async () => {
      setDisposeVisit(null);
      setNotice(t("er.disposed"));
      await invalidate();
    },
  });

  const errorMessage =
    register.error ?? triage.error ?? assess.error ?? dispose.error;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("er.title")}</h1>
          <p className="page-subtitle">{t("er.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => void board.refetch()}
          >
            {t("common.refresh")}
          </button>
          {canCreate && (
            <button
              type="button"
              className="button"
              onClick={() => {
                register.reset();
                setRegisterOpen(true);
              }}
            >
              {t("er.register")}
            </button>
          )}
        </div>
      </header>

      {notice && (
        <div className="alert alert-success" role="status">
          {notice}
        </div>
      )}
      {errorMessage && !registerOpen && !triageVisit && !disposeVisit && (
        <div className="alert alert-danger" role="alert">
          {describeApiError(errorMessage, locale, t("common.loadError"))}
        </div>
      )}

      <section className="kpi-grid">
        <div className="kpi-card">
          <span className="kpi-label">{t("er.waiting")}</span>
          <strong className="kpi-value">{board.data?.counts.waiting ?? 0}</strong>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">{t("er.inTreatment")}</span>
          <strong className="kpi-value">{board.data?.counts.inTreatment ?? 0}</strong>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">{t("er.slaBreach")}</span>
          <strong className="kpi-value">{board.data?.counts.slaBreaches ?? 0}</strong>
        </div>
        <div className="kpi-card">
          <span className="kpi-label">{t("er.recent")}</span>
          <strong className="kpi-value">{board.data?.counts.recent ?? 0}</strong>
        </div>
      </section>

      {board.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : board.isError ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : (
        <>
          <div className="split-grid">
            <section className="card">
              <header className="card-header">
                <h2>{t("er.waiting")}</h2>
                <span className="badge badge-warn">
                  {board.data?.waiting.length ?? 0}
                </span>
              </header>
              {board.data && board.data.waiting.length === 0 ? (
                <p className="empty-inline">{t("er.emptyWaiting")}</p>
              ) : (
                <ul className="board-list">
                  {board.data?.waiting.map((visit) => (
                    <li
                      key={visit.publicId}
                      className={`board-item${visit.slaBreach ? " is-critical" : ""}`}
                    >
                      <div className="board-item-head">
                        <strong>{visitName(visit)}</strong>
                        <span className="muted mono">{visit.patient.medicalNumber}</span>
                        <span
                          className="badge badge-info"
                          title={levelLabel(visit, locale)}
                        >
                          {visit.triageLevel ? levelLabel(visit, locale) : t("erStatus.ARRIVED")}
                        </span>
                        {visit.slaBreach && (
                          <span className="badge badge-danger">{t("er.slaBreach")}</span>
                        )}
                      </div>
                      <p className="muted">
                        {visit.chiefComplaint ?? "—"} · {visit.waitMinutes}{" "}
                        {t("er.waitMinutes")}
                        {visit.maxWaitMinutes !== null
                          ? ` · ${t("er.maxWait")}: ${visit.maxWaitMinutes}`
                          : ""}
                      </p>
                      <div className="board-item-actions">
                        {visit.status === "ARRIVED" && canTriage && (
                          <button
                            type="button"
                            className="button button-sm"
                            onClick={() => {
                              triage.reset();
                              setTriageVisit(visit);
                            }}
                          >
                            {t("er.triage")}
                          </button>
                        )}
                        {visit.status === "TRIAGED" && canWrite && (
                          <button
                            type="button"
                            className="button button-sm"
                            onClick={() => assess.mutate(visit)}
                            disabled={assess.isPending}
                          >
                            {t("er.startTreatment")}
                          </button>
                        )}
                        {canWrite && (
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => {
                              dispose.reset();
                              setDisposeVisit(visit);
                            }}
                          >
                            {t("er.disposition")}
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="card">
              <header className="card-header">
                <h2>{t("er.inTreatment")}</h2>
                <span className="badge badge-success">
                  {board.data?.inTreatment.length ?? 0}
                </span>
              </header>
              {board.data && board.data.inTreatment.length === 0 ? (
                <p className="empty-inline">{t("er.emptyTreatment")}</p>
              ) : (
                <ul className="board-list">
                  {board.data?.inTreatment.map((visit) => (
                    <li key={visit.publicId} className="board-item">
                      <div className="board-item-head">
                        <strong>{visitName(visit)}</strong>
                        <span className="muted mono">{visit.patient.medicalNumber}</span>
                        <span className="badge badge-info">
                          {levelLabel(visit, locale)}
                        </span>
                      </div>
                      <p className="muted">
                        {visit.chiefComplaint ?? "—"}
                        {visit.assessmentMinutes !== null
                          ? ` · ${visit.assessmentMinutes} ${t("er.assessmentMinutes")}`
                          : ""}
                      </p>
                      {canWrite && (
                        <div className="board-item-actions">
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => {
                              dispose.reset();
                              setDisposeVisit(visit);
                            }}
                          >
                            {t("er.disposition")}
                          </button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>

          <section className="card">
            <header className="card-header">
              <h2>{t("er.recent")}</h2>
            </header>
            {board.data && board.data.recent.length === 0 ? (
              <p className="empty-inline">{t("er.emptyRecent")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("er.patient")}</th>
                      <th>{t("common.type")}</th>
                      <th>{t("adt.status")}</th>
                      <th>{t("er.arrivedAt")}</th>
                      <th>{t("er.triagedAt")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {board.data?.recent.map((visit) => (
                      <tr key={visit.publicId}>
                        <td>
                          {visitName(visit)}
                          <span className="muted mono"> · {visit.patient.medicalNumber}</span>
                        </td>
                        <td>{visit.disposition ? t(`erDisposition.${visit.disposition}`) : "—"}</td>
                        <td>
                          <span className="badge badge-muted">
                            {t(`erStatus.${visit.status}`)}
                          </span>
                        </td>
                        <td>{formatDateTime(visit.arrivalAt, locale)}</td>
                        <td>{formatDateTime(visit.triageAt, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}

      {registerOpen && (
        <RegisterModal
          onClose={() => setRegisterOpen(false)}
          pending={register.isPending}
          error={register.error}
          onSubmit={(body) => register.mutate(body)}
        />
      )}

      {triageVisit && (
        <TriageModal
          visit={triageVisit}
          levels={levels.data ?? []}
          pending={triage.isPending}
          error={triage.error}
          onClose={() => setTriageVisit(null)}
          onSubmit={(body) =>
            triage.mutate({ publicId: triageVisit.publicId, body })
          }
        />
      )}

      {disposeVisit && (
        <DispositionModal
          visit={disposeVisit}
          pending={dispose.isPending}
          error={dispose.error}
          onClose={() => setDisposeVisit(null)}
          onSubmit={(body) =>
            dispose.mutate({ publicId: disposeVisit.publicId, body })
          }
        />
      )}
    </div>
  );
}

function RegisterModal({
  onClose,
  pending,
  error,
  onSubmit,
}: {
  onClose: () => void;
  pending: boolean;
  error: unknown;
  onSubmit: (body: {
    patientPublicId: string;
    arrivalMode?: string;
    chiefComplaint?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [arrivalMode, setArrivalMode] = useState("WALK_IN");
  const [complaint, setComplaint] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!patient) {
      return;
    }
    onSubmit({
      patientPublicId: patient.publicId,
      arrivalMode,
      chiefComplaint: complaint.trim() || undefined,
    });
  }

  return (
    <Modal title={t("er.registerTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <label className="field">
          <span>{t("er.patient")}</span>
          <PatientPicker selected={patient} onSelect={setPatient} />
        </label>
        <label className="field">
          <span>{t("er.arrivalMode")}</span>
          <select value={arrivalMode} onChange={(event) => setArrivalMode(event.target.value)}>
            {ER_ARRIVAL_MODES.map((mode) => (
              <option key={mode} value={mode}>
                {t(`erArrival.${mode}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("er.complaint")}</span>
          <textarea
            value={complaint}
            onChange={(event) => setComplaint(event.target.value)}
            rows={3}
            maxLength={500}
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
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function TriageModal({
  visit,
  levels,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  visit: ErVisit;
  levels: { code: string; nameAr: string; nameEn: string }[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    triageLevelCode: string;
    chiefComplaint?: string;
    version: number;
    vitals?: VitalsInput;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [code, setCode] = useState(levels[0]?.code ?? "");
  const [complaint, setComplaint] = useState(visit.chiefComplaint ?? "");
  const [vitals, setVitals] = useState<Record<string, string>>({});

  function number(key: string): number | undefined {
    const raw = vitals[key];
    if (!raw || raw.trim() === "") {
      return undefined;
    }
    const value = Number(raw);
    return Number.isFinite(value) ? value : undefined;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const payload: VitalsInput = {
      temperatureC: number("temperatureC"),
      heartRate: number("heartRate"),
      respiratoryRate: number("respiratoryRate"),
      systolic: number("systolic"),
      diastolic: number("diastolic"),
      spo2: number("spo2"),
      painScore: number("painScore"),
    };
    const hasVitals = Object.values(payload).some((value) => value !== undefined);
    onSubmit({
      triageLevelCode: code,
      chiefComplaint: complaint.trim() || undefined,
      version: visit.version,
      vitals: hasVitals ? payload : undefined,
    });
  }

  return (
    <Modal title={t("er.triageTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <p className="muted">
          {visit.patient.fullName} · {visit.patient.medicalNumber}
        </p>
        <label className="field">
          <span>{t("er.triageLevel")}</span>
          <select value={code} onChange={(event) => setCode(event.target.value)} required>
            {levels.map((level) => (
              <option key={level.code} value={level.code}>
                {locale === "ar" ? level.nameAr : level.nameEn || level.nameAr}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("er.complaint")}</span>
          <textarea
            value={complaint}
            onChange={(event) => setComplaint(event.target.value)}
            rows={2}
            maxLength={500}
          />
        </label>
        <fieldset className="fieldset">
          <legend>{t("er.vitalsOptional")}</legend>
          <div className="vital-grid">
            {[
              ["temperatureC", t("vitals.temperature")],
              ["heartRate", t("vitals.heartRate")],
              ["respiratoryRate", t("vitals.respiratoryRate")],
              ["systolic", t("vitals.systolic")],
              ["diastolic", t("vitals.diastolic")],
              ["spo2", t("vitals.spo2")],
              ["painScore", t("vitals.pain")],
            ].map(([key, label]) => (
              <label className="field" key={key}>
                <span>{label}</span>
                <input
                  type="number"
                  step="any"
                  value={vitals[key] ?? ""}
                  onChange={(event) =>
                    setVitals((current) => ({ ...current, [key]: event.target.value }))
                  }
                />
              </label>
            ))}
          </div>
        </fieldset>
        {error ? (
          <p className="field-error" role="alert">
            {describeApiError(error, locale, t("common.saveError"))}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={onClose}>
            {t("common.cancel")}
          </button>
          <button type="submit" className="button" disabled={!code || pending}>
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function DispositionModal({
  visit,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  visit: ErVisit;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (body: {
    disposition: string;
    notes?: string;
    reason?: string;
    version: number;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [disposition, setDisposition] = useState("DISCHARGE");
  const [notes, setNotes] = useState("");
  const [reason, setReason] = useState("");
  const requiresReason = disposition === "LWBS" || disposition === "ADMIT";

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      disposition,
      notes: notes.trim() || undefined,
      reason: requiresReason ? reason.trim() || undefined : undefined,
      version: visit.version,
    });
  }

  return (
    <Modal title={t("er.dispositionTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        <p className="muted">
          {visit.patient.fullName} · {visit.patient.medicalNumber}
        </p>
        <label className="field">
          <span>{t("er.dispositionType")}</span>
          <select
            value={disposition}
            onChange={(event) => setDisposition(event.target.value)}
          >
            {ER_DISPOSITIONS.map((value) => (
              <option key={value} value={value}>
                {t(`erDisposition.${value}`)}
              </option>
            ))}
          </select>
        </label>
        {requiresReason && (
          <label className="field">
            <span>{t("adt.reason")}</span>
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={2}
              required
              maxLength={500}
            />
          </label>
        )}
        <label className="field">
          <span>{t("er.notes")}</span>
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
          <button
            type="submit"
            className="button"
            disabled={pending || (requiresReason && !reason.trim())}
          >
            {pending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}
