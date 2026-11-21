-- Smart Hospital — V002
-- Audit, access log and transactional outbox (Phase 1/2)
-- Append-only tables have no updated_at/version by design.

SET NAMES utf8mb4;

CREATE TABLE audit_logs (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id     BIGINT UNSIGNED NULL,
    action            VARCHAR(80)     NOT NULL,
    entity_type       VARCHAR(40)     NOT NULL,
    entity_public_id  CHAR(26)        NULL,
    before_json       JSON            NULL,
    after_json        JSON            NULL,
    reason            VARCHAR(500)    NULL,
    ip                VARCHAR(45)     NULL,
    trace_id          CHAR(32)        NULL,
    prev_hash         CHAR(64)        NULL,
    hash              CHAR(64)        NOT NULL,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_audit_logs_actor_time (actor_user_id, created_at),
    KEY ix_audit_logs_entity (entity_type, entity_public_id),
    KEY ix_audit_logs_created (created_at),
    CONSTRAINT fk_audit_logs_users
        FOREIGN KEY (actor_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE access_logs (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    patient_id          BIGINT UNSIGNED NULL,
    resource_type       VARCHAR(40)     NOT NULL,
    resource_public_id  CHAR(26)        NULL,
    action              VARCHAR(20)     NOT NULL,
    ip                  VARCHAR(45)     NULL,
    accessed_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_access_logs_user_time (user_id, accessed_at),
    KEY ix_access_logs_patient_time (patient_id, accessed_at),
    CONSTRAINT fk_access_logs_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_access_logs_action
        CHECK (action IN ('READ', 'EXPORT', 'PRINT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE outbox_events (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id        CHAR(26)        NOT NULL,
    event_type      VARCHAR(80)     NOT NULL,
    aggregate_type  VARCHAR(40)     NOT NULL,
    aggregate_id    CHAR(26)        NULL,
    payload_json    JSON            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    attempts        INT             NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)     NULL,
    last_error      VARCHAR(1000)   NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at    DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_events_event_id (event_id),
    KEY ix_outbox_events_pending (status, next_attempt_at),
    KEY ix_outbox_events_aggregate (aggregate_type, aggregate_id),
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD')),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_keys (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    endpoint       VARCHAR(120)    NOT NULL,
    `key`          VARCHAR(120)    NOT NULL,
    request_hash   CHAR(64)        NOT NULL,
    response_json  JSON            NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'IN_PROGRESS',
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at     DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_idempotency_user_endpoint_key (user_id, endpoint, `key`),
    KEY ix_idempotency_expiry (expires_at),
    CONSTRAINT fk_idempotency_keys_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_idempotency_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE job_runs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    job_name        VARCHAR(100)    NOT NULL,
    started_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at     DATETIME(6)     NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'RUNNING',
    processed_count INT             NOT NULL DEFAULT 0,
    error_message   VARCHAR(1000)   NULL,
    PRIMARY KEY (id),
    KEY ix_job_runs_name_time (job_name, started_at),
    CONSTRAINT ck_job_runs_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_job_runs_count CHECK (processed_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Audit is append-only. The application user may hold DELETE at database
-- level, so the database itself must refuse mutation (ADR-009).
CREATE TRIGGER trg_audit_logs_no_update
BEFORE UPDATE ON audit_logs FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';

CREATE TRIGGER trg_audit_logs_no_delete
BEFORE DELETE ON audit_logs FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';
