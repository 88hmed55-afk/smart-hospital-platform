package com.smarthospital.platform.web;

import com.smarthospital.clinical.adt.AdtServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Wards and their current inpatients (FR-ADT / FR-NUR). */
@Path("/wards")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class WardResource {

    @Inject
    private AdtServiceLocal adt;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    public List<Map<String, Object>> wards() {
        authz.require(principal(), "ADT:READ");
        return adt.wards();
    }

    @GET
    @Path("/{publicId}/patients")
    public Map<String, Object> patients(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:READ");
        return adt.wardPatients(publicId, actor, WebCookies.clientIp(request));
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
