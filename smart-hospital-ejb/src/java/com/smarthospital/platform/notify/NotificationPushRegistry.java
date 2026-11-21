package com.smarthospital.platform.notify;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Local;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory fan-out for SSE clients (ADR-006).
 *
 * <p>Bean-managed concurrency is used on purpose: container write locks would
 * serialise every network write across all users. The maps are concurrent and
 * a listener that throws is removed on the spot.
 *
 * <p>Writes are handed to a single daemon writer thread through a bounded
 * queue. The caller is the thread committing the outbox transaction, and a
 * half-open browser socket must never block that commit: a blocked commit keeps
 * row locks open, which used to stall the whole outbox on a lock wait. If the
 * queue is full the frame is dropped and the 30-second polling fallback covers
 * the gap. A listener that is still "writing" after {@code STUCK_MILLIS} is
 * closed by the heartbeat.
 */
@Singleton
@Startup
@Local(NotificationPushRegistryLocal.class)
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class NotificationPushRegistry implements NotificationPushRegistryLocal {

    private static final Logger LOG = Logger.getLogger(NotificationPushRegistry.class.getName());
    private static final int QUEUE_CAPACITY = 4096;
    private static final long STUCK_MILLIS = 30_000L;

    private final Map<Long, Set<Entry>> listeners = new ConcurrentHashMap<>();
    private ThreadPoolExecutor writer;

    @PostConstruct
    void init() {
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "sh-notify-push");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (writer != null) {
            writer.shutdownNow();
        }
    }

    @Override
    public void register(long userId, NotificationPushListener listener) {
        listeners.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
                .add(new Entry(listener));
    }

    @Override
    public void unregister(long userId, NotificationPushListener listener) {
        Set<Entry> set = listeners.get(userId);
        if (set == null) {
            return;
        }
        set.removeIf(entry -> entry.listener == listener);
        if (set.isEmpty()) {
            listeners.remove(userId, set);
        }
    }

    @Override
    public void push(long userId, String json) {
        Set<Entry> set = listeners.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (Entry entry : set) {
            submit(userId, entry, json);
        }
    }

    @Override
    public void pingAll() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, Set<Entry>> group : listeners.entrySet()) {
            for (Entry entry : group.getValue()) {
                long busySince = entry.busySince;
                if (busySince != 0L && now - busySince > STUCK_MILLIS) {
                    LOG.log(Level.FINE, "Dropping stuck notification stream for user " + group.getKey());
                    drop(group.getKey(), entry);
                } else {
                    submit(group.getKey(), entry, null);
                }
            }
        }
    }

    @Override
    public int connectionCount() {
        int total = 0;
        for (Set<Entry> set : listeners.values()) {
            total += set.size();
        }
        return total;
    }

    private void submit(long userId, Entry entry, String json) {
        ThreadPoolExecutor pool = writer;
        if (pool == null) {
            return;
        }
        pool.execute(() -> write(userId, entry, json));
    }

    private void write(long userId, Entry entry, String json) {
        entry.busySince = System.currentTimeMillis();
        try {
            if (json == null) {
                entry.listener.onPing();
            } else {
                entry.listener.onNotification(json);
            }
        } catch (RuntimeException ex) {
            drop(userId, entry);
        } finally {
            entry.busySince = 0L;
        }
    }

    private void drop(long userId, Entry entry) {
        listeners.getOrDefault(userId, Set.of()).remove(entry);
        try {
            entry.listener.close();
        } catch (RuntimeException ignored) {
            // already closed by the client
        }
    }

    /** A registered listener plus the timestamp of its in-flight write. */
    private static final class Entry {
        private final NotificationPushListener listener;
        private volatile long busySince;

        private Entry(NotificationPushListener listener) {
            this.listener = listener;
        }
    }
}
