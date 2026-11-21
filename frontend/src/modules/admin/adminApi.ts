import { api } from "../../shared/api/client";
import type { Locale } from "../../shared/i18n";

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface AdminUser {
  publicId: string;
  username: string;
  displayNameAr: string;
  displayNameEn: string | null;
  email: string | null;
  phone: string | null;
  branchId: number | null;
  departmentId: number | null;
  status: string;
  mustChangePassword: boolean;
  mfaEnabled: boolean;
  failedAttempts: number;
  lockedUntil: string | null;
  lastLoginAt: string | null;
  createdAt: string | null;
  roles: string[];
  scopes: string[];
}

export interface CreateUserBody {
  username: string;
  displayNameAr: string;
  displayNameEn?: string;
  email?: string;
  phone?: string;
  password: string;
  roleCodes: string[];
}

export interface RoleItem {
  publicId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  systemRole: boolean;
  permissions: string[];
  permissionCount: number;
}

export interface PermissionItem {
  code: string;
  domain: string;
  action: string;
  descriptionAr: string | null;
}

export interface AuditItem {
  id: number;
  actorUserId: number | null;
  action: string;
  entityType: string;
  entityPublicId: string | null;
  before: string | null;
  after: string | null;
  reason: string | null;
  ip: string | null;
  traceId: string | null;
  prevHash: string | null;
  hash: string;
  createdAt: string | null;
}

export interface SessionItem {
  id: number;
  userPublicId: string | null;
  username: string | null;
  displayNameAr: string | null;
  ip: string | null;
  userAgent: string | null;
  startedAt: string | null;
  lastSeenAt: string | null;
}

export interface BreakGlassItem {
  publicId: string;
  patientPublicId: string;
  requesterUserId: number;
  reason: string;
  status: string;
  grantedAt: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  reviewedAt: string | null;
  reviewedBy: number | null;
  reviewNote: string | null;
}

export interface SettingItem {
  key: string;
  value: string | null;
  valueType: string;
  category: string;
  sensitive: boolean;
  updatedBy: number | null;
  updatedAt: string | null;
  version: number;
}

export interface ChainVerification {
  valid: boolean;
  checked: number;
  brokenAt: number | null;
  expectedHash: string | null;
  actualHash: string | null;
}

export interface AuditFilters {
  action?: string;
  entityType?: string;
  page?: number;
  size?: number;
}

export const adminApi = {
  users(query: string, page: number, size = 20) {
    const params = new URLSearchParams({
      q: query,
      page: String(page),
      size: String(size),
    });
    return api<PageResult<AdminUser>>(`/admin/users?${params.toString()}`);
  },
  createUser(body: CreateUserBody) {
    return api<AdminUser>("/admin/users", {
      method: "POST",
      body: JSON.stringify(body),
    });
  },
  setEnabled(publicId: string, enabled: boolean) {
    return api<AdminUser>(
      `/admin/users/${publicId}/${enabled ? "enable" : "disable"}`,
      { method: "POST" },
    );
  },
  resetPassword(publicId: string, newPassword: string) {
    return api<AdminUser>(`/admin/users/${publicId}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ newPassword }),
    });
  },
  roles() {
    return api<RoleItem[]>("/admin/roles");
  },
  permissions() {
    return api<PermissionItem[]>("/admin/permissions");
  },
  updateRolePermissions(publicId: string, permissionCodes: string[]) {
    return api<{ publicId: string; code: string; permissions: string[] }>(
      `/admin/roles/${publicId}/permissions`,
      {
        method: "PUT",
        body: JSON.stringify({ permissionCodes }),
      },
    );
  },
  audit(filters: AuditFilters) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(filters.size ?? 20),
    });
    if (filters.action) {
      params.set("action", filters.action);
    }
    if (filters.entityType) {
      params.set("entityType", filters.entityType);
    }
    return api<PageResult<AuditItem>>(`/admin/audit?${params.toString()}`);
  },
  verifyAudit() {
    return api<ChainVerification>("/admin/audit/verify");
  },
  sessions(page = 0, size = 50) {
    const params = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    return api<PageResult<SessionItem>>(`/admin/sessions?${params.toString()}`);
  },
  revokeSession(id: number) {
    return api<void>(`/admin/sessions/${id}`, { method: "DELETE" });
  },
  breakGlass(status?: string) {
    const suffix = status ? `?status=${encodeURIComponent(status)}` : "";
    return api<BreakGlassItem[]>(`/admin/break-glass${suffix}`);
  },
  requestBreakGlass(patientPublicId: string, reason: string, minutes: number) {
    return api<BreakGlassItem>("/admin/break-glass", {
      method: "POST",
      body: JSON.stringify({ patientPublicId, reason, minutes }),
    });
  },
  revokeBreakGlass(publicId: string) {
    return api<BreakGlassItem>(`/admin/break-glass/${publicId}/revoke`, {
      method: "POST",
    });
  },
  reviewBreakGlass(publicId: string, note: string) {
    return api<BreakGlassItem>(`/admin/break-glass/${publicId}/review`, {
      method: "POST",
      body: JSON.stringify({ note }),
    });
  },
  settings() {
    return api<SettingItem[]>("/admin/settings");
  },
  updateSetting(key: string, value: string) {
    return api<SettingItem>(`/admin/settings/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    });
  },
};

export function formatDateTime(value: string | null, locale: Locale): string {
  if (!value) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
