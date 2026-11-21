package com.smarthospital.clinical.billing;

import com.smarthospital.clinical.access.AccessLogServiceLocal;
import com.smarthospital.platform.audit.AuditServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.notify.OutboxServiceLocal;
import com.smarthospital.platform.settings.SettingsRepository;
import com.smarthospital.shared.clinical.ClinicalSupport;
import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.id.Ulid;
import com.smarthospital.shared.sql.Sql;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Billing: charge capture, invoices, payments, refunds, credit notes and cash
 * sessions (FR-BIL-001..010).
 *
 * <p>Money invariants:
 * <ul>
 *   <li>An invoice item always references a {@code service_execution} (ADR-016).</li>
 *   <li>Prices are snapshotted on the execution and never re-read at payment time.</li>
 *   <li>Totals are derived; the client can never post a grand total.</li>
 *   <li>Balance = grand − credited − paid + refunded, clamped at zero.</li>
 *   <li>Payments are idempotent through {@code idempotency_keys} plus a unique
 *       backstop on {@code payments.idempotency_key}.</li>
 *   <li>A closed cash session freezes its invoices (409 {@code INVOICE_CLOSED}).</li>
 * </ul>
 */
@Stateless
public class BillingService implements BillingServiceLocal {

    private static final Set<String> PAYMENT_METHODS =
            Set.of("CASH", "CARD", "TRANSFER", "INSURANCE", "OTHER");
    private static final Set<String> PAYABLE_STATUSES =
            Set.of("ISSUED", "PARTIALLY_PAID");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    @EJB
    private BillingRepository repository;

    @EJB
    private AuditServiceLocal audit;

    @EJB
    private OutboxServiceLocal outbox;

    @EJB
    private SettingsRepository settings;

    @EJB
    private AccessLogServiceLocal accessLog;

    // ------------------------------------------------------------------
    // Invoices
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> listInvoices(String query, String status, String patientPublicId,
                                            LocalDate from, LocalDate to, int page, int size) {
        String normalized = normalizeStatus(status);
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        long total = repository.countInvoices(query, normalized, patientPublicId, from, to);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.invoices(query, normalized, patientPublicId,
                from, to, safePage * safeSize, safeSize)) {
            items.add(invoiceView(row, false));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> invoice(String publicId, AuthPrincipal actor, String ip) {
        Map<String, Object> row = repository.invoiceByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("INVOICE_NOT_FOUND",
                    "الفاتورة غير موجودة.", "Invoice not found.");
        }
        accessLog.record(actor.getUserId(), Sql.longValue(row, "patientId", 0),
                "INVOICE", publicId, "READ", ip);
        return invoiceView(row, true);
    }

