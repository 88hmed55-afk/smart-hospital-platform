package com.smarthospital.platform.web;

import com.smarthospital.platform.health.HealthResult;
import com.smarthospital.platform.health.HealthServiceLocal;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Operational health endpoint. Public by design: it exposes no patient data,
 * no credentials and no infrastructure details beyond status and latency.
 *
 * <p>The resource is a CDI bean (see {@code WEB-INF/beans.xml}) so the EJB local
 * view is injected by the container instead of being resolved by Jersey itself.
 */
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class HealthResource {

    @Inject
    private HealthServiceLocal healthService;

    @GET
    public Response health() {
        HealthResult result = healthService.check();
        int status = result.isUp() ? Response.Status.OK.getStatusCode()
                : Response.Status.SERVICE_UNAVAILABLE.getStatusCode();
        return Response.status(status)
                .header("Cache-Control", "no-store")
                .entity(result)
                .build();
    }
}
