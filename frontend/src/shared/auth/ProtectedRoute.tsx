import type { ReactNode } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext";
import { useI18n } from "../i18n";

/** Blocks unauthenticated users and forces the first-login password change. */
export function ProtectedRoute() {
  const { status, user } = useAuth();
  const location = useLocation();

  if (status === "loading") {
    return (
      <div className="page-loader" role="status" aria-live="polite">
        <span className="spinner" aria-hidden="true" />
      </div>
    );
  }

  if (status !== "authenticated" || !user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (user.mustChangePassword) {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
}

interface PermissionGateProps {
  anyOf: string[];
  children: ReactNode;
}

/** Renders children only when the current user holds at least one permission. */
export function PermissionGate({ anyOf, children }: PermissionGateProps) {
  const { hasAnyPermission } = useAuth();
  const { t } = useI18n();

  if (!hasAnyPermission(...anyOf)) {
    return (
      <div className="page">
        <section className="card empty-state">
          <h2>{t("admin.forbidden.title")}</h2>
          <p className="muted">{t("admin.forbidden.body")}</p>
        </section>
      </div>
    );
  }

  return <>{children}</>;
}
