package com.smarthospital.platform.web;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Declares UTF-8 explicitly on JSON responses.
 *
 * <p>JSON is UTF-8 by definition, but many HTTP clients fall back to ISO-8859-1
 * when the charset parameter is absent. Arabic text would then be decoded
 * incorrectly even though the bytes on the wire are correct.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class CharsetResponseFilter implements ContainerResponseFilter {

    private static final MediaType JSON_UTF8 =
            new MediaType("application", "json", Map.of("charset", "UTF-8"));

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        MediaType type = responseContext.getMediaType();
        if (type != null && MediaType.APPLICATION_JSON_TYPE.isCompatible(type)) {
            responseContext.getHeaders().putSingle("Content-Type", JSON_UTF8.toString());
        }
    }
}
