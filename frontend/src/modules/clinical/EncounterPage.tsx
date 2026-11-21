import { useMemo, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { formatDate, formatDateTime, formatTime } from "../../shared/ui/format";
import { schedulingApi } from "../scheduling/schedulingApi";
import {
  clinicalApi,
  noteContent,
  type NoteRow,
  type PrescriptionItemInput,
  type PrintPayload,
  type VitalsInput,
} from "./clinicalApi";
import { statusTone } from "../patients/PatientsPage";

type TabId =
  | "overview"
  | "vitals"
  | "diagnoses"
  | "notes"
  | "orders"
  | "prescriptions"
  | "charges"
  | "print";

const NOTE_TYPES = ["PROGRESS", "ADMISSION", "DISCHARGE", "NURSING", "CONSULTATION"];
const ORDER_TYPES = ["LAB", "RAD", "PROCEDURE"];
const ORDER_PRIORITIES = ["ROUTINE", "URGENT", "STAT"];
const ORDER_STATUSES = ["ORDERED", "IN_PROGRESS", "COMPLETED", "CANCELLED"];
const ROUTES = ["ORAL", "IV", "IM", "SC", "TOPICAL", "INHALED", "RECTAL", "OPHTHALMIC"];
const FREQUENCIES = ["OD", "BID", "TID", "QID", "PRN", "STAT"];

interface VitalsForm {
  temperatureC: string;
  heartRate: string;
  respiratoryRate: string;
  systolic: string;
  diastolic: string;
  spo2: string;
  weightKg: string;
  heightCm: string;
  painScore: string;
}

const emptyVitals: VitalsForm = {
  temperatureC: "",
  heartRate: "",
  respiratoryRate: "",
  systolic: "",
  diastolic: "",
  spo2: "",
  weightKg: "",
  heightCm: "",
  painScore: "",
};

interface NoteFormState {
  subjective: string;
  objective: string;
  assessment: string;
  plan: string;
}

const emptyNote: NoteFormState = {
  subjective: "",
  objective: "",
  assessment: "",
  plan: "",
};

interface OrderLine {
  servicePublicId: string;
  quantity: number;
}

interface DrugLine extends PrescriptionItemInput {
  key: number;
}

function numberOrNull(value: string): number | null {
  if (!value.trim()) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function displayName(
  locale: string,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return (locale === "ar" ? ar : en) ?? ar ?? en ?? "—";
}

export function EncounterPage() {
  const { publicId = "" } = useParams();
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();

  const canUpdate = hasPermission("EMR:UPDATE");
  const canDiagnose = hasPermission("EMR:DIAGNOSE");
  const canSign = hasPermission("EMR:SIGN");

  const [tab, setTab] = useState<TabId>("overview");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [vitalsForm, setVitalsForm] = useState<VitalsForm>(emptyVitals);
  const [diagnosisForm, setDiagnosisForm] = useState({
    code: "",
    description: "",
    primary: false,
  });
  const [noteType, setNoteType] = useState("PROGRESS");
  const [noteForm, setNoteForm] = useState<NoteFormState>(emptyNote);
  const [editNote, setEditNote] = useState<{ publicId: string; form: NoteFormState } | null>(
    null,
  );
  const [amendNote, setAmendNote] = useState<{
    publicId: string;
    reason: string;
    form: NoteFormState;
  } | null>(null);
  const [versionsNote, setVersionsNote] = useState<NoteRow | null>(null);

  const [orderForm, setOrderForm] = useState({
    orderType: "LAB",
    priority: "ROUTINE",
    notes: "",
  });
  const [orderLines, setOrderLines] = useState<OrderLine[]>([]);
  const [orderService, setOrderService] = useState("");
  const [orderQuantity, setOrderQuantity] = useState("1");

  const [drugLines, setDrugLines] = useState<DrugLine[]>([]);
  const [prescriptionNotes, setPrescriptionNotes] = useState("");
  const [viewPrescription, setViewPrescription] = useState<string | null>(null);

  const [chargeService, setChargeService] = useState("");
  const [chargeQuantity, setChargeQuantity] = useState("1");

  const [lifecycleAction, setLifecycleAction] = useState<"close" | "cancel" | null>(null);
  const [lifecycleReason, setLifecycleReason] = useState("");

  const workspace = useQuery({
    queryKey: ["encounter", publicId],
    queryFn: () => clinicalApi.get(publicId),
    enabled: publicId.length > 0,
  });

  const services = useQuery({
    queryKey: ["scheduling-services"],
    queryFn: () => schedulingApi.services(),
    staleTime: 300_000,
  });

  const drugs = useQuery({
    queryKey: ["scheduling-drugs"],
    queryFn: () => schedulingApi.drugs(),
    staleTime: 300_000,
  });

  const versions = useQuery({
    queryKey: ["note-versions", publicId, versionsNote?.publicId],
    queryFn: () => clinicalApi.noteVersions(publicId, versionsNote?.publicId ?? ""),
    enabled: versionsNote !== null,
  });

  const printPayload = useQuery({
    queryKey: ["encounter-print", publicId],
    queryFn: () => clinicalApi.print(publicId),
    enabled: tab === "print",
  });

  const prescription = useQuery({
    queryKey: ["prescription", viewPrescription],
    queryFn: () => clinicalApi.getPrescription(viewPrescription ?? ""),
    enabled: viewPrescription !== null,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["encounter", publicId] });
  }

  function handleError(cause: unknown) {
    setError(describeApiError(cause, locale, t("common.saveError")));
  }

  function handleSuccess(message: string) {
    setNotice(message);
    setError(null);
  }

  const addVitals = useMutation({
    mutationFn: () => {
      const body: VitalsInput = {
        temperatureC: numberOrNull(vitalsForm.temperatureC),
        heartRate: numberOrNull(vitalsForm.heartRate),
        respiratoryRate: numberOrNull(vitalsForm.respiratoryRate),
        systolic: numberOrNull(vitalsForm.systolic),
        diastolic: numberOrNull(vitalsForm.diastolic),
        spo2: numberOrNull(vitalsForm.spo2),
        weightKg: numberOrNull(vitalsForm.weightKg),
        heightCm: numberOrNull(vitalsForm.heightCm),
        painScore: numberOrNull(vitalsForm.painScore),
      };
      return clinicalApi.addVitals(publicId, body);
    },
    onSuccess: () => {
      refresh();
      setVitalsForm(emptyVitals);
      handleSuccess(t("encounters.vitalsSaved"));
    },
    onError: handleError,
  });

  const addDiagnosis = useMutation({
    mutationFn: () =>
      clinicalApi.addDiagnosis(publicId, {
        code: diagnosisForm.code.trim() || undefined,
        description: diagnosisForm.description.trim(),
        primary: diagnosisForm.primary,
      }),
    onSuccess: () => {
      refresh();
      setDiagnosisForm({ code: "", description: "", primary: false });
      handleSuccess(t("encounters.diagnosisSaved"));
    },
    onError: handleError,
  });

  const removeDiagnosis = useMutation({
    mutationFn: (diagnosisPublicId: string) =>
      clinicalApi.deleteDiagnosis(publicId, diagnosisPublicId),
    onSuccess: refresh,
    onError: handleError,
  });

  const createNote = useMutation({
    mutationFn: () =>
      clinicalApi.createNote(publicId, {
        noteType,
        content: noteForm,
      }),
    onSuccess: () => {
      refresh();
      setNoteForm(emptyNote);
      handleSuccess(t("encounters.noteSaved"));
    },
    onError: handleError,
  });

  const saveNote = useMutation({
    mutationFn: () =>
      clinicalApi.updateNote(publicId, editNote?.publicId ?? "", {
        content: editNote?.form ?? emptyNote,
      }),
    onSuccess: () => {
      refresh();
      setEditNote(null);
      handleSuccess(t("encounters.noteSaved"));
    },
    onError: handleError,
  });

  const signNote = useMutation({
    mutationFn: (notePublicId: string) => clinicalApi.signNote(publicId, notePublicId),
    onSuccess: () => {
      refresh();
      handleSuccess(t("encounters.noteSigned"));
    },
    onError: handleError,
  });

  const amendNoteMutation = useMutation({
    mutationFn: () =>
      clinicalApi.amendNote(publicId, amendNote?.publicId ?? "", {
        reason: amendNote?.reason.trim() ?? "",
        content: amendNote?.form ?? emptyNote,
      }),
    onSuccess: () => {
      refresh();
      setAmendNote(null);
      handleSuccess(t("encounters.noteAmended"));
    },
    onError: handleError,
  });

  const createOrder = useMutation({
    mutationFn: () =>
      clinicalApi.createOrder(publicId, {
        orderType: orderForm.orderType,
        priority: orderForm.priority,
        notes: orderForm.notes.trim() || undefined,
        services: orderLines,
      }),
    onSuccess: () => {
      refresh();
      setOrderLines([]);
      setOrderForm({ ...orderForm, notes: "" });
      handleSuccess(t("encounters.orderSaved"));
    },
    onError: handleError,
  });

  const changeOrderStatus = useMutation({
    mutationFn: (payload: { orderPublicId: string; status: string }) =>
      clinicalApi.updateOrderStatus(publicId, payload.orderPublicId, payload.status),
    onSuccess: refresh,
    onError: handleError,
  });

  const createPrescription = useMutation({
    mutationFn: () =>
      clinicalApi.createPrescription(publicId, {
        notes: prescriptionNotes.trim() || undefined,
        items: drugLines.map((line) => ({
          drugPublicId: line.drugPublicId,
          dose: line.dose,
          route: line.route,
          frequency: line.frequency,
          durationDays: line.durationDays,
          quantity: line.quantity,
          instructions: line.instructions,
        })),
      }),
    onSuccess: () => {
      refresh();
      setDrugLines([]);
      setPrescriptionNotes("");
      handleSuccess(t("encounters.prescriptionSaved"));
    },
    onError: handleError,
  });

  const issuePrescription = useMutation({
    mutationFn: (prescriptionPublicId: string) =>
      clinicalApi.issuePrescription(publicId, prescriptionPublicId),
    onSuccess: () => {
      refresh();
      handleSuccess(t("encounters.prescriptionIssued"));
    },
    onError: handleError,
  });

  const cancelPrescription = useMutation({
    mutationFn: (payload: { prescriptionPublicId: string; reason: string }) =>
      clinicalApi.cancelPrescription(publicId, payload.prescriptionPublicId, payload.reason),
    onSuccess: () => {
      refresh();
      handleSuccess(t("encounters.prescriptionCancelled"));
    },
    onError: handleError,
  });

  const addCharge = useMutation({
    mutationFn: () =>
      clinicalApi.addCharge(publicId, {
        servicePublicId: chargeService,
        quantity: Number(chargeQuantity) || 1,
      }),
    onSuccess: () => {
      refresh();
      setChargeQuantity("1");
      handleSuccess(t("encounters.chargeSaved"));
    },
    onError: handleError,
  });

  const closeEncounter = useMutation({
    mutationFn: () =>
      clinicalApi.close(publicId, {
        reason: lifecycleReason.trim() || undefined,
        version: workspace.data?.encounter.version ?? 0,
      }),
    onSuccess: () => {
      refresh();
      setLifecycleAction(null);
      setLifecycleReason("");
      handleSuccess(t("encounters.closed"));
    },
    onError: handleError,
  });

  const cancelEncounter = useMutation({
    mutationFn: () =>
      clinicalApi.cancel(publicId, {
        reason: lifecycleReason.trim() || undefined,
        version: workspace.data?.encounter.version ?? 0,
      }),
    onSuccess: () => {
      refresh();
      setLifecycleAction(null);
      setLifecycleReason("");
      handleSuccess(t("encounters.cancelled"));
    },
    onError: handleError,
  });

  const serviceList = services.data ?? [];
  const drugList = drugs.data ?? [];
  const orderServices = useMemo(
    () => serviceList.filter((service) => service.category === orderForm.orderType),
    [serviceList, orderForm.orderType],
  );

  if (workspace.isPending) {
    return <p className="hint">{t("common.loading")}</p>;
  }
  if (workspace.isError || !workspace.data) {
    return <p className="field-error">{t("encounters.notFound")}</p>;
  }

  const data = workspace.data;
  const encounter = data.encounter;
  const editable = data.editable;
  const severeAllergies = data.allergies.filter(
    (allergy) => allergy.severity === "SEVERE" || allergy.severity === "LIFE_THREATENING",
  );

  const tabs: [TabId, string][] = [
    ["overview", "encounters.tab.overview"],
    ["vitals", "encounters.tab.vitals"],
    ["diagnoses", "encounters.tab.diagnoses"],
    ["notes", "encounters.tab.notes"],
    ["orders", "encounters.tab.orders"],
    ["prescriptions", "encounters.tab.prescriptions"],
    ["charges", "encounters.tab.charges"],
    ["print", "encounters.tab.print"],
  ];

  function openEditNote(note: NoteRow) {
    setEditNote({ publicId: note.publicId, form: noteContent(note) });
  }

  function openAmendNote(note: NoteRow) {
    setAmendNote({
      publicId: note.publicId,
      reason: "",
      form: noteContent(note),
    });
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <Link className="back-link" to="/app/clinical">
            <span className="back-arrow">←</span> {t("encounters.backToList")}
          </Link>
          <h1>{data.patient.fullName}</h1>
          <p className="muted">
            <span className="mono">{data.patient.medicalNumber}</span>
            {" · "}
            {t(`gender.${data.patient.gender}`)}
            {data.patient.age !== null ? ` · ${data.patient.age} ${t("common.years")}` : ""}
            {data.patient.bloodGroup ? ` · ${data.patient.bloodGroup}` : ""}
          </p>
          <div className="chip-row">
            <span className={`badge badge-${statusTone(encounter.status)}`}>
              {t(`encounterStatus.${encounter.status}`)}
            </span>
            <span className="chip">{t(`encounterType.${encounter.type}`)}</span>
            <span className="chip">
              {formatDateTime(encounter.startedAt, locale)}
            </span>
            <span className="chip">
              {displayName(locale, encounter.providerAr, encounter.providerEn)}
            </span>
          </div>
        </div>
        <div className="header-actions">
          {canUpdate && editable && (
            <button
              type="button"
              className="button button-ghost"
              onClick={() => {
                setLifecycleReason("");
                setLifecycleAction("cancel");
              }}
            >
              {t("encounters.cancelEncounter")}
            </button>
          )}
          <button
            type="button"
            className="button button-ghost"
            onClick={() => setTab("print")}
          >
            {t("encounters.print")}
          </button>
          {canUpdate && editable && (
            <button
              type="button"
              className="button"
              onClick={() => {
                setLifecycleReason("");
                setLifecycleAction("close");
              }}
            >
              {t("encounters.closeEncounter")}
            </button>
          )}
        </div>
      </header>

      {error && <div className="alert alert-danger">{error}</div>}
      {notice && <div className="alert alert-success">{notice}</div>}

      {severeAllergies.length > 0 && (
        <div className="alert alert-danger">
          <strong>{t("patients.allergyAlert")}</strong>{" "}
          {severeAllergies.map((allergy) => allergy.substance).join(" · ")}
        </div>
      )}

      {!editable && (
        <div className="notice">{t("encounters.readOnly")}</div>
      )}

      <div className="tabs" role="tablist">
        {tabs.map(([id, key]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            className={`tab${tab === id ? " is-active" : ""}`}
            onClick={() => setTab(id)}
          >
            {t(key)}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="tab-content">
          <div className="split-grid">
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.encounterDetails")}</h2>
              </header>
              <dl className="meta-grid">
                <dt>{t("encounters.type")}</dt>
                <dd>{t(`encounterType.${encounter.type}`)}</dd>
                <dt>{t("encounters.provider")}</dt>
                <dd>{displayName(locale, encounter.providerAr, encounter.providerEn)}</dd>
                <dt>{t("encounters.department")}</dt>
                <dd>{displayName(locale, encounter.departmentAr, encounter.departmentEn)}</dd>
                <dt>{t("encounters.startedAt")}</dt>
                <dd>{formatDateTime(encounter.startedAt, locale)}</dd>
                <dt>{t("encounters.closedAt")}</dt>
                <dd>{formatDateTime(encounter.closedAt, locale)}</dd>
                <dt>{t("encounters.appointment")}</dt>
                <dd className="mono">{encounter.appointmentPublicId ?? "—"}</dd>
                <dt>{t("encounters.complaint")}</dt>
                <dd>{encounter.chiefComplaint ?? "—"}</dd>
              </dl>
            </section>

            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.latestVitals")}</h2>
              </header>
              {data.latestVitals ? (
                <div className="vital-grid">
                  <VitalChip label={t("vitals.temperature")} value={data.latestVitals.temperatureC} unit="°C" />
                  <VitalChip label={t("vitals.heartRate")} value={data.latestVitals.heartRate} unit="bpm" />
                  <VitalChip label={t("vitals.respiratoryRate")} value={data.latestVitals.respiratoryRate} unit="/min" />
                  <VitalChip
                    label={t("vitals.bloodPressure")}
                    value={
                      data.latestVitals.systolic || data.latestVitals.diastolic
                        ? `${data.latestVitals.systolic ?? "—"}/${data.latestVitals.diastolic ?? "—"}`
                        : null
                    }
                    unit="mmHg"
                  />
                  <VitalChip label={t("vitals.spo2")} value={data.latestVitals.spo2} unit="%" />
                  <VitalChip label={t("vitals.weight")} value={data.latestVitals.weightKg} unit="kg" />
                  <VitalChip label={t("vitals.height")} value={data.latestVitals.heightCm} unit="cm" />
                  <VitalChip label={t("vitals.pain")} value={data.latestVitals.painScore} unit="/10" />
                </div>
              ) : (
                <p className="empty-inline">{t("encounters.noVitals")}</p>
              )}

              <header className="card-header">
                <h2>{t("patients.conditions")}</h2>
              </header>
              {data.conditions.length === 0 ? (
                <p className="empty-inline">{t("patients.noConditions")}</p>
              ) : (
                <ul className="plain-list">
                  {data.conditions.map((condition) => (
                    <li key={condition.publicId}>
                      <strong>{condition.description}</strong>
                      <span className="muted">
                        {condition.onsetDate ? ` · ${formatDate(condition.onsetDate, locale)}` : ""}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.history")}</h2>
            </header>
            {data.history.length === 0 ? (
              <p className="empty-inline">{t("encounters.noHistory")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("encounters.startedAt")}</th>
                      <th>{t("encounters.type")}</th>
                      <th>{t("encounters.complaint")}</th>
                      <th>{t("encounters.provider")}</th>
                      <th>{t("common.status")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.history.map((item) => (
                      <tr key={item.publicId}>
                        <td>{formatDateTime(item.startedAt, locale)}</td>
                        <td>{t(`encounterType.${item.type}`)}</td>
                        <td className="reason-cell">{item.chiefComplaint ?? "—"}</td>
                        <td>{displayName(locale, item.providerAr, item.providerEn)}</td>
                        <td>
                          <span className={`badge badge-${statusTone(item.status)}`}>
                            {t(`encounterStatus.${item.status}`)}
                          </span>
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

      {tab === "vitals" && (
        <div className="tab-content">
          {editable && canUpdate && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.recordVitals")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  addVitals.mutate();
                }}
              >
                <div className="form-grid">
                  <VitalField label={t("vitals.temperature")} value={vitalsForm.temperatureC} onChange={(value) => setVitalsForm({ ...vitalsForm, temperatureC: value })} />
                  <VitalField label={t("vitals.heartRate")} value={vitalsForm.heartRate} onChange={(value) => setVitalsForm({ ...vitalsForm, heartRate: value })} />
                  <VitalField label={t("vitals.respiratoryRate")} value={vitalsForm.respiratoryRate} onChange={(value) => setVitalsForm({ ...vitalsForm, respiratoryRate: value })} />
                  <VitalField label={t("vitals.systolic")} value={vitalsForm.systolic} onChange={(value) => setVitalsForm({ ...vitalsForm, systolic: value })} />
                  <VitalField label={t("vitals.diastolic")} value={vitalsForm.diastolic} onChange={(value) => setVitalsForm({ ...vitalsForm, diastolic: value })} />
                  <VitalField label={t("vitals.spo2")} value={vitalsForm.spo2} onChange={(value) => setVitalsForm({ ...vitalsForm, spo2: value })} />
                  <VitalField label={t("vitals.weight")} value={vitalsForm.weightKg} onChange={(value) => setVitalsForm({ ...vitalsForm, weightKg: value })} />
                  <VitalField label={t("vitals.height")} value={vitalsForm.heightCm} onChange={(value) => setVitalsForm({ ...vitalsForm, heightCm: value })} />
                  <VitalField label={t("vitals.pain")} value={vitalsForm.painScore} onChange={(value) => setVitalsForm({ ...vitalsForm, painScore: value })} />
                </div>
                <div className="form-actions">
                  <button type="submit" className="button" disabled={addVitals.isPending}>
                    {addVitals.isPending ? t("common.saving") : t("common.save")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.vitalsHistory")}</h2>
            </header>
            {data.vitals.length === 0 ? (
              <p className="empty-inline">{t("encounters.noVitals")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("encounters.recordedAt")}</th>
                      <th>°C</th>
                      <th>{t("vitals.heartRate")}</th>
                      <th>{t("vitals.respiratoryRate")}</th>
                      <th>{t("vitals.bloodPressure")}</th>
                      <th>SpO₂</th>
                      <th>{t("vitals.weight")}</th>
                      <th>{t("vitals.height")}</th>
                      <th>{t("vitals.pain")}</th>
                      <th>{t("encounters.recordedBy")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.vitals.map((vital) => (
                      <tr key={vital.publicId}>
                        <td>{formatDateTime(vital.recordedAt, locale)}</td>
                        <td>{vital.temperatureC ?? "—"}</td>
                        <td>{vital.heartRate ?? "—"}</td>
                        <td>{vital.respiratoryRate ?? "—"}</td>
                        <td>
                          {vital.systolic || vital.diastolic
                            ? `${vital.systolic ?? "—"}/${vital.diastolic ?? "—"}`
                            : "—"}
                        </td>
                        <td>{vital.spo2 ?? "—"}</td>
                        <td>{vital.weightKg ?? "—"}</td>
                        <td>{vital.heightCm ?? "—"}</td>
                        <td>{vital.painScore ?? "—"}</td>
                        <td>{displayName(locale, vital.recordedByAr, vital.recordedByEn)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "diagnoses" && (
        <div className="tab-content">
          {editable && canDiagnose && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.addDiagnosis")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  addDiagnosis.mutate();
                }}
              >
                <div className="form-grid">
                  <label className="field">
                    <span>{t("encounters.diagnosisCode")}</span>
                    <input
                      value={diagnosisForm.code}
                      onChange={(event) =>
                        setDiagnosisForm({ ...diagnosisForm, code: event.target.value })
                      }
                    />
                  </label>
                  <label className="field">
                    <span>{t("encounters.diagnosisDescription")} *</span>
                    <input
                      value={diagnosisForm.description}
                      onChange={(event) =>
                        setDiagnosisForm({ ...diagnosisForm, description: event.target.value })
                      }
                      required
                    />
                  </label>
                  <label className="field-inline">
                    <input
                      type="checkbox"
                      checked={diagnosisForm.primary}
                      onChange={(event) =>
                        setDiagnosisForm({ ...diagnosisForm, primary: event.target.checked })
                      }
                    />
                    <span>{t("encounters.primaryDiagnosis")}</span>
                  </label>
                </div>
                <div className="form-actions">
                  <button
                    type="submit"
                    className="button"
                    disabled={addDiagnosis.isPending}
                  >
                    {addDiagnosis.isPending ? t("common.saving") : t("common.create")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.diagnoses")}</h2>
            </header>
            {data.diagnoses.length === 0 ? (
              <p className="empty-inline">{t("encounters.noDiagnoses")}</p>
            ) : (
              <ul className="plain-list">
                {data.diagnoses.map((diagnosis) => (
                  <li key={diagnosis.publicId} className="diagnosis-row">
                    <div>
                      <span className="mono muted">{diagnosis.code ?? "—"}</span>{" "}
                      <strong>{diagnosis.description}</strong>{" "}
                      {Boolean(diagnosis.primaryFlag) && (
                        <span className="badge badge-info">
                          {t("encounters.primary")}
                        </span>
                      )}
                      <div className="muted">
                        {t(`diagnosisType.${diagnosis.diagnosisType}`)} ·{" "}
                        {formatDateTime(diagnosis.createdAt, locale)}
                      </div>
                    </div>
                    {editable && canDiagnose && (
                      <button
                        type="button"
                        className="button button-ghost button-sm"
                        onClick={() => {
                          if (window.confirm(t("encounters.confirmRemoveDiagnosis"))) {
                            removeDiagnosis.mutate(diagnosis.publicId);
                          }
                        }}
                      >
                        {t("patients.remove")}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {tab === "notes" && (
        <div className="tab-content">
          {editable && canUpdate && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.newNote")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  createNote.mutate();
                }}
              >
                <label className="field field-inline">
                  <span>{t("encounters.noteType")}</span>
                  <select value={noteType} onChange={(event) => setNoteType(event.target.value)}>
                    {NOTE_TYPES.map((item) => (
                      <option key={item} value={item}>
                        {t(`noteType.${item}`)}
                      </option>
                    ))}
                  </select>
                </label>
                <NoteFields
                  value={noteForm}
                  onChange={setNoteForm}
                  labels={{
                    subjective: t("note.subjective"),
                    objective: t("note.objective"),
                    assessment: t("note.assessment"),
                    plan: t("note.plan"),
                  }}
                />
                <div className="form-actions">
                  <button type="submit" className="button" disabled={createNote.isPending}>
                    {createNote.isPending ? t("common.saving") : t("common.create")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.notes")}</h2>
            </header>
            {data.notes.length === 0 ? (
              <p className="empty-inline">{t("encounters.noNotes")}</p>
            ) : (
              <div className="note-list">
                {data.notes.map((note) => {
                  const content = noteContent(note);
                  return (
                    <article key={note.publicId} className="note-card">
                      <header className="note-head">
                        <div>
                          <span className="badge badge-info">{t(`noteType.${note.noteType}`)}</span>{" "}
                          <span className={`badge badge-${statusTone(note.status)}`}>
                            {t(`noteStatus.${note.status}`)}
                          </span>{" "}
                          <span className="muted">
                            v{note.versionNo ?? 1} ·{" "}
                            {formatDateTime(note.createdAt, locale)} ·{" "}
                            {displayName(locale, note.authorAr, note.authorEn)}
                          </span>
                        </div>
                        <div className="row-actions">
                          {note.status === "DRAFT" && canUpdate && editable && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => openEditNote(note)}
                            >
                              {t("common.edit")}
                            </button>
                          )}
                          {note.status === "DRAFT" && canSign && (
                            <button
                              type="button"
                              className="button button-sm"
                              onClick={() => signNote.mutate(note.publicId)}
                              disabled={signNote.isPending}
                            >
                              {t("encounters.sign")}
                            </button>
                          )}
                          {(note.status === "SIGNED" || note.status === "AMENDED") &&
                            canSign && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() => openAmendNote(note)}
                              >
                                {t("encounters.amend")}
                              </button>
                            )}
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => setVersionsNote(note)}
                          >
                            {t("encounters.versions")}
                          </button>
                        </div>
                      </header>
                      <dl className="note-body">
                        <dt>{t("note.subjective")}</dt>
                        <dd>{content.subjective || "—"}</dd>
                        <dt>{t("note.objective")}</dt>
                        <dd>{content.objective || "—"}</dd>
                        <dt>{t("note.assessment")}</dt>
                        <dd>{content.assessment || "—"}</dd>
                        <dt>{t("note.plan")}</dt>
                        <dd>{content.plan || "—"}</dd>
                      </dl>
                      {note.signedAt && (
                        <p className="muted">
                          {t("encounters.signedAt")}: {formatDateTime(note.signedAt, locale)}
                          {note.signedByAr || note.signedByEn
                            ? ` · ${displayName(locale, note.signedByAr, note.signedByEn)}`
                            : ""}
                        </p>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "orders" && (
        <div className="tab-content">
          {editable && canUpdate && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.newOrder")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (orderLines.length === 0) {
                    setError(t("encounters.orderNeedsService"));
                    return;
                  }
                  createOrder.mutate();
                }}
              >
                <div className="form-grid">
                  <label className="field">
                    <span>{t("encounters.orderType")}</span>
                    <select
                      value={orderForm.orderType}
                      onChange={(event) =>
                        setOrderForm({ ...orderForm, orderType: event.target.value })
                      }
                    >
                      {ORDER_TYPES.map((item) => (
                        <option key={item} value={item}>
                          {t(`orderType.${item}`)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="field">
                    <span>{t("encounters.priority")}</span>
                    <select
                      value={orderForm.priority}
                      onChange={(event) =>
                        setOrderForm({ ...orderForm, priority: event.target.value })
                      }
                    >
                      {ORDER_PRIORITIES.map((item) => (
                        <option key={item} value={item}>
                          {t(`orderPriority.${item}`)}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>

                <div className="line-editor">
                  <select
                    value={orderService}
                    onChange={(event) => setOrderService(event.target.value)}
                  >
                    <option value="">{t("encounters.chooseService")}</option>
                    {orderServices.map((service) => (
                      <option key={service.publicId} value={service.publicId}>
                        {displayName(locale, service.nameAr, service.nameEn)} ({service.code})
                      </option>
                    ))}
                  </select>
                  <input
                    type="number"
                    min={1}
                    max={50}
                    value={orderQuantity}
                    onChange={(event) => setOrderQuantity(event.target.value)}
                    aria-label={t("encounters.quantity")}
                  />
                  <button
                    type="button"
                    className="button button-ghost button-sm"
                    onClick={() => {
                      if (!orderService) {
                        return;
                      }
                      setOrderLines((lines) => [
                        ...lines,
                        { servicePublicId: orderService, quantity: Number(orderQuantity) || 1 },
                      ]);
                      setOrderService("");
                      setOrderQuantity("1");
                    }}
                  >
                    {t("encounters.addLine")}
                  </button>
                </div>

                {orderLines.length > 0 && (
                  <ul className="plain-list">
                    {orderLines.map((line, index) => {
                      const service = serviceList.find(
                        (item) => item.publicId === line.servicePublicId,
                      );
                      return (
                        <li key={`${line.servicePublicId}-${index}`}>
                          {service
                            ? displayName(locale, service.nameAr, service.nameEn)
                            : line.servicePublicId}{" "}
                          × {line.quantity}
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() =>
                              setOrderLines((lines) =>
                                lines.filter((_, current) => current !== index),
                              )
                            }
                          >
                            {t("patients.remove")}
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}

                <label className="field">
                  <span>{t("encounters.orderNotes")}</span>
                  <textarea
                    rows={2}
                    value={orderForm.notes}
                    onChange={(event) =>
                      setOrderForm({ ...orderForm, notes: event.target.value })
                    }
                  />
                </label>

                <div className="form-actions">
                  <button type="submit" className="button" disabled={createOrder.isPending}>
                    {createOrder.isPending ? t("common.saving") : t("common.create")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.orders")}</h2>
            </header>
            {data.orders.length === 0 ? (
              <p className="empty-inline">{t("encounters.noOrders")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("encounters.orderType")}</th>
                      <th>{t("encounters.services")}</th>
                      <th>{t("encounters.priority")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("encounters.orderedAt")}</th>
                      <th>{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.orders.map((order) => (
                      <tr key={order.publicId}>
                        <td>{t(`orderType.${order.orderType}`)}</td>
                        <td>
                          {(locale === "ar" ? order.itemsAr : order.itemsEn) ?? "—"}
                          {order.notes && <div className="muted">{order.notes}</div>}
                        </td>
                        <td>{t(`orderPriority.${order.priority}`)}</td>
                        <td>
                          <span className={`badge badge-${statusTone(order.status)}`}>
                            {t(`orderStatus.${order.status}`)}
                          </span>
                        </td>
                        <td>{formatDateTime(order.createdAt, locale)}</td>
                        <td className="row-actions">
                          {editable && canUpdate && (
                            <select
                              value={order.status}
                              onChange={(event) =>
                                changeOrderStatus.mutate({
                                  orderPublicId: order.publicId,
                                  status: event.target.value,
                                })
                              }
                              aria-label={t("common.status")}
                            >
                              {ORDER_STATUSES.map((item) => (
                                <option key={item} value={item}>
                                  {t(`orderStatus.${item}`)}
                                </option>
                              ))}
                            </select>
                          )}
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

      {tab === "prescriptions" && (
        <div className="tab-content">
          {editable && canUpdate && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.newPrescription")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (drugLines.length === 0) {
                    setError(t("encounters.prescriptionNeedsDrug"));
                    return;
                  }
                  createPrescription.mutate();
                }}
              >
                <div className="drug-lines">
                  {drugLines.map((line) => (
                    <div key={line.key} className="drug-line">
                      <div className="drug-line-head">
                        <strong>
                          {displayName(
                            locale,
                            drugList.find((drug) => drug.publicId === line.drugPublicId)?.nameAr,
                            drugList.find((drug) => drug.publicId === line.drugPublicId)?.nameEn,
                          )}
                        </strong>
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() =>
                            setDrugLines((lines) =>
                              lines.filter((current) => current.key !== line.key),
                            )
                          }
                        >
                          {t("patients.remove")}
                        </button>
                      </div>
                      <div className="form-grid">
                        <label className="field">
                          <span>{t("encounters.dose")}</span>
                          <input
                            value={line.dose}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? { ...current, dose: event.target.value }
                                    : current,
                                ),
                              )
                            }
                            required
                          />
                        </label>
                        <label className="field">
                          <span>{t("encounters.route")}</span>
                          <select
                            value={line.route}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? { ...current, route: event.target.value }
                                    : current,
                                ),
                              )
                            }
                          >
                            {ROUTES.map((item) => (
                              <option key={item} value={item}>
                                {t(`route.${item}`)}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label className="field">
                          <span>{t("encounters.frequency")}</span>
                          <select
                            value={line.frequency}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? { ...current, frequency: event.target.value }
                                    : current,
                                ),
                              )
                            }
                          >
                            {FREQUENCIES.map((item) => (
                              <option key={item} value={item}>
                                {t(`frequency.${item}`)}
                              </option>
                            ))}
                          </select>
                        </label>
                        <label className="field">
                          <span>{t("encounters.durationDays")}</span>
                          <input
                            type="number"
                            min={1}
                            value={line.durationDays ?? ""}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? {
                                        ...current,
                                        durationDays: numberOrNull(event.target.value),
                                      }
                                    : current,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="field">
                          <span>{t("encounters.quantity")}</span>
                          <input
                            type="number"
                            min={1}
                            value={line.quantity ?? 1}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? {
                                        ...current,
                                        quantity: Number(event.target.value) || 1,
                                      }
                                    : current,
                                ),
                              )
                            }
                          />
                        </label>
                        <label className="field">
                          <span>{t("encounters.instructions")}</span>
                          <input
                            value={line.instructions ?? ""}
                            onChange={(event) =>
                              setDrugLines((lines) =>
                                lines.map((current) =>
                                  current.key === line.key
                                    ? { ...current, instructions: event.target.value }
                                    : current,
                                ),
                              )
                            }
                          />
                        </label>
                      </div>
                    </div>
                  ))}
                </div>

                <div className="line-editor">
                  <select
                    value=""
                    onChange={(event) => {
                      const drugPublicId = event.target.value;
                      if (!drugPublicId) {
                        return;
                      }
                      setDrugLines((lines) => [
                        ...lines,
                        {
                          key: Date.now(),
                          drugPublicId,
                          dose: "",
                          route: "ORAL",
                          frequency: "BID",
                          durationDays: 5,
                          quantity: 1,
                          instructions: "",
                        },
                      ]);
                    }}
                  >
                    <option value="">{t("encounters.addDrug")}</option>
                    {drugList.map((drug) => (
                      <option key={drug.publicId} value={drug.publicId}>
                        {displayName(locale, drug.nameAr, drug.nameEn)} ({drug.code})
                      </option>
                    ))}
                  </select>
                </div>

                <label className="field">
                  <span>{t("encounters.orderNotes")}</span>
                  <textarea
                    rows={2}
                    value={prescriptionNotes}
                    onChange={(event) => setPrescriptionNotes(event.target.value)}
                  />
                </label>

                <div className="form-actions">
                  <button
                    type="submit"
                    className="button"
                    disabled={createPrescription.isPending || drugLines.length === 0}
                  >
                    {createPrescription.isPending ? t("common.saving") : t("common.create")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.prescriptions")}</h2>
            </header>
            {data.prescriptions.length === 0 ? (
              <p className="empty-inline">{t("encounters.noPrescriptions")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("encounters.prescribedAt")}</th>
                      <th>{t("encounters.prescribedBy")}</th>
                      <th>{t("patients.items")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.prescriptions.map((item) => (
                      <tr key={item.publicId}>
                        <td>{formatDateTime(item.issuedAt ?? item.createdAt, locale)}</td>
                        <td>{displayName(locale, item.prescribedByAr, item.prescribedByEn)}</td>
                        <td>{item.itemCount}</td>
                        <td>
                          <span className={`badge badge-${statusTone(item.status)}`}>
                            {t(`prescriptionStatus.${item.status}`)}
                          </span>
                        </td>
                        <td className="row-actions">
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => setViewPrescription(item.publicId)}
                          >
                            {t("common.details")}
                          </button>
                          {item.status === "DRAFT" && canSign && editable && (
                            <button
                              type="button"
                              className="button button-sm"
                              onClick={() => issuePrescription.mutate(item.publicId)}
                              disabled={issuePrescription.isPending}
                            >
                              {t("encounters.issue")}
                            </button>
                          )}
                          {item.status !== "CANCELLED" && canUpdate && editable && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() => {
                                const reason = window.prompt(t("encounters.cancelReason"));
                                if (reason && reason.trim()) {
                                  cancelPrescription.mutate({
                                    prescriptionPublicId: item.publicId,
                                    reason: reason.trim(),
                                  });
                                }
                              }}
                            >
                              {t("scheduling.cancel")}
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
        </div>
      )}

      {tab === "charges" && (
        <div className="tab-content">
          {editable && canUpdate && (
            <section className="card">
              <header className="card-header">
                <h2>{t("encounters.addCharge")}</h2>
              </header>
              <form
                className="stack-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (!chargeService) {
                    setError(t("encounters.chargeNeedsService"));
                    return;
                  }
                  addCharge.mutate();
                }}
              >
                <div className="line-editor">
                  <select
                    value={chargeService}
                    onChange={(event) => setChargeService(event.target.value)}
                  >
                    <option value="">{t("encounters.chooseService")}</option>
                    {serviceList.map((service) => (
                      <option key={service.publicId} value={service.publicId}>
                        {displayName(locale, service.nameAr, service.nameEn)} ({service.code})
                      </option>
                    ))}
                  </select>
                  <input
                    type="number"
                    min={1}
                    max={20}
                    value={chargeQuantity}
                    onChange={(event) => setChargeQuantity(event.target.value)}
                    aria-label={t("encounters.quantity")}
                  />
                  <button type="submit" className="button" disabled={addCharge.isPending}>
                    {addCharge.isPending ? t("common.saving") : t("common.create")}
                  </button>
                </div>
              </form>
            </section>
          )}

          <section className="card">
            <header className="card-header">
              <h2>{t("encounters.charges")}</h2>
            </header>
            {data.charges.length === 0 ? (
              <p className="empty-inline">{t("encounters.noCharges")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("encounters.performedAt")}</th>
                      <th>{t("encounters.service")}</th>
                      <th>{t("encounters.source")}</th>
                      <th>{t("encounters.price")}</th>
                      <th>{t("common.status")}</th>
                      <th>{t("encounters.performedBy")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.charges.map((charge) => (
                      <tr key={charge.publicId}>
                        <td>{formatDateTime(charge.performedAt, locale)}</td>
                        <td>
                          {displayName(locale, charge.serviceAr, charge.serviceEn)}
                          <div className="muted mono">{charge.serviceCode}</div>
                        </td>
                        <td>{t(`chargeSource.${charge.sourceType}`)}</td>
                        <td className="mono">{charge.priceSnapshot ?? "—"}</td>
                        <td>{t(`chargeStatus.${charge.status}`)}</td>
                        <td>
                          {displayName(locale, charge.performedByAr, charge.performedByEn)}
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

      {tab === "print" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <h2>{t("encounters.printPreview")}</h2>
              <button
                type="button"
                className="button"
                onClick={() => window.print()}
                disabled={!printPayload.data}
              >
                {t("encounters.print")}
              </button>
            </div>
          </section>
          {printPayload.isPending ? (
            <p className="hint">{t("common.loading")}</p>
          ) : printPayload.isError || !printPayload.data ? (
            <p className="field-error">{t("common.loadError")}</p>
          ) : (
            <PrintSheet payload={printPayload.data} locale={locale} t={t} />
          )}
        </div>
      )}

      {editNote && (
        <Modal title={t("encounters.editNote")} onClose={() => setEditNote(null)}>
          <form
            className="stack-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              saveNote.mutate();
            }}
          >
            <NoteFields
              value={editNote.form}
              onChange={(form) => setEditNote({ ...editNote, form })}
              labels={{
                subjective: t("note.subjective"),
                objective: t("note.objective"),
                assessment: t("note.assessment"),
                plan: t("note.plan"),
              }}
            />
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setEditNote(null)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={saveNote.isPending}>
                {saveNote.isPending ? t("common.saving") : t("common.save")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {amendNote && (
        <Modal title={t("encounters.amendNote")} onClose={() => setAmendNote(null)}>
          <form
            className="stack-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              amendNoteMutation.mutate();
            }}
          >
            <label className="field">
              <span>{t("encounters.amendReason")} *</span>
              <input
                value={amendNote.reason}
                onChange={(event) =>
                  setAmendNote({ ...amendNote, reason: event.target.value })
                }
                required
              />
            </label>
            <NoteFields
              value={amendNote.form}
              onChange={(form) => setAmendNote({ ...amendNote, form })}
              labels={{
                subjective: t("note.subjective"),
                objective: t("note.objective"),
                assessment: t("note.assessment"),
                plan: t("note.plan"),
              }}
            />
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setAmendNote(null)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={amendNoteMutation.isPending}>
                {amendNoteMutation.isPending ? t("common.saving") : t("encounters.amend")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {versionsNote && (
        <Modal title={t("encounters.noteVersions")} onClose={() => setVersionsNote(null)}>
          {versions.isPending ? (
            <p className="hint">{t("common.loading")}</p>
          ) : !versions.data || versions.data.length === 0 ? (
            <p className="empty-inline">{t("encounters.noVersions")}</p>
          ) : (
            <ol className="version-list">
              {versions.data.map((version) => (
                <li key={version.publicId}>
                  <div className="version-head">
                    <strong>v{version.versionNo}</strong>
                    <span className="muted">
                      {formatDateTime(version.createdAt, locale)}
                    </span>
                    {version.signedAt && (
                      <span className="badge badge-success">
                        {t("encounters.signedAt")}: {formatDateTime(version.signedAt, locale)}
                      </span>
                    )}
                  </div>
                  {version.amendReason && (
                    <p className="notice">
                      {t("encounters.amendReason")}: {version.amendReason}
                    </p>
                  )}
                  <pre className="version-text">{version.contentText ?? "—"}</pre>
                </li>
              ))}
            </ol>
          )}
        </Modal>
      )}

      {viewPrescription && (
        <Modal
          title={t("encounters.prescriptionDetails")}
          onClose={() => setViewPrescription(null)}
        >
          {prescription.isPending ? (
            <p className="hint">{t("common.loading")}</p>
          ) : prescription.isError || !prescription.data ? (
            <p className="field-error">{t("common.loadError")}</p>
          ) : (
            <div className="stack-form">
              <div className="chip-row">
                <span className={`badge badge-${statusTone(prescription.data.status)}`}>
                  {t(`prescriptionStatus.${prescription.data.status}`)}
                </span>
                <span className="chip">
                  {formatDateTime(prescription.data.issuedAt, locale)}
                </span>
              </div>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("encounters.drug")}</th>
                    <th>{t("encounters.dose")}</th>
                    <th>{t("encounters.route")}</th>
                    <th>{t("encounters.frequency")}</th>
                    <th>{t("encounters.durationDays")}</th>
                    <th>{t("encounters.quantity")}</th>
                  </tr>
                </thead>
                <tbody>
                  {prescription.data.items.map((item) => (
                    <tr key={item.publicId}>
                      <td>
                        {displayName(locale, item.drugAr, item.drugEn)}
                        <div className="muted mono">{item.drugCode}</div>
                      </td>
                      <td>{item.dose}</td>
                      <td>{t(`route.${item.route}`)}</td>
                      <td>{t(`frequency.${item.frequency}`)}</td>
                      <td>{item.durationDays ?? "—"}</td>
                      <td>{item.quantity}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {prescription.data.notes && (
                <p className="muted">{prescription.data.notes}</p>
              )}
            </div>
          )}
        </Modal>
      )}

      {lifecycleAction && (
        <Modal
          title={
            lifecycleAction === "close"
              ? t("encounters.closeTitle")
              : t("encounters.cancelTitle")
          }
          onClose={() => setLifecycleAction(null)}
        >
          <form
            className="stack-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              if (lifecycleAction === "close") {
                closeEncounter.mutate();
              } else {
                cancelEncounter.mutate();
              }
            }}
          >
            <p className="notice">
              {lifecycleAction === "close"
                ? t("encounters.closeHint")
                : t("encounters.cancelHint")}
            </p>
            <label className="field">
              <span>{t("encounters.reason")}</span>
              <textarea
                rows={3}
                value={lifecycleReason}
                onChange={(event) => setLifecycleReason(event.target.value)}
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setLifecycleAction(null)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className={`button${lifecycleAction === "cancel" ? " button-danger" : ""}`}
                disabled={closeEncounter.isPending || cancelEncounter.isPending}
              >
                {closeEncounter.isPending || cancelEncounter.isPending
                  ? t("common.saving")
                  : t("common.confirm")}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}

function VitalChip({
  label,
  value,
  unit,
}: {
  label: string;
  value: string | number | null | undefined;
  unit: string;
}) {
  return (
    <div className="vital-chip">
      <span className="vital-label">{label}</span>
      <strong>
        {value === null || value === undefined || value === "" ? "—" : value}
      </strong>
      <span className="vital-unit">{unit}</span>
    </div>
  );
}

function VitalField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type="number"
        step="any"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function NoteFields({
  value,
  onChange,
  labels,
}: {
  value: NoteFormState;
  onChange: (value: NoteFormState) => void;
  labels: { subjective: string; objective: string; assessment: string; plan: string };
}) {
  return (
    <div className="note-fields">
      {(["subjective", "objective", "assessment", "plan"] as const).map((key) => (
        <label key={key} className="field">
          <span>{labels[key]}</span>
          <textarea
            rows={3}
            value={value[key]}
            onChange={(event) => onChange({ ...value, [key]: event.target.value })}
          />
        </label>
      ))}
    </div>
  );
}

function PrintSheet({
  payload,
  locale,
  t,
}: {
  payload: PrintPayload;
  locale: Locale;
  t: (key: string) => string;
}) {
  return (
    <section className="card print-sheet">
      <header className="print-header">
        <div>
          <h2>{t("app.name")}</h2>
          <p className="muted">{t("encounters.printTitle")}</p>
        </div>
        <div className="print-meta">
          <div>
            {t("encounters.printedAt")}: {formatDateTime(payload.printedAt, locale)}
          </div>
          <div>
            {t("encounters.printedBy")}:{" "}
            {displayName(locale, payload.printedBy.nameAr, payload.printedBy.nameEn)}
          </div>
        </div>
      </header>

      <div className="print-block">
        <h3>{t("encounters.patientInfo")}</h3>
        <dl className="meta-grid">
          <dt>{t("patients.name")}</dt>
          <dd>{payload.patient.fullName}</dd>
          <dt>{t("patients.medicalNumber")}</dt>
          <dd className="mono">{payload.patient.medicalNumber}</dd>
          <dt>{t("patients.gender")}</dt>
          <dd>{t(`gender.${payload.patient.gender}`)}</dd>
          <dt>{t("patients.age")}</dt>
          <dd>{payload.patient.age ?? "—"}</dd>
          <dt>{t("patients.phone")}</dt>
          <dd className="mono">{payload.patient.phone ?? "—"}</dd>
          <dt>{t("patients.bloodGroup")}</dt>
          <dd>{payload.patient.bloodGroup ?? "—"}</dd>
          <dt>{t("encounters.type")}</dt>
          <dd>{t(`encounterType.${payload.encounter.type}`)}</dd>
          <dt>{t("encounters.startedAt")}</dt>
          <dd>{formatDateTime(payload.encounter.startedAt, locale)}</dd>
          <dt>{t("encounters.provider")}</dt>
          <dd>
            {displayName(locale, payload.encounter.providerAr, payload.encounter.providerEn)}
          </dd>
          <dt>{t("encounters.complaint")}</dt>
          <dd>{payload.encounter.chiefComplaint ?? "—"}</dd>
        </dl>
      </div>

      {payload.allergies.length > 0 && (
        <div className="print-block">
          <h3>{t("patients.allergies")}</h3>
          <p>
            {payload.allergies
              .map(
                (allergy) =>
                  `${allergy.substance} (${t(`severity.${allergy.severity}`)})`,
              )
              .join(" · ")}
          </p>
        </div>
      )}

      {payload.vitals && (
        <div className="print-block">
          <h3>{t("encounters.latestVitals")}</h3>
          <p>
            {t("vitals.temperature")}: {payload.vitals.temperatureC ?? "—"} °C ·{" "}
            {t("vitals.heartRate")}: {payload.vitals.heartRate ?? "—"} ·{" "}
            {t("vitals.bloodPressure")}: {payload.vitals.systolic ?? "—"}/
            {payload.vitals.diastolic ?? "—"} · SpO₂: {payload.vitals.spo2 ?? "—"}%
          </p>
          <p className="muted">
            {t("encounters.recordedAt")}:{" "}
            {formatDateTime(payload.vitals.recordedAt, locale)}
          </p>
        </div>
      )}

      {payload.diagnoses.length > 0 && (
        <div className="print-block">
          <h3>{t("encounters.diagnoses")}</h3>
          <ul className="plain-list">
            {payload.diagnoses.map((diagnosis) => (
              <li key={diagnosis.publicId}>
                {diagnosis.code ? `${diagnosis.code} · ` : ""}
                {diagnosis.description}
              </li>
            ))}
          </ul>
        </div>
      )}

      {payload.orders.length > 0 && (
        <div className="print-block">
          <h3>{t("encounters.orders")}</h3>
          <ul className="plain-list">
            {payload.orders.map((order) => (
              <li key={order.publicId}>
                {t(`orderType.${order.orderType}`)} ·{" "}
                {(locale === "ar" ? order.itemsAr : order.itemsEn) ?? "—"}
              </li>
            ))}
          </ul>
        </div>
      )}

      {payload.prescriptions.length > 0 && (
        <div className="print-block">
          <h3>{t("encounters.prescriptions")}</h3>
          {payload.prescriptions.map((item) => (
            <div key={item.publicId}>
              <ul className="plain-list">
                {item.items.map((line) => (
                  <li key={line.publicId}>
                    {displayName(locale, line.drugAr, line.drugEn)} · {line.dose} ·{" "}
                    {t(`route.${line.route}`)} · {t(`frequency.${line.frequency}`)} ·{" "}
                    {line.durationDays ?? "—"} {t("common.days")}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}

      {payload.notes.length > 0 && (
        <div className="print-block">
          <h3>{t("encounters.notes")}</h3>
          {payload.notes.map((note) => (
            <div key={note.publicId} className="print-note">
              <strong>
                {t(`noteType.${note.noteType}`)} · v{note.versionNo ?? 1}
              </strong>
              <pre className="version-text">{note.contentText ?? "—"}</pre>
            </div>
          ))}
        </div>
      )}

      {payload.charges.length > 0 && (
        <div className="print-block">
          <h3>{t("encounters.charges")}</h3>
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("encounters.service")}</th>
                <th>{t("encounters.price")}</th>
                <th>{t("encounters.performedAt")}</th>
              </tr>
            </thead>
            <tbody>
              {payload.charges.map((charge) => (
                <tr key={charge.publicId}>
                  <td>{displayName(locale, charge.serviceAr, charge.serviceEn)}</td>
                  <td className="mono">{charge.priceSnapshot}</td>
                  <td>{formatDateTime(charge.performedAt, locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <footer className="print-footer">
        {t("footer.note")} · {formatTime(payload.printedAt, locale)}
      </footer>
    </section>
  );
}
