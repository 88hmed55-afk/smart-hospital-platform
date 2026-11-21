-- Smart Hospital — V005
-- Phase 4: emergency (ER), admissions/beds (ADT), nursing (NUR),
-- laboratory (LAB), pharmacy/stock (PHR) and notifications.
-- MySQL 8.0.38 · InnoDB · utf8mb4
-- Forward-only. Never edit after this file has been applied.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- ER — triage levels and emergency visits (FR-ER-001..006)
--
-- Assumption: "minimum data" registration still requires a patient record.
-- Unknown/unidentified patients are registered through the normal patient
-- endpoint with a temporary name and merged later with the existing patient
-- merge feature. This keeps every clinical row attached to a real patient id
-- instead of introducing a parallel anonymous record type.
-- ---------------------------------------------------------------------------

CREATE TABLE triage_levels (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id   CHAR(26)        NOT NULL,
    code        VARCHAR(20)     NOT NULL,
    name_ar     VARCHAR(100)    NOT NULL,
    name_en     VARCHAR(100)    NOT NULL,
    rank_no     TINYINT         NOT NULL,
    color       VARCHAR(20)     NOT NULL DEFAULT 'GRAY',
    max_wait_minutes INT        NULL,
    active      TINYINT(1)      NOT NULL DEFAULT 1,
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_triage_levels_public_id (public_id),
    UNIQUE KEY uq_triage_levels_code (code),
    CONSTRAINT ck_triage_levels_rank CHECK (rank_no BETWEEN 1 AND 10),
    CONSTRAINT ck_triage_levels_active CHECK (active IN (0, 1)),
    CONSTRAINT ck_triage_levels_wait CHECK (max_wait_minutes IS NULL OR max_wait_minutes >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE emergency_visits (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id             CHAR(26)        NOT NULL,
    patient_id            BIGINT UNSIGNED NOT NULL,
    encounter_id          BIGINT UNSIGNED NULL,
    triage_level_id       BIGINT UNSIGNED NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'ARRIVED',
    arrival_mode          VARCHAR(20)     NOT NULL DEFAULT 'WALK_IN',
    chief_complaint       VARCHAR(500)    NULL,
    arrival_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    triage_at             DATETIME(6)     NULL,
    triaged_by            BIGINT UNSIGNED NULL,
    first_assessment_at   DATETIME(6)     NULL,
    assessed_by           BIGINT UNSIGNED NULL,
    disposition           VARCHAR(30)     NULL,
    disposition_at        DATETIME(6)     NULL,
    disposition_by        BIGINT UNSIGNED NULL,
    disposition_notes     VARCHAR(1000)   NULL,
    lwbs_at               DATETIME(6)     NULL,
    lwbs_reason           VARCHAR(500)    NULL,
    entered_in_error_reason VARCHAR(500)  NULL,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by            BIGINT UNSIGNED NULL,
    updated_at            DATETIME(6)     NULL,
    updated_by            BIGINT UNSIGNED NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_emergency_visits_public_id (public_id),
    KEY ix_emergency_visits_board (status, arrival_at),
    KEY ix_emergency_visits_patient (patient_id, arrival_at),
    KEY ix_emergency_visits_encounter (encounter_id),
    KEY ix_emergency_visits_triage (triage_level_id),
    CONSTRAINT fk_emergency_visits_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_emergency_visits_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_emergency_visits_triage
        FOREIGN KEY (triage_level_id) REFERENCES triage_levels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_emergency_visits_triaged_by
        FOREIGN KEY (triaged_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_emergency_visits_assessed_by
        FOREIGN KEY (assessed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_emergency_visits_disposed_by
        FOREIGN KEY (disposition_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_emergency_visits_status
        CHECK (status IN ('ARRIVED', 'TRIAGED', 'IN_TREATMENT', 'DISPOSED', 'LWBS', 'ENTERED_IN_ERROR')),
    CONSTRAINT ck_emergency_visits_arrival
        CHECK (arrival_mode IN ('WALK_IN', 'AMBULANCE', 'POLICE', 'TRANSFER', 'OTHER')),
    CONSTRAINT ck_emergency_visits_disposition
        CHECK (disposition IS NULL OR disposition IN
               ('DISCHARGE', 'ADMIT', 'TRANSFER_CLINIC', 'TRANSFER_HOSPITAL', 'LWBS', 'ENTERED_IN_ERROR'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- ADT — wards, beds, admissions, assignments, transfers, discharge (FR-ADT)
-- ---------------------------------------------------------------------------

CREATE TABLE wards (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    branch_id      BIGINT UNSIGNED NOT NULL,
    department_id  BIGINT UNSIGNED NOT NULL,
    code           VARCHAR(20)     NOT NULL,
    name_ar        VARCHAR(150)    NOT NULL,
    name_en        VARCHAR(150)    NULL,
    floor          VARCHAR(20)     NULL,
    gender_policy  VARCHAR(10)     NOT NULL DEFAULT 'MIXED',
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_wards_public_id (public_id),
    UNIQUE KEY uq_wards_branch_code (branch_id, code),
    KEY ix_wards_department (department_id),
    CONSTRAINT fk_wards_branches
        FOREIGN KEY (branch_id) REFERENCES branches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wards_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_wards_gender
        CHECK (gender_policy IN ('MIXED', 'MALE', 'FEMALE')),
    CONSTRAINT ck_wards_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE beds (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    ward_id        BIGINT UNSIGNED NOT NULL,
    code           VARCHAR(20)     NOT NULL,
    bed_type       VARCHAR(30)     NOT NULL DEFAULT 'STANDARD',
    status         VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE',
    notes          VARCHAR(255)    NULL,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_beds_public_id (public_id),
    UNIQUE KEY uq_beds_ward_code (ward_id, code),
    KEY ix_beds_ward_status (ward_id, status),
    CONSTRAINT fk_beds_wards
        FOREIGN KEY (ward_id) REFERENCES wards (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_beds_type
        CHECK (bed_type IN ('STANDARD', 'ISOLATION', 'ICU', 'PEDIATRIC', 'MATERNITY')),
    CONSTRAINT ck_beds_status
        CHECK (status IN ('AVAILABLE', 'RESERVED', 'OCCUPIED', 'CLEANING', 'MAINTENANCE')),
    CONSTRAINT ck_beds_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE admissions (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id             CHAR(26)        NOT NULL,
    patient_id            BIGINT UNSIGNED NOT NULL,
    encounter_id          BIGINT UNSIGNED NOT NULL,
    requested_by          BIGINT UNSIGNED NOT NULL,
    requested_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reason                VARCHAR(1000)   NOT NULL,
    admitting_provider_id BIGINT UNSIGNED NULL,
    department_id         BIGINT UNSIGNED NULL,
    ward_id               BIGINT UNSIGNED NULL,
    status                VARCHAR(30)     NOT NULL DEFAULT 'REQUESTED',
    approved_by           BIGINT UNSIGNED NULL,
    approved_at           DATETIME(6)     NULL,
    rejected_by           BIGINT UNSIGNED NULL,
    rejected_at           DATETIME(6)     NULL,
    rejection_reason      VARCHAR(500)    NULL,
    admitted_at           DATETIME(6)     NULL,
    discharged_at         DATETIME(6)     NULL,
    discharge_type        VARCHAR(30)     NULL,
    billing_cleared       TINYINT(1)      NOT NULL DEFAULT 1,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by            BIGINT UNSIGNED NULL,
    updated_at            DATETIME(6)     NULL,
    updated_by            BIGINT UNSIGNED NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_admissions_public_id (public_id),
    KEY ix_admissions_status (status, requested_at),
    KEY ix_admissions_patient (patient_id, requested_at),
    KEY ix_admissions_encounter (encounter_id),
    KEY ix_admissions_ward (ward_id, status),
    CONSTRAINT fk_admissions_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_requested_by
        FOREIGN KEY (requested_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_provider
        FOREIGN KEY (admitting_provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_department
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_ward
        FOREIGN KEY (ward_id) REFERENCES wards (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_admissions_rejected_by
        FOREIGN KEY (rejected_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_admissions_status
        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'ADMITTED',
                          'TRANSFERRED', 'DISCHARGE_PENDING', 'DISCHARGED')),
    CONSTRAINT ck_admissions_discharge_type
        CHECK (discharge_type IS NULL OR discharge_type IN
               ('HOME', 'TRANSFER', 'AGAINST_ADVICE', 'DECEASED', 'OTHER')),
    CONSTRAINT ck_admissions_billing CHECK (billing_cleared IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE bed_assignments (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    admission_id   BIGINT UNSIGNED NOT NULL,
    bed_id         BIGINT UNSIGNED NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    assigned_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    assigned_by    BIGINT UNSIGNED NOT NULL,
    ended_at       DATETIME(6)     NULL,
    ended_by       BIGINT UNSIGNED NULL,
    end_reason     VARCHAR(30)     NULL,
    active_flag    TINYINT         NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bed_assignments_public_id (public_id),
    UNIQUE KEY uq_bed_assignments_bed_active (bed_id, active_flag),
    UNIQUE KEY uq_bed_assignments_admission_active (admission_id, active_flag),
    UNIQUE KEY uq_bed_assignments_patient_active (patient_id, active_flag),
    KEY ix_bed_assignments_open (bed_id, ended_at),
    CONSTRAINT fk_bed_assignments_admissions
        FOREIGN KEY (admission_id) REFERENCES admissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_bed_assignments_beds
        FOREIGN KEY (bed_id) REFERENCES beds (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_bed_assignments_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_bed_assignments_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_bed_assignments_ended_by
        FOREIGN KEY (ended_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_bed_assignments_active
        CHECK (active_flag IS NULL OR active_flag IN (0, 1)),
    CONSTRAINT ck_bed_assignments_end
        CHECK (ended_at IS NULL OR end_reason IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_transfers (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    admission_id   BIGINT UNSIGNED NOT NULL,
    from_bed_id    BIGINT UNSIGNED NOT NULL,
    to_bed_id      BIGINT UNSIGNED NOT NULL,
    reason         VARCHAR(500)    NOT NULL,
    transferred_by BIGINT UNSIGNED NOT NULL,
    transferred_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_transfers_public_id (public_id),
    KEY ix_patient_transfers_admission (admission_id, transferred_at),
    CONSTRAINT fk_patient_transfers_admissions
        FOREIGN KEY (admission_id) REFERENCES admissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patient_transfers_from_bed
        FOREIGN KEY (from_bed_id) REFERENCES beds (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patient_transfers_to_bed
        FOREIGN KEY (to_bed_id) REFERENCES beds (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patient_transfers_by
        FOREIGN KEY (transferred_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_transfers_beds CHECK (from_bed_id <> to_bed_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE discharge_summaries (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    admission_id       BIGINT UNSIGNED NOT NULL,
    diagnoses_text     VARCHAR(2000)   NOT NULL,
    medications_text   VARCHAR(2000)   NULL,
    instructions_text  VARCHAR(2000)   NOT NULL,
    follow_up_text     VARCHAR(1000)   NULL,
    created_by         BIGINT UNSIGNED NOT NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_discharge_summaries_public_id (public_id),
    UNIQUE KEY uq_discharge_summaries_admission (admission_id),
    CONSTRAINT fk_discharge_summaries_admissions
        FOREIGN KEY (admission_id) REFERENCES admissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_discharge_summaries_users
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- NUR — tasks, medication administration and early warning scores (FR-NUR)
-- ---------------------------------------------------------------------------

CREATE TABLE nursing_tasks (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    encounter_id    BIGINT UNSIGNED NULL,
    admission_id    BIGINT UNSIGNED NULL,
    ward_id         BIGINT UNSIGNED NULL,
    task_type       VARCHAR(30)     NOT NULL DEFAULT 'OTHER',
    title           VARCHAR(200)    NOT NULL,
    description     VARCHAR(1000)   NULL,
    priority        VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    due_at          DATETIME(6)     NULL,
    assigned_to     BIGINT UNSIGNED NULL,
    assigned_by     BIGINT UNSIGNED NULL,
    assigned_at     DATETIME(6)     NULL,
    completed_by    BIGINT UNSIGNED NULL,
    completed_at    DATETIME(6)     NULL,
    completion_note VARCHAR(1000)   NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT UNSIGNED NULL,
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_nursing_tasks_public_id (public_id),
    KEY ix_nursing_tasks_ward_status (ward_id, status, due_at),
    KEY ix_nursing_tasks_assigned (assigned_to, status, due_at),
    KEY ix_nursing_tasks_patient (patient_id, created_at),
    CONSTRAINT fk_nursing_tasks_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_admissions
        FOREIGN KEY (admission_id) REFERENCES admissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_wards
        FOREIGN KEY (ward_id) REFERENCES wards (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_assigned_to
        FOREIGN KEY (assigned_to) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_assigned_by
        FOREIGN KEY (assigned_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_nursing_tasks_completed_by
        FOREIGN KEY (completed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_nursing_tasks_type
        CHECK (task_type IN ('MEDICATION', 'VITALS', 'NOTE', 'PROCEDURE', 'OBSERVATION', 'OTHER')),
    CONSTRAINT ck_nursing_tasks_priority
        CHECK (priority IN ('NORMAL', 'URGENT', 'STAT')),
    CONSTRAINT ck_nursing_tasks_status
        CHECK (status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drug_batches (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    drug_id             BIGINT UNSIGNED NOT NULL,
    batch_number        VARCHAR(60)     NOT NULL,
    lot_number          VARCHAR(60)     NULL,
    expiry_date         DATE            NOT NULL,
    quantity_received   INT             NOT NULL,
    quantity_available  INT             NOT NULL,
    unit_cost           DECIMAL(14,2)   NOT NULL DEFAULT 0,
    supplier            VARCHAR(150)    NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    received_by         BIGINT UNSIGNED NOT NULL,
    received_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NULL,
    updated_by          BIGINT UNSIGNED NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_drug_batches_public_id (public_id),
    UNIQUE KEY uq_drug_batches_number (drug_id, batch_number),
    KEY ix_drug_batches_fefo (drug_id, status, expiry_date),
    KEY ix_drug_batches_expiry (expiry_date, status),
    CONSTRAINT fk_drug_batches_drugs
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_drug_batches_received_by
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_drug_batches_status
        CHECK (status IN ('ACTIVE', 'QUARANTINED', 'EXPIRED', 'DEPLETED', 'WRITTEN_OFF')),
    CONSTRAINT ck_drug_batches_qty
        CHECK (quantity_received >= 0 AND quantity_available >= 0 AND quantity_available <= quantity_received),
    CONSTRAINT ck_drug_batches_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE medication_administrations (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    patient_id          BIGINT UNSIGNED NOT NULL,
    admission_id        BIGINT UNSIGNED NULL,
    prescription_item_id BIGINT UNSIGNED NOT NULL,
    drug_id             BIGINT UNSIGNED NOT NULL,
    batch_id            BIGINT UNSIGNED NOT NULL,
    dose                VARCHAR(50)     NOT NULL,
    route               VARCHAR(30)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'GIVEN',
    barcode_patient     VARCHAR(80)     NULL,
    barcode_drug        VARCHAR(80)     NULL,
    barcode_batch       VARCHAR(80)     NULL,
    verified_patient    TINYINT(1)      NOT NULL DEFAULT 0,
    verified_drug       TINYINT(1)      NOT NULL DEFAULT 0,
    verified_batch      TINYINT(1)      NOT NULL DEFAULT 0,
    administered_by     BIGINT UNSIGNED NOT NULL,
    administered_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    notes               VARCHAR(1000)   NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_medication_administrations_public_id (public_id),
    KEY ix_medication_admin_patient (patient_id, administered_at),
    KEY ix_medication_admin_admission (admission_id, administered_at),
    CONSTRAINT fk_medication_admin_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_medication_admin_admissions
        FOREIGN KEY (admission_id) REFERENCES admissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_medication_admin_item
        FOREIGN KEY (prescription_item_id) REFERENCES prescription_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_medication_admin_drug
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_medication_admin_batch
        FOREIGN KEY (batch_id) REFERENCES drug_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_medication_admin_by
        FOREIGN KEY (administered_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_medication_admin_status
        CHECK (status IN ('GIVEN', 'REFUSED', 'HELD', 'ERROR')),
    CONSTRAINT ck_medication_admin_verified
        CHECK (verified_patient IN (0, 1) AND verified_drug IN (0, 1) AND verified_batch IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE early_warning_scores (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    encounter_id    BIGINT UNSIGNED NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    score           SMALLINT        NOT NULL,
    risk_level      VARCHAR(20)     NOT NULL,
    parameters_json JSON            NOT NULL,
    calculated_by   BIGINT UNSIGNED NOT NULL,
    calculated_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_early_warning_scores_public_id (public_id),
    KEY ix_early_warning_encounter (encounter_id, calculated_at),
    KEY ix_early_warning_patient (patient_id, calculated_at),
    CONSTRAINT fk_early_warning_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_early_warning_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_early_warning_users
        FOREIGN KEY (calculated_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_early_warning_score CHECK (score >= 0),
    CONSTRAINT ck_early_warning_risk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- LAB — catalog, orders, specimens, results, versions, critical alerts
-- ---------------------------------------------------------------------------

CREATE TABLE lab_tests (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    code               VARCHAR(40)     NOT NULL,
    name_ar            VARCHAR(200)    NOT NULL,
    name_en            VARCHAR(200)    NULL,
    specimen_type      VARCHAR(20)     NOT NULL,
    unit               VARCHAR(40)     NULL,
    result_type        VARCHAR(20)     NOT NULL DEFAULT 'NUMERIC',
    ref_low            DECIMAL(14,4)   NULL,
    ref_high           DECIMAL(14,4)   NULL,
    ref_text           VARCHAR(255)    NULL,
    critical_low       DECIMAL(14,4)   NULL,
    critical_high      DECIMAL(14,4)   NULL,
    target_tat_minutes INT             NOT NULL DEFAULT 120,
    service_id         BIGINT UNSIGNED NULL,
    active             TINYINT(1)      NOT NULL DEFAULT 1,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by         BIGINT UNSIGNED NULL,
    updated_at         DATETIME(6)     NULL,
    updated_by         BIGINT UNSIGNED NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_tests_public_id (public_id),
    UNIQUE KEY uq_lab_tests_code (code),
    KEY ix_lab_tests_active (active, name_ar),
    KEY ix_lab_tests_service (service_id),
    CONSTRAINT fk_lab_tests_services
        FOREIGN KEY (service_id) REFERENCES service_catalog (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_tests_specimen
        CHECK (specimen_type IN ('BLOOD', 'URINE', 'STOOL', 'SPUTUM', 'SWAB', 'CSF', 'OTHER')),
    CONSTRAINT ck_lab_tests_result_type
        CHECK (result_type IN ('NUMERIC', 'TEXT')),
    CONSTRAINT ck_lab_tests_range
        CHECK (ref_low IS NULL OR ref_high IS NULL OR ref_low <= ref_high),
    CONSTRAINT ck_lab_tests_critical
        CHECK (critical_low IS NULL OR critical_high IS NULL OR critical_low <= critical_high),
    CONSTRAINT ck_lab_tests_tat CHECK (target_tat_minutes > 0),
    CONSTRAINT ck_lab_tests_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_orders (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    encounter_id    BIGINT UNSIGNED NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    ordered_by      BIGINT UNSIGNED NOT NULL,
    service_order_id BIGINT UNSIGNED NULL,
    priority        VARCHAR(20)     NOT NULL DEFAULT 'ROUTINE',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ORDERED',
    ordered_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    clinical_notes  VARCHAR(1000)   NULL,
    cancel_reason   VARCHAR(500)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_orders_public_id (public_id),
    UNIQUE KEY uq_lab_orders_service_order (service_order_id),
    KEY ix_lab_orders_status (status, priority, ordered_at),
    KEY ix_lab_orders_patient (patient_id, ordered_at),
    KEY ix_lab_orders_encounter (encounter_id, ordered_at),
    CONSTRAINT fk_lab_orders_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_orders_service_orders
        FOREIGN KEY (service_order_id) REFERENCES service_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_orders_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_orders_users
        FOREIGN KEY (ordered_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_orders_priority
        CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    CONSTRAINT ck_lab_orders_status
        CHECK (status IN ('ORDERED', 'COLLECTED', 'RECEIVED', 'IN_PROCESS', 'RESULTED',
                          'VERIFIED', 'RELEASED', 'CORRECTED', 'CANCELLED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_order_items (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    lab_order_id    BIGINT UNSIGNED NOT NULL,
    lab_test_id     BIGINT UNSIGNED NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ORDERED',
    result_id       BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_order_items_public_id (public_id),
    UNIQUE KEY uq_lab_order_items_test (lab_order_id, lab_test_id),
    KEY ix_lab_order_items_order (lab_order_id),
    KEY ix_lab_order_items_result (result_id),
    CONSTRAINT fk_lab_order_items_orders
        FOREIGN KEY (lab_order_id) REFERENCES lab_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_order_items_tests
        FOREIGN KEY (lab_test_id) REFERENCES lab_tests (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_order_items_status
        CHECK (status IN ('ORDERED', 'COLLECTED', 'RECEIVED', 'IN_PROCESS', 'RESULTED',
                          'VERIFIED', 'RELEASED', 'CANCELLED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_specimens (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    lab_order_id      BIGINT UNSIGNED NOT NULL,
    barcode           VARCHAR(80)     NOT NULL,
    specimen_type     VARCHAR(20)     NOT NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'COLLECTED',
    collected_by      BIGINT UNSIGNED NOT NULL,
    collected_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    received_by       BIGINT UNSIGNED NULL,
    received_at       DATETIME(6)     NULL,
    rejected_by       BIGINT UNSIGNED NULL,
    rejected_at       DATETIME(6)     NULL,
    rejection_reason  VARCHAR(500)    NULL,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version           BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_specimens_public_id (public_id),
    UNIQUE KEY uq_lab_specimens_barcode (barcode),
    KEY ix_lab_specimens_order (lab_order_id, status),
    CONSTRAINT fk_lab_specimens_orders
        FOREIGN KEY (lab_order_id) REFERENCES lab_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_specimens_collected_by
        FOREIGN KEY (collected_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_specimens_received_by
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_specimens_rejected_by
        FOREIGN KEY (rejected_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_specimens_type
        CHECK (specimen_type IN ('BLOOD', 'URINE', 'STOOL', 'SPUTUM', 'SWAB', 'CSF', 'OTHER')),
    CONSTRAINT ck_lab_specimens_status
        CHECK (status IN ('COLLECTED', 'RECEIVED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_sample_events (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    specimen_id    BIGINT UNSIGNED NOT NULL,
    from_status    VARCHAR(20)     NULL,
    to_status      VARCHAR(20)     NOT NULL,
    actor_id       BIGINT UNSIGNED NOT NULL,
    note           VARCHAR(500)    NULL,
    occurred_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_lab_sample_events (specimen_id, occurred_at),
    CONSTRAINT fk_lab_sample_events_specimen
        FOREIGN KEY (specimen_id) REFERENCES lab_specimens (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_sample_events_actor
        FOREIGN KEY (actor_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_results (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    lab_order_id       BIGINT UNSIGNED NOT NULL,
    lab_order_item_id  BIGINT UNSIGNED NOT NULL,
    lab_test_id        BIGINT UNSIGNED NOT NULL,
    patient_id         BIGINT UNSIGNED NOT NULL,
    status             VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    value_numeric      DECIMAL(14,4)   NULL,
    value_text         VARCHAR(500)    NULL,
    unit_snapshot      VARCHAR(40)     NULL,
    ref_low_snapshot   DECIMAL(14,4)   NULL,
    ref_high_snapshot  DECIMAL(14,4)   NULL,
    ref_text_snapshot  VARCHAR(255)    NULL,
    flag               VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    critical_flag      TINYINT(1)      NOT NULL DEFAULT 0,
    comment            VARCHAR(1000)   NULL,
    entered_by         BIGINT UNSIGNED NOT NULL,
    entered_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    verified_by        BIGINT UNSIGNED NULL,
    verified_at        DATETIME(6)     NULL,
    released_by        BIGINT UNSIGNED NULL,
    released_at        DATETIME(6)     NULL,
    current_version_id BIGINT UNSIGNED NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NULL,
    updated_by         BIGINT UNSIGNED NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_results_public_id (public_id),
    UNIQUE KEY uq_lab_results_item (lab_order_item_id),
    KEY ix_lab_results_order (lab_order_id, status),
    KEY ix_lab_results_patient (patient_id, entered_at),
    CONSTRAINT fk_lab_results_orders
        FOREIGN KEY (lab_order_id) REFERENCES lab_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_items
        FOREIGN KEY (lab_order_item_id) REFERENCES lab_order_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_tests
        FOREIGN KEY (lab_test_id) REFERENCES lab_tests (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_entered_by
        FOREIGN KEY (entered_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_verified_by
        FOREIGN KEY (verified_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_results_released_by
        FOREIGN KEY (released_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_results_status
        CHECK (status IN ('DRAFT', 'RESULTED', 'VERIFIED', 'RELEASED', 'CORRECTED', 'ENTERED_IN_ERROR')),
    CONSTRAINT ck_lab_results_flag
        CHECK (flag IN ('NORMAL', 'HIGH', 'LOW', 'CRITICAL_HIGH', 'CRITICAL_LOW', 'ABNORMAL')),
    CONSTRAINT ck_lab_results_critical CHECK (critical_flag IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_result_versions (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id        CHAR(26)        NOT NULL,
    result_id        BIGINT UNSIGNED NOT NULL,
    version_no       INT             NOT NULL,
    value_numeric    DECIMAL(14,4)   NULL,
    value_text       VARCHAR(500)    NULL,
    flag             VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    comment          VARCHAR(1000)   NULL,
    amended_from_id  BIGINT UNSIGNED NULL,
    amend_reason     VARCHAR(500)    NULL,
    created_by       BIGINT UNSIGNED NOT NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_result_versions_public_id (public_id),
    UNIQUE KEY uq_lab_result_versions_no (result_id, version_no),
    KEY ix_lab_result_versions_amend (amended_from_id),
    CONSTRAINT fk_lab_result_versions_results
        FOREIGN KEY (result_id) REFERENCES lab_results (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_result_versions_amend
        FOREIGN KEY (amended_from_id) REFERENCES lab_result_versions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lab_result_versions_users
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lab_result_versions_no CHECK (version_no > 0),
    CONSTRAINT ck_lab_result_versions_flag
        CHECK (flag IN ('NORMAL', 'HIGH', 'LOW', 'CRITICAL_HIGH', 'CRITICAL_LOW', 'ABNORMAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE critical_value_alerts (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    result_id         BIGINT UNSIGNED NOT NULL,
    patient_id        BIGINT UNSIGNED NOT NULL,
    notified_user_id  BIGINT UNSIGNED NULL,
    notified_at       DATETIME(6)     NULL,
    acknowledged_by   BIGINT UNSIGNED NULL,
    acknowledged_at   DATETIME(6)     NULL,
    notes             VARCHAR(1000)   NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    version           BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_critical_value_alerts_public_id (public_id),
    KEY ix_critical_value_alerts_result (result_id),
    KEY ix_critical_value_alerts_status (status, notified_at),
    CONSTRAINT fk_critical_value_alerts_results
        FOREIGN KEY (result_id) REFERENCES lab_results (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_critical_value_alerts_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_critical_value_alerts_notified
        FOREIGN KEY (notified_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_critical_value_alerts_ack
        FOREIGN KEY (acknowledged_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_critical_value_alerts_status
        CHECK (status IN ('OPEN', 'NOTIFIED', 'ACKNOWLEDGED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE lab_analyzer_messages (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    direction      VARCHAR(20)     NOT NULL DEFAULT 'INBOUND',
    payload_json   JSON            NULL,
    status         VARCHAR(20)     NOT NULL,
    error_message  VARCHAR(1000)   NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lab_analyzer_messages_public_id (public_id),
    KEY ix_lab_analyzer_messages_status (status, created_at),
    CONSTRAINT ck_lab_analyzer_messages_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_lab_analyzer_messages_status
        CHECK (status IN ('SIMULATED', 'FAILED', 'IGNORED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- PHR — stock movements, dispensing, returns, interactions
-- ---------------------------------------------------------------------------

CREATE TABLE stock_movements (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id            CHAR(26)        NOT NULL,
    drug_id              BIGINT UNSIGNED NOT NULL,
    batch_id             BIGINT UNSIGNED NOT NULL,
    movement_type        VARCHAR(30)     NOT NULL,
    quantity_delta       INT             NOT NULL,
    reference_type       VARCHAR(30)     NULL,
    reference_public_id  CHAR(26)        NULL,
    reason               VARCHAR(500)    NULL,
    performed_by         BIGINT UNSIGNED NOT NULL,
    performed_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_movements_public_id (public_id),
    KEY ix_stock_movements_drug (drug_id, performed_at),
    KEY ix_stock_movements_batch (batch_id, performed_at),
    KEY ix_stock_movements_reference (reference_type, reference_public_id),
    CONSTRAINT fk_stock_movements_drugs
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_movements_batches
        FOREIGN KEY (batch_id) REFERENCES drug_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_movements_users
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_stock_movements_type
        CHECK (movement_type IN ('RECEIPT', 'DISPENSE', 'RETURN', 'ADJUSTMENT',
                                 'EXPIRED', 'WASTAGE', 'TRANSFER_IN', 'TRANSFER_OUT')),
    CONSTRAINT ck_stock_movements_quantity CHECK (quantity_delta <> 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TRIGGER trg_stock_movements_no_update
BEFORE UPDATE ON stock_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only';

CREATE TRIGGER trg_stock_movements_no_delete
BEFORE DELETE ON stock_movements FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only';

CREATE TABLE dispensing_transactions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    prescription_id BIGINT UNSIGNED NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    dispensed_by    BIGINT UNSIGNED NOT NULL,
    dispensed_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status          VARCHAR(30)     NOT NULL DEFAULT 'COMMITTED',
    notes           VARCHAR(1000)   NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dispensing_transactions_public_id (public_id),
    KEY ix_dispensing_prescription (prescription_id, dispensed_at),
    KEY ix_dispensing_patient (patient_id, dispensed_at),
    CONSTRAINT fk_dispensing_prescriptions
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_users
        FOREIGN KEY (dispensed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_dispensing_status
        CHECK (status IN ('PENDING', 'COMMITTED', 'PARTIALLY_RETURNED', 'RETURNED', 'VOIDED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE dispensing_items (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id            CHAR(26)        NOT NULL,
    dispensing_id        BIGINT UNSIGNED NOT NULL,
    prescription_item_id BIGINT UNSIGNED NOT NULL,
    drug_id              BIGINT UNSIGNED NOT NULL,
    batch_id             BIGINT UNSIGNED NOT NULL,
    quantity             INT             NOT NULL,
    stock_movement_id    BIGINT UNSIGNED NOT NULL,
    returned_quantity    INT             NOT NULL DEFAULT 0,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dispensing_items_public_id (public_id),
    KEY ix_dispensing_items_dispensing (dispensing_id),
    KEY ix_dispensing_items_item (prescription_item_id),
    CONSTRAINT fk_dispensing_items_dispensing
        FOREIGN KEY (dispensing_id) REFERENCES dispensing_transactions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_items_prescription_item
        FOREIGN KEY (prescription_item_id) REFERENCES prescription_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_items_drugs
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_items_batches
        FOREIGN KEY (batch_id) REFERENCES drug_batches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_items_movement
        FOREIGN KEY (stock_movement_id) REFERENCES stock_movements (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_dispensing_items_qty
        CHECK (quantity > 0 AND returned_quantity >= 0 AND returned_quantity <= quantity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE dispensing_returns (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id            CHAR(26)        NOT NULL,
    dispensing_id        BIGINT UNSIGNED NOT NULL,
    dispensing_item_id   BIGINT UNSIGNED NOT NULL,
    quantity             INT             NOT NULL,
    reason               VARCHAR(500)    NOT NULL,
    returned_by          BIGINT UNSIGNED NOT NULL,
    returned_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    stock_movement_id    BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dispensing_returns_public_id (public_id),
    KEY ix_dispensing_returns_dispensing (dispensing_id, returned_at),
    CONSTRAINT fk_dispensing_returns_dispensing
        FOREIGN KEY (dispensing_id) REFERENCES dispensing_transactions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_returns_item
        FOREIGN KEY (dispensing_item_id) REFERENCES dispensing_items (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_returns_by
        FOREIGN KEY (returned_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dispensing_returns_movement
        FOREIGN KEY (stock_movement_id) REFERENCES stock_movements (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_dispensing_returns_qty CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drug_interactions (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id           CHAR(26)        NOT NULL,
    drug_id             BIGINT UNSIGNED NOT NULL,
    interacting_drug_id BIGINT UNSIGNED NOT NULL,
    severity            VARCHAR(20)     NOT NULL,
    description_ar      VARCHAR(500)    NOT NULL,
    description_en      VARCHAR(500)    NULL,
    active              TINYINT(1)      NOT NULL DEFAULT 1,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version             BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_drug_interactions_public_id (public_id),
    UNIQUE KEY uq_drug_interactions_pair (drug_id, interacting_drug_id),
    KEY ix_drug_interactions_drug (drug_id, active),
    CONSTRAINT fk_drug_interactions_drug
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_drug_interactions_other
        FOREIGN KEY (interacting_drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_drug_interactions_severity
        CHECK (severity IN ('MINOR', 'MODERATE', 'MAJOR', 'CONTRAINDICATED')),
    CONSTRAINT ck_drug_interactions_active CHECK (active IN (0, 1)),
    CONSTRAINT ck_drug_interactions_distinct CHECK (drug_id <> interacting_drug_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- NOT — in-app notifications written by the transactional outbox writer
-- ---------------------------------------------------------------------------

CREATE TABLE notifications (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    user_id            BIGINT UNSIGNED NOT NULL,
    type               VARCHAR(60)     NOT NULL,
    title_ar           VARCHAR(200)    NOT NULL,
    title_en           VARCHAR(200)    NULL,
    body_ar            VARCHAR(1000)   NULL,
    body_en            VARCHAR(1000)   NULL,
    entity_type        VARCHAR(40)     NULL,
    entity_public_id   CHAR(26)        NULL,
    severity           VARCHAR(20)     NOT NULL DEFAULT 'INFO',
    status             VARCHAR(20)     NOT NULL DEFAULT 'UNREAD',
    dedupe_key         VARCHAR(120)    NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at            DATETIME(6)     NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notifications_public_id (public_id),
    UNIQUE KEY uq_notifications_dedupe (user_id, dedupe_key),
    KEY ix_notifications_user_status (user_id, status, created_at),
    CONSTRAINT fk_notifications_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notifications_status
        CHECK (status IN ('UNREAD', 'READ'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Phase 4 extensions to Phase 3 tables
-- ---------------------------------------------------------------------------

ALTER TABLE drugs
    ADD COLUMN unit_price DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER reorder_level,
    ADD CONSTRAINT ck_drugs_unit_price CHECK (unit_price >= 0);

ALTER TABLE prescriptions
    DROP CHECK ck_prescriptions_status;

ALTER TABLE prescriptions
    ADD CONSTRAINT ck_prescriptions_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'REVIEWED', 'REJECTED',
                          'DISPENSED', 'PARTIAL', 'CANCELLED')),
    ADD COLUMN rejected_reason VARCHAR(500) NULL AFTER reviewed_at;

ALTER TABLE prescription_items
    ADD COLUMN dispensed_quantity INT NOT NULL DEFAULT 0 AFTER quantity,
    ADD CONSTRAINT ck_prescription_items_dispensed
        CHECK (dispensed_quantity >= 0 AND dispensed_quantity <= quantity);

-- Forward references resolved after both sides exist.
ALTER TABLE lab_order_items
    ADD CONSTRAINT fk_lab_order_items_results
        FOREIGN KEY (result_id) REFERENCES lab_results (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE lab_results
    ADD CONSTRAINT fk_lab_results_current_version
        FOREIGN KEY (current_version_id) REFERENCES lab_result_versions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
