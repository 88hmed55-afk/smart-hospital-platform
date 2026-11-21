package com.smarthospital.platform.web;

import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.platform.notify.NotificationPushListener;
import com.smarthospital.platform.notify.NotificationPushRegistryLocal;
import com.smarthospital.platform.notify.NotificationServiceLocal;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.List;
import java.util.Map;

/**
 * Personal notification inbox (FR-NOT).
 *
 * <p>Clients may subscribe to {@code /notifications/stream} (SSE) and keep the
 * existing 30-second polling as a fallback; both paths return the same rows.
 */
@Path("/notifications")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class NotificationResource {

    @Inject
    private NotificationServiceLocal notifications;

    @Inject
    private NotificationPushRegistryLocal pushRegistry;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    @Context
    private HttpServletResponse response;

    @GET
    public Map<String, Object> list(@QueryParam("unreadOnly") @DefaultValue("false") boolean unreadOnly,
                                    @QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("size") @DefaultValue("20") int size) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        return notifications.list(actor.getUserId(), unreadOnly, page, size);
    }

    @GET
    @Path("/unread-count")
    public Map<String, Object> summary() {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        return notifications.summary(actor.getUserId());
    }

    @POST
    @Path("/{publicId}/read")
    public Map<String, Object> markRead(@PathParam("publicId") String publicId) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        return notifications.markRead(actor.getUserId(), publicId);
    }

    @POST
    @Path("/read-all")
    public Map<String, Object> markAllRead() {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        return notifications.markAllRead(actor.getUserId());
    }

    @GET
    @Path("/preferences")
    public Map<String, Object> preferences() {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        return notifications.preferences(actor.getUserId());
    }

    @PUT
    @Path("/preferences")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updatePreferences(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        Object raw = body == null ? null : body.get("categories");
        if (!(raw instanceof List<?> list)) {
            throw AppException.badRequest("PREFERENCES_REQUIRED",
                    "قائمة التفضيلات مطلوبة.", "A preferences list is required.");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) list;
        return notifications.updatePreferences(actor.getUserId(), entries);
    }

    /**
     * Live inbox stream. The connection is held open by the container; each
     * committed notification is pushed as a {@code notification} event and a
     * comment frame every 20 seconds keeps proxies from closing an idle stream.
     */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context SseEventSink sink, @Context Sse sse) {
        AuthPrincipal actor = principal();
        authz.require(actor, "NOT:READ");
        long userId = actor.getUserId();
        String sessionHash = actor.getSessionHash();

        // A proxy must forward each frame immediately: buffering would turn a
        // live stream into one delayed burst (ADR-006).
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        NotificationPushListener listener = new NotificationPushListener() {
            @Override
            public void onNotification(String json) {
                try {
                    sink.send(sse.newEventBuilder().name("notification").data(json).build());
                } catch (RuntimeException ex) {
                    close();
                }
            }

            @Override
            public void onPing() {
                // A revoked or logged-out session must not keep a live channel.
                if (!notifications.sessionActive(sessionHash)) {
                    close();
                    return;
                }
                try {
                    sink.send(sse.newEventBuilder().comment("keep-alive").build());
                } catch (RuntimeException ex) {
                    close();
                }
            }

            @Override
            public void close() {
                pushRegistry.unregister(userId, this);
                try {
                    sink.close();
                } catch (RuntimeException ignored) {
                    // already closed by the client
                }
            }
        };

        pushRegistry.register(userId, listener);
        try {
            Object unread = notifications.summary(userId).get("unread");
            sink.send(sse.newEventBuilder().comment("connected").build());
            sink.send(sse.newEventBuilder().name("ready")
                    .data("{\"unread\":" + (unread == null ? "0" : unread) + "}").build());
        } catch (RuntimeException ex) {
            pushRegistry.unregister(userId, listener);
            try {
                sink.close();
            } catch (RuntimeException ignored) {
                // already closed by the client
            }
        }
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
