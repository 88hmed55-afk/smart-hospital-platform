package com.smarthospital.clinical.patient;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

@Local
public interface PatientServiceLocal {

    Map<String, Object> search(String q, String status, int page, int size);

    Map<String, Object> get(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> create(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> update(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> checkDuplicates(Map<String, Object> body);

    Map<String, Object> merge(String sourcePublicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    List<Map<String, Object>> timeline(String publicId, AuthPrincipal actor, String ip);

    List<Map<String, Object>> allergies(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> addAllergy(String publicId, Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> removeAllergy(String publicId, String allergyPublicId,
                                      AuthPrincipal actor, String ip);
}
