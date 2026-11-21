package com.smarthospital.platform.notify;

import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

/**
 * Keeps idle SSE connections alive. Proxies and browsers drop a silent stream
 * after 30-60 seconds, so a comment frame is sent every 20 seconds (ADR-006).
 */
@Singleton
@Startup
public class NotificationHeartbeat {

    @EJB
    private NotificationPushRegistryLocal registry;

    @Schedule(second = "*/20", minute = "*", hour = "*", persistent = false)
    public void ping() {
        try {
            registry.pingAll();
        } catch (RuntimeException ex) {
            // A broken stream must never cancel the keep-alive timer.
        }
    }
}
