-- Smart Hospital — one-time database bootstrap.
-- Run as a MySQL administrator:
--   mysql -u root -p < db/bootstrap.sql
-- Replace every CHANGE_ME value before running. Never commit real passwords.

CREATE DATABASE IF NOT EXISTS smart_hospital
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- Migration account: owns schema changes, never used by the application.
CREATE USER IF NOT EXISTS 'sh_migrator'@'localhost' IDENTIFIED BY 'CHANGE_ME_MIGRATOR';
CREATE USER IF NOT EXISTS 'sh_migrator'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_MIGRATOR';
GRANT ALL PRIVILEGES ON smart_hospital.* TO 'sh_migrator'@'localhost';
GRANT ALL PRIVILEGES ON smart_hospital.* TO 'sh_migrator'@'127.0.0.1';

-- Application account: DML only. No DDL, no DROP, no GRANT.
CREATE USER IF NOT EXISTS 'sh_app'@'localhost' IDENTIFIED BY 'CHANGE_ME_APP';
CREATE USER IF NOT EXISTS 'sh_app'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_APP';
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_hospital.* TO 'sh_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_hospital.* TO 'sh_app'@'127.0.0.1';

FLUSH PRIVILEGES;

-- Verification (expected: two rows).
SELECT user, host FROM mysql.user WHERE user IN ('sh_app', 'sh_migrator');
