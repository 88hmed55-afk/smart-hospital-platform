package com.smarthospital.clinical.billing;

import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit SQL gateway for billing (FR-BIL-001..010).
 *
 * <p>All statements run on the JTA connection of the current EJB transaction.
 * Rows that participate in money movement are read with {@code FOR UPDATE}
 * (ADR-008): invoice on payment/refund, cash session on close.
 */
@Stateless
public class BillingRepository {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    // ------------------------------------------------------------------
    // Invoices
    // ------------------------------------------------------------------

    private static final String INVOICE_SELECT =
            "SELECT i.id, i.public_id, i.invoice_number, i.patient_id, i.encounter_id, "
                    + "i.cash_session_id, i.status, i.subtotal, i.discount_total, i.tax_total, "
                    + "i.grand_total, i.paid_total, i.refunded_total, i.credited_total, i.balance, "
                    + "i.currency, i.discount_reason, i.notes, i.issued_at, i.due_date, "
                    + "i.closed_at, i.voided_at, i.void_reason, i.version, "
                    + "p.public_id AS patient_public_id, p.medical_number, p.full_name, "
                    + "e.public_id AS encounter_public_id, "
                    + "cs.public_id AS session_public_id, cs.status AS session_status "
                    + "FROM invoices i "
                    + "JOIN patients p ON p.id = i.patient_id "
                    + "LEFT JOIN encounters e ON e.id = i.encounter_id "
                    + "LEFT JOIN cash_sessions cs ON cs.id = i.cash_session_id ";

    public Map<String, Object> invoiceByPublicId(String publicId) {
        return Sql.one(em, INVOICE_SELECT + "WHERE i.public_id = ?", publicId);
    }

    public Map<String, Object> invoiceById(long id) {
        return Sql.one(em, INVOICE_SELECT + "WHERE i.id = ?", id);
    }

