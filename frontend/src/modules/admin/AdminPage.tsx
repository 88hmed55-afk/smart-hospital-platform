import { useState } from "react";
import { useAuth } from "../../shared/auth/AuthContext";
import { useI18n } from "../../shared/i18n";
import { AuditTab } from "./AuditTab";
import { BreakGlassTab } from "./BreakGlassTab";
import { RolesTab } from "./RolesTab";
import { SessionsTab } from "./SessionsTab";
import { SettingsTab } from "./SettingsTab";
import { UsersTab } from "./UsersTab";

type TabId = "users" | "roles" | "audit" | "sessions" | "breakglass" | "settings";

interface TabDefinition {
  id: TabId;
  label: string;
  anyOf: string[];
}

const TABS: TabDefinition[] = [
  { id: "users", label: "admin.tab.users", anyOf: ["IAM:READ"] },
  { id: "roles", label: "admin.tab.roles", anyOf: ["IAM:READ"] },
  { id: "audit", label: "admin.tab.audit", anyOf: ["AUDIT:READ"] },
  { id: "sessions", label: "admin.tab.sessions", anyOf: ["IAM:READ"] },
  {
    id: "breakglass",
    label: "admin.tab.breakglass",
    anyOf: ["IAM:BREAK_GLASS", "AUDIT:READ", "IAM:WRITE"],
  },
  { id: "settings", label: "admin.tab.settings", anyOf: ["SYS:CONFIG"] },
];

export function AdminPage() {
  const { t } = useI18n();
  const { hasAnyPermission } = useAuth();
  const [selected, setSelected] = useState<TabId | null>(null);

  const visibleTabs = TABS.filter((tab) => hasAnyPermission(...tab.anyOf));
  const active =
    selected && visibleTabs.some((tab) => tab.id === selected)
      ? selected
      : visibleTabs[0]?.id;

  if (!active) {
    return (
      <div className="page">
        <section className="card empty-state">
          <h2>{t("admin.forbidden.title")}</h2>
          <p className="muted">{t("admin.forbidden.body")}</p>
        </section>
      </div>
    );
  }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("admin.title")}</h1>
          <p className="muted">{t("admin.subtitle")}</p>
        </div>
        <span className="phase-badge">{t("admin.phase")}</span>
      </header>

      <div className="tabs" role="tablist" aria-label={t("admin.title")}>
        {visibleTabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            aria-selected={active === tab.id}
            className={`tab${active === tab.id ? " is-active" : ""}`}
            onClick={() => setSelected(tab.id)}
          >
            {t(tab.label)}
          </button>
        ))}
      </div>

      <div className="tab-content">
        {active === "users" && <UsersTab />}
        {active === "roles" && <RolesTab />}
        {active === "audit" && <AuditTab />}
        {active === "sessions" && <SessionsTab />}
        {active === "breakglass" && <BreakGlassTab />}
        {active === "settings" && <SettingsTab />}
      </div>
    </div>
  );
}
