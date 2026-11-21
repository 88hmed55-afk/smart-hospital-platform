package com.smarthospital.platform.health;

/**
 * One measurable health check.
 */
public class HealthCheck {

    private final String name;
    private final String status;
    private final String detail;
    private final long latencyMs;

    public HealthCheck(String name, String status, String detail, long latencyMs) {
        this.name = name;
        this.status = status;
        this.detail = detail;
        this.latencyMs = latencyMs;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public long getLatencyMs() {
        return latencyMs;
    }
}
