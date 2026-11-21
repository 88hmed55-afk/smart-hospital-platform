package com.smarthospital.clinical.billing;

import com.smarthospital.platform.iam.AuthPrincipal;

import java.time.LocalDate;
import java.util.Map;

/** Local view of the billing service (FR-BIL-001..010). */
public interface BillingServiceLocal {

    Map<String, Object> listInvoices(String query, String status, String patientPublicId,
                                     LocalDate from, LocalDate to, int page, int size);

    Map<String, Object> invoice(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> createInvoice(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> applyDiscount(String publicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip);

    Map<String, Object> voidInvoice(String publicId, Map<String, Object> body,
                                    AuthPrincipal actor, String ip);

    Map<String, Object> addPayment(String invoicePublicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> paymentReceipt(String paymentPublicId, AuthPrincipal actor, String ip);

    Map<String, Object> refundPayment(String paymentPublicId, Map<String, Object> body,
                                      AuthPrincipal actor, String ip);

    Map<String, Object> createCreditNote(String invoicePublicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip);

    Map<String, Object> currentCashSession(AuthPrincipal actor);

    Map<String, Object> openCashSession(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> closeCashSession(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip);

    Map<String, Object> listCashSessions(String status, int page, int size, AuthPrincipal actor);

    Map<String, Object> dailyReport(LocalDate date);

    Map<String, Object> priceList(String query);

    Map<String, Object> createPrice(Map<String, Object> body, AuthPrincipal actor, String ip);
}
