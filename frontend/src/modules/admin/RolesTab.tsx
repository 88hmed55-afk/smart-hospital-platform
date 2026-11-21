import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { adminApi, type PermissionItem } from "./adminApi";

export function RolesTab() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("IAM:WRITE");
  const queryClient = useQueryClient();

  const rolesQuery = useQuery({
    queryKey: ["admin", "roles"],
    queryFn: adminApi.roles,
  });
  const permissionsQuery = useQuery({
    queryKey: ["admin", "permissions"],
    queryFn: adminApi.permissions,
  });

  const [selectedCode, setSelectedCode] = useState<string>("");
  const [checked, setChecked] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);

  const roles = rolesQuery.data ?? [];
  const role =
    roles.find((item) => item.code === selectedCode) ?? roles[0] ?? null;
  const roleKey = role ? `${role.publicId}:${role.permissions.join("|")}` : "";

  useEffect(() => {
    setChecked(role?.permissions ?? []);
    setSaved(false);
  }, [roleKey]);

  const grouped = useMemo(() => {
    const map = new Map<string, PermissionItem[]>();
    for (const permission of permissionsQuery.data ?? []) {
      const list = map.get(permission.domain) ?? [];
      list.push(permission);
      map.set(permission.domain, list);
    }
    return [...map.entries()].sort(([left], [right]) =>
      left.localeCompare(right),
    );
  }, [permissionsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!role) {
        throw new Error("No role selected");
      }
      return adminApi.updateRolePermissions(role.publicId, checked);
    },
    onSuccess: () => {
      setSaved(true);
      void queryClient.invalidateQueries({ queryKey: ["admin", "roles"] });
    },
  });

  function togglePermission(code: string) {
    setSaved(false);
    setChecked((current) =>
      current.includes(code)
        ? current.filter((item) => item !== code)
        : [...current, code],
    );
  }

  function toggleDomain(permissions: PermissionItem[]) {
    setSaved(false);
    const codes = permissions.map((permission) => permission.code);
    const allSelected = codes.every((code) => checked.includes(code));
    setChecked((current) =>
      allSelected
        ? current.filter((code) => !codes.includes(code))
        : [...new Set([...current, ...codes])],
    );
  }

  if (rolesQuery.isLoading || permissionsQuery.isLoading) {
    return <p className="muted">{t("common.loading")}</p>;
  }

  if (rolesQuery.isError || permissionsQuery.isError) {
    return (
      <div className="alert alert-danger" role="alert">
        {describeApiError(
          rolesQuery.error ?? permissionsQuery.error,
          locale,
          t("common.loadError"),
        )}
      </div>
    );
  }

  if (!role) {
    return <div className="empty-inline">{t("admin.roles.empty")}</div>;
  }

  return (
    <section className="card">
      <div className="toolbar">
        <label className="field field-inline">
          <span>{t("admin.roles.select")}</span>
          <select
            value={role.code}
            onChange={(event) => setSelectedCode(event.target.value)}
          >
            {roles.map((item) => (
              <option key={item.code} value={item.code}>
                {locale === "ar" ? item.nameAr : item.nameEn} ({item.code})
              </option>
            ))}
          </select>
        </label>
        <span className="badge badge-muted">
          {role.permissionCount} {t("admin.roles.permissions")}
        </span>
      </div>

      {saved && (
        <div className="alert alert-success" role="status">
          {t("admin.roles.saved")}
        </div>
      )}
      {saveMutation.isError && (
        <div className="alert alert-danger" role="alert">
          {describeApiError(saveMutation.error, locale, t("common.saveError"))}
        </div>
      )}

      <div className="domain-list">
        {grouped.map(([domain, permissions]) => {
          const allSelected = permissions.every((permission) =>
            checked.includes(permission.code),
          );
          return (
            <div key={domain} className="domain-block">
              <div className="domain-head">
                <label className="checkbox-item">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    onChange={() => toggleDomain(permissions)}
                    disabled={!canWrite}
                  />
                  <strong>{domain}</strong>
                </label>
                <span className="muted small">
                  {permissions.filter((permission) =>
                    checked.includes(permission.code),
                  ).length}{" "}
                  / {permissions.length}
                </span>
              </div>
              <div className="checkbox-grid">
                {permissions.map((permission) => (
                  <label key={permission.code} className="checkbox-item">
                    <input
                      type="checkbox"
                      checked={checked.includes(permission.code)}
                      onChange={() => togglePermission(permission.code)}
                      disabled={!canWrite}
                    />
                    <span className="mono small">{permission.code}</span>
                  </label>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      {canWrite && (
        <div className="form-actions">
          <button
            type="button"
            className="button"
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending}
          >
            {saveMutation.isPending ? t("common.saving") : t("common.save")}
          </button>
        </div>
      )}
    </section>
  );
}
