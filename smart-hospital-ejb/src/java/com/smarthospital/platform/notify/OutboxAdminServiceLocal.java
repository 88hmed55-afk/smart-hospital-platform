package com.smarthospital.platform.notify;

import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Operations view of the transactional outbox (NFR-OPS monitoring). */
@Local
public interface OutboxAdminServiceLocal {

    Map<String, Object> stats();

    Map<String, Object> events(String status, int limit);

    Map<String, Object> retry(long eventId);

    List<Map<String, Object>> templates();

    Map<String, Object> updateTemplate(String code, Map<String, Object> body);
}
