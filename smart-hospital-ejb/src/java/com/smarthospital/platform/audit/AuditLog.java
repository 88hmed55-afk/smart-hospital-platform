package com.smarthospital.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Read model for the append-only audit log. Writes go through
 * {@link AuditService} with native SQL so the hash chain stays authoritative.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_public_id", length = 26)
    private String entityPublicId;

    @Column(name = "before_json", columnDefinition = "JSON")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "JSON")
    private String afterJson;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuditLog() {
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityPublicId() {
        return entityPublicId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getReason() {
        return reason;
    }

    public String getIp() {
        return ip;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
