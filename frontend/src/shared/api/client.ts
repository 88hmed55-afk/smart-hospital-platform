export interface ApiErrorBody {
  code?: string;
  messageAr?: string;
  messageEn?: string;
  traceId?: string;
  fieldErrors?: Record<string, string>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly messageAr?: string;
  readonly messageEn?: string;
  readonly traceId?: string;
  readonly fieldErrors?: Record<string, string>;

  constructor(status: number, body: ApiErrorBody, traceId?: string) {
    super(body.messageEn ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.messageAr = body.messageAr;
    this.messageEn = body.messageEn;
    this.traceId = body.traceId ?? traceId;
    this.fieldErrors = body.fieldErrors;
  }
}

/** Emitted when a request is rejected as unauthenticated (expired session). */
export const AUTH_EVENT = "sh:unauthorized";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(";")) {
    const value = part.trim();
    if (value.startsWith(prefix)) {
      return decodeURIComponent(value.slice(prefix.length));
    }
  }
  return null;
}

/**
 * Single API entry point. The session cookie is HttpOnly; the browser attaches
 * it automatically. No token is ever stored in JavaScript (ADR-003).
 *
 * <p>The CSRF token is a double-submit cookie: the server sets a readable
 * {@code SH_CSRF} cookie and the client echoes it in {@code X-CSRF-Token} for
 * every unsafe method. The token is never kept in application state.
 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (UNSAFE_METHODS.has(method)) {
    const csrf = readCookie("SH_CSRF");
    if (csrf) {
      headers.set("X-CSRF-Token", csrf);
    }
  }

  const response = await fetch(`/api/v1${path}`, {
    ...init,
    method,
    credentials: "include",
    headers,
  });

  if (!response.ok) {
    let body: ApiErrorBody = {};
    try {
      body = (await response.json()) as ApiErrorBody;
    } catch {
      // Non-JSON error page: keep the status and trace id only.
    }
    const isCredentialCheck = path.startsWith("/auth/login")
      || path.startsWith("/auth/mfa/");
    if (response.status === 401 && !isCredentialCheck) {
      window.dispatchEvent(new Event(AUTH_EVENT));
    }
    throw new ApiError(
      response.status,
      body,
      response.headers.get("X-Trace-Id") ?? undefined,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
