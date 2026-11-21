package com.smarthospital.platform.health;

import jakarta.ejb.Local;

/**
 * Local contract consumed by the web layer.
 */
@Local
public interface HealthServiceLocal {

    HealthResult check();
}
