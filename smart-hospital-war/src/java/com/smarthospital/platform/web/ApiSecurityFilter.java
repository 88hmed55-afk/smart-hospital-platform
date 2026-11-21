package com.smarthospital.platform.web;

import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthServiceLocal;
import jakarta.ejb.EJB;
import jakarta.json.Json;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Central API guard: session validation, CSRF double-submit and the first-login
 * password-change gate.
 *
 * <p>Filter order is declared in {@code web.xml} so the trace filter always runs
 * first and every rejection still carries an {@code X-Trace-Id}.
 */
public class ApiSecurityFilter implements Filter {

    @EJB
    private AuthServiceLocal authService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = httpRequest.getSession(false);
        AuthPrincipal principal = resolvePrincipal(httpRequest, session);
        if (principal != null) {
            httpRequest.setAttribute(WebCookies.PRINCIPAL_ATTRIBUTE, principal);
        }

        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean mfaPending = session != null
                && session.getAttribute(WebCookies.PENDING_MFA_ATTRIBUTE) != null;

        if (principal == null) {
            if (mfaPending && path.equals("/api/v1/auth/mfa/verify")) {
                // Pending MFA has no DB session yet; CSRF still applies.
                if (!csrfValid(httpRequest, session)) {
                    reject(httpRequest, httpResponse, 403, "CSRF_INVALID",
                            "رمز الحماية من CSRF غير صالح.",
                            "The CSRF token is invalid.");
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            reject(httpRequest, httpResponse, 401, "AUTH_REQUIRED",
                    "يلزم تسجيل الدخول.",
                    "Authentication required.");
            return;
        }

        if (!principal.isMfaVerified()) {
            reject(httpRequest, httpResponse, 401, "MFA_REQUIRED",
                    "يلزم إكمال التحقق بخطوة ثانية.",
                    "Second-factor verification is required.");
            return;
        }

        if (principal.isMustChangePassword() && !passwordChangeAllowed(path)) {
            reject(httpRequest, httpResponse, 403, "PASSWORD_CHANGE_REQUIRED",
                    "يجب تغيير كلمة المرور قبل المتابعة.",
                    "You must change your password before continuing.");
            return;
        }

        if (isUnsafe(httpRequest.getMethod()) && !csrfValid(httpRequest, session)) {
            reject(httpRequest, httpResponse, 403, "CSRF_INVALID",
                    "رمز الحماية من CSRF غير صالح.",
                    "The CSRF token is invalid.");
            return;
        }

        chain.doFilter(request, response);
    }

    private AuthPrincipal resolvePrincipal(HttpServletRequest request, HttpSession session) {
        String token = WebCookies.cookie(request, WebCookies.SESSION_COOKIE);
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return authService.loadPrincipalByToken(token);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isPublic(String path) {
        return path.equals("/api/v1/health")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/csrf");
    }

    private static boolean isUnsafe(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static boolean passwordChangeAllowed(String path) {
        return path.equals("/api/v1/auth/change-password")
                || path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/auth/csrf")
                || path.startsWith("/api/v1/auth/mfa/");
    }

    private static boolean csrfValid(HttpServletRequest request, HttpSession session) {
        if (session == null) {
            return false;
        }
        Object stored = session.getAttribute(WebCookies.CSRF_ATTRIBUTE);
        String header = request.getHeader(WebCookies.CSRF_HEADER);
        String cookie = WebCookies.cookie(request, WebCookies.CSRF_COOKIE);
        if (stored == null || header == null || cookie == null) {
            return false;
        }
        String expected = stored.toString();
        return constantTimeEquals(expected, header) && constantTimeEquals(header, cookie);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
                               int status, String code, String messageAr, String messageEn)
            throws IOException {
        Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        String traceId = trace == null ? null : trace.toString();
        WebCookies.noStore(response);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.createObjectBuilder()
                .add("code", code)
                .add("messageAr", messageAr)
                .add("messageEn", messageEn)
                .add("traceId", traceId == null ? "" : traceId)
                .build()
                .toString());
    }
}
