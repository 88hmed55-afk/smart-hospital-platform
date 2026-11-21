package com.smarthospital.platform.web;

import com.smarthospital.clinical.nursing.NursingServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
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

/** Nursing tasks, ward overview, vitals and early warning scores (FR-NUR). */
@Path("/nursing")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class NursingResource {

    @Inject
    private NursingServiceLocal nursing;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    @GET
    @Path("/tasks")
    public Map<String, Object> tasks(@QueryParam("status") String status,
                                     @QueryParam("type") String taskType,
                                     @QueryParam("ward") String ward,
                                     @QueryParam("assignedTo") String assignedTo,
                                     @QueryParam("admission") String admission,
                                     @QueryParam("q") String query,
                                     @QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "NUR:READ");
        return nursing.searchTasks(status, taskType, ward, assignedTo, admission, query, page, size);
    }

    @POST
    @Path("/tasks")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createTask(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        Map<String, Object> created = nursing.createTask(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/nursing/tasks/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/tasks/{publicId}")
    public Map<String, Object> task(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:READ");
        return nursing.task(publicId, actor, WebCookies.clientIp(request));
    }

    @PATCH
    @Path("/tasks/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateTask(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return nursing.updateTask(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/tasks/{publicId}/assign")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> assignTask(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return nursing.assignTask(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/tasks/{publicId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> startTask(@PathParam("publicId") String publicId,
                                         Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return nursing.startTask(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/tasks/{publicId}/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> completeTask(@PathParam("publicId") String publicId,
                                            Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return nursing.completeTask(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/tasks/{publicId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> cancelTask(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return nursing.cancelTask(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Admission workspace
    // ------------------------------------------------------------------

    @GET
    @Path("/admissions/{publicId}/overview")
    public Map<String, Object> overview(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:READ");
        return nursing.overview(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/admissions/{publicId}/vitals")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response recordVitals(@PathParam("publicId") String publicId,
                                 Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:WRITE");
        return Response.status(Response.Status.CREATED)
                .entity(nursing.recordVitals(publicId, body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @GET
    @Path("/admissions/{publicId}/ews")
    public List<Map<String, Object>> ews(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NUR:READ");
        return nursing.ewsHistory(publicId, actor, WebCookies.clientIp(request));
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
