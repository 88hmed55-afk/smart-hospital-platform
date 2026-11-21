package com.smarthospital.clinical.er;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Local view of the emergency department service (FR-ER-001..006). */
public interface ErServiceLocal {

    List<Map<String, Object>> triageLevels();

    Map<String, Object> createVisit(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> board();

    Map<String, Object> visit(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> triage(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> assess(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> disposition(String publicId, Map<String, Object> body,
                                    AuthPrincipal actor, String ip);
}
