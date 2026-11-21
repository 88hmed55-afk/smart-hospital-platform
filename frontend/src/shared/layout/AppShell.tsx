import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { SecurityModal } from "../auth/SecurityModal";
import { useI18n } from "../i18n";
import { Icon } from "../ui/Icon";
import { NotificationBell } from "./NotificationBell";

interface NavItem {
  to: string;
  key: string;
  icon: string;
  permissions?: string[];
}

interface NavGroup {
  key: string;
  items: NavItem[];
}

const ADMIN_NAV_PERMISSIONS = [
  "IAM:READ",
  "IAM:WRITE",
  "IAM:BREAK_GLASS",
  "AUDIT:READ",
  "SYS:CONFIG",
];

export function AppShell() {
  const { t, toggleLocale, locale } = useI18n();
  const { user, logout, hasAnyPermission } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [securityOpen, setSecurityOpen] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
  const showAdmin = hasAnyPermission(...ADMIN_NAV_PERMISSIONS);

  const groups = useMemo<NavGroup[]>(() => {
    const base: NavGroup[] = [
      {
        key: "nav.group.clinical",
        items: [
          { to: "/app/dashboard", key: "nav.dashboard", icon: "dashboard" },
          {
            to: "/app/patients",
            key: "nav.patients",
            icon: "patients",
            permissions: ["PAT:READ"],
          },
          {
            to: "/app/appointments",
            key: "nav.appointments",
            icon: "appointments",
            permissions: ["SCH:READ"],
          },
          {
            to: "/app/clinical",
            key: "nav.clinical",
            icon: "clinical",
            permissions: ["EMR:READ"],
          },
          {
            to: "/app/er",
            key: "nav.er",
            icon: "emergency",
            permissions: ["ER:READ"],
          },
        ],
      },
      {
        key: "nav.group.operations",
        items: [
          {
            to: "/app/admission",
            key: "nav.admission",
            icon: "admission",
            permissions: ["ADT:READ"],
          },
          {
            to: "/app/nursing",
            key: "nav.nursing",
            icon: "nursing",
            permissions: ["NUR:READ"],
          },
          {
            to: "/app/laboratory",
            key: "nav.laboratory",
            icon: "laboratory",
            permissions: ["LAB:READ"],
          },
          {
            to: "/app/pharmacy",
            key: "nav.pharmacy",
            icon: "pharmacy",
            permissions: ["PHR:READ"],
          },
          { to: "/app/billing", key: "nav.billing", icon: "billing", permissions: ["BIL:READ"] },
          {
            to: "/app/inventory",
            key: "nav.inventory",
            icon: "inventory",
            permissions: ["INV:READ"],
          },
        ],
      },
      {
        key: "nav.group.governance",
        items: [{ to: "/app/analytics", key: "nav.analytics", icon: "analytics" }],
      },
    ];
    if (showAdmin) {
      base[2].items.push({ to: "/app/admin", key: "nav.admin", icon: "admin" });
    }
    return base
      .map((group) => ({
        ...group,
        items: group.items.filter(
          (item) => !item.permissions || hasAnyPermission(...item.permissions),
        ),
      }))
      .filter((group) => group.items.length > 0);
  }, [showAdmin, hasAnyPermission]);

  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  const current = useMemo(() => {
    let match: { group: string; item: NavItem } | null = null;
    for (const group of groups) {
      for (const item of group.items) {
        const hit =
          location.pathname === item.to ||
          location.pathname.startsWith(`${item.to}/`);
        if (hit && (!match || item.to.length > match.item.to.length)) {
          match = { group: group.key, item };
        }
      }
    }
    return match;
  }, [groups, location.pathname]);

  const displayName = user
    ? locale === "ar"
      ? user.displayNameAr
      : user.displayNameEn || user.displayNameAr
    : t("topbar.user");
  const roleLabel = user?.roles.join(" · ") ?? t("topbar.role");
  const initial = displayName.trim().slice(0, 1).toUpperCase();

  async function handleLogout() {
    await logout();
    navigate("/login", { replace: true });
  }

  return (
    <div className={`shell${navOpen ? " is-nav-open" : ""}`}>
      <aside className="sidebar" aria-label={t("app.name")}>
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            <span />
            <span />
          </div>
          <div className="brand-text">
            <strong>{t("app.name")}</strong>
            <small>{t("app.tagline")}</small>
          </div>
        </div>

        <nav className="nav" aria-label={t("app.name")}>
          {groups.map((group) => (
            <section key={group.key} className="nav-group">
              <h2 className="nav-heading">{t(group.key)}</h2>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `nav-link${isActive ? " is-active" : ""}`
                  }
                  onClick={() => setNavOpen(false)}
                >
                  <Icon name={item.icon} />
                  <span>{t(item.key)}</span>
                </NavLink>
              ))}
            </section>
          ))}
        </nav>

        <div className="sidebar-footer">
          <span className="phase-chip">{t("shell.phase")}</span>
          <span className="build-tag">{t("shell.build")}</span>
        </div>
      </aside>

      {navOpen && (
        <button
          type="button"
          className="nav-backdrop"
          aria-label={t("common.close")}
          onClick={() => setNavOpen(false)}
        />
      )}

      <div className="shell-main">
        <header className="topbar">
          <div className="topbar-context">
            <button
              type="button"
              className="nav-toggle"
              onClick={() => setNavOpen((value) => !value)}
              aria-label={t("nav.menu")}
              aria-expanded={navOpen}
            >
              <Icon name="menu" />
            </button>
            <nav className="topbar-crumbs" aria-label={t("shell.breadcrumb")}>
              <span className="crumb-group">
                {current ? t(current.group) : t("app.name")}
              </span>
              <span className="crumb-sep" aria-hidden="true">
                /
              </span>
              <span className="crumb-current">
                {current ? t(current.item.key) : t("app.tagline")}
              </span>
            </nav>
          </div>
          <div className="topbar-actions">
            <span className="topbar-status">
              <span className="topbar-dot" aria-hidden="true" />
              LAN · HTTPS
            </span>
            <NotificationBell />
            <button
              type="button"
              className="lang-button"
              onClick={toggleLocale}
              aria-label={t("topbar.language")}
              lang={locale === "ar" ? "en" : "ar"}
            >
              {t("topbar.language")}
            </button>
            <button
              type="button"
              className="icon-button"
              onClick={() => setSecurityOpen(true)}
              aria-label={t("security.title")}
              title={t("security.title")}
            >
              <Icon name="key" />
            </button>
            <div className="user-chip" title={`${displayName} · ${roleLabel}`}>
              <span className="avatar" aria-hidden="true">
                {initial}
              </span>
              <span className="user-meta">
                <strong>{displayName}</strong>
                <small>{roleLabel}</small>
              </span>
            </div>
            <button
              type="button"
              className="icon-button"
              onClick={() => void handleLogout()}
              aria-label={t("auth.logout")}
              title={t("auth.logout")}
            >
              <Icon name="logout" />
            </button>
          </div>
        </header>

        <main className="content">
          <Outlet />
        </main>

        <footer className="footer">{t("footer.note")}</footer>
      </div>

      {securityOpen && <SecurityModal onClose={() => setSecurityOpen(false)} />}
    </div>
  );
}
