import { useI18n } from "../../shared/i18n";

interface PlaceholderProps {
  titleKey: string;
  phase: string;
}

export function Placeholder({ titleKey, phase }: PlaceholderProps) {
  const { t } = useI18n();
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t(titleKey)}</h1>
          <p className="muted">{t("dashboard.subtitle")}</p>
        </div>
        <span className="phase-badge">Phase {phase}</span>
      </header>
      <section className="card empty-state">
        <h2>{t("common.comingSoon")}</h2>
        <p className="muted">{t("dashboard.phaseNote")}</p>
      </section>
    </div>
  );
}
