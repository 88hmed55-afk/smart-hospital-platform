package com.smarthospital.platform.iam;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticated principal cached in the HTTP session.
 *
 * <p>The raw session token never enters this object: the browser holds it in an
 * HttpOnly cookie and the server keeps only its SHA-256 hash (ADR-003).
 */
public class AuthPrincipal implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String publicId;
    private String username;
    private String displayNameAr;
    private String displayNameEn;
    private String sessionHash;
    private List<String> roles = new ArrayList<>();
    private List<String> permissions = new ArrayList<>();
    private List<ScopeView> scopes = new ArrayList<>();
    private boolean mustChangePassword;
    private boolean mfaEnabled;
    private boolean mfaVerified = true;

    public AuthPrincipal() {
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayNameAr() {
        return displayNameAr;
    }

    public void setDisplayNameAr(String displayNameAr) {
        this.displayNameAr = displayNameAr;
    }

    public String getDisplayNameEn() {
        return displayNameEn;
    }

    public void setDisplayNameEn(String displayNameEn) {
        this.displayNameEn = displayNameEn;
    }

    public String getSessionHash() {
        return sessionHash;
    }

    public void setSessionHash(String sessionHash) {
        this.sessionHash = sessionHash;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles == null ? new ArrayList<>() : roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }

    public List<ScopeView> getScopes() {
        return scopes;
    }

    public void setScopes(List<ScopeView> scopes) {
        this.scopes = scopes == null ? new ArrayList<>() : scopes;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public boolean isMfaVerified() {
        return mfaVerified;
    }

    public void setMfaVerified(boolean mfaVerified) {
        this.mfaVerified = mfaVerified;
    }

    public boolean hasPermission(String permission) {
        return permission != null && permissions.contains(permission);
    }

    public String primaryRole() {
        return roles.isEmpty() ? "" : roles.get(0);
    }
}
