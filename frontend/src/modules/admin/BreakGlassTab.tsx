import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import {
  adminApi,
  formatDateTime,
  type BreakGlassItem,
} from "./adminApi";

const STATUS_OPTIONS = ["", "GRANTED", "REQUESTED", "EXPIRED", "REVOKED", "REVIEWED"];

function statusClass(status: string): string {
  switch (status) {
    case "GRANTED":
      return "badge-success";
    case "REQUESTED":
      return "badge-warn";
    case "REVOKED":
      return "badge-danger";
    case "REVIEWED":
      return "badge-info";
    default:
      return "badge-muted";
  }
}

export function BreakGlassTab() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canRequest = hasPermission("IAM:BREAK_GLASS");
  const canReview = hasPermission("AUDIT:READ") || hasPermission("IAM:WRITE");
  const queryClient = useQueryClient();

  const [statusFilter, setStatusFilter] = useState("");
  const [patientPublicId, setPatientPublicId] = useState("");
  const [reason, setReason] = useState("");
  const [minutes, setMinutes] = useState(30);
  const [reviewTarget, setReviewTarget] = useState<BreakGlassItem | null>(null);

  const grantsQuery = useQuery({
    queryKey: ["admin", "break-glass", statusFilter],
    queryFn: () => adminApi.breakGlass(statusFilter || undefined),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: ["admin", "break-glass"] });

  const requestMutation = useMutation({
    mutationFn: () =>
      adminApi.requestBreakGlass(patientPublicId.trim(), reason.trim(), minutes),
    onSuccess: () => {
      setReason("");
      void invalidate();
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (publicId: string) => adminApi.revokeBreakGlass(publicId),
    onSuccess: () => {
      void invalidate();
    },
  });

  const reviewMutation = useMutation({
    mutationFn: (input: { publicId: string; note: string }) =>
      adminApi.reviewBreakGlass(input.publicId, input.note),
    onSuccess: () => {
      setReviewTarget(null);
      void invalidate();
    },
  });

  function submitRequest(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    requestMutation.mutate();
  }

  function revoke(grant: BreakGlassItem) {
    if (!window.confirm(t("admin.bg.confirmRevoke"))) {
      return;
    }
    revokeMutation.mutate(grant.publicId);
  }

  const grants = grantsQuery.data ?? [];

  return (
    <section className="tab-content">
      {canRequest && (
        <form className="card inline-form" onSubmit={submitRequest}>
          <h3>{t("admin.bg.requestTitle")}</h3>
          {requestMutation.isError && (
            <div className="alert alert-danger" role="alert">
              {describeApiError(
                requestMutation.error,
                locale,
                t("common.saveError"),
              )}
            </div>
          )}
          <div className="form-grid">
            <label className="field">
              <span>{t("admin.bg.patient")}</span>
              <input
                type="text"
                required
                minLength={26}
                maxLength={26}
                value={patientPublicId}
                onChange={(event) => setPatientPublicId(event.target.value)}
                placeholder="01M78EYAJSSVKDXATR5KJ4WYFA"
              />
            </label>
            <label className="field">
              <span>{t("admin.bg.minutes")}</span>
              <input
                type="number"
                required
                min={1}
                max={60}
                value={minutes}
                onChange={(event) => setMinutes(Number(event.target.value))}
              />
              <small className="muted">{t("admin.bg.maxNote")}</small>
            </label>
          </div>
          <label className="field">
            <span>{t("admin.bg.reason")}</span>
            <textarea
              required
              minLength={10}
              rows={3}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <small className="muted">{t("admin.bg.reasonHint")}</small>
          </label>
          <div className="form-actions">
            <button
              type="submit"
              className="button"
              disabled={requestMutation.isPending}
            >
              {requestMutation.isPending
                ? t("common.saving")
                : t("admin.bg.request")}
            </button>
          </div>
        </form>
      )}

      <section className="card">
        <div className="toolbar">
          <label className="field field-inline">
            <span>{t("common.status")}</span>
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              {STATUS_OPTIONS.map((status) => (
                <option key={status || "all"} value={status}>
                  {status ? t(`bg.${status}`) : t("common.all")}
                </option>
              ))}
            </select>
          </label>
        </div>

        {revokeMutation.isError && (
          <div className="alert alert-danger" role="alert">
            {describeApiError(revokeMutation.error, locale, t("common.saveError"))}
          </div>
        )}
        {reviewMutation.isError && (
          <div className="alert alert-danger" role="alert">
            {describeApiError(reviewMutation.error, locale, t("common.saveError"))}
          </div>
        )}

        {grantsQuery.isLoading ? (
          <p className="muted">{t("common.loading")}</p>
        ) : grantsQuery.isError ? (
          <div className="alert alert-danger" role="alert">
            {describeApiError(grantsQuery.error, locale, t("common.loadError"))}
          </div>
        ) : grants.length === 0 ? (
          <div className="empty-inline">{t("admin.bg.empty")}</div>
        ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("admin.bg.patient")}</th>
                <th>{t("admin.bg.reason")}</th>
                <th>{t("common.status")}</th>
                <th>{t("admin.bg.granted")}</th>
                <th>{t("admin.bg.expires")}</th>
                <th>{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {grants.map((grant) => (
                <tr key={grant.publicId}>
                  <td className="mono small">{grant.patientPublicId}</td>
                  <td className="reason-cell">{grant.reason}</td>
                  <td>
                    <span className={`badge ${statusClass(grant.status)}`}>
                      {t(`bg.${grant.status}`)}
                    </span>
                  </td>
                  <td className="mono small">
                    {formatDateTime(grant.grantedAt, locale)}
                  </td>
                  <td className="mono small">
                    {formatDateTime(grant.expiresAt, locale)}
                  </td>
                  <td>
                    <div className="row-actions">
                      {grant.status === "GRANTED" && (
                        <button
                          type="button"
                          className="button button-danger button-sm"
                          onClick={() => revoke(grant)}
                          disabled={revokeMutation.isPending}
                        >
                          {t("admin.bg.revoke")}
                        </button>
                      )}
                      {canReview && grant.status !== "REVIEWED" && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => setReviewTarget(grant)}
                        >
                          {t("admin.bg.review")}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        )}
      </section>

      {reviewTarget && (
        <ReviewForm
          grant={reviewTarget}
          busy={reviewMutation.isPending}
          onClose={() => setReviewTarget(null)}
          onSubmit={(note) =>
            reviewMutation.mutate({ publicId: reviewTarget.publicId, note })
          }
        />
      )}
    </section>
  );
}

function ReviewForm({
  grant,
  busy,
  onClose,
  onSubmit,
}: {
  grant: BreakGlassItem;
  busy: boolean;
  onClose: () => void;
  onSubmit: (note: string) => void;
}) {
  const { t } = useI18n();
  const [note, setNote] = useState("");

  return (
    <Modal title={t("admin.bg.reviewTitle")} onClose={onClose}>
      <form
        className="stack-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit(note.trim());
        }}
      >
        <p className="muted">
          {t("admin.bg.patient")}: <strong>{grant.patientPublicId}</strong>
        </p>
        <label className="field">
          <span>{t("admin.bg.reviewNote")}</span>
          <textarea
            rows={3}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </label>
        <div className="form-actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? t("common.saving") : t("admin.bg.markReviewed")}
          </button>
          <button
            type="button"
            className="button button-ghost"
            onClick={onClose}
            disabled={busy}
          >
            {t("common.cancel")}
          </button>
        </div>
      </form>
    </Modal>
  );
}
