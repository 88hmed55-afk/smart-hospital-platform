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

/** Pharmacy drug catalog and dispensing queue (FR-PHR-001, FR-PHR-005). */
@Path("/pharmacy")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PharmacyResource {

    @Inject
    private PharmacyServiceLocal pharmacy;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/drugs")
    public Map<String, Object> drugs(@QueryParam("q") String query,
                                     @QueryParam("activeOnly") @DefaultValue("true")
                                     boolean activeOnly,
                                     @QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "PHR:READ");
        return pharmacy.drugs(query, activeOnly, page, size);
    }

    @POST
    @Path("/drugs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createDrug(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:WRITE");
        Map<String, Object> created = pharmacy.createDrug(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/pharmacy/drugs/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/queue")
    public Map<String, Object> queue(@QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "PHR:READ");
        return pharmacy.queue(page, size);
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
