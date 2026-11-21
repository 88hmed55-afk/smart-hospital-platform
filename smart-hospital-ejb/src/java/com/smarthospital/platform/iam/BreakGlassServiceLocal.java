package com.smarthospital.platform.iam;

import java.util.List;
import java.util.Map;

/** Local view of Break-Glass emergency access. */
public interface BreakGlassServiceLocal {

    Map<String, Object> request(AuthPrincipal actor, String patientPublicId, String reason,
                                int minutes, String ip);

    Map<String, Object> revoke(AuthPrincipal actor, String publicId, String ip);

    Map<String, Object> review(AuthPrincipal actor, String publicId, String note, String ip);

    List<Map<String, Object>> list(AuthPrincipal actor, String status);
}
