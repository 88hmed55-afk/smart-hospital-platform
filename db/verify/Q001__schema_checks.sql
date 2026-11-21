-- Smart Hospital — Q001
-- Structural verification after migrations. Every row should return ok = 1.

SET NAMES utf8mb4;

SELECT 'branches' AS object_name, COUNT(*) AS present
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'branches';

SELECT 'missing_tables' AS check_name, COUNT(*) AS failures
FROM (
    SELECT 'branches' AS t UNION ALL SELECT 'departments' UNION ALL
    SELECT 'settings' UNION ALL SELECT 'users' UNION ALL
    SELECT 'roles' UNION ALL SELECT 'permissions' UNION ALL
    SELECT 'role_permissions' UNION ALL SELECT 'user_roles' UNION ALL
    SELECT 'user_sessions' UNION ALL SELECT 'login_attempts' UNION ALL
    SELECT 'audit_logs' UNION ALL SELECT 'access_logs' UNION ALL
    SELECT 'outbox_events' UNION ALL SELECT 'idempotency_keys' UNION ALL
    SELECT 'job_runs'
) expected
LEFT JOIN information_schema.tables actual
       ON actual.table_schema = DATABASE()
      AND actual.table_name = expected.t
WHERE actual.table_name IS NULL;

SELECT 'audit_triggers' AS check_name, COUNT(*) AS present
FROM information_schema.triggers
WHERE trigger_schema = DATABASE()
  AND event_object_table = 'audit_logs';
