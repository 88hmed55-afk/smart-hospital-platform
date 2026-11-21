-- Smart Hospital - V007
-- Phase 5: Billing (invoices, payments, refunds, credit notes, cash sessions)
-- and general Inventory (warehouses, items, batches, movements, transfers,
-- counts, suppliers, purchase requests/orders, goods receipts).
--
-- Design references:
--   FR-BIL-001..010 / BR-BIL-001..010 / ADR-016 (charge capture separation)
--   FR-INV-001..010 / BR-INV-001..010
--   ADR-008 (optimistic + selective pessimistic locking)
--   ADR-009 (audit chain; every write is audited by the service layer)
--
-- Conventions inherited from V001..V006:
--   * BIGINT UNSIGNED surrogate PK + CHAR(26) ULID public_id
--   * DECIMAL(14,2) money, DATETIME(6) timestamps, utf8mb4_0900_ai_ci
--   * append-only ledgers protected by BEFORE UPDATE/DELETE triggers
--   * CHECK constraints for every state machine

SET NAMES utf8mb4;

-- ===========================================================================
-- 1. BILLING
-- ===========================================================================

CREATE TABLE cash_sessions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    session_number  VARCHAR(40)     NOT NULL,
    cashier_id      BIGINT UNSIGNED NOT NULL,
    opening_float   DECIMAL(14,2)   NOT NULL DEFAULT 0,
    expected_total  DECIMAL(14,2)   NULL,
    counted_total   DECIMAL(14,2)   NULL,
    variance        DECIMAL(14,2)   NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'JOD',
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    active_flag     CHAR(1)         NULL DEFAULT 'Y',
    opened_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at       DATETIME(6)     NULL,
    closed_by       BIGINT UNSIGNED NULL,
    notes           VARCHAR(1000)   NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT UNSIGNED NULL,
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cash_sessions_public_id (public_id),
    UNIQUE KEY uq_cash_sessions_number (session_number),
    UNIQUE KEY uq_cash_sessions_active_cashier (cashier_id, active_flag),
    KEY ix_cash_sessions_status (status, opened_at),
    CONSTRAINT fk_cash_sessions_users
        FOREIGN KEY (cashier_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cash_sessions_closed_by
        FOREIGN KEY (closed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cash_sessions_status
        CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_cash_sessions_active
        CHECK (active_flag IS NULL OR active_flag = 'Y'),
    CONSTRAINT ck_cash_sessions_float CHECK (opening_float >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE invoices (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    invoice_number   VARCHAR(40)     NOT NULL,
    patient_id       BIGINT UNSIGNED NOT NULL,
    encounter_id     BIGINT UNSIGNED NULL,
    cash_session_id  BIGINT UNSIGNED NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'ISSUED',
    subtotal         DECIMAL(14,2)   NOT NULL DEFAULT 0,
    discount_total   DECIMAL(14,2)   NOT NULL DEFAULT 0,
    tax_total        DECIMAL(14,2)   NOT NULL DEFAULT 0,
    grand_total      DECIMAL(14,2)   NOT NULL DEFAULT 0,
    paid_total       DECIMAL(14,2)   NOT NULL DEFAULT 0,
    refunded_total   DECIMAL(14,2)   NOT NULL DEFAULT 0,
    credited_total   DECIMAL(14,2)   NOT NULL DEFAULT 0,
    balance          DECIMAL(14,2)   GENERATED ALWAYS AS
                     (grand_total - credited_total - paid_total + refunded_total) STORED,
    currency         CHAR(3)         NOT NULL DEFAULT 'JOD',
    discount_reason  VARCHAR(500)    NULL,
    discount_by      BIGINT UNSIGNED NULL,
    discount_at      DATETIME(6)     NULL,
    notes            VARCHAR(1000)   NULL,
    issued_by        BIGINT UNSIGNED NOT NULL,
    issued_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    due_date         DATE            NULL,
    closed_at        DATETIME(6)     NULL,
    voided_at        DATETIME(6)     NULL,
    voided_by        BIGINT UNSIGNED NULL,
    void_reason      VARCHAR(500)    NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by       BIGINT UNSIGNED NULL,
    updated_at       DATETIME(6)     NULL,
    updated_by       BIGINT UNSIGNED NULL,
    version          BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_invoices_public_id (public_id),
    UNIQUE KEY uq_invoices_number (invoice_number),
    KEY ix_invoices_patient (patient_id, issued_at),
    KEY ix_invoices_encounter (encounter_id),
    KEY ix_invoices_status (status, issued_at),
    KEY ix_invoices_session (cash_session_id),
    CONSTRAINT fk_invoices_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoices_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoices_cash_sessions
        FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoices_issued_by
        FOREIGN KEY (issued_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoices_discount_by
        FOREIGN KEY (discount_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoices_voided_by
        FOREIGN KEY (voided_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_invoices_status
        CHECK (status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'CREDITED', 'VOID')),
    CONSTRAINT ck_invoices_amounts
        CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0
               AND grand_total >= 0 AND paid_total >= 0
               AND refunded_total >= 0 AND credited_total >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE invoice_items (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id            CHAR(26)        NOT NULL,
    invoice_id           BIGINT UNSIGNED NOT NULL,
    service_execution_id BIGINT UNSIGNED NOT NULL,
    service_id           BIGINT UNSIGNED NOT NULL,
    description_ar       VARCHAR(300)    NOT NULL,
    description_en       VARCHAR(300)    NULL,
    quantity             INT             NOT NULL DEFAULT 1,
    unit_price           DECIMAL(14,2)   NOT NULL,
    line_total           DECIMAL(14,2)   NOT NULL,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_invoice_items_public_id (public_id),
    UNIQUE KEY uq_invoice_items_execution (service_execution_id),
    KEY ix_invoice_items_invoice (invoice_id),
    KEY ix_invoice_items_service (service_id),
    CONSTRAINT fk_invoice_items_invoices
        FOREIGN KEY (invoice_id) REFERENCES invoices (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoice_items_executions
        FOREIGN KEY (service_execution_id) REFERENCES service_executions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_invoice_items_services
        FOREIGN KEY (service_id) REFERENCES service_catalog (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_invoice_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_invoice_items_amounts
        CHECK (unit_price >= 0 AND line_total >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payments (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    receipt_number   VARCHAR(40)     NOT NULL,
    invoice_id       BIGINT UNSIGNED NOT NULL,
    patient_id       BIGINT UNSIGNED NOT NULL,
    cash_session_id  BIGINT UNSIGNED NULL,
    amount           DECIMAL(14,2)   NOT NULL,
    currency         CHAR(3)         NOT NULL DEFAULT 'JOD',
    method           VARCHAR(20)     NOT NULL DEFAULT 'CASH',
    reference        VARCHAR(120)    NULL,
    idempotency_key  VARCHAR(120)    NULL,
    status           VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    notes            VARCHAR(500)    NULL,
    received_by      BIGINT UNSIGNED NOT NULL,
    received_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_payments_public_id (public_id),
    UNIQUE KEY uq_payments_receipt (receipt_number),
    UNIQUE KEY uq_payments_idempotency (idempotency_key),
    KEY ix_payments_invoice (invoice_id, received_at),
    KEY ix_payments_session (cash_session_id, received_at),
    KEY ix_payments_patient (patient_id, received_at),
    CONSTRAINT fk_payments_invoices
        FOREIGN KEY (invoice_id) REFERENCES invoices (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_payments_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_payments_cash_sessions
        FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_payments_received_by
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_method
        CHECK (method IN ('CASH', 'CARD', 'TRANSFER', 'INSURANCE', 'OTHER')),
    CONSTRAINT ck_payments_status
        CHECK (status IN ('POSTED', 'VOIDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payment_allocations (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    payment_id       BIGINT UNSIGNED NOT NULL,
    invoice_id       BIGINT UNSIGNED NOT NULL,
    amount           DECIMAL(14,2)   NOT NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_allocations_public_id (public_id),
    KEY ix_payment_allocations_payment (payment_id),
    KEY ix_payment_allocations_invoice (invoice_id),
    CONSTRAINT fk_payment_allocations_payments
        FOREIGN KEY (payment_id) REFERENCES payments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_payment_allocations_invoices
        FOREIGN KEY (invoice_id) REFERENCES invoices (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_payment_allocations_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_payment_allocations_no_update
BEFORE UPDATE ON payment_allocations FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_allocations is append-only';

CREATE TRIGGER trg_payment_allocations_no_delete
BEFORE DELETE ON payment_allocations FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment_allocations is append-only';

CREATE TABLE refunds (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    refund_number   VARCHAR(40)     NOT NULL,
    payment_id      BIGINT UNSIGNED NOT NULL,
    invoice_id      BIGINT UNSIGNED NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    cash_session_id BIGINT UNSIGNED NULL,
    amount          DECIMAL(14,2)   NOT NULL,
    currency        CHAR(3)         NOT NULL DEFAULT 'JOD',
    method          VARCHAR(20)     NOT NULL DEFAULT 'CASH',
    reason          VARCHAR(500)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    approved_by     BIGINT UNSIGNED NOT NULL,
    approved_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    performed_by    BIGINT UNSIGNED NOT NULL,
    performed_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_refunds_public_id (public_id),
    UNIQUE KEY uq_refunds_number (refund_number),
    KEY ix_refunds_payment (payment_id),
    KEY ix_refunds_invoice (invoice_id, performed_at),
    KEY ix_refunds_session (cash_session_id, performed_at),
    CONSTRAINT fk_refunds_payments
        FOREIGN KEY (payment_id) REFERENCES payments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_invoices
        FOREIGN KEY (invoice_id) REFERENCES invoices (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_cash_sessions
        FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refunds_performed_by
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('POSTED', 'VOIDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE credit_notes (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    credit_number  VARCHAR(40)     NOT NULL,
    invoice_id     BIGINT UNSIGNED NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    amount         DECIMAL(14,2)   NOT NULL,
    currency       CHAR(3)         NOT NULL DEFAULT 'JOD',
    reason         VARCHAR(500)    NOT NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'ISSUED',
    issued_by      BIGINT UNSIGNED NOT NULL,
    issued_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_credit_notes_public_id (public_id),
    UNIQUE KEY uq_credit_notes_number (credit_number),
    KEY ix_credit_notes_invoice (invoice_id, issued_at),
    CONSTRAINT fk_credit_notes_invoices
        FOREIGN KEY (invoice_id) REFERENCES invoices (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_credit_notes_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_credit_notes_issued_by
        FOREIGN KEY (issued_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_credit_notes_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_notes_status CHECK (status IN ('ISSUED', 'VOIDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE cash_movements (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    cash_session_id     BIGINT UNSIGNED NOT NULL,
    movement_type       VARCHAR(20)     NOT NULL,
    amount              DECIMAL(14,2)   NOT NULL,
    reference_type      VARCHAR(30)     NULL,
    reference_public_id CHAR(26)        NULL,
    reason              VARCHAR(500)    NULL,
    performed_by        BIGINT UNSIGNED NOT NULL,
    performed_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cash_movements_public_id (public_id),
    KEY ix_cash_movements_session (cash_session_id, performed_at),
    KEY ix_cash_movements_reference (reference_type, reference_public_id),
    CONSTRAINT fk_cash_movements_sessions
        FOREIGN KEY (cash_session_id) REFERENCES cash_sessions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cash_movements_users
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cash_movements_type
        CHECK (movement_type IN ('OPENING', 'PAYMENT', 'REFUND', 'ADJUSTMENT', 'CLOSING')),
    CONSTRAINT ck_cash_movements_amount CHECK (amount <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_cash_movements_no_update
BEFORE UPDATE ON cash_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash_movements is append-only';

CREATE TRIGGER trg_cash_movements_no_delete
BEFORE DELETE ON cash_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cash_movements is append-only';

-- ===========================================================================
-- 2. INVENTORY
-- ===========================================================================

CREATE TABLE warehouses (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id     CHAR(26)        NOT NULL,
    code          VARCHAR(30)     NOT NULL,
    name_ar       VARCHAR(150)    NOT NULL,
    name_en       VARCHAR(150)    NULL,
    warehouse_type VARCHAR(20)    NOT NULL DEFAULT 'MAIN',
    department_id BIGINT UNSIGNED NULL,
    location      VARCHAR(200)    NULL,
    active        TINYINT(1)      NOT NULL DEFAULT 1,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT UNSIGNED NULL,
    updated_at    DATETIME(6)     NULL,
    updated_by    BIGINT UNSIGNED NULL,
    version       BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_warehouses_public_id (public_id),
    UNIQUE KEY uq_warehouses_code (code),
    KEY ix_warehouses_active (active),
    CONSTRAINT fk_warehouses_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_warehouses_type
        CHECK (warehouse_type IN ('MAIN', 'PHARMACY', 'ER', 'WARD', 'LAB', 'OTHER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE suppliers (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id   CHAR(26)        NOT NULL,
    code        VARCHAR(30)     NOT NULL,
    name_ar     VARCHAR(200)    NOT NULL,
    name_en     VARCHAR(200)    NULL,
    phone       VARCHAR(40)     NULL,
    email       VARCHAR(150)    NULL,
    address     VARCHAR(500)    NULL,
    contact_person VARCHAR(150) NULL,
    active      TINYINT(1)      NOT NULL DEFAULT 1,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by  BIGINT UNSIGNED NULL,
    updated_at  DATETIME(6)     NULL,
    updated_by  BIGINT UNSIGNED NULL,
    version     BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_suppliers_public_id (public_id),
    UNIQUE KEY uq_suppliers_code (code),
    KEY ix_suppliers_active (active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inventory_items (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id    CHAR(26)        NOT NULL,
    code         VARCHAR(40)     NOT NULL,
    sku          VARCHAR(60)     NOT NULL,
    name_ar      VARCHAR(200)    NOT NULL,
    name_en      VARCHAR(200)    NULL,
    category     VARCHAR(40)     NOT NULL DEFAULT 'SUPPLY',
    base_unit    VARCHAR(20)     NOT NULL,
    track_expiry TINYINT(1)      NOT NULL DEFAULT 1,
    unit_cost    DECIMAL(14,2)   NOT NULL DEFAULT 0,
    barcode      VARCHAR(80)     NULL,
    active       TINYINT(1)      NOT NULL DEFAULT 1,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   BIGINT UNSIGNED NULL,
    updated_at   DATETIME(6)     NULL,
    updated_by   BIGINT UNSIGNED NULL,
    version      BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_inventory_items_public_id (public_id),
    UNIQUE KEY uq_inventory_items_code (code),
    UNIQUE KEY uq_inventory_items_sku (sku),
    UNIQUE KEY uq_inventory_items_barcode (barcode),
    KEY ix_inventory_items_category (category, active),
    CONSTRAINT ck_inventory_items_category
        CHECK (category IN ('SUPPLY', 'CONSUMABLE', 'INSTRUMENT', 'LINEN',
                            'STATIONERY', 'FOOD', 'OTHER')),
    CONSTRAINT ck_inventory_items_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inventory_reorder_points (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    item_id          BIGINT UNSIGNED NOT NULL,
    warehouse_id     BIGINT UNSIGNED NOT NULL,
    reorder_level    INT             NOT NULL DEFAULT 0,
    reorder_quantity INT             NOT NULL DEFAULT 0,
    active           TINYINT(1)      NOT NULL DEFAULT 1,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NULL,
    version          BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_reorder_points_public_id (public_id),
    UNIQUE KEY uq_reorder_points_item_warehouse (item_id, warehouse_id),
    KEY ix_reorder_points_warehouse (warehouse_id, active),
    CONSTRAINT fk_reorder_points_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reorder_points_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reorder_points_values
        CHECK (reorder_level >= 0 AND reorder_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inventory_batches (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    item_id            BIGINT UNSIGNED NOT NULL,
    warehouse_id       BIGINT UNSIGNED NOT NULL,
    supplier_id        BIGINT UNSIGNED NULL,
    batch_number       VARCHAR(60)     NOT NULL,
    lot_number         VARCHAR(60)     NULL,
    expiry_date        DATE            NULL,
    quantity_received  INT             NOT NULL DEFAULT 0,
    quantity_available INT             NOT NULL DEFAULT 0,
    unit_cost          DECIMAL(14,2)   NOT NULL DEFAULT 0,
    status             VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    received_by        BIGINT UNSIGNED NOT NULL,
    received_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NULL,
    updated_by         BIGINT UNSIGNED NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_inventory_batches_public_id (public_id),
    UNIQUE KEY uq_inventory_batches_number (item_id, warehouse_id, batch_number),
    KEY ix_inventory_batches_fefo (item_id, warehouse_id, status, expiry_date),
    KEY ix_inventory_batches_expiry (expiry_date, status),
    CONSTRAINT fk_inventory_batches_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_batches_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_batches_suppliers
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_batches_users
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_inventory_batches_quantities
        CHECK (quantity_received >= 0 AND quantity_available >= 0
               AND quantity_available <= quantity_received),
    CONSTRAINT ck_inventory_batches_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_inventory_batches_status
        CHECK (status IN ('ACTIVE', 'QUARANTINE', 'EXPIRED', 'DEPLETED', 'VOID'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inventory_movements (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    item_id             BIGINT UNSIGNED NOT NULL,
    batch_id            BIGINT UNSIGNED NOT NULL,
    warehouse_id        BIGINT UNSIGNED NOT NULL,
    movement_type       VARCHAR(20)     NOT NULL,
    quantity_delta      INT             NOT NULL,
    unit_cost           DECIMAL(14,2)   NULL,
    reference_type      VARCHAR(30)     NULL,
    reference_public_id CHAR(26)        NULL,
    reason              VARCHAR(500)    NULL,
    performed_by        BIGINT UNSIGNED NOT NULL,
    performed_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_inventory_movements_public_id (public_id),
    KEY ix_inventory_movements_item (item_id, performed_at),
    KEY ix_inventory_movements_batch (batch_id, performed_at),
    KEY ix_inventory_movements_warehouse (warehouse_id, performed_at),
    KEY ix_inventory_movements_reference (reference_type, reference_public_id),
    CONSTRAINT fk_inventory_movements_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_movements_batches
        FOREIGN KEY (batch_id) REFERENCES inventory_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_movements_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_movements_users
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_inventory_movements_type
        CHECK (movement_type IN ('RECEIPT', 'ISSUE', 'TRANSFER_IN', 'TRANSFER_OUT',
                                 'ADJUSTMENT', 'WRITE_OFF', 'RETURN')),
    CONSTRAINT ck_inventory_movements_quantity CHECK (quantity_delta <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_inventory_movements_no_update
BEFORE UPDATE ON inventory_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inventory_movements is append-only';

CREATE TRIGGER trg_inventory_movements_no_delete
BEFORE DELETE ON inventory_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inventory_movements is append-only';

CREATE TABLE stock_transfers (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    transfer_number   VARCHAR(40)     NOT NULL,
    from_warehouse_id BIGINT UNSIGNED NOT NULL,
    to_warehouse_id   BIGINT UNSIGNED NOT NULL,
    document_ref      VARCHAR(120)    NOT NULL,
    reason            VARCHAR(500)    NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    transferred_by    BIGINT UNSIGNED NOT NULL,
    transferred_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version           BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_transfers_public_id (public_id),
    UNIQUE KEY uq_stock_transfers_number (transfer_number),
    KEY ix_stock_transfers_from (from_warehouse_id, transferred_at),
    KEY ix_stock_transfers_to (to_warehouse_id, transferred_at),
    CONSTRAINT fk_stock_transfers_from
        FOREIGN KEY (from_warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_transfers_to
        FOREIGN KEY (to_warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_transfers_users
        FOREIGN KEY (transferred_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_stock_transfers_status CHECK (status IN ('POSTED', 'CANCELLED')),
    CONSTRAINT ck_stock_transfers_route CHECK (from_warehouse_id <> to_warehouse_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_transfer_items (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id   CHAR(26)        NOT NULL,
    transfer_id BIGINT UNSIGNED NOT NULL,
    item_id     BIGINT UNSIGNED NOT NULL,
    source_batch_id BIGINT UNSIGNED NOT NULL,
    target_batch_id BIGINT UNSIGNED NULL,
    quantity    INT             NOT NULL,
    unit_cost   DECIMAL(14,2)   NOT NULL DEFAULT 0,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_transfer_items_public_id (public_id),
    KEY ix_stock_transfer_items_transfer (transfer_id),
    KEY ix_stock_transfer_items_item (item_id),
    CONSTRAINT fk_stock_transfer_items_transfers
        FOREIGN KEY (transfer_id) REFERENCES stock_transfers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_transfer_items_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_transfer_items_source
        FOREIGN KEY (source_batch_id) REFERENCES inventory_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_transfer_items_target
        FOREIGN KEY (target_batch_id) REFERENCES inventory_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_stock_transfer_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_counts (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id     CHAR(26)        NOT NULL,
    count_number  VARCHAR(40)     NOT NULL,
    warehouse_id  BIGINT UNSIGNED NOT NULL,
    status        VARCHAR(20)     NOT NULL DEFAULT 'COUNTING',
    reason        VARCHAR(500)    NULL,
    counted_by    BIGINT UNSIGNED NOT NULL,
    counted_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    posted_by     BIGINT UNSIGNED NULL,
    posted_at     DATETIME(6)     NULL,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NULL,
    version       BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_counts_public_id (public_id),
    UNIQUE KEY uq_stock_counts_number (count_number),
    KEY ix_stock_counts_warehouse (warehouse_id, counted_at),
    KEY ix_stock_counts_status (status),
    CONSTRAINT fk_stock_counts_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_counts_users
        FOREIGN KEY (counted_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_counts_posted_by
        FOREIGN KEY (posted_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_stock_counts_status
        CHECK (status IN ('COUNTING', 'POSTED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE stock_count_lines (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    count_id         BIGINT UNSIGNED NOT NULL,
    item_id          BIGINT UNSIGNED NOT NULL,
    batch_id         BIGINT UNSIGNED NOT NULL,
    system_quantity  INT             NOT NULL,
    counted_quantity INT             NULL,
    variance         INT             GENERATED ALWAYS AS (counted_quantity - system_quantity) STORED,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_count_lines_public_id (public_id),
    UNIQUE KEY uq_stock_count_lines_batch (count_id, batch_id),
    KEY ix_stock_count_lines_count (count_id),
    KEY ix_stock_count_lines_item (item_id),
    CONSTRAINT fk_stock_count_lines_counts
        FOREIGN KEY (count_id) REFERENCES stock_counts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_count_lines_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_count_lines_batches
        FOREIGN KEY (batch_id) REFERENCES inventory_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_stock_count_lines_system CHECK (system_quantity >= 0),
    CONSTRAINT ck_stock_count_lines_counted CHECK (counted_quantity IS NULL OR counted_quantity >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE purchase_requests (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    request_number VARCHAR(40)     NOT NULL,
    warehouse_id   BIGINT UNSIGNED NOT NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    requested_by   BIGINT UNSIGNED NOT NULL,
    requested_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    approved_by    BIGINT UNSIGNED NULL,
    approved_at    DATETIME(6)     NULL,
    notes          VARCHAR(1000)   NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_requests_public_id (public_id),
    UNIQUE KEY uq_purchase_requests_number (request_number),
    KEY ix_purchase_requests_status (status, requested_at),
    KEY ix_purchase_requests_warehouse (warehouse_id),
    CONSTRAINT fk_purchase_requests_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_requests_users
        FOREIGN KEY (requested_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_requests_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_purchase_requests_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CONVERTED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE purchase_request_items (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id   CHAR(26)        NOT NULL,
    request_id  BIGINT UNSIGNED NOT NULL,
    item_id     BIGINT UNSIGNED NOT NULL,
    quantity    INT             NOT NULL,
    notes       VARCHAR(500)    NULL,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_request_items_public_id (public_id),
    UNIQUE KEY uq_purchase_request_items_line (request_id, item_id),
    KEY ix_purchase_request_items_item (item_id),
    CONSTRAINT fk_purchase_request_items_requests
        FOREIGN KEY (request_id) REFERENCES purchase_requests (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_request_items_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_purchase_request_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE purchase_orders (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    po_number           VARCHAR(40)     NOT NULL,
    supplier_id         BIGINT UNSIGNED NOT NULL,
    warehouse_id        BIGINT UNSIGNED NOT NULL,
    purchase_request_id BIGINT UNSIGNED NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ORDERED',
    ordered_by          BIGINT UNSIGNED NOT NULL,
    ordered_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expected_at         DATE            NULL,
    total_amount        DECIMAL(14,2)   NOT NULL DEFAULT 0,
    notes               VARCHAR(1000)   NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NULL,
    updated_by          BIGINT UNSIGNED NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_orders_public_id (public_id),
    UNIQUE KEY uq_purchase_orders_number (po_number),
    KEY ix_purchase_orders_supplier (supplier_id, ordered_at),
    KEY ix_purchase_orders_status (status, ordered_at),
    KEY ix_purchase_orders_warehouse (warehouse_id),
    CONSTRAINT fk_purchase_orders_suppliers
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_orders_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_orders_requests
        FOREIGN KEY (purchase_request_id) REFERENCES purchase_requests (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_orders_users
        FOREIGN KEY (ordered_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_purchase_orders_status
        CHECK (status IN ('ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_purchase_orders_total CHECK (total_amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE purchase_order_items (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    purchase_order_id BIGINT UNSIGNED NOT NULL,
    item_id           BIGINT UNSIGNED NOT NULL,
    quantity_ordered  INT             NOT NULL,
    quantity_received INT             NOT NULL DEFAULT 0,
    unit_cost         DECIMAL(14,2)   NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_order_items_public_id (public_id),
    UNIQUE KEY uq_purchase_order_items_line (purchase_order_id, item_id),
    KEY ix_purchase_order_items_item (item_id),
    CONSTRAINT fk_purchase_order_items_orders
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_order_items_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_purchase_order_items_quantities
        CHECK (quantity_ordered > 0 AND quantity_received >= 0
               AND quantity_received <= quantity_ordered),
    CONSTRAINT ck_purchase_order_items_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE goods_receipts (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    grn_number      VARCHAR(40)     NOT NULL,
    purchase_order_id BIGINT UNSIGNED NOT NULL,
    warehouse_id    BIGINT UNSIGNED NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'POSTED',
    notes           VARCHAR(1000)   NULL,
    received_by     BIGINT UNSIGNED NOT NULL,
    received_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_goods_receipts_public_id (public_id),
    UNIQUE KEY uq_goods_receipts_number (grn_number),
    KEY ix_goods_receipts_order (purchase_order_id, received_at),
    CONSTRAINT fk_goods_receipts_orders
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_goods_receipts_warehouses
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_goods_receipts_users
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_goods_receipts_status CHECK (status IN ('POSTED', 'VOID'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE goods_receipt_items (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    goods_receipt_id BIGINT UNSIGNED NOT NULL,
    purchase_order_item_id BIGINT UNSIGNED NOT NULL,
    item_id          BIGINT UNSIGNED NOT NULL,
    batch_id         BIGINT UNSIGNED NOT NULL,
    batch_number     VARCHAR(60)     NOT NULL,
    expiry_date      DATE            NULL,
    quantity         INT             NOT NULL,
    unit_cost        DECIMAL(14,2)   NOT NULL DEFAULT 0,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_goods_receipt_items_public_id (public_id),
    KEY ix_goods_receipt_items_receipt (goods_receipt_id),
    KEY ix_goods_receipt_items_po_item (purchase_order_item_id),
    CONSTRAINT fk_goods_receipt_items_receipts
        FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_goods_receipt_items_po_items
        FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_goods_receipt_items_items
        FOREIGN KEY (item_id) REFERENCES inventory_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_goods_receipt_items_batches
        FOREIGN KEY (batch_id) REFERENCES inventory_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_goods_receipt_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_goods_receipt_items_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
