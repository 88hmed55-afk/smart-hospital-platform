package com.smarthospital.platform.web;

import com.smarthospital.clinical.adt.AdtServiceLocal;
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

import java.util.Map;

/** Admissions, transfers, discharge and discharge summaries (FR-ADT). */
@Path("/admissions")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class AdtResource {

    @Inject
    private AdtServiceLocal adt;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    public Map<String, Object> search(@QueryParam("status") String status,
                                      @QueryParam("ward") String ward,
                                      @QueryParam("q") String query,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "ADT:READ");
        return adt.search(status, ward, query, page, size);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response request(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "ADT:REQUEST", "ADT:WRITE");
        Map<String, Object> created = adt.requestAdmission(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/admissions/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/{publicId}")
    public Map<String, Object> get(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:READ");
        return adt.get(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> approve(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:APPROVE");
        return adt.approve(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> reject(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:APPROVE");
        return adt.reject(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/assign-bed")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> assignBed(@PathParam("publicId") String publicId,
                                         Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "ADT:ASSIGN", "ADT:WRITE");
        return adt.assignBed(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/transfer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> transfer(@PathParam("publicId") String publicId,
                                        Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:WRITE");
        return adt.transfer(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/discharge")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> discharge(@PathParam("publicId") String publicId,
                                         Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:WRITE");
        return adt.discharge(publicId, body, actor, WebCookies.clientIp(request));
    }

    @GET
    @Path("/{publicId}/summary")
    public Map<String, Object> summary(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:READ");
        return adt.dischargeSummary(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/{publicId}/summary")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> saveSummary(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ADT:WRITE");
        return adt.saveDischargeSummary(publicId, body, actor, WebCookies.clientIp(request));
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
