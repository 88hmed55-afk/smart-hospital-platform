package com.smarthospital.shared.observability;

/**
 * Per-request trace id. The web filter sets it before calling into EJB services
 * and clears it afterwards; services use it when writing audit records.
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
