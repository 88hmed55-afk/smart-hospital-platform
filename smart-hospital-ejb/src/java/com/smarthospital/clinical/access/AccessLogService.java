package com.smarthospital.clinical.access;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Patient-data access log (NFR-PRV-002, BR-EMR-008).
 *
 * <p>Every read of a patient chart, encounter or timeline is recorded with the
 * actor, the patient and the resource. The log is append-only by convention and
 * is written in the same transaction as the read so an audit trail cannot be
 * silently dropped.
 */
@Stateless
public class AccessLogService implements AccessLogServiceLocal {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @Override
    public void record(Long userId, Long patientId, String resourceType,
                       String resourcePublicId, String action, String ip) {
        if (userId == null) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO access_logs (user_id, patient_id, resource_type, "
                                + "resource_public_id, action, ip) VALUES (?, ?, ?, ?, ?, ?)")
                .setParameter(1, userId)
                .setParameter(2, patientId)
                .setParameter(3, resourceType)
                .setParameter(4, resourcePublicId)
                .setParameter(5, action)
                .setParameter(6, ip)
                .executeUpdate();
    }
}
