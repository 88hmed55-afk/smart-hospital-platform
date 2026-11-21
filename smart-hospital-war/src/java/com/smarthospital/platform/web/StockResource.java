package com.smarthospital.platform.web;

import com.smarthospital.clinical.pharmacy.PharmacyServiceLocal;
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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Stock batches, movements and low-stock/expiry alerts (FR-PHR-002, FR-PHR-003). */
@Path("/stock")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class StockResource {

    @Inject
    private PharmacyServiceLocal pharmacy;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/batches")
    public Map<String, Object> batches(@QueryParam("drug") String drug,
                                       @QueryParam("status") String status,
                                       @QueryParam("q") String query,
                                       @QueryParam("page") @DefaultValue("0") int page,
                                       @QueryParam("size") @DefaultValue("20") int size) {
        authz.requireAny(principal(), "PHR:BATCH_READ", "PHR:READ");
        return pharmacy.batches(drug, status, query, page, size);
    }

    @POST
    @Path("/batches")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receive(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:RECEIVE");
        Map<String, Object> created = pharmacy.receiveBatch(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/stock/batches/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/movements")
    public Map<String, Object> movements(@QueryParam("drug") String drug,
                                         @QueryParam("batch") String batch,
                                         @QueryParam("type") String type,
                                         @QueryParam("date") String date,
                                         @QueryParam("page") @DefaultValue("0") int page,
                                         @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "PHR:READ");
        return pharmacy.stockMovements(drug, batch, type, date, page, size);
    }

    @GET
    @Path("/alerts")
    public Map<String, Object> alerts(@QueryParam("limit") @DefaultValue("50") int limit) {
        authz.require(principal(), "PHR:READ");
        return pharmacy.stockAlerts(limit);
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
