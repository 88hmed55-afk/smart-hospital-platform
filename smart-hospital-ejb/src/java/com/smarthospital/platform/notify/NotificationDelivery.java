package com.smarthospital.platform.notify;

import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns one outbox payload into inbox rows and live SSE pushes.
 *
 * <p>Runs in its own transaction, invoked by {@link NotificationMdb} after the
 * outbox dispatcher has committed the event to JMS. Delivery is idempotent:
 * {@code INSERT IGNORE} plus the {@code (user_id, dedupe_key)} unique key makes
 * a redelivered message harmless. When a payload carries no dedupe key the
 * delivery derives one from the event id, so retries still cannot duplicate.
 */
@Stateless
public class NotificationDelivery {

    private static final Logger LOG = Logger.getLogger(NotificationDelivery.class.getName());

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @EJB
    private NotificationPushRegistryLocal pushRegistry;

    @EJB
    private SettingsRepository settings;

    @jakarta.annotation.Resource
    private TransactionSynchronizationRegistry txSync;

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void deliverEvent(long eventId, String payloadJson) {
        if (simulatedDeliveryFailure()) {
            throw new IllegalStateException("Simulated delivery failure (TC-176)");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            return;
        }
        JsonObject root;
        try {
            root = Json.createReader(new StringReader(payloadJson)).readObject();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Outbox payload is not valid JSON", ex);
        }
        JsonArray notifications = root.getJsonArray("notifications");
        if (notifications == null) {
            return;
        }
        int index = 0;
        for (JsonValue value : notifications) {
            if (value.getValueType() == JsonValue.ValueType.OBJECT) {
                deliverOne(eventId, index, value.asJsonObject());
            }
            index++;
        }
    }

    private void deliverOne(long eventId, int index, JsonObject spec) {
        List<Long> userIds = new ArrayList<>();
        for (String publicId : strings(spec, "users")) {
            var user = Sql.one(em,
                    "SELECT id FROM users WHERE public_id = ? AND status = 'ACTIVE'", publicId);
            if (user != null) {
                userIds.add(Sql.longValue(user, "id", 0));
            }
        }
        for (String roleCode : strings(spec, "roles")) {
            List<java.util.Map<String, Object>> rows = Sql.query(em,
                    "SELECT DISTINCT u.id FROM users u "
                            + "JOIN user_roles ur ON ur.user_id = u.id "
                            + "JOIN roles r ON r.id = ur.role_id "
                            + "WHERE r.code = ? AND u.status = 'ACTIVE'", roleCode);
            for (var row : rows) {
                userIds.add(Sql.longValue(row, "id", 0));
            }
        }
        if (userIds.isEmpty()) {
            return;
        }
        String type = text(spec, "type", "GENERAL");
        String severity = NotificationCategories.normalizeSeverity(text(spec, "severity", null));
        String titleAr = text(spec, "titleAr", "إشعار");
        String titleEn = text(spec, "titleEn", "Notification");
        String bodyAr = text(spec, "bodyAr", null);
        String bodyEn = text(spec, "bodyEn", null);
        JsonObject params = spec.getJsonObject("params");
        String templateCode = text(spec, "templateCode", null);
        if (templateCode != null) {
            var template = Sql.one(em,
                    "SELECT title_ar, title_en, body_ar, body_en, default_severity "
                            + "FROM notification_templates WHERE code = ? AND enabled = 1",
                    templateCode);
            if (template != null) {
                titleAr = render(Sql.text(template, "titleAr"), params);
                titleEn = render(Sql.text(template, "titleEn"), params);
                bodyAr = render(Sql.text(template, "bodyAr"), params);
                bodyEn = render(Sql.text(template, "bodyEn"), params);
                if (spec.get("severity") == null) {
                    severity = NotificationCategories.normalizeSeverity(
                            Sql.text(template, "defaultSeverity"));
                }
            }
        }
        int priority = priorityOf(severity);
        String entityType = text(spec, "entityType", null);
        String entityPublicId = text(spec, "entityPublicId", null);
        String dedupeKey = text(spec, "dedupeKey", null);
        for (Long userId : userIds.stream().distinct().toList()) {
            if (!allowed(userId, type, severity)) {
                continue;
            }
            String publicId = Ulid.next();
            String rowKey = dedupeKey == null
                    ? "evt:" + eventId + ":" + index + ":" + userId
                    : dedupeKey;
            Sql.insert(em,
                    "INSERT IGNORE INTO notifications (public_id, user_id, type, title_ar, title_en, "
                            + "body_ar, body_en, entity_type, entity_public_id, severity, priority, "
                            + "status, dedupe_key) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNREAD', ?)",
                    publicId, userId, type, titleAr, titleEn, bodyAr, bodyEn,
                    entityType, entityPublicId, severity, priority, rowKey);
            pushAfterCommit(userId, Json.createObjectBuilder()
                    .add("publicId", publicId)
                    .add("type", type)
                    .add("severity", severity)
                    .add("priority", priority)
                    .add("titleAr", titleAr)
                    .add("titleEn", titleEn == null ? titleAr : titleEn)
                    .add("bodyAr", bodyAr == null ? "" : bodyAr)
                    .add("bodyEn", bodyEn == null ? "" : bodyEn)
                    .add("entityType", entityType == null ? "" : entityType)
                    .add("entityPublicId", entityPublicId == null ? "" : entityPublicId)
                    .add("createdAt", Instant.now().toString())
                    .build()
                    .toString());
        }
    }

