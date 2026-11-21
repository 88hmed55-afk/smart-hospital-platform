package com.smarthospital.platform.health;

import com.smarthospital.shared.config.AppConfig;
import jakarta.annotation.Resource;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only operational health probe. It never writes, never caches and never
 * exposes connection details.
 */
@Stateless
public class HealthService implements HealthServiceLocal {

    private static final Logger LOG = Logger.getLogger(HealthService.class.getName());

    private static final String UP = "UP";
    private static final String WARN = "WARN";
    private static final String DOWN = "DOWN";

    @Resource(lookup = "jdbc/smartHospitalDS")
    private DataSource dataSource;

    @EJB
    private AppConfig config;

    @Override
    public HealthResult check() {
        List<HealthCheck> checks = new ArrayList<>();
        checks.add(checkDatabase());
        checks.add(checkDisk());

        String overall;
        if (checks.stream().anyMatch(c -> DOWN.equals(c.getStatus()))) {
            overall = DOWN;
        } else if (checks.stream().anyMatch(c -> WARN.equals(c.getStatus()))) {
            overall = "DEGRADED";
        } else {
            overall = UP;
        }

        return new HealthResult(
                overall,
                config.getVersion(),
                config.getEnvironment(),
                Instant.now().toString(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                checks);
    }

    private HealthCheck checkDatabase() {
        long started = System.nanoTime();
        if (dataSource == null) {
            return new HealthCheck("database", DOWN, "DataSource not bound", elapsedMs(started));
        }
        int timeout = config.getInt("health.db.timeout-seconds", 3);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("SELECT 1");
            return new HealthCheck("database", UP, "reachable", elapsedMs(started));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Database health check failed", ex);
            return new HealthCheck("database", DOWN, safeMessage(ex), elapsedMs(started));
        }
    }

    private HealthCheck checkDisk() {
        long started = System.nanoTime();
        Path path = Paths.get(config.get("health.disk.path", ".")).toAbsolutePath().normalize();
        long minimumMb = config.getInt("health.disk.min-free-mb", 2048);
        try {
            FileStore store = Files.getFileStore(path);
            long freeMb = store.getUsableSpace() / (1024L * 1024L);
            if (freeMb < minimumMb) {
                return new HealthCheck("disk", DOWN,
                        "free=" + freeMb + "MB below minimum=" + minimumMb + "MB",
                        elapsedMs(started));
            }
            return new HealthCheck("disk", UP, "free=" + freeMb + "MB", elapsedMs(started));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Disk health check failed for " + path, ex);
            return new HealthCheck("disk", WARN, safeMessage(ex), elapsedMs(started));
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
