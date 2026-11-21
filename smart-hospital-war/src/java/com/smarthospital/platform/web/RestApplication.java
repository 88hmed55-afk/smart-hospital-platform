package com.smarthospital.platform.web;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Single JAX-RS entry point. All resources live under {@code /api/v1}.
 */
@ApplicationPath("/api/v1")
public class RestApplication extends Application {
}
