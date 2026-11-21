package com.smarthospital.platform.web;

import com.smarthospital.clinical.lab.LabServiceLocal;
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

import java.util.List;
import java.util.Map;

/** Laboratory catalog, orders, specimens, results and reports (FR-LAB). */
@Path("/lab")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class LabResource {

    @Inject
    private LabServiceLocal lab;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    @GET
    @Path("/tests")
    public List<Map<String, Object>> tests(@QueryParam("q") String query,
                                           @QueryParam("activeOnly") @DefaultValue("true")
                                           boolean activeOnly) {
        authz.require(principal(), "LAB:READ");
        return lab.tests(query, activeOnly);
    }

    @POST
    @Path("/tests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTest(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:WRITE", "LAB:CATALOG");
        return Response.status(Response.Status.CREATED)
                .entity(lab.createTest(body, actor, WebCookies.clientIp(request)))
                .build();
    }

    // ------------------------------------------------------------------
    // Orders and specimens
    // ------------------------------------------------------------------

    @GET
    @Path("/orders")
    public Map<String, Object> orders(@QueryParam("status") String status,
                                      @QueryParam("priority") String priority,
                                      @QueryParam("patient") String patient,
                                      @QueryParam("q") String query,
                                      @QueryParam("date") String date,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "LAB:READ");
        return lab.searchOrders(status, priority, patient, query, date, page, size);
    }

    @POST
    @Path("/orders")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createOrder(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:ORDER");
        Map<String, Object> created = lab.createOrder(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/lab/orders/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/orders/{publicId}")
    public Map<String, Object> order(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:READ");
        return lab.order(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/orders/{publicId}/collect")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> collect(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:COLLECT");
        return lab.collect(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/orders/{publicId}/process")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> process(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:PROCESS");
        return lab.startProcessing(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/orders/{publicId}/result")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> enterResult(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:RESULT_ENTER", "LAB:RESULT");
        return lab.enterResult(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/specimens/{publicId}/receive")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> receiveSpecimen(@PathParam("publicId") String publicId,
                                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:RECEIVE");
        return lab.receiveSpecimen(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/specimens/{publicId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> rejectSpecimen(@PathParam("publicId") String publicId,
                                              Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:RECEIVE");
        return lab.rejectSpecimen(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    @POST
    @Path("/results/{publicId}/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> verify(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "LAB:VERIFY");
        return lab.verify(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/results/{publicId}/release")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> release(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:REPORT_RELEASE", "LAB:VERIFY");
        return lab.release(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/results/{publicId}/amend")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> amend(@PathParam("publicId") String publicId,
                                     Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:AMEND", "LAB:VERIFY");
        return lab.amend(publicId, body, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/results/{publicId}/report")
    public Map<String, Object> resultReport(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:PRINT", "LAB:READ");
        return lab.report(publicId, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/orders/{publicId}/report")
    public Map<String, Object> orderReport(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:PRINT", "LAB:READ");
        return lab.orderReport(publicId, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Worklist and critical alerts
    // ------------------------------------------------------------------

    @GET
    @Path("/worklist")
    public Map<String, Object> worklist(@QueryParam("priority") String priority,
                                        @QueryParam("page") @DefaultValue("0") int page,
                                        @QueryParam("size") @DefaultValue("25") int size) {
        authz.require(principal(), "LAB:READ");
        return lab.worklist(priority, page, size);
    }

    @GET
    @Path("/alerts")
    public Map<String, Object> alerts(@QueryParam("status") String status,
                                      @QueryParam("limit") @DefaultValue("50") int limit) {
        authz.require(principal(), "LAB:READ");
        return lab.alerts(status, limit);
    }

    @POST
    @Path("/alerts/{publicId}/acknowledge")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> acknowledge(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "LAB:VERIFY", "LAB:RESULT");
        return lab.acknowledgeAlert(publicId, body, actor, WebCookies.clientIp(request));
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
}
