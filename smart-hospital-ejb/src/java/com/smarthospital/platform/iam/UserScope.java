package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_scopes")
public class UserScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_ref_id", nullable = false)
    private long scopeRefId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected UserScope() {
    }

    public UserScope(Long userId, String scopeType, long scopeRefId) {
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeRefId = scopeRefId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public long getScopeRefId() {
        return scopeRefId;
    }
}
