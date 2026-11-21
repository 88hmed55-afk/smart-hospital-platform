import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Pagination } from "../../shared/ui/Pagination";
import { adminApi, formatDateTime, type SessionItem } from "./adminApi";

export function SessionsTab() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("IAM:WRITE");
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);

  const sessionsQuery = useQuery({
    queryKey: ["admin", "sessions", page],
    queryFn: () => adminApi.sessions(page),
  });

  const revokeMutation = useMutation({
    mutationFn: (id: number) => adminApi.revokeSession(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin", "sessions"] });
    },
  });

  function revoke(session: SessionItem) {
    if (!window.confirm(t("admin.sessions.confirmRevoke"))) {
      return;
    }
    revokeMutation.mutate(session.id);
  }

  const data = sessionsQuery.data;

  return (
    <section className="card">
      <div className="toolbar">
        <span className="muted">{t("admin.sessions.hint")}</span>
      </div>

      {revokeMutation.isError && (
        <div className="alert alert-danger" role="alert">
          {describeApiError(
            revokeMutation.error,
            locale,
            t("common.saveError"),
          )}
        </div>
      )}

      {sessionsQuery.isLoading ? (
        <p className="muted">{t("common.loading")}</p>
      ) : sessionsQuery.isError ? (
        <div className="alert alert-danger" role="alert">
          {describeApiError(
            sessionsQuery.error,
            locale,
            t("common.loadError"),
          )}
        </div>
      ) : !data || data.items.length === 0 ? (
        <div className="empty-inline">{t("admin.sessions.empty")}</div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("admin.sessions.user")}</th>
                <th>{t("admin.sessions.ip")}</th>
                <th>{t("admin.sessions.agent")}</th>
                <th>{t("admin.sessions.started")}</th>
                <th>{t("admin.sessions.lastSeen")}</th>
                {canWrite && <th>{t("common.actions")}</th>}
              </tr>
            </thead>
            <tbody>
              {data.items.map((session) => (
                <tr key={session.id}>
                  <td>
                    <strong>{session.username ?? "—"}</strong>
                    <div className="muted small">
                      {session.displayNameAr ?? ""}
                    </div>
                  </td>
                  <td className="mono small">{session.ip ?? "—"}</td>
                  <td className="agent-cell small" title={session.userAgent ?? ""}>
                    {session.userAgent ?? "—"}
                  </td>
                  <td className="mono small">
                    {formatDateTime(session.startedAt, locale)}
                  </td>
                  <td className="mono small">
                    {formatDateTime(session.lastSeenAt, locale)}
                  </td>
                  {canWrite && (
                    <td>
                      <button
                        type="button"
                        className="button button-danger button-sm"
                        onClick={() => revoke(session)}
                        disabled={revokeMutation.isPending}
                      >
                        {t("admin.sessions.revoke")}
                      </button>
                    </td>
                  )}
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
