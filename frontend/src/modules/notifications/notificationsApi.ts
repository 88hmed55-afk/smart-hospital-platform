import { api } from "../../shared/api/client";
import type { PageResult } from "../patients/patientsApi";

export interface NotificationRow {
  publicId: string;
  userId: number;
  type: string;
  titleAr: string;
  titleEn: string | null;
  bodyAr: string | null;
  bodyEn: string | null;
  severity: string;
  entityType: string | null;
  entityPublicId: string | null;
  dedupeKey: string | null;
  status: string;
  readAt: string | null;
  createdAt: string | null;
}

export interface NotificationPage extends PageResult<NotificationRow> {
  unread: number;
}

export interface NotificationSummary {
  unread: number;
  items: NotificationRow[];
}

export interface NotificationPreference {
  category: string;
  enabled: boolean;
  minSeverity: string;
}

export interface NotificationPreferences {
  channel: string;
  categories: NotificationPreference[];
  severities: string[];
}

export const notificationsApi = {
  list(unreadOnly: boolean, page = 0, size = 20) {
    const params = new URLSearchParams({
      unreadOnly: String(unreadOnly),
      page: String(page),
      size: String(size),
    });
    return api<NotificationPage>(`/notifications?${params.toString()}`);
  },
  summary() {
    return api<NotificationSummary>("/notifications/unread-count");
  },
  markRead(publicId: string) {
    return api<NotificationRow>(`/notifications/${publicId}/read`, {
      method: "POST",
    });
  },
  markAllRead() {
    return api<{ updated: number; unread: number }>("/notifications/read-all", {
      method: "POST",
    });
  },
  preferences() {
    return api<NotificationPreferences>("/notifications/preferences");
  },
  updatePreferences(categories: NotificationPreference[]) {
    return api<NotificationPreferences>("/notifications/preferences", {
      method: "PUT",
      body: JSON.stringify({ categories }),
    });
  },
};
