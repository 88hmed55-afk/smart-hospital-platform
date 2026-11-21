-- Smart Hospital - V011
-- Phase 6 slice 2: atomic outbox claiming.
--
-- 1) claim_token identifies the worker batch that owns an event. The claim is a
--    single UPDATE ... ORDER BY id LIMIT n, so there is no read-then-write
--    window between two workers (the previous SELECT ... FOR UPDATE SKIP LOCKED
--    plus per-row UPDATE could deadlock on secondary-index gap locks).
-- 2) ix_outbox_events_ready (status, id) keeps the claim scan bounded: the
--    UPDATE walks PENDING rows in id order and stops after LIMIT rows.

ALTER TABLE outbox_events
    ADD COLUMN claim_token VARCHAR(40) NULL AFTER claimed_at,
    ADD KEY ix_outbox_events_ready (status, id);
