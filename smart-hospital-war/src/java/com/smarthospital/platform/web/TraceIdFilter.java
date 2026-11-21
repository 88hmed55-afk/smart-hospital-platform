package com.smarthospital.platform.web;

import com.smarthospital.shared.observability.TraceContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Assigns a correlation id to every request, adds baseline security headers
 * and emits one structured access line.
 *
 * <p>The trace id is echoed in {@code X-Trace-Id} and stored as a request
 * attribute so the error mapper can include it in problem responses.
 */
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID_ATTRIBUTE = "smarthospital.traceId";
    private static final Logger LOG = Logger.getLogger(TraceIdFilter.class.getName());

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Force UTF-8 before any parameter or body parsing. Without this the
        // container falls back to the platform charset and Arabic request bodies
        // are silently mangled on Windows servers.
        if (httpRequest.getCharacterEncoding() == null) {
            httpRequest.setCharacterEncoding("UTF-8");
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        httpRequest.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        httpResponse.setHeader("X-Trace-Id", traceId);
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Referrer-Policy", "no-referrer");
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
                        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if (httpRequest.getRequestURI().startsWith("/api/")) {
            httpResponse.setHeader("Cache-Control", "no-store");
        }

        long started = System.nanoTime();
        TraceContext.set(traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            LOG.info(() -> "traceId=" + traceId
                    + " method=" + httpRequest.getMethod()
                    + " path=" + httpRequest.getRequestURI()
                    + " status=" + httpResponse.getStatus()
                    + " durationMs=" + elapsedMs);
        }
    }
}
