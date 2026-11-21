package com.smarthospital.platform.notify;

import jakarta.ejb.EJB;
import jakarta.ejb.Local;
import jakarta.ejb.Stateless;

import java.util.List;
import java.util.Map;

/** Read/retry facade used by the operations endpoints. */
@Stateless
@Local(OutboxAdminServiceLocal.class)
public class OutboxAdminService implements OutboxAdminServiceLocal {

    @EJB
    private OutboxDispatcher dispatcher;

    @Override
    public Map<String, Object> stats() {
        return dispatcher.stats();
    }

    @Override
    public Map<String, Object> events(String status, int limit) {
        return dispatcher.events(status, limit);
    }

    @Override
    public Map<String, Object> retry(long eventId) {
        return dispatcher.retry(eventId);
    }

    @Override
    public List<Map<String, Object>> templates() {
        return dispatcher.templates();
    }

    @Override
    public Map<String, Object> updateTemplate(String code, Map<String, Object> body) {
        return dispatcher.updateTemplate(code, body);
    }
}