    @Override
    public Map<String, Object> createInvoice(Map<String, Object> body, AuthPrincipal actor,
                                             String ip) {
        String patientPublicId = ClinicalSupport.required(body, "patientPublicId",
                "المريض مطلوب.", "Patient is required.");
        Map<String, Object> patient = repository.patientByPublicId(patientPublicId);
        if (patient == null) {
            throw AppException.notFound("PATIENT_NOT_FOUND",
                    "المريض غير موجود.", "Patient not found.");
        }
        long patientId = Sql.longValue(patient, "id", 0);

        String encounterPublicId = ClinicalSupport.optional(body, "encounterPublicId");
        Long encounterId = null;
        if (encounterPublicId != null) {
            Map<String, Object> encounter = repository.encounterByPublicId(encounterPublicId);
            if (encounter == null) {
                throw AppException.notFound("ENCOUNTER_NOT_FOUND",
                        "الزيارة غير موجودة.", "Encounter not found.");
            }
            if (Sql.longValue(encounter, "patientId", 0) != patientId) {
                throw AppException.unprocessable("ENCOUNTER_PATIENT_MISMATCH",
                        "الزيارة لا تخص هذا المريض.",
                        "The encounter does not belong to this patient.");
            }
            encounterId = Sql.longValue(encounter, "id", 0);
        }

        List<String> requestedItems = new ArrayList<>();
        Object rawItems = body == null ? null : body.get("itemPublicIds");
        if (rawItems instanceof List<?> list) {
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) {
                    requestedItems.add(value.toString().trim());
                }
            }
        }

        List<Map<String, Object>> executions =
                repository.capturableExecutions(patientId, encounterId, requestedItems);
        if (executions.isEmpty()) {
            throw AppException.unprocessable("NO_CHARGES",
                    "لا توجد خدمات منفذة غير مفوترة لهذا المريض.",
                    "There are no unbilled executed services for this patient.");
        }
        if (!requestedItems.isEmpty() && executions.size() != requestedItems.size()) {
            throw AppException.unprocessable("CHARGE_NOT_BILLABLE",
                    "بعض الخدمات المحددة غير قابلة للفوترة أو سبق تفويتها.",
                    "Some selected charges are not billable or were already invoiced.");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map<String, Object> execution : executions) {
            BigDecimal price = Sql.decimal(execution, "priceSnapshot");
            if (price == null) {
                price = BigDecimal.ZERO;
            }
            subtotal = subtotal.add(price);
        }
        subtotal = money(subtotal);

        int taxPercent = intSetting("billing.tax.percent", 0);
        BigDecimal tax = money(subtotal.multiply(BigDecimal.valueOf(taxPercent))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal grand = money(subtotal.add(tax));
        String currency = setting("billing.currency", "JOD");
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);
        LocalDate dueDate = ClinicalSupport.date(body, "dueDate", false);
        if (dueDate == null) {
            dueDate = LocalDate.now().plusDays(intSetting("billing.invoice.due_days", 7));
        }

        String publicId = Ulid.next();
        String number = number("INVOICE", "INV");
        long invoiceId = repository.insertInvoice(publicId, number, patientId, encounterId,
                "ISSUED", subtotal, BigDecimal.ZERO, tax, grand, currency, notes, dueDate,
                actor.getUserId());

        List<String> itemPublicIds = new ArrayList<>();
        for (Map<String, Object> execution : executions) {
            long executionId = Sql.longValue(execution, "id", 0);
            long serviceId = Sql.longValue(execution, "serviceId", 0);
            BigDecimal price = Sql.decimal(execution, "priceSnapshot");
            if (price == null) {
                price = BigDecimal.ZERO;
            }
            String itemPublicId = Ulid.next();
            itemPublicIds.add(itemPublicId);
            repository.insertInvoiceItem(itemPublicId, invoiceId, executionId, serviceId,
                    Sql.text(execution, "nameAr"), Sql.text(execution, "nameEn"), 1,
                    money(price), money(price));
            repository.markExecutionBilled(executionId);
        }

        audit.record(actor.getUserId(), "INVOICE_ISSUED", "INVOICE", publicId, null,
                ClinicalSupport.json("invoiceNumber", number, "patient", patientPublicId,
                        "encounter", encounterPublicId, "subtotal", subtotal,
                        "tax", tax, "grandTotal", grand, "currency", currency,
                        "items", itemPublicIds.size()), null, ip);

        return invoiceView(repository.invoiceById(invoiceId), true);
    }

    @Override
    public Map<String, Object> applyDiscount(String publicId, Map<String, Object> body,
                                             AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireInvoice(publicId);
        if (!PAYABLE_STATUSES.contains(Sql.text(current, "status"))) {
            throw AppException.conflict("INVOICE_NOT_DISCOUNTABLE",
                    "لا يمكن تعديل خصم فاتورة في حالتها الحالية.",
                    "A discount cannot be applied to the invoice in its current state.");
        }
        if (isClosed(current)) {
            audit.recordRejection(actor.getUserId(), "INVOICE_EDIT_REJECTED", "INVOICE",
                    publicId, "discount attempted on closed invoice", ip);
            throw AppException.conflict("INVOICE_CLOSED",
                    "الفاتورة مقفلة ضمن وردية مغلقة. صحّحها بإشعار دائن.",
                    "The invoice belongs to a closed cash session. Use a credit note.");
        }
        if (Sql.decimal(current, "paidTotal").signum() > 0) {
            throw AppException.conflict("INVOICE_HAS_PAYMENTS",
                    "لا يمكن تعديل خصم فاتورة عليها دفعات.",
                    "A discount cannot change an invoice that already has payments.");
        }
        long invoiceId = Sql.longValue(current, "id", 0);
        Map<String, Object> locked = repository.lockInvoice(invoiceId);
        long version = Sql.longValue(locked, "version", 0);
        if (body != null && body.get("version") != null
                && ClinicalSupport.optionalLong(body, "version") != null
                && ClinicalSupport.optionalLong(body, "version") != version) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                    "The invoice changed by another user. Reload and retry.");
        }

        BigDecimal subtotal = Sql.decimal(locked, "subtotal");
        BigDecimal discount = BigDecimal.ZERO;
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الخصم مطلوب.", "A discount reason is required.");
        Long percentValue = ClinicalSupport.optionalLong(body, "percent");
        BigDecimal amountValue = decimal(body, "amount");
        boolean fullDiscount = actor.hasPermission("BIL:DISCOUNT");
        int maxPercent = intSetting("billing.discount.max_percent", 20);

        if (percentValue != null) {
            if (percentValue < 0 || percentValue > 100) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "نسبة الخصم يجب أن تكون بين 0 و100.",
                        "The discount percent must be between 0 and 100.");
            }
            if (!fullDiscount && percentValue > maxPercent) {
                throw AppException.unprocessable("DISCOUNT_LIMIT_EXCEEDED",
                        "نسبة الخصم تتجاوز الحد المسموح (" + maxPercent + "%).",
                        "The discount exceeds the allowed limit (" + maxPercent + "%).");
            }
            discount = money(subtotal.multiply(BigDecimal.valueOf(percentValue))
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        } else if (amountValue != null) {
            if (amountValue.signum() < 0) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "قيمة الخصم لا يمكن أن تكون سالبة.",
                        "The discount amount cannot be negative.");
            }
            if (!fullDiscount && subtotal.signum() > 0) {
                BigDecimal percent = amountValue.multiply(BigDecimal.valueOf(100))
                        .divide(subtotal, 4, RoundingMode.HALF_UP);
                if (percent.compareTo(BigDecimal.valueOf(maxPercent)) > 0) {
                    throw AppException.unprocessable("DISCOUNT_LIMIT_EXCEEDED",
                            "قيمة الخصم تتجاوز الحد المسموح (" + maxPercent + "%).",
                            "The discount exceeds the allowed limit (" + maxPercent + "%).");
                }
            }
            discount = money(amountValue);
        } else {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "حدد نسبة الخصم أو قيمته.", "Provide a discount percent or amount.");
        }
        if (discount.compareTo(subtotal) > 0) {
            throw AppException.unprocessable("DISCOUNT_EXCEEDS_SUBTOTAL",
                    "الخصم لا يمكن أن يتجاوز إجمالي البنود.",
                    "The discount cannot exceed the line subtotal.");
        }

        int taxPercent = intSetting("billing.tax.percent", 0);
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal tax = money(taxable.multiply(BigDecimal.valueOf(taxPercent))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal grand = money(taxable.add(tax));

        if (repository.updateDiscount(invoiceId, version, discount, tax, grand, reason,
                actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                    "The invoice changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "INVOICE_DISCOUNTED", "INVOICE", publicId,
                ClinicalSupport.json("grandTotal", Sql.decimal(locked, "grandTotal")),
                ClinicalSupport.json("discount", discount, "tax", tax, "grandTotal", grand,
                        "reason", reason), reason, ip);
        return invoiceView(repository.invoiceById(invoiceId), true);
    }

    @Override
    public Map<String, Object> voidInvoice(String publicId, Map<String, Object> body,
                                           AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireInvoice(publicId);
        if ("VOID".equals(Sql.text(current, "status"))) {
            throw AppException.conflict("INVOICE_ALREADY_VOID",
                    "الفاتورة ملغاة مسبقًا.", "The invoice is already void.");
        }
        if (isClosed(current)) {
            audit.recordRejection(actor.getUserId(), "INVOICE_EDIT_REJECTED", "INVOICE",
                    publicId, "void attempted on closed invoice", ip);
            throw AppException.conflict("INVOICE_CLOSED",
                    "الفاتورة مقفلة ضمن وردية مغلقة. صحّحها بإشعار دائن.",
                    "The invoice belongs to a closed cash session. Use a credit note.");
        }
        if (Sql.decimal(current, "paidTotal").signum() > 0
                || Sql.decimal(current, "refundedTotal").signum() > 0
                || Sql.decimal(current, "creditedTotal").signum() > 0) {
            audit.recordRejection(actor.getUserId(), "INVOICE_VOID_REJECTED", "INVOICE",
                    publicId, "void requested on invoice with financial movements", ip);
            throw AppException.conflict("INVOICE_HAS_PAYMENTS",
                    "لا يمكن إلغاء فاتورة عليها دفعات. استخدم إشعارًا دائنًا واستردادًا.",
                    "An invoice with payments cannot be voided. Use a credit note/refund.");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الإلغاء مطلوب.", "A void reason is required.");
        long invoiceId = Sql.longValue(current, "id", 0);
        long version = Sql.longValue(current, "version", 0);
        if (repository.voidInvoice(invoiceId, version, reason, actor.getUserId()) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                    "The invoice changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "INVOICE_VOIDED", "INVOICE", publicId,
                ClinicalSupport.json("status", Sql.text(current, "status")),
                ClinicalSupport.json("status", "VOID", "reason", reason), reason, ip);
        return invoiceView(repository.invoiceById(invoiceId), true);
    }

    // ------------------------------------------------------------------
    // Payments
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> addPayment(String invoicePublicId, Map<String, Object> body,
                                          AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireInvoice(invoicePublicId);
        long invoiceId = Sql.longValue(current, "id", 0);
        Map<String, Object> invoice = repository.lockInvoice(invoiceId);
        if (!PAYABLE_STATUSES.contains(Sql.text(invoice, "status"))) {
            if ("PAID".equals(Sql.text(invoice, "status"))) {
                throw AppException.unprocessable("INVOICE_ALREADY_PAID",
                        "لا يوجد رصيد متبقٍ على الفاتورة.",
                        "The invoice has no outstanding balance.");
            }
            throw AppException.conflict("INVOICE_NOT_PAYABLE",
                    "لا يمكن الدفع لفاتورة في حالتها الحالية.",
                    "The invoice cannot accept a payment in its current state.");
        }
        if (isClosed(invoice)) {
            audit.recordRejection(actor.getUserId(), "INVOICE_EDIT_REJECTED", "INVOICE",
                    invoicePublicId, "payment attempted on closed invoice", ip);
            throw AppException.conflict("INVOICE_CLOSED",
                    "الفاتورة مقفلة ضمن وردية مغلقة. لا يمكن إضافة دفعة.",
                    "The invoice belongs to a closed cash session and cannot be paid.");
        }

        BigDecimal amount = decimal(body, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مبلغ الدفعة يجب أن يكون أكبر من صفر.",
                    "The payment amount must be greater than zero.");
        }
        amount = money(amount);
        String method = ClinicalSupport.optional(body, "method");
        method = method == null ? "CASH" : method.toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "طريقة الدفع غير صحيحة.", "Invalid payment method.");
        }
        String reference = ClinicalSupport.optionalTrim(body, "reference", 120);
        String notes = ClinicalSupport.optionalTrim(body, "notes", 500);
        BigDecimal balance = Sql.decimal(invoice, "balance");
        if (balance.signum() <= 0) {
            throw AppException.unprocessable("INVOICE_ALREADY_PAID",
                    "لا يوجد رصيد متبقٍ على الفاتورة.",
                    "The invoice has no outstanding balance.");
        }
        if (amount.compareTo(balance) > 0) {
            throw AppException.unprocessable("PAYMENT_EXCEEDS_BALANCE",
                    "مبلغ الدفعة يتجاوز الرصيد المتبقي (" + balance + ").",
                    "The payment exceeds the outstanding balance (" + balance + ").");
        }

        String idempotencyKey = ClinicalSupport.required(body, "idempotencyKey",
                "مفتاح منع التكرار مطلوب.", "An idempotency key is required.");
        if (idempotencyKey.length() > 120) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "مفتاح منع التكرار أطول من الحد المسموح.",
                    "The idempotency key exceeds 120 characters.");
        }
        String requestHash = hash(invoicePublicId + "|" + amount.toPlainString() + "|"
                + method + "|" + (reference == null ? "" : reference));
        Map<String, Object> existingKey = repository.idempotencyKey(actor.getUserId(),
                "billing.payment", idempotencyKey);
        if (existingKey != null) {
            if (!requestHash.equals(Sql.text(existingKey, "requestHash"))) {
                throw AppException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "مفتاح منع التكرار مستخدم لطلب مختلف.",
                        "The idempotency key was already used for a different request.");
            }
            Map<String, Object> existingPayment =
                    repository.paymentByIdempotencyKey(idempotencyKey);
            if (existingPayment != null) {
                return paymentView(repository.paymentByPublicId(
                        Sql.text(existingPayment, "publicId")), true);
            }
            throw AppException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "طلب سابق بالمفتاح نفسه قيد المعالجة.",
                    "A previous request with the same key is still in progress.");
        }

        Long sessionId = null;
        Map<String, Object> session = repository.currentSession(actor.getUserId());
        if (session != null && "OPEN".equals(Sql.text(session, "status"))) {
            sessionId = Sql.longValue(session, "id", 0);
        } else if ("CASH".equals(method) && boolSetting("billing.session.require_open", false)) {
            throw AppException.conflict("CASH_SESSION_REQUIRED",
                    "افتح وردية صندوق قبل تسجيل دفعة نقدية.",
                    "Open a cash session before recording a cash payment.");
        }

        long keyId;
        try {
            keyId = repository.insertIdempotencyKey(actor.getUserId(), "billing.payment",
                    idempotencyKey, requestHash, 24);
        } catch (RuntimeException ex) {
            // Another request with the same key won the race between the lookup
            // above and this insert. The unique constraint protects the effect.
            throw AppException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "طلب سابق بالمفتاح نفسه قيد المعالجة.",
                    "A previous request with the same key is still in progress.");
        }
        long patientId = Sql.longValue(invoice, "patientId", 0);
        String publicId = Ulid.next();
        String receiptNumber = number("RECEIPT", "PAY");
        BigDecimal paid = money(Sql.decimal(invoice, "paidTotal").add(amount));
        BigDecimal refunded = Sql.decimal(invoice, "refundedTotal");
        BigDecimal credited = Sql.decimal(invoice, "creditedTotal");
        String status = derivedStatus(Sql.decimal(invoice, "grandTotal"), paid, refunded, credited);

        try {
            long paymentId = repository.insertPayment(publicId, receiptNumber, invoiceId,
                    patientId, sessionId, amount, Sql.text(invoice, "currency"), method,
                    reference, idempotencyKey, notes, actor.getUserId());
            repository.insertAllocation(Ulid.next(), paymentId, invoiceId, amount);
            if (repository.updateTotals(invoiceId, Sql.longValue(invoice, "version", 0), paid,
                    refunded, credited, status, sessionId) == 0) {
                throw AppException.conflict("STALE_VERSION",
                        "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                        "The invoice changed by another user. Reload and retry.");
            }
            if (sessionId != null) {
                repository.insertCashMovement(Ulid.next(), sessionId, "PAYMENT", amount,
                        "PAYMENT", publicId, "Invoice " + Sql.text(invoice, "invoiceNumber"),
                        actor.getUserId());
            }
            audit.record(actor.getUserId(), "PAYMENT_RECEIVED", "INVOICE", invoicePublicId,
                    ClinicalSupport.json("paidTotal", Sql.decimal(invoice, "paidTotal"),
                            "balance", balance),
                    ClinicalSupport.json("payment", publicId, "receiptNumber", receiptNumber,
                            "amount", amount, "method", method, "paidTotal", paid,
                            "balance", Sql.decimal(invoice, "grandTotal").subtract(credited)
                                    .subtract(paid).add(refunded),
                            "status", status), null, ip);
            outbox.enqueue("PAYMENT_RECEIVED", "INVOICE", invoicePublicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "roles", List.of("FIN_MANAGER", "CASHIER"),
                            "type", "PAYMENT_RECEIVED",
                            "titleAr", "تم استلام دفعة",
                            "titleEn", "Payment received",
                            "bodyAr", "دفعة " + amount + " " + Sql.text(invoice, "currency")
                                    + " على الفاتورة " + Sql.text(invoice, "invoiceNumber"),
                            "bodyEn", "Payment of " + amount + " "
                                    + Sql.text(invoice, "currency") + " on invoice "
                                    + Sql.text(invoice, "invoiceNumber"),
                            "severity", "INFO",
                            "entityType", "INVOICE",
                            "entityPublicId", invoicePublicId,
                            "dedupeKey", "PAYMENT_RECEIVED:" + publicId))));
            repository.completeIdempotencyKey(keyId, ClinicalSupport.json(
                    "paymentPublicId", publicId, "receiptNumber", receiptNumber,
                    "amount", amount));
        } catch (RuntimeException ex) {
            repository.failIdempotencyKey(keyId);
            throw ex;
        }
        return paymentView(repository.paymentByPublicId(publicId), false);
    }

    @Override
    public Map<String, Object> paymentReceipt(String paymentPublicId, AuthPrincipal actor,
                                              String ip) {
        Map<String, Object> payment = repository.paymentByPublicId(paymentPublicId);
        if (payment == null) {
            throw AppException.notFound("PAYMENT_NOT_FOUND",
                    "الدفعة غير موجودة.", "Payment not found.");
        }
        long patientId = Sql.longValue(payment, "patientId", 0);
        accessLog.record(actor.getUserId(), patientId, "PAYMENT", paymentPublicId, "READ", ip);
        Map<String, Object> receipt = new LinkedHashMap<>(payment);
        receipt.put("refunds", repository.refundsForPayment(
                Sql.longValue(payment, "id", 0)));
        receipt.put("hospital", ClinicalSupport.map(
                "nameAr", settings.findValue("hospital.name_ar").orElse("المستشفى"),
                "nameEn", settings.findValue("hospital.name_en").orElse("Hospital"),
                "footerAr", settings.findValue("print.footer_ar").orElse(""),
                "currency", Sql.text(payment, "currency")));
        return receipt;
    }

    // ------------------------------------------------------------------
    // Refunds and credit notes
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> refundPayment(String paymentPublicId, Map<String, Object> body,
                                             AuthPrincipal actor, String ip) {
        Map<String, Object> payment = repository.paymentByPublicId(paymentPublicId);
        if (payment == null) {
            throw AppException.notFound("PAYMENT_NOT_FOUND",
                    "الدفعة غير موجودة.", "Payment not found.");
        }
        if (!"POSTED".equals(Sql.text(payment, "status"))) {
            throw AppException.conflict("PAYMENT_NOT_REFUNDABLE",
                    "لا يمكن استرداد دفعة ملغاة.", "A voided payment cannot be refunded.");
        }
        long paymentId = Sql.longValue(payment, "id", 0);
        long invoiceId = Sql.longValue(payment, "invoiceId", 0);
        Map<String, Object> invoice = repository.lockInvoice(invoiceId);
        if (isClosed(invoice)) {
            audit.recordRejection(actor.getUserId(), "INVOICE_EDIT_REJECTED", "INVOICE",
                    Sql.text(invoice, "publicId"), "refund attempted on closed invoice", ip);
            throw AppException.conflict("INVOICE_CLOSED",
                    "الفاتورة مقفلة ضمن وردية مغلقة. لا يمكن استرداد دفعة.",
                    "The invoice belongs to a closed cash session and cannot be refunded.");
        }
        BigDecimal amount = decimal(body, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مبلغ الاسترداد يجب أن يكون أكبر من صفر.",
                    "The refund amount must be greater than zero.");
        }
        amount = money(amount);
        BigDecimal alreadyRefunded = repository.refundedForPayment(paymentId);
        BigDecimal refundable = money(Sql.decimal(payment, "amount").subtract(alreadyRefunded));
        if (amount.compareTo(refundable) > 0) {
            throw AppException.unprocessable("REFUND_EXCEEDS_PAYMENT",
                    "مبلغ الاسترداد يتجاوز المبلغ القابل للاسترداد (" + refundable + ").",
                    "The refund exceeds the refundable amount (" + refundable + ").");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الاسترداد مطلوب.", "A refund reason is required.");
        String method = ClinicalSupport.optional(body, "method");
        method = method == null ? Sql.text(payment, "method") : method.toUpperCase();
        if (!PAYMENT_METHODS.contains(method)) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "طريقة الاسترداد غير صحيحة.", "Invalid refund method.");
        }

        long approvedBy = actor.getUserId();
        if (!actor.hasPermission("BIL:REFUND_APPROVE")) {
            String approverPublicId = ClinicalSupport.optional(body, "approvedByPublicId");
            if (approverPublicId == null) {
                throw AppException.forbidden("REFUND_APPROVAL_REQUIRED",
                        "الاسترداد يتطلب موافقة مسؤول مالي.",
                        "A refund requires finance-manager approval.");
            }
            Map<String, Object> approver = repository.userByPublicId(approverPublicId);
            if (approver == null || !"ACTIVE".equals(Sql.text(approver, "status"))) {
                throw AppException.unprocessable("APPROVER_NOT_FOUND",
                        "المستخدم المعتمد غير موجود أو غير مفعّل.",
                        "The approving user is missing or inactive.");
            }
            long approverId = Sql.longValue(approver, "id", 0);
            if (!repository.userHasPermission(approverId, "BIL:REFUND_APPROVE")) {
                throw AppException.forbidden("APPROVER_NOT_AUTHORIZED",
                        "المستخدم المحدد لا يملك صلاحية اعتماد الاسترداد.",
                        "The selected user cannot approve refunds.");
            }
            approvedBy = approverId;
        }

        Long sessionId = null;
        Map<String, Object> session = repository.currentSession(actor.getUserId());
        if (session != null && "OPEN".equals(Sql.text(session, "status"))) {
            sessionId = Sql.longValue(session, "id", 0);
        }

        String publicId = Ulid.next();
        String refundNumber = number("REFUND", "REF");
        repository.insertRefund(publicId, refundNumber, paymentId, invoiceId,
                Sql.longValue(payment, "patientId", 0), sessionId, amount,
                Sql.text(payment, "currency"), method, reason, approvedBy, actor.getUserId());

        BigDecimal refunded = money(Sql.decimal(invoice, "refundedTotal").add(amount));
        BigDecimal paid = Sql.decimal(invoice, "paidTotal");
        BigDecimal credited = Sql.decimal(invoice, "creditedTotal");
        String status = derivedStatus(Sql.decimal(invoice, "grandTotal"), paid, refunded, credited);
        if (repository.updateTotals(invoiceId, Sql.longValue(invoice, "version", 0), paid,
                refunded, credited, status, sessionId) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                    "The invoice changed by another user. Reload and retry.");
        }
        if (sessionId != null) {
            repository.insertCashMovement(Ulid.next(), sessionId, "REFUND", amount,
                    "REFUND", publicId, reason, actor.getUserId());
        }
        audit.record(actor.getUserId(), "PAYMENT_REFUNDED", "PAYMENT", paymentPublicId,
                ClinicalSupport.json("refundedTotal", Sql.decimal(invoice, "refundedTotal")),
                ClinicalSupport.json("refund", publicId, "refundNumber", refundNumber,
                        "amount", amount, "method", method, "reason", reason,
                        "approvedBy", approvedBy, "refundedTotal", refunded), reason, ip);
        outbox.enqueue("PAYMENT_REFUNDED", "INVOICE", Sql.text(invoice, "publicId"),
                ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                        "roles", List.of("FIN_MANAGER", "CASHIER"),
                        "type", "PAYMENT_REFUNDED",
                        "titleAr", "تم تنفيذ استرداد",
                        "titleEn", "Refund issued",
                        "bodyAr", "استرداد " + amount + " " + Sql.text(payment, "currency")
                                + " من الإيصال " + Sql.text(payment, "receiptNumber"),
                        "bodyEn", "Refund of " + amount + " " + Sql.text(payment, "currency")
                                + " for receipt " + Sql.text(payment, "receiptNumber"),
                        "severity", "WARNING",
                        "entityType", "INVOICE",
                        "entityPublicId", Sql.text(invoice, "publicId"),
                        "dedupeKey", "PAYMENT_REFUNDED:" + publicId))));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", publicId);
        view.put("refundNumber", refundNumber);
        view.put("amount", amount);
        view.put("currency", Sql.text(payment, "currency"));
        view.put("method", method);
        view.put("reason", reason);
        view.put("status", "POSTED");
        view.put("paymentPublicId", paymentPublicId);
        view.put("invoicePublicId", Sql.text(invoice, "publicId"));
        view.put("approvedBy", approvedBy);
        view.put("refundableRemaining", money(refundable.subtract(amount)));
        view.put("invoiceStatus", status);
        view.put("invoiceBalance", Sql.decimal(invoice, "grandTotal").subtract(credited)
                .subtract(paid).add(refunded).max(BigDecimal.ZERO));
        return view;
    }

    @Override
    public Map<String, Object> createCreditNote(String invoicePublicId,
                                                Map<String, Object> body,
                                                AuthPrincipal actor, String ip) {
        Map<String, Object> current = requireInvoice(invoicePublicId);
        long invoiceId = Sql.longValue(current, "id", 0);
        Map<String, Object> invoice = repository.lockInvoice(invoiceId);
        if ("VOID".equals(Sql.text(invoice, "status"))) {
            throw AppException.conflict("INVOICE_VOID",
                    "لا يمكن إصدار إشعار دائن لفاتورة ملغاة.",
                    "A credit note cannot be issued for a void invoice.");
        }
        if (isClosed(invoice)) {
            audit.recordRejection(actor.getUserId(), "INVOICE_EDIT_REJECTED", "INVOICE",
                    invoicePublicId, "credit note attempted on closed invoice", ip);
            throw AppException.conflict("INVOICE_CLOSED",
                    "الفاتورة مقفلة ضمن وردية مغلقة. صحّحها في فترة لاحقة.",
                    "The invoice belongs to a closed cash session. Correct it in a later period.");
        }
        BigDecimal amount = decimal(body, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "مبلغ الإشعار الدائن يجب أن يكون أكبر من صفر.",
                    "The credit note amount must be greater than zero.");
        }
        amount = money(amount);
        BigDecimal grand = Sql.decimal(invoice, "grandTotal");
        BigDecimal credited = Sql.decimal(invoice, "creditedTotal");
        BigDecimal creditable = money(grand.subtract(credited));
        if (amount.compareTo(creditable) > 0) {
            throw AppException.unprocessable("CREDIT_EXCEEDS_INVOICE",
                    "مبلغ الإشعار الدائن يتجاوز المبلغ القابل للخصم (" + creditable + ").",
                    "The credit note exceeds the creditable amount (" + creditable + ").");
        }
        String reason = ClinicalSupport.required(body, "reason",
                "سبب الإشعار الدائن مطلوب.", "A credit note reason is required.");
        String publicId = Ulid.next();
        String creditNumber = number("CREDIT_NOTE", "CRN");
        repository.insertCreditNote(publicId, creditNumber, invoiceId,
                Sql.longValue(invoice, "patientId", 0), amount, Sql.text(invoice, "currency"),
                reason, actor.getUserId());

        credited = money(credited.add(amount));
        BigDecimal paid = Sql.decimal(invoice, "paidTotal");
        BigDecimal refunded = Sql.decimal(invoice, "refundedTotal");
        String status = derivedStatus(grand, paid, refunded, credited);
        if (repository.updateTotals(invoiceId, Sql.longValue(invoice, "version", 0), paid,
                refunded, credited, status, null) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل الفاتورة من مستخدم آخر. أعد التحميل.",
                    "The invoice changed by another user. Reload and retry.");
        }
        audit.record(actor.getUserId(), "CREDIT_NOTE_ISSUED", "INVOICE", invoicePublicId,
                ClinicalSupport.json("creditedTotal", Sql.decimal(invoice, "creditedTotal")),
                ClinicalSupport.json("creditNote", publicId, "creditNumber", creditNumber,
                        "amount", amount, "reason", reason, "creditedTotal", credited,
                        "status", status), reason, ip);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", publicId);
        view.put("creditNumber", creditNumber);
        view.put("amount", amount);
        view.put("currency", Sql.text(invoice, "currency"));
        view.put("reason", reason);
        view.put("status", "ISSUED");
        view.put("invoicePublicId", invoicePublicId);
        view.put("invoiceStatus", status);
        view.put("creditableRemaining", money(creditable.subtract(amount)));
        return view;
    }

    // ------------------------------------------------------------------
    // Cash sessions
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> currentCashSession(AuthPrincipal actor) {
        Map<String, Object> session = repository.currentSession(actor.getUserId());
        return ClinicalSupport.map("session", session == null ? null : sessionView(session));
    }

    @Override
    public Map<String, Object> openCashSession(Map<String, Object> body, AuthPrincipal actor,
                                               String ip) {
        Map<String, Object> existing = repository.currentSession(actor.getUserId());
        if (existing != null) {
            throw AppException.conflict("CASH_SESSION_OPEN",
                    "لديك وردية صندوق مفتوحة بالفعل.",
                    "You already have an open cash session.");
        }
        BigDecimal openingFloat = decimal(body, "openingFloat");
        if (openingFloat == null) {
            openingFloat = BigDecimal.ZERO;
        }
        if (openingFloat.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "الرصيد الافتتاحي لا يمكن أن يكون سالبًا.",
                    "The opening float cannot be negative.");
        }
        openingFloat = money(openingFloat);
        String currency = setting("billing.currency", "JOD");
        String publicId = Ulid.next();
        String number = number("CASH_SESSION", "CSH");
        long id = repository.insertSession(publicId, number, actor.getUserId(), openingFloat,
                currency, actor.getUserId());
        if (openingFloat.signum() > 0) {
            repository.insertCashMovement(Ulid.next(), id, "OPENING", openingFloat,
                    "CASH_SESSION", publicId, "Opening float", actor.getUserId());
        }
        audit.record(actor.getUserId(), "CASH_SESSION_OPENED", "CASH_SESSION", publicId, null,
                ClinicalSupport.json("sessionNumber", number, "openingFloat", openingFloat,
                        "currency", currency), null, ip);
        return sessionView(repository.sessionByPublicId(publicId));
    }

    @Override
    public Map<String, Object> closeCashSession(String publicId, Map<String, Object> body,
                                                AuthPrincipal actor, String ip) {
        Map<String, Object> found = repository.sessionByPublicId(publicId);
        if (found == null) {
            throw AppException.notFound("CASH_SESSION_NOT_FOUND",
                    "وردية الصندوق غير موجودة.", "Cash session not found.");
        }
        long id = Sql.longValue(found, "id", 0);
        Map<String, Object> session = repository.lockSession(id);
        if (!"OPEN".equals(Sql.text(session, "status"))) {
            throw AppException.conflict("CASH_SESSION_CLOSED",
                    "وردية الصندوق مغلقة مسبقًا.", "The cash session is already closed.");
        }
        long cashierId = Sql.longValue(session, "cashierId", 0);
        if (cashierId != actor.getUserId() && !actor.hasPermission("BIL:CLOSE_PERIOD")) {
            throw AppException.forbidden("CASH_SESSION_NOT_OWNER",
                    "لا يمكنك إغلاق وردية صندوق مستخدم آخر.",
                    "You cannot close another user's cash session.");
        }
        BigDecimal counted = decimal(body, "countedTotal");
        if (counted == null || counted.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "المبلغ المعدود مطلوب ولا يمكن أن يكون سالبًا.",
                    "The counted total is required and cannot be negative.");
        }
        counted = money(counted);
        String notes = ClinicalSupport.optionalTrim(body, "notes", 1000);

        Map<String, Object> totals = repository.sessionTotals(id);
        BigDecimal payments = Sql.decimal(totals, "payments");
        BigDecimal refunds = Sql.decimal(totals, "refunds");
        BigDecimal adjustments = Sql.decimal(totals, "adjustments");
        BigDecimal expected = money(Sql.decimal(session, "openingFloat").add(payments)
                .subtract(refunds).add(adjustments));
        BigDecimal variance = money(counted.subtract(expected));

        if (repository.closeSession(id, Sql.longValue(session, "version", 0), expected, counted,
                variance, actor.getUserId(), notes) == 0) {
            throw AppException.conflict("STALE_VERSION",
                    "تم تعديل وردية الصندوق من مستخدم آخر. أعد التحميل.",
                    "The cash session changed by another user. Reload and retry.");
        }
        repository.closeInvoicesForSession(id);
        audit.record(actor.getUserId(), "CASH_SESSION_CLOSED", "CASH_SESSION", publicId,
                ClinicalSupport.json("status", "OPEN"),
                ClinicalSupport.json("expectedTotal", expected, "countedTotal", counted,
                        "variance", variance, "payments", payments, "refunds", refunds,
                        "adjustments", adjustments), notes, ip);
        if (variance.signum() != 0) {
            outbox.enqueue("CASH_VARIANCE", "CASH_SESSION", publicId,
                    ClinicalSupport.map("notifications", List.of(ClinicalSupport.map(
                            "roles", List.of("FIN_MANAGER"),
                            "type", "CASH_VARIANCE",
                            "titleAr", "فرق في إقفال الصندوق",
                            "titleEn", "Cash session variance",
                            "bodyAr", "فرق " + variance + " في الوردية "
                                    + Sql.text(session, "sessionNumber"),
                            "bodyEn", "Variance of " + variance + " in session "
                                    + Sql.text(session, "sessionNumber"),
                            "severity", "WARNING",
                            "entityType", "CASH_SESSION",
                            "entityPublicId", publicId,
                            "dedupeKey", "CASH_VARIANCE:" + publicId))));
        }
        return sessionView(repository.sessionByPublicId(publicId));
    }

    @Override
    public Map<String, Object> listCashSessions(String status, int page, int size,
                                                AuthPrincipal actor) {
        String normalized = normalizeStatus(status);
        int safePage = Math.max(0, page);
        int safeSize = ClinicalSupport.clampSize(size);
        boolean finance = actor.hasPermission("BIL:CLOSE_PERIOD");
        Long cashierId = finance ? null : actor.getUserId();
        long total = repository.countSessions(cashierId, normalized);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.sessions(cashierId, normalized,
                safePage * safeSize, safeSize)) {
            items.add(sessionView(row));
        }
        return ClinicalSupport.pageResult(items, safePage, safeSize, total);
    }

    // ------------------------------------------------------------------
    // Reports and price list
    // ------------------------------------------------------------------

    @Override
    public Map<String, Object> dailyReport(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<String, Object> totals = repository.dailyTotals(day);
        List<Map<String, Object>> collections = new ArrayList<>();
        for (Map<String, Object> row : repository.dailyCollections(day)) {
            BigDecimal collected = Sql.decimal(row, "collected");
            BigDecimal refunded = Sql.decimal(row, "refunded");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", Sql.text(row, "method"));
            item.put("paymentCount", Sql.longValue(row, "paymentCount", 0));
            item.put("collected", collected);
            item.put("refunded", refunded);
            item.put("net", money(collected.subtract(refunded)));
            collections.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("currency", setting("billing.currency", "JOD"));
        result.put("invoiceCount", Sql.longValue(totals, "invoices", 0));
        result.put("collected", Sql.decimal(totals, "collected"));
        result.put("refunded", Sql.decimal(totals, "refunded"));
        result.put("credited", Sql.decimal(totals, "credited"));
        result.put("net", money(Sql.decimal(totals, "collected")
                .subtract(Sql.decimal(totals, "refunded"))));
        result.put("outstanding", Sql.decimal(totals, "outstanding"));
        result.put("byMethod", collections);
        return result;
    }

    @Override
    public Map<String, Object> priceList(String query) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.servicePrices(query)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("publicId", row.get("publicId"));
            view.put("servicePublicId", row.get("servicePublicId"));
            view.put("code", row.get("code"));
            view.put("nameAr", row.get("nameAr"));
            view.put("nameEn", row.get("nameEn"));
            view.put("category", row.get("category"));
            view.put("taxable", row.get("taxable"));
            view.put("active", row.get("active"));
            view.put("amount", row.get("amount"));
            view.put("currency", row.get("currency"));
            view.put("validFrom", row.get("validFrom"));
            view.put("validTo", row.get("validTo"));
            items.add(view);
        }
        return ClinicalSupport.map("items", items, "total", items.size());
    }

    @Override
    public Map<String, Object> createPrice(Map<String, Object> body, AuthPrincipal actor,
                                           String ip) {
        String servicePublicId = ClinicalSupport.required(body, "servicePublicId",
                "الخدمة مطلوبة.", "Service is required.");
        Map<String, Object> service = repository.serviceByPublicId(servicePublicId);
        if (service == null) {
            throw AppException.notFound("SERVICE_NOT_FOUND",
                    "الخدمة غير موجودة.", "Service not found.");
        }
        BigDecimal amount = decimal(body, "amount");
        if (amount == null || amount.signum() < 0) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "السعر مطلوب ولا يمكن أن يكون سالبًا.",
                    "A non-negative price is required.");
        }
        amount = money(amount);
        String currency = ClinicalSupport.optional(body, "currency");
        currency = currency == null ? setting("billing.currency", "JOD") : currency.toUpperCase();
        LocalDate validFrom = ClinicalSupport.date(body, "validFrom", false);
        if (validFrom == null) {
            validFrom = LocalDate.now();
        }
        long serviceId = Sql.longValue(service, "id", 0);
        repository.closeCurrentPrice(serviceId, validFrom.minusDays(1));
        String publicId = Ulid.next();
        repository.insertPrice(publicId, serviceId, amount, currency, validFrom,
                actor.getUserId());
        audit.record(actor.getUserId(), "SERVICE_PRICE_SET", "SERVICE", servicePublicId, null,
                ClinicalSupport.json("amount", amount, "currency", currency,
                        "validFrom", validFrom.toString()), null, ip);
        return ClinicalSupport.map("publicId", publicId, "servicePublicId", servicePublicId,
                "amount", amount, "currency", currency, "validFrom", validFrom.toString());
    }

    // ------------------------------------------------------------------
    // Views and helpers
    // ------------------------------------------------------------------

    private Map<String, Object> invoiceView(Map<String, Object> row, boolean details) {
        long invoiceId = Sql.longValue(row, "id", 0);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("invoiceNumber", row.get("invoiceNumber"));
        view.put("status", row.get("status"));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("fullName")));
        view.put("encounterPublicId", row.get("encounterPublicId"));
        view.put("cashSession", row.get("sessionPublicId") == null ? null
                : ClinicalSupport.map("publicId", row.get("sessionPublicId"),
                        "status", row.get("sessionStatus")));
        view.put("subtotal", row.get("subtotal"));
        view.put("discountTotal", row.get("discountTotal"));
        view.put("taxTotal", row.get("taxTotal"));
        view.put("grandTotal", row.get("grandTotal"));
        view.put("paidTotal", row.get("paidTotal"));
        view.put("refundedTotal", row.get("refundedTotal"));
        view.put("creditedTotal", row.get("creditedTotal"));
        view.put("balance", row.get("balance"));
        view.put("currency", row.get("currency"));
        view.put("discountReason", row.get("discountReason"));
        view.put("notes", row.get("notes"));
        view.put("issuedAt", row.get("issuedAt"));
        view.put("dueDate", row.get("dueDate"));
        view.put("closedAt", row.get("closedAt"));
        view.put("voidedAt", row.get("voidedAt"));
        view.put("voidReason", row.get("voidReason"));
        view.put("version", row.get("version"));
        view.put("closed", isClosed(row));
        if (details) {
            view.put("items", repository.invoiceItems(invoiceId));
            view.put("payments", repository.paymentsForInvoice(invoiceId));
            view.put("refunds", repository.refundsForInvoice(invoiceId));
            view.put("creditNotes", repository.creditNotesForInvoice(invoiceId));
        }
        return view;
    }

    private Map<String, Object> paymentView(Map<String, Object> row, boolean replayed) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("receiptNumber", row.get("receiptNumber"));
        view.put("amount", row.get("amount"));
        view.put("currency", row.get("currency"));
        view.put("method", row.get("method"));
        view.put("reference", row.get("reference"));
        view.put("status", row.get("status"));
        view.put("notes", row.get("notes"));
        view.put("receivedAt", row.get("receivedAt"));
        view.put("invoice", ClinicalSupport.map(
                "publicId", row.get("invoicePublicId"),
                "invoiceNumber", row.get("invoiceNumber"),
                "grandTotal", row.get("grandTotal"),
                "balance", row.get("balance")));
        view.put("patient", ClinicalSupport.map(
                "publicId", row.get("patientPublicId"),
                "medicalNumber", row.get("medicalNumber"),
                "fullName", row.get("fullName")));
        view.put("cashSessionPublicId", row.get("sessionPublicId"));
        view.put("replayed", replayed);
        return view;
    }

    private Map<String, Object> sessionView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("publicId", row.get("publicId"));
        view.put("sessionNumber", row.get("sessionNumber"));
        view.put("cashierPublicId", row.get("cashierPublicId"));
        view.put("cashierNameAr", row.get("cashierAr"));
        view.put("cashierNameEn", row.get("cashierEn"));
        view.put("openingFloat", row.get("openingFloat"));
        view.put("expectedTotal", row.get("expectedTotal"));
        view.put("countedTotal", row.get("countedTotal"));
        view.put("variance", row.get("variance"));
        view.put("currency", row.get("currency"));
        view.put("status", row.get("status"));
        view.put("openedAt", row.get("openedAt"));
        view.put("closedAt", row.get("closedAt"));
        view.put("notes", row.get("notes"));
        view.put("version", row.get("version"));
        return view;
    }

    private Map<String, Object> requireInvoice(String publicId) {
        Map<String, Object> row = repository.invoiceByPublicId(publicId);
        if (row == null) {
            throw AppException.notFound("INVOICE_NOT_FOUND",
                    "الفاتورة غير موجودة.", "Invoice not found.");
        }
        return row;
    }

    private boolean isClosed(Map<String, Object> row) {
        if (row.get("closedAt") != null) {
            return true;
        }
        return "CLOSED".equals(Sql.text(row, "sessionStatus"));
    }

    private String derivedStatus(BigDecimal grand, BigDecimal paid, BigDecimal refunded,
                                 BigDecimal credited) {
        if (grand.signum() > 0 && credited.compareTo(grand) >= 0) {
            return "CREDITED";
        }
        BigDecimal balance = grand.subtract(credited).subtract(paid).add(refunded);
        if (balance.signum() <= 0) {
            return "PAID";
        }
        if (paid.subtract(refunded).signum() > 0) {
            return "PARTIALLY_PAID";
        }
        return "ISSUED";
    }

    private String number(String sequence, String prefix) {
        String period = LocalDate.now().format(DAY);
        int value = repository.nextNumber(sequence, period);
        return prefix + "-" + period + "-" + String.format(Locale.ROOT, "%04d", value);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        String value = ClinicalSupport.optional(body, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية غير صحيحة في الحقل " + key + ".",
                    "Invalid numeric value for " + key + ".");
        }
    }

    private String setting(String key, String fallback) {
        return settings.findValue(key).orElse(fallback);
    }

    private int intSetting(String key, int fallback) {
        return settings.findValue(key).map(value -> {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private boolean boolSetting(String key, boolean fallback) {
        return settings.findValue(key)
                .map(value -> "1".equals(value) || "true".equalsIgnoreCase(value))
                .orElse(fallback);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
