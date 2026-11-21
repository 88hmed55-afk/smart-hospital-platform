-- Smart Hospital — S000
-- Minimal platform defaults required before the first login (Phase 1/2).
-- Idempotent and non-destructive: existing owner values are preserved.

SET NAMES utf8mb4;

INSERT IGNORE INTO branches (public_id, code, name_ar, name_en, timezone, active)
VALUES ('000000000000000000000000A1', 'MAIN', 'المستشفى الرئيسي', 'Main Hospital', 'Asia/Amman', 1);

INSERT INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A2', id, 'GEN-MED', 'الطب العام', 'General Medicine', 'CLINICAL', 1
FROM branches
WHERE code = 'MAIN'
ON DUPLICATE KEY UPDATE name_ar = VALUES(name_ar), name_en = VALUES(name_en);

INSERT IGNORE INTO settings (setting_key, setting_value, value_type, category, is_sensitive) VALUES
  ('hospital.name_ar',        'المستشفى الرئيسي', 'STRING', 'HOSPITAL', 0),
  ('hospital.name_en',        'Main Hospital',    'STRING', 'HOSPITAL', 0),
  ('hospital.currency',       'JOD',               'STRING', 'HOSPITAL', 0),
  ('hospital.timezone',       'Asia/Amman',        'STRING', 'HOSPITAL', 0),
  ('app.default_locale',      'ar',                'STRING', 'HOSPITAL', 0),
  ('print.footer_ar',         'نظام إدارة المستشفى', 'STRING', 'PRINT', 0),
  ('security.password.iterations', '600000',       'INT',    'SECURITY', 0);
