package com.smarthospital.platform.notify;

import com.smarthospital.platform.settings.SettingsRepository;
import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Queue;

/**
 * JMS producer for the notification pipeline.
 *
 * <p>The outbox dispatcher hands a committed event to this bean; the message is
 * sent inside the dispatcher's container transaction, so an event is marked
 * {@code SENT} only when the broker accepted it. If the broker is unavailable
 * the send throws, the transaction rolls back and the outbox row stays
 * {@code PENDING} — the guarantee exercised by TC-176.
 */
@Stateless
public class NotificationQueueSender {

    static final String NOTIFICATION_QUEUE = "jms/NotificationQueue";
    static final String DEAD_LETTER_QUEUE = "jms/NotificationDLQ";
    static final String CONNECTION_FACTORY = "jms/__defaultConnectionFactory";

    @Resource(lookup = NOTIFICATION_QUEUE)
    private Queue notificationQueue;

    @Resource(lookup = DEAD_LETTER_QUEUE)
    private Queue deadLetterQueue;

    @Resource(lookup = CONNECTION_FACTORY)
    private ConnectionFactory connectionFactory;

    @EJB
    private SettingsRepository settings;

    /** Enqueues one outbox event. Failures propagate so the caller can retry. */
    public void enqueue(long eventId, String payloadJson) {
        simulateOutageIfRequested();
        try (JMSContext context = connectionFactory.createContext()) {
            context.createProducer()
                    .setProperty("eventId", eventId)
                    .send(notificationQueue, payloadJson == null ? "" : payloadJson);
        } catch (JMSRuntimeException ex) {
            throw new IllegalStateException(
                    "Notification queue unavailable: " + ex.getMessage(), ex);
        }
    }

    /**
     * Test hook used by TC-176 to reproduce a broker outage deterministically:
     * when {@code outbox.jms.simulate.failure} is {@code true} the send fails
     * exactly as it would if the broker were unreachable. The setting is absent
     * in normal operation and removed again by the harness.
     */
    private void simulateOutageIfRequested() {
        try {
            settings.findValue("outbox.jms.simulate.failure")
                    .filter(value -> "true".equalsIgnoreCase(value.trim()))
                    .ifPresent(value -> {
                        throw new IllegalStateException(
                                "Simulated JMS outage after commit (TC-176)");
                    });
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // A settings lookup problem must never block notification delivery.
        }
    }

    /** Parks a message that the consumer could not deliver, with the failure reason. */
    public void parkInDeadLetter(long eventId, String payloadJson, String error) {
        try (JMSContext context = connectionFactory.createContext()) {
            context.createProducer()
                    .setProperty("eventId", eventId)
                    .setProperty("failure", truncate(error))
                    .send(deadLetterQueue, payloadJson == null ? "" : payloadJson);
        } catch (JMSRuntimeException ex) {
            throw new IllegalStateException(
                    "Dead-letter queue unavailable: " + ex.getMessage(), ex);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
