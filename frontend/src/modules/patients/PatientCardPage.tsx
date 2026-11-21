import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDate, formatDateTime } from "../../shared/ui/format";
import {
  patientsApi,
  type PatientCard,
  type PatientInput,
  type PatientSummary,
} from "./patientsApi";
import { clinicalApi } from "../clinical/clinicalApi";
import { statusTone } from "./PatientsPage";

const GENDERS = ["MALE", "FEMALE", "UNKNOWN"];
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];
const MARITAL = ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "UNKNOWN"];
const ALLERGY_TYPES = ["DRUG", "FOOD", "ENVIRONMENT", "OTHER"];
const SEVERITIES = ["MILD", "MODERATE", "SEVERE", "LIFE_THREATENING"];

interface EditForm {
  fullName: string;
  gender: string;
  birthDate: string;
  phone: string;
  email: string;
  address: string;
  bloodGroup: string;
  maritalStatus: string;
  notes: string;
}

interface AllergyForm {
  substance: string;
  allergyType: string;
  severity: string;
  reaction: string;
}

export function PatientCardPage() {
  const { publicId = "" } = useParams();
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const canUpdate = hasPermission("PAT:UPDATE");
  const canMerge = hasPermission("PAT:MERGE_APPROVE");
  const canOpenEncounter = hasPermission("EMR:CREATE");

  const [editOpen, setEditOpen] = useState(false);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [allergyOpen, setAllergyOpen] = useState(false);
  const [editForm, setEditForm] = useState<EditForm | null>(null);
  const [allergyForm, setAllergyForm] = useState<AllergyForm>({
    substance: "",
    allergyType: "DRUG",
    severity: "MODERATE",
    reaction: "",
  });
  const [mergeTarget, setMergeTarget] = useState<PatientSummary | null>(null);
  const [mergeReason, setMergeReason] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  const card = useQuery({
    queryKey: ["patient", publicId],
    queryFn: () => patientsApi.get(publicId),
    enabled: publicId.length > 0,
  });

  const timeline = useQuery({
    queryKey: ["patient-timeline", publicId],
    queryFn: () => patientsApi.timeline(publicId),
    enabled: publicId.length > 0,
  });

  const prescriptions = useQuery({
    queryKey: ["patient-prescriptions", publicId],
    queryFn: () => patientsApi.prescriptions(publicId),
    enabled: publicId.length > 0,
  });

  const update = useMutation({
    mutationFn: (body: Partial<PatientInput> & { version: number }) =>
      patientsApi.update(publicId, body),
    onSuccess: (updated) => {
      queryClient.setQueryData(["patient", publicId], updated);
      void queryClient.invalidateQueries({ queryKey: ["patients"] });
      setEditOpen(false);
    },
    onError: (cause) => setActionError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const merge = useMutation({
    mutationFn: () =>
      patientsApi.merge(publicId, mergeTarget?.publicId ?? "", mergeReason.trim()),
    onSuccess: (target) => {
      void queryClient.invalidateQueries({ queryKey: ["patients"] });
      void queryClient.invalidateQueries({ queryKey: ["patient", publicId] });
      setMergeOpen(false);
      navigate(`/app/patients/${target.publicId}`);
    },
    onError: (cause) => setActionError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const addAllergy = useMutation({
    mutationFn: () =>
      patientsApi.addAllergy(publicId, {
        substance: allergyForm.substance.trim(),
        allergyType: allergyForm.allergyType,
        severity: allergyForm.severity,
        reaction: allergyForm.reaction.trim() || null,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["patient", publicId] });
      setAllergyOpen(false);
      setAllergyForm({
        substance: "",
        allergyType: "DRUG",
        severity: "MODERATE",
        reaction: "",
      });
    },
    onError: (cause) => setActionError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const removeAllergy = useMutation({
    mutationFn: (allergyPublicId: string) =>
      patientsApi.removeAllergy(publicId, allergyPublicId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["patient", publicId] });
    },
    onError: (cause) => setActionError(describeApiError(cause, locale, t("common.saveError"))),
  });

  const openEncounter = useMutation({
    mutationFn: () => clinicalApi.create({ patientPublicId: publicId }),
    onSuccess: (workspace) =>
      navigate(`/app/clinical/${workspace.encounter.publicId}`),
    onError: (cause) => setActionError(describeApiError(cause, locale, t("common.saveError"))),
  });

  if (card.isPending) {
    return <p className="hint">{t("common.loading")}</p>;
  }
  if (card.isError || !card.data) {
    return <p className="field-error">{t("patients.notFound")}</p>;
  }

  const patient: PatientCard = card.data;
  const severe = patient.allergies.some(
    (allergy) =>
      allergy.severity === "SEVERE" || allergy.severity === "LIFE_THREATENING",
  );

  function openEdit() {
    setActionError(null);
    setEditForm({
      fullName: patient.fullName,
      gender: patient.gender,
      birthDate: patient.birthDate ?? "",
      phone: patient.phone ?? "",
      email: patient.email ?? "",
      address: patient.address ?? "",
      bloodGroup: patient.bloodGroup ?? "",
      maritalStatus: patient.maritalStatus ?? "UNKNOWN",
      notes: patient.notes ?? "",
    });
    setEditOpen(true);
  }

  function submitEdit(event: FormEvent) {
    event.preventDefault();
    if (!editForm) {
      return;
    }
    setActionError(null);
    update.mutate({
      fullName: editForm.fullName.trim(),
      gender: editForm.gender,
      birthDate: editForm.birthDate || null,
      phone: editForm.phone.trim() || null,
      email: editForm.email.trim() || null,
      address: editForm.address.trim() || null,
      bloodGroup: editForm.bloodGroup || null,
      maritalStatus: editForm.maritalStatus || null,
      notes: editForm.notes.trim() || null,
      version: patient.version,
    });
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <Link className="back-link" to="/app/patients">
            <span className="back-arrow">←</span> {t("patients.backToList")}
          </Link>
          <h1>{patient.fullName}</h1>
          <p className="muted">
            <span className="mono">{patient.medicalNumber}</span>
            {" · "}
            {t(`gender.${patient.gender}`)}
            {patient.age !== null ? ` · ${patient.age} ${t("common.years")}` : ""}
            {patient.birthDate
              ? ` · ${formatDate(patient.birthDate, locale)}${patient.birthDateEstimated ? " *" : ""}`
              : ""}
          </p>
          <div className="chip-row">
            <span className={`badge badge-${statusTone(patient.status)}`}>
              {t(`patientStatus.${patient.status}`)}
            </span>
            {patient.bloodGroup && (
              <span className="chip">{patient.bloodGroup}</span>
            )}
            {patient.flags.map((flag) => (
              <span key={flag.publicId} className="badge badge-warn" title={flag.note ?? ""}>
                {t(`flag.${flag.flagType}`)}
              </span>
            ))}
          </div>
        </div>
        <div className="header-actions">
          {canUpdate && patient.status !== "MERGED" && (
            <button type="button" className="button button-ghost" onClick={openEdit}>
              {t("common.edit")}
            </button>
          )}
          {canMerge && patient.status === "ACTIVE" && (
            <button
              type="button"
              className="button button-danger"
              onClick={() => {
                setActionError(null);
                setMergeTarget(null);
                setMergeReason("");
                setMergeOpen(true);
              }}
            >
              {t("patients.merge")}
            </button>
          )}
          {canOpenEncounter && patient.status === "ACTIVE" && (
            <button
              type="button"
              className="button"
              onClick={() => openEncounter.mutate()}
              disabled={openEncounter.isPending}
            >
              {openEncounter.isPending
                ? t("common.loading")
                : t("patients.openEncounter")}
            </button>
          )}
        </div>
      </header>

      {actionError && <div className="alert alert-danger">{actionError}</div>}

      {patient.mergedIntoPublicId && (
        <div className="alert alert-warn">
          {t("patients.mergedInto")}{" "}
          <Link className="table-link" to={`/app/patients/${patient.mergedIntoPublicId}`}>
            {patient.mergedIntoMedicalNumber ?? patient.mergedIntoPublicId}
          </Link>
        </div>
      )}

      {severe && (
        <div className="alert alert-danger">
          <strong>{t("patients.allergyAlert")}</strong>{" "}
          {patient.allergies
            .filter(
              (allergy) =>
                allergy.severity === "SEVERE" ||
                allergy.severity === "LIFE_THREATENING",
            )
            .map((allergy) => allergy.substance)
            .join(" · ")}
        </div>
      )}

      <div className="split-grid">
        <section className="card">
          <header className="card-header">
            <h2>{t("patients.demographics")}</h2>
          </header>
          <dl className="meta-grid">
            <dt>{t("patients.nationalId")}</dt>
            <dd className="mono">{patient.nationalId ?? "—"}</dd>
            <dt>{t("patients.phone")}</dt>
            <dd className="mono">{patient.phone ?? "—"}</dd>
            <dt>{t("patients.email")}</dt>
            <dd>{patient.email ?? "—"}</dd>
            <dt>{t("patients.address")}</dt>
            <dd>{patient.address ?? "—"}</dd>
            <dt>{t("patients.maritalStatus")}</dt>
            <dd>{t(`marital.${patient.maritalStatus ?? "UNKNOWN"}`)}</dd>
            <dt>{t("patients.registeredAt")}</dt>
            <dd>{formatDateTime(patient.createdAt, locale)}</dd>
            <dt>{t("patients.encounters")}</dt>
            <dd>{patient.encounterCount}</dd>
            <dt>{t("patients.appointments")}</dt>
            <dd>{patient.appointmentCount}</dd>
          </dl>
          {patient.notes && <p className="muted">{patient.notes}</p>}
        </section>

        <section className="card">
          <header className="card-header">
            <h2>{t("patients.identifiers")}</h2>
          </header>
          {patient.identifiers.length === 0 ? (
            <p className="empty-inline">{t("patients.noIdentifiers")}</p>
          ) : (
            <ul className="plain-list">
              {patient.identifiers.map((identifier) => (
                <li key={identifier.publicId}>
                  <span className="badge badge-muted">{identifier.type}</span>{" "}
                  <span className="mono">{identifier.value}</span>
                  {identifier.isPrimary && (
                    <span className="muted"> · {t("patients.primary")}</span>
                  )}
                </li>
              ))}
            </ul>
          )}

          <header className="card-header">
            <h2>{t("patients.contacts")}</h2>
          </header>
          {patient.contacts.length === 0 ? (
            <p className="empty-inline">{t("patients.noContacts")}</p>
          ) : (
            <ul className="plain-list">
              {patient.contacts.map((contact) => (
                <li key={contact.publicId}>
                  <strong>{contact.fullName}</strong>
                  {contact.relation ? ` · ${contact.relation}` : ""}
                  <span className="mono"> · {contact.phone ?? "—"}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <div className="split-grid">
        <section className="card">
          <header className="card-header">
            <h2>{t("patients.allergies")}</h2>
            {canUpdate && patient.status !== "MERGED" && (
              <button
                type="button"
                className="button button-ghost button-sm"
                onClick={() => {
                  setActionError(null);
                  setAllergyOpen(true);
                }}
              >
                {t("patients.addAllergy")}
              </button>
            )}
          </header>
          {patient.allergies.length === 0 ? (
            <p className="empty-inline">{t("patients.noAllergies")}</p>
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{t("patients.substance")}</th>
                    <th>{t("patients.allergyType")}</th>
                    <th>{t("patients.severity")}</th>
                    <th>{t("patients.reaction")}</th>
                    <th>{t("common.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {patient.allergies.map((allergy) => (
                    <tr key={allergy.publicId}>
                      <td>{allergy.substance}</td>
                      <td>{t(`allergyType.${allergy.allergyType}`)}</td>
                      <td>
                        <span className={`badge badge-${severityTone(allergy.severity)}`}>
                          {t(`severity.${allergy.severity}`)}
                        </span>
                      </td>
                      <td>{allergy.reaction ?? "—"}</td>
                      <td>
                        {canUpdate && (
                          <button
                            type="button"
                            className="button button-ghost button-sm"
                            onClick={() => {
                              if (window.confirm(t("patients.confirmRemoveAllergy"))) {
                                removeAllergy.mutate(allergy.publicId);
                              }
                            }}
                          >
                            {t("patients.remove")}
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
            <h2>{t("patients.conditions")}</h2>
          </header>
          {patient.conditions.length === 0 ? (
            <p className="empty-inline">{t("patients.noConditions")}</p>
          ) : (
            <ul className="plain-list">
              {patient.conditions.map((condition) => (
                <li key={condition.publicId}>
                  <strong>{condition.description}</strong>
                  <span className="muted">
                    {" "}
                    · {t(`conditionStatus.${condition.status}`)}
                    {condition.onsetDate
                      ? ` · ${formatDate(condition.onsetDate, locale)}`
                      : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card">
        <header className="card-header">
          <h2>{t("patients.timeline")}</h2>
        </header>
        {timeline.isPending ? (
          <p className="hint">{t("common.loading")}</p>
        ) : timeline.isError ? (
          <p className="field-error">{t("common.loadError")}</p>
        ) : timeline.data.length === 0 ? (
          <p className="empty-inline">{t("patients.noTimeline")}</p>
        ) : (
          <ol className="timeline">
            {timeline.data.map((event) => (
              <li key={`${event.type}-${event.publicId}`} className="timeline-item">
                <span className={`timeline-dot timeline-${event.type.toLowerCase()}`} />
                <div className="timeline-body">
                  <div className="timeline-head">
                    <span className="badge badge-muted">{t(`event.${event.type}`)}</span>
                    <span className="muted">{formatDateTime(event.at, locale)}</span>
                    {event.status && (
                      <span className={`badge badge-${statusTone(event.status)}`}>
                        {t(`eventStatus.${event.status}`)}
                      </span>
                    )}
                  </div>
                  <strong>
                    {(locale === "ar" ? event.titleAr : event.titleEn) ?? "—"}
                  </strong>
                  <p className="muted">
                    {(locale === "ar" ? event.subtitleAr : event.subtitleEn) ?? ""}
                  </p>
                  {event.type === "ENCOUNTER" && (
                    <Link
                      className="button button-ghost button-sm"
                      to={`/app/clinical/${event.publicId}`}
                    >
                      {t("patients.openEncounter")}
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="card">
        <header className="card-header">
          <h2>{t("patients.prescriptions")}</h2>
        </header>
        {prescriptions.isPending ? (
          <p className="hint">{t("common.loading")}</p>
        ) : prescriptions.data && prescriptions.data.length > 0 ? (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("patients.prescribedAt")}</th>
                  <th>{t("patients.prescribedBy")}</th>
                  <th>{t("patients.items")}</th>
                  <th>{t("common.status")}</th>
                </tr>
              </thead>
              <tbody>
                {prescriptions.data.map((prescription) => (
                  <tr key={prescription.publicId}>
                    <td>{formatDateTime(prescription.at, locale)}</td>
                    <td>
                      {(locale === "ar"
                        ? prescription.prescribedByAr
                        : prescription.prescribedByEn) ?? "—"}
                    </td>
                    <td>{prescription.itemCount}</td>
                    <td>
                      <span className={`badge badge-${statusTone(prescription.status)}`}>
                        {t(`prescriptionStatus.${prescription.status}`)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="empty-inline">{t("patients.noPrescriptions")}</p>
        )}
      </section>

      {editOpen && editForm && (
        <Modal title={t("patients.editTitle")} onClose={() => setEditOpen(false)}>
          <form className="stack-form" onSubmit={submitEdit}>
            <div className="form-grid">
              <label className="field">
                <span>{t("patients.name")} *</span>
                <input
                  value={editForm.fullName}
                  onChange={(event) =>
                    setEditForm({ ...editForm, fullName: event.target.value })
                  }
                  required
                />
              </label>
              <label className="field">
                <span>{t("patients.gender")} *</span>
                <select
                  value={editForm.gender}
                  onChange={(event) =>
                    setEditForm({ ...editForm, gender: event.target.value })
                  }
                >
                  {GENDERS.map((item) => (
                    <option key={item} value={item}>
                      {t(`gender.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("patients.birthDate")}</span>
                <input
                  type="date"
                  value={editForm.birthDate}
                  onChange={(event) =>
                    setEditForm({ ...editForm, birthDate: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("patients.phone")}</span>
                <input
                  value={editForm.phone}
                  onChange={(event) =>
                    setEditForm({ ...editForm, phone: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("patients.email")}</span>
                <input
                  value={editForm.email}
                  onChange={(event) =>
                    setEditForm({ ...editForm, email: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("patients.address")}</span>
                <input
                  value={editForm.address}
                  onChange={(event) =>
                    setEditForm({ ...editForm, address: event.target.value })
                  }
                />
              </label>
              <label className="field">
                <span>{t("patients.bloodGroup")}</span>
                <select
                  value={editForm.bloodGroup}
                  onChange={(event) =>
                    setEditForm({ ...editForm, bloodGroup: event.target.value })
                  }
                >
                  <option value="">—</option>
                  {BLOOD_GROUPS.map((item) => (
                    <option key={item} value={item}>
                      {item}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("patients.maritalStatus")}</span>
                <select
                  value={editForm.maritalStatus}
                  onChange={(event) =>
                    setEditForm({ ...editForm, maritalStatus: event.target.value })
                  }
                >
                  {MARITAL.map((item) => (
                    <option key={item} value={item}>
                      {t(`marital.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="field">
              <span>{t("patients.notes")}</span>
              <textarea
                rows={3}
                value={editForm.notes}
                onChange={(event) =>
                  setEditForm({ ...editForm, notes: event.target.value })
                }
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setEditOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={update.isPending}>
                {update.isPending ? t("common.saving") : t("common.save")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {allergyOpen && (
        <Modal title={t("patients.addAllergy")} onClose={() => setAllergyOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              setActionError(null);
              addAllergy.mutate();
            }}
          >
            <label className="field">
              <span>{t("patients.substance")} *</span>
              <input
                value={allergyForm.substance}
                onChange={(event) =>
                  setAllergyForm({ ...allergyForm, substance: event.target.value })
                }
                required
              />
            </label>
            <div className="form-grid">
              <label className="field">
                <span>{t("patients.allergyType")}</span>
                <select
                  value={allergyForm.allergyType}
                  onChange={(event) =>
                    setAllergyForm({ ...allergyForm, allergyType: event.target.value })
                  }
                >
                  {ALLERGY_TYPES.map((item) => (
                    <option key={item} value={item}>
                      {t(`allergyType.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("patients.severity")}</span>
                <select
                  value={allergyForm.severity}
                  onChange={(event) =>
                    setAllergyForm({ ...allergyForm, severity: event.target.value })
                  }
                >
                  {SEVERITIES.map((item) => (
                    <option key={item} value={item}>
                      {t(`severity.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="field">
              <span>{t("patients.reaction")}</span>
              <input
                value={allergyForm.reaction}
                onChange={(event) =>
                  setAllergyForm({ ...allergyForm, reaction: event.target.value })
                }
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setAllergyOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={addAllergy.isPending}>
                {addAllergy.isPending ? t("common.saving") : t("common.save")}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {mergeOpen && (
        <Modal title={t("patients.mergeTitle")} onClose={() => setMergeOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event) => {
              event.preventDefault();
              setActionError(null);
              if (mergeTarget) {
                merge.mutate();
              }
            }}
          >
            <p className="notice">{t("patients.mergeWarning")}</p>
            <div className="field">
              <span>{t("patients.mergeTarget")} *</span>
              <PatientPicker selected={mergeTarget} onSelect={setMergeTarget} />
            </div>
            <label className="field">
              <span>{t("patients.mergeReason")} *</span>
              <textarea
                rows={3}
                value={mergeReason}
                onChange={(event) => setMergeReason(event.target.value)}
                required
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setMergeOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className="button button-danger"
                disabled={!mergeTarget || merge.isPending}
              >
                {merge.isPending ? t("common.saving") : t("patients.merge")}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}

function severityTone(severity: string): string {
  switch (severity) {
    case "LIFE_THREATENING":
    case "SEVERE":
      return "danger";
    case "MODERATE":
      return "warn";
    default:
      return "muted";
  }
}
