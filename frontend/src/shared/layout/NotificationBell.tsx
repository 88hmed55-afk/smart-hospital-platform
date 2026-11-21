import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useI18n } from "../i18n";
import { formatDateTime } from "../ui/format";
import { Icon } from "../ui/Icon";
import {
  notificationsApi,
  type NotificationPreference,
  type NotificationRow,
} from "../../modules/notifications/notificationsApi";

function severityBadge(severity: string): string {
  switch (severity) {
    case "CRITICAL":
      return "badge-danger";
    case "WARNING":
      return "badge-warn";
    default:
      return "badge-muted";
  }
}

type PanelView = "inbox" | "preferences";
type LiveState = "connecting" | "live" | "polling";

export function NotificationBell() {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<PanelView>("inbox");
  const [live, setLive] = useState<LiveState>("connecting");
  const [draft, setDraft] = useState<NotificationPreference[] | null>(null);

  const summary = useQuery({
    queryKey: ["notifications-summary"],
    queryFn: () => notificationsApi.summary(),
    refetchInterval: live === "live" ? false : 30_000,
    staleTime: 15_000,
  });

  const preferences = useQuery({
    queryKey: ["notifications-preferences"],
    queryFn: () => notificationsApi.preferences(),
    enabled: open && view === "preferences",
    staleTime: 60_000,
  });

  useEffect(() => {
    if (draft === null && preferences.data) {
      setDraft(preferences.data.categories);
    }
  }, [draft, preferences.data]);

  // Live stream first, polling as the automatic fallback (ADR-006).
  useEffect(() => {
    if (typeof EventSource === "undefined") {
      setLive("polling");
      return;
    }
    const source = new EventSource("/api/v1/notifications/stream");
    source.addEventListener("ready", () => {
      setLive("live");
      void queryClient.invalidateQueries({ queryKey: ["notifications-summary"] });
    });
    source.addEventListener("notification", () => {
      void queryClient.invalidateQueries({ queryKey: ["notifications-summary"] });
      void queryClient.invalidateQueries({ queryKey: ["notifications-list"] });
    });
    source.onerror = () => {
      // EventSource reconnects on its own; polling covers the gap meanwhile.
      setLive("polling");
    };
    return () => {
      source.close();
    };
  }, [queryClient]);

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["notifications-summary"] }),
      queryClient.invalidateQueries({ queryKey: ["notifications-list"] }),
    ]);
  };

  const markRead = useMutation({
    mutationFn: (publicId: string) => notificationsApi.markRead(publicId),
    onSuccess: () => void invalidate(),
  });
  const markAll = useMutation({
    mutationFn: () => notificationsApi.markAllRead(),
    onSuccess: () => void invalidate(),
  });
  const savePreferences = useMutation({
    mutationFn: (entries: NotificationPreference[]) =>
      notificationsApi.updatePreferences(entries),
    onSuccess: (data) => {
      setDraft(data.categories);
      queryClient.setQueryData(["notifications-preferences"], data);
    },
  });

  const unread = summary.data?.unread ?? 0;
  const items = summary.data?.items ?? [];
  const categories = draft ?? preferences.data?.categories ?? [];
  const severities = preferences.data?.severities ?? ["INFO", "WARNING", "CRITICAL"];

  const title = (row: NotificationRow) =>
    locale === "ar" ? row.titleAr || row.titleEn || "" : row.titleEn || row.titleAr || "";
  const body = (row: NotificationRow) =>
    locale === "ar" ? row.bodyAr || row.bodyEn || "" : row.bodyEn || row.bodyAr || "";

  const patchCategory = (category: string, patch: Partial<NotificationPreference>) => {
    setDraft((current) =>
      (current ?? []).map((entry) =>
        entry.category === category ? { ...entry, ...patch } : entry,
      ),
    );
  };

  const liveLabel =
    live === "live" ? t("not.live") : live === "polling" ? t("not.polling") : t("not.connecting");

  return (
    <div className="notif-wrap">
      <button
        type="button"
        className="icon-button notif-button"
        onClick={() => setOpen((value) => !value)}
        aria-label={t("not.title")}
        aria-expanded={open}
        title={t("not.title")}
      >
        <Icon name="bell" />
        {unread > 0 && (
          <span className="notif-badge" aria-label={`${unread} ${t("not.unread")}`}>
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {open && (
        <>
          <div className="notif-backdrop" onClick={() => setOpen(false)} />
          <div className="notif-panel" role="dialog" aria-label={t("not.title")}>
            <header className="notif-head">
              <div className="notif-head-titles">
                <strong>{view === "inbox" ? t("not.title") : t("not.preferences")}</strong>
                <span className="notif-live" data-state={live}>
                  <span className="notif-live-dot" aria-hidden="true" />
                  {liveLabel}
                </span>
              </div>
              <div className="notif-head-actions">
                {view === "inbox" && unread > 0 && (
                  <button
                    type="button"
                    className="button button-ghost button-sm"
                    disabled={markAll.isPending}
                    onClick={() => markAll.mutate()}
                  >
                    {t("not.markAllRead")}
                  </button>
                )}
                <button
                  type="button"
                  className={`icon-button notif-gear${view === "preferences" ? " is-active" : ""}`}
                  aria-label={t("not.preferences")}
                  title={t("not.preferences")}
                  onClick={() => {
                    setView((current) => (current === "inbox" ? "preferences" : "inbox"));
                    setDraft(null);
                  }}
                >
                  <Icon name="settings" />
                </button>
              </div>
            </header>

            {view === "preferences" ? (
              <div className="notif-prefs">
                <p className="muted notif-prefs-hint">{t("not.preferencesHint")}</p>
                {preferences.isPending ? (
                  <p className="muted notif-empty">{t("common.loading")}</p>
                ) : preferences.isError ? (
                  <p className="field-error notif-empty">{t("common.loadError")}</p>
                ) : (
                  <>
                    <ul className="notif-pref-list">
                      {categories.map((entry) => (
                        <li key={entry.category} className="notif-pref-row">
                          <label className="notif-pref-toggle">
                            <input
                              type="checkbox"
                              checked={entry.enabled}
                              onChange={(event) =>
                                patchCategory(entry.category, {
                                  enabled: event.target.checked,
                                })
                              }
                            />
                            <span>{t(`not.category.${entry.category}`)}</span>
                          </label>
                          <select
                            value={entry.minSeverity}
                            disabled={!entry.enabled}
                            aria-label={`${t(`not.category.${entry.category}`)} — ${t("not.minSeverity")}`}
                            onChange={(event) =>
                              patchCategory(entry.category, {
                                minSeverity: event.target.value,
                              })
                            }
                          >
                            {severities.map((severity) => (
                              <option key={severity} value={severity}>
                                {t(`notSeverity.${severity}`)}
                              </option>
                            ))}
                          </select>
                        </li>
                      ))}
                    </ul>
                    <div className="notif-pref-foot">
                      <button
                        type="button"
                        className="button button-sm"
                        disabled={savePreferences.isPending || draft === null}
                        onClick={() => draft && savePreferences.mutate(draft)}
                      >
                        {t("common.save")}
                      </button>
                      {savePreferences.isSuccess && !savePreferences.isPending && (
                        <span className="notif-saved">{t("not.saved")}</span>
                      )}
                      {savePreferences.isError && (
                        <span className="field-error">{t("common.saveError")}</span>
                      )}
                    </div>
                  </>
                )}
              </div>
            ) : summary.isPending ? (
              <p className="muted notif-empty">{t("common.loading")}</p>
            ) : summary.isError ? (
              <p className="field-error notif-empty">{t("common.loadError")}</p>
            ) : items.length === 0 ? (
              <p className="muted notif-empty">{t("not.empty")}</p>
            ) : (
              <ul className="notif-list">
                {items.map((row) => (
                  <li
                    key={row.publicId}
                    className={`notif-item${row.status === "UNREAD" ? " is-unread" : ""}`}
                  >
                    <div className="notif-item-head">
                      <span className={`badge ${severityBadge(row.severity)}`}>
                        {t(`notSeverity.${row.severity}`)}
                      </span>
                      <strong>{title(row)}</strong>
                      {row.status === "UNREAD" && (
                        <span className="notif-dot" aria-label={t("not.new")} />
                      )}
                    </div>
                    {body(row) && <p className="notif-body">{body(row)}</p>}
                    <div className="notif-item-foot">
                      <span className="muted">{formatDateTime(row.createdAt, locale)}</span>
                      {row.status === "UNREAD" && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          disabled={markRead.isPending}
                          onClick={() => markRead.mutate(row.publicId)}
                        >
                          {t("not.markRead")}
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </div>
  );
}
