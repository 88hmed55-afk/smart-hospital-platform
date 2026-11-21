package com.smarthospital.platform.web;

import com.smarthospital.clinical.encounter.EncounterServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

/** Encounter workspace, vitals, diagnoses, notes, orders and charge capture (FR-EMR). */
@Path("/encounters")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class EncounterResource {

    @Inject
    private EncounterServiceLocal encounters;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    @GET
    public Map<String, Object> search(@QueryParam("q") String query,
                                      @QueryParam("status") String status,
                                      @QueryParam("provider") String provider,
                                      @QueryParam("patient") String patient,
                                      @QueryParam("date") String date,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "EMR:READ");
        return encounters.search(query, status, provider, patient, date, page, size);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:CREATE");
        Map<String, Object> created = encounters.create(body, actor, WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/encounters/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/{publicId}")
    public Map<String, Object> get(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:READ");
        return encounters.get(publicId, actor, WebCookies.clientIp(request));
    }

    @PATCH
    @Path("/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> update(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.update(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/close")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> close(@PathParam("publicId") String publicId,
                                     Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.close(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> cancel(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.cancel(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Vitals
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/vitals")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addVitals(@PathParam("publicId") String publicId,
                              Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.addVitals(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    // ------------------------------------------------------------------
    // Diagnoses
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/diagnoses")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addDiagnosis(@PathParam("publicId") String publicId,
                                 Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:DIAGNOSE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.addDiagnosis(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @DELETE
    @Path("/{publicId}/diagnoses/{diagnosisPublicId}")
    public Map<String, Object> deleteDiagnosis(@PathParam("publicId") String publicId,
                                               @PathParam("diagnosisPublicId") String diagnosisPublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:DIAGNOSE");
        return encounters.deleteDiagnosis(publicId, diagnosisPublicId, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Clinical notes
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/notes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createNote(@PathParam("publicId") String publicId,
                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.createNote(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @PATCH
    @Path("/{publicId}/notes/{notePublicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateNote(@PathParam("publicId") String publicId,
                                          @PathParam("notePublicId") String notePublicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.updateNote(publicId, notePublicId, body, actor,
                WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/notes/{notePublicId}/sign")
    public Map<String, Object> signNote(@PathParam("publicId") String publicId,
                                        @PathParam("notePublicId") String notePublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:SIGN");
        return encounters.signNote(publicId, notePublicId, Map.of(), actor,
                WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/notes/{notePublicId}/amend")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> amendNote(@PathParam("publicId") String publicId,
                                         @PathParam("notePublicId") String notePublicId,
                                         Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:SIGN");
        return encounters.amendNote(publicId, notePublicId, body, actor,
                WebCookies.clientIp(request));
    }

    @GET
    @Path("/{publicId}/notes/{notePublicId}/versions")
    public List<Map<String, Object>> noteVersions(@PathParam("publicId") String publicId,
                                                  @PathParam("notePublicId") String notePublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:READ");
        return encounters.noteVersions(publicId, notePublicId, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/orders")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createOrder(@PathParam("publicId") String publicId,
                                Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.createOrder(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @PATCH
    @Path("/{publicId}/orders/{orderPublicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateOrder(@PathParam("publicId") String publicId,
                                           @PathParam("orderPublicId") String orderPublicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.updateOrderStatus(publicId, orderPublicId, body, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Prescriptions
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/prescriptions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPrescription(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.createPrescription(publicId, body, actor,
                        WebCookies.clientIp(request)))
                .build();
    }

    @POST
    @Path("/{publicId}/prescriptions/{prescriptionPublicId}/issue")
    public Map<String, Object> issuePrescription(@PathParam("publicId") String publicId,
                                                 @PathParam("prescriptionPublicId") String prescriptionPublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:SIGN");
        return encounters.issuePrescription(publicId, prescriptionPublicId, Map.of(), actor,
                WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/prescriptions/{prescriptionPublicId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> cancelPrescription(@PathParam("publicId") String publicId,
                                                  @PathParam("prescriptionPublicId") String prescriptionPublicId,
                                                  Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return encounters.cancelPrescription(publicId, prescriptionPublicId, body, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Charge capture and printing
    // ------------------------------------------------------------------

    @POST
    @Path("/{publicId}/charges")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addCharge(@PathParam("publicId") String publicId,
                              Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:UPDATE");
        return Response.status(Response.Status.CREATED)
                .entity(encounters.addCharge(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @GET
    @Path("/{publicId}/print")
    public Map<String, Object> print(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "EMR:READ");
        return encounters.printPayload(publicId, actor, WebCookies.clientIp(request));
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