    /** Locks the invoice row for the rest of the transaction (payment/refund/discount/void). */
    public Map<String, Object> lockInvoice(long id) {
        return Sql.one(em, INVOICE_SELECT + "WHERE i.id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> invoices(String query, String status, String patientPublicId,
                                              LocalDate from, LocalDate to, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = invoiceWhere(query, status, patientPublicId, from, to, params);
        params.add(limit);
        params.add(offset);
        return Sql.query(em, INVOICE_SELECT + where
                + "ORDER BY i.issued_at DESC, i.id DESC LIMIT ? OFFSET ?", params.toArray());
    }

    public long countInvoices(String query, String status, String patientPublicId,
                              LocalDate from, LocalDate to) {
        List<Object> params = new ArrayList<>();
        String where = invoiceWhere(query, status, patientPublicId, from, to, params);
        Map<String, Object> row = Sql.one(em, "SELECT COUNT(*) AS total FROM invoices i "
                + "JOIN patients p ON p.id = i.patient_id " + where, params.toArray());
        return Sql.longValue(row, "total", 0);
    }

    private String invoiceWhere(String query, String status, String patientPublicId,
                                LocalDate from, LocalDate to, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        if (query != null && !query.isBlank()) {
            where.append("AND (i.invoice_number LIKE ? OR p.medical_number LIKE ? "
                    + "OR p.full_name LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append("AND i.status = ? ");
            params.add(status);
        }
        if (patientPublicId != null && !patientPublicId.isBlank()) {
            where.append("AND p.public_id = ? ");
            params.add(patientPublicId);
        }
        if (from != null) {
            where.append("AND i.issued_at >= ? ");
            params.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append("AND i.issued_at < ? ");
            params.add(to.plusDays(1).atStartOfDay());
        }
        return where.toString();
    }

    public long insertInvoice(String publicId, String invoiceNumber, long patientId,
                              Long encounterId, String status, BigDecimal subtotal,
                              BigDecimal discount, BigDecimal tax, BigDecimal grand,
                              String currency, String notes, LocalDate dueDate, long actorId) {
        return Sql.insert(em,
                "INSERT INTO invoices (public_id, invoice_number, patient_id, encounter_id, "
                        + "status, subtotal, discount_total, tax_total, grand_total, currency, "
                        + "notes, due_date, issued_by, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, invoiceNumber, patientId, encounterId, status, subtotal, discount,
                tax, grand, currency, notes, dueDate, actorId, actorId);
    }

    public void insertInvoiceItem(String publicId, long invoiceId, long executionId,
                                  long serviceId, String descriptionAr, String descriptionEn,
                                  int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        Sql.insert(em,
                "INSERT INTO invoice_items (public_id, invoice_id, service_execution_id, "
                        + "service_id, description_ar, description_en, quantity, unit_price, "
                        + "line_total) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, invoiceId, executionId, serviceId, descriptionAr, descriptionEn,
                quantity, unitPrice, lineTotal);
    }

    public List<Map<String, Object>> invoiceItems(long invoiceId) {
        return Sql.query(em,
                "SELECT ii.id, ii.public_id, ii.service_execution_id, ii.service_id, "
                        + "ii.description_ar, ii.description_en, ii.quantity, ii.unit_price, "
                        + "ii.line_total, s.code AS service_code, s.category AS service_category "
                        + "FROM invoice_items ii JOIN service_catalog s ON s.id = ii.service_id "
                        + "WHERE ii.invoice_id = ? ORDER BY ii.id", invoiceId);
    }

    public int updateDiscount(long invoiceId, long version, BigDecimal discount,
                              BigDecimal tax, BigDecimal grand, String reason, long actorId) {
        return Sql.update(em,
                "UPDATE invoices SET discount_total = ?, tax_total = ?, grand_total = ?, "
                        + "discount_reason = ?, discount_by = ?, discount_at = CURRENT_TIMESTAMP(6), "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status <> 'VOID'",
                discount, tax, grand, reason, actorId, actorId, invoiceId, version);
    }

    public int updateTotals(long invoiceId, long version, BigDecimal paid, BigDecimal refunded,
                            BigDecimal credited, String status, Long sessionId) {
        return Sql.update(em,
                "UPDATE invoices SET paid_total = ?, refunded_total = ?, credited_total = ?, "
                        + "status = ?, cash_session_id = COALESCE(cash_session_id, ?), "
                        + "updated_at = CURRENT_TIMESTAMP(6), version = version + 1 "
                        + "WHERE id = ? AND version = ?",
                paid, refunded, credited, status, sessionId, invoiceId, version);
    }

    public int voidInvoice(long invoiceId, long version, String reason, long actorId) {
        return Sql.update(em,
                "UPDATE invoices SET status = 'VOID', voided_at = CURRENT_TIMESTAMP(6), "
                        + "voided_by = ?, void_reason = ?, updated_at = CURRENT_TIMESTAMP(6), "
                        + "updated_by = ?, version = version + 1 WHERE id = ? AND version = ?",
                actorId, reason, actorId, invoiceId, version);
    }

    // ------------------------------------------------------------------
    // Charge capture
    // ------------------------------------------------------------------

    public List<Map<String, Object>> capturableExecutions(long patientId, Long encounterId,
                                                          List<String> itemPublicIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT se.id, se.public_id, se.service_id, se.price_snapshot, se.performed_at, "
                        + "s.code AS service_code, s.name_ar, s.name_en, s.category "
                        + "FROM service_executions se "
                        + "JOIN service_catalog s ON s.id = se.service_id "
                        + "WHERE se.patient_id = ? AND se.status = 'EXECUTED' ");
        List<Object> params = new ArrayList<>();
        params.add(patientId);
        if (encounterId != null) {
            sql.append("AND se.encounter_id = ? ");
            params.add(encounterId);
        }
        if (itemPublicIds != null && !itemPublicIds.isEmpty()) {
            sql.append("AND se.public_id IN (");
            sql.append("?,".repeat(itemPublicIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(") ");
            params.addAll(itemPublicIds);
        }
        sql.append("ORDER BY se.performed_at, se.id");
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public void markExecutionBilled(long executionId) {
        Sql.update(em, "UPDATE service_executions SET status = 'BILLED' "
                + "WHERE id = ? AND status = 'EXECUTED'", executionId);
    }

    public Map<String, Object> executionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT se.id, se.public_id, se.status, se.patient_id, se.price_snapshot "
                        + "FROM service_executions se WHERE se.public_id = ?", publicId);
    }

    // ------------------------------------------------------------------
    // Payments / allocations / refunds / credit notes
    // ------------------------------------------------------------------

    public Map<String, Object> paymentByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT pay.id, pay.public_id, pay.receipt_number, pay.invoice_id, "
                        + "pay.patient_id, pay.cash_session_id, pay.amount, pay.currency, "
                        + "pay.method, pay.reference, pay.status, pay.notes, pay.received_at, "
                        + "i.public_id AS invoice_public_id, i.invoice_number, i.balance, "
                        + "i.grand_total, p.public_id AS patient_public_id, p.medical_number, "
                        + "p.full_name, cs.public_id AS session_public_id "
                        + "FROM payments pay "
                        + "JOIN invoices i ON i.id = pay.invoice_id "
                        + "JOIN patients p ON p.id = pay.patient_id "
                        + "LEFT JOIN cash_sessions cs ON cs.id = pay.cash_session_id "
                        + "WHERE pay.public_id = ?", publicId);
    }

