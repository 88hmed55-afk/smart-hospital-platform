import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useI18n } from "../i18n";
import { patientsApi, type PatientSummary } from "../../modules/patients/patientsApi";
import { formatDate } from "./format";

interface PatientPickerProps {
  selected: PatientSummary | null;
  onSelect: (patient: PatientSummary | null) => void;
  disabled?: boolean;
  placeholder?: string;
}

/** Debounced patient search used by booking, encounter and merge dialogs. */
export function PatientPicker({
  selected,
  onSelect,
  disabled,
  placeholder,
}: PatientPickerProps) {
  const { t, locale } = useI18n();
  const [term, setTerm] = useState("");
  const [debounced, setDebounced] = useState("");

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(term.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [term]);

  const search = useQuery({
    queryKey: ["patient-picker", debounced],
    queryFn: () => patientsApi.search(debounced, undefined, 0, 8),
    enabled: debounced.length >= 2,
    staleTime: 15_000,
  });

  if (selected) {
    return (
      <div className="picker-selected">
        <div className="picker-selected-main">
          <strong>{selected.fullName}</strong>
          <span className="muted mono">{selected.medicalNumber}</span>
          <span className="muted">
            {selected.gender === "FEMALE"
              ? t("gender.FEMALE")
              : selected.gender === "MALE"
                ? t("gender.MALE")
                : t("gender.UNKNOWN")}
            {selected.age !== null ? ` · ${selected.age} ${t("common.years")}` : ""}
            {selected.birthDate ? ` · ${formatDate(selected.birthDate, locale)}` : ""}
          </span>
        </div>
        <button
          type="button"
          className="button button-ghost button-sm"
          onClick={() => onSelect(null)}
          disabled={disabled}
        >
          {t("picker.change")}
        </button>
      </div>
    );
  }

  return (
    <div className="picker">
      <input
        type="search"
        value={term}
        onChange={(event) => setTerm(event.target.value)}
        placeholder={placeholder ?? t("picker.placeholder")}
        disabled={disabled}
        aria-label={placeholder ?? t("picker.placeholder")}
      />
      {debounced.length < 2 ? (
        <p className="hint">{t("picker.hint")}</p>
      ) : search.isPending ? (
        <p className="hint">{t("common.loading")}</p>
      ) : search.isError ? (
        <p className="field-error">{t("common.loadError")}</p>
      ) : search.data && search.data.items.length > 0 ? (
        <ul className="picker-results">
          {search.data.items.map((patient) => (
            <li key={patient.publicId}>
              <button
                type="button"
                className="picker-result"
                onClick={() => {
                  onSelect(patient);
                  setTerm("");
                  setDebounced("");
                }}
              >
                <span className="picker-result-name">{patient.fullName}</span>
                <span className="muted mono">{patient.medicalNumber}</span>
                <span className="muted">
                  {patient.phone ?? "—"}
                  {patient.birthDate
                    ? ` · ${formatDate(patient.birthDate, locale)}`
                    : ""}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="hint">{t("picker.empty")}</p>
      )}
    </div>
  );
}
