-- Smart Hospital — S004
-- Phase 4 reference data: ER triage levels, inpatient wards/beds, lab test
-- catalog, drug batches + opening stock, Phase 4 permissions/role mapping and
-- module settings.
-- Idempotent: safe to run repeatedly and never overwrites owner values.
--
-- Note: opening batches/stock are seeded only when at least one application
-- user already exists (received_by/performed_by are accountability columns and
-- cannot be NULL). On a brand-new database, run the seed again after the first
-- administrator account is created.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Phase 4 permissions missing from S001/S002
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO permissions (code, domain, action, description_ar) VALUES
  ('ER:READ',           'ER',  'READ',           'عرض لوحة الطوارئ والزيارات'),
  ('ER:WRITE',          'ER',  'WRITE',          'قرارات الطوارئ والتحويل والخروج'),
  ('ADT:WRITE',         'ADT', 'WRITE',          'طلب تنويم/نقل/خروج'),
  ('ADT:ASSIGN',        'ADT', 'ASSIGN',         'تخصيص وإلغاء تخصيص الأسرة'),
  ('ADT:HOUSEKEEPING',  'ADT', 'HOUSEKEEPING',   'تحديث حالة السرير (تنظيف/صيانة)'),
  ('NUR:WRITE',         'NUR', 'WRITE',          'إنشاء وإسناد المهام التمريضية'),
  ('NUR:MED',           'NUR', 'MED',            'تسجيل إعطاء الدواء للمريض'),
  ('LAB:WRITE',         'LAB', 'WRITE',          'إدارة كتالوج الفحوصات المخبرية'),
  ('LAB:RESULT',        'LAB', 'RESULT',         'إدخال وتعديل نتائج المختبر'),
  ('PHR:WRITE',         'PHR', 'WRITE',          'إدارة كتالوج الأدوية'),
  ('PHR:RECEIVE',       'PHR', 'RECEIVE',        'استلام دفعات الأدوية للمخزون'),
  ('NOT:READ',          'NOT', 'READ',           'عرض الإشعارات الشخصية');

