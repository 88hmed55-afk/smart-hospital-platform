package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 26)
    private String publicId;

    @Column(name = "username", nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_salt", nullable = false, columnDefinition = "VARBINARY(32)")
    private byte[] passwordSalt;

    @Column(name = "password_algo", nullable = false, length = 40)
    private String passwordAlgorithm;

    @Column(name = "password_iterations", nullable = false)
    private int passwordIterations;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "display_name_ar", nullable = false, length = 150)
    private String displayNameAr;

    @Column(name = "display_name_en", length = 150)
    private String displayNameEn;

    @Column(name = "email", length = 190)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "mfa_secret", columnDefinition = "VARBINARY(255)")
    private byte[] mfaSecret;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected User() {
    }

    public User(String publicId, String username, String passwordHash, byte[] passwordSalt,
                String passwordAlgorithm, int passwordIterations, String displayNameAr,
                String displayNameEn) {
        this.publicId = publicId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.passwordAlgorithm = passwordAlgorithm;
        this.passwordIterations = passwordIterations;
        this.displayNameAr = displayNameAr;
        this.displayNameEn = displayNameEn;
        this.status = "ACTIVE";
        this.mustChangePassword = true;
        this.failedAttempts = 0;
        this.mfaEnabled = false;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public byte[] getPasswordSalt() {
        return passwordSalt;
    }

    public String getPasswordAlgorithm() {
        return passwordAlgorithm;
    }

    public int getPasswordIterations() {
        return passwordIterations;
    }

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public String getDisplayNameAr() {
        return displayNameAr;
    }

    public String getDisplayNameEn() {
        return displayNameEn;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public Long getBranchId() {
        return branchId;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public String getStatus() {
        return status;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public byte[] getMfaSecret() {
        return mfaSecret;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }

    public void setPassword(String hash, byte[] salt, String algorithm, int iterations) {
        this.passwordHash = hash;
        this.passwordSalt = salt;
        this.passwordAlgorithm = algorithm;
        this.passwordIterations = iterations;
        this.passwordChangedAt = LocalDateTime.now();
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public void setDisplayNameAr(String displayNameAr) {
        this.displayNameAr = displayNameAr;
    }

    public void setDisplayNameEn(String displayNameEn) {
        this.displayNameEn = displayNameEn;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public void setMfaSecret(byte[] mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
