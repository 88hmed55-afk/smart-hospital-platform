import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { formatDate } from "../../shared/ui/format";
import {
  patientsApi,
  type DuplicateCheck,
  type PatientInput,
} from "./patientsApi";

const STATUSES = ["ACTIVE", "MERGED", "INACTIVE", "DECEASED"];
const GENDERS = ["MALE", "FEMALE", "UNKNOWN"];
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];
const MARITAL = ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "UNKNOWN"];

interface RegisterForm {
  fullName: string;
  gender: string;
  birthDate: string;
  birthDateEstimated: boolean;
  nationalId: string;
  phone: string;
  email: string;
  address: string;
  bloodGroup: string;
  maritalStatus: string;
  notes: string;
  contactName: string;
  contactPhone: string;
  contactRelation: string;
  overrideReason: string;
}

const emptyForm: RegisterForm = {
  fullName: "",
  gender: "MALE",
  birthDate: "",
  birthDateEstimated: false,
  nationalId: "",
  phone: "",
  email: "",
  address: "",
  bloodGroup: "",
  maritalStatus: "UNKNOWN",
  notes: "",
  contactName: "",
  contactPhone: "",
  contactRelation: "",
  overrideReason: "",
};

export function PatientsPage() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canCreate = hasPermission("PAT:CREATE");
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [term, setTerm] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [registerOpen, setRegisterOpen] = useState(false);
  const [form, setForm] = useState<RegisterForm>(emptyForm);
  const [duplicate, setDuplicate] = useState<DuplicateCheck | null>(null);
  const [error, setError] = useState<string | null>(null);

  const list = useQuery({
    queryKey: ["patients", term, status, page],
    queryFn: () => patientsApi.search(term, status || undefined, page, 20),
  });

  const duplicateCheck = useMutation({
    mutationFn: patientsApi.checkDuplicates,
  });

  const create = useMutation({
    mutationFn: patientsApi.create,
    onSuccess: (patient) => {
      void queryClient.invalidateQueries({ queryKey: ["patients"] });
      setRegisterOpen(false);
      setForm(emptyForm);
      setDuplicate(null);
      navigate(`/app/patients/${patient.publicId}`);
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  function update<K extends keyof RegisterForm>(key: K, value: RegisterForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function buildBody(): PatientInput {
    return {
      fullName: form.fullName.trim(),
      gender: form.gender,
      birthDate: form.birthDate || null,
      birthDateEstimated: form.birthDateEstimated,
      nationalId: form.nationalId.trim() || null,
      phone: form.phone.trim() || null,
      email: form.email.trim() || null,
      address: form.address.trim() || null,
      bloodGroup: form.bloodGroup || null,
      maritalStatus: form.maritalStatus || null,
      notes: form.notes.trim() || null,
      emergencyContact:
        form.contactName.trim() && form.contactPhone.trim()
          ? {
              name: form.contactName.trim(),
              phone: form.contactPhone.trim(),
              relation: form.contactRelation.trim() || null,
            }
          : null,
    };
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (form.fullName.trim().length < 2) {
      setError(t("patients.nameRequired"));
      return;
    }
    const body = buildBody();
    if (duplicate && duplicate.status !== "NO_MATCH") {
      if (!form.overrideReason.trim()) {
        setError(t("patients.overrideRequired"));
        return;
      }
      create.mutate({
        ...body,
        override: true,
        overrideReason: form.overrideReason.trim(),
      });
      return;
    }
    try {
      const check = await duplicateCheck.mutateAsync({
        fullName: body.fullName,
        nationalId: body.nationalId ?? undefined,
        phone: body.phone ?? undefined,
        birthDate: body.birthDate ?? undefined,
      });
      if (check.status !== "NO_MATCH") {
        setDuplicate(check);
        return;
      }
      create.mutate(body);
    } catch (cause) {
      setError(describeApiError(cause, locale, t("common.loadError")));
    }
  }

  function openRegister() {
    setForm(emptyForm);
    setDuplicate(null);
    setError(null);
    setRegisterOpen(true);
  }

  const exactNationalId =
    duplicate?.matches.some((match) => match.matchReason === "NATIONAL_ID") ?? false;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("patients.title")}</h1>
          <p className="muted">{t("patients.subtitle")}</p>
        </div>
        <div className="header-actions">
          {canCreate && (
            <button type="button" className="button" onClick={openRegister}>
              {t("patients.new")}
            </button>
          )}
        </div>
      </header>

      <section className="card">
        <form
          className="search-form"
          onSubmit={(event) => {
            event.preventDefault();
            setPage(0);
            setTerm(search.trim());
          }}
        >
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t("patients.searchPlaceholder")}
            aria-label={t("patients.searchPlaceholder")}
          />
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
            aria-label={t("common.status")}
          >
            <option value="">{t("common.all")}</option>
            {STATUSES.map((item) => (
              <option key={item} value={item}>
                {t(`patientStatus.${item}`)}
              </option>
            ))}
          </select>
          <button type="submit" className="button">
            {t("common.search")}
          </button>
        </form>

        {list.isPending ? (
          <p className="hint">{t("common.loading")}</p>
        ) : list.isError ? (
          <p className="field-error">{t("common.loadError")}</p>
        ) : list.data.items.length === 0 ? (
          <p className="empty-inline">{t("patients.empty")}</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("patients.medicalNumber")}</th>
                  <th>{t("patients.name")}</th>
                  <th>{t("patients.gender")}</th>
                  <th>{t("patients.age")}</th>
                  <th>{t("patients.phone")}</th>
                  <th>{t("patients.allergies")}</th>
                  <th>{t("common.status")}</th>
                  <th>{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((patient) => (
                  <tr key={patient.publicId}>
                    <td className="mono">{patient.medicalNumber}</td>
                    <td>
                      <Link className="table-link" to={`/app/patients/${patient.publicId}`}>
                        {patient.fullName}
                      </Link>
                    </td>
                    <td>{t(`gender.${patient.gender}`)}</td>
                    <td>
                      {patient.age !== null
                        ? `${patient.age} ${t("common.years")}`
                        : formatDate(patient.birthDate, locale)}
                    </td>
                    <td className="mono">{patient.phone ?? "—"}</td>
                    <td>
                      {(patient.allergyCount ?? 0) > 0 ? (
                        <span className="badge badge-danger">{patient.allergyCount}</span>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td>
                      <span className={`badge badge-${statusTone(patient.status)}`}>
                        {t(`patientStatus.${patient.status}`)}
                      </span>
                    </td>
                    <td className="row-actions">
                      <Link
                        className="button button-ghost button-sm"
                        to={`/app/patients/${patient.publicId}`}
                      >
                        {t("common.details")}
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list.data && (
          <Pagination
            page={list.data.page}
            totalPages={list.data.totalPages}
            total={list.data.total}
            onChange={setPage}
          />
        )}
      </section>

      {registerOpen && (
        <Modal title={t("patients.registerTitle")} onClose={() => setRegisterOpen(false)}>
          <form className="stack-form" onSubmit={handleSubmit}>
            {error && <div className="alert alert-danger">{error}</div>}

            <div className="form-grid">
              <label className="field">
                <span>{t("patients.name")} *</span>
                <input
                  value={form.fullName}
                  onChange={(event) => update("fullName", event.target.value)}
                  required
                />
              </label>
              <label className="field">
                <span>{t("patients.gender")} *</span>
                <select
                  value={form.gender}
                  onChange={(event) => update("gender", event.target.value)}
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
                  value={form.birthDate}
                  onChange={(event) => update("birthDate", event.target.value)}
                />
              </label>
              <label className="field field-inline">
                <input
                  type="checkbox"
                  checked={form.birthDateEstimated}
                  onChange={(event) =>
                    update("birthDateEstimated", event.target.checked)
                  }
                />
                <span>{t("patients.birthEstimated")}</span>
              </label>
              <label className="field">
                <span>{t("patients.nationalId")}</span>
                <input
                  value={form.nationalId}
                  onChange={(event) => update("nationalId", event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("patients.phone")}</span>
                <input
                  value={form.phone}
                  onChange={(event) => update("phone", event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("patients.email")}</span>
                <input
                  type="email"
                  value={form.email}
                  onChange={(event) => update("email", event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("patients.bloodGroup")}</span>
                <select
                  value={form.bloodGroup}
                  onChange={(event) => update("bloodGroup", event.target.value)}
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
                  value={form.maritalStatus}
                  onChange={(event) => update("maritalStatus", event.target.value)}
                >
                  {MARITAL.map((item) => (
                    <option key={item} value={item}>
                      {t(`marital.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("patients.address")}</span>
                <input
                  value={form.address}
                  onChange={(event) => update("address", event.target.value)}
                />
              </label>
            </div>

            <fieldset className="fieldset">
              <legend>{t("patients.emergencyContact")}</legend>
              <div className="form-grid">
                <label className="field">
                  <span>{t("patients.contactName")}</span>
                  <input
                    value={form.contactName}
                    onChange={(event) => update("contactName", event.target.value)}
                  />
                </label>
                <label className="field">
                  <span>{t("patients.contactPhone")}</span>
                  <input
                    value={form.contactPhone}
                    onChange={(event) => update("contactPhone", event.target.value)}
                  />
                </label>
                <label className="field">
                  <span>{t("patients.contactRelation")}</span>
                  <input
                    value={form.contactRelation}
                    onChange={(event) => update("contactRelation", event.target.value)}
                  />
                </label>
              </div>
            </fieldset>

            <label className="field">
              <span>{t("patients.notes")}</span>
              <textarea
                rows={2}
                value={form.notes}
                onChange={(event) => update("notes", event.target.value)}
              />
            </label>

            {duplicate && duplicate.status !== "NO_MATCH" && (
              <div className="alert alert-danger">
                <strong>
                  {duplicate.status === "EXACT_MATCH"
                    ? t("patients.duplicateExact")
                    : t("patients.duplicatePossible")}
                </strong>
                <ul className="plain-list">
                  {duplicate.matches.map((match) => (
                    <li key={match.publicId}>
                      <Link
                        className="table-link"
                        to={`/app/patients/${match.publicId}`}
                        target="_blank"
                        rel="noreferrer"
                      >
                        {match.fullName}
                      </Link>
                      <span className="mono"> · {match.medicalNumber}</span>
                      <span className="muted">
                        {" "}
                        · {t(`duplicate.${match.matchReason}`)}
                      </span>
                    </li>
                  ))}
                </ul>
                {exactNationalId ? (
                  <p>{t("patients.exactNationalId")}</p>
                ) : (
                  <label className="field">
                    <span>{t("patients.overrideReason")} *</span>
                    <input
                      value={form.overrideReason}
                      onChange={(event) =>
                        update("overrideReason", event.target.value)
                      }
                    />
                  </label>
                )}
              </div>
            )}

            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setRegisterOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                className="button"
                disabled={
                  create.isPending ||
                  duplicateCheck.isPending ||
                  (duplicate !== null && exactNationalId)
                }
              >
                {create.isPending || duplicateCheck.isPending
                  ? t("common.saving")
                  : duplicate && duplicate.status !== "NO_MATCH"
                    ? t("patients.createOverride")
                    : t("common.create")}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}

export function statusTone(status: string): string {
  switch (status) {
    case "ACTIVE":
    case "OPEN":
    case "ISSUED":
    case "COMPLETED":
    case "DONE":
      return "success";
    case "CANCELLED":
    case "NO_SHOW":
    case "DECEASED":
    case "LEFT":
      return "danger";
    case "MERGED":
    case "INACTIVE":
    case "DRAFT":
    case "BOOKED":
    case "WAITING":
      return "muted";
    default:
      return "info";
  }
}
