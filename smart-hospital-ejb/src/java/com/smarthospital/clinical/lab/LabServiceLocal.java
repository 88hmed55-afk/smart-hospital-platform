package com.smarthospital.clinical.lab;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Local view of the laboratory workflow (FR-LAB-001..012). */
public interface LabServiceLocal {

    List<Map<String, Object>> tests(String query, boolean activeOnly);

    Map<String, Object> createTest(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> searchOrders(String status, String priority, String patientPublicId,
                                     String query, String date, int page, int size);

    Map<String, Object> createOrder(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> order(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> collect(String orderPublicId, Map<String, Object> body,
                                AuthPrincipal actor, String ip);

    Map<String, Object> receiveSpecimen(String specimenPublicId, Map<String, Object> body,
                                        AuthPrincipal actor, String ip);

    Map<String, Object> rejectSpecimen(String specimenPublicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip);

    Map<String, Object> startProcessing(String orderPublicId, Map<String, Object> body,
                                        AuthPrincipal actor, String ip);

    Map<String, Object> enterResult(String orderPublicId, Map<String, Object> body,
                                    AuthPrincipal actor, String ip);

    Map<String, Object> verify(String resultPublicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> release(String resultPublicId, Map<String, Object> body,
                                AuthPrincipal actor, String ip);

    Map<String, Object> amend(String resultPublicId, Map<String, Object> body,
                              AuthPrincipal actor, String ip);

    Map<String, Object> worklist(String priority, int page, int size);

    Map<String, Object> report(String resultPublicId, AuthPrincipal actor, String ip);

    Map<String, Object> orderReport(String orderPublicId, AuthPrincipal actor, String ip);

    Map<String, Object> alerts(String status, int limit);

    Map<String, Object> acknowledgeAlert(String alertPublicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip);
}
