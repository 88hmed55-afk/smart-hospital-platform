package com.smarthospital.platform.notify;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.EJB;
import jakarta.ejb.EJBException;
import jakarta.ejb.MessageDriven;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JMS consumer for the notification queue.
 *
 * <p>The dispatcher commits an event to the queue, then this bean creates the
 * inbox rows in its own transaction. A delivery failure triggers broker
 * redelivery; after {@link #MAX_DELIVERIES} attempts the payload is parked on
 * {@code jms/NotificationDLQ} and the outbox row is marked {@code DEAD} so the
 * admin screen can retry it. Nothing is silently dropped.
 */
@MessageDriven(activationConfig = {
    @ActivationConfigProperty(
            propertyName = "destinationLookup",
            propertyValue = NotificationQueueSender.NOTIFICATION_QUEUE),
    @ActivationConfigProperty(
            propertyName = "destinationType",
            propertyValue = "jakarta.jms.Queue"),
    @ActivationConfigProperty(
            propertyName = "connectionFactoryLookup",
            propertyValue = NotificationQueueSender.CONNECTION_FACTORY)
})
public class NotificationMdb implements MessageListener {

    private static final Logger LOG = Logger.getLogger(NotificationMdb.class.getName());
    private static final int MAX_DELIVERIES = 3;

    @EJB
    private NotificationDelivery delivery;

    @EJB
    private NotificationQueueSender sender;

    @EJB
    private OutboxDispatcher dispatcher;

    @Override
    public void onMessage(Message message) {
        long eventId;
        String payload;
        try {
            eventId = message.getLongProperty("eventId");
            payload = message.getBody(String.class);
        } catch (JMSException | RuntimeException ex) {
            // A message we cannot even read can never be delivered; keep the
            // broker from redelivering it forever.
            LOG.log(Level.WARNING, "Unreadable notification message dropped: " + ex.getMessage());
            return;
        }

        try {
            delivery.deliverEvent(eventId, payload);
        } catch (RuntimeException ex) {
            String reason = rootMessage(ex);
            int attempt = dispatcher.noteQueueFailure(eventId, reason);
            if (attempt >= MAX_DELIVERIES) {
                LOG.log(Level.WARNING, "Parking notification event " + eventId
                        + " on the dead-letter queue after " + attempt + " attempts: " + reason);
                try {
                    sender.parkInDeadLetter(eventId, payload, reason);
                    dispatcher.markDead(eventId, "Queue delivery failed: " + reason);
                } catch (RuntimeException parkFailure) {
                    throw new EJBException("Could not park event " + eventId
                            + " on the dead-letter queue", parkFailure);
                }
                return;
            }
            // Rethrowing lets the container roll back and redeliver the message.
            throw new EJBException("Notification delivery failed for event " + eventId, ex);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
