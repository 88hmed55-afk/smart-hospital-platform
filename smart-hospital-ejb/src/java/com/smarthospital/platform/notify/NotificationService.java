package com.smarthospital.platform.notify;

import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Local;
import jakarta.ejb.Stateless;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-app notification inbox and per-category delivery preferences (FR-NOT). */
@Stateless
@Local(NotificationServiceLocal.class)
public class NotificationService implements NotificationServiceLocal {

    @EJB
    private NotificationRepository repository;

    @Override
    public Map<String, Object> list(long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.count(userId, unreadOnly);
        List<Map<String, Object>> items = repository.list(userId, unreadOnly,
                safePage * safeSize, safeSize);
        Map<String, Object> result = ClinicalSupport.pageResult(items, safePage, safeSize, total);
        result.put("unread", repository.unreadCount(userId));
        return result;
    }

    @Override
    public Map<String, Object> summary(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unread", repository.unreadCount(userId));
        result.put("items", repository.list(userId, true, 0, 10));
        return result;
    }

    @Override
    public Map<String, Object> markRead(long userId, String publicId) {
        Map<String, Object> row = repository.byPublicId(userId, publicId);
        if (row == null) {
            throw AppException.notFound("NOTIFICATION_NOT_FOUND",
                    "الإشعار غير موجود.", "Notification not found.");
        }
        repository.markRead(userId, publicId);
        return repository.byPublicId(userId, publicId);
    }

    @Override
    public Map<String, Object> markAllRead(long userId) {
        int updated = repository.markAllRead(userId);
        return ClinicalSupport.map("updated", updated, "unread", repository.unreadCount(userId));
    }

    @Override
    public Map<String, Object> preferences(long userId) {
        Map<String, Map<String, Object>> stored = new LinkedHashMap<>();
        for (Map<String, Object> row : repository.preferences(userId)) {
            stored.put(Sql.text(row, "category"), row);
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (String category : NotificationCategories.ALL) {
            Map<String, Object> row = stored.get(category);
            boolean enabled = row == null || Sql.boolValue(row, "enabled");
            String severity = row == null
                    ? "INFO"
                    : NotificationCategories.normalizeSeverity(Sql.text(row, "minSeverity"));
            categories.add(ClinicalSupport.map(
                    "category", category,
                    "enabled", enabled,
                    "minSeverity", severity));
        }
        return ClinicalSupport.map(
                "channel", "IN_APP",
                "categories", categories,
                "severities", NotificationCategories.SEVERITIES);
    }

    @Override
    public Map<String, Object> updatePreferences(long userId, List<Map<String, Object>> entries) {
        if (entries == null) {
            throw AppException.badRequest("PREFERENCES_REQUIRED",
                    "قائمة التفضيلات مطلوبة.", "A preferences list is required.");
        }
        for (Map<String, Object> entry : entries) {
            String category = entry == null ? null : text(entry.get("category"));
            if (!NotificationCategories.known(category)) {
                throw AppException.badRequest("PREFERENCE_CATEGORY_INVALID",
                        "فئة إشعار غير معروفة.", "Unknown notification category.");
            }
            String severity = NotificationCategories.normalizeSeverity(text(entry.get("minSeverity")));
            boolean enabled = asBoolean(entry.get("enabled"), true);
            repository.upsertPreference(userId, category.toUpperCase(Locale.ROOT), enabled, severity);
        }
        return preferences(userId);
    }

    @Override
    public boolean sessionActive(String sessionHash) {
        if (sessionHash == null || sessionHash.isBlank()) {
            return true;
        }
        return repository.sessionActive(sessionHash);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = value.toString().trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
    }
}
