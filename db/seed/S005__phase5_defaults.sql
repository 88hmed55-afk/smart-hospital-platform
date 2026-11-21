-- Smart Hospital - S005
-- Phase 5 reference data: billing/inventory permissions and role mapping,
-- module settings, warehouses, suppliers, general inventory items, reorder
-- points and opening batches/movements.
-- Idempotent: safe to run repeatedly and never overwrites owner values.
--
-- Note: opening batches/movements are seeded only when at least one
-- application user already exists (received_by/performed_by are
-- accountability columns and cannot be NULL). On a brand-new database, run the
-- seed again after the first administrator account is created.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Phase 5 permissions missing from S001/S002
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO permissions (code, domain, action, description_ar) VALUES
  ('BIL:REFUND',       'BIL', 'REFUND',       'إنشاء استرداد مالي لدفعة'),
  ('BIL:CASH_SESSION', 'BIL', 'CASH_SESSION', 'فتح وإغلاق وردية الصندوق'),
  ('INV:WRITE',        'INV', 'WRITE',        'إنشاء وتعديل أصناف المخزون والمستودعات'),
  ('INV:WRITE_OFF',    'INV', 'WRITE_OFF',    'شطب أصناف تالفة أو منتهية الصلاحية'),
  ('INV:REQUEST',      'INV', 'REQUEST',      'إنشاء طلب شراء'),
  ('INV:APPROVE',      'INV', 'APPROVE',      'اعتماد طلب شراء');

