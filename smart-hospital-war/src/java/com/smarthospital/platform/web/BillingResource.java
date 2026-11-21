package com.smarthospital.platform.web;

import com.smarthospital.clinical.billing.BillingServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Billing: invoices, payments, refunds, credit notes, cash sessions (FR-BIL-001..010). */
@Path("/billing")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class BillingResource {

    @Inject
    private BillingServiceLocal billing;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Invoices
    // ------------------------------------------------------------------

    @GET
    @Path("/invoices")
    public Map<String, Object> invoices(@QueryParam("q") String query,
                                        @QueryParam("status") String status,
                                        @QueryParam("patient") String patient,
                                        @QueryParam("from") String from,
                                        @QueryParam("to") String to,
                                        @QueryParam("page") @DefaultValue("0") int page,
                                        @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "BIL:READ");
        return billing.listInvoices(query, status, patient, date(from), date(to), page, size);
    }

    @GET
    @Path("/invoices/{publicId}")
    public Map<String, Object> invoice(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:READ");
        return billing.invoice(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/invoices")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createInvoice(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:CREATE");
        Map<String, Object> created = billing.createInvoice(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/billing/invoices/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/invoices/{publicId}/discount")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> discount(@PathParam("publicId") String publicId,
                                        Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "BIL:DISCOUNT", "BIL:DISCOUNT_LIMITED");
        return billing.applyDiscount(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/invoices/{publicId}/void")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> voidInvoice(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:VOID");
        return billing.voidInvoice(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/invoices/{publicId}/payments")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addPayment(@PathParam("publicId") String publicId,
                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:PAYMENT");
        Map<String, Object> payment = billing.addPayment(publicId, body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/billing/payments/" + payment.get("publicId"))
                .entity(payment)
                .build();
    }

    @POST
    @Path("/invoices/{publicId}/credit-notes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response creditNote(@PathParam("publicId") String publicId,
                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:VOID");
        Map<String, Object> created = billing.createCreditNote(publicId, body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    // ------------------------------------------------------------------
    // Payments, receipts and refunds
    // ------------------------------------------------------------------

    @GET
    @Path("/payments/{publicId}/receipt")
    public Map<String, Object> receipt(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "BIL:PRINT", "BIL:READ");
        return billing.paymentReceipt(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/payments/{publicId}/refunds")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response refund(@PathParam("publicId") String publicId,
                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:REFUND");
        Map<String, Object> created = billing.refundPayment(publicId, body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    // ------------------------------------------------------------------
    // Cash sessions
    // ------------------------------------------------------------------

    @GET
    @Path("/cash-sessions")
    public Map<String, Object> cashSessions(@QueryParam("status") String status,
                                            @QueryParam("page") @DefaultValue("0") int page,
                                            @QueryParam("size") @DefaultValue("20") int size) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:CASH_SESSION");
        return billing.listCashSessions(status, page, size, actor);
    }

    @GET
    @Path("/cash-sessions/current")
    public Map<String, Object> currentCashSession() {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:CASH_SESSION");
        return billing.currentCashSession(actor);
    }

    @POST
    @Path("/cash-sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response openCashSession(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:CASH_SESSION");
        Map<String, Object> created = billing.openCashSession(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/billing/cash-sessions/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/cash-sessions/{publicId}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> closeCashSession(@PathParam("publicId") String publicId,
                                                Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:CASH_SESSION");
        return billing.closeCashSession(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Reports and price list
    // ------------------------------------------------------------------

    @GET
    @Path("/reports/daily")
    public Map<String, Object> dailyReport(@QueryParam("date") String date) {
        authz.require(principal(), "BIL:READ");
        return billing.dailyReport(date(date));
    }

    @GET
    @Path("/service-prices")
    public Map<String, Object> priceList(@QueryParam("q") String query) {
        authz.require(principal(), "BIL:READ");
        return billing.priceList(query);
    }

    @POST
    @Path("/service-prices")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPrice(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "BIL:PRICE_LIST");
        Map<String, Object> created = billing.createPrice(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    private AuthPrincipal principal() {
        Object value = request.getAttribute(WebCookies.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthPrincipal principal) {
            return principal;
        }
        throw AppException.unauthorized("AUTH_REQUIRED",
                "يلزم تسجيل الدخول.",
                "Authentication required.");
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة التاريخ غير صحيحة. استخدم YYYY-MM-DD.",
                    "Invalid date format. Use YYYY-MM-DD.");
        }
    }
}
