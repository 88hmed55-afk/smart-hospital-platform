package com.smarthospital.platform.notify;

import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consumes outbox events and turns them into in-app notifications.
 *
 * <p>Events are claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED} so two
 * workers (or two application-server nodes) never deliver the same row. Each
 * claim is stamped with {@code claimed_at}; the poller resets claims older than
 * the configured lease, so a crash mid-delivery cannot park an event forever.
 *
 * <p>A poisoned event never blocks the queue: it is retried with backoff and
 * finally parked as {@code DEAD}. Delivery is idempotent at the database level
 * through {@code UNIQUE(user_id, dedupe_key)}.
 *
 * <p>Payload contract (all keys optional except {@code notifications}):
 * <pre>
 * {
 *   "notifications": [
 *     {
 *       "users": ["&lt;user public id&gt;", ...],
 *       "roles": ["LAB_SUPERVISOR", ...],
 *       "type": "LAB_CRITICAL",
 *       "titleAr": "...", "titleEn": "...",
 *       "bodyAr": "...",  "bodyEn": "...",
 *       "severity": "INFO|WARNING|CRITICAL",
 *       "entityType": "LAB_RESULT",
 *       "entityPublicId": "...",
 *       "dedupeKey": "..."
 *     }
 *   ]
 * }
 * </pre>
 */
@Stateless
public class OutboxDispatcher {

    private static final int MAX_ATTEMPTS_FALLBACK = 8;
    private static final List<String> STATUSES =
            List.of("PENDING", "PROCESSING", "SENT", "FAILED", "DEAD");

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @EJB
    private SettingsRepository settings;

    @EJB
    private NotificationPushRegistryLocal pushRegistry;

    @EJB
    private NotificationQueueSender queueSender;

    /**
     * Claims up to {@code limit} due events for this worker.
     *
     * <p>The claim is one atomic {@code UPDATE ... ORDER BY priority DESC, id LIMIT n}
     * stamped with a batch token. Ordering by priority first means a CRITICAL
     * event is not stuck behind a long INFO backlog; {@code id} keeps FIFO order
     * inside one priority band. A single statement leaves no window between
     * reading a row and marking it, which is what made the earlier
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} + per-row {@code UPDATE} version
     * deadlock on secondary-index gap locks (the whole timer stalled on a 50s
     * lock wait). The claim transaction is therefore also very short.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<Long> claimBatch(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        String token = Ulid.next();
        int claimed = Sql.update(em,
                "UPDATE outbox_events SET status = 'PROCESSING', claimed_at = NOW(6), "
                        + "claim_token = ? "
                        + "WHERE status = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6)) "
                        + "ORDER BY priority DESC, id LIMIT ?", token, safe);
        if (claimed == 0) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(claimed);
        for (Map<String, Object> row : Sql.query(em,
                "SELECT id FROM outbox_events WHERE claim_token = ? ORDER BY id", token)) {
            ids.add(Sql.longValue(row, "id", 0));
        }
        return ids;
    }

    /**
     * Releases claims held longer than the lease; safe to call every tick.
     *
     * <p>{@code REQUIRES_NEW} is essential: the poller calls this before
     * claiming, and if it joined the caller's transaction its gap locks would
     * still be held while {@link #claimBatch} runs in a suspended outer
     * transaction — the claim then waits on locks held by its own thread until
     * the 50-second lock timeout.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public int recoverStale(int staleSeconds) {
        int safe = Math.max(30, staleSeconds);
        return Sql.update(em,
                "UPDATE outbox_events SET status = 'PENDING', claimed_at = NULL, claim_token = NULL "
                        + "WHERE status = 'PROCESSING' AND claimed_at IS NOT NULL "
                        + "AND claimed_at < DATE_SUB(NOW(6), INTERVAL ? SECOND)", safe);
    }

    /** Processes one event in its own transaction. Returns false when already handled. */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public boolean dispatchOne(long eventId) {
        Map<String, Object> event = Sql.one(em,
                "SELECT * FROM outbox_events WHERE id = ? FOR UPDATE", eventId);
        if (event == null) {
            return false;
        }
        String status = Sql.text(event, "status");
        if (!"PROCESSING".equals(status) && !"PENDING".equals(status)) {
            return false;
        }
        String payload = Sql.text(event, "payloadJson");
        validatePayload(payload);
        queueSender.enqueue(eventId, payload);
        Sql.update(em,
                "UPDATE outbox_events SET status = 'SENT', processed_at = NOW(6), "
                        + "attempts = attempts + 1, last_error = NULL, claimed_at = NULL, "
                        + "claim_token = NULL "
                        + "WHERE id = ?", eventId);
        return true;
    }

