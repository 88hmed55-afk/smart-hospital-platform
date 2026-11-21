-- Smart Hospital - V009
-- Phase 5 correction: daily document sequences use an 8-character period
-- (yyyyMMdd) for invoice, payment, refund, credit-note, cash-session,
-- transfer, count, purchase-request, purchase-order and goods-receipt
-- numbers. CHAR(4) truncated the insert and silently reset the counter,
-- producing duplicate "...-0000" numbers (TC-108..TC-127, TC-175).

ALTER TABLE number_sequences
    MODIFY COLUMN period CHAR(8) NOT NULL;