    private boolean allowed(long userId, String type, String severity) {
        var pref = Sql.one(em,
                "SELECT enabled, min_severity FROM notification_preferences "
                        + "WHERE user_id = ? AND category = ?",
                userId, NotificationCategories.of(type));
        if (pref == null) {
            return true;
        }
        if (!Sql.boolValue(pref, "enabled")) {
            return false;
        }
        String min = Sql.text(pref, "minSeverity");
        return NotificationCategories.rank(severity) >= NotificationCategories.rank(min);
    }

    /**
     * Sends the SSE payload only after the notification row is committed, so a
     * client can never receive an event that a rollback erased.
     */
    private void pushAfterCommit(long userId, String json) {
        try {
            txSync.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // nothing to do
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        safePush(userId, json);
                    }
                }
            });
        } catch (RuntimeException ex) {
            safePush(userId, json);
        }
    }

    private void safePush(long userId, String json) {
        try {
            pushRegistry.push(userId, json);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Live notification push skipped: " + ex.getMessage());
        }
    }

    /** Test hook for the dead-letter path; absent in normal operation. */
    private boolean simulatedDeliveryFailure() {
        try {
            return settings.findValue("outbox.jms.simulate.delivery-failure")
                    .map(value -> "true".equalsIgnoreCase(value.trim()))
                    .orElse(false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static List<String> strings(JsonObject spec, String key) {
        JsonArray array = spec.getJsonArray(key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonValue value : array) {
            if (value instanceof JsonString string) {
                values.add(string.getString());
            }
        }
        return values;
    }

    /**
     * Fills {@code {placeholders}} in a template with the payload parameters.
     * Unknown placeholders are left untouched so a missing value is visible in
     * the inbox instead of silently disappearing.
     */
    private static String render(String template, JsonObject params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (String key : params.keySet()) {
            rendered = rendered.replace("{" + key + "}", text(params, key, ""));
        }
        return rendered;
    }

    static int priorityOf(String severity) {
        return switch (NotificationCategories.normalizeSeverity(severity)) {
            case "CRITICAL" -> 100;
            case "WARNING" -> 50;
            default -> 0;
        };
    }

    private static String text(JsonObject spec, String key, String fallback) {
        JsonValue value = spec.get(key);
        if (value == null || value.getValueType() == JsonValue.ValueType.NULL) {
            return fallback;
        }
        return value.toString().replaceAll("^\"|\"$", "");
    }
}
