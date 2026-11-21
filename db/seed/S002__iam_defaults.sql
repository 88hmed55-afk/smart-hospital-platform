-- Smart Hospital — S002
-- Phase 2 IAM reference data: write/reset/break-glass permissions,
-- role → permission mapping, and security settings.
-- Idempotent: safe to run repeatedly and never overwrites owner values.

SET NAMES utf8mb4;

INSERT IGNORE INTO permissions (code, domain, action, description_ar) VALUES
  ('IAM:WRITE',       'IAM', 'WRITE',       'إدارة المستخدمين والأدوار'),
  ('IAM:RESET',       'IAM', 'RESET',       'إعادة تعيين كلمات المرور'),
  ('IAM:BREAK_GLASS', 'IAM', 'BREAK_GLASS', 'طلب وإلغاء وصول طارئ');

-- ---------------------------------------------------------------------------
-- Role → permission mapping
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'SYS_ADMIN';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'HOSP_ADMIN'
  AND p.code NOT IN ('SYS:OPS', 'BACKUP:TRIGGER');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'AUDITOR'
  AND (p.domain IN ('AUDIT', 'LOG')
       OR p.code IN ('RPT:AUDIT', 'ANL:READ', 'ANL:DATA_QUALITY', 'IAM:READ'));

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'IT_OPERATOR'
  AND p.code IN ('SYS:OPS', 'SYS:CONFIG', 'BACKUP:TRIGGER', 'LOG:READ',
                 'IAM:READ', 'IAM:UPDATE');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'RECEPTION'
  AND (p.domain = 'PAT'
       OR p.code IN ('ADT:READ', 'ADT:REQUEST', 'ADT:BED_STATUS',
                     'BIL:CREATE', 'BIL:READ', 'BIL:PRINT'));

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DOCTOR'
  AND p.code IN ('PAT:READ', 'PAT:UPDATE',
                 'EMR:CREATE', 'EMR:READ', 'EMR:UPDATE', 'EMR:DIAGNOSE', 'EMR:SIGN',
                 'LAB:ORDER', 'LAB:READ', 'RAD:ORDER', 'RAD:READ',
                 'PHR:PRESCRIBE', 'PHR:READ', 'VITALS:CREATE', 'NUR:READ',
                 'ADT:READ', 'ADT:REQUEST', 'MED:CATALOG_READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CONSULTANT'
  AND p.code IN ('PAT:READ', 'PAT:UPDATE',
                 'EMR:CREATE', 'EMR:READ', 'EMR:UPDATE', 'EMR:DIAGNOSE', 'EMR:SIGN',
                 'EMR:READ_ALL', 'EMR:AMEND_LATE',
                 'LAB:ORDER', 'LAB:READ', 'RAD:ORDER', 'RAD:READ',
                 'PHR:PRESCRIBE', 'PHR:READ', 'VITALS:CREATE', 'NUR:READ',
                 'ADT:READ', 'ADT:REQUEST', 'MED:CATALOG_READ', 'DEC:REVIEW');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'NURSE'
  AND p.code IN ('PAT:READ', 'EMR:READ', 'NUR:READ', 'NUR:NOTE_SIGN',
                 'VITALS:CREATE', 'MED:ADMINISTER', 'MED:CATALOG_READ',
                 'LAB:COLLECT', 'LAB:READ', 'ADT:READ', 'ADT:BED_STATUS',
                 'ADT:REQUEST', 'PHR:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'TRIAGE_NURSE'
  AND p.code IN ('PAT:CREATE_TEMP', 'PAT:READ', 'ER:TRIAGE', 'ER:QUEUE', 'ER:CREATE',
                 'VITALS:CREATE', 'ADT:READ', 'ADT:REQUEST', 'NUR:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'LAB_TECH'
  AND p.code IN ('PAT:READ', 'LAB:READ', 'LAB:RECEIVE', 'LAB:PROCESS',
                 'LAB:RESULT_ENTER', 'LAB:PRINT');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'LAB_SUPERVISOR'
  AND (p.domain = 'LAB' OR p.code = 'PAT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'RADIOLOGIST'
  AND (p.domain = 'RAD' OR p.code = 'PAT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'PHARMACIST'
  AND p.code IN ('PAT:READ', 'PHR:READ', 'PHR:REVIEW', 'PHR:DISPENSE',
                 'PHR:RETURN', 'PHR:BATCH_READ', 'MED:CATALOG_READ',
                 'INV:READ', 'INV:ISSUE');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CASHIER'
  AND p.code IN ('PAT:READ', 'BIL:CREATE', 'BIL:READ', 'BIL:PAYMENT',
                 'BIL:PRINT', 'BIL:DISCOUNT_LIMITED');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'FIN_MANAGER'
  AND (p.domain = 'BIL'
       OR p.code IN ('RPT:FIN', 'ANL:FIN', 'DEC:APPROVE_FIN', 'INV:READ'));

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'STOREKEEPER'
  AND (p.domain = 'INV' OR p.code IN ('MED:CATALOG_READ', 'INV:READ'));

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'HR_OFFICER'
  AND p.code IN ('HR:READ', 'RPT:HR', 'IAM:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DEPT_MANAGER'
  AND p.code IN ('ANL:DEPT', 'RPT:DEPT', 'DEC:REVIEW_DEPT', 'EMR:READ_AGGREGATE',
                 'EMR:READ', 'PAT:READ', 'LAB:READ', 'RAD:READ');

-- ---------------------------------------------------------------------------
-- Security settings (INSERT IGNORE keeps owner changes on re-seed)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO settings (setting_key, setting_value, value_type, category, is_sensitive) VALUES
  ('security.password.min_length',      '12',  'INT', 'SECURITY', 0),
  ('security.password.history',         '5',   'INT', 'SECURITY', 0),
  ('security.password.expiry_days',     '365', 'INT', 'SECURITY', 0),
  ('security.lockout.threshold',        '5',   'INT', 'SECURITY', 0),
  ('security.lockout.minutes',          '15',  'INT', 'SECURITY', 0),
  ('security.session.idle_minutes',     '30',  'INT', 'SECURITY', 0),
  ('security.session.absolute_hours',   '12',  'INT', 'SECURITY', 0),
  ('security.session.max_concurrent',   '3',   'INT', 'SECURITY', 0),
  ('security.break_glass.max_minutes',  '60',  'INT', 'SECURITY', 0),
  ('security.audit.hash_algorithm',     'SHA-256', 'STRING', 'SECURITY', 0);
