package com.smarthospital.platform.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 80)
    private String code;

    @Column(name = "domain", nullable = false, length = 20)
    private String domain;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "description_ar", length = 255)
    private String descriptionAr;

    protected Permission() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDomain() {
        return domain;
    }

    public String getAction() {
        return action;
    }

    public String getDescriptionAr() {
        return descriptionAr;
    }
}