-- ---------------------------------------------------------------------------
-- Role mapping (explicit, because the new permissions are created after the
-- S002 domain-based mappings have already run)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'SYS_ADMIN'
  AND p.domain IN ('BIL', 'INV');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'HOSP_ADMIN'
  AND p.domain IN ('BIL', 'INV');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CASHIER'
  AND p.code IN ('BIL:CASH_SESSION', 'BIL:READ', 'BIL:CREATE', 'BIL:PAYMENT',
                 'BIL:PRINT', 'BIL:DISCOUNT_LIMITED');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'FIN_MANAGER'
  AND (p.domain = 'BIL' OR p.code IN ('INV:READ', 'INV:APPROVE'));

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'STOREKEEPER'
  AND p.code IN ('INV:WRITE', 'INV:WRITE_OFF', 'INV:REQUEST', 'INV:APPROVE',
                 'INV:READ', 'INV:RECEIVE', 'INV:ISSUE', 'INV:TRANSFER',
                 'INV:COUNT', 'INV:ADJUST', 'INV:PO_CREATE', 'INV:SUPPLIER');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'PHARMACIST'
  AND p.code IN ('INV:READ', 'INV:ISSUE', 'INV:RECEIVE', 'INV:TRANSFER',
                 'BIL:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DOCTOR'
  AND p.code IN ('BIL:READ', 'INV:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'CONSULTANT'
  AND p.code IN ('BIL:READ', 'INV:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'NURSE'
  AND p.code IN ('BIL:READ', 'INV:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'TRIAGE_NURSE'
  AND p.code IN ('BIL:READ', 'INV:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'RECEPTION'
  AND p.code IN ('BIL:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'AUDITOR'
  AND p.code IN ('BIL:READ', 'INV:READ');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
WHERE r.code = 'DEPT_MANAGER'
  AND p.code IN ('BIL:READ', 'INV:READ');

-- ---------------------------------------------------------------------------
-- Phase 5 settings (INSERT IGNORE keeps owner changes on re-seed)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO settings (setting_key, setting_value, value_type, category, is_sensitive) VALUES
  ('billing.currency',                  'JOD', 'STRING', 'BILLING', 0),
  ('billing.tax.percent',               '0',   'INT',    'BILLING', 0),
  ('billing.discount.max_percent',      '20',  'INT',    'BILLING', 0),
  ('billing.discount.require_reason',   '1',   'BOOL',   'BILLING', 0),
  ('billing.refund.approval_threshold', '0',   'INT',    'BILLING', 0),
  ('billing.invoice.due_days',          '7',   'INT',    'BILLING', 0),
  ('billing.session.require_open',      '0',   'BOOL',   'BILLING', 0),
  ('billing.payment.methods',           'CASH,CARD,TRANSFER,INSURANCE,OTHER', 'STRING', 'BILLING', 0),

  ('inventory.expiry.block',            '1',   'BOOL',   'INVENTORY', 0),
  ('inventory.expiry.warning_days',     '60',  'INT',    'INVENTORY', 0),
  ('inventory.transfer.document.required', '1', 'BOOL',  'INVENTORY', 0),
  ('inventory.po.require_request',      '1',   'BOOL',   'INVENTORY', 0),
  ('inventory.count.variance.require_reason', '1', 'BOOL', 'INVENTORY', 0),
  ('inventory.reorder.alert.enabled',   '1',   'BOOL',   'INVENTORY', 0),
  ('inventory.valuation.method',        'BATCH_COST', 'STRING', 'INVENTORY', 0),
  ('inventory.issue.fefo.enabled',      '1',   'BOOL',   'INVENTORY', 0);

-- ---------------------------------------------------------------------------
-- Warehouses
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO warehouses
    (public_id, code, name_ar, name_en, warehouse_type, location, active)
VALUES
  ('0000000000000000000000WH01', 'MAIN',   'المستودع الرئيسي', 'Main Warehouse',     'MAIN',     'المبنى الرئيسي - الطابق الأرضي', 1),
  ('0000000000000000000000WH02', 'PHARM',  'مستودع الصيدلية',  'Pharmacy Store',     'PHARMACY', 'مبنى الصيدلية',                  1),
  ('0000000000000000000000WH03', 'ER',     'مستودع الطوارئ',   'Emergency Store',    'ER',       'قسم الطوارئ',                    1),
  ('0000000000000000000000WH04', 'WARD-A', 'مستودع العنبر أ',  'Ward A Store',       'WARD',     'العنبر أ - الطابق الأول',        1);

-- ---------------------------------------------------------------------------
-- Suppliers
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO suppliers
    (public_id, code, name_ar, name_en, phone, email, address, contact_person, active)
VALUES
  ('0000000000000000000000SP01', 'SUP-01', 'شركة الإمداد الطبي', 'Medical Supply Co.',
   '+962790000001', 'supply@example.test', 'عمّان - الأردن', 'قسم المبيعات', 1),
  ('0000000000000000000000SP02', 'SUP-02', 'مؤسسة المستلزمات الصحية', 'Health Consumables Est.',
   '+962790000002', 'consumables@example.test', 'الزرقاء - الأردن', 'قسم الطلبات', 1);

-- ---------------------------------------------------------------------------
-- General inventory items (non-drug consumables and supplies)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO inventory_items
    (public_id, code, sku, name_ar, name_en, category, base_unit, track_expiry, unit_cost, active)
VALUES
  ('0000000000000000000000IT01', 'GLV-NST', 'SKU-GLV-NST', 'قفازات غير معقمة',      'Non-sterile Gloves',   'SUPPLY',     'BOX',    0, 3.50, 1),
  ('0000000000000000000000IT02', 'SYR-5ML', 'SKU-SYR-5ML', 'محقنة 5 مل',            'Syringe 5 ml',         'CONSUMABLE', 'PIECE',  1, 0.08, 1),
  ('0000000000000000000000IT03', 'GAU-STR', 'SKU-GAU-STR', 'شاش معقم 10×10',        'Sterile Gauze 10x10',  'CONSUMABLE', 'PACK',   1, 0.25, 1),
  ('0000000000000000000000IT04', 'IVS-SET', 'SKU-IVS-SET', 'طقم إعطاء وريدي',       'IV Administration Set','CONSUMABLE', 'PIECE',  1, 1.10, 1),
  ('0000000000000000000000IT05', 'ALC-70',  'SKU-ALC-70',  'كحول إيثيلي 70%',       'Ethyl Alcohol 70%',    'CONSUMABLE', 'BOTTLE', 1, 1.75, 1),
  ('0000000000000000000000IT06', 'MSK-N95', 'SKU-MSK-N95', 'كمامة N95',             'N95 Respirator Mask',  'SUPPLY',     'PIECE',  0, 0.90, 1);

-- ---------------------------------------------------------------------------
-- Reorder points (MAIN warehouse + pharmacy store)
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP01', i.id, w.id, 20, 100, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'GLV-NST';

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP02', i.id, w.id, 100, 500, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'SYR-5ML';

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP03', i.id, w.id, 50, 200, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'GAU-STR';

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP04', i.id, w.id, 30, 100, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'IVS-SET';

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP05', i.id, w.id, 10, 50, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'ALC-70';

INSERT IGNORE INTO inventory_reorder_points
    (public_id, item_id, warehouse_id, reorder_level, reorder_quantity, active)
SELECT '0000000000000000000000RP06', i.id, w.id, 25, 100, 1
FROM inventory_items i JOIN warehouses w ON w.code = 'MAIN'
WHERE i.code = 'MSK-N95';

-- ---------------------------------------------------------------------------
-- Opening batches (MAIN warehouse). Seeded only when an application user
-- exists so received_by is a real accountability reference.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB01', i.id, w.id, s.id, 'GLV-2601', 'LOT-GLV-2601',
       NULL, 120, 120, 3.50, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'GLV-NST';

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB02', i.id, w.id, s.id, 'SYR-2601', 'LOT-SYR-2601',
       '2028-03-31', 800, 800, 0.08, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'SYR-5ML';

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB03', i.id, w.id, s.id, 'GAU-2601', 'LOT-GAU-2601',
       '2027-12-31', 300, 300, 0.25, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'GAU-STR';

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB04', i.id, w.id, s.id, 'IVS-2601', 'LOT-IVS-2601',
       '2028-06-30', 150, 150, 1.10, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'IVS-SET';

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB05', i.id, w.id, s.id, 'ALC-2601', 'LOT-ALC-2601',
       '2027-09-30', 60, 60, 1.75, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'ALC-70';

INSERT IGNORE INTO inventory_batches
    (public_id, item_id, warehouse_id, supplier_id, batch_number, lot_number,
     expiry_date, quantity_received, quantity_available, unit_cost, status, received_by)
SELECT '0000000000000000000000IB06', i.id, w.id, s.id, 'MSK-2601', 'LOT-MSK-2601',
       NULL, 200, 200, 0.90, 'ACTIVE', u.id
FROM inventory_items i
JOIN warehouses w ON w.code = 'MAIN'
JOIN suppliers s ON s.code = 'SUP-01'
JOIN users u ON u.username = 'admin'
WHERE i.code = 'MSK-N95';

-- Opening stock movements keep the ledger equal to quantity_available.
INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM01', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB01';

INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM02', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB02';

INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM03', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB03';

INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM04', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB04';

INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM05', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB05';

INSERT IGNORE INTO inventory_movements
    (public_id, item_id, batch_id, warehouse_id, movement_type, quantity_delta,
     unit_cost, reference_type, reference_public_id, reason, performed_by)
SELECT '0000000000000000000000IM06', b.item_id, b.id, b.warehouse_id, 'RECEIPT',
       b.quantity_received, b.unit_cost, 'BATCH', b.public_id, 'Opening stock (seed)', u.id
FROM inventory_batches b JOIN users u ON u.username = 'admin'
WHERE b.public_id = '0000000000000000000000IB06';
