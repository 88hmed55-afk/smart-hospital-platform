import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import { PatientPicker } from "../../shared/ui/PatientPicker";
import { formatDateTime, todayInput } from "../../shared/ui/format";
import type { PatientSummary } from "../patients/patientsApi";
import { statusTone } from "../patients/PatientsPage";
import { schedulingApi } from "../scheduling/schedulingApi";
import { clinicalApi } from "./clinicalApi";

const ENCOUNTER_TYPES = ["OUTPATIENT", "EMERGENCY", "INPATIENT"];
const ENCOUNTER_STATUSES = ["OPEN", "CLOSED", "CANCELLED"];

export function EncountersPage() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const canCreate = hasPermission("EMR:CREATE");
  const [search, setSearch] = useState("");
  const [term, setTerm] = useState("");
  const [status, setStatus] = useState("");
  const [date, setDate] = useState("");
  const [provider, setProvider] = useState("");
  const [page, setPage] = useState(0);

  const [open, setOpen] = useState(false);
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [type, setType] = useState("OUTPATIENT");
  const [providerPublicId, setProviderPublicId] = useState("");
  const [complaint, setComplaint] = useState("");
  const [error, setError] = useState<string | null>(null);

  const providers = useQuery({
    queryKey: ["scheduling-providers"],
    queryFn: schedulingApi.providers,
    staleTime: 300_000,
  });

  const list = useQuery({
    queryKey: ["encounters", term, status, date, provider, page],
    queryFn: () =>
      clinicalApi.search({
        q: term || undefined,
        status: status || undefined,
        date: date || undefined,
        provider: provider || undefined,
        page,
        size: 20,
      }),
  });

  const create = useMutation({
    mutationFn: () =>
      clinicalApi.create({
        patientPublicId: patient?.publicId ?? "",
        type,
        providerPublicId: providerPublicId || undefined,
        chiefComplaint: complaint.trim() || undefined,
      }),
    onSuccess: (workspace) => {
      void queryClient.invalidateQueries({ queryKey: ["encounters"] });
      setOpen(false);
      setPatient(null);
      setComplaint("");
      navigate(`/app/clinical/${workspace.encounter.publicId}`);
    },
    onError: (cause) => setError(describeApiError(cause, locale, t("common.saveError"))),
  });

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("encounters.title")}</h1>
          <p className="muted">{t("encounters.subtitle")}</p>
        </div>
        <div className="header-actions">
          {canCreate && (
            <button
              type="button"
              className="button"
              onClick={() => {
                setError(null);
                setPatient(null);
                setType("OUTPATIENT");
                setProviderPublicId("");
                setComplaint("");
                setOpen(true);
              }}
            >
              {t("encounters.openNew")}
            </button>
          )}
        </div>
      </header>

      <section className="card">
        <form
          className="filter-row"
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
            placeholder={t("encounters.searchPlaceholder")}
            aria-label={t("encounters.searchPlaceholder")}
          />
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">{t("common.all")}</option>
            {ENCOUNTER_STATUSES.map((item) => (
              <option key={item} value={item}>
                {t(`encounterStatus.${item}`)}
              </option>
            ))}
          </select>
          <input
            type="date"
            value={date}
            onChange={(event) => {
              setDate(event.target.value);
              setPage(0);
            }}
            aria-label={t("scheduling.date")}
          />
          <select value={provider} onChange={(event) => setProvider(event.target.value)}>
            <option value="">{t("common.all")}</option>
            {(providers.data ?? []).map((item) => (
              <option key={item.publicId} value={item.publicId}>
                {(locale === "ar" ? item.displayNameAr : item.displayNameEn) ?? item.username}
              </option>
            ))}
          </select>
          <button type="submit" className="button">
            {t("common.search")}
          </button>
          <button
            type="button"
            className="button button-ghost"
            onClick={() => {
              setSearch("");
              setTerm("");
              setStatus("");
              setDate(todayInput());
              setProvider("");
              setPage(0);
            }}
          >
            {t("encounters.today")}
          </button>
        </form>

        {list.isPending ? (
          <p className="hint">{t("common.loading")}</p>
        ) : list.isError ? (
          <p className="field-error">{t("common.loadError")}</p>
        ) : list.data.items.length === 0 ? (
          <p className="empty-inline">{t("encounters.empty")}</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>{t("encounters.startedAt")}</th>
                  <th>{t("scheduling.patient")}</th>
                  <th>{t("encounters.type")}</th>
                  <th>{t("encounters.complaint")}</th>
                  <th>{t("encounters.provider")}</th>
                  <th>{t("common.status")}</th>
                  <th>{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((encounter) => (
                  <tr key={encounter.publicId}>
                    <td>{formatDateTime(encounter.startedAt, locale)}</td>
                    <td>
                      <Link
                        className="table-link"
                        to={`/app/clinical/${encounter.publicId}`}
                      >
                        {encounter.patientName}
                      </Link>
                      <div className="muted mono">{encounter.medicalNumber}</div>
                    </td>
                    <td>{t(`encounterType.${encounter.type}`)}</td>
                    <td className="reason-cell">{encounter.chiefComplaint ?? "—"}</td>
                    <td>
                      {(locale === "ar" ? encounter.providerAr : encounter.providerEn) ?? "—"}
                    </td>
                    <td>
                      <span className={`badge badge-${statusTone(encounter.status)}`}>
                        {t(`encounterStatus.${encounter.status}`)}
                      </span>
                    </td>
                    <td>
                      <Link
                        className="button button-ghost button-sm"
                        to={`/app/clinical/${encounter.publicId}`}
                      >
                        {t("encounters.open")}
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

      {open && (
        <Modal title={t("encounters.openNew")} onClose={() => setOpen(false)}>
          <form
            className="stack-form"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              setError(null);
              if (!patient) {
                setError(t("scheduling.patientRequired"));
                return;
              }
              create.mutate();
            }}
          >
            {error && <div className="alert alert-danger">{error}</div>}
            <div className="field">
              <span>{t("scheduling.patient")} *</span>
              <PatientPicker selected={patient} onSelect={setPatient} />
            </div>
            <div className="form-grid">
              <label className="field">
                <span>{t("encounters.type")}</span>
                <select value={type} onChange={(event) => setType(event.target.value)}>
                  {ENCOUNTER_TYPES.map((item) => (
                    <option key={item} value={item}>
                      {t(`encounterType.${item}`)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                <span>{t("encounters.provider")}</span>
                <select
                  value={providerPublicId}
                  onChange={(event) => setProviderPublicId(event.target.value)}
                >
                  <option value="">{t("encounters.currentUser")}</option>
                  {(providers.data ?? []).map((item) => (
                    <option key={item.publicId} value={item.publicId}>
                      {(locale === "ar" ? item.displayNameAr : item.displayNameEn) ??
                        item.username}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="field">
              <span>{t("encounters.complaint")}</span>
              <textarea
                rows={3}
                value={complaint}
                onChange={(event) => setComplaint(event.target.value)}
              />
            </label>
            <div className="form-actions">
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setOpen(false)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="button" disabled={create.isPending || !patient}>
                {create.isPending ? t("common.saving") : t("encounters.openNew")}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
