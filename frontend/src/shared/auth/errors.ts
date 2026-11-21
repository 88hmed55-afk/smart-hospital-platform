import { ApiError } from "../api/client";
import type { Locale } from "../i18n";

/** Resolves an API failure to a user-facing message in the active locale. */
export function describeApiError(
  error: unknown,
  locale: Locale,
  fallback: string,
): string {
  if (error instanceof ApiError) {
    const localized = locale === "ar" ? error.messageAr : error.messageEn;
    if (localized) {
      return localized;
    }
    if (error.code) {
      return `${error.code}${error.traceId ? ` · ${error.traceId}` : ""}`;
    }
  }
  return fallback;
}
