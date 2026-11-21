package com.smarthospital.platform.web;

import com.smarthospital.shared.error.AppException;
import jakarta.ejb.EJBException;
import jakarta.json.JsonException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts every uncaught exception into the documented JSON error envelope.
 *
 * <p>Without this mapper GlassFish returns its own HTML error page for API
 * failures, which breaks the client contract and hides the trace id. Stack
 * traces are logged server-side only and never returned to the browser.
 */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class.getName());

    @Context
    private HttpServletRequest request;

    @Override
    public Response toResponse(Throwable throwable) {
        Throwable root = unwrap(throwable);
        int status = statusOf(root);
        String traceId = traceId();
        ApiError error = new ApiError(
                codeOf(root, status),
                messageArOf(root, status),
                messageEnOf(root, status),
                traceId);

        if (status >= 500) {
            LOG.log(Level.SEVERE, "Unhandled API error traceId=" + traceId, throwable);
        } else {
            LOG.log(Level.FINE, "API error status=" + status + " traceId=" + traceId);
        }

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(error)
                .build();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof EJBException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static int statusOf(Throwable throwable) {
        if (throwable instanceof AppException app) {
            return app.getStatus();
        }
        if (throwable instanceof WebApplicationException web) {
            return web.getResponse() != null
                    ? web.getResponse().getStatus()
                    : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        }
        if (throwable instanceof JsonException
                || throwable instanceof jakarta.json.stream.JsonParsingException) {
            return Response.Status.BAD_REQUEST.getStatusCode();
        }
        return Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
    }

    private static String codeOf(Throwable throwable, int status) {
        if (throwable instanceof AppException app) {
            return app.getCode();
        }
        if (status == 400 && throwable instanceof JsonException) {
            return "VALIDATION_FAILED";
        }
        return switch (status) {
            case 400 -> "VALIDATION_FAILED";
            case 401 -> "AUTH_REQUIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "VERSION_CONFLICT";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "BUSINESS_RULE_VIOLATION";
            case 423 -> "ACCOUNT_LOCKED";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }

    private static String messageArOf(Throwable throwable, int status) {
        if (throwable instanceof AppException app) {
            return app.getMessageAr();
        }
        return switch (status) {
            case 400 -> "الطلب غير صالح.";
            case 401 -> "يلزم تسجيل الدخول.";
            case 403 -> "لا تملك صلاحية تنفيذ هذه العملية.";
            case 404 -> "المورد غير موجود.";
            case 405 -> "طريقة الطلب غير مدعومة.";
            case 409 -> "تعارض في البيانات.";
            case 415 -> "نوع المحتوى غير مدعوم.";
            case 422 -> "العملية تخالف قاعدة عمل.";
            case 423 -> "الحساب مقفل مؤقتًا.";
            case 429 -> "تجاوزت الحد المسموح من الطلبات.";
            case 503 -> "الخدمة غير متاحة مؤقتًا.";
            default -> status >= 500 ? "خطأ داخلي غير متوقع." : "تعذّر تنفيذ الطلب.";
        };
    }

    private static String messageEnOf(Throwable throwable, int status) {
        if (throwable instanceof AppException app) {
            return app.getMessageEn();
        }
        if (status >= 500) {
            return "Unexpected internal error.";
        }
        if (throwable instanceof WebApplicationException web
                && web.getMessage() != null
                && !web.getMessage().isBlank()) {
            return web.getMessage();
        }
        return switch (status) {
            case 400 -> "Invalid request.";
            case 401 -> "Authentication required.";
            case 403 -> "You do not have permission to perform this operation.";
            case 404 -> "Resource not found.";
            case 405 -> "Request method not supported.";
            case 409 -> "Data conflict.";
            case 415 -> "Unsupported media type.";
            case 422 -> "The operation violates a business rule.";
            case 423 -> "The account is temporarily locked.";
            case 429 -> "Rate limit exceeded.";
            case 503 -> "Service temporarily unavailable.";
            default -> "Request could not be completed.";
        };
    }

    private String traceId() {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
