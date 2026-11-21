package com.smarthospital.platform.iam;

import java.io.Serializable;

/**
 * Result of a login attempt. Failure is a value, not an exception, so the
 * failed-attempt counter and the audit row are committed before the web layer
 * returns 401.
 */
public class LoginResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String failureCode;
    private String messageAr;
    private String messageEn;
    private AuthPrincipal principal;
    private String sessionToken;
    private boolean mfaRequired;

    public LoginResult() {
    }

    public static LoginResult failure(String code, String messageAr, String messageEn) {
        LoginResult result = new LoginResult();
        result.success = false;
        result.failureCode = code;
        result.messageAr = messageAr;
        result.messageEn = messageEn;
        return result;
    }

    public static LoginResult success(AuthPrincipal principal, String sessionToken, boolean mfaRequired) {
        LoginResult result = new LoginResult();
        result.success = true;
        result.principal = principal;
        result.sessionToken = sessionToken;
        result.mfaRequired = mfaRequired;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getMessageAr() {
        return messageAr;
    }

    public String getMessageEn() {
        return messageEn;
    }

    public AuthPrincipal getPrincipal() {
        return principal;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public boolean isMfaRequired() {
        return mfaRequired;
    }
}
