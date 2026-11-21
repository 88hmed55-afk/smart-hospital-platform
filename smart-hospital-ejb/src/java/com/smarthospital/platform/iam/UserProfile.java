package com.smarthospital.platform.iam;

import java.io.Serializable;
import java.util.List;

/**
 * Public identity of the authenticated user. Internal numeric ids are never
 * included; the API exposes {@code publicId} only (ADR-007).
 */
public class UserProfile implements Serializable {

    private String publicId;
    private String username;
    private String displayNameAr;
    private String displayNameEn;
    private List<String> roles;
    private List<String> permissions;
    private List<ScopeView> scopes;
    private boolean mustChangePassword;
    private boolean mfaEnabled;

    public UserProfile() {
    }

    public UserProfile(String publicId, String username, String displayNameAr, String displayNameEn,
                       List<String> roles, List<String> permissions, List<ScopeView> scopes,
                       boolean mustChangePassword, boolean mfaEnabled) {
        this.publicId = publicId;
        this.username = username;
        this.displayNameAr = displayNameAr;
        this.displayNameEn = displayNameEn;
        this.roles = roles;
        this.permissions = permissions;
        this.scopes = scopes;
        this.mustChangePassword = mustChangePassword;
        this.mfaEnabled = mfaEnabled;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayNameAr() {
        return displayNameAr;
    }

    public String getDisplayNameEn() {
        return displayNameEn;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public List<ScopeView> getScopes() {
        return scopes;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }
}
