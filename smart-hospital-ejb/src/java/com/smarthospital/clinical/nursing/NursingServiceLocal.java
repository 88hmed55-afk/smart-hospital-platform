package com.smarthospital.clinical.nursing;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

/** Local view of nursing tasks, medication administration and EWS (FR-NUR). */
public interface NursingServiceLocal {

    Map<String, Object> searchTasks(String status, String taskType, String wardPublicId,
                                    String assignedToPublicId, String admissionPublicId,
                                    String query, int page, int size);

    Map<String, Object> task(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> createTask(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateTask(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> assignTask(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> startTask(String publicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> completeTask(String publicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip);

    Map<String, Object> cancelTask(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> administer(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> recordVitals(String admissionPublicId, Map<String, Object> body,
                                     AuthPrincipal actor, String ip);

    Map<String, Object> overview(String admissionPublicId, AuthPrincipal actor, String ip);

    List<Map<String, Object>> ewsHistory(String admissionPublicId, AuthPrincipal actor, String ip);
}
