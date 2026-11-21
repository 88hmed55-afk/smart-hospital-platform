package com.smarthospital.platform.audit;

import com.smarthospital.platform.iam.SecurityTokens;
import com.smarthospital.shared.observability.TraceContext;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hash-chained, append-only audit writer (ADR-009).
 *
 * <p>Every write locks the single {@code audit_chain} row, so concurrent
 * transactions serialise on the chain instead of forking it. JSON values are
 * normalised by MySQL before hashing; verification therefore recomputes the
 * same canonical text that the database actually stored.
 */
@Stateless
public class AuditService implements AuditServiceLocal {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @Override
    public void record(Long actorUserId,
                       String action,
                       String entityType,
                       String entityPublicId,
                       String beforeJson,
                       String afterJson,
                       String reason,
                       String ip) {

        String safeAction = truncate(action, 80);
        String safeEntityType = truncate(entityType, 40);
        String safePublicId = truncate(entityPublicId, 26);
        String safeReason = truncate(reason, 500);
        String safeIp = truncate(ip, 45);
        String traceId = truncate(TraceContext.get(), 32);
        String before = normalizeJson(beforeJson);
        String after = normalizeJson(afterJson);
        LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        String prevHash = lockHead();
        String hash = hashOf(actorUserId, safeAction, safeEntityType, safePublicId,
                before, after, safeReason, safeIp, traceId, createdAt, prevHash);

        em.createNativeQuery(
                        "INSERT INTO audit_logs "
                                + "(actor_user_id, action, entity_type, entity_public_id, before_json, after_json, "
                                + " reason, ip, trace_id, prev_hash, hash, created_at) "
                                + "VALUES (?1, ?2, ?3, ?4, CAST(?5 AS JSON), CAST(?6 AS JSON), "
                                + " ?7, ?8, ?9, ?10, ?11, ?12)")
                .setParameter(1, actorUserId)
                .setParameter(2, safeAction)
                .setParameter(3, safeEntityType)
                .setParameter(4, safePublicId)
                .setParameter(5, before)
                .setParameter(6, after)
                .setParameter(7, safeReason)
                .setParameter(8, safeIp)
                .setParameter(9, traceId)
                .setParameter(10, prevHash)
                .setParameter(11, hash)
                .setParameter(12, Timestamp.valueOf(createdAt))
                .executeUpdate();

        em.createNativeQuery("UPDATE audit_chain SET last_hash = ?1, updated_at = CURRENT_TIMESTAMP(6) WHERE id = 1")
                .setParameter(1, hash)
                .executeUpdate();
    }

    @Override
    @jakarta.ejb.TransactionAttribute(jakarta.ejb.TransactionAttributeType.REQUIRES_NEW)
    public void recordRejection(Long actorUserId,
                                String action,
                                String entityType,
                                String entityPublicId,
                                String reason,
                                String ip) {
        record(actorUserId, action, entityType, entityPublicId, null, null, reason, ip);
    }

