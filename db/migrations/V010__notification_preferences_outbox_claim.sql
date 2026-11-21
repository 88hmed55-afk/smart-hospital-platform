-- Smart Hospital - V010
-- Phase 6 slice 1: durable outbox claiming and per-user notification preferences.
--
-- 1) outbox_events.claimed_at lets a worker own an event while it is being
--    delivered. Stale claims are recovered by the poller, so a crash mid-flight
--    never parks an event in PROCESSING forever (ADR-005).
-- 2) notification_preferences stores the opt-out / minimum-severity policy that
--    the dispatcher applies before inserting an inbox row (FR-NOT-004).

ALTER TABLE outbox_events
    ADD COLUMN claimed_at DATETIME(6) NULL AFTER next_attempt_at,
    ADD KEY ix_outbox_events_claim (status, next_attempt_at, id);

CREATE TABLE notification_preferences (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    category     VARCHAR(40)     NOT NULL,
    enabled      TINYINT(1)      NOT NULL DEFAULT 1,
    min_severity VARCHAR(20)     NOT NULL DEFAULT 'INFO',
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
    version      BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_notification_preferences_user_category (user_id, category),
    CONSTRAINT fk_notification_preferences_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT ck_notification_preferences_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_notification_preferences_severity
        CHECK (min_severity IN ('INFO', 'WARNING', 'CRITICAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
