package com.smarthospital.platform.notify;

import jakarta.ejb.EJB;
import jakarta.ejb.Lock;
import jakarta.ejb.LockType;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import com.smarthospital.platform.settings.SettingsRepository;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background outbox drain. Runs on a fixed schedule inside the application
 * server; no external broker is required (ADR-005).
 *
 * <p>Every tick first releases claims whose lease expired (a crashed worker),
 * then claims a fresh batch with a single atomic
 * {@code UPDATE ... ORDER BY id LIMIT n} stamped with a {@code claim_token}.
 * The schedule is short (5s) so a critical lab result reaches the ward almost
 * immediately, while the batch size keeps each pass bounded.
 *
 * <p>The callback never lets an exception escape: a single poisoned event must
 * not roll back the batch or cancel the container timer. Each claim and each
 * delivery commits independently, and a failure is recorded in its own
 * transaction by {@link OutboxDispatcher#markFailed}.
 *
 * <p>Note: GlassFish does not honour {@code NOT_SUPPORTED} on a timer callback,
 * so the callback still runs inside a container transaction. Every dispatcher
 * method therefore declares {@code REQUIRES_NEW}; otherwise its locks would be
 * held for the whole tick and the next claim would wait on its own thread.
 */
@Singleton
@Startup
public class OutboxPoller {

    private static final Logger LOG = Logger.getLogger(OutboxPoller.class.getName());
    private static final int BATCH = 25;
    private static final int CLAIM_LEASE_SECONDS = 120;

    @EJB
    private OutboxDispatcher dispatcher;

    @EJB
    private SettingsRepository settings;

    @Schedule(second = "*/5", minute = "*", hour = "*", persistent = false)
    @Lock(LockType.WRITE)
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void poll() {
        try {
            drain();
        } catch (Throwable fatal) {
            // A system exception cancels a non-persistent timer for good, so the
            // callback must never throw. Whatever failed is retried next tick.
            LOG.log(Level.WARNING, "Outbox poll aborted", fatal);
        }
    }

    private void drain() {
        if (paused()) {
            return;
        }
        try {
            dispatcher.recoverStale(CLAIM_LEASE_SECONDS);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Outbox lease recovery skipped: " + ex.getMessage());
        }
        List<Long> ids;
        try {
            ids = dispatcher.claimBatch(BATCH);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Outbox claim failed: " + ex.getMessage(), ex);
            return;
        }
        for (Long id : ids) {
            try {
                dispatcher.dispatchOne(id);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Outbox event " + id + " deferred: " + ex.getMessage());
                try {
                    dispatcher.markFailed(id, rootMessage(ex));
                } catch (RuntimeException recordFailure) {
                    // The claim lease recovery will pick the event up again.
                    LOG.log(Level.WARNING, "Could not record outbox failure for event " + id
                            + ": " + recordFailure.getMessage());
                }
            }
        }
    }

    /**
     * Maintenance switch and deterministic test seam: while
     * {@code outbox.dispatch.paused} is {@code true} the poller claims nothing.
     * The setting is absent in normal operation.
     */
    private boolean paused() {
        try {
            return settings.findValue("outbox.dispatch.paused")
                    .map(value -> "true".equalsIgnoreCase(value.trim()))
                    .orElse(false);
        } catch (RuntimeException ex) {
            return false;
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
