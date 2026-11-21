package com.smarthospital.clinical.access;

import jakarta.ejb.Local;

@Local
public interface AccessLogServiceLocal {

    void record(Long userId, Long patientId, String resourceType,
                String resourcePublicId, String action, String ip);
}
