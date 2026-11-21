package com.smarthospital.platform.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cookie and request helpers shared by the authentication filter and resources.
 *
 * <p>The session cookie is HttpOnly and SameSite=Strict; the CSRF cookie is
 * intentionally readable by JavaScript (double-submit pattern). Neither value
 * is ever returned in a JSON body.
 */
final class WebCookies {

    static final String SESSION_COOKIE = "SH_SESSION";
    static final String CSRF_COOKIE = "SH_CSRF";
    static final String CSRF_HEADER = "X-CSRF-Token";

    static final String PRINCIPAL_ATTRIBUTE = "smarthospital.principal";
    static final String PENDING_MFA_ATTRIBUTE = "smarthospital.pendingMfa";
    static final String CSRF_ATTRIBUTE = "smarthospital.csrf";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_AGE_SECONDS = 12 * 60 * 60;

    private WebCookies() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean secure() {
        String explicit = System.getProperty("smarthospital.cookies.secure");
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit);
        }
        String environment = System.getenv("SMART_HOSPITAL_ENV");
        return environment != null && environment.equalsIgnoreCase("PROD");
    }

    static void setSessionCookie(HttpServletResponse response, String token) {
        writeCookie(response, SESSION_COOKIE, token, true, MAX_AGE_SECONDS);
    }

    static void clearSessionCookie(HttpServletResponse response) {
        writeCookie(response, SESSION_COOKIE, "", true, 0);
    }

    static void setCsrfCookie(HttpServletResponse response, String token) {
        writeCookie(response, CSRF_COOKIE, token, false, MAX_AGE_SECONDS);
    }

    static void clearCsrfCookie(HttpServletResponse response) {
        writeCookie(response, CSRF_COOKIE, "", false, 0);
    }

    private static void writeCookie(HttpServletResponse response, String name, String value,
                                    boolean httpOnly, int maxAge) {
        StringBuilder header = new StringBuilder();
        header.append(name).append('=').append(value)
                .append("; Path=/; Max-Age=").append(maxAge)
                .append("; SameSite=Strict");
        if (httpOnly) {
            header.append("; HttpOnly");
        }
        if (secure()) {
            header.append("; Secure");
        }
        response.addHeader("Set-Cookie", header.toString());
    }

    static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    static String userAgent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        if (value == null) {
            return null;
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