    /**
     * Rejects a payload that breaks the notification contract before it reaches
     * the queue, so a poisoned event is retried and parked in the outbox rather
     * than looping through the broker.
     */
    static void validatePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return;
        }
        JsonObject root;
        try {
            root = Json.createReader(new StringReader(payloadJson)).readObject();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Outbox payload is not valid JSON", ex);
        }
        JsonValue notifications = root.get("notifications");
        if (notifications != null
                && notifications.getValueType() != JsonValue.ValueType.ARRAY) {
            throw new IllegalStateException("Outbox payload notifications must be an array");
        }
    }

    /**
     * Records a failed attempt in a fresh transaction.
     *
     * <p>Deliberately lets a database failure propagate: swallowing it inside
     * this transaction leaves it marked rollback-only, so the container throws
     * {@code EJBTransactionRolledbackException} at commit time — a system
     * exception that used to cancel the outbox timer. The caller
     * ({@link OutboxPoller}) contains the failure instead; the claim lease then
     * re-queues the event.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void markFailed(long eventId, String error) {
        Map<String, Object> event = Sql.one(em,
                "SELECT attempts FROM outbox_events WHERE id = ?", eventId);
        if (event == null) {
            return;
        }
        int attempts = Sql.intValue(event, "attempts", 0) + 1;
        int max = maxAttempts();
        String message = error == null ? "unknown error" : error;
        if (message.length() > 990) {
            message = message.substring(0, 990);
        }
        if (attempts >= max) {
            Sql.update(em,
                    "UPDATE outbox_events SET status = 'DEAD', attempts = ?, last_error = ?, "
                            + "claimed_at = NULL, claim_token = NULL WHERE id = ?",
                    attempts, message, eventId);
        } else {
            int backoffSeconds = Math.min(300, 5 * attempts * attempts);
            Sql.update(em,
                    "UPDATE outbox_events SET status = 'PENDING', attempts = ?, last_error = ?, "
                            + "next_attempt_at = DATE_ADD(NOW(6), INTERVAL ? SECOND), "
                            + "claimed_at = NULL, claim_token = NULL WHERE id = ?",
                    attempts, message, backoffSeconds, eventId);
        }
    }

    // ------------------------------------------------------------ monitoring

    /**
     * Marks an already-enqueued event as dead after the consumer exhausted its
     * redeliveries and parked the payload on the DLQ.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void markDead(long eventId, String error) {
        String message = error == null ? "queue delivery failed" : error;
        if (message.length() > 990) {
            message = message.substring(0, 990);
        }
        Sql.update(em,
                "UPDATE outbox_events SET status = 'DEAD', attempts = attempts + 1, "
                        + "last_error = ?, processed_at = NULL "
                        + "WHERE id = ? AND status IN ('SENT', 'PROCESSING')",
                message, eventId);
    }

    /**
     * Records one failed consumer delivery in a fresh transaction and returns
     * the running attempt count. The counter lives on the outbox row so it
     * survives a broker restart; the broker's own redelivery counter is not
     * portable across providers.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public int noteQueueFailure(long eventId, String error) {
        String message = error == null ? "queue delivery failed" : error;
        if (message.length() > 990) {
            message = message.substring(0, 990);
        }
        Sql.update(em,
                "UPDATE outbox_events SET attempts = attempts + 1, last_error = ? "
                        + "WHERE id = ? AND status = 'SENT'",
                message, eventId);
        Map<String, Object> row = Sql.one(em,
                "SELECT attempts FROM outbox_events WHERE id = ?", eventId);
        return row == null ? 0 : Sql.intValue(row, "attempts", 0);
    }

    public Map<String, Object> stats() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : STATUSES) {
            counts.put(status, 0L);
        }
        for (Map<String, Object> row : Sql.query(em,
                "SELECT status, COUNT(*) AS total FROM outbox_events GROUP BY status")) {
            String status = Sql.text(row, "status");
            if (status != null) {
                counts.put(status, Sql.longValue(row, "total", 0));
            }
        }
        Map<String, Object> backlog = Sql.one(em,
                "SELECT COUNT(*) AS total, MIN(created_at) AS oldest_created_at, "
                        + "SUM(priority >= 100) AS critical_total, "
                        + "TIMESTAMPDIFF(SECOND, MIN(created_at), NOW(6)) AS oldest_age_seconds "
                        + "FROM outbox_events WHERE status IN ('PENDING', 'PROCESSING')");
        Map<String, Object> last = Sql.one(em,
                "SELECT MAX(processed_at) AS last_processed_at FROM outbox_events WHERE status = 'SENT'");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("pending", counts.get("PENDING"));
        result.put("processing", counts.get("PROCESSING"));
        result.put("sent", counts.get("SENT"));
        result.put("failed", counts.get("FAILED"));
        result.put("dead", counts.get("DEAD"));
        result.put("backlog", backlog);
        result.put("lastProcessedAt", last == null ? null : last.get("lastProcessedAt"));
        result.put("deadSamples", Sql.query(em,
                "SELECT id, event_id, event_type, attempts, "
                        + "last_error, created_at "
                        + "FROM outbox_events WHERE status = 'DEAD' ORDER BY id DESC LIMIT 10"));
        result.put("pushConnections", pushRegistry.connectionCount());
        return result;
    }

    public Map<String, Object> events(String status, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        String normalized = status == null ? null : status.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows;
        if (normalized == null || normalized.isBlank()) {
            rows = Sql.query(em,
                    "SELECT id, event_id, event_type, "
                            + "aggregate_type, aggregate_id, "
                            + "status, attempts, priority, last_error, claimed_at, "
                            + "created_at, processed_at "
                            + "FROM outbox_events ORDER BY id DESC LIMIT ?", safe);
        } else {
            if (!STATUSES.contains(normalized)) {
                throw AppException.badRequest("OUTBOX_STATUS_INVALID",
                        "حالة غير معروفة لطابور الأحداث.", "Unknown outbox status.");
            }
            rows = Sql.query(em,
                    "SELECT id, event_id, event_type, "
                            + "aggregate_type, aggregate_id, "
                            + "status, attempts, priority, last_error, claimed_at, "
                            + "created_at, processed_at "
                            + "FROM outbox_events WHERE status = ? ORDER BY id DESC LIMIT ?",
                    normalized, safe);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("total", rows.size());
        return result;
    }

    /** Requeues a DEAD/FAILED event for another delivery attempt. */
    public Map<String, Object> retry(long eventId) {
        int updated = Sql.update(em,
                "UPDATE outbox_events SET status = 'PENDING', attempts = 0, "
                        + "next_attempt_at = NOW(6), claimed_at = NULL, claim_token = NULL, "
                        + "last_error = NULL, processed_at = NULL "
                        + "WHERE id = ? AND status IN ('DEAD', 'FAILED')", eventId);
        if (updated == 0) {
            throw AppException.conflict("OUTBOX_NOT_RETRYABLE",
                    "لا يمكن إعادة إرسال هذا الحدث في حالته الحالية.",
                    "This event cannot be retried in its current state.");
        }
        return Sql.one(em,
                "SELECT id, event_id, status, attempts FROM outbox_events WHERE id = ?",
                eventId);
    }

    // ------------------------------------------------------ templates

    /** Lists the configurable notification wording (FR-NOT-005). */
    public List<Map<String, Object>> templates() {
        return Sql.query(em,
                "SELECT code, category, default_severity, title_ar, title_en, "
                        + "body_ar, body_en, enabled, updated_at "
                        + "FROM notification_templates ORDER BY category, code");
    }

    /** Updates one template; the code itself is the stable contract. */
    public Map<String, Object> updateTemplate(String code, Map<String, Object> body) {
        Map<String, Object> existing = Sql.one(em,
                "SELECT code FROM notification_templates WHERE code = ?", code);
        if (existing == null) {
            throw AppException.notFound("TEMPLATE_NOT_FOUND",
                    "قالب الإشعار غير موجود.", "Notification template not found.");
        }
        if (body == null) {
            throw AppException.badRequest("TEMPLATE_INVALID",
                    "بيانات القالب مطلوبة.", "Template payload is required.");
        }
        String category = value(body.get("category"));
        if (category != null && !NotificationCategories.known(category)) {
            throw AppException.badRequest("TEMPLATE_CATEGORY_INVALID",
                    "فئة القالب غير معروفة.", "Unknown template category.");
        }
        String severity = NotificationCategories.normalizeSeverity(
                value(body.get("defaultSeverity")));
        String titleAr = required(body, "titleAr", "العنوان العربي مطلوب.");
        String titleEn = required(body, "titleEn", "English title is required.");
        String bodyAr = required(body, "bodyAr", "النص العربي مطلوب.");
        String bodyEn = required(body, "bodyEn", "English body is required.");
        boolean enabled = body.get("enabled") == null
                || Boolean.parseBoolean(value(body.get("enabled")));
        Sql.update(em,
                "UPDATE notification_templates SET category = COALESCE(?, category), "
                        + "default_severity = ?, title_ar = ?, title_en = ?, body_ar = ?, "
                        + "body_en = ?, enabled = ? WHERE code = ?",
                category == null ? null : category.toUpperCase(Locale.ROOT), severity,
                titleAr, titleEn, bodyAr, bodyEn, enabled ? 1 : 0, code);
        return Sql.one(em,
                "SELECT code, category, default_severity, title_ar, title_en, "
                        + "body_ar, body_en, enabled, updated_at "
                        + "FROM notification_templates WHERE code = ?", code);
    }

    private static String value(Object raw) {
        return raw == null ? null : raw.toString().trim();
    }

    private static String required(Map<String, Object> body, String key, String message) {
        String text = value(body.get(key));
        if (text == null || text.isBlank()) {
            throw AppException.badRequest("TEMPLATE_INVALID", message, message);
        }
        return text;
    }

    private int maxAttempts() {
        try {
            return settings.findValue("outbox.max.attempts")
                    .map(value -> {
                        try {
                            return Integer.parseInt(value.trim());
                        } catch (NumberFormatException ex) {
                            return MAX_ATTEMPTS_FALLBACK;
                        }
                    })
                    .orElse(MAX_ATTEMPTS_FALLBACK);
        } catch (RuntimeException ex) {
            return MAX_ATTEMPTS_FALLBACK;
        }
    }
}
