package com.smarthospital.platform.health;

import java.util.Collections;
import java.util.List;

/**
 * Aggregated health snapshot returned by {@code GET /api/v1/health}.
 */
public class HealthResult {

    private final String status;
    private final String version;
    private final String environment;
    private final String timestamp;
    private final long uptimeMs;
    private final List<HealthCheck> checks;

    public HealthResult(String status, String version, String environment,
                        String timestamp, long uptimeMs, List<HealthCheck> checks) {
        this.status = status;
        this.version = version;
        this.environment = environment;
        this.timestamp = timestamp;
        this.uptimeMs = uptimeMs;
        this.checks = Collections.unmodifiableList(checks);
    }

    public String getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public long getUptimeMs() {
        return uptimeMs;
    }

    public List<HealthCheck> getChecks() {
        return checks;
    }

    public boolean isUp() {
        return "UP".equals(status);
    }
}