    @Override
    public List<Map<String, Object>> search(String action,
                                            String entityType,
                                            String entityPublicId,
                                            LocalDateTime from,
                                            LocalDateTime to,
                                            int first,
                                            int max) {
        StringBuilder jpql = new StringBuilder("select a from AuditLog a where 1 = 1");
        appendFilters(jpql, action, entityType, entityPublicId, from, to);
        jpql.append(" order by a.id desc");
        var query = em.createQuery(jpql.toString(), AuditLog.class)
                .setFirstResult(first)
                .setMaxResults(max);
        applyFilters(query, action, entityType, entityPublicId, from, to);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditLog row : query.getResultList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("actorUserId", row.getActorUserId());
            item.put("action", row.getAction());
            item.put("entityType", row.getEntityType());
            item.put("entityPublicId", row.getEntityPublicId());
            item.put("before", row.getBeforeJson());
            item.put("after", row.getAfterJson());
            item.put("reason", row.getReason());
            item.put("ip", row.getIp());
            item.put("traceId", row.getTraceId());
            item.put("prevHash", row.getPrevHash());
            item.put("hash", row.getHash());
            item.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
            items.add(item);
        }
        return items;
    }

    @Override
    public long count(String action,
                      String entityType,
                      String entityPublicId,
                      LocalDateTime from,
                      LocalDateTime to) {
        StringBuilder jpql = new StringBuilder("select count(a) from AuditLog a where 1 = 1");
        appendFilters(jpql, action, entityType, entityPublicId, from, to);
        var query = em.createQuery(jpql.toString(), Long.class);
        applyFilters(query, action, entityType, entityPublicId, from, to);
        return query.getSingleResult();
    }

    @Override
    public ChainVerification verifyChain() {
        List<AuditLog> rows = em.createQuery("select a from AuditLog a order by a.id", AuditLog.class)
                .getResultList();
        String previousHash = null;
        long checked = 0;
        for (AuditLog row : rows) {
            checked++;
            if (checked > 1 && !equalsNullSafe(previousHash, row.getPrevHash())) {
                return new ChainVerification(false, checked, row.getId(), previousHash, row.getPrevHash());
            }
            String expected = hashOf(row.getActorUserId(), row.getAction(), row.getEntityType(),
                    row.getEntityPublicId(), row.getBeforeJson(), row.getAfterJson(), row.getReason(),
                    row.getIp(), row.getTraceId(), row.getCreatedAt(), row.getPrevHash());
            if (!expected.equals(row.getHash())) {
                return new ChainVerification(false, checked, row.getId(), expected, row.getHash());
            }
            previousHash = row.getHash();
        }
        return new ChainVerification(true, checked, null, null, null);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String lockHead() {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                        "SELECT last_hash FROM audit_chain WHERE id = 1 FOR UPDATE")
                .getResultList();
        if (rows.isEmpty()) {
            em.createNativeQuery("INSERT INTO audit_chain (id, last_hash) VALUES (1, NULL)")
                    .executeUpdate();
            return null;
        }
        Object value = rows.get(0);
        if (value instanceof Object[] array) {
            value = array.length == 0 ? null : array[0];
        }
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Object normalized = em.createNativeQuery("SELECT CAST(?1 AS JSON)")
                .setParameter(1, json)
                .getSingleResult();
        if (normalized == null) {
            return null;
        }
        if (normalized instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(normalized);
    }

    private static String hashOf(Long actorUserId,
                                 String action,
                                 String entityType,
                                 String entityPublicId,
                                 String before,
                                 String after,
                                 String reason,
                                 String ip,
                                 String traceId,
                                 LocalDateTime createdAt,
                                 String prevHash) {
        String payload = n(actorUserId) + "|" + n(action) + "|" + n(entityType) + "|"
                + n(entityPublicId) + "|" + n(before) + "|" + n(after) + "|" + n(reason) + "|"
                + n(ip) + "|" + n(traceId) + "|" + n(createdAt) + "|" + n(prevHash);
        return SecurityTokens.sha256Hex(payload);
    }

    private static String n(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean equalsNullSafe(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void appendFilters(StringBuilder jpql,
                                      String action,
                                      String entityType,
                                      String entityPublicId,
                                      LocalDateTime from,
                                      LocalDateTime to) {
        if (action != null && !action.isBlank()) {
            jpql.append(" and a.action = :action");
        }
        if (entityType != null && !entityType.isBlank()) {
            jpql.append(" and a.entityType = :entityType");
        }
        if (entityPublicId != null && !entityPublicId.isBlank()) {
            jpql.append(" and a.entityPublicId = :entityPublicId");
        }
        if (from != null) {
            jpql.append(" and a.createdAt >= :from");
        }
        if (to != null) {
            jpql.append(" and a.createdAt <= :to");
        }
    }

    private static void applyFilters(jakarta.persistence.Query query,
                                     String action,
                                     String entityType,
                                     String entityPublicId,
                                     LocalDateTime from,
                                     LocalDateTime to) {
        if (action != null && !action.isBlank()) {
            query.setParameter("action", action);
        }
        if (entityType != null && !entityType.isBlank()) {
            query.setParameter("entityType", entityType);
        }
        if (entityPublicId != null && !entityPublicId.isBlank()) {
            query.setParameter("entityPublicId", entityPublicId);
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
    }
}