-- ---------------------------------------------------------------------------
-- Role → permission mapping (self-sufficient even if S002 is not re-run)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'RECEPTION'
  AND p.code IN ('ER:READ', 'ER:CREATE', 'ADT:READ', 'ADT:REQUEST',
                 'LAB:READ', 'PHR:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DOCTOR'
  AND p.code IN ('ER:READ', 'ER:WRITE', 'ER:QUEUE',
                 'ADT:READ', 'ADT:REQUEST', 'ADT:WRITE',
                 'NUR:READ', 'LAB:READ', 'LAB:ORDER', 'LAB:PRINT',
                 'PHR:READ', 'PHR:PRESCRIBE', 'MED:CATALOG_READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CONSULTANT'
  AND p.code IN ('ER:READ', 'ER:WRITE', 'ER:QUEUE',
                 'ADT:READ', 'ADT:REQUEST', 'ADT:WRITE', 'ADT:APPROVE',
                 'NUR:READ', 'LAB:READ', 'LAB:ORDER', 'LAB:PRINT',
                 'PHR:READ', 'PHR:PRESCRIBE', 'MED:CATALOG_READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'NURSE'
  AND p.code IN ('ER:READ', 'ER:TRIAGE', 'ER:QUEUE',
                 'ADT:READ', 'ADT:BED_STATUS', 'ADT:HOUSEKEEPING',
                 'NUR:READ', 'NUR:WRITE', 'NUR:NOTE_SIGN', 'NUR:MED',
                 'LAB:READ', 'LAB:COLLECT', 'MED:ADMINISTER', 'MED:CATALOG_READ',
                 'PHR:READ', 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'TRIAGE_NURSE'
  AND p.code IN ('ER:READ', 'ER:CREATE', 'ER:TRIAGE', 'ER:QUEUE', 'ER:WRITE',
                 'ADT:READ', 'ADT:BED_STATUS',
                 'NUR:READ', 'NUR:WRITE', 'NUR:NOTE_SIGN', 'NUR:MED',
                 'LAB:READ', 'LAB:COLLECT', 'MED:ADMINISTER', 'MED:CATALOG_READ',
                 'PHR:READ', 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'LAB_TECH'
  AND p.code IN ('LAB:READ', 'LAB:ORDER', 'LAB:COLLECT', 'LAB:RECEIVE',
                 'LAB:PROCESS', 'LAB:RESULT_ENTER', 'LAB:RESULT',
                 'LAB:PRINT', 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'LAB_SUPERVISOR'
  AND p.domain = 'LAB';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'LAB_SUPERVISOR'
  AND p.code = 'NOT:READ';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'PHARMACIST'
  AND p.code IN ('PHR:READ', 'PHR:REVIEW', 'PHR:DISPENSE', 'PHR:RETURN',
                 'PHR:BATCH_READ', 'PHR:RECEIVE', 'PHR:WRITE',
                 'MED:CATALOG_READ', 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'STOREKEEPER'
  AND p.code IN ('PHR:READ', 'PHR:BATCH_READ', 'PHR:RECEIVE', 'PHR:WRITE',
                 'PHR:RETURN', 'INV:READ', 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DEPT_MANAGER'
  AND p.code IN ('ADT:READ', 'ADT:APPROVE', 'ADT:ASSIGN', 'ADT:WRITE',
                 'ADT:BED_STATUS', 'ER:READ', 'NUR:READ', 'LAB:READ',
                 'NOT:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code IN ('SYS_ADMIN', 'HOSP_ADMIN')
  AND p.domain IN ('ER', 'ADT', 'NUR', 'LAB', 'PHR', 'NOT');

-- Every role owns an inbox; NOT:READ only exposes the caller's own rows.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE p.code = 'NOT:READ';

-- ---------------------------------------------------------------------------
-- Departments used by wards, ER and the lab
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A3', b.id, 'INP-MED', 'القسم الداخلي', 'Inpatient Medicine', 'CLINICAL', 1
FROM branches b WHERE b.code = 'MAIN';

INSERT IGNORE INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A4', b.id, 'OBGYN', 'النساء والتوليد', 'Obstetrics & Gynecology', 'CLINICAL', 1
FROM branches b WHERE b.code = 'MAIN';

INSERT IGNORE INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A5', b.id, 'ER', 'الطوارئ', 'Emergency', 'ER', 1
FROM branches b WHERE b.code = 'MAIN';

INSERT IGNORE INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A6', b.id, 'LAB', 'المختبر', 'Laboratory', 'LAB', 1
FROM branches b WHERE b.code = 'MAIN';

INSERT IGNORE INTO departments (public_id, branch_id, code, name_ar, name_en, type, active)
SELECT '000000000000000000000000A7', b.id, 'PHARM', 'الصيدلية', 'Pharmacy', 'PHARMACY', 1
FROM branches b WHERE b.code = 'MAIN';

-- ---------------------------------------------------------------------------
-- ER triage levels (FR-ER-002)
-- Levels are configuration only; no clinical protocol is embedded.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO triage_levels
    (public_id, code, name_ar, name_en, rank_no, color, max_wait_minutes, active) VALUES
  ('000000000000000000000000T1', 'RED',    'حرج',        'Critical',   1, 'RED',    0,   1),
  ('000000000000000000000000T2', 'ORANGE', 'عاجل جداً',  'Emergent',   2, 'ORANGE', 10,  1),
  ('000000000000000000000000T3', 'YELLOW', 'عاجل',       'Urgent',     3, 'YELLOW', 30,  1),
  ('000000000000000000000000T4', 'GREEN',  'غير عاجل',   'Less urgent', 4, 'GREEN',  60,  1),
  ('000000000000000000000000T5', 'BLUE',   'روتيني',     'Routine',    5, 'BLUE',   120, 1);

-- ---------------------------------------------------------------------------
-- Wards and beds (FR-ADT-003, TC-059..TC-062)
-- 10 beds across two wards: 6 mixed medicine, 4 female maternity.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO wards
    (public_id, branch_id, department_id, code, name_ar, name_en, floor, gender_policy, active)
SELECT '000000000000000000000000W1', b.id, d.id,
       'WARD-MED-A', 'الجناح الداخلي أ', 'Inpatient Ward A', '1', 'MIXED', 1
FROM branches b
JOIN departments d ON d.branch_id = b.id AND d.code = 'INP-MED'
WHERE b.code = 'MAIN';

INSERT IGNORE INTO wards
    (public_id, branch_id, department_id, code, name_ar, name_en, floor, gender_policy, active)
SELECT '000000000000000000000000W2', b.id, d.id,
       'WARD-MAT-B', 'جناح الأمومة ب', 'Maternity Ward B', '2', 'FEMALE', 1
FROM branches b
JOIN departments d ON d.branch_id = b.id AND d.code = 'OBGYN'
WHERE b.code = 'MAIN';

INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B01', w.id, 'A-101', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B02', w.id, 'A-102', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B03', w.id, 'A-103', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B04', w.id, 'A-104', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B05', w.id, 'A-105', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B06', w.id, 'A-106', 'ISOLATION', 'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MED-A';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B07', w.id, 'B-201', 'MATERNITY', 'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MAT-B';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B08', w.id, 'B-202', 'MATERNITY', 'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MAT-B';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B09', w.id, 'B-203', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MAT-B';
INSERT IGNORE INTO beds (public_id, ward_id, code, bed_type, status, active)
SELECT '00000000000000000000000B10', w.id, 'B-204', 'STANDARD',  'AVAILABLE', 1 FROM wards w WHERE w.code = 'WARD-MAT-B';

-- ---------------------------------------------------------------------------
-- Laboratory test catalog (FR-LAB-001)
-- Reference/critical ranges are illustrative defaults, not clinical guidance.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L01', 'CBC', 'تعداد دم كامل', 'Complete blood count',
       'BLOOD', '10^9/L', 'NUMERIC', 4.0000, 11.0000, NULL, 2.0000, 20.0000, 60, s.id, 1
FROM service_catalog s WHERE s.code = 'LAB-CBC';

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L02', 'CREAT', 'كرياتينين الدم', 'Serum creatinine',
       'BLOOD', 'mg/dL', 'NUMERIC', 0.6000, 1.3000, NULL, 0.2000, 7.0000, 90, s.id, 1
FROM service_catalog s WHERE s.code = 'LAB-CREAT';

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L03', 'HGB', 'هيموغلوبين', 'Hemoglobin',
       'BLOOD', 'g/dL', 'NUMERIC', 12.0000, 17.0000, NULL, 7.0000, 20.0000, 60, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L04', 'GLU', 'سكر الدم صائم', 'Fasting glucose',
       'BLOOD', 'mg/dL', 'NUMERIC', 70.0000, 110.0000, NULL, 40.0000, 500.0000, 30, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L05', 'K', 'بوتاسيوم الدم', 'Serum potassium',
       'BLOOD', 'mmol/L', 'NUMERIC', 3.5000, 5.1000, NULL, 2.5000, 6.5000, 60, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L06', 'NA', 'صوديوم الدم', 'Serum sodium',
       'BLOOD', 'mmol/L', 'NUMERIC', 135.0000, 145.0000, NULL, 120.0000, 160.0000, 60, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L07', 'ALT', 'إنزيم الكبد ALT', 'Alanine aminotransferase',
       'BLOOD', 'U/L', 'NUMERIC', 7.0000, 56.0000, NULL, NULL, NULL, 120, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L08', 'TSH', 'هرمون الغدة الدرقية', 'Thyroid stimulating hormone',
       'BLOOD', 'mIU/L', 'NUMERIC', 0.4000, 4.0000, NULL, NULL, NULL, 180, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L09', 'CRP', 'بروتين سي التفاعلي', 'C-reactive protein',
       'BLOOD', 'mg/L', 'NUMERIC', 0.0000, 5.0000, NULL, NULL, NULL, 45, NULL, 1
FROM DUAL;

INSERT IGNORE INTO lab_tests
    (public_id, code, name_ar, name_en, specimen_type, unit, result_type,
     ref_low, ref_high, ref_text, critical_low, critical_high,
     target_tat_minutes, service_id, active)
SELECT '00000000000000000000000L10', 'UA', 'تحليل بول عام', 'Urinalysis',
       'URINE', NULL, 'TEXT', NULL, NULL, 'Negative', NULL, NULL, 60, NULL, 1
FROM DUAL;

-- ---------------------------------------------------------------------------
-- Drug prices + opening batches (FEFO: the earliest expiry is dispensed first)
-- ---------------------------------------------------------------------------

UPDATE drugs SET unit_price = 0.50 WHERE code = 'AMOX-500' AND unit_price = 0;
UPDATE drugs SET unit_price = 0.10 WHERE code = 'PARAC-500' AND unit_price = 0;
UPDATE drugs SET unit_price = 0.30 WHERE code = 'OMEP-20'  AND unit_price = 0;
UPDATE drugs SET unit_price = 0.12 WHERE code = 'METF-500' AND unit_price = 0;
UPDATE drugs SET unit_price = 3.50 WHERE code = 'SALB-INH' AND unit_price = 0;

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N01', d.id, 'AMX-2602', 'LOT-AMX-2602', '2027-02-28',
       120, 120, 0.34, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'AMOX-500';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N02', d.id, 'AMX-2601', 'LOT-AMX-2601', '2027-06-30',
       240, 240, 0.35, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'AMOX-500';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N03', d.id, 'PCM-2512', 'LOT-PCM-2512', '2026-12-31',
       60, 60, 0.05, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'PARAC-500';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N04', d.id, 'PCM-2601', 'LOT-PCM-2601', '2027-09-30',
       500, 500, 0.05, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'PARAC-500';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N05', d.id, 'OMP-2601', 'LOT-OMP-2601', '2027-04-30',
       180, 180, 0.20, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'OMEP-20';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N06', d.id, 'MTF-2601', 'LOT-MTF-2601', '2027-08-31',
       300, 300, 0.06, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'METF-500';

INSERT IGNORE INTO drug_batches
    (public_id, drug_id, batch_number, lot_number, expiry_date,
     quantity_received, quantity_available, unit_cost, supplier, status, received_by)
SELECT '00000000000000000000000N07', d.id, 'SLB-2601', 'LOT-SLB-2601', '2027-01-31',
       40, 40, 2.10, 'Default supplier', 'ACTIVE', u.id
FROM drugs d JOIN users u ON u.username = 'admin'
WHERE d.code = 'SALB-INH';

-- Opening stock movements keep the ledger equal to quantity_available.
INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P01', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N01';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P02', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N02';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P03', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N03';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P04', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N04';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P05', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N05';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P06', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N06';

INSERT IGNORE INTO stock_movements
    (public_id, drug_id, batch_id, movement_type, quantity_delta,
     reference_type, reference_public_id, reason, performed_by)
SELECT '00000000000000000000000P07', b.drug_id, b.id, 'RECEIPT', b.quantity_received,
       'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM drug_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '00000000000000000000000N07';

-- ---------------------------------------------------------------------------
-- Known interaction pair used by the pharmacy review screen (illustrative only)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO drug_interactions
    (public_id, drug_id, interacting_drug_id, severity, description_ar, description_en, active)
SELECT '00000000000000000000000X01', a.id, b.id, 'MODERATE',
       'قد يزيد ميتفورمين من خطر انخفاض السكر عند استخدامه مع الأموكسيسيلين.',
       'Metformin may increase the risk of hypoglycemia when used with amoxicillin.', 1
FROM drugs a JOIN drugs b ON b.code = 'AMOX-500'
WHERE a.code = 'METF-500';

-- ---------------------------------------------------------------------------
-- Phase 4 settings (INSERT IGNORE keeps owner changes on re-seed)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO settings (setting_key, setting_value, value_type, category, is_sensitive) VALUES
  ('er.lwbs.window_minutes',           '30',  'INT',  'ER', 0),
  ('er.wait.warning_minutes',          '60',  'INT',  'ER', 0),
  ('er.board.refresh_seconds',         '15',  'INT',  'ER', 0),

  ('adt.discharge.require_billing_clear', '0', 'BOOL', 'ADT', 0),
  ('adt.bed.cleaning_minutes',         '30',  'INT',  'ADT', 0),
  ('adt.gender.exception.permission',  'ADT:ASSIGN', 'STRING', 'ADT', 0),

  ('nur.ews.thresholds',               '{"respiratory_rate":{"low":8,"high":25},"spo2":{"low":92},"systolic":{"low":90,"high":180},"heart_rate":{"low":50,"high":120},"temperature_c":{"low":35.0,"high":38.5},"risk_medium":3,"risk_high":5}',
                                             'JSON', 'NURSING', 0),
  ('nur.med.barcode.required',         '1',   'BOOL', 'NURSING', 0),

  ('lab.specimen.recollect.window_hours', '24', 'INT', 'LAB', 0),
  ('lab.critical.repeat_minutes',      '15',  'INT',  'LAB', 0),
  ('lab.result.auto_flag',             '1',   'BOOL', 'LAB', 0),

  ('phr.fefo.enabled',                 '1',   'BOOL', 'PHARMACY', 0),
  ('phr.stock.expiry.warning_days',    '90',  'INT',  'PHARMACY', 0),
  ('phr.dispense.partial.allowed',     '1',   'BOOL', 'PHARMACY', 0),
  ('phr.return.window_hours',          '72',  'INT',  'PHARMACY', 0),

  ('outbox.poll.interval_seconds',     '5',   'INT',  'PLATFORM', 0),
  ('outbox.max.attempts',              '8',   'INT',  'PLATFORM', 0),
  ('notifications.retention_days',     '90',  'INT',  'PLATFORM', 0);
