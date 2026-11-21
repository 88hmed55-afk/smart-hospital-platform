import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n, type Locale } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { formatDateTime } from "../../shared/ui/format";
import { adtApi } from "../adt/adtApi";
import {
  ADMIN_STATUSES,
  TASK_STATUSES,
  TASK_TYPES,
  nursingApi,
  type AdmissionTaskRow,
  type NursingTask,
  type VitalsInput,
  type WardPatient,
} from "./nursingApi";

const TASK_PRIORITIES = ["NORMAL", "URGENT", "STAT"];

function personName(
  locale: Locale,
  ar: string | null | undefined,
  en: string | null | undefined,
): string {
  return locale === "ar" ? ar || en || "—" : en || ar || "—";
}

function riskBadgeClass(risk: string | null | undefined): string {
  switch (risk) {
    case "HIGH":
      return "badge-danger";
    case "MEDIUM":
      return "badge-warn";
    case "LOW":
      return "badge-success";
    default:
      return "badge-muted";
  }
}

function taskBadgeClass(status: string): string {
  switch (status) {
    case "COMPLETED":
      return "badge-success";
    case "IN_PROGRESS":
      return "badge-info";
    case "CANCELLED":
      return "badge-muted";
    default:
      return "badge-warn";
  }
}

export function NursingPage() {
  const { t, locale } = useI18n();
  const { hasAnyPermission, user } = useAuth();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<"wards" | "tasks">("wards");
  const [wardId, setWardId] = useState("");
  const [workspaceId, setWorkspaceId] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [taskType, setTaskType] = useState("");
  const [taskWard, setTaskWard] = useState("");
  const [page, setPage] = useState(0);
  const [actionTask, setActionTask] = useState<{
    task: NursingTask;
    mode: "complete" | "cancel";
  } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);

  const canWrite = hasAnyPermission("NUR:WRITE");
  const canMed = hasAnyPermission("NUR:MED", "MED:ADMINISTER");

  const wards = useQuery({
    queryKey: ["wards"],
    queryFn: () => adtApi.wards(),
    staleTime: 300_000,
  });
  const patients = useQuery({
    queryKey: ["ward-patients", wardId],
    queryFn: () => nursingApi.wardPatients(wardId),
    enabled: Boolean(wardId),
    refetchInterval: 30_000,
  });
  const tasks = useQuery({
    queryKey: ["nursing-tasks", status, taskType, taskWard, page],
    queryFn: () =>
      nursingApi.tasks({
        status: status || undefined,
        type: taskType || undefined,
        ward: taskWard || undefined,
        page,
        size: 20,
      }),
  });

  useEffect(() => {
    if (!wardId && wards.data && wards.data.length > 0) {
      setWardId(wards.data[0].publicId);
    }
  }, [wards.data, wardId]);

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["ward-patients"] }),
      queryClient.invalidateQueries({ queryKey: ["nursing-tasks"] }),
      queryClient.invalidateQueries({ queryKey: ["nursing-overview"] }),
    ]);
  };

  const taskAction = useMutation({
    mutationFn: (payload: {
      task: NursingTask;
      mode: "assign" | "start" | "complete" | "cancel";
      completionNote?: string;
      reason?: string;
    }) => {
      if (payload.mode === "assign") {
        return nursingApi.assignTask(payload.task.publicId, {
          version: payload.task.version,
          assignedToPublicId: user?.publicId ?? "",
        });
      }
      if (payload.mode === "start") {
        return nursingApi.startTask(payload.task.publicId, {
          version: payload.task.version,
        });
      }
      if (payload.mode === "complete") {
        return nursingApi.completeTask(payload.task.publicId, {
          version: payload.task.version,
          completionNote: payload.completionNote,
        });
      }
      return nursingApi.cancelTask(payload.task.publicId, {
        version: payload.task.version,
        reason: payload.reason ?? "",
      });
    },
    onSuccess: async () => {
      setActionTask(null);
      setActionError(null);
      setNotice(t("nur.taskSaved"));
      await invalidate();
    },
    onError: (error) => setActionError(error),
  });

  const currentWard = wards.data?.find((ward) => ward.publicId === wardId);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("nur.title")}</h1>
          <p className="muted">{t("nur.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              void patients.refetch();
              void tasks.refetch();
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
        <button
          type="button"
          className={`tab${tab === "wards" ? " is-active" : ""}`}
          onClick={() => setTab("wards")}
        >
          {t("nur.tab.wards")}
        </button>
        <button
          type="button"
          className={`tab${tab === "tasks" ? " is-active" : ""}`}
          onClick={() => setTab("tasks")}
        >
          {t("nur.tab.tasks")}
        </button>
      </div>

      {tab === "wards" && (
        <div className="tab-content">
          <section className="card">
            <div className="toolbar">
              <label className="field field-inline">
                <span>{t("adt.ward")}</span>
                <select
                  value={wardId}
                  onChange={(event) => setWardId(event.target.value)}
                >
                  {wards.data?.map((ward) => (
                    <option key={ward.publicId} value={ward.publicId}>
                      {personName(locale, ward.nameAr, ward.nameEn)}
                    </option>
                  ))}
                </select>
              </label>
              {currentWard && (
                <span className="muted">
                  {t("nur.patients")}: {patients.data?.count ?? 0}
                  {currentWard.genderPolicy ? ` · ${currentWard.genderPolicy}` : ""}
                </span>
              )}
            </div>

            {!wardId ? (
              <p className="empty-inline">{t("nur.selectWard")}</p>
            ) : patients.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : patients.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : patients.data && patients.data.patients.length === 0 ? (
              <p className="empty-inline">{t("nur.noPatients")}</p>
            ) : (
              <div className="ward-patient-grid">
                {patients.data?.patients.map((patient) => (
                  <PatientCard
                    key={patient.admissionPublicId}
                    patient={patient}
                    onOpen={() => setWorkspaceId(patient.admissionPublicId)}
                  />
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {tab === "tasks" && (
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
                  {TASK_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {t(`taskStatus.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("nur.taskType")}</span>
                <select
                  value={taskType}
                  onChange={(event) => {
                    setTaskType(event.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">{t("common.all")}</option>
                  {TASK_TYPES.map((value) => (
                    <option key={value} value={value}>
                      {t(`taskType.${value}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field field-inline">
                <span>{t("adt.ward")}</span>
                <select
                  value={taskWard}
                  onChange={(event) => {
                    setTaskWard(event.target.value);
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
            </div>

            {tasks.isPending ? (
              <p className="muted">{t("common.loading")}</p>
            ) : tasks.isError ? (
              <div className="alert alert-danger">{t("common.loadError")}</div>
            ) : tasks.data && tasks.data.items.length === 0 ? (
              <p className="empty-inline">{t("nur.emptyTasks")}</p>
            ) : (
              <>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("adt.patient")}</th>
                        <th>{t("nur.taskTitle")}</th>
                        <th>{t("nur.taskType")}</th>
                        <th>{t("common.priority")}</th>
                        <th>{t("adt.status")}</th>
                        <th>{t("nur.dueAt")}</th>
                        <th>{t("nur.assignedTo")}</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {tasks.data?.items.map((task) => (
                        <tr key={task.publicId}>
                          <td>
                            {task.patient.fullName}
                            <span className="muted mono">
                              {" "}
                              · {task.patient.medicalNumber}
                            </span>
                            {task.bedCode && (
                              <span className="muted"> · {task.bedCode}</span>
                            )}
                          </td>
                          <td>{task.title}</td>
                          <td>{t(`taskType.${task.taskType}`)}</td>
                          <td>{t(`priority.${task.priority}`)}</td>
                          <td>
                            <span className={`badge ${taskBadgeClass(task.status)}`}>
                              {t(`taskStatus.${task.status}`)}
                            </span>
                          </td>
                          <td>{formatDateTime(task.dueAt, locale)}</td>
                          <td>
                            {task.assignedTo
                              ? personName(
                                  locale,
                                  task.assignedTo.nameAr,
                                  task.assignedTo.nameEn,
                                )
                              : t("nur.unassigned")}
                          </td>
                          <td className="cell-actions">
                            {canWrite && !task.assignedTo && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() =>
                                  taskAction.mutate({ task, mode: "assign" })
                                }
                              >
                                {t("nur.assign")}
                              </button>
                            )}
                            {canWrite && task.status === "ASSIGNED" && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() =>
                                  taskAction.mutate({ task, mode: "start" })
                                }
                              >
                                {t("nur.start")}
                              </button>
                            )}
                            {canWrite &&
                              (task.status === "ASSIGNED" ||
                                task.status === "IN_PROGRESS") && (
                                <>
                                  <button
                                    type="button"
                                    className="button button-sm"
                                    onClick={() =>
                                      setActionTask({ task, mode: "complete" })
                                    }
                                  >
                                    {t("nur.complete")}
                                  </button>
                                  <button
                                    type="button"
                                    className="button button-ghost button-sm"
                                    onClick={() =>
                                      setActionTask({ task, mode: "cancel" })
                                    }
                                  >
                                    {t("nur.cancel")}
                                  </button>
                                </>
                              )}
                            {task.admissionPublicId && (
                              <button
                                type="button"
                                className="button button-ghost button-sm"
                                onClick={() =>
                                  setWorkspaceId(task.admissionPublicId)
                                }
                              >
                                {t("nur.workspace")}
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {tasks.data && (
                  <Pagination
                    page={tasks.data.page}
                    totalPages={tasks.data.totalPages}
                    total={tasks.data.total}
                    onChange={setPage}
                  />
                )}
              </>
            )}
          </section>
        </div>
      )}

      {workspaceId && (
        <WorkspaceModal
          admissionPublicId={workspaceId}
          canWrite={canWrite}
          canMed={canMed}
          currentUserPublicId={user?.publicId ?? null}
          onClose={() => setWorkspaceId(null)}
          onChanged={async (message) => {
            setNotice(message);
            setActionError(null);
            await invalidate();
          }}
          onError={(error) => setActionError(error)}
        />
      )}

      {actionTask && (
        <TaskActionModal
          task={actionTask.task}
          mode={actionTask.mode}
          pending={taskAction.isPending}
          error={taskAction.error}
          onClose={() => setActionTask(null)}
          onSubmit={(values) =>
            taskAction.mutate({
              task: actionTask.task,
              mode: actionTask.mode,
              completionNote: values.completionNote,
              reason: values.reason,
            })
          }
        />
      )}
    </div>
  );
}

function PatientCard({
  patient,
  onOpen,
}: {
  patient: WardPatient;
  onOpen: () => void;
}) {
  const { t, locale } = useI18n();

  return (
    <article className="ward-patient-card">
      <header>
        <div>
          <strong>{patient.patientName}</strong>
          <span className="muted mono"> · {patient.medicalNumber}</span>
        </div>
        <span className={`badge ${riskBadgeClass(patient.ewsRisk)}`}>
          {patient.ewsRisk
            ? t(`ewsRisk.${patient.ewsRisk}`)
            : t("nur.risk")}
        </span>
      </header>
      <p className="muted">
        {patient.bedCode ? `${t("adt.bed")}: ${patient.bedCode}` : "—"}
        {patient.age !== null ? ` · ${patient.age} ${t("common.years")}` : ""}
      </p>
      <dl className="meta-grid">
        <div>
          <dt>{t("nur.ewsScore")}</dt>
          <dd>{patient.ewsScore ?? "—"}</dd>
        </div>
        <div>
          <dt>{t("nur.openTasks")}</dt>
          <dd>{patient.openTasks ?? 0}</dd>
        </div>
        <div>
          <dt>{t("nur.admittedAt")}</dt>
          <dd>{formatDateTime(patient.admittedAt, locale)}</dd>
        </div>
      </dl>
      {patient.latestVitals && (
        <p className="muted">
          {t("vitals.heartRate")}: {patient.latestVitals.heartRate ?? "—"} ·{" "}
          {t("vitals.spo2")}: {patient.latestVitals.spo2 ?? "—"} ·{" "}
          {t("vitals.temperature")}: {patient.latestVitals.temperatureC ?? "—"}
        </p>
      )}
      <button type="button" className="button button-sm" onClick={onOpen}>
        {t("nur.workspace")}
      </button>
    </article>
  );
}

type WorkspacePanel =
  | { kind: "vitals" }
  | { kind: "medication" }
  | { kind: "task" }
  | { kind: "complete"; task: AdmissionTaskRow }
  | { kind: "cancel"; task: AdmissionTaskRow }
  | null;

function WorkspaceModal({
  admissionPublicId,
  canWrite,
  canMed,
  currentUserPublicId,
  onClose,
  onChanged,
  onError,
}: {
  admissionPublicId: string;
  canWrite: boolean;
  canMed: boolean;
  currentUserPublicId: string | null;
  onClose: () => void;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [panel, setPanel] = useState<WorkspacePanel>(null);

  const overview = useQuery({
    queryKey: ["nursing-overview", admissionPublicId],
    queryFn: () => nursingApi.overview(admissionPublicId),
  });

  const refresh = async (message: string) => {
    setPanel(null);
    await onChanged(message);
    await queryClient.invalidateQueries({
      queryKey: ["nursing-overview", admissionPublicId],
    });
  };

  const taskAction = useMutation({
    mutationFn: (payload: {
      task: AdmissionTaskRow;
      mode: "assign" | "start" | "complete" | "cancel";
      completionNote?: string;
      reason?: string;
    }) => {
      if (payload.mode === "assign") {
        return nursingApi.assignTask(payload.task.publicId, {
          version: payload.task.version,
          assignedToPublicId: currentUserPublicId ?? "",
        });
      }
      if (payload.mode === "start") {
        return nursingApi.startTask(payload.task.publicId, {
          version: payload.task.version,
        });
      }
      if (payload.mode === "complete") {
        return nursingApi.completeTask(payload.task.publicId, {
          version: payload.task.version,
          completionNote: payload.completionNote,
        });
      }
      return nursingApi.cancelTask(payload.task.publicId, {
        version: payload.task.version,
        reason: payload.reason ?? "",
      });
    },
    onSuccess: () => void refresh(t("nur.taskSaved")),
    onError,
  });

  const createTask = useMutation({
    mutationFn: (body: {
      taskType: string;
      priority: string;
      title: string;
      description?: string;
      dueAt?: string;
      assignedToPublicId?: string;
    }) =>
      nursingApi.createTask({
        ...body,
        admissionPublicId,
      }),
    onSuccess: () => void refresh(t("nur.taskSaved")),
    onError,
  });

  const recordVitals = useMutation({
    mutationFn: (body: VitalsInput) =>
      nursingApi.recordVitals(admissionPublicId, body),
    onSuccess: () => void refresh(t("nur.vitalsSaved")),
    onError,
  });

  const administer = useMutation({
    mutationFn: (body: {
      prescriptionItemPublicId: string;
      status: string;
      barcodePatient?: string;
      barcodeDrug?: string;
      barcodeBatch?: string;
      notes?: string;
    }) =>
      nursingApi.administer({
        ...body,
        admissionPublicId,
      }),
    onSuccess: () => void refresh(t("nur.medSaved")),
    onError,
  });

  const data = overview.data;

  return (
    <Modal title={t("nur.workspace")} onClose={onClose}>
      {overview.isPending ? (
        <p className="muted">{t("common.loading")}</p>
      ) : overview.isError || !data ? (
        <div className="alert alert-danger">{t("common.loadError")}</div>
      ) : panel?.kind === "vitals" ? (
        <VitalsForm
          pending={recordVitals.isPending}
          error={recordVitals.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => recordVitals.mutate(body)}
        />
      ) : panel?.kind === "medication" ? (
        <MedicationForm
          patientPublicId={data.admission.patient.publicId}
          pending={administer.isPending}
          error={administer.error}
          onBack={() => setPanel(null)}
          onSubmit={(body) => administer.mutate(body)}
        />
      ) : panel?.kind === "task" ? (
        <TaskForm
          pending={createTask.isPending}
          error={createTask.error}
          currentUserPublicId={currentUserPublicId}
          onBack={() => setPanel(null)}
          onSubmit={(body) => createTask.mutate(body)}
        />
      ) : panel?.kind === "complete" || panel?.kind === "cancel" ? (
        <TaskActionForm
          mode={panel.kind}
          pending={taskAction.isPending}
          error={taskAction.error}
          onBack={() => setPanel(null)}
          onSubmit={(values) =>
            taskAction.mutate({
              task: panel.task,
              mode: panel.kind,
              completionNote: values.completionNote,
              reason: values.reason,
            })
          }
        />
      ) : (
        <div className="stack-form">
          <div className="detail-grid">
            <div>
              <span className="muted">{t("adt.patient")}</span>
              <strong>{data.admission.patient.fullName}</strong>
              <span className="muted mono">
                {" "}
                · {data.admission.patient.medicalNumber}
              </span>
            </div>
            <div>
              <span className="muted">{t("adt.ward")}</span>
              <strong>
                {personName(
                  locale,
                  data.admission.ward?.nameAr,
                  data.admission.ward?.nameEn,
                )}
              </strong>
            </div>
            <div>
              <span className="muted">{t("adt.bed")}</span>
              <strong>{data.admission.bed?.code ?? "—"}</strong>
            </div>
            <div>
              <span className="muted">{t("nur.admittedAt")}</span>
              <strong>{formatDateTime(data.admission.admittedAt, locale)}</strong>
            </div>
            <div>
              <span className="muted">{t("adt.reason")}</span>
              <strong>{data.admission.reason ?? "—"}</strong>
            </div>
            <div>
              <span className="muted">{t("nur.ews")}</span>
              <strong>
                {data.ews
                  ? `${data.ews.score} · ${t(`ewsRisk.${data.ews.riskLevel}`)}`
                  : "—"}
              </strong>
            </div>
          </div>

          <div className="form-actions form-actions-start">
            {canWrite && (
              <button
                type="button"
                className="button"
                onClick={() => setPanel({ kind: "task" })}
              >
                {t("nur.newTask")}
              </button>
            )}
            {canWrite && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setPanel({ kind: "vitals" })}
              >
                {t("nur.recordVitals")}
              </button>
            )}
            {canMed && (
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setPanel({ kind: "medication" })}
              >
                {t("nur.medication")}
              </button>
            )}
          </div>

          <section>
            <h3>{t("nur.tasks")}</h3>
            {data.tasks.length === 0 ? (
              <p className="empty-inline">{t("nur.noTasks")}</p>
            ) : (
              <ul className="board-list">
                {data.tasks.map((task) => (
                  <li className="board-item" key={task.publicId}>
                    <div className="board-item-head">
                      <strong>{task.title}</strong>
                      <span className="badge badge-muted">
                        {t(`taskType.${task.taskType}`)}
                      </span>
                      <span className={`badge ${taskBadgeClass(task.status)}`}>
                        {t(`taskStatus.${task.status}`)}
                      </span>
                      <span className="muted">{t(`priority.${task.priority}`)}</span>
                    </div>
                    <p className="muted">
                      {t("nur.dueAt")}: {formatDateTime(task.dueAt, locale)}
                      {task.assignedToAr || task.assignedToEn
                        ? ` · ${personName(locale, task.assignedToAr, task.assignedToEn)}`
                        : ` · ${t("nur.unassigned")}`}
                    </p>
                    {task.description && <p>{task.description}</p>}
                    {canWrite &&
                      (task.status === "PENDING" ||
                        task.status === "ASSIGNED" ||
                        task.status === "IN_PROGRESS") && (
                        <div className="board-item-actions">
                          {!task.assignedTo && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() =>
                                taskAction.mutate({ task, mode: "assign" })
                              }
                            >
                              {t("nur.assign")}
                            </button>
                          )}
                          {task.status === "ASSIGNED" && (
                            <button
                              type="button"
                              className="button button-ghost button-sm"
                              onClick={() =>
                                taskAction.mutate({ task, mode: "start" })
                              }
                            >
                              {t("nur.start")}
                            </button>
                          )}
                          {task.status !== "PENDING" && (
                            <button
                              type="button"
                              className="button button-sm"
                              onClick={() =>
                                setPanel({ kind: "complete", task })
                              }
                            >
                              {t("nur.complete")}
                            </button>
                          )}
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => setPanel({ kind: "cancel", task })}
                          >
                            {t("nur.cancel")}
                          </button>
                        </div>
                      )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <h3>{t("nur.vitals")}</h3>
            {data.vitals.length === 0 ? (
              <p className="empty-inline">{t("nur.noVitals")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("common.date")}</th>
                      <th>{t("vitals.temperature")}</th>
                      <th>{t("vitals.heartRate")}</th>
                      <th>{t("vitals.respiratoryRate")}</th>
                      <th>{t("vitals.bloodPressure")}</th>
                      <th>{t("vitals.spo2")}</th>
                      <th>{t("vitals.pain")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.vitals.map((row) => (
                      <tr key={row.publicId}>
                        <td>{formatDateTime(row.recordedAt, locale)}</td>
                        <td>{row.temperatureC ?? "—"}</td>
                        <td>{row.heartRate ?? "—"}</td>
                        <td>{row.respiratoryRate ?? "—"}</td>
                        <td>
                          {row.systolic ?? "—"}/{row.diastolic ?? "—"}
                        </td>
                        <td>{row.spo2 ?? "—"}</td>
                        <td>{row.painScore ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section>
            <h3>{t("nur.administrations")}</h3>
            {data.administrations.length === 0 ? (
              <p className="empty-inline">{t("nur.noAdministrations")}</p>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("common.date")}</th>
                      <th>{t("phr.drug")}</th>
                      <th>{t("phr.batch")}</th>
                      <th>{t("nur.dose")}</th>
                      <th>{t("nur.adminStatus")}</th>
                      <th>{t("nur.assignedTo")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.administrations.map((row) => (
                      <tr key={row.publicId}>
                        <td>{formatDateTime(row.administeredAt, locale)}</td>
                        <td>{personName(locale, row.drugAr, row.drugEn)}</td>
                        <td className="mono">{row.batchNumber}</td>
                        <td>
                          {row.dose} · {row.route}
                        </td>
                        <td>
                          <span className="badge badge-muted">
                            {t(`medStatus.${row.status}`)}
                          </span>
                        </td>
                        <td>
                          {personName(
                            locale,
                            row.administeredByAr,
                            row.administeredByEn,
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
    </Modal>
  );
}

function VitalsForm({
  pending,
  error,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: VitalsInput) => void;
}) {
  const { t, locale } = useI18n();
  const [values, setValues] = useState<Record<string, string>>({});

  function number(key: string): number | undefined {
    const raw = values[key];
    if (!raw || raw.trim() === "") {
      return undefined;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      temperatureC: number("temperatureC"),
      heartRate: number("heartRate"),
      respiratoryRate: number("respiratoryRate"),
      systolic: number("systolic"),
      diastolic: number("diastolic"),
      spo2: number("spo2"),
      weightKg: number("weightKg"),
      heightCm: number("heightCm"),
      painScore: number("painScore"),
    });
  }

  const fields: [string, string][] = [
    ["temperatureC", t("vitals.temperature")],
    ["heartRate", t("vitals.heartRate")],
    ["respiratoryRate", t("vitals.respiratoryRate")],
    ["systolic", t("vitals.systolic")],
    ["diastolic", t("vitals.diastolic")],
    ["spo2", t("vitals.spo2")],
    ["weightKg", t("vitals.weight")],
    ["heightCm", t("vitals.height")],
    ["painScore", t("vitals.pain")],
  ];

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("nur.recordVitals")}</h3>
      <div className="vital-grid">
        {fields.map(([key, label]) => (
          <label className="field" key={key}>
            <span>{label}</span>
            <input
              type="number"
              step="any"
              value={values[key] ?? ""}
              onChange={(event) =>
                setValues((current) => ({
                  ...current,
                  [key]: event.target.value,
                }))
              }
            />
          </label>
        ))}
      </div>
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

function MedicationForm({
  patientPublicId,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  patientPublicId: string;
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (body: {
    prescriptionItemPublicId: string;
    status: string;
    barcodePatient?: string;
    barcodeDrug?: string;
    barcodeBatch?: string;
    notes?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [prescriptionId, setPrescriptionId] = useState("");
  const [itemId, setItemId] = useState("");
  const [status, setStatus] = useState("GIVEN");
  const [barcodePatient, setBarcodePatient] = useState("");
  const [barcodeDrug, setBarcodeDrug] = useState("");
  const [barcodeBatch, setBarcodeBatch] = useState("");
  const [notes, setNotes] = useState("");

  const prescriptions = useQuery({
    queryKey: ["patient-prescriptions", patientPublicId],
    queryFn: () => nursingApi.patientPrescriptions(patientPublicId),
  });
  const detail = useQuery({
    queryKey: ["prescription-detail", prescriptionId],
    queryFn: () => nursingApi.prescription(prescriptionId),
    enabled: Boolean(prescriptionId),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!itemId) {
      return;
    }
    onSubmit({
      prescriptionItemPublicId: itemId,
      status,
      barcodePatient: barcodePatient.trim() || undefined,
      barcodeDrug: barcodeDrug.trim() || undefined,
      barcodeBatch: barcodeBatch.trim() || undefined,
      notes: notes.trim() || undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("nur.medicationTitle")}</h3>
      <p className="muted">{t("nur.barcodeHint")}</p>
      <label className="field">
        <span>{t("nur.prescription")}</span>
        <select
          value={prescriptionId}
          onChange={(event) => {
            setPrescriptionId(event.target.value);
            setItemId("");
          }}
        >
          <option value="">{t("common.select")}</option>
          {prescriptions.data?.map((row) => (
            <option key={row.publicId} value={row.publicId}>
              {row.publicId} · {t(`phrStatus.${row.status}`)} ·{" "}
              {formatDateTime(row.at, locale)}
            </option>
          ))}
        </select>
        {prescriptions.data && prescriptions.data.length === 0 && (
          <span className="field-hint">{t("nur.noPrescriptions")}</span>
        )}
      </label>
      <label className="field">
        <span>{t("nur.prescriptionItem")}</span>
        <select
          value={itemId}
          onChange={(event) => setItemId(event.target.value)}
          disabled={!detail.data}
        >
          <option value="">{t("common.select")}</option>
          {detail.data?.items.map((item) => (
            <option key={item.publicId} value={item.publicId}>
              {personName(locale, item.drugAr, item.drugEn)} · {item.dose} ·{" "}
              {item.frequency ?? ""}
            </option>
          ))}
        </select>
        {!prescriptionId && (
          <span className="field-hint">{t("nur.choosePrescription")}</span>
        )}
      </label>
      <label className="field">
        <span>{t("nur.adminStatus")}</span>
        <select value={status} onChange={(event) => setStatus(event.target.value)}>
          {ADMIN_STATUSES.map((value) => (
            <option key={value} value={value}>
              {t(`medStatus.${value}`)}
            </option>
          ))}
        </select>
      </label>
      <div className="form-grid">
        <label className="field">
          <span>{t("nur.barcodePatient")}</span>
          <input
            type="text"
            value={barcodePatient}
            onChange={(event) => setBarcodePatient(event.target.value)}
            required={status === "GIVEN"}
          />
        </label>
        <label className="field">
          <span>{t("nur.barcodeDrug")}</span>
          <input
            type="text"
            value={barcodeDrug}
            onChange={(event) => setBarcodeDrug(event.target.value)}
            required={status === "GIVEN"}
          />
        </label>
        <label className="field">
          <span>{t("nur.barcodeBatch")}</span>
          <input
            type="text"
            value={barcodeBatch}
            onChange={(event) => setBarcodeBatch(event.target.value)}
            required={status === "GIVEN"}
          />
        </label>
      </div>
      <label className="field">
        <span>{t("nur.notes")}</span>
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
        <button type="button" className="button button-ghost" onClick={onBack}>
          {t("common.back")}
        </button>
        <button type="submit" className="button" disabled={!itemId || pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function TaskForm({
  pending,
  error,
  currentUserPublicId,
  onBack,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  currentUserPublicId: string | null;
  onBack: () => void;
  onSubmit: (body: {
    taskType: string;
    priority: string;
    title: string;
    description?: string;
    dueAt?: string;
    assignedToPublicId?: string;
  }) => void;
}) {
  const { t, locale } = useI18n();
  const [taskType, setTaskType] = useState("OTHER");
  const [priority, setPriority] = useState("NORMAL");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [assignToMe, setAssignToMe] = useState(false);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      taskType,
      priority,
      title: title.trim(),
      description: description.trim() || undefined,
      dueAt: dueAt || undefined,
      assignedToPublicId:
        assignToMe && currentUserPublicId ? currentUserPublicId : undefined,
    });
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{t("nur.newTaskTitle")}</h3>
      <div className="form-grid">
        <label className="field">
          <span>{t("nur.taskType")}</span>
          <select
            value={taskType}
            onChange={(event) => setTaskType(event.target.value)}
          >
            {TASK_TYPES.map((value) => (
              <option key={value} value={value}>
                {t(`taskType.${value}`)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t("common.priority")}</span>
          <select
            value={priority}
            onChange={(event) => setPriority(event.target.value)}
          >
            {TASK_PRIORITIES.map((value) => (
              <option key={value} value={value}>
                {t(`priority.${value}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label className="field">
        <span>{t("nur.taskTitle")}</span>
        <input
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          maxLength={200}
          required
        />
      </label>
      <label className="field">
        <span>{t("nur.taskDescription")}</span>
        <textarea
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          rows={2}
          maxLength={1000}
        />
      </label>
      <label className="field">
        <span>{t("nur.dueAt")}</span>
        <input
          type="datetime-local"
          value={dueAt}
          onChange={(event) => setDueAt(event.target.value)}
        />
      </label>
      {currentUserPublicId && (
        <label className="field field-checkbox">
          <input
            type="checkbox"
            checked={assignToMe}
            onChange={(event) => setAssignToMe(event.target.checked)}
          />
          <span>{t("nur.assign")}</span>
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
        <button type="submit" className="button" disabled={!title.trim() || pending}>
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function TaskActionForm({
  mode,
  pending,
  error,
  onBack,
  onSubmit,
}: {
  mode: "complete" | "cancel";
  pending: boolean;
  error: unknown;
  onBack: () => void;
  onSubmit: (values: { completionNote?: string; reason?: string }) => void;
}) {
  const { t, locale } = useI18n();
  const [text, setText] = useState("");

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit(
      mode === "complete"
        ? { completionNote: text.trim() || undefined }
        : { reason: text.trim() },
    );
  }

  return (
    <form className="stack-form" onSubmit={submit}>
      <h3>{mode === "complete" ? t("nur.complete") : t("nur.cancel")}</h3>
      <label className="field">
        <span>
          {mode === "complete" ? t("nur.completionNote") : t("nur.cancelReason")}
        </span>
        <textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          rows={3}
          maxLength={1000}
          required={mode === "cancel"}
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
          disabled={pending || (mode === "cancel" && !text.trim())}
        >
          {pending ? t("common.saving") : t("common.save")}
        </button>
      </div>
    </form>
  );
}

function TaskActionModal({
  task,
  mode,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  task: NursingTask;
  mode: "complete" | "cancel";
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (values: { completionNote?: string; reason?: string }) => void;
}) {
  const { t } = useI18n();

  return (
    <Modal
      title={mode === "complete" ? t("nur.complete") : t("nur.cancel")}
      onClose={onClose}
    >
      <div className="stack-form">
        <p className="muted">{task.title}</p>
        <TaskActionForm
          mode={mode}
          pending={pending}
          error={error}
          onBack={onClose}
          onSubmit={onSubmit}
        />
      </div>
    </Modal>
  );
}
