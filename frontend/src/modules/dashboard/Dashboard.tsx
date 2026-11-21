import { Link } from "react-router-dom";
import type { HealthCheck, HealthResult } from "../../shared/api/health";
import { useHealth } from "../../shared/api/health";
import { useAuth } from "../../shared/auth/AuthContext";
import { useI18n } from "../../shared/i18n";
import { Icon } from "../../shared/ui/Icon";

interface ModuleLink {
  to: string;
  icon: string;
  titleKey: string;
  descKey: string;
  permissions?: string[];
}

const ADMIN_PERMISSIONS = [
  "IAM:READ",
  "IAM:WRITE",
  "IAM:BREAK_GLASS",
  "AUDIT:READ",
  "SYS:CONFIG",
];

const MODULES: ModuleLink[] = [
  {
    to: "/app/patients",
    icon: "patients",
    titleKey: "nav.patients",
    descKey: "dashboard.mod.patients",
    permissions: ["PAT:READ"],
  },
  {
    to: "/app/appointments",
    icon: "appointments",
    titleKey: "nav.appointments",
    descKey: "dashboard.mod.appointments",
    permissions: ["SCH:READ"],
  },
  {
    to: "/app/clinical",
    icon: "clinical",
    titleKey: "nav.clinical",
    descKey: "dashboard.mod.clinical",
    permissions: ["EMR:READ"],
  },
  {
    to: "/app/er",
    icon: "emergency",
    titleKey: "nav.er",
    descKey: "dashboard.mod.er",
    permissions: ["ER:READ"],
  },
  {
    to: "/app/admission",
    icon: "admission",
    titleKey: "nav.admission",
    descKey: "dashboard.mod.admission",
    permissions: ["ADT:READ"],
  },
  {
    to: "/app/nursing",
    icon: "nursing",
    titleKey: "nav.nursing",
    descKey: "dashboard.mod.nursing",
    permissions: ["NUR:READ"],
  },
  {
    to: "/app/laboratory",
    icon: "laboratory",
    titleKey: "nav.laboratory",
    descKey: "dashboard.mod.laboratory",
    permissions: ["LAB:READ"],
  },
  {
    to: "/app/pharmacy",
    icon: "pharmacy",
    titleKey: "nav.pharmacy",
    descKey: "dashboard.mod.pharmacy",
    permissions: ["PHR:READ"],
  },
  {
    to: "/app/billing",
    icon: "billing",
    titleKey: "nav.billing",
    descKey: "dashboard.mod.billing",
    permissions: ["BIL:READ"],
  },
  {
    to: "/app/inventory",
    icon: "inventory",
    titleKey: "nav.inventory",
    descKey: "dashboard.mod.inventory",
    permissions: ["INV:READ"],
  },
  {
    to: "/app/analytics",
    icon: "analytics",
    titleKey: "nav.analytics",
    descKey: "dashboard.mod.analytics",
  },
  {
    to: "/app/admin",
    icon: "admin",
    titleKey: "nav.admin",
    descKey: "dashboard.mod.admin",
    permissions: ADMIN_PERMISSIONS,
  },
];

function formatUptime(ms: number): string {
  const totalMinutes = Math.floor(ms / 60_000);
  const days = Math.floor(totalMinutes / 1_440);
  const hours = Math.floor((totalMinutes % 1_440) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

function statusLabel(status: string, t: (key: string) => string): string {
  const known = t(`status.${status}`);
  return known === `status.${status}` ? status : known;
}

function CheckRow({ check }: { check: HealthCheck }) {
  const { t } = useI18n();
  const label =
    check.name === "database"
      ? t("health.database")
      : check.name === "disk"
        ? t("health.disk")
        : check.name;

  return (
    <li className="check-row">
      <span className="check-dot" data-status={check.status} aria-hidden="true" />
      <span className="check-name">{label}</span>
      <span className="check-detail" title={check.detail}>
        {check.detail}
      </span>
      <span className="check-latency">{check.latencyMs} ms</span>
    </li>
  );
}

function HealthPanel({ data }: { data: HealthResult }) {
  const { t } = useI18n();
  return (
    <section className="card health-card" aria-labelledby="health-title">
      <header className="card-header">
        <div>
          <h2 id="health-title">{t("health.title")}</h2>
          <p className="muted">{t("health.subtitle")}</p>
        </div>
        <span className="status-pill" data-status={data.status}>
          {statusLabel(data.status, t)}
        </span>
      </header>

      <dl className="meta-grid">
        <div>
          <dt>{t("health.version")}</dt>
          <dd>{data.version}</dd>
        </div>
        <div>
          <dt>{t("health.environment")}</dt>
          <dd>{data.environment}</dd>
        </div>
        <div>
          <dt>{t("health.uptime")}</dt>
          <dd>{formatUptime(data.uptimeMs)}</dd>
        </div>
      </dl>

      <ul className="check-list">
        {data.checks.map((check) => (
          <CheckRow key={check.name} check={check} />
        ))}
      </ul>
    </section>
  );
}

function HealthSkeleton() {
  const { t } = useI18n();
  return (
    <section className="card health-card" aria-busy="true">
      <header className="card-header">
        <div>
          <h2>{t("health.title")}</h2>
          <p className="muted">{t("common.loading")}</p>
        </div>
      </header>
      <div className="skeleton-line" />
      <div className="skeleton-line short" />
    </section>
  );
}

function HealthError({ onRetry }: { onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <section className="card health-card" role="alert">
      <header className="card-header">
        <div>
          <h2>{t("health.title")}</h2>
          <p className="muted">{t("health.unreachable")}</p>
        </div>
        <span className="status-pill" data-status="DOWN">
          {t("status.DOWN")}
        </span>
      </header>
      <p className="hint">{t("health.hint")}</p>
      <button type="button" className="button" onClick={onRetry}>
        {t("common.retry")}
      </button>
    </section>
  );
}

export function Dashboard() {
  const { t } = useI18n();
  const { hasAnyPermission } = useAuth();
  const health = useHealth();

  const modules = MODULES.filter(
    (module) => !module.permissions || hasAnyPermission(...module.permissions),
  );

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("dashboard.title")}</h1>
          <p className="muted">{t("dashboard.subtitle")}</p>
        </div>
        <div className="header-actions">
          <button
            type="button"
            className="button button-ghost"
            onClick={() => void health.refetch()}
            disabled={health.isFetching}
          >
            {t("common.refresh")}
          </button>
        </div>
      </header>

      <section className="card" aria-labelledby="modules-title">
        <header className="card-header">
          <div>
            <h2 id="modules-title">{t("dashboard.modules")}</h2>
            <p className="muted">{t("dashboard.modulesHint")}</p>
          </div>
        </header>
        <div className="module-grid">
          {modules.map((module) => (
            <Link key={module.to} to={module.to} className="module-tile">
              <span className="module-icon" aria-hidden="true">
                <Icon name={module.icon} />
              </span>
              <span className="module-body">
                <strong>{t(module.titleKey)}</strong>
                <small>{t(module.descKey)}</small>
              </span>
              <span className="module-arrow" aria-hidden="true">
                ›
              </span>
            </Link>
          ))}
        </div>
      </section>

      {health.isPending ? (
        <HealthSkeleton />
      ) : health.isError ? (
        <HealthError onRetry={() => void health.refetch()} />
      ) : (
        <HealthPanel data={health.data} />
      )}
    </div>
  );
}
