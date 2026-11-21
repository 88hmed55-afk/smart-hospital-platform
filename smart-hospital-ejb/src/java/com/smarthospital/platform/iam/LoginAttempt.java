package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username_attempted", nullable = false, length = 80)
    private String usernameAttempted;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "ip", nullable = false, length = 45)
    private String ip;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "reason", length = 50)
    private String reason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    protected LoginAttempt() {
    }

    public LoginAttempt(String usernameAttempted, Long userId, String ip, boolean success, String reason) {
        this.usernameAttempted = usernameAttempted;
        this.userId = userId;
        this.ip = ip;
        this.success = success;
        this.reason = reason;
        this.attemptedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsernameAttempted() {
        return usernameAttempted;
    }

    public Long getUserId() {
        return userId;
    }

    public String getIp() {
        return ip;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getAttemptedAt() {
        return attemptedAt;
    }
}
