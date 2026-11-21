import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { adminApi, formatDateTime, type SettingItem } from "./adminApi";

export function SettingsTab() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("SYS:CONFIG");

  const settingsQuery = useQuery({
    queryKey: ["admin", "settings"],
    queryFn: adminApi.settings,
  });

  const [category, setCategory] = useState("");

  const grouped = useMemo(() => {
    const map = new Map<string, SettingItem[]>();
    for (const setting of settingsQuery.data ?? []) {
      const list = map.get(setting.category) ?? [];
      list.push(setting);
      map.set(setting.category, list);
    }
    return [...map.entries()].sort(([left], [right]) =>
      left.localeCompare(right),
    );
  }, [settingsQuery.data]);

  const categories = grouped.map(([name]) => name);
  const visible = category
    ? grouped.filter(([name]) => name === category)
    : grouped;
  const visibleCount = visible.reduce((total, [, rows]) => total + rows.length, 0);

  return (
    <section className="card">
      {settingsQuery.isLoading && <p className="muted">{t("common.loading")}</p>}

      {settingsQuery.isError && (
        <div className="alert alert-danger" role="alert">
          {describeApiError(settingsQuery.error, locale, t("common.loadError"))}
        </div>
      )}

      {!settingsQuery.isLoading && !settingsQuery.isError && grouped.length === 0 && (
        <div className="empty-inline">{t("admin.settings.empty")}</div>
      )}

      {grouped.length > 0 && (
        <>
          <div className="toolbar">
            <label className="field field-inline">
              <span>{t("admin.settings.category")}</span>
              <select
                value={category}
                onChange={(event) => setCategory(event.target.value)}
              >
                <option value="">{t("common.all")}</option>
                {categories.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
            <span className="muted">
              {visibleCount} {t("admin.settings.items")}
            </span>
          </div>

          {visible.map(([groupName, settings]) => (
            <div key={groupName} className="settings-group">
              <h3>{groupName}</h3>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>{t("admin.settings.key")}</th>
                      <th>{t("admin.settings.value")}</th>
                      <th>{t("admin.settings.type")}</th>
                      <th>{t("admin.settings.updated")}</th>
                      <th>{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {settings.map((setting) => (
                      <SettingRow
                        key={setting.key}
                        setting={setting}
                        canWrite={canWrite}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}
        </>
      )}
    </section>
  );
}

function SettingRow({
  setting,
  canWrite,
}: {
  setting: SettingItem;
  canWrite: boolean;
}) {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const initial = setting.sensitive ? "" : (setting.value ?? "");
  const [value, setValue] = useState(initial);
  const [dirty, setDirty] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setValue(setting.sensitive ? "" : (setting.value ?? ""));
    setDirty(false);
    setSaved(false);
  }, [setting.key, setting.value, setting.version, setting.sensitive]);

  const mutation = useMutation({
    mutationFn: () => adminApi.updateSetting(setting.key, value),
    onSuccess: () => {
      setDirty(false);
      setSaved(true);
      if (setting.sensitive) {
        setValue("");
      }
      void queryClient.invalidateQueries({ queryKey: ["admin", "settings"] });
    },
  });

  const disabled =
    !canWrite || !dirty || (setting.sensitive && value.trim() === "");

  function input() {
    if (setting.valueType === "BOOL") {
      return (
        <select
          value={value || "false"}
          onChange={(event) => {
            setValue(event.target.value);
            setDirty(true);
            setSaved(false);
          }}
          disabled={!canWrite}
        >
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      );
    }
    if (setting.valueType === "JSON") {
      return (
        <textarea
          rows={2}
          value={value}
          onChange={(event) => {
            setValue(event.target.value);
            setDirty(true);
            setSaved(false);
          }}
          disabled={!canWrite}
        />
      );
    }
    return (
      <input
        type={setting.valueType === "INT" ? "number" : "text"}
        value={value}
        placeholder={setting.sensitive ? (setting.value ?? "") : undefined}
        onChange={(event) => {
          setValue(event.target.value);
          setDirty(true);
          setSaved(false);
        }}
        disabled={!canWrite}
      />
    );
  }

  return (
    <tr>
      <td>
        <strong className="mono small">{setting.key}</strong>
        {setting.sensitive && (
          <div>
            <span className="badge badge-warn">{t("admin.settings.sensitive")}</span>
          </div>
        )}
      </td>
      <td className="setting-value">{input()}</td>
      <td>
        <span className="chip">{setting.valueType}</span>
      </td>
      <td className="mono small">
        {formatDateTime(setting.updatedAt, locale)}
      </td>
      <td>
        <div className="row-actions">
          <button
            type="button"
            className="button button-ghost button-sm"
            onClick={() => mutation.mutate()}
            disabled={disabled || mutation.isPending}
          >
            {mutation.isPending ? t("common.saving") : t("admin.settings.save")}
          </button>
          {saved && (
            <span className="badge badge-success">{t("admin.settings.saved")}</span>
          )}
        </div>
        {mutation.isError && (
          <div className="field-error">
            {describeApiError(mutation.error, locale, t("common.saveError"))}
          </div>
        )}
      </td>
    </tr>
  );
}
