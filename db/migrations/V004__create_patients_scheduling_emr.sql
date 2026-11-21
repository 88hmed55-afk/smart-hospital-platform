-- Smart Hospital — V004
-- Phase 3: patients (EMPI), scheduling, encounters / EMR, charge capture
-- MySQL 8.0.38 · InnoDB · utf8mb4
-- Forward-only. Never edit after this file has been applied.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Reference data used by Phase 3 (clinics, service catalog, drug catalog)
-- ---------------------------------------------------------------------------

CREATE TABLE clinics (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    branch_id      BIGINT UNSIGNED NOT NULL,
    department_id  BIGINT UNSIGNED NOT NULL,
    code           VARCHAR(20)     NOT NULL,
    name_ar        VARCHAR(150)    NOT NULL,
    name_en        VARCHAR(150)    NULL,
    location       VARCHAR(100)    NULL,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_clinics_public_id (public_id),
    UNIQUE KEY uq_clinics_branch_code (branch_id, code),
    KEY ix_clinics_department (department_id),
    CONSTRAINT fk_clinics_branches
        FOREIGN KEY (branch_id) REFERENCES branches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_clinics_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_clinics_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE service_catalog (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    code           VARCHAR(40)     NOT NULL,
    name_ar        VARCHAR(200)    NOT NULL,
    name_en        VARCHAR(200)    NULL,
    category       VARCHAR(40)     NOT NULL,
    department_id  BIGINT UNSIGNED NULL,
    taxable        TINYINT(1)      NOT NULL DEFAULT 0,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_service_catalog_public_id (public_id),
    UNIQUE KEY uq_service_catalog_code (code),
    KEY ix_service_catalog_category (category, active),
    CONSTRAINT fk_service_catalog_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_service_catalog_category
        CHECK (category IN ('CONSULTATION', 'LAB', 'RAD', 'PROCEDURE', 'DRUG', 'OTHER')),
    CONSTRAINT ck_service_catalog_taxable CHECK (taxable IN (0, 1)),
    CONSTRAINT ck_service_catalog_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE service_prices (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    service_id     BIGINT UNSIGNED NOT NULL,
    amount         DECIMAL(14,2)   NOT NULL,
    currency       CHAR(3)         NOT NULL DEFAULT 'JOD',
    valid_from     DATE            NOT NULL,
    valid_to       DATE            NULL,
    created_by     BIGINT UNSIGNED NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_service_prices_public_id (public_id),
    KEY ix_service_prices_service (service_id, valid_from),
    CONSTRAINT fk_service_prices_services
        FOREIGN KEY (service_id) REFERENCES service_catalog (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_service_prices_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drugs (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    code           VARCHAR(30)     NOT NULL,
    name_ar        VARCHAR(150)    NOT NULL,
    name_en        VARCHAR(150)    NULL,
    form           VARCHAR(40)     NOT NULL,
    strength       VARCHAR(40)     NULL,
    category       VARCHAR(40)     NULL,
    reorder_level  INT             NOT NULL DEFAULT 0,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_drugs_public_id (public_id),
    UNIQUE KEY uq_drugs_code (code),
    KEY ix_drugs_name (name_en, name_ar),
    CONSTRAINT ck_drugs_reorder CHECK (reorder_level >= 0),
    CONSTRAINT ck_drugs_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Patients / EMPI (FR-PAT-001..012)
-- ---------------------------------------------------------------------------

CREATE TABLE patients (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id             CHAR(26)        NOT NULL,
    medical_number        VARCHAR(20)     NOT NULL,
    full_name             VARCHAR(200)    NOT NULL,
    name_normalized       VARCHAR(200)    NOT NULL,
    gender                VARCHAR(10)     NOT NULL,
    birth_date            DATE            NULL,
    birth_date_estimated  TINYINT(1)      NOT NULL DEFAULT 0,
    national_id           VARCHAR(40)     NULL,
    phone                 VARCHAR(30)     NULL,
    email                 VARCHAR(190)    NULL,
    address               VARCHAR(500)    NULL,
    blood_group           VARCHAR(5)      NULL,
    marital_status        VARCHAR(20)     NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    merged_into_id        BIGINT UNSIGNED NULL,
    notes                 TEXT            NULL,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by            BIGINT UNSIGNED NULL,
    updated_at            DATETIME(6)     NULL,
    updated_by            BIGINT UNSIGNED NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patients_public_id (public_id),
    UNIQUE KEY uq_patients_medical_number (medical_number),
    UNIQUE KEY uq_patients_national_id (national_id),
    KEY ix_patients_name (name_normalized),
    KEY ix_patients_phone (phone),
    KEY ix_patients_status_name (status, name_normalized),
    KEY ix_patients_merged (merged_into_id),
    CONSTRAINT fk_patients_merged
        FOREIGN KEY (merged_into_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patients_gender
        CHECK (gender IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT ck_patients_status
        CHECK (status IN ('ACTIVE', 'MERGED', 'INACTIVE', 'DECEASED')),
    CONSTRAINT ck_patients_birth_estimated CHECK (birth_date_estimated IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_identifiers (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    type           VARCHAR(30)     NOT NULL,
    value          VARCHAR(80)     NOT NULL,
    is_primary     TINYINT(1)      NOT NULL DEFAULT 0,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_identifiers_public_id (public_id),
    UNIQUE KEY uq_patient_identifiers_type_value (type, value),
    KEY ix_patient_identifiers_patient (patient_id),
    CONSTRAINT fk_patient_identifiers_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_identifiers_type
        CHECK (type IN ('NATIONAL_ID', 'PASSPORT', 'INSURANCE', 'MRN', 'OTHER')),
    CONSTRAINT ck_patient_identifiers_primary CHECK (is_primary IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_contacts (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    contact_type   VARCHAR(20)     NOT NULL,
    full_name      VARCHAR(150)    NOT NULL,
    relation       VARCHAR(40)     NULL,
    phone          VARCHAR(30)     NOT NULL,
    is_primary     TINYINT(1)      NOT NULL DEFAULT 0,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_contacts_public_id (public_id),
    KEY ix_patient_contacts_patient (patient_id),
    CONSTRAINT fk_patient_contacts_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_contacts_type
        CHECK (contact_type IN ('EMERGENCY', 'GUARDIAN', 'NEXT_OF_KIN')),
    CONSTRAINT ck_patient_contacts_primary CHECK (is_primary IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_allergies (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    substance      VARCHAR(150)    NOT NULL,
    allergy_type   VARCHAR(30)     NOT NULL,
    severity       VARCHAR(20)     NOT NULL,
    reaction       VARCHAR(255)    NULL,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    recorded_by    BIGINT UNSIGNED NOT NULL,
    recorded_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_allergies_public_id (public_id),
    KEY ix_patient_allergies_patient (patient_id, active),
    CONSTRAINT fk_patient_allergies_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_allergies_type
        CHECK (allergy_type IN ('DRUG', 'FOOD', 'ENVIRONMENT', 'OTHER')),
    CONSTRAINT ck_patient_allergies_severity
        CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING')),
    CONSTRAINT ck_patient_allergies_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_conditions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    concept_id     BIGINT UNSIGNED NULL,
    description    VARCHAR(255)    NOT NULL,
    onset_date     DATE            NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_conditions_public_id (public_id),
    KEY ix_patient_conditions_patient (patient_id, status),
    CONSTRAINT fk_patient_conditions_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_conditions_status CHECK (status IN ('ACTIVE', 'RESOLVED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_merges (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    source_patient_id BIGINT UNSIGNED NOT NULL,
    target_patient_id BIGINT UNSIGNED NOT NULL,
    reason            VARCHAR(500)    NOT NULL,
    merged_by         BIGINT UNSIGNED NOT NULL,
    merged_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_merges_public_id (public_id),
    KEY ix_patient_merges_source (source_patient_id),
    KEY ix_patient_merges_target (target_patient_id),
    CONSTRAINT fk_patient_merges_source
        FOREIGN KEY (source_patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_patient_merges_target
        FOREIGN KEY (target_patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_merges_distinct CHECK (source_patient_id <> target_patient_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE patient_flags (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    flag_type      VARCHAR(30)     NOT NULL,
    note           VARCHAR(255)    NULL,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_patient_flags_public_id (public_id),
    KEY ix_patient_flags_patient (patient_id, active),
    CONSTRAINT fk_patient_flags_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_patient_flags_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE consents (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    consent_type   VARCHAR(40)     NOT NULL,
    granted        TINYINT(1)      NOT NULL,
    granted_at     DATETIME(6)     NULL,
    revoked_at     DATETIME(6)     NULL,
    document_id    BIGINT UNSIGNED NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_consents_public_id (public_id),
    KEY ix_consents_patient (patient_id, consent_type),
    CONSTRAINT fk_consents_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_consents_type
        CHECK (consent_type IN ('TREATMENT', 'SHARING', 'RESEARCH')),
    CONSTRAINT ck_consents_granted CHECK (granted IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Scheduling (FR-SCH-001..010)
-- ---------------------------------------------------------------------------

CREATE TABLE provider_schedules (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    provider_id    BIGINT UNSIGNED NOT NULL,
    clinic_id      BIGINT UNSIGNED NOT NULL,
    day_of_week    TINYINT         NOT NULL,
    start_time     TIME            NOT NULL,
    end_time       TIME            NOT NULL,
    slot_minutes   SMALLINT        NOT NULL DEFAULT 20,
    capacity       SMALLINT        NULL,
    valid_from     DATE            NOT NULL,
    valid_to       DATE            NULL,
    active         TINYINT(1)      NOT NULL DEFAULT 1,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_provider_schedules_public_id (public_id),
    KEY ix_provider_schedules_provider (provider_id, day_of_week, active),
    KEY ix_provider_schedules_clinic (clinic_id, day_of_week),
    CONSTRAINT fk_provider_schedules_users
        FOREIGN KEY (provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_provider_schedules_clinics
        FOREIGN KEY (clinic_id) REFERENCES clinics (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_provider_schedules_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_provider_schedules_time CHECK (end_time > start_time),
    CONSTRAINT ck_provider_schedules_slot CHECK (slot_minutes BETWEEN 5 AND 240),
    CONSTRAINT ck_provider_schedules_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE schedule_exceptions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    provider_id    BIGINT UNSIGNED NOT NULL,
    exception_date DATE            NOT NULL,
    start_time     TIME            NULL,
    end_time       TIME            NULL,
    reason         VARCHAR(255)    NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_schedule_exceptions_public_id (public_id),
    KEY ix_schedule_exceptions_provider (provider_id, exception_date),
    CONSTRAINT fk_schedule_exceptions_users
        FOREIGN KEY (provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_schedule_exceptions_time
        CHECK (start_time IS NULL OR end_time IS NULL OR end_time > start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE appointments (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    provider_id     BIGINT UNSIGNED NOT NULL,
    clinic_id       BIGINT UNSIGNED NOT NULL,
    slot_start      DATETIME(6)     NOT NULL,
    slot_end        DATETIME(6)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'BOOKED',
    source          VARCHAR(20)     NOT NULL DEFAULT 'RECEPTION',
    cancel_reason   VARCHAR(255)    NULL,
    active_flag     TINYINT         NULL DEFAULT 1,
    checked_in_at   DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT UNSIGNED NULL,
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_appointments_public_id (public_id),
    UNIQUE KEY uq_appointments_provider_slot (provider_id, slot_start, active_flag),
    KEY ix_appointments_patient_time (patient_id, slot_start),
    KEY ix_appointments_day (slot_start, status),
    KEY ix_appointments_clinic_day (clinic_id, slot_start),
    CONSTRAINT fk_appointments_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_appointments_providers
        FOREIGN KEY (provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_appointments_clinics
        FOREIGN KEY (clinic_id) REFERENCES clinics (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_appointments_status
        CHECK (status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_appointments_source
        CHECK (source IN ('RECEPTION', 'PHONE', 'WALK_IN', 'PORTAL')),
    CONSTRAINT ck_appointments_time CHECK (slot_end > slot_start),
    CONSTRAINT ck_appointments_active CHECK (active_flag IS NULL OR active_flag IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE appointment_status_history (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    appointment_id BIGINT UNSIGNED NOT NULL,
    from_status    VARCHAR(20)     NULL,
    to_status      VARCHAR(20)     NOT NULL,
    changed_by     BIGINT UNSIGNED NOT NULL,
    changed_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reason         VARCHAR(255)    NULL,
    PRIMARY KEY (id),
    KEY ix_appointment_history (appointment_id, changed_at),
    CONSTRAINT fk_appointment_history_appointments
        FOREIGN KEY (appointment_id) REFERENCES appointments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE queue_tickets (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    appointment_id BIGINT UNSIGNED NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    clinic_id      BIGINT UNSIGNED NOT NULL,
    ticket_date    DATE            NOT NULL,
    ticket_no      INT             NOT NULL,
    priority       VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    status         VARCHAR(20)     NOT NULL DEFAULT 'WAITING',
    checked_in_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    called_at      DATETIME(6)     NULL,
    served_at      DATETIME(6)     NULL,
    created_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_queue_tickets_public_id (public_id),
    UNIQUE KEY uq_queue_tickets_number (clinic_id, ticket_date, ticket_no),
    KEY ix_queue_tickets_waiting (clinic_id, ticket_date, status),
    KEY ix_queue_tickets_patient (patient_id),
    CONSTRAINT fk_queue_tickets_appointments
        FOREIGN KEY (appointment_id) REFERENCES appointments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_queue_tickets_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_queue_tickets_clinics
        FOREIGN KEY (clinic_id) REFERENCES clinics (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_queue_tickets_priority
        CHECK (priority IN ('NORMAL', 'URGENT', 'EMERGENCY')),
    CONSTRAINT ck_queue_tickets_status
        CHECK (status IN ('WAITING', 'CALLED', 'IN_SERVICE', 'DONE', 'LEFT')),
    CONSTRAINT ck_queue_tickets_no CHECK (ticket_no > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE waitlist_entries (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    patient_id     BIGINT UNSIGNED NOT NULL,
    provider_id    BIGINT UNSIGNED NOT NULL,
    clinic_id      BIGINT UNSIGNED NOT NULL,
    desired_from   DATE            NOT NULL,
    desired_to     DATE            NULL,
    priority       VARCHAR(20)     NOT NULL DEFAULT 'NORMAL',
    status         VARCHAR(20)     NOT NULL DEFAULT 'WAITING',
    notes          VARCHAR(255)    NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_waitlist_entries_public_id (public_id),
    KEY ix_waitlist_entries_pending (status, desired_from),
    KEY ix_waitlist_entries_provider (provider_id, status),
    CONSTRAINT fk_waitlist_entries_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_waitlist_entries_users
        FOREIGN KEY (provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_waitlist_entries_clinics
        FOREIGN KEY (clinic_id) REFERENCES clinics (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_waitlist_entries_priority
        CHECK (priority IN ('NORMAL', 'URGENT', 'EMERGENCY')),
    CONSTRAINT ck_waitlist_entries_status
        CHECK (status IN ('WAITING', 'BOOKED', 'CANCELLED', 'EXPIRED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Encounter / EMR (FR-EMR-001..012)
-- ---------------------------------------------------------------------------

CREATE TABLE encounters (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    appointment_id  BIGINT UNSIGNED NULL,
    provider_id     BIGINT UNSIGNED NOT NULL,
    department_id   BIGINT UNSIGNED NULL,
    type            VARCHAR(20)     NOT NULL DEFAULT 'OUTPATIENT',
    status          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    chief_complaint VARCHAR(500)    NULL,
    started_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at       DATETIME(6)     NULL,
    closed_by       BIGINT UNSIGNED NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT UNSIGNED NULL,
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_encounters_public_id (public_id),
    KEY ix_encounters_patient_time (patient_id, started_at),
    KEY ix_encounters_provider_status (provider_id, status),
    KEY ix_encounters_appointment (appointment_id),
    CONSTRAINT fk_encounters_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_encounters_appointments
        FOREIGN KEY (appointment_id) REFERENCES appointments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_encounters_providers
        FOREIGN KEY (provider_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_encounters_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_encounters_type
        CHECK (type IN ('OUTPATIENT', 'EMERGENCY', 'INPATIENT')),
    CONSTRAINT ck_encounters_status
        CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE clinical_notes (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    encounter_id       BIGINT UNSIGNED NOT NULL,
    note_type          VARCHAR(30)     NOT NULL DEFAULT 'PROGRESS',
    author_id          BIGINT UNSIGNED NOT NULL,
    current_version_id BIGINT UNSIGNED NULL,
    status             VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NULL,
    updated_by         BIGINT UNSIGNED NULL,
    version            BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_clinical_notes_public_id (public_id),
    KEY ix_clinical_notes_encounter (encounter_id, status),
    KEY ix_clinical_notes_author (author_id),
    CONSTRAINT fk_clinical_notes_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_clinical_notes_authors
        FOREIGN KEY (author_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_clinical_notes_type
        CHECK (note_type IN ('PROGRESS', 'ADMISSION', 'DISCHARGE', 'NURSING', 'CONSULTATION')),
    CONSTRAINT ck_clinical_notes_status
        CHECK (status IN ('DRAFT', 'SIGNED', 'AMENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE clinical_note_versions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    note_id        BIGINT UNSIGNED NOT NULL,
    version_no     INT             NOT NULL,
    content_json   JSON            NOT NULL,
    content_text   MEDIUMTEXT      NULL,
    amends_id      BIGINT UNSIGNED NULL,
    amend_reason   VARCHAR(500)    NULL,
    signed_by      BIGINT UNSIGNED NULL,
    signed_at      DATETIME(6)     NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_clinical_note_versions_public_id (public_id),
    UNIQUE KEY uq_clinical_note_versions_no (note_id, version_no),
    KEY ix_clinical_note_versions_amend (amends_id),
    CONSTRAINT fk_clinical_note_versions_notes
        FOREIGN KEY (note_id) REFERENCES clinical_notes (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_clinical_note_versions_amend
        FOREIGN KEY (amends_id) REFERENCES clinical_note_versions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_clinical_note_versions_no CHECK (version_no > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE clinical_notes
    ADD CONSTRAINT fk_clinical_notes_current_version
        FOREIGN KEY (current_version_id) REFERENCES clinical_note_versions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE diagnoses (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    encounter_id   BIGINT UNSIGNED NOT NULL,
    concept_id     BIGINT UNSIGNED NULL,
    code           VARCHAR(20)     NULL,
    description    VARCHAR(255)    NOT NULL,
    diagnosis_type VARCHAR(20)     NOT NULL DEFAULT 'SECONDARY',
    diagnosed_by   BIGINT UNSIGNED NOT NULL,
    primary_flag   TINYINT         NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_diagnoses_public_id (public_id),
    UNIQUE KEY uq_diagnoses_primary (encounter_id, primary_flag),
    KEY ix_diagnoses_encounter (encounter_id),
    CONSTRAINT fk_diagnoses_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_diagnoses_users
        FOREIGN KEY (diagnosed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_diagnoses_type
        CHECK (diagnosis_type IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_diagnoses_primary CHECK (primary_flag IS NULL OR primary_flag = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE vitals (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id         CHAR(26)        NOT NULL,
    encounter_id      BIGINT UNSIGNED NOT NULL,
    patient_id        BIGINT UNSIGNED NOT NULL,
    temperature_c     DECIMAL(4,1)    NULL,
    heart_rate        SMALLINT        NULL,
    respiratory_rate  SMALLINT        NULL,
    systolic          SMALLINT        NULL,
    diastolic         SMALLINT        NULL,
    spo2              TINYINT         NULL,
    weight_kg         DECIMAL(5,1)    NULL,
    height_cm         DECIMAL(5,1)    NULL,
    pain_score        TINYINT         NULL,
    recorded_by       BIGINT UNSIGNED NOT NULL,
    recorded_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_vitals_public_id (public_id),
    KEY ix_vitals_encounter (encounter_id, recorded_at),
    KEY ix_vitals_patient (patient_id, recorded_at),
    CONSTRAINT fk_vitals_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_vitals_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_vitals_users
        FOREIGN KEY (recorded_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_vitals_temperature CHECK (temperature_c IS NULL OR temperature_c BETWEEN 25.0 AND 45.0),
    CONSTRAINT ck_vitals_heart CHECK (heart_rate IS NULL OR heart_rate BETWEEN 20 AND 300),
    CONSTRAINT ck_vitals_resp CHECK (respiratory_rate IS NULL OR respiratory_rate BETWEEN 4 AND 80),
    CONSTRAINT ck_vitals_systolic CHECK (systolic IS NULL OR systolic BETWEEN 40 AND 300),
    CONSTRAINT ck_vitals_diastolic CHECK (diastolic IS NULL OR diastolic BETWEEN 20 AND 200),
    CONSTRAINT ck_vitals_spo2 CHECK (spo2 IS NULL OR spo2 BETWEEN 0 AND 100),
    CONSTRAINT ck_vitals_weight CHECK (weight_kg IS NULL OR weight_kg BETWEEN 0.3 AND 400.0),
    CONSTRAINT ck_vitals_height CHECK (height_cm IS NULL OR height_cm BETWEEN 20.0 AND 260.0),
    CONSTRAINT ck_vitals_pain CHECK (pain_score IS NULL OR pain_score BETWEEN 0 AND 10)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE service_orders (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    encounter_id   BIGINT UNSIGNED NOT NULL,
    order_type     VARCHAR(20)     NOT NULL,
    ordered_by     BIGINT UNSIGNED NOT NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'ORDERED',
    priority       VARCHAR(20)     NOT NULL DEFAULT 'ROUTINE',
    notes          VARCHAR(500)    NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)     NULL,
    updated_by     BIGINT UNSIGNED NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_service_orders_public_id (public_id),
    KEY ix_service_orders_encounter (encounter_id, order_type),
    KEY ix_service_orders_status (status, priority),
    CONSTRAINT fk_service_orders_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_service_orders_users
        FOREIGN KEY (ordered_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_service_orders_type
        CHECK (order_type IN ('LAB', 'RAD', 'PROCEDURE')),
    CONSTRAINT ck_service_orders_status
        CHECK (status IN ('ORDERED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_service_orders_priority
        CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE order_items (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    order_id       BIGINT UNSIGNED NOT NULL,
    service_id     BIGINT UNSIGNED NOT NULL,
    quantity       SMALLINT        NOT NULL DEFAULT 1,
    status         VARCHAR(20)     NOT NULL DEFAULT 'ORDERED',
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_items_public_id (public_id),
    KEY ix_order_items_order (order_id),
    CONSTRAINT fk_order_items_orders
        FOREIGN KEY (order_id) REFERENCES service_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_items_services
        FOREIGN KEY (service_id) REFERENCES service_catalog (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_status
        CHECK (status IN ('ORDERED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE prescriptions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    encounter_id    BIGINT UNSIGNED NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    prescribed_by   BIGINT UNSIGNED NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    issued_at       DATETIME(6)     NULL,
    reviewed_by     BIGINT UNSIGNED NULL,
    reviewed_at     DATETIME(6)     NULL,
    notes           VARCHAR(500)    NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NULL,
    updated_by      BIGINT UNSIGNED NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prescriptions_public_id (public_id),
    KEY ix_prescriptions_encounter (encounter_id),
    KEY ix_prescriptions_patient (patient_id, issued_at),
    KEY ix_prescriptions_status (status),
    CONSTRAINT fk_prescriptions_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_prescriptions_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_prescriptions_users
        FOREIGN KEY (prescribed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_prescriptions_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'REVIEWED', 'DISPENSED', 'PARTIAL', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE prescription_items (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id      CHAR(26)        NOT NULL,
    prescription_id BIGINT UNSIGNED NOT NULL,
    drug_id        BIGINT UNSIGNED NOT NULL,
    dose           VARCHAR(50)     NOT NULL,
    route          VARCHAR(30)     NOT NULL,
    frequency      VARCHAR(50)     NOT NULL,
    duration_days  SMALLINT        NULL,
    quantity       INT             NOT NULL,
    instructions   VARCHAR(500)    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_prescription_items_public_id (public_id),
    KEY ix_prescription_items_prescription (prescription_id),
    CONSTRAINT fk_prescription_items_prescriptions
        FOREIGN KEY (prescription_id) REFERENCES prescriptions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_prescription_items_drugs
        FOREIGN KEY (drug_id) REFERENCES drugs (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_prescription_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_prescription_items_duration CHECK (duration_days IS NULL OR duration_days > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Charge capture (ADR-016): executed service is the billing source of truth
-- ---------------------------------------------------------------------------

CREATE TABLE service_executions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id       CHAR(26)        NOT NULL,
    patient_id      BIGINT UNSIGNED NOT NULL,
    encounter_id    BIGINT UNSIGNED NULL,
    service_id      BIGINT UNSIGNED NOT NULL,
    performed_by    BIGINT UNSIGNED NOT NULL,
    performed_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    price_snapshot  DECIMAL(14,2)   NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'EXECUTED',
    source_type     VARCHAR(20)     NOT NULL DEFAULT 'MANUAL',
    source_public_id CHAR(26)       NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_service_executions_public_id (public_id),
    KEY ix_service_executions_patient (patient_id, performed_at),
    KEY ix_service_executions_encounter (encounter_id),
    KEY ix_service_executions_status (status),
    CONSTRAINT fk_service_executions_patients
        FOREIGN KEY (patient_id) REFERENCES patients (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_service_executions_encounters
        FOREIGN KEY (encounter_id) REFERENCES encounters (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_service_executions_services
        FOREIGN KEY (service_id) REFERENCES service_catalog (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_service_executions_users
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_service_executions_status
        CHECK (status IN ('EXECUTED', 'BILLED', 'CANCELLED')),
    CONSTRAINT ck_service_executions_source
        CHECK (source_type IN ('MANUAL', 'ORDER', 'CONSULTATION', 'PROCEDURE')),
    CONSTRAINT ck_service_executions_price CHECK (price_snapshot >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Number sequences (medical numbers, queue tickets)
-- ---------------------------------------------------------------------------

CREATE TABLE number_sequences (
    seq_name       VARCHAR(60) NOT NULL,
    period         CHAR(4)     NOT NULL,
    current_value  INT         NOT NULL DEFAULT 0,
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (seq_name, period),
    CONSTRAINT ck_number_sequences_value CHECK (current_value >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
