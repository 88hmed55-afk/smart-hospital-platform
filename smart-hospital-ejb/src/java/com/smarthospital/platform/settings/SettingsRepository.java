package com.smarthospital.platform.settings;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.Optional;

/**
 * Read access to platform settings. Writes arrive in Phase 2 with the
 * {@code SYS:CONFIG} permission and audit trail.
 */
@Stateless
public class SettingsRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    public Optional<String> findValue(String key) {
        List<String> values = em.createQuery(
                        "select s.value from Setting s where s.key = :key", String.class)
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList();
        return values.stream().findFirst();
    }

    public List<Setting> findAll() {
        return em.createQuery("select s from Setting s order by s.category, s.key", Setting.class)
                .getResultList();
    }

    public Optional<Setting> findByKey(String key) {
        List<Setting> rows = em.createQuery("select s from Setting s where s.key = :key", Setting.class)
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst();
    }

    public void save(Setting setting) {
        em.merge(setting);
    }
}
