package com.smarthospital.platform.web;

import com.smarthospital.clinical.encounter.EncounterServiceLocal;
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

import java.util.List;
import java.util.Map;

/** Prescription reads, patient medication history and pharmacy review/dispense. */
@Path("/prescriptions")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PrescriptionResource {

    @Inject
    private EncounterServiceLocal encounters;

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
        authz.require(actor, "EMR:READ");
        return encounters.prescription(publicId, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/patient/{patientPublicId}")
    public List<Map<String, Object>> patientPrescriptions(
            @PathParam("patientPublicId") String patientPublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:READ");
        return encounters.patientPrescriptions(patientPublicId, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Pharmacy workspace
    // ------------------------------------------------------------------

    @GET
    @Path("/{publicId}/pharmacy")
    public Map<String, Object> pharmacyView(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:READ");
        return pharmacy.prescription(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> review(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:REVIEW");
        return pharmacy.review(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> reject(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:REVIEW");
        return pharmacy.reject(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/dispense")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> dispense(@PathParam("publicId") String publicId,
                                        Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PHR:DISPENSE");
        return pharmacy.dispense(publicId, body, actor, WebCookies.clientIp(request));
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
