import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";
import { Modal } from "../../shared/ui/Modal";
import { Pagination } from "../../shared/ui/Pagination";
import {
  adminApi,
  formatDateTime,
  type AdminUser,
  type RoleItem,
} from "./adminApi";

export function UsersTab() {
  const { t, locale } = useI18n();
  const { hasPermission } = useAuth();
  const canWrite = hasPermission("IAM:WRITE");
  const canReset = hasPermission("IAM:RESET");
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [term, setTerm] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const usersQuery = useQuery({
    queryKey: ["admin", "users", term, page],
    queryFn: () => adminApi.users(term, page),
  });
  const rolesQuery = useQuery({
    queryKey: ["admin", "roles"],
    queryFn: adminApi.roles,
  });

  const toggleMutation = useMutation({
    mutationFn: (input: { publicId: string; enabled: boolean }) =>
      adminApi.setEnabled(input.publicId, input.enabled),
    onSuccess: () => {
      setNotice(null);
      void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
    },
  });

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setTerm(search.trim());
  }

  function toggle(user: AdminUser) {
    if (user.status === "ACTIVE") {
      if (!window.confirm(t("admin.users.confirmDisable"))) {
        return;
      }
    }
    toggleMutation.mutate({
      publicId: user.publicId,
      enabled: user.status !== "ACTIVE",
    });
  }

  const data = usersQuery.data;
  const error =
    usersQuery.isError || toggleMutation.isError
      ? describeApiError(
          toggleMutation.error ?? usersQuery.error,
          locale,
          t("common.loadError"),
        )
      : null;

  return (
    <section className="card">
      <div className="toolbar">
        <form className="search-form" onSubmit={submitSearch}>
          <input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={t("admin.users.searchPlaceholder")}
            aria-label={t("common.search")}
          />
          <button type="submit" className="button button-ghost">
            {t("common.search")}
          </button>
        </form>
        {canWrite && (
          <button
            type="button"
            className="button"
            onClick={() => setCreating(true)}
            disabled={(rolesQuery.data ?? []).length === 0}
          >
            {t("admin.users.new")}
          </button>
        )}
      </div>

      {notice && (
        <div className="alert alert-success" role="status">
          {notice}
        </div>
      )}
      {error && (
        <div className="alert alert-danger" role="alert">
          {error}
        </div>
      )}

      {usersQuery.isLoading ? (
        <p className="muted">{t("common.loading")}</p>
      ) : !data || data.items.length === 0 ? (
        <div className="empty-inline">{t("admin.users.empty")}</div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t("admin.users.username")}</th>
                <th>{t("admin.users.roles")}</th>
                <th>{t("common.status")}</th>
                <th>{t("admin.users.mfa")}</th>
                <th>{t("admin.users.lastLogin")}</th>
                <th>{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((user) => (
                <tr key={user.publicId}>
                  <td>
                    <strong>{user.username}</strong>
                    <div className="muted small">
                      {user.displayNameAr}
                      {user.displayNameEn ? ` · ${user.displayNameEn}` : ""}
                    </div>
                  </td>
                  <td>
                    <div className="chip-row">
                      {user.roles.map((role) => (
                        <span key={role} className="chip">
                          {role}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td>
                    <span
                      className={`badge ${
                        user.status === "ACTIVE" ? "badge-success" : "badge-danger"
                      }`}
                    >
                      {t(`status.${user.status}`)}
                    </span>
                  </td>
                  <td>{user.mfaEnabled ? t("common.yes") : t("common.no")}</td>
                  <td className="mono small">
                    {formatDateTime(user.lastLoginAt, locale)}
                  </td>
                  <td>
                    <div className="row-actions">
                      {canWrite && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => toggle(user)}
                          disabled={toggleMutation.isPending}
                        >
                          {user.status === "ACTIVE"
                            ? t("admin.users.disable")
                            : t("admin.users.enable")}
                        </button>
                      )}
                      {canReset && (
                        <button
                          type="button"
                          className="button button-ghost button-sm"
                          onClick={() => setResetTarget(user)}
                        >
                          {t("admin.users.reset")}
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

      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          total={data.total}
          onChange={setPage}
        />
      )}

      {creating && (
        <CreateUserForm
          roles={rolesQuery.data ?? []}
          onClose={() => setCreating(false)}
          onCreated={() => {
            setCreating(false);
            setNotice(t("admin.users.created"));
            void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
          }}
        />
      )}

      {resetTarget && (
        <ResetPasswordForm
          user={resetTarget}
          onClose={() => setResetTarget(null)}
          onDone={() => {
            setResetTarget(null);
            setNotice(t("admin.users.resetDone"));
          }}
        />
      )}
    </section>
  );
}

function CreateUserForm({
  roles,
  onClose,
  onCreated,
}: {
  roles: RoleItem[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const { t, locale } = useI18n();
  const [username, setUsername] = useState("");
  const [displayNameAr, setDisplayNameAr] = useState("");
  const [displayNameEn, setDisplayNameEn] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [roleCodes, setRoleCodes] = useState<string[]>(["NURSE"]);

  const mutation = useMutation({
    mutationFn: () =>
      adminApi.createUser({
        username: username.trim(),
        displayNameAr: displayNameAr.trim(),
        displayNameEn: displayNameEn.trim() || undefined,
        email: email.trim() || undefined,
        phone: phone.trim() || undefined,
        password,
        roleCodes,
      }),
    onSuccess: onCreated,
  });

  function toggleRole(code: string) {
    setRoleCodes((current) =>
      current.includes(code)
        ? current.filter((item) => item !== code)
        : [...current, code],
    );
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate();
  }

  return (
    <Modal title={t("admin.users.createTitle")} onClose={onClose}>
      <form className="stack-form" onSubmit={submit}>
        {mutation.isError && (
          <div className="alert alert-danger" role="alert">
            {describeApiError(mutation.error, locale, t("common.saveError"))}
          </div>
        )}
        <div className="form-grid">
          <label className="field">
            <span>{t("admin.users.username")}</span>
            <input
              type="text"
              required
              pattern="[a-z0-9._\-]{3,80}"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("admin.users.password")}</span>
            <input
              type="password"
              required
              minLength={12}
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("admin.users.displayNameAr")}</span>
            <input
              type="text"
              required
              value={displayNameAr}
              onChange={(event) => setDisplayNameAr(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("admin.users.displayNameEn")}</span>
            <input
              type="text"
              value={displayNameEn}
              onChange={(event) => setDisplayNameEn(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("admin.users.email")}</span>
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{t("admin.users.phone")}</span>
            <input
              type="tel"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
            />
          </label>
        </div>

        <fieldset className="fieldset">
          <legend>{t("admin.users.roles")}</legend>
          <div className="checkbox-grid">
            {roles.map((role) => (
              <label key={role.code} className="checkbox-item">
                <input
                  type="checkbox"
                  checked={roleCodes.includes(role.code)}
                  onChange={() => toggleRole(role.code)}
                />
                <span>{locale === "ar" ? role.nameAr : role.nameEn}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="form-actions">
          <button
            type="submit"
            className="button"
            disabled={mutation.isPending || roleCodes.length === 0}
          >
            {mutation.isPending ? t("common.saving") : t("common.create")}
          </button>
          <button
            type="button"
            className="button button-ghost"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            {t("common.cancel")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ResetPasswordForm({
  user,
  onClose,
  onDone,
}: {
  user: AdminUser;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t, locale } = useI18n();
  const [newPassword, setNewPassword] = useState("");

  const mutation = useMutation({
    mutationFn: () => adminApi.resetPassword(user.publicId, newPassword),
    onSuccess: onDone,
  });

  return (
    <Modal title={t("admin.users.resetTitle")} onClose={onClose}>
      <form
        className="stack-form"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate();
        }}
      >
        <p className="muted">
          {t("admin.users.resetFor")} <strong>{user.username}</strong>
        </p>
        {mutation.isError && (
          <div className="alert alert-danger" role="alert">
            {describeApiError(mutation.error, locale, t("common.saveError"))}
          </div>
        )}
        <label className="field">
          <span>{t("admin.users.newPassword")}</span>
          <input
            type="password"
            required
            minLength={12}
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
          <small className="muted">{t("auth.passwordHint")}</small>
        </label>
        <div className="form-actions">
          <button type="submit" className="button" disabled={mutation.isPending}>
            {mutation.isPending ? t("common.saving") : t("common.save")}
          </button>
          <button
            type="button"
            className="button button-ghost"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            {t("common.cancel")}
          </button>
        </div>
      </form>
    </Modal>
  );
}
