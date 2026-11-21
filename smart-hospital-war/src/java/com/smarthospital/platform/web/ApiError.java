package com.smarthospital.platform.web;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Error envelope documented in {@code architecture/04-api-contracts.md} §3.3.
 */
public class ApiError {

    private final String code;
    private final String messageAr;
    private final String messageEn;
    private final String traceId;
    private final List<Map<String, String>> fieldErrors;
    private final Map<String, Object> details;

    public ApiError(String code, String messageAr, String messageEn, String traceId) {
        this(code, messageAr, messageEn, traceId, Collections.emptyList(), Collections.emptyMap());
    }

    public ApiError(String code, String messageAr, String messageEn, String traceId,
                    List<Map<String, String>> fieldErrors, Map<String, Object> details) {
        this.code = code;
        this.messageAr = messageAr;
        this.messageEn = messageEn;
        this.traceId = traceId;
        this.fieldErrors = fieldErrors == null ? Collections.emptyList() : fieldErrors;
        this.details = details == null ? Collections.emptyMap() : details;
    }

    public String getCode() {
        return code;
    }

    public String getMessageAr() {
        return messageAr;
    }

    public String getMessageEn() {
        return messageEn;
    }

    public String getTraceId() {
        return traceId;
    }

    public List<Map<String, String>> getFieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
