-- Smart Hospital — S003
-- Phase 3 reference data: scheduling permissions, clinics, service/drug
-- catalogs, prices and clinical settings.
-- Idempotent: safe to run repeatedly and never overwrites owner values.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Scheduling permissions (FR-SCH-001..010)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO permissions (code, domain, action, description_ar) VALUES
  ('SCH:READ',     'SCH', 'READ',     'عرض الجداول والمواعيد والطابور'),
  ('SCH:CREATE',   'SCH', 'CREATE',   'حجز موعد جديد'),
  ('SCH:WRITE',    'SCH', 'WRITE',    'تعديل/إلغاء/إعادة جدولة المواعيد'),
  ('SCH:CHECKIN',  'SCH', 'CHECKIN',  'تسجيل وصول المريض وإدارة الطابور'),
  ('SCH:OVERBOOK', 'SCH', 'OVERBOOK', 'الحجز فوق الطاقة الاستيعابية');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'RECEPTION'
  AND p.code IN ('SCH:READ', 'SCH:CREATE', 'SCH:WRITE', 'SCH:CHECKIN');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DOCTOR'
  AND p.code IN ('SCH:READ', 'SCH:WRITE');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CONSULTANT'
  AND p.code IN ('SCH:READ', 'SCH:WRITE', 'SCH:OVERBOOK');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'NURSE'
  AND p.code IN ('SCH:READ', 'SCH:CHECKIN');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'TRIAGE_NURSE'
  AND p.code IN ('SCH:READ', 'SCH:CHECKIN');

-- SYS_ADMIN and HOSP_ADMIN receive new permissions on the next S002 seed run;
-- map them explicitly here as well so a Phase 3-only seed is self-sufficient.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code IN ('SYS_ADMIN', 'HOSP_ADMIN')
  AND p.domain = 'SCH';

-- ---------------------------------------------------------------------------
-- Clinics
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO clinics
    (public_id, branch_id, department_id, code, name_ar, name_en, location, active)
SELECT '000000000000000000000000C1', b.id, d.id,
       'OPD-GEN', 'عيادة الطب العام', 'General Medicine Clinic', 'Ground floor', 1
FROM branches b
JOIN departments d ON d.branch_id = b.id AND d.code = 'GEN-MED'
WHERE b.code = 'MAIN';

INSERT IGNORE INTO clinics
    (public_id, branch_id, department_id, code, name_ar, name_en, location, active)
SELECT '000000000000000000000000C2', b.id, d.id,
       'OPD-FOLLOW', 'عيادة المتابعة', 'Follow-up Clinic', 'Ground floor', 1
FROM branches b
JOIN departments d ON d.branch_id = b.id AND d.code = 'GEN-MED'
WHERE b.code = 'MAIN';

-- ---------------------------------------------------------------------------
-- Service catalog + price list
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO service_catalog
    (public_id, code, name_ar, name_en, category, department_id, taxable, active)
SELECT '000000000000000000000000D1', 'CONSULT-GEN', 'استشارة طبية عامة', 'General consultation',
       'CONSULTATION', d.id, 0, 1
FROM departments d WHERE d.code = 'GEN-MED';

INSERT IGNORE INTO service_catalog
    (public_id, code, name_ar, name_en, category, department_id, taxable, active)
SELECT '000000000000000000000000D2', 'LAB-CBC', 'تعداد دم كامل', 'Complete blood count',
       'LAB', d.id, 0, 1
FROM departments d WHERE d.code = 'GEN-MED';

INSERT IGNORE INTO service_catalog
    (public_id, code, name_ar, name_en, category, department_id, taxable, active)
SELECT '000000000000000000000000D3', 'LAB-CREAT', 'كرياتينين الدم', 'Serum creatinine',
       'LAB', d.id, 0, 1
FROM departments d WHERE d.code = 'GEN-MED';

INSERT IGNORE INTO service_catalog
    (public_id, code, name_ar, name_en, category, department_id, taxable, active)
SELECT '000000000000000000000000D4', 'RAD-CXR', 'صورة صدر', 'Chest X-ray',
       'RAD', d.id, 0, 1
