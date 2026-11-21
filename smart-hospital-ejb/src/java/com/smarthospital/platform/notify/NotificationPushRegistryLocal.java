package com.smarthospital.platform.notify;

import jakarta.ejb.Local;

/** Registry of live notification subscribers (SSE connections). */
@Local
public interface NotificationPushRegistryLocal {

    void register(long userId, NotificationPushListener listener);

    void unregister(long userId, NotificationPushListener listener);

    void push(long userId, String json);

    void pingAll();

    int connectionCount();
}
