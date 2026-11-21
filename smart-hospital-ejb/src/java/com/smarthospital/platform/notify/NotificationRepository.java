package com.smarthospital.platform.notify;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Map;

/** Explicit SQL gateway for the in-app notification inbox. */
@Stateless
public class NotificationRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    public List<Map<String, Object>> list(long userId, boolean unreadOnly, int offset, int limit) {
        String filter = unreadOnly ? " AND status = 'UNREAD'" : "";
        return Sql.query(em,
                "SELECT * FROM notifications WHERE user_id = ?" + filter
                        + " ORDER BY priority DESC, created_at DESC, id DESC LIMIT ? OFFSET ?",
                userId, limit, offset);
    }

    public long count(long userId, boolean unreadOnly) {
        String filter = unreadOnly ? " AND status = 'UNREAD'" : "";
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM notifications WHERE user_id = ?" + filter, userId);
        return Sql.longValue(row, "total", 0);
    }

    public long unreadCount(long userId) {
        return count(userId, true);
    }

    public Map<String, Object> byPublicId(long userId, String publicId) {
        return Sql.one(em,
                "SELECT * FROM notifications WHERE user_id = ? AND public_id = ?", userId, publicId);
    }

    public int markRead(long userId, String publicId) {
        return Sql.update(em,
                "UPDATE notifications SET status = 'READ', read_at = NOW(6), version = version + 1 "
                        + "WHERE user_id = ? AND public_id = ? AND status = 'UNREAD'",
                userId, publicId);
    }

    public int markAllRead(long userId) {
        return Sql.update(em,
                "UPDATE notifications SET status = 'READ', read_at = NOW(6), version = version + 1 "
                        + "WHERE user_id = ? AND status = 'UNREAD'", userId);
    }

    // ------------------------------------------------------- preferences

    public List<Map<String, Object>> preferences(long userId) {
        return Sql.query(em,
                "SELECT category, enabled, min_severity FROM notification_preferences "
                        + "WHERE user_id = ? ORDER BY category", userId);
    }

    public Map<String, Object> preference(long userId, String category) {
        return Sql.one(em,
                "SELECT enabled, min_severity FROM notification_preferences "
                        + "WHERE user_id = ? AND category = ?", userId, category);
    }

    /** True while the session behind an SSE stream is still open. */
    public boolean sessionActive(String sessionHash) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS open_sessions FROM user_sessions "
                        + "WHERE session_hash = ? AND ended_at IS NULL", sessionHash);
        return Sql.longValue(row, "openSessions", 0) > 0;
    }

    public void upsertPreference(long userId, String category, boolean enabled, String minSeverity) {
        Sql.update(em,
                "INSERT INTO notification_preferences (user_id, category, enabled, min_severity) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), "
                        + "min_severity = VALUES(min_severity), version = version + 1",
                userId, category, enabled ? 1 : 0, minSeverity);
    }
}
