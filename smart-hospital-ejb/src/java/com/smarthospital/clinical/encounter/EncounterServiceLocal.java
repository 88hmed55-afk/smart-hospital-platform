package com.smarthospital.clinical.encounter;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

@Local
public interface EncounterServiceLocal {

    Map<String, Object> search(String q, String status, String providerPublicId,
                               String patientPublicId, String date, int page, int size);

    Map<String, Object> get(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> create(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> update(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> close(String publicId, Map<String, Object> body,
                              AuthPrincipal actor, String ip);

    Map<String, Object> cancel(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> addVitals(String publicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> addDiagnosis(String publicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip);

    Map<String, Object> deleteDiagnosis(String publicId, String diagnosisPublicId,
                                        AuthPrincipal actor, String ip);

    Map<String, Object> createNote(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> updateNote(String publicId, String notePublicId,
                                   Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> signNote(String publicId, String notePublicId,
                                 Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> amendNote(String publicId, String notePublicId,
                                  Map<String, Object> body, AuthPrincipal actor, String ip);

    List<Map<String, Object>> noteVersions(String publicId, String notePublicId,
                                           AuthPrincipal actor, String ip);

    Map<String, Object> createOrder(String publicId, Map<String, Object> body,
                                    AuthPrincipal actor, String ip);

    Map<String, Object> updateOrderStatus(String publicId, String orderPublicId,
                                          Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> createPrescription(String publicId, Map<String, Object> body,
                                           AuthPrincipal actor, String ip);

    Map<String, Object> issuePrescription(String publicId, String prescriptionPublicId,
                                          Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> cancelPrescription(String publicId, String prescriptionPublicId,
                                           Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> prescription(String prescriptionPublicId, AuthPrincipal actor, String ip);

    Map<String, Object> addCharge(String publicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> printPayload(String publicId, AuthPrincipal actor, String ip);

    List<Map<String, Object>> patientPrescriptions(String patientPublicId,
                                                   AuthPrincipal actor, String ip);
}