    public Map<String, Object> paymentByIdempotencyKey(String idempotencyKey) {
        return Sql.one(em,
                "SELECT id, public_id, invoice_id, amount, status FROM payments "
                        + "WHERE idempotency_key = ?", idempotencyKey);
    }

    public List<Map<String, Object>> paymentsForInvoice(long invoiceId) {
        return Sql.query(em,
                "SELECT pay.id, pay.public_id, pay.receipt_number, pay.amount, pay.method, "
                        + "pay.reference, pay.status, pay.received_at, "
                        + "u.display_name_ar AS received_by_ar, u.display_name_en AS received_by_en "
                        + "FROM payments pay JOIN users u ON u.id = pay.received_by "
                        + "WHERE pay.invoice_id = ? ORDER BY pay.received_at, pay.id", invoiceId);
    }

    public long insertPayment(String publicId, String receiptNumber, long invoiceId,
                              long patientId, Long sessionId, BigDecimal amount,
                              String currency, String method, String reference,
                              String idempotencyKey, String notes, long actorId) {
        return Sql.insert(em,
                "INSERT INTO payments (public_id, receipt_number, invoice_id, patient_id, "
                        + "cash_session_id, amount, currency, method, reference, idempotency_key, "
                        + "notes, received_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, receiptNumber, invoiceId, patientId, sessionId, amount, currency,
                method, reference, idempotencyKey, notes, actorId);
    }

    public void insertAllocation(String publicId, long paymentId, long invoiceId,
                                 BigDecimal amount) {
        Sql.insert(em,
                "INSERT INTO payment_allocations (public_id, payment_id, invoice_id, amount) "
                        + "VALUES (?, ?, ?, ?)", publicId, paymentId, invoiceId, amount);
    }

