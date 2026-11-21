package com.smarthospital.platform.web;

import com.smarthospital.clinical.nursing.NursingServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Barcode-verified medication administration at the bedside (FR-NUR-004). */
@Path("/medication-administrations")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class MedicationResource {

    @Inject
    private NursingServiceLocal nursing;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response administer(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:MED");
        return Response.status(Response.Status.CREATED)
                .entity(nursing.administer(body, actor, WebCookies.clientIp(request)))
                .build();
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
