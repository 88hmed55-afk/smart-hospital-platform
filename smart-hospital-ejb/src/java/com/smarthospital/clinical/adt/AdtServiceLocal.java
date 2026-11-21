package com.smarthospital.clinical.adt;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Local view of admissions, beds and transfers (FR-ADT-001..009). */
public interface AdtServiceLocal {

    List<Map<String, Object>> wards();

    Map<String, Object> search(String status, String wardPublicId, String query, int page, int size);

    Map<String, Object> get(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> requestAdmission(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> approve(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> reject(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> assignBed(String publicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> transfer(String publicId, Map<String, Object> body,
                                 AuthPrincipal actor, String ip);

    Map<String, Object> discharge(String publicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> dischargeSummary(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> saveDischargeSummary(String publicId, Map<String, Object> body,
                                             AuthPrincipal actor, String ip);

    Map<String, Object> bedBoard();

    Map<String, Object> bedStatus(String bedPublicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> wardPatients(String wardPublicId, AuthPrincipal actor, String ip);
}