FROM departments d WHERE d.code = 'GEN-MED';

INSERT IGNORE INTO service_catalog
    (public_id, code, name_ar, name_en, category, department_id, taxable, active)
SELECT '000000000000000000000000D5', 'PROC-ECG', 'تخطيط قلب', 'Electrocardiogram',
       'PROCEDURE', d.id, 0, 1
FROM departments d WHERE d.code = 'GEN-MED';

INSERT IGNORE INTO service_prices (public_id, service_id, amount, currency, valid_from)
SELECT '000000000000000000000000E1', s.id, 15.00, 'JOD', '2026-01-01'
FROM service_catalog s WHERE s.code = 'CONSULT-GEN';

INSERT IGNORE INTO service_prices (public_id, service_id, amount, currency, valid_from)
SELECT '000000000000000000000000E2', s.id, 6.00, 'JOD', '2026-01-01'
FROM service_catalog s WHERE s.code = 'LAB-CBC';

INSERT IGNORE INTO service_prices (public_id, service_id, amount, currency, valid_from)
SELECT '000000000000000000000000E3', s.id, 5.00, 'JOD', '2026-01-01'
FROM service_catalog s WHERE s.code = 'LAB-CREAT';

INSERT IGNORE INTO service_prices (public_id, service_id, amount, currency, valid_from)
SELECT '000000000000000000000000E4', s.id, 12.00, 'JOD', '2026-01-01'
FROM service_catalog s WHERE s.code = 'RAD-CXR';

INSERT IGNORE INTO service_prices (public_id, service_id, amount, currency, valid_from)
SELECT '000000000000000000000000E5', s.id, 8.00, 'JOD', '2026-01-01'
FROM service_catalog s WHERE s.code = 'PROC-ECG';

-- ---------------------------------------------------------------------------
-- Drug catalog (prescriptions only; inventory is Phase 4)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO drugs
    (public_id, code, name_ar, name_en, form, strength, category, reorder_level, active) VALUES
  ('000000000000000000000000F1', 'AMOX-500', 'أموكسيسيلين', 'Amoxicillin', 'CAPSULE', '500 mg', 'ANTIBIOTIC', 20, 1),
  ('000000000000000000000000F2', 'PARAC-500', 'باراسيتامول', 'Paracetamol', 'TABLET', '500 mg', 'ANALGESIC', 50, 1),
  ('000000000000000000000000F3', 'OMEP-20', 'أوميبرازول', 'Omeprazole', 'CAPSULE', '20 mg', 'GASTRO', 20, 1),
  ('000000000000000000000000F4', 'METF-500', 'ميتفورمين', 'Metformin', 'TABLET', '500 mg', 'ANTIDIABETIC', 30, 1),
  ('000000000000000000000000F5', 'SALB-INH', 'سالبوتامول', 'Salbutamol', 'INHALER', '100 mcg', 'RESPIRATORY', 10, 1);

-- ---------------------------------------------------------------------------
-- Clinical settings (INSERT IGNORE keeps owner changes on re-seed)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO settings (setting_key, setting_value, value_type, category, is_sensitive) VALUES
  ('scheduling.slot.minutes',          '20',  'INT',  'SCHEDULING', 0),
  ('scheduling.checkin.early_minutes', '60',  'INT',  'SCHEDULING', 0),
  ('scheduling.checkin.late_minutes',  '30',  'INT',  'SCHEDULING', 0),
  ('scheduling.cancel.min_hours',      '24',  'INT',  'SCHEDULING', 0),
  ('scheduling.queue.prefix',          'Q',   'STRING', 'SCHEDULING', 0),
  ('emr.note.amend.window_hours',      '24',  'INT',  'CLINICAL', 0),
  ('emr.vitals.hard_range',            '{"temperature_c":[25,45],"heart_rate":[20,300],"respiratory_rate":[4,80],"systolic":[40,300],"diastolic":[20,200],"spo2":[0,100],"weight_kg":[0.3,400],"height_cm":[20,260],"pain_score":[0,10]}',
                                             'JSON', 'CLINICAL', 0);
