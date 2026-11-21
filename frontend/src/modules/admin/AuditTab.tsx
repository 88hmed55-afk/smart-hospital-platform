import { useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Pagination } from "../../shared/ui/Pagination";
import {
  adminApi,
  formatDateTime,
  type AuditFilters,
} from "./adminApi";

export function AuditTab() {
  const { t, locale } = useI18n();
  const [action, setAction] = useState("");
  const [entityType, setEntityType] = useState("");
  const [filters, setFilters] = useState<AuditFilters>({});
  const [page, setPage] = useState(0);

  const auditQuery = useQuery({
    queryKey: ["admin", "audit", filters, page],
    queryFn: () => adminApi.audit({ ...filters, page }),
  });

  const verifyMutation = useMutation({ mutationFn: adminApi.verifyAudit });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setFilters({
      action: action.trim() || undefined,
      entityType: entityType.trim() || undefined,
    });
  }

  const data = auditQuery.data;
  const verification = verifyMutation.data;

  return (
    <section className="card">
      <div className="toolbar">
        <form className="search-form" onSubmit={submit}>
          <input
            type="text"
            value={action}
            onChange={(event) => setAction(event.target.value)}
            placeholder={t("admin.audit.filterAction")}
            aria-label={t("admin.audit.action")}
          />
          <input
            type="text"
            value={entityType}
            onChange={(event) => setEntityType(event.target.value)}
            placeholder={t("admin.audit.filterEntity")}
            aria-label={t("admin.audit.entityType")}
          />
          <button type="submit" className="button button-ghost">
            {t("common.search")}
          </button>
        </form>
        <button
          type="button"
          className="button"
          onClick={() => verifyMutation.mutate()}
          disabled={verifyMutation.isPending}
        >
          {verifyMutation.isPending
            ? t("common.loading")
            : t("admin.audit.verify")}
        </button>
      </div>

      {verifyMutation.isError && (
        <div className="alert alert-danger" role="alert">
          {describeApiError(
            verifyMutation.error,
            locale,
            t("common.loadError"),
          )}
        </div>
      )}
      {verification && (
        <div
          className={`alert ${
            verification.valid ? "alert-success" : "alert-danger"
          }`}
          role="status"
        >
          {verification.valid
            ? t("admin.audit.verified")
            : t("admin.audit.broken")}{" "}
          · {t("admin.audit.checked")}: {verification.checked}
          {verification.brokenAt != null && (
            <> · #{verification.brokenAt}</>
          )}
        </div>
      )}

      {auditQuery.isLoading ? (
        <p className="muted">{t("common.loading")}</p>
      ) : auditQuery.isError ? (
        <div className="alert alert-danger" role="alert">
          {describeApiError(auditQuery.error, locale, t("common.loadError"))}
        </div>
      ) : !data || data.items.length === 0 ? (
        <div className="empty-inline">{t("admin.audit.empty")}</div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("admin.audit.time")}</th>
                <th>{t("admin.audit.action")}</th>
                <th>{t("admin.audit.entity")}</th>
                <th>{t("admin.audit.actor")}</th>
                <th>{t("admin.audit.ip")}</th>
                <th>{t("common.details")}</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.id}>
                  <td className="mono small">
                    {formatDateTime(item.createdAt, locale)}
                  </td>
                  <td>
                    <span className="chip">{item.action}</span>
                  </td>
                  <td>
                    <div>{item.entityType}</div>
                    <div className="muted mono small">
                      {item.entityPublicId ?? "—"}
                    </div>
                  </td>
                  <td className="mono small">{item.actorUserId ?? "—"}</td>
                  <td className="mono small">{item.ip ?? "—"}</td>
                  <td>
                    <details className="audit-details">
                      <summary>{t("admin.audit.details")}</summary>
                      <div className="audit-detail">
                        <div>
                          <span className="muted small">
                            {t("admin.audit.before")}
                          </span>
                          <pre>{item.before ?? "—"}</pre>
                        </div>
                        <div>
                          <span className="muted small">
                            {t("admin.audit.after")}
                          </span>
                          <pre>{item.after ?? "—"}</pre>
                        </div>
                        <div>
                          <span className="muted small">
                            {t("admin.audit.reason")}
                          </span>
                          <pre>{item.reason ?? "—"}</pre>
                        </div>
                        <div>
                          <span className="muted small">
                            {t("admin.audit.trace")}
                          </span>
                          <pre>{item.traceId ?? "—"}</pre>
                        </div>
                      </div>
                    </details>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          total={data.total}
          onChange={setPage}
        />
      )}
    </section>
  );
}
