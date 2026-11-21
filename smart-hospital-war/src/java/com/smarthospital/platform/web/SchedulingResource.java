package com.smarthospital.platform.web;

import com.smarthospital.clinical.scheduling.SchedulingServiceLocal;
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

import java.util.List;
import java.util.Map;

/** Clinics, schedules, appointments, queue and waitlist (FR-SCH-001..010). */
@Path("/scheduling")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class SchedulingResource {

    @Inject
    private SchedulingServiceLocal scheduling;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Reference data
    // ------------------------------------------------------------------

    @GET
    @Path("/clinics")
    public List<Map<String, Object>> clinics() {
        authz.require(principal(), "SCH:READ");
        return scheduling.clinics();
    }

    @GET
    @Path("/providers")
    public List<Map<String, Object>> providers() {
        authz.require(principal(), "SCH:READ");
        return scheduling.providers();
    }

    @GET
    @Path("/services")
    public List<Map<String, Object>> services(@QueryParam("category") String category) {
        authz.require(principal(), "SCH:READ");
        return scheduling.services(category);
    }

    @GET
    @Path("/drugs")
    public List<Map<String, Object>> drugs(@QueryParam("q") String query) {
        authz.require(principal(), "SCH:READ");
        return scheduling.drugs(query);
    }

    @GET
    @Path("/schedules")
    public List<Map<String, Object>> schedules(@QueryParam("provider") String provider) {
        authz.require(principal(), "SCH:READ");
        return scheduling.schedules(provider);
    }

    @POST
    @Path("/schedules")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSchedule(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:WRITE");
        Map<String, Object> created = scheduling.createSchedule(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @GET
    @Path("/slots")
    public Map<String, Object> slots(@QueryParam("provider") String provider,
                                     @QueryParam("clinic") String clinic,
                                     @QueryParam("date") String date) {
        authz.require(principal(), "SCH:READ");
        return scheduling.slots(provider, clinic, date);
    }

    // ------------------------------------------------------------------
    // Appointments
    // ------------------------------------------------------------------

    @GET
    @Path("/appointments")
    public Map<String, Object> appointments(@QueryParam("date") String date,
                                            @QueryParam("provider") String provider,
                                            @QueryParam("clinic") String clinic,
                                            @QueryParam("status") String status,
                                            @QueryParam("patient") String patient,
                                            @QueryParam("page") @DefaultValue("0") int page,
                                            @QueryParam("size") @DefaultValue("50") int size) {
        authz.require(principal(), "SCH:READ");
        return scheduling.searchAppointments(date, provider, clinic, status, patient, page, size);
    }

    @POST
    @Path("/appointments")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAppointment(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CREATE");
        Map<String, Object> created = scheduling.createAppointment(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/scheduling/appointments/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/appointments/{publicId}/reschedule")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> reschedule(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:WRITE");
        return scheduling.reschedule(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/appointments/{publicId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> cancel(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:WRITE");
        return scheduling.cancel(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/appointments/{publicId}/check-in")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> checkIn(@PathParam("publicId") String publicId,
                                       Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CHECKIN");
        return scheduling.checkIn(publicId, body, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/appointments/{publicId}/no-show")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> noShow(@PathParam("publicId") String publicId,
                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:WRITE");
        return scheduling.noShow(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------

    @GET
    @Path("/queue")
    public List<Map<String, Object>> queue(@QueryParam("clinic") String clinic,
                                           @QueryParam("date") String date) {
        authz.require(principal(), "SCH:READ");
        return scheduling.queue(clinic, date);
    }

    @POST
    @Path("/queue/{publicId}/call")
    public Map<String, Object> call(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CHECKIN");
        return scheduling.callTicket(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/queue/{publicId}/serve")
    public Map<String, Object> serve(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CHECKIN");
        return scheduling.serveTicket(publicId, actor, WebCookies.clientIp(request));
    }

    @POST
    @Path("/queue/{publicId}/complete")
    public Map<String, Object> complete(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CHECKIN");
        return scheduling.completeTicket(publicId, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Waitlist
    // ------------------------------------------------------------------

    @GET
    @Path("/waitlist")
    public List<Map<String, Object>> waitlist(@QueryParam("clinic") String clinic) {
        authz.require(principal(), "SCH:READ");
        return scheduling.waitlist(clinic);
    }

    @POST
    @Path("/waitlist")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addWaitlist(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "SCH:CREATE");
        return Response.status(Response.Status.CREATED)
                .entity(scheduling.addWaitlist(body, actor, WebCookies.clientIp(request)))
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
