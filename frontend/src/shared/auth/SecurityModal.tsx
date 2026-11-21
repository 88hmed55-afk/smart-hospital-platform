import { useState, type FormEvent } from "react";
import { api } from "../api/client";
import { useAuth } from "./AuthContext";
import { describeApiError } from "./errors";
import { useI18n } from "../i18n";
import { Modal } from "../ui/Modal";

interface MfaSetup {
  secret: string;
  otpauthUrl: string;
}

export function SecurityModal({ onClose }: { onClose: () => void }) {
  const { t, locale } = useI18n();
  const { user, refresh } = useAuth();
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!user) {
    return null;
  }

  async function beginSetup() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const result = await api<MfaSetup>("/auth/mfa/setup", { method: "POST" });
      setSetup(result);
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  async function enableMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api("/auth/mfa/enable", {
        method: "POST",
        body: JSON.stringify({ code: code.trim() }),
      });
      await refresh();
      setSetup(null);
      setCode("");
      setSuccess(t("security.mfa.enabled"));
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  async function disableMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api("/auth/mfa/disable", {
        method: "POST",
        body: JSON.stringify({ password }),
      });
      await refresh();
      setPassword("");
      setSuccess(t("security.mfa.disabled"));
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={t("security.title")} onClose={onClose}>
      <div className="security-panel">
        <div className="security-status">
          <span className="muted">{t("security.mfa.status")}</span>
          <span
            className={`badge ${user.mfaEnabled ? "badge-success" : "badge-muted"}`}
          >
            {user.mfaEnabled ? t("security.mfa.on") : t("security.mfa.off")}
          </span>
        </div>

        {error && (
          <div className="alert alert-danger" role="alert">
            {error}
          </div>
        )}
        {success && (
          <div className="alert alert-success" role="status">
            {success}
          </div>
        )}

        {user.mfaEnabled ? (
          <form className="stack-form" onSubmit={disableMfa}>
            <p className="muted">{t("security.mfa.disableHint")}</p>
            <label className="field">
              <span>{t("auth.password")}</span>
              <input
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <button type="submit" className="button button-danger" disabled={busy}>
              {busy ? t("common.saving") : t("security.mfa.disable")}
            </button>
          </form>
        ) : setup ? (
          <form className="stack-form" onSubmit={enableMfa}>
            <p className="muted">{t("security.mfa.scanHint")}</p>
            <div className="secret-box">
              <span className="muted">{t("security.mfa.secret")}</span>
              <code>{setup.secret}</code>
            </div>
            <div className="secret-box">
              <span className="muted">{t("security.mfa.uri")}</span>
              <code className="secret-uri">{setup.otpauthUrl}</code>
            </div>
            <label className="field">
              <span>{t("security.mfa.code")}</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                required
                value={code}
                onChange={(event) => setCode(event.target.value)}
              />
            </label>
            <div className="form-actions">
              <button type="submit" className="button" disabled={busy}>
                {busy ? t("common.saving") : t("security.mfa.enable")}
              </button>
              <button
                type="button"
                className="button button-ghost"
                onClick={() => setSetup(null)}
                disabled={busy}
              >
                {t("common.cancel")}
              </button>
            </div>
          </form>
        ) : (
          <div className="stack-form">
            <p className="muted">{t("security.mfa.enableHint")}</p>
            <button
              type="button"
              className="button"
              onClick={() => void beginSetup()}
              disabled={busy}
            >
              {busy ? t("common.loading") : t("security.mfa.start")}
            </button>
          </div>
        )}
      </div>
    </Modal>
  );
}
