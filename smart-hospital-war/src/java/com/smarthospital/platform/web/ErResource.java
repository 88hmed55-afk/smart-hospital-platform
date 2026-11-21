package com.smarthospital.platform.web;

import com.smarthospital.clinical.er.ErServiceLocal;
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
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Emergency department board, triage and disposition (FR-ER). */
@Path("/er")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class ErResource {

    @Inject
    private ErServiceLocal er;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/triage-levels")
    public List<Map<String, Object>> triageLevels() {
        authz.require(principal(), "ER:READ");
        return er.triageLevels();
    }

    @POST
    @Path("/visits")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createVisit(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.requireAny(actor, "ER:CREATE", "ER:WRITE");
        Map<String, Object> created = er.createVisit(body, actor, WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/er/visits/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/board")
    public Map<String, Object> board() {
        authz.require(principal(), "ER:READ");
        return er.board();
    }

    @GET
    @Path("/visits/{publicId}")
    public Map<String, Object> visit(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ER:READ");
        return er.visit(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/visits/{publicId}/triage")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> triage(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ER:TRIAGE");
        return er.triage(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/visits/{publicId}/assess")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> assess(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ER:WRITE");
        return er.assess(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/visits/{publicId}/disposition")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> disposition(@PathParam("publicId") String publicId,
                                           Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "ER:WRITE");
        return er.disposition(publicId, body, actor, WebCookies.clientIp(request));
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
