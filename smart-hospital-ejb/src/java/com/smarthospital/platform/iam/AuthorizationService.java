package com.smarthospital.platform.iam;

import com.smarthospital.shared.error.AppException;
import jakarta.ejb.Stateless;

/** Permission checks. Every resource calls this before touching domain data. */
@Stateless
public class AuthorizationService {

    public boolean has(AuthPrincipal principal, String permission) {
        return principal != null && principal.hasPermission(permission);
    }

    public void require(AuthPrincipal principal, String permission) {
        if (principal == null) {
            throw AppException.unauthorized(
                    "AUTH_REQUIRED",
                    "يلزم تسجيل الدخول.",
                    "Authentication required.");
        }
        if (!principal.isMfaVerified()) {
            throw AppException.unauthorized(
                    "MFA_REQUIRED",
                    "يلزم إكمال التحقق بخطوة ثانية.",
                    "Second-factor verification is required.");
        }
        if (!has(principal, permission)) {
            throw AppException.forbidden(
                    "FORBIDDEN",
                    "لا تملك صلاحية تنفيذ هذه العملية.",
                    "You do not have permission to perform this operation.");
        }
    }

    public void requireAny(AuthPrincipal principal, String... permissions) {
        if (principal == null) {
            throw AppException.unauthorized(
                    "AUTH_REQUIRED",
                    "يلزم تسجيل الدخول.",
                    "Authentication required.");
        }
        if (!principal.isMfaVerified()) {
            throw AppException.unauthorized(
                    "MFA_REQUIRED",
                    "يلزم إكمال التحقق بخطوة ثانية.",
                    "Second-factor verification is required.");
        }
        for (String permission : permissions) {
            if (has(principal, permission)) {
                return;
            }
        }
        throw AppException.forbidden(
                "FORBIDDEN",
                "لا تملك صلاحية تنفيذ هذه العملية.",
                "You do not have permission to perform this operation.");
    }
}
