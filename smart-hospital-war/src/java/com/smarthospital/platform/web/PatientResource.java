package com.smarthospital.platform.web;

import com.smarthospital.clinical.patient.PatientServiceLocal;
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

/** Patient registration, search, EMPI, chart and timeline (FR-PAT-001..012). */
@Path("/patients")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class PatientResource {

    @Inject
    private PatientServiceLocal patients;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    public Map<String, Object> search(@QueryParam("q") String query,
                                      @QueryParam("status") String status,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "PAT:READ");
        return patients.search(query, status, page, size);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:CREATE");
        Map<String, Object> created = patients.create(body, actor, WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/patients/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/check-duplicates")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> checkDuplicates(Map<String, Object> body) {
        authz.require(principal(), "PAT:CREATE");
        return patients.checkDuplicates(body);
    }

    @GET
    @Path("/{publicId}")
    public Map<String, Object> get(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:READ");
        return patients.get(publicId, actor, WebCookies.clientIp(request));
    }

    @PATCH
    @Path("/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> update(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:UPDATE");
        return patients.update(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/merge")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> merge(@PathParam("publicId") String publicId,
                                     Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:MERGE_APPROVE");
        return patients.merge(publicId, body, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/{publicId}/timeline")
    public List<Map<String, Object>> timeline(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:READ");
        return patients.timeline(publicId, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/{publicId}/allergies")
    public List<Map<String, Object>> allergies(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:READ");
        return patients.allergies(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/allergies")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addAllergy(@PathParam("publicId") String publicId,
                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:UPDATE");
        Map<String, Object> created = patients.addAllergy(publicId, body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @DELETE
    @Path("/{publicId}/allergies/{allergyPublicId}")
    public Map<String, Object> removeAllergy(@PathParam("publicId") String publicId,
                                             @PathParam("allergyPublicId") String allergyPublicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "PAT:UPDATE");
        return patients.removeAllergy(publicId, allergyPublicId, actor,
                WebCookies.clientIp(request));
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
