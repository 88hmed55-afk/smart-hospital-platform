package com.smarthospital.platform.web;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.annotation.WebListener;

import java.util.logging.Logger;

/**
 * Hardens the container session cookie. The application's authentication cookie
 * is written by {@link WebCookies}; this listener covers the JSESSIONID used to
 * carry the server-side principal and CSRF state.
 */
@WebListener
public class SessionCookieConfigListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(SessionCookieConfigListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        SessionCookieConfig config = event.getServletContext().getSessionCookieConfig();
        config.setName("SH_JSESSION");
        config.setHttpOnly(true);
        config.setSecure(WebCookies.secure());
        config.setPath("/");
        LOG.info(() -> "Session cookie configured: HttpOnly=true, Secure=" + WebCookies.secure()
                + ", SameSite is applied by the application cookie");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        // Nothing to release.
    }
}
