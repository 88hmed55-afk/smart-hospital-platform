package com.smarthospital.platform.web;

import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.platform.notify.OutboxAdminServiceLocal;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Outbox backlog, dead-letter and retry operations (NFR-OPS, ADR-005). */
@Path("/admin/outbox")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class OutboxResource {

    @Inject
    private OutboxAdminServiceLocal outbox;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @GET
    @Path("/stats")
    public Map<String, Object> stats() {
        authz.require(principal(), "SYS:CONFIG");
        return outbox.stats();
    }

    @GET
    @Path("/events")
    public Map<String, Object> events(@QueryParam("status") String status,
                                      @QueryParam("limit") @DefaultValue("50") int limit) {
        authz.require(principal(), "SYS:CONFIG");
        return outbox.events(status, limit);
    }

    @POST
    @Path("/{id}/retry")
    public Map<String, Object> retry(@PathParam("id") String id) {
        authz.require(principal(), "SYS:CONFIG");
        long eventId;
        try {
            eventId = Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("OUTBOX_ID_INVALID",
                    "معرّف الحدث غير صالح.", "Invalid outbox event id.");
        }
        return outbox.retry(eventId);
    }

    /** Configurable notification wording (FR-NOT-005). */
    @GET
    @Path("/templates")
    public Map<String, Object> templates() {
        authz.require(principal(), "SYS:CONFIG");
        List<Map<String, Object>> items = outbox.templates();
        return Map.of("items", items, "total", items.size());
    }

    @PUT
    @Path("/templates/{code}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateTemplate(@PathParam("code") String code,
                                              Map<String, Object> body) {
        authz.require(principal(), "SYS:CONFIG");
        return outbox.updateTemplate(code, body);
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
