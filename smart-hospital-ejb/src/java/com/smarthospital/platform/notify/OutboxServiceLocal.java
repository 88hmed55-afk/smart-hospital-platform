package com.smarthospital.platform.notify;

import java.util.Map;

/** Local view of the transactional outbox writer. */
public interface OutboxServiceLocal {

    /**
     * Appends one event to the outbox inside the caller's transaction.
     *
     * @return the generated event id (ULID), useful for tracing
     */
    String enqueue(String eventType, String aggregateType, String aggregatePublicId,
                   Map<String, Object> payload);
}
