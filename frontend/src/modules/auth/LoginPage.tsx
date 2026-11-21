import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../../shared/auth/AuthContext";
import { describeApiError } from "../../shared/auth/errors";
import { useI18n } from "../../shared/i18n";

type Step = "credentials" | "mfa" | "change";

export function LoginPage() {
  const { t, locale, toggleLocale } = useI18n();
  const { status, user, mfaPending, login, verifyMfa, changePassword, logout } =
    useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from =
    (location.state as { from?: string } | null)?.from ?? "/app/dashboard";

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === "authenticated" && user && !user.mustChangePassword) {
    return <Navigate to={from} replace />;
  }

  const step: Step =
    status === "authenticated" && user?.mustChangePassword
      ? "change"
      : mfaPending
        ? "mfa"
        : "credentials";

  const title =
    step === "mfa"
      ? t("auth.mfa.title")
      : step === "change"
        ? t("auth.change.title")
        : t("auth.login.title");
  const subtitle =
    step === "mfa"
      ? t("auth.mfa.subtitle")
      : step === "change"
        ? t("auth.change.subtitle")
        : t("auth.login.subtitle");

  async function submitCredentials(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const outcome = await login(username.trim(), password);
      if (outcome.mfaRequired) {
        setPassword("");
      } else if (outcome.user.mustChangePassword) {
        setCurrentPassword(password);
        setPassword("");
      }
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  async function submitMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await verifyMfa(code.trim());
      setCode("");
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  async function submitChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError(t("auth.change.mismatch"));
      return;
    }
    setBusy(true);
    try {
      await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      navigate(from, { replace: true });
    } catch (caught) {
      setError(describeApiError(caught, locale, t("auth.error.generic")));
    } finally {
      setBusy(false);
    }
  }

  async function cancelMfa() {
    setError(null);
    await logout();
    setCode("");
  }

  return (
    <div className="login-page">
      <div className="login-shell">
        <aside className="login-aside">
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
          <h1>{t("app.name")}</h1>
          <p>{t("auth.aside.lead")}</p>
          <ul className="login-points">
            <li>{t("auth.aside.point1")}</li>
            <li>{t("auth.aside.point2")}</li>
            <li>{t("auth.aside.point3")}</li>
          </ul>
          <small className="login-disclaimer">{t("footer.note")}</small>
        </aside>

        <main className="login-card">
          <header className="login-card-head">
            <div>
              <h2>{title}</h2>
              <p className="muted">{subtitle}</p>
            </div>
            <button
              type="button"
              className="lang-button"
              onClick={toggleLocale}
              aria-label={t("topbar.language")}
              lang={locale === "ar" ? "en" : "ar"}
            >
              {t("topbar.language")}
            </button>
          </header>

          {error && (
            <div className="alert alert-danger" role="alert">
              {error}
            </div>
          )}

          {step === "credentials" && (
            <form className="login-form" onSubmit={submitCredentials}>
              <label className="field">
                <span>{t("auth.username")}</span>
                <input
                  type="text"
                  name="username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("auth.password")}</span>
                <input
                  type="password"
                  name="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </label>
              <button type="submit" className="button button-block" disabled={busy}>
                {busy ? t("auth.signingIn") : t("auth.signIn")}
              </button>
            </form>
          )}

          {step === "mfa" && (
            <form className="login-form" onSubmit={submitMfa}>
              <p className="muted">
                {t("auth.mfa.signedInAs")} <strong>{username}</strong>
              </p>
              <label className="field">
                <span>{t("auth.mfa.code")}</span>
                <input
                  type="text"
                  name="code"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="[0-9]*"
                  maxLength={6}
                  autoFocus
                  required
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                />
              </label>
              <button type="submit" className="button button-block" disabled={busy}>
                {busy ? t("auth.mfa.verifying") : t("auth.mfa.verify")}
              </button>
              <button
                type="button"
                className="button button-ghost button-block"
                onClick={() => void cancelMfa()}
                disabled={busy}
              >
                {t("auth.mfa.back")}
              </button>
            </form>
          )}

          {step === "change" && (
            <form className="login-form" onSubmit={submitChange}>
              <p className="notice">{t("auth.change.forcedNote")}</p>
              <label className="field">
                <span>{t("auth.change.current")}</span>
                <input
                  type="password"
                  name="currentPassword"
                  autoComplete="current-password"
                  required
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                />
              </label>
              <label className="field">
                <span>{t("auth.change.new")}</span>
                <input
                  type="password"
                  name="newPassword"
                  autoComplete="new-password"
                  minLength={12}
                  required
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                />
                <small className="muted">{t("auth.passwordHint")}</small>
              </label>
              <label className="field">
                <span>{t("auth.change.confirm")}</span>
                <input
                  type="password"
                  name="confirmPassword"
                  autoComplete="new-password"
                  minLength={12}
                  required
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                />
              </label>
              <button type="submit" className="button button-block" disabled={busy}>
                {busy ? t("common.saving") : t("auth.change.submit")}
              </button>
            </form>
          )}
        </main>
      </div>
    </div>
  );
}
