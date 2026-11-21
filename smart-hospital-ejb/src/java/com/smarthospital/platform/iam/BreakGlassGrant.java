package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "break_glass_grants")
public class BreakGlassGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 26)
    private String publicId;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "patient_public_id", nullable = false, length = 26)
    private String patientPublicId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected BreakGlassGrant() {
    }

    public BreakGlassGrant(String publicId, Long requesterUserId, String patientPublicId,
                           String reason, LocalDateTime grantedAt, LocalDateTime expiresAt) {
        this.publicId = publicId;
        this.requesterUserId = requesterUserId;
        this.patientPublicId = patientPublicId;
        this.reason = reason;
        this.status = "GRANTED";
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getRequesterUserId() {
        return requesterUserId;
    }

    public String getPatientPublicId() {
        return patientPublicId;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void revoke() {
        this.status = "REVOKED";
        this.revokedAt = LocalDateTime.now();
    }

    public void review(Long reviewerId, String note) {
        this.status = "REVIEWED";
        this.reviewedAt = LocalDateTime.now();
        this.reviewedBy = reviewerId;
        this.reviewNote = note;
    }

    public void expire() {
        this.status = "EXPIRED";
    }
}
