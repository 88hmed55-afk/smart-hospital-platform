package com.smarthospital.platform.notify;

import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Local view of the notification inbox. */
public interface NotificationServiceLocal {

    Map<String, Object> list(long userId, boolean unreadOnly, int page, int size);

    Map<String, Object> summary(long userId);

    Map<String, Object> markRead(long userId, String publicId);

    Map<String, Object> markAllRead(long userId);

    Map<String, Object> preferences(long userId);

    Map<String, Object> updatePreferences(long userId, List<Map<String, Object>> entries);

    /**
     * True while the session behind an SSE stream is still open. The stream
     * closes itself on the next heartbeat after a logout or an admin revoke.
     */
    boolean sessionActive(String sessionHash);
}
