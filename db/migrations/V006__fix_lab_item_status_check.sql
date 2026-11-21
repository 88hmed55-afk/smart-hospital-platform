-- Smart Hospital - V006
-- Phase 4 fix: lab_order_items must accept the CORRECTED status.
--
-- When a result is amended, LabService marks the parent order item CORRECTED
-- so the item tracks the same lifecycle as lab_results. V005 only allowed
-- ORDERED..RELEASED plus CANCELLED/REJECTED, so the amend path failed with a
-- check-constraint violation. This migration widens the constraint.
--
-- Forward-only. Never edit after this file has been applied.

SET NAMES utf8mb4;

ALTER TABLE lab_order_items
    DROP CHECK ck_lab_order_items_status;

ALTER TABLE lab_order_items
    ADD CONSTRAINT ck_lab_order_items_status
        CHECK (status IN ('ORDERED', 'COLLECTED', 'RECEIVED', 'IN_PROCESS', 'RESULTED',
                          'VERIFIED', 'RELEASED', 'CORRECTED', 'CANCELLED', 'REJECTED'));
