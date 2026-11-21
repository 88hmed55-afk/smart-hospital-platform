-- Smart Hospital — V001
-- Foundation: platform structure + identity & access (Phase 1/2)
-- MySQL 8.0.38 · InnoDB · utf8mb4
-- Forward-only. Never edit after this file has been applied.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- Platform structure
-- ---------------------------------------------------------------------------

CREATE TABLE branches (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id     CHAR(26)        NOT NULL,
    code          VARCHAR(20)     NOT NULL,
    name_ar       VARCHAR(150)    NOT NULL,
    name_en       VARCHAR(150)    NULL,
    address       VARCHAR(255)    NULL,
    phone         VARCHAR(30)     NULL,
    timezone      VARCHAR(60)     NOT NULL DEFAULT 'Asia/Amman',
    active        TINYINT(1)      NOT NULL DEFAULT 1,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT UNSIGNED NULL,
    updated_at    DATETIME(6)     NULL,
    updated_by    BIGINT UNSIGNED NULL,
    version       BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_branches_public_id (public_id),
    UNIQUE KEY uq_branches_code (code),
    CONSTRAINT ck_branches_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE departments (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id     CHAR(26)        NOT NULL,
    branch_id     BIGINT UNSIGNED NOT NULL,
    code          VARCHAR(20)     NOT NULL,
    name_ar       VARCHAR(150)    NOT NULL,
    name_en       VARCHAR(150)    NULL,
    type          VARCHAR(30)     NOT NULL,
    active        TINYINT(1)      NOT NULL DEFAULT 1,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    BIGINT UNSIGNED NULL,
    updated_at    DATETIME(6)     NULL,
    updated_by    BIGINT UNSIGNED NULL,
    version       BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_departments_public_id (public_id),
    UNIQUE KEY uq_departments_branch_code (branch_id, code),
    KEY ix_departments_branch (branch_id),
    CONSTRAINT fk_departments_branches
        FOREIGN KEY (branch_id) REFERENCES branches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_departments_type
        CHECK (type IN ('CLINICAL', 'ADMIN', 'LAB', 'RAD', 'PHARMACY', 'ER', 'OR')),
    CONSTRAINT ck_departments_active CHECK (active IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settings (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key    VARCHAR(100)    NOT NULL,
    setting_value  TEXT            NULL,
    value_type     VARCHAR(20)     NOT NULL,
    category       VARCHAR(40)     NOT NULL,
    is_sensitive   TINYINT(1)      NOT NULL DEFAULT 0,
    updated_by     BIGINT UNSIGNED NULL,
    updated_at     DATETIME(6)     NULL,
    version        BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_settings_key (setting_key),
    KEY ix_settings_category (category),
    CONSTRAINT ck_settings_value_type
        CHECK (value_type IN ('STRING', 'INT', 'BOOL', 'JSON')),
    CONSTRAINT ck_settings_sensitive CHECK (is_sensitive IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Identity & access
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id             CHAR(26)        NOT NULL,
    username              VARCHAR(80)     NOT NULL,
    password_hash         VARCHAR(255)    NOT NULL,
    password_salt         VARBINARY(32)   NOT NULL,
    password_algo         VARCHAR(40)     NOT NULL DEFAULT 'PBKDF2WithHmacSHA256',
    password_iterations   INT             NOT NULL DEFAULT 600000,
    password_changed_at   DATETIME(6)     NULL,
    must_change_password  TINYINT(1)      NOT NULL DEFAULT 1,
    display_name_ar       VARCHAR(150)    NOT NULL,
    display_name_en       VARCHAR(150)    NULL,
    email                 VARCHAR(190)    NULL,
    phone                 VARCHAR(30)     NULL,
    branch_id             BIGINT UNSIGNED NULL,
    department_id         BIGINT UNSIGNED NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    failed_attempts       INT             NOT NULL DEFAULT 0,
    locked_until          DATETIME(6)     NULL,
    mfa_enabled           TINYINT(1)      NOT NULL DEFAULT 0,
    mfa_secret            VARBINARY(255)  NULL,
    last_login_at         DATETIME(6)     NULL,
    created_at            DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by            BIGINT UNSIGNED NULL,
    updated_at            DATETIME(6)     NULL,
    updated_by            BIGINT UNSIGNED NULL,
    version               BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_public_id (public_id),
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_email (email),
    KEY ix_users_status (status),
    KEY ix_users_branch (branch_id),
    KEY ix_users_department (department_id),
    CONSTRAINT fk_users_branches
        FOREIGN KEY (branch_id) REFERENCES branches (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_users_departments
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_users_status
        CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_users_mfa CHECK (mfa_enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    public_id    CHAR(26)        NOT NULL,
    code         VARCHAR(40)     NOT NULL,
    name_ar      VARCHAR(100)    NOT NULL,
    name_en      VARCHAR(100)    NOT NULL,
    description  VARCHAR(255)    NULL,
    system_role  TINYINT(1)      NOT NULL DEFAULT 1,
    created_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)     NULL,
    version      BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_roles_public_id (public_id),
    UNIQUE KEY uq_roles_code (code),
    CONSTRAINT ck_roles_system CHECK (system_role IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code           VARCHAR(80)     NOT NULL,
    domain         VARCHAR(20)     NOT NULL,
    action         VARCHAR(40)     NOT NULL,
    description_ar VARCHAR(255)    NULL,
    created_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_permissions_code (code),
    KEY ix_permissions_domain (domain)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id       BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    assigned_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    assigned_by   BIGINT UNSIGNED NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY ix_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_roles
        FOREIGN KEY (role_id) REFERENCES roles (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_role_permissions_permissions
        FOREIGN KEY (permission_id) REFERENCES permissions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id     BIGINT UNSIGNED NOT NULL,
    role_id     BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NULL,
    assigned_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_id),
    KEY ix_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_roles_roles
        FOREIGN KEY (role_id) REFERENCES roles (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_sessions (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    session_hash  CHAR(64)        NOT NULL,
    ip            VARCHAR(45)     NULL,
    user_agent    VARCHAR(255)    NULL,
    started_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at      DATETIME(6)     NULL,
    end_reason    VARCHAR(30)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_sessions_hash (session_hash),
    KEY ix_user_sessions_user (user_id, started_at),
    KEY ix_user_sessions_open (user_id, ended_at),
    CONSTRAINT fk_user_sessions_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_user_sessions_end_reason
        CHECK (end_reason IS NULL OR end_reason IN ('LOGOUT', 'EXPIRED', 'REVOKED', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE login_attempts (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username_attempted  VARCHAR(80)     NOT NULL,
    user_id             BIGINT UNSIGNED NULL,
    ip                  VARCHAR(45)     NOT NULL,
    success             TINYINT(1)      NOT NULL,
    reason              VARCHAR(50)     NULL,
    attempted_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_login_attempts_username_time (username_attempted, attempted_at),
    KEY ix_login_attempts_ip_time (ip, attempted_at),
    CONSTRAINT fk_login_attempts_users
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_login_attempts_success CHECK (success IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
