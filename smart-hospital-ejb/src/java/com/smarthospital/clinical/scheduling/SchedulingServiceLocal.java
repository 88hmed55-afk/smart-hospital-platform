package com.smarthospital.clinical.scheduling;

import com.smarthospital.platform.iam.AuthPrincipal;
import jakarta.ejb.Local;

import java.util.List;
import java.util.Map;

@Local
public interface SchedulingServiceLocal {

    List<Map<String, Object>> clinics();

    List<Map<String, Object>> providers();

    List<Map<String, Object>> schedules(String providerPublicId);

    Map<String, Object> createSchedule(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> slots(String providerPublicId, String clinicPublicId, String date);

    Map<String, Object> searchAppointments(String date, String providerPublicId, String clinicPublicId,
                                           String status, String patientPublicId, int page, int size);

    Map<String, Object> createAppointment(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> reschedule(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> cancel(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    Map<String, Object> checkIn(String publicId, Map<String, Object> body,
                                AuthPrincipal actor, String ip);

    Map<String, Object> noShow(String publicId, Map<String, Object> body,
                               AuthPrincipal actor, String ip);

    List<Map<String, Object>> queue(String clinicPublicId, String date);

    Map<String, Object> callTicket(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> serveTicket(String publicId, AuthPrincipal actor, String ip);

    Map<String, Object> completeTicket(String publicId, AuthPrincipal actor, String ip);

    List<Map<String, Object>> waitlist(String clinicPublicId);

    Map<String, Object> addWaitlist(Map<String, Object> body, AuthPrincipal actor, String ip);

    List<Map<String, Object>> services(String category);

    List<Map<String, Object>> drugs(String q);
}
