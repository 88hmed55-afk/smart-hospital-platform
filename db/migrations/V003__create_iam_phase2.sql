-- Smart Hospital — V003
-- Phase 2: IAM completion (data scopes, password history, break-glass, audit chain)
-- MySQL 8.0.38 · InnoDB · utf8mb4
-- Forward-only. Never edit after this file has been applied.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Data scopes (FR-IAM-006)
-- scope_ref_id = 0 for ALL / OWN_PATIENTS so the unique key works with NULL-free
-- semantics (MySQL unique keys allow repeated NULLs).
-- ---------------------------------------------------------------------------

CREATE TABLE user_scopes (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    scope_type    VARCHAR(20)     NOT NULL,
    scope_ref_id  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_scopes (user_id, scope_type, scope_ref_id),
    KEY ix_user_scopes_user (user_id),
    CONSTRAINT fk_user_scopes_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_user_scopes_type
        CHECK (scope_type IN ('ALL', 'BRANCH', 'DEPARTMENT', 'WARD', 'OWN_PATIENTS')),
    CONSTRAINT ck_user_scopes_ref
        CHECK ((scope_type IN ('ALL', 'OWN_PATIENTS') AND scope_ref_id = 0)
            OR (scope_type IN ('BRANCH', 'DEPARTMENT', 'WARD') AND scope_ref_id > 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Password history (prevent reuse of the last 5 passwords)
-- ---------------------------------------------------------------------------

CREATE TABLE password_history (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    password_hash       VARCHAR(255)    NOT NULL,
    password_salt       VARBINARY(32)   NOT NULL,
    password_algo       VARCHAR(40)     NOT NULL,
    password_iterations INT             NOT NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_password_history_user (user_id, created_at),
    CONSTRAINT fk_password_history_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Break-Glass grants (FR-IAM-009, ADR-009)
-- The 60-minute ceiling is enforced by the database as a second line of
-- defence in addition to the service check.
-- ---------------------------------------------------------------------------

CREATE TABLE break_glass_grants (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id          CHAR(26)        NOT NULL,
    requester_user_id  BIGINT UNSIGNED NOT NULL,
    patient_public_id  CHAR(26)        NOT NULL,
    reason             VARCHAR(500)    NOT NULL,
    status             VARCHAR(20)     NOT NULL DEFAULT 'REQUESTED',
    granted_at         DATETIME(6)     NULL,
    expires_at         DATETIME(6)     NULL,
    revoked_at         DATETIME(6)     NULL,
    reviewed_at        DATETIME(6)     NULL,
    reviewed_by        BIGINT UNSIGNED NULL,
    review_note        VARCHAR(500)    NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_break_glass_public_id (public_id),
    KEY ix_break_glass_requester (requester_user_id, created_at),
    KEY ix_break_glass_patient (patient_public_id, created_at),
    KEY ix_break_glass_status (status, expires_at),
    CONSTRAINT fk_break_glass_requester
        FOREIGN KEY (requester_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_break_glass_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_break_glass_status
        CHECK (status IN ('REQUESTED', 'GRANTED', 'EXPIRED', 'REVOKED', 'REVIEWED')),
    CONSTRAINT ck_break_glass_reason CHECK (CHAR_LENGTH(reason) >= 10),
    CONSTRAINT ck_break_glass_duration
        CHECK (expires_at IS NULL OR granted_at IS NULL
            OR expires_at <= DATE_ADD(granted_at, INTERVAL 60 MINUTE))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Audit hash chain head (ADR-009)
-- One row, locked for update by every audit write so concurrent transactions
-- serialise on the chain and cannot fork it.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_chain (
    id         TINYINT UNSIGNED NOT NULL,
    last_hash  CHAR(64)         NULL,
    updated_at DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_audit_chain_single CHECK (id = 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Continue the chain from any pre-existing audit rows (e.g. a database that
-- was seeded or tested before V003).
INSERT INTO audit_chain (id, last_hash)
SELECT 1, (SELECT hash FROM audit_logs ORDER BY id DESC LIMIT 1);
