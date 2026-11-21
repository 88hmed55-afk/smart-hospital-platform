package com.smarthospital.platform.notify;

/**
 * Transport-neutral sink for live notification pushes.
 *
 * <p>The EJB tier never depends on JAX-RS types: the web layer supplies an
 * implementation backed by an {@code SseEventSink} and registers it in
 * {@link NotificationPushRegistry}. A failing listener is dropped silently so a
 * broken browser tab can never poison outbox delivery.
 */
public interface NotificationPushListener {

    void onNotification(String json);

    void onPing();

    /**
     * Releases the underlying transport. Called when a write is stuck for too
     * long, so a half-open TCP connection cannot hold the fan-out thread.
     */
    default void close() {
    }
}
