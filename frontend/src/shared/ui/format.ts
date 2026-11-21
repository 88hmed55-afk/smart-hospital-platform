import type { Locale } from "../i18n";

function toDate(value: string | null | undefined): Date | null {
  if (!value) {
    return null;
  }
  // Date-only values are parsed as local midnight, not UTC midnight.
  const normalized = value.length <= 10 ? `${value}T00:00:00` : value;
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date;
}

function localeTag(locale: Locale): string {
  return locale === "ar" ? "ar-EG" : "en-GB";
}

export function formatDate(value: string | null | undefined, locale: Locale): string {
  const date = toDate(value);
  if (!date) {
    return "—";
  }
  return date.toLocaleDateString(localeTag(locale), {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function formatDateTime(value: string | null | undefined, locale: Locale): string {
  const date = toDate(value);
  if (!date) {
    return "—";
  }
  return date.toLocaleString(localeTag(locale), {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatTime(value: string | null | undefined, locale: Locale): string {
  const date = toDate(value);
  if (!date) {
    return "—";
  }
  return date.toLocaleTimeString(localeTag(locale), {
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** Value for an `<input type="date">` control. */
export function toDateInput(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : "";
}

/** Value for an `<input type="datetime-local">` control. */
export function toDateTimeInput(value: string | null | undefined): string {
  return value ? value.slice(0, 16) : "";
}

export function todayInput(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset();
  return new Date(now.getTime() - offset * 60_000).toISOString().slice(0, 10);
}

export function nowTimeInput(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset();
  return new Date(now.getTime() - offset * 60_000).toISOString().slice(11, 16);
}

export function initials(name: string | null | undefined): string {
  const parts = (name ?? "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "?";
  }
  return parts
    .slice(0, 2)
    .map((part) => part.slice(0, 1))
    .join("")
    .toUpperCase();
}
