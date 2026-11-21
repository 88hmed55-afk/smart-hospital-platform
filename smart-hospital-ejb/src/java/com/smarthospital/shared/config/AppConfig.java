package com.smarthospital.shared.config;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.ConcurrencyManagement;
import jakarta.ejb.ConcurrencyManagementType;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * External, read-only application configuration.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>JVM system property {@code smarthospital.config}</li>
 *   <li>Environment variable {@code SMART_HOSPITAL_CONFIG}</li>
 *   <li>{@code ${user.home}/smart-hospital/hospital.properties}</li>
 *   <li>Classpath resource {@code /hospital.properties}</li>
 * </ol>
 *
 * <p>Secrets must never live in this file. Database credentials belong to the
 * GlassFish JDBC resource, not to the application.
 */
@Singleton
@Startup
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());
    private static final String DEFAULT_CONFIG_PATH = "smart-hospital/hospital.properties";

    private final Properties properties = new Properties();

    @PostConstruct
    void load() {
        Path path = resolvePath();
        if (path != null && Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
                LOG.info(() -> "Configuration loaded from " + path.toAbsolutePath());
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "Cannot read configuration file: " + path, ex);
            }
        } else {
            LOG.warning("No external configuration found; defaults will be used");
        }

        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("hospital.properties")) {
            if (in != null) {
                properties.load(in);
                LOG.info("Classpath configuration merged");
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Cannot read classpath configuration", ex);
        }
    }

    private Path resolvePath() {
        String explicit = System.getProperty("smarthospital.config");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("SMART_HOSPITAL_CONFIG");
        }
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Paths.get(home, DEFAULT_CONFIG_PATH);
    }

    public String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public int getInt(String key, int defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            LOG.warning(() -> "Invalid integer for " + key + ": " + raw);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public String getVersion() {
        return get("app.version", "0.0.0-dev");
    }

    public String getEnvironment() {
        return get("app.environment", "DEV");
    }
}