    public BigDecimal refundedForPayment(long paymentId) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COALESCE(SUM(amount), 0) AS total FROM refunds "
                        + "WHERE payment_id = ? AND status = 'POSTED'", paymentId);
        return Sql.decimal(row, "total") == null ? BigDecimal.ZERO : Sql.decimal(row, "total");
    }

    public long insertRefund(String publicId, String refundNumber, long paymentId,
                             long invoiceId, long patientId, Long sessionId, BigDecimal amount,
                             String currency, String method, String reason, long approvedBy,
                             long performedBy) {
        return Sql.insert(em,
                "INSERT INTO refunds (public_id, refund_number, payment_id, invoice_id, "
                        + "patient_id, cash_session_id, amount, currency, method, reason, "
                        + "approved_by, performed_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, refundNumber, paymentId, invoiceId, patientId, sessionId, amount,
                currency, method, reason, approvedBy, performedBy);
    }

    public List<Map<String, Object>> refundsForInvoice(long invoiceId) {
        return Sql.query(em,
                "SELECT r.id, r.public_id, r.refund_number, r.amount, r.method, r.reason, "
                        + "r.status, r.performed_at, pay.receipt_number, pay.public_id AS payment_public_id "
                        + "FROM refunds r JOIN payments pay ON pay.id = r.payment_id "
                        + "WHERE r.invoice_id = ? ORDER BY r.performed_at, r.id", invoiceId);
    }

    public long insertCreditNote(String publicId, String creditNumber, long invoiceId,
                                 long patientId, BigDecimal amount, String currency,
                                 String reason, long actorId) {
        return Sql.insert(em,
                "INSERT INTO credit_notes (public_id, credit_number, invoice_id, patient_id, "
                        + "amount, currency, reason, issued_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, creditNumber, invoiceId, patientId, amount, currency, reason, actorId);
    }

    public List<Map<String, Object>> creditNotesForInvoice(long invoiceId) {
        return Sql.query(em,
                "SELECT public_id, credit_number, amount, currency, reason, status, issued_at "
                        + "FROM credit_notes WHERE invoice_id = ? ORDER BY issued_at, id", invoiceId);
    }

    // ------------------------------------------------------------------
    // Cash sessions
    // ------------------------------------------------------------------

    public Map<String, Object> currentSession(long cashierId) {
        return Sql.one(em,
                "SELECT cs.id, cs.public_id, cs.session_number, cs.opening_float, "
                        + "cs.expected_total, cs.counted_total, cs.variance, cs.currency, "
                        + "cs.status, cs.opened_at, cs.closed_at, cs.notes, cs.version, "
                        + "u.public_id AS cashier_public_id, "
                        + "u.display_name_ar AS cashier_ar, u.display_name_en AS cashier_en "
                        + "FROM cash_sessions cs JOIN users u ON u.id = cs.cashier_id "
                        + "WHERE cs.cashier_id = ? AND cs.active_flag = 'Y'", cashierId);
    }

    public Map<String, Object> sessionByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT cs.id, cs.public_id, cs.session_number, cs.cashier_id, "
                        + "cs.opening_float, cs.expected_total, cs.counted_total, cs.variance, "
                        + "cs.currency, cs.status, cs.opened_at, cs.closed_at, cs.notes, "
                        + "cs.version, u.public_id AS cashier_public_id, "
                        + "u.display_name_ar AS cashier_ar, u.display_name_en AS cashier_en "
                        + "FROM cash_sessions cs JOIN users u ON u.id = cs.cashier_id "
                        + "WHERE cs.public_id = ?", publicId);
    }

    public Map<String, Object> lockSession(long id) {
        return Sql.one(em,
                "SELECT id, public_id, session_number, cashier_id, opening_float, status, "
                        + "version FROM cash_sessions WHERE id = ? FOR UPDATE", id);
    }

    public long insertSession(String publicId, String sessionNumber, long cashierId,
                              BigDecimal openingFloat, String currency, long actorId) {
        return Sql.insert(em,
                "INSERT INTO cash_sessions (public_id, session_number, cashier_id, "
                        + "opening_float, currency, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                publicId, sessionNumber, cashierId, openingFloat, currency, actorId);
    }

    public int closeSession(long sessionId, long version, BigDecimal expected,
                            BigDecimal counted, BigDecimal variance, long actorId,
                            String notes) {
        return Sql.update(em,
                "UPDATE cash_sessions SET status = 'CLOSED', active_flag = NULL, "
                        + "expected_total = ?, counted_total = ?, variance = ?, "
                        + "closed_at = CURRENT_TIMESTAMP(6), closed_by = ?, notes = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6), updated_by = ?, version = version + 1 "
                        + "WHERE id = ? AND version = ? AND status = 'OPEN'",
                expected, counted, variance, actorId, notes, actorId, sessionId, version);
    }

    public List<Map<String, Object>> sessions(Long cashierId, String status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT cs.id, cs.public_id, cs.session_number, cs.opening_float, "
                        + "cs.expected_total, cs.counted_total, cs.variance, cs.currency, "
                        + "cs.status, cs.opened_at, cs.closed_at, cs.notes, cs.version, "
                        + "u.public_id AS cashier_public_id, u.display_name_ar AS cashier_ar, "
                        + "u.display_name_en AS cashier_en "
                        + "FROM cash_sessions cs JOIN users u ON u.id = cs.cashier_id WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (cashierId != null) {
            sql.append("AND cs.cashier_id = ? ");
            params.add(cashierId);
        }
        if (status != null && !status.isBlank()) {
            sql.append("AND cs.status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY cs.opened_at DESC, cs.id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public long countSessions(Long cashierId, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total FROM cash_sessions cs WHERE 1 = 1 ");
        List<Object> params = new ArrayList<>();
        if (cashierId != null) {
            sql.append("AND cs.cashier_id = ? ");
            params.add(cashierId);
        }
        if (status != null && !status.isBlank()) {
            sql.append("AND cs.status = ? ");
            params.add(status);
        }
        return Sql.longValue(Sql.one(em, sql.toString(), params.toArray()), "total", 0);
    }

    public void insertCashMovement(String publicId, long sessionId, String movementType,
                                   BigDecimal amount, String referenceType,
                                   String referencePublicId, String reason, long actorId) {
        Sql.insert(em,
                "INSERT INTO cash_movements (public_id, cash_session_id, movement_type, amount, "
                        + "reference_type, reference_public_id, reason, performed_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                publicId, sessionId, movementType, amount, referenceType, referencePublicId,
                reason, actorId);
    }

    public Map<String, Object> sessionTotals(long sessionId) {
        return Sql.one(em,
                "SELECT "
                        + "COALESCE(SUM(CASE WHEN movement_type = 'PAYMENT' THEN amount END), 0) AS payments, "
                        + "COALESCE(SUM(CASE WHEN movement_type = 'REFUND' THEN amount END), 0) AS refunds, "
                        + "COALESCE(SUM(CASE WHEN movement_type = 'ADJUSTMENT' THEN amount END), 0) AS adjustments "
                        + "FROM cash_movements WHERE cash_session_id = ?", sessionId);
    }

    // ------------------------------------------------------------------
    // Reports / price list
    // ------------------------------------------------------------------

    public List<Map<String, Object>> dailyCollections(LocalDate date) {
        return Sql.query(em,
                "SELECT m.method, COALESCE(p.payment_count, 0) AS payment_count, "
                        + "COALESCE(p.collected, 0) AS collected, "
                        + "COALESCE(r.refunded, 0) AS refunded "
                        + "FROM ("
                        + "  SELECT method FROM payments "
                        + "  WHERE status = 'POSTED' AND DATE(received_at) = ? "
                        + "  UNION "
                        + "  SELECT method FROM refunds "
                        + "  WHERE status = 'POSTED' AND DATE(performed_at) = ?"
                        + ") m "
                        + "LEFT JOIN ("
                        + "  SELECT method, COUNT(*) AS payment_count, "
                        + "         COALESCE(SUM(amount), 0) AS collected "
                        + "  FROM payments WHERE status = 'POSTED' AND DATE(received_at) = ? "
                        + "  GROUP BY method"
                        + ") p ON p.method = m.method "
                        + "LEFT JOIN ("
                        + "  SELECT method, COALESCE(SUM(amount), 0) AS refunded "
                        + "  FROM refunds WHERE status = 'POSTED' AND DATE(performed_at) = ? "
                        + "  GROUP BY method"
                        + ") r ON r.method = m.method "
                        + "ORDER BY m.method",
                date, date, date, date);
    }

    public Map<String, Object> dailyTotals(LocalDate date) {
        return Sql.one(em,
                "SELECT "
                        + "(SELECT COALESCE(SUM(amount), 0) FROM payments "
                        + "WHERE status = 'POSTED' AND DATE(received_at) = ?) AS collected, "
                        + "(SELECT COALESCE(SUM(amount), 0) FROM refunds "
                        + "WHERE status = 'POSTED' AND DATE(performed_at) = ?) AS refunded, "
                        + "(SELECT COALESCE(SUM(amount), 0) FROM credit_notes "
                        + "WHERE status = 'ISSUED' AND DATE(issued_at) = ?) AS credited, "
                        + "(SELECT COUNT(*) FROM invoices WHERE DATE(issued_at) = ?) AS invoices, "
                        + "(SELECT COALESCE(SUM(balance), 0) FROM invoices "
                        + "WHERE status NOT IN ('VOID', 'CREDITED') AND DATE(issued_at) = ?) AS outstanding",
                date, date, date, date, date);
    }

    public List<Map<String, Object>> servicePrices(String query) {
        StringBuilder sql = new StringBuilder(
                "SELECT sp.public_id, sp.amount, sp.currency, sp.valid_from, sp.valid_to, "
                        + "s.public_id AS service_public_id, s.code, s.name_ar, s.name_en, "
                        + "s.category, s.taxable, s.active "
                        + "FROM service_prices sp JOIN service_catalog s ON s.id = sp.service_id "
                        + "WHERE sp.valid_to IS NULL OR sp.valid_to >= CURRENT_DATE ");
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append("AND (s.code LIKE ? OR s.name_ar LIKE ? OR s.name_en LIKE ?) ");
            String like = "%" + query.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append("ORDER BY s.code");
        return Sql.query(em, sql.toString(), params.toArray());
    }

    public Map<String, Object> serviceByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, code, name_ar, name_en, category FROM service_catalog "
                        + "WHERE public_id = ?", publicId);
    }

    public Map<String, Object> currentPrice(long serviceId) {
        return Sql.one(em,
                "SELECT amount, currency FROM service_prices WHERE service_id = ? "
                        + "AND valid_from <= CURRENT_DATE AND (valid_to IS NULL OR valid_to >= CURRENT_DATE) "
                        + "ORDER BY valid_from DESC, id DESC LIMIT 1", serviceId);
    }

    public long insertPrice(String publicId, long serviceId, BigDecimal amount, String currency,
                            LocalDate validFrom, long actorId) {
        return Sql.insert(em,
                "INSERT INTO service_prices (public_id, service_id, amount, currency, valid_from, "
                        + "created_by) VALUES (?, ?, ?, ?, ?, ?)",
                publicId, serviceId, amount, currency, validFrom, actorId);
    }

    public void closeCurrentPrice(long serviceId, LocalDate validTo) {
        Sql.update(em,
                "UPDATE service_prices SET valid_to = ? WHERE service_id = ? AND valid_to IS NULL",
                validTo, serviceId);
    }

    // ------------------------------------------------------------------
    // Idempotency keys
    // ------------------------------------------------------------------

    public Map<String, Object> idempotencyKey(long userId, String endpoint, String key) {
        return Sql.one(em,
                "SELECT id, user_id, endpoint, `key`, request_hash, response_json, status "
                        + "FROM idempotency_keys WHERE user_id = ? AND endpoint = ? AND `key` = ?",
                userId, endpoint, key);
    }

    public long insertIdempotencyKey(long userId, String endpoint, String key, String hash,
                                     int ttlHours) {
        return Sql.insert(em,
                "INSERT INTO idempotency_keys (user_id, endpoint, `key`, request_hash, status, "
                        + "expires_at) VALUES (?, ?, ?, ?, 'IN_PROGRESS', "
                        + "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? HOUR))",
                userId, endpoint, key, hash, ttlHours);
    }

    public void completeIdempotencyKey(long id, String responseJson) {
        Sql.update(em,
                "UPDATE idempotency_keys SET status = 'COMPLETED', response_json = ? WHERE id = ?",
                responseJson, id);
    }

    public void failIdempotencyKey(long id) {
        Sql.update(em, "UPDATE idempotency_keys SET status = 'FAILED' WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // Users / sequences
    // ------------------------------------------------------------------

    public Map<String, Object> userByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, username, display_name_ar, display_name_en, status "
                        + "FROM users WHERE public_id = ?", publicId);
    }

    public boolean userHasPermission(long userId, String permissionCode) {
        Map<String, Object> row = Sql.one(em,
                "SELECT COUNT(*) AS total FROM user_roles ur "
                        + "JOIN role_permissions rp ON rp.role_id = ur.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE ur.user_id = ? AND p.code = ?", userId, permissionCode);
        return Sql.longValue(row, "total", 0) > 0;
    }

    public int nextNumber(String sequence, String period) {
        return Sql.nextSequence(em, sequence, period);
    }

    public Map<String, Object> patientByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, medical_number, full_name, status FROM patients "
                        + "WHERE public_id = ?", publicId);
    }

    public Map<String, Object> encounterByPublicId(String publicId) {
        return Sql.one(em,
                "SELECT id, public_id, patient_id, status FROM encounters WHERE public_id = ?",
                publicId);
    }

    public void closeInvoicesForSession(long sessionId) {
        Sql.update(em,
                "UPDATE invoices SET closed_at = CURRENT_TIMESTAMP(6) "
                        + "WHERE cash_session_id = ? AND closed_at IS NULL", sessionId);
    }

    public List<Map<String, Object>> refundsForPayment(long paymentId) {
        return Sql.query(em,
                "SELECT public_id, refund_number, amount, method, reason, status, performed_at "
                        + "FROM refunds WHERE payment_id = ? ORDER BY performed_at, id", paymentId);
    }
}
