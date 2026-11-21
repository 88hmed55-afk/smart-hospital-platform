import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, AUTH_EVENT } from "../api/client";

export interface ScopeView {
  type: string;
  refId: number;
}

export interface AuthUser {
  publicId: string;
  username: string;
  displayNameAr: string;
  displayNameEn: string | null;
  roles: string[];
  permissions: string[];
  scopes: ScopeView[];
  mustChangePassword: boolean;
  mfaEnabled: boolean;
}

export interface SessionResponse {
  user: AuthUser;
  csrfToken: string;
  mfaRequired: boolean;
}

export type AuthStatus = "loading" | "anonymous" | "authenticated";

export interface LoginOutcome {
  mfaRequired: boolean;
  user: AuthUser;
}

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  pendingUser: AuthUser | null;
  mfaPending: boolean;
  login: (username: string, password: string) => Promise<LoginOutcome>;
  verifyMfa: (code: string) => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  hasPermission: (code: string) => boolean;
  hasAnyPermission: (...codes: string[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);
  const [pendingUser, setPendingUser] = useState<AuthUser | null>(null);

  const clear = useCallback(() => {
    setUser(null);
    setPendingUser(null);
    setStatus("anonymous");
  }, []);

  const refresh = useCallback(async () => {
    try {
      const result = await api<SessionResponse>("/auth/me");
      setUser(result.user);
      setPendingUser(null);
      setStatus("authenticated");
    } catch {
      clear();
    }
  }, [clear]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    window.addEventListener(AUTH_EVENT, clear);
    return () => window.removeEventListener(AUTH_EVENT, clear);
  }, [clear]);

  const login = useCallback(
    async (username: string, password: string): Promise<LoginOutcome> => {
      const result = await api<SessionResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });
      if (result.mfaRequired) {
        setPendingUser(result.user);
        setUser(null);
        setStatus("anonymous");
        return { mfaRequired: true, user: result.user };
      }
      setPendingUser(null);
      setUser(result.user);
      setStatus("authenticated");
      return { mfaRequired: false, user: result.user };
    },
    [],
  );

  const verifyMfa = useCallback(async (code: string) => {
    const result = await api<SessionResponse>("/auth/mfa/verify", {
      method: "POST",
      body: JSON.stringify({ code }),
    });
    setPendingUser(null);
    setUser(result.user);
    setStatus("authenticated");
  }, []);

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      const result = await api<SessionResponse>("/auth/change-password", {
        method: "POST",
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      setUser(result.user);
      setStatus("authenticated");
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      await api<void>("/auth/logout", { method: "POST" });
    } catch {
      // The local session is cleared even if the server session already expired.
    }
    clear();
  }, [clear]);

  const hasPermission = useCallback(
    (code: string) => user?.permissions.includes(code) ?? false,
    [user],
  );

  const hasAnyPermission = useCallback(
    (...codes: string[]) => codes.some((code) => hasPermission(code)),
    [hasPermission],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      pendingUser,
      mfaPending: pendingUser !== null,
      login,
      verifyMfa,
      changePassword,
      logout,
      refresh,
      hasPermission,
      hasAnyPermission,
    }),
    [
      status,
      user,
      pendingUser,
      login,
      verifyMfa,
      changePassword,
      logout,
      refresh,
      hasPermission,
      hasAnyPermission,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
