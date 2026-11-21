package com.smarthospital.platform.web;

import com.smarthospital.clinical.pharmacy.PharmacyServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** Dispensing transactions and returns (FR-PHR-007, FR-PHR-009). */
@Path("/dispensings")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class DispensingResource {

    @Inject
    private PharmacyServiceLocal pharmacy;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/{publicId}")
    public Map<String, Object> get(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:READ");
        return pharmacy.dispensing(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/return")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> returnItems(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:RETURN");
        return pharmacy.returnDispensing(publicId, body, actor, WebCookies.clientIp(request));
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
