package com.smarthospital.platform.iam;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Persistence gateway for identity and access data.
 *
 * <p>Entities cover the tables that the application mutates through JPA;
 * link tables (user_roles, role_permissions) and append-only history tables use
 * native statements because they have no standalone identity.
 */
@Stateless
public class IamRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Users
    // ------------------------------------------------------------------

    public User findUserByUsername(String username) {
        List<User> rows = em.createQuery(
                        "select u from User u where u.username = :username", User.class)
                .setParameter("username", username)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public User findUserByPublicId(String publicId) {
        List<User> rows = em.createQuery(
                        "select u from User u where u.publicId = :publicId", User.class)
                .setParameter("publicId", publicId)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public User findUserById(Long id) {
        return id == null ? null : em.find(User.class, id);
    }

    public long countUsers(String query) {
        if (query == null || query.isBlank()) {
            return em.createQuery("select count(u) from User u", Long.class).getSingleResult();
        }
        return em.createQuery(
                        "select count(u) from User u where lower(u.username) like :q "
                                + "or lower(u.displayNameAr) like :q or lower(coalesce(u.displayNameEn, '')) like :q",
                        Long.class)
                .setParameter("q", "%" + query.trim().toLowerCase() + "%")
                .getSingleResult();
    }

    public List<User> listUsers(String query, int first, int max) {
        if (query == null || query.isBlank()) {
            return em.createQuery("select u from User u order by u.username", User.class)
                    .setFirstResult(first)
                    .setMaxResults(max)
                    .getResultList();
        }
        return em.createQuery(
                        "select u from User u where lower(u.username) like :q "
                                + "or lower(u.displayNameAr) like :q or lower(coalesce(u.displayNameEn, '')) like :q "
                                + "order by u.username",
                        User.class)
                .setParameter("q", "%" + query.trim().toLowerCase() + "%")
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList();
    }

    public User saveUser(User user) {
        if (user.getId() == null) {
            em.persist(user);
            // IDENTITY ids are assigned on insert; flush so callers can use the
            // generated id immediately (password history, role links, audit).
            em.flush();
            return user;
        }
        return em.merge(user);
    }

    // ------------------------------------------------------------------
    // Roles and permissions
    // ------------------------------------------------------------------

    public List<Role> listRoles() {
        return em.createQuery("select r from Role r order by r.code", Role.class).getResultList();
    }

    public Role findRoleByCode(String code) {
        List<Role> rows = em.createQuery("select r from Role r where r.code = :code", Role.class)
                .setParameter("code", code)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Role findRoleByPublicId(String publicId) {
        List<Role> rows = em.createQuery("select r from Role r where r.publicId = :publicId", Role.class)
                .setParameter("publicId", publicId)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Permission> listPermissions() {
        return em.createQuery("select p from Permission p order by p.domain, p.code", Permission.class)
                .getResultList();
    }

    public List<Role> findRolesByUserId(Long userId) {
        @SuppressWarnings("unchecked")
        List<Role> rows = em.createNativeQuery(
                        "SELECT r.* FROM roles r JOIN user_roles ur ON ur.role_id = r.id "
                                + "WHERE ur.user_id = ?1 ORDER BY r.code", Role.class)
                .setParameter(1, userId)
                .getResultList();
        return rows;
    }

    public List<String> findRoleCodesByUserId(Long userId) {
        return asStrings(em.createNativeQuery(
                        "SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id = r.id "
                                + "WHERE ur.user_id = ?1 ORDER BY r.code")
                .setParameter(1, userId)
                .getResultList());
    }

    public List<String> findPermissionCodesByUserId(Long userId) {
        return asStrings(em.createNativeQuery(
                        "SELECT DISTINCT p.code FROM permissions p "
                                + "JOIN role_permissions rp ON rp.permission_id = p.id "
                                + "JOIN user_roles ur ON ur.role_id = rp.role_id "
                                + "WHERE ur.user_id = ?1 ORDER BY p.code")
                .setParameter(1, userId)
                .getResultList());
    }

    public List<String> findPermissionCodesByRoleId(Long roleId) {
        return asStrings(em.createNativeQuery(
                        "SELECT p.code FROM permissions p "
                                + "JOIN role_permissions rp ON rp.permission_id = p.id "
                                + "WHERE rp.role_id = ?1 ORDER BY p.code")
                .setParameter(1, roleId)
                .getResultList());
    }

    public List<UserScope> findScopesByUserId(Long userId) {
        return em.createQuery(
                        "select s from UserScope s where s.userId = :userId order by s.scopeType, s.scopeRefId",
                        UserScope.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    public void replaceUserRoles(Long userId, Collection<Long> roleIds, Long assignedBy) {
        em.createNativeQuery("DELETE FROM user_roles WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        for (Long roleId : roleIds) {
            em.createNativeQuery(
                            "INSERT INTO user_roles (user_id, role_id, assigned_by) VALUES (?1, ?2, ?3)")
                    .setParameter(1, userId)
                    .setParameter(2, roleId)
                    .setParameter(3, assignedBy)
                    .executeUpdate();
        }
    }

    public void replaceRolePermissions(Long roleId, Collection<Long> permissionIds, Long assignedBy) {
        em.createNativeQuery("DELETE FROM role_permissions WHERE role_id = ?1")
                .setParameter(1, roleId)
                .executeUpdate();
        for (Long permissionId : permissionIds) {
            em.createNativeQuery(
                            "INSERT INTO role_permissions (role_id, permission_id, assigned_by) VALUES (?1, ?2, ?3)")
                    .setParameter(1, roleId)
                    .setParameter(2, permissionId)
                    .setParameter(3, assignedBy)
                    .executeUpdate();
        }
    }

    public void replaceUserScopes(Long userId, List<UserScope> scopes) {
        em.createNativeQuery("DELETE FROM user_scopes WHERE user_id = ?1")
                .setParameter(1, userId)
                .executeUpdate();
        for (UserScope scope : scopes) {
            em.persist(scope);
        }
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    public UserSession createSession(Long userId, String sessionHash, String ip, String userAgent) {
        UserSession session = new UserSession(userId, sessionHash, truncate(ip, 45), truncate(userAgent, 255));
        em.persist(session);
        return session;
    }

    public UserSession findSessionByHash(String sessionHash) {
        List<UserSession> rows = em.createQuery(
                        "select s from UserSession s where s.sessionHash = :hash", UserSession.class)
                .setParameter("hash", sessionHash)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<UserSession> listOpenSessions(Long userId) {
        return em.createQuery(
                        "select s from UserSession s where s.userId = :userId and s.endedAt is null "
                                + "order by s.startedAt", UserSession.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    public List<UserSession> listActiveSessions() {
        return em.createQuery(
                        "select s from UserSession s where s.endedAt is null order by s.lastSeenAt desc",
                        UserSession.class)
                .getResultList();
    }

    public long countOpenSessions(Long userId) {
        return em.createQuery(
                        "select count(s) from UserSession s where s.userId = :userId and s.endedAt is null",
                        Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    public void endSession(UserSession session, String reason) {
        session.end(reason);
        em.merge(session);
    }

    public void saveSession(UserSession session) {
        em.merge(session);
    }

    public void endOtherSessions(Long userId, String keepHash, String reason) {
        LocalDateTime now = LocalDateTime.now();
        if (keepHash == null) {
            em.createQuery("update UserSession s set s.endedAt = :now, s.endReason = :reason "
                            + "where s.userId = :userId and s.endedAt is null")
                    .setParameter("now", now)
                    .setParameter("reason", reason)
                    .setParameter("userId", userId)
                    .executeUpdate();
            return;
        }
        em.createQuery("update UserSession s set s.endedAt = :now, s.endReason = :reason "
                        + "where s.userId = :userId and s.endedAt is null and s.sessionHash <> :keep")
                .setParameter("now", now)
                .setParameter("reason", reason)
                .setParameter("userId", userId)
                .setParameter("keep", keepHash)
                .executeUpdate();
    }

    public UserSession findSessionById(Long id) {
        return id == null ? null : em.find(UserSession.class, id);
    }

    // ------------------------------------------------------------------
    // Login attempts
    // ------------------------------------------------------------------

    public void recordLoginAttempt(String username, Long userId, String ip, boolean success, String reason) {
        em.persist(new LoginAttempt(
                truncate(username, 80),
                userId,
                truncate(ip, 45) == null ? "unknown" : truncate(ip, 45),
                success,
                truncate(reason, 50)));
    }

    public long countFailedAttemptsByUsername(String username, LocalDateTime since) {
        return em.createQuery(
                        "select count(a) from LoginAttempt a where a.usernameAttempted = :username "
                                + "and a.success = false and a.attemptedAt >= :since", Long.class)
                .setParameter("username", username)
                .setParameter("since", since)
                .getSingleResult();
    }

    public long countFailedAttemptsByIp(String ip, LocalDateTime since) {
        return em.createQuery(
                        "select count(a) from LoginAttempt a where a.ip = :ip "
                                + "and a.success = false and a.attemptedAt >= :since", Long.class)
                .setParameter("ip", ip)
                .setParameter("since", since)
                .getSingleResult();
    }

    public List<LoginAttempt> listLoginAttempts(int first, int max) {
        return em.createQuery("select a from LoginAttempt a order by a.attemptedAt desc", LoginAttempt.class)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList();
    }

    public long countLoginAttempts() {
        return em.createQuery("select count(a) from LoginAttempt a", Long.class).getSingleResult();
    }

    // ------------------------------------------------------------------
    // Password history
    // ------------------------------------------------------------------

    public List<PasswordHashRecord> findPasswordHistory(Long userId, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT password_hash, password_salt, password_algo, password_iterations "
                                + "FROM password_history WHERE user_id = ?1 ORDER BY created_at DESC LIMIT ?2")
                .setParameter(1, userId)
                .setParameter(2, limit)
                .getResultList();
        List<PasswordHashRecord> records = new ArrayList<>();
        for (Object[] row : rows) {
            records.add(new PasswordHashRecord(
                    String.valueOf(row[0]),
                    (byte[]) row[1],
                    String.valueOf(row[2]),
                    ((Number) row[3]).intValue()));
        }
        return records;
    }

    public void addPasswordHistory(Long userId, String hash, byte[] salt, String algorithm, int iterations) {
        em.createNativeQuery(
                        "INSERT INTO password_history "
                                + "(user_id, password_hash, password_salt, password_algo, password_iterations) "
                                + "VALUES (?1, ?2, ?3, ?4, ?5)")
                .setParameter(1, userId)
                .setParameter(2, hash)
                .setParameter(3, salt)
                .setParameter(4, algorithm)
                .setParameter(5, iterations)
                .executeUpdate();
    }

    // ------------------------------------------------------------------
    // Break-Glass
    // ------------------------------------------------------------------

    public BreakGlassGrant saveBreakGlass(BreakGlassGrant grant) {
        if (grant.getId() == null) {
            em.persist(grant);
            return grant;
        }
        return em.merge(grant);
    }

    public BreakGlassGrant findBreakGlassByPublicId(String publicId) {
        List<BreakGlassGrant> rows = em.createQuery(
                        "select g from BreakGlassGrant g where g.publicId = :publicId", BreakGlassGrant.class)
                .setParameter("publicId", publicId)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BreakGlassGrant> listBreakGlass(boolean all, Long requesterId, String status) {
        StringBuilder jpql = new StringBuilder("select g from BreakGlassGrant g where 1 = 1");
        if (!all) {
            jpql.append(" and g.requesterUserId = :requesterId");
        }
        if (status != null && !status.isBlank()) {
            jpql.append(" and g.status = :status");
        }
        jpql.append(" order by g.createdAt desc");
        var query = em.createQuery(jpql.toString(), BreakGlassGrant.class);
        if (!all) {
            query.setParameter("requesterId", requesterId);
        }
        if (status != null && !status.isBlank()) {
            query.setParameter("status", status);
        }
        return query.getResultList();
    }

    public int expireBreakGlass(LocalDateTime now) {
        return em.createQuery(
                        "update BreakGlassGrant g set g.status = 'EXPIRED' "
                                + "where g.status = 'GRANTED' and g.expiresAt < :now")
                .setParameter("now", now)
                .executeUpdate();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> asStrings(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row == null) {
                continue;
            }
            if (row instanceof Object[] array) {
                if (array.length > 0 && array[0] != null) {
                    values.add(String.valueOf(array[0]));
                }
            } else {
                values.add(String.valueOf(row));
            }
        }
        return values;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
