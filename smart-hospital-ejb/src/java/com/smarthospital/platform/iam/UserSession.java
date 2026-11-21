package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Audit trail for server-side sessions. The session token itself is never
 * stored; only its SHA-256 hash is kept. */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_hash", nullable = false, unique = true, length = 64)
    private String sessionHash;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    protected UserSession() {
    }

    public UserSession(Long userId, String sessionHash, String ip, String userAgent) {
        this.userId = userId;
        this.sessionHash = sessionHash;
        this.ip = ip;
        this.userAgent = userAgent;
        this.startedAt = LocalDateTime.now();
        this.lastSeenAt = this.startedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSessionHash() {
        return sessionHash;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }

    public void touch() {
        this.lastSeenAt = LocalDateTime.now();
    }

    public void end(String reason) {
        if (this.endedAt == null) {
            this.endedAt = LocalDateTime.now();
            this.endReason = reason;
        }
    }
}
