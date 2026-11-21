package com.smarthospital.clinical.pharmacy;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.Map;

/** Local view of pharmacy stock and dispensing (FR-PHR-001..012). */
public interface PharmacyServiceLocal {

    Map<String, Object> drugs(String query, boolean activeOnly, int page, int size);

    Map<String, Object> createDrug(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> batches(String drugPublicId, String status, String query,
                                int page, int size);

    Map<String, Object> receiveBatch(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> queue(int page, int size);

    Map<String, Object> prescription(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> review(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> reject(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> dispense(String publicId, Map<String, Object> body,
                                 AuthPrincipal actor, String ip);

    Map<String, Object> dispensing(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> returnDispensing(String publicId, Map<String, Object> body,
                                         AuthPrincipal actor, String ip);

    Map<String, Object> stockMovements(String drugPublicId, String batchPublicId,
                                       String movementType, String date, int page, int size);

    Map<String, Object> stockAlerts(int limit);
}
