package com.smarthospital.platform.iam;

import com.smarthospital.shared.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.DependsOn;
import jakarta.ejb.EJB;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs the first-run bootstrap once, after configuration is loaded. */
@Singleton
@Startup
@DependsOn("AppConfig")
public class AdminBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class.getName());

    @EJB
    private BootstrapService bootstrapService;

    @EJB
    private AppConfig config;

    @PostConstruct
    void bootstrap() {
        try {
            bootstrapService.bootstrapIfEmpty(
                    config.getEnvironment(),
                    config.get("iam.bootstrap.admin_password", null));
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Administrator bootstrap failed", ex);
        }
    }
}
