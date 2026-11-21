package com.smarthospital.platform.web;

import com.smarthospital.clinical.adt.AdtServiceLocal;
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

/** Bed board and housekeeping status changes (FR-ADT-003). */
@Path("/beds")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class BedResource {

    @Inject
    private AdtServiceLocal adt;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/board")
    public Map<String, Object> board() {
        authz.require(principal(), "ADT:READ");
        return adt.bedBoard();
    }

    @POST
    @Path("/{publicId}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> status(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "ADT:HOUSEKEEPING", "ADT:BED_STATUS");
        return adt.bedStatus(publicId, body, actor, WebCookies.clientIp(request));
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
