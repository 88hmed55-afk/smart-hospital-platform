package com.smarthospital.platform.notify;

import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.json.JsonUtil;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.ejb.Local;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Transactional outbox writer (ADR-005).
 *
 * <p>Domain services call {@link #enqueue} inside the same JTA transaction as
 * the business change, so either both commit or neither does. Delivery happens
 * asynchronously in {@link OutboxPoller}; nothing is sent from the request
 * thread.
 */
@Stateless
@Local(OutboxServiceLocal.class)
public class OutboxService implements OutboxServiceLocal {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @Override
    public String enqueue(String eventType, String aggregateType, String aggregatePublicId,
                          Map<String, Object> payload) {
        String eventId = Ulid.next();
        Sql.insert(em,
                "INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, "
                        + "payload_json, status, attempts, priority, next_attempt_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, NOW(6))",
                eventId, eventType, aggregateType, aggregatePublicId,
                JsonUtil.toJson(payload), priorityOf(payload));
        return eventId;
    }

    /**
     * Derives the claim priority from the payload: a CRITICAL notification must
     * jump a long INFO backlog instead of waiting behind it. Producers may also
     * set an explicit numeric {@code priority} to override the derivation.
     */
    static int priorityOf(Map<String, Object> payload) {
        if (payload == null) {
            return 0;
        }
        Object explicit = payload.get("priority");
        if (explicit instanceof Number number) {
            return Math.max(0, Math.min(100, number.intValue()));
        }
        Object raw = payload.get("notifications");
        if (!(raw instanceof List<?> notifications)) {
            return 0;
        }
        int max = 0;
        for (Object item : notifications) {
            if (item instanceof Map<?, ?> spec) {
                Object severity = spec.get("severity");
                max = Math.max(max, priorityOfSeverity(
                        severity == null ? null : severity.toString()));
            }
        }
        return max;
    }

    private static int priorityOfSeverity(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 100;
            case "WARNING" -> 50;
            default -> 0;
        };
    }
}
