-- Smart Hospital - V008
-- Phase 5 correction: the invoice balance is a derived, non-negative value.
-- A credit note on a fully paid invoice extinguishes the receivable without
-- turning the stored balance negative (BR-BIL-008, TC-169).

ALTER TABLE invoices
    MODIFY COLUMN balance DECIMAL(14,2) GENERATED ALWAYS AS
        (GREATEST(grand_total - credited_total - paid_total + refunded_total, 0)) STORED;
