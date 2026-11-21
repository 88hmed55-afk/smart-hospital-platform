# 02 — Data Dictionary

| Field | Value |
|---|---|
| Document ID | DB-DIC-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Charset | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| Engine | InnoDB |
| Time | UTC (`DATETIME(6)`) |

> **كيف تُقرأ:** كل جدول يرث الأعمدة المشتركة في §1. لا تُكرَّر في الجداول أدناه إلا عند وجود اختلاف. الأنواع مطابقة لـ MySQL 8.0.38.

---

## 1. الأعمدة المشتركة (Base Columns)

| العمود | النوع | Null | Default | الوصف |
|---|---|---|---|---|
| `id` | `BIGINT UNSIGNED` | لا | AUTO_INCREMENT | مفتاح داخلي |
| `public_id` | `CHAR(26)` | لا | — | ULID، فريد، يُستخدم في API |
| `created_at` | `DATETIME(6)` | لا | CURRENT_TIMESTAMP(6) | UTC |
| `created_by` | `BIGINT UNSIGNED` | نعم | NULL | المستخدم (NULL للنظام) |
| `updated_at` | `DATETIME(6)` | نعم | NULL | UTC |
| `updated_by` | `BIGINT UNSIGNED` | نعم | NULL | المستخدم |
| `version` | `BIGINT` | لا | 0 | Optimistic locking |

**جداول Append-only** (لا `updated_*` ولا `version`): `audit_logs`, `access_logs`, `stock_movements`, `login_attempts`, `specimen_events`, `payment_allocations`, `notification_deliveries`.

**جداول مرجعية صغيرة** قد تستغني عن `public_id` (مثل `permissions`, `settings`).

---

## 2. اصطلاحات الأنواع

| النوع | الاستخدام |
|---|---|
| `BIGINT UNSIGNED` | معرفات ومراجع |
| `CHAR(26)` | ULID |
| `VARCHAR(n)` | نص قصير (n ≤ 255) |
| `TEXT` / `MEDIUMTEXT` | ملاحظات/محتوى |
| `DECIMAL(14,2)` | المال |
| `DECIMAL(10,3)` | القيم المخبرية |
| `DATE` | تاريخ ميلاد/انتهاء |
| `DATETIME(6)` | وقت UTC |
| `TINYINT(1)` | Boolean |
| `JSON` | Payload/مرونة |
| `ENUM` | **لا**؛ نستخدم `VARCHAR(30)` + `CHECK` |

**قاعدة:** لا `FLOAT/DOUBLE` للمال. لا `TIMESTAMP` إلا للتوافق (نستخدم `DATETIME(6)`).

---

## 3. IAM — الهوية والوصول

### `users`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `username` | `VARCHAR(80)` | لا | فريد، حروف لاتينية/أرقام |
| `password_hash` | `VARCHAR(255)` | لا | Base64 لـ PBKDF2 |
| `password_salt` | `VARBINARY(32)` | لا | 16 بايت |
| `password_algo` | `VARCHAR(40)` | لا | `PBKDF2WithHmacSHA256` |
| `password_iterations` | `INT` | لا | 600000 |
| `password_changed_at` | `DATETIME(6)` | نعم | |
| `must_change_password` | `TINYINT(1)` | لا | 1 عند الإنشاء/إعادة التعيين |
| `display_name_ar` | `VARCHAR(150)` | لا | |
| `display_name_en` | `VARCHAR(150)` | نعم | |
| `email` | `VARCHAR(190)` | نعم | فريد إن وُجد |
| `phone` | `VARCHAR(30)` | نعم | |
| `branch_id` | `BIGINT UNSIGNED` | نعم | FK |
| `department_id` | `BIGINT UNSIGNED` | نعم | FK |
| `status` | `VARCHAR(20)` | لا | `ACTIVE`, `LOCKED`, `SUSPENDED`, `DISABLED` |
| `failed_attempts` | `INT` | لا | 0 |
| `locked_until` | `DATETIME(6)` | نعم | |
| `mfa_enabled` | `TINYINT(1)` | لا | 0 |
| `mfa_secret` | `VARBINARY(255)` | نعم | مشفّر |
| `last_login_at` | `DATETIME(6)` | نعم | |

**فهارس:** `UNIQUE(username)`, `UNIQUE(email)`, `(status)`, `(branch_id)`.

### `roles`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `code` | `VARCHAR(40)` | لا | `DOCTOR`, `NURSE`... فريد |
| `name_ar` | `VARCHAR(100)` | لا | |
| `name_en` | `VARCHAR(100)` | لا | |
| `description` | `VARCHAR(255)` | نعم | |
| `system_role` | `TINYINT(1)` | لا | 1 = لا يُحذف |

### `permissions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `code` | `VARCHAR(80)` | لا | `PAT:READ` فريد |
| `domain` | `VARCHAR(20)` | لا | `PAT` |
| `action` | `VARCHAR(40)` | لا | `READ` |
| `description_ar` | `VARCHAR(255)` | نعم | |

### `role_permissions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `role_id` | `BIGINT UNSIGNED` | لا | FK |
| `permission_id` | `BIGINT UNSIGNED` | لا | FK |

**PK مركب:** `(role_id, permission_id)`.

### `user_roles`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `user_id` | `BIGINT UNSIGNED` | لا | FK |
| `role_id` | `BIGINT UNSIGNED` | لا | FK |
| `assigned_by` | `BIGINT UNSIGNED` | نعم | |
| `assigned_at` | `DATETIME(6)` | لا | |

**PK مركب:** `(user_id, role_id)`.

### `user_sessions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `user_id` | `BIGINT UNSIGNED` | لا | |
| `session_hash` | `CHAR(64)` | لا | SHA-256 لمعرف الجلسة |
| `ip` | `VARCHAR(45)` | نعم | IPv4/IPv6 |
| `user_agent` | `VARCHAR(255)` | نعم | |
| `started_at` | `DATETIME(6)` | لا | |
| `last_seen_at` | `DATETIME(6)` | لا | |
| `ended_at` | `DATETIME(6)` | نعم | |
| `end_reason` | `VARCHAR(30)` | نعم | `LOGOUT`, `EXPIRED`, `REVOKED` |

### `login_attempts`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `username_attempted` | `VARCHAR(80)` | لا | |
| `user_id` | `BIGINT UNSIGNED` | نعم | إن وُجد |
| `ip` | `VARCHAR(45)` | لا | |
| `success` | `TINYINT(1)` | لا | |
| `reason` | `VARCHAR(50)` | نعم | `BAD_PASSWORD`, `LOCKED` |
| `attempted_at` | `DATETIME(6)` | لا | |

### `break_glass_grants`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `user_id` | `BIGINT UNSIGNED` | لا | |
| `patient_id` | `BIGINT UNSIGNED` | لا | مريض واحد |
| `reason` | `VARCHAR(500)` | لا | ≥ 15 حرفًا |
| `status` | `VARCHAR(20)` | لا | `REQUESTED`, `GRANTED`, `EXPIRED`, `REVOKED`, `REVIEWED` |
| `granted_at` | `DATETIME(6)` | نعم | |
| `expires_at` | `DATETIME(6)` | نعم | ≤ 60 دقيقة |
| `revoked_at` | `DATETIME(6)` | نعم | |
| `reviewed_by` | `BIGINT UNSIGNED` | نعم | |
| `reviewed_at` | `DATETIME(6)` | نعم | |

---

## 4. SYS — المنصة والهيكل

### `settings`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `setting_key` | `VARCHAR(100)` | لا | فريد |
| `setting_value` | `TEXT` | نعم | |
| `value_type` | `VARCHAR(20)` | لا | `STRING`, `INT`, `BOOL`, `JSON` |
| `category` | `VARCHAR(40)` | لا | `HOSPITAL`, `SECURITY`, `PRINT` |
| `is_sensitive` | `TINYINT(1)` | لا | 0 |
| `updated_by` | `BIGINT UNSIGNED` | نعم | |

### `branches`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `code` | `VARCHAR(20)` | لا | فريد |
| `name_ar` | `VARCHAR(150)` | لا | |
| `name_en` | `VARCHAR(150)` | نعم | |
| `address` | `VARCHAR(255)` | نعم | |
| `phone` | `VARCHAR(30)` | نعم | |
| `timezone` | `VARCHAR(60)` | لا | `Asia/Amman` |
| `active` | `TINYINT(1)` | لا | 1 |

### `departments`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `branch_id` | `BIGINT UNSIGNED` | لا | FK |
| `code` | `VARCHAR(20)` | لا | فريد داخل الفرع |
| `name_ar` | `VARCHAR(150)` | لا | |
| `name_en` | `VARCHAR(150)` | نعم | |
| `type` | `VARCHAR(30)` | لا | `CLINICAL`, `ADMIN`, `LAB`, `RAD`, `PHARMACY` |
| `active` | `TINYINT(1)` | لا | 1 |

### `clinics`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `department_id` | `BIGINT UNSIGNED` | لا | FK |
| `name_ar` | `VARCHAR(150)` | لا | |
| `name_en` | `VARCHAR(150)` | نعم | |
| `location` | `VARCHAR(100)` | نعم | |
| `active` | `TINYINT(1)` | لا | 1 |

### `wards` / `rooms` / `beds`

| الجدول | الأعمدة الخاصة |
|---|---|
| `wards` | `department_id`, `name_ar`, `name_en`, `ward_type` (`GENERAL`,`ICU`,`MATERNITY`), `floor`, `active` |
| `rooms` | `ward_id`, `room_no`, `room_type` (`PRIVATE`,`SHARED`,`ISOLATION`), `capacity`, `active` |
| `beds` | `room_id`, `bed_no`, `bed_type`, `status` (`AVAILABLE`,`OCCUPIED`,`RESERVED`,`CLEANING`,`MAINTENANCE`,`BLOCKED`), `active` |

**قيد:** `UNIQUE(room_id, bed_no)`.

### `service_catalog`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `code` | `VARCHAR(40)` | لا | فريد |
| `name_ar` | `VARCHAR(200)` | لا | |
| `name_en` | `VARCHAR(200)` | نعم | |
| `category` | `VARCHAR(40)` | لا | `CONSULTATION`, `LAB`, `RAD`, `PROCEDURE`, `DRUG` |
| `department_id` | `BIGINT UNSIGNED` | نعم | |
| `taxable` | `TINYINT(1)` | لا | 0 |
| `active` | `TINYINT(1)` | لا | 1 |

### `service_prices`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `service_id` | `BIGINT UNSIGNED` | لا | FK |
| `amount` | `DECIMAL(14,2)` | لا | |
| `currency` | `CHAR(3)` | لا | `JOD` |
| `valid_from` | `DATE` | لا | |
| `valid_to` | `DATE` | نعم | NULL = ساري |
| `created_by` | `BIGINT UNSIGNED` | لا | |

**قاعدة:** لا يُعدَّل سعر ساري؛ يُغلق بـ `valid_to` ويُضاف سعر جديد (BR-SYS-003).

### `working_hours` / `holidays`

| الجدول | الأعمدة |
|---|---|
| `working_hours` | `branch_id`, `day_of_week` (1–7), `open_time`, `close_time`, `is_closed` |
| `holidays` | `branch_id`, `holiday_date`, `name_ar`, `name_en` |

### `templates`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `template_type` | `VARCHAR(40)` | لا | `NOTE`, `ORDER_SET`, `NOTIFICATION`, `PRINT` |
| `code` | `VARCHAR(60)` | لا | فريد داخل النوع |
| `name_ar` | `VARCHAR(150)` | لا | |
| `content` | `MEDIUMTEXT` | لا | JSON/HTML |
| `version_no` | `INT` | لا | |
| `active` | `TINYINT(1)` | لا | 0 افتراضيًا حتى الاعتماد |

### `attachments`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `owner_type` | `VARCHAR(30)` | لا | `PATIENT`, `ENCOUNTER`, `LAB_RESULT` |
| `owner_id` | `BIGINT UNSIGNED` | لا | |
| `file_name` | `VARCHAR(255)` | لا | الاسم الأصلي (للعرض) |
| `storage_key` | `VARCHAR(255)` | لا | مسار عشوائي |
| `mime_type` | `VARCHAR(100)` | لا | |
| `size_bytes` | `BIGINT` | لا | ≤ 10MB |
| `sha256` | `CHAR(64)` | لا | تحقق سلامة |
| `uploaded_by` | `BIGINT UNSIGNED` | لا | |

### `audit_logs` (Append-only)

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `actor_user_id` | `BIGINT UNSIGNED` | نعم | |
| `action` | `VARCHAR(80)` | لا | `PATIENT_VIEW`, `INVOICE_VOID` |
| `entity_type` | `VARCHAR(40)` | لا | |
| `entity_public_id` | `CHAR(26)` | نعم | |
| `before_json` | `JSON` | نعم | الحقول الحساسة فقط |
| `after_json` | `JSON` | نعم | |
| `reason` | `VARCHAR(500)` | نعم | |
| `ip` | `VARCHAR(45)` | نعم | |
| `trace_id` | `CHAR(32)` | نعم | |
| `prev_hash` | `CHAR(64)` | نعم | |
| `hash` | `CHAR(64)` | لا | SHA-256 |
| `created_at` | `DATETIME(6)` | لا | |

**Partitioning:** شهري على `created_at`.

### `access_logs`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `user_id` | `BIGINT UNSIGNED` | لا | |
| `patient_id` | `BIGINT UNSIGNED` | نعم | |
| `resource_type` | `VARCHAR(40)` | لا | |
| `resource_public_id` | `CHAR(26)` | نعم | |
| `action` | `VARCHAR(20)` | لا | `READ`, `EXPORT`, `PRINT` |
| `ip` | `VARCHAR(45)` | نعم | |
| `accessed_at` | `DATETIME(6)` | لا | |

### `outbox_events` / `idempotency_keys` / `job_runs`

| الجدول | الأعمدة الأساسية |
|---|---|
| `outbox_events` | `event_id` (ULID, UK), `event_type`, `aggregate_type`, `aggregate_id`, `payload_json`, `status`, `attempts`, `next_attempt_at`, `last_error`, `processed_at` |
| `idempotency_keys` | `user_id`, `endpoint`, `key`, `request_hash`, `response_json`, `status`, `expires_at` — `UNIQUE(user_id, endpoint, key)` |
| `job_runs` | `job_name`, `started_at`, `finished_at`, `status`, `processed_count`, `error_message` |

---

## 5. PAT — المرضى

### `patients`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `medical_number` | `VARCHAR(20)` | لا | فريد، `P-2026-000421` |
| `full_name` | `VARCHAR(200)` | لا | |
| `name_normalized` | `VARCHAR(200)` | لا | للبحث/التشابه |
| `gender` | `VARCHAR(10)` | لا | `MALE`, `FEMALE`, `UNKNOWN` |
| `birth_date` | `DATE` | نعم | |
| `birth_date_estimated` | `TINYINT(1)` | لا | 0 |
| `national_id` | `VARCHAR(40)` | نعم | فريد إن وُجد |
| `phone` | `VARCHAR(30)` | نعم | |
| `email` | `VARCHAR(190)` | نعم | |
| `address` | `VARCHAR(500)` | نعم | |
| `blood_group` | `VARCHAR(5)` | نعم | |
| `marital_status` | `VARCHAR(20)` | نعم | |
| `status` | `VARCHAR(20)` | لا | `ACTIVE`, `MERGED`, `INACTIVE`, `DECEASED` |
| `merged_into_id` | `BIGINT UNSIGNED` | نعم | |
| `notes` | `TEXT` | نعم | |

### `patient_identifiers`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `type` | `VARCHAR(30)` | لا | `NATIONAL_ID`, `PASSPORT`, `INSURANCE`, `MRN` |
| `value` | `VARCHAR(80)` | لا | |
| `is_primary` | `TINYINT(1)` | لا | 0 |

**فهرس:** `(type, value)`.

### `patient_contacts`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `contact_type` | `VARCHAR(20)` | لا | `EMERGENCY`, `GUARDIAN`, `NEXT_OF_KIN` |
| `full_name` | `VARCHAR(150)` | لا | |
| `relation` | `VARCHAR(40)` | نعم | |
| `phone` | `VARCHAR(30)` | لا | |
| `is_primary` | `TINYINT(1)` | لا | 0 |

### `patient_allergies`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `substance` | `VARCHAR(150)` | لا | |
| `allergy_type` | `VARCHAR(30)` | لا | `DRUG`, `FOOD`, `ENVIRONMENT` |
| `severity` | `VARCHAR(20)` | لا | `MILD`, `MODERATE`, `SEVERE`, `LIFE_THREATENING` |
| `reaction` | `VARCHAR(255)` | نعم | |
| `active` | `TINYINT(1)` | لا | 1 |
| `recorded_by` | `BIGINT UNSIGNED` | لا | |

### `patient_conditions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `concept_id` | `BIGINT UNSIGNED` | نعم | |
| `description` | `VARCHAR(255)` | لا | |
| `onset_date` | `DATE` | نعم | |
| `status` | `VARCHAR(20)` | لا | `ACTIVE`, `RESOLVED` |

### `patient_merges`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `source_patient_id` | `BIGINT UNSIGNED` | لا | |
| `target_patient_id` | `BIGINT UNSIGNED` | لا | |
| `reason` | `VARCHAR(500)` | لا | |
| `merged_by` | `BIGINT UNSIGNED` | لا | |
| `merged_at` | `DATETIME(6)` | لا | |

### `patient_flags`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `flag_type` | `VARCHAR(30)` | لا | `VIP`, `DIFFICULT_IV`, `INFECTION` |
| `note` | `VARCHAR(255)` | نعم | |
| `active` | `TINYINT(1)` | لا | 1 |

### `consents`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `consent_type` | `VARCHAR(40)` | لا | `TREATMENT`, `SHARING`, `RESEARCH` |
| `granted` | `TINYINT(1)` | لا | |
| `granted_at` | `DATETIME(6)` | نعم | |
| `revoked_at` | `DATETIME(6)` | نعم | |
| `document_id` | `BIGINT UNSIGNED` | نعم | |

---

## 6. SCH / EMR — المواعيد والزيارة

### `provider_schedules`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `provider_id` | `BIGINT UNSIGNED` | لا | user/doctor |
| `clinic_id` | `BIGINT UNSIGNED` | لا | |
| `day_of_week` | `TINYINT` | لا | 1–7 |
| `start_time` | `TIME` | لا | |
| `end_time` | `TIME` | لا | |
| `slot_minutes` | `SMALLINT` | لا | 15/20/30 |
| `capacity` | `SMALLINT` | نعم | |
| `valid_from` | `DATE` | لا | |
| `valid_to` | `DATE` | نعم | |

### `schedule_exceptions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `provider_id` | `BIGINT UNSIGNED` | لا | |
| `exception_date` | `DATE` | لا | |
| `start_time` | `TIME` | نعم | NULL = يوم كامل |
| `end_time` | `TIME` | نعم | |
| `reason` | `VARCHAR(255)` | نعم | |

### `appointments`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `provider_id` | `BIGINT UNSIGNED` | لا | |
| `clinic_id` | `BIGINT UNSIGNED` | لا | |
| `slot_start` | `DATETIME(6)` | لا | UTC |
| `slot_end` | `DATETIME(6)` | لا | |
| `status` | `VARCHAR(20)` | لا | `BOOKED`, `CHECKED_IN`, `COMPLETED`, `CANCELLED`, `NO_SHOW` |
| `source` | `VARCHAR(20)` | لا | `RECEPTION`, `PHONE`, `WALK_IN` |
| `cancel_reason` | `VARCHAR(255)` | نعم | |
| `active_flag` | `TINYINT` | نعم | 1 للنشط، NULL للمنتهي |
| `checked_in_at` | `DATETIME(6)` | نعم | |

**فهرس فريد:** `(provider_id, slot_start, active_flag)`.

### `appointment_status_history`

| العمود | النوع | Null |
|---|---|---|
| `appointment_id` | `BIGINT UNSIGNED` | لا |
| `from_status` | `VARCHAR(20)` | نعم |
| `to_status` | `VARCHAR(20)` | لا |
| `changed_by` | `BIGINT UNSIGNED` | لا |
| `changed_at` | `DATETIME(6)` | لا |
| `reason` | `VARCHAR(255)` | نعم |

### `queue_tickets`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `appointment_id` | `BIGINT UNSIGNED` | نعم | |
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `clinic_id` | `BIGINT UNSIGNED` | لا | |
| `ticket_no` | `INT` | لا | يومي |
| `priority` | `VARCHAR(20)` | لا | `NORMAL`, `URGENT`, `EMERGENCY` |
| `status` | `VARCHAR(20)` | لا | `WAITING`, `CALLED`, `IN_SERVICE`, `DONE`, `LEFT` |
| `checked_in_at` | `DATETIME(6)` | لا | |
| `called_at` | `DATETIME(6)` | نعم | |
| `served_at` | `DATETIME(6)` | نعم | |

### `waitlist_entries`

| العمود | النوع | Null |
|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا |
| `provider_id` | `BIGINT UNSIGNED` | لا |
| `desired_from` | `DATE` | لا |
| `desired_to` | `DATE` | نعم |
| `priority` | `VARCHAR(20)` | لا |
| `status` | `VARCHAR(20)` | لا |

### `encounters`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `appointment_id` | `BIGINT UNSIGNED` | نعم | |
| `provider_id` | `BIGINT UNSIGNED` | لا | |
| `department_id` | `BIGINT UNSIGNED` | نعم | |
| `type` | `VARCHAR(20)` | لا | `OUTPATIENT`, `EMERGENCY`, `INPATIENT` |
| `status` | `VARCHAR(20)` | لا | `OPEN`, `CLOSED`, `CANCELLED` |
| `chief_complaint` | `VARCHAR(500)` | نعم | |
| `started_at` | `DATETIME(6)` | لا | |
| `closed_at` | `DATETIME(6)` | نعم | |
| `closed_by` | `BIGINT UNSIGNED` | نعم | |

### `clinical_notes`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `note_type` | `VARCHAR(30)` | لا | `PROGRESS`, `ADMISSION`, `DISCHARGE`, `NURSING` |
| `author_id` | `BIGINT UNSIGNED` | لا | |
| `current_version_id` | `BIGINT UNSIGNED` | نعم | |
| `status` | `VARCHAR(20)` | لا | `DRAFT`, `SIGNED`, `AMENDED` |

### `clinical_note_versions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `note_id` | `BIGINT UNSIGNED` | لا | |
| `version_no` | `INT` | لا | يبدأ 1 |
| `content_json` | `JSON` | لا | أقسام الملاحظة |
| `content_text` | `MEDIUMTEXT` | نعم | للبحث |
| `amends_id` | `BIGINT UNSIGNED` | نعم | النسخة المصححة |
| `amend_reason` | `VARCHAR(500)` | نعم | إلزامي عند `amends_id` |
| `signed_by` | `BIGINT UNSIGNED` | نعم | |
| `signed_at` | `DATETIME(6)` | نعم | |

**فهرس فريد:** `(note_id, version_no)`.

### `diagnoses`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `concept_id` | `BIGINT UNSIGNED` | نعم | |
| `code` | `VARCHAR(20)` | نعم | ICD اختياري |
| `description` | `VARCHAR(255)` | لا | |
| `diagnosis_type` | `VARCHAR(20)` | لا | `PRIMARY`, `SECONDARY` |
| `diagnosed_by` | `BIGINT UNSIGNED` | لا | |

**قيد:** أساسي واحد لكل زيارة (Unique جزئي عبر `active_flag`).

### `vitals`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `temperature_c` | `DECIMAL(4,1)` | نعم | |
| `heart_rate` | `SMALLINT` | نعم | |
| `respiratory_rate` | `SMALLINT` | نعم | |
| `systolic` / `diastolic` | `SMALLINT` | نعم | |
| `spo2` | `TINYINT` | نعم | |
| `weight_kg` | `DECIMAL(5,1)` | نعم | |
| `height_cm` | `DECIMAL(5,1)` | نعم | |
| `pain_score` | `TINYINT` | نعم | 0–10 |
| `recorded_by` | `BIGINT UNSIGNED` | لا | |
| `recorded_at` | `DATETIME(6)` | لا | |

### `service_orders`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `order_type` | `VARCHAR(20)` | لا | `LAB`, `RAD`, `PROCEDURE` |
| `ordered_by` | `BIGINT UNSIGNED` | لا | |
| `status` | `VARCHAR(20)` | لا | `ORDERED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` |
| `priority` | `VARCHAR(20)` | لا | `ROUTINE`, `URGENT`, `STAT` |
| `notes` | `VARCHAR(500)` | نعم | |

### `order_items`

| العمود | النوع | Null |
|---|---|---|
| `order_id` | `BIGINT UNSIGNED` | لا |
| `service_id` | `BIGINT UNSIGNED` | لا |
| `quantity` | `SMALLINT` | لا |
| `status` | `VARCHAR(20)` | لا |

### `prescriptions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `prescribed_by` | `BIGINT UNSIGNED` | لا | |
| `status` | `VARCHAR(20)` | لا | `DRAFT`, `ISSUED`, `REVIEWED`, `DISPENSED`, `PARTIAL`, `CANCELLED` |
| `issued_at` | `DATETIME(6)` | نعم | |
| `reviewed_by` | `BIGINT UNSIGNED` | نعم | |
| `reviewed_at` | `DATETIME(6)` | نعم | |

### `prescription_items`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `prescription_id` | `BIGINT UNSIGNED` | لا | |
| `drug_id` | `BIGINT UNSIGNED` | لا | |
| `dose` | `VARCHAR(50)` | لا | |
| `route` | `VARCHAR(30)` | لا | |
| `frequency` | `VARCHAR(50)` | لا | |
| `duration_days` | `SMALLINT` | نعم | |
| `quantity` | `INT` | لا | |
| `instructions` | `VARCHAR(500)` | نعم | |

### `referrals`

| العمود | النوع | Null |
|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا |
| `referred_to_department_id` | `BIGINT UNSIGNED` | نعم |
| `referred_to_provider_id` | `BIGINT UNSIGNED` | نعم |
| `reason` | `VARCHAR(500)` | لا |
| `status` | `VARCHAR(20)` | لا |

### `concepts` / `concept_mappings`

| الجدول | الأعمدة |
|---|---|
| `concepts` | `code`, `display_ar`, `display_en`, `concept_type` (`DIAGNOSIS`,`LAB`,`DRUG`,`SYMPTOM`), `active` |
| `concept_mappings` | `concept_id`, `system` (`ICD10`,`LOINC`,`ATC`,`LOCAL`), `code`, `display`, `version` |

---

## 7. ER / ADT / NUR

### `er_visits`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `encounter_id` | `BIGINT UNSIGNED` | نعم | |
| `arrived_at` | `DATETIME(6)` | لا | |
| `triage_level` | `VARCHAR(10)` | نعم | 1–5 |
| `status` | `VARCHAR(20)` | لا | `WAITING`, `TRIAGED`, `IN_TREATMENT`, `DISPOSITION`, `LEFT` |
| `disposition` | `VARCHAR(30)` | نعم | `ADMIT`, `DISCHARGE`, `TRANSFER`, `CLINIC` |
| `left_without_being_seen` | `TINYINT(1)` | لا | 0 |

### `triage_assessments`

| العمود | النوع | Null |
|---|---|---|
| `er_visit_id` | `BIGINT UNSIGNED` | لا |
| `level` | `VARCHAR(10)` | لا |
| `protocol_code` | `VARCHAR(40)` | نعم |
| `notes` | `VARCHAR(500)` | نعم |
| `assessed_by` | `BIGINT UNSIGNED` | لا |
| `assessed_at` | `DATETIME(6)` | لا |

### `admissions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `encounter_id` | `BIGINT UNSIGNED` | لا | |
| `admitting_doctor_id` | `BIGINT UNSIGNED` | لا | |
| `ward_id` | `BIGINT UNSIGNED` | نعم | |
| `status` | `VARCHAR(20)` | لا | `REQUESTED`, `APPROVED`, `ADMITTED`, `DISCHARGED`, `CANCELLED` |
| `admitted_at` | `DATETIME(6)` | نعم | |
| `discharged_at` | `DATETIME(6)` | نعم | |
| `reason` | `VARCHAR(500)` | نعم | |

### `bed_assignments`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `admission_id` | `BIGINT UNSIGNED` | لا | |
| `bed_id` | `BIGINT UNSIGNED` | لا | |
| `active_flag` | `TINYINT` | نعم | 1 للنشط، NULL للمنتهي |
| `started_at` | `DATETIME(6)` | لا | |
| `ended_at` | `DATETIME(6)` | نعم | |
| `assigned_by` | `BIGINT UNSIGNED` | لا | |

**قيد فريد:** `(bed_id, active_flag)`.

### `bed_transfers`

| العمود | النوع | Null |
|---|---|---|
| `admission_id` | `BIGINT UNSIGNED` | لا |
| `from_bed_id` | `BIGINT UNSIGNED` | نعم |
| `to_bed_id` | `BIGINT UNSIGNED` | لا |
| `reason` | `VARCHAR(255)` | لا |
| `transferred_by` | `BIGINT UNSIGNED` | لا |
| `transferred_at` | `DATETIME(6)` | لا |

### `discharges`

| العمود | النوع | Null |
|---|---|---|
| `admission_id` | `BIGINT UNSIGNED` | لا |
| `discharge_type` | `VARCHAR(20)` | لا |
| `summary_note_id` | `BIGINT UNSIGNED` | نعم |
| `discharged_by` | `BIGINT UNSIGNED` | لا |
| `discharged_at` | `DATETIME(6)` | لا |

### `bed_status_history`

| العمود | النوع | Null |
|---|---|---|
| `bed_id` | `BIGINT UNSIGNED` | لا |
| `from_status` | `VARCHAR(20)` | نعم |
| `to_status` | `VARCHAR(20)` | لا |
| `changed_by` | `BIGINT UNSIGNED` | لا |
| `changed_at` | `DATETIME(6)` | لا |

### `nursing_tasks`

| العمود | النوع | Null |
|---|---|---|
| `admission_id` | `BIGINT UNSIGNED` | نعم |
| `patient_id` | `BIGINT UNSIGNED` | لا |
| `task_type` | `VARCHAR(40)` | لا |
| `description` | `VARCHAR(500)` | نعم |
| `assigned_to` | `BIGINT UNSIGNED` | نعم |
| `due_at` | `DATETIME(6)` | نعم |
| `status` | `VARCHAR(20)` | لا |
| `completed_at` | `DATETIME(6)` | نعم |

### `medication_administrations`

| العمود | النوع | Null |
|---|---|---|
| `prescription_item_id` | `BIGINT UNSIGNED` | لا |
| `patient_id` | `BIGINT UNSIGNED` | لا |
| `batch_id` | `BIGINT UNSIGNED` | نعم |
| `administered_by` | `BIGINT UNSIGNED` | لا |
| `administered_at` | `DATETIME(6)` | لا |
| `dose_given` | `VARCHAR(50)` | لا |
| `barcode_verified` | `TINYINT(1)` | لا |

### `intake_output`

| العمود | النوع | Null |
|---|---|---|
| `admission_id` | `BIGINT UNSIGNED` | لا |
| `record_type` | `VARCHAR(10)` | لا |
| `amount_ml` | `INT` | لا |
| `recorded_at` | `DATETIME(6)` | لا |
| `recorded_by` | `BIGINT UNSIGNED` | لا |

---

## 8. LAB / RAD

### `lab_tests`

| العمود | النوع | Null |
|---|---|---|
| `code` | `VARCHAR(30)` | لا |
| `name_ar` | `VARCHAR(150)` | لا |
| `name_en` | `VARCHAR(150)` | نعم |
| `specimen_type` | `VARCHAR(40)` | لا |
| `unit` | `VARCHAR(20)` | نعم |
| `tat_minutes` | `INT` | نعم |
| `active` | `TINYINT(1)` | لا |

### `lab_orders`

| العمود | النوع | Null |
|---|---|---|
| `encounter_id` | `BIGINT UNSIGNED` | لا |
| `patient_id` | `BIGINT UNSIGNED` | لا |
| `ordered_by` | `BIGINT UNSIGNED` | لا |
| `priority` | `VARCHAR(20)` | لا |
| `status` | `VARCHAR(20)` | لا |
| `ordered_at` | `DATETIME(6)` | لا |

### `lab_order_items`

| العمود | النوع | Null |
|---|---|---|
| `lab_order_id` | `BIGINT UNSIGNED` | لا |
| `lab_test_id` | `BIGINT UNSIGNED` | لا |
| `status` | `VARCHAR(20)` | لا |

### `specimens`

| العمود | النوع | Null |
|---|---|---|
| `lab_order_id` | `BIGINT UNSIGNED` | لا |
| `barcode` | `VARCHAR(40)` | لا |
| `specimen_type` | `VARCHAR(40)` | لا |
| `status` | `VARCHAR(20)` | لا |
| `collected_by` | `BIGINT UNSIGNED` | نعم |
| `collected_at` | `DATETIME(6)` | نعم |
| `received_at` | `DATETIME(6)` | نعم |

### `specimen_events`

| العمود | النوع | Null |
|---|---|---|
| `specimen_id` | `BIGINT UNSIGNED` | لا |
| `event_type` | `VARCHAR(30)` | لا |
| `from_status` | `VARCHAR(20)` | نعم |
| `to_status` | `VARCHAR(20)` | لا |
| `actor_id` | `BIGINT UNSIGNED` | لا |
| `event_at` | `DATETIME(6)` | لا |

### `lab_results`

| العمود | النوع | Null |
|---|---|---|
| `lab_order_item_id` | `BIGINT UNSIGNED` | لا |
| `current_version_id` | `BIGINT UNSIGNED` | نعم |
| `status` | `VARCHAR(20)` | لا |
| `flag` | `VARCHAR(10)` | نعم |
| `released_at` | `DATETIME(6)` | نعم |

### `lab_result_versions`

| العمود | النوع | Null |
|---|---|---|
| `result_id` | `BIGINT UNSIGNED` | لا |
| `version_no` | `INT` | لا |
| `value_numeric` | `DECIMAL(12,3)` | نعم |
| `value_text` | `VARCHAR(255)` | نعم |
| `unit` | `VARCHAR(20)` | نعم |
| `reference_low` | `DECIMAL(12,3)` | نعم |
| `reference_high` | `DECIMAL(12,3)` | نعم |
| `flag` | `VARCHAR(10)` | نعم |
| `amends_id` | `BIGINT UNSIGNED` | نعم |
| `amend_reason` | `VARCHAR(500)` | نعم |
| `entered_by` | `BIGINT UNSIGNED` | لا |
| `verified_by` | `BIGINT UNSIGNED` | نعم |
| `verified_at` | `DATETIME(6)` | نعم |

### `lab_reference_ranges`

| العمود | النوع | Null |
|---|---|---|
| `lab_test_id` | `BIGINT UNSIGNED` | لا |
| `gender` | `VARCHAR(10)` | نعم |
| `age_min` | `SMALLINT` | نعم |
| `age_max` | `SMALLINT` | نعم |
| `low_value` | `DECIMAL(12,3)` | نعم |
| `high_value` | `DECIMAL(12,3)` | نعم |
| `critical_low` | `DECIMAL(12,3)` | نعم |
| `critical_high` | `DECIMAL(12,3)` | نعم |

### `radiology_orders` / `radiology_studies` / `radiology_reports`

| الجدول | الأعمدة الخاصة |
|---|---|
| `radiology_orders` | `encounter_id`, `patient_id`, `ordered_by`, `status`, `scheduled_at`, `priority` |
| `radiology_studies` | `radiology_order_id`, `study_uid` (فريد اختياري), `performed_at`, `performed_by`, `modality` |
| `radiology_reports` | `radiology_order_id`, `current_version_id`, `status`, `signed_by`, `signed_at`, `content_json` |

---

## 9. PHR / INV

### `drugs`

| العمود | النوع | Null |
|---|---|---|
| `code` | `VARCHAR(30)` | لا |
| `name_ar` | `VARCHAR(150)` | لا |
| `name_en` | `VARCHAR(150)` | نعم |
| `form` | `VARCHAR(40)` | لا |
| `strength` | `VARCHAR(40)` | نعم |
| `category` | `VARCHAR(40)` | نعم |
| `reorder_level` | `INT` | لا |
| `active` | `TINYINT(1)` | لا |

### `drug_interactions`

| العمود | النوع | Null |
|---|---|---|
| `drug_a_id` | `BIGINT UNSIGNED` | لا |
| `drug_b_id` | `BIGINT UNSIGNED` | لا |
| `severity` | `VARCHAR(20)` | لا |
| `description` | `VARCHAR(500)` | لا |
| `active` | `TINYINT(1)` | لا |

**قيد:** `UNIQUE(drug_a_id, drug_b_id)` مع ترتيب تصاعدي.

### `warehouses`

| العمود | النوع | Null |
|---|---|---|
| `code` | `VARCHAR(20)` | لا |
| `name_ar` | `VARCHAR(100)` | لا |
| `type` | `VARCHAR(20)` | لا |
| `branch_id` | `BIGINT UNSIGNED` | لا |
| `active` | `TINYINT(1)` | لا |

### `stock_batches`

| العمود | النوع | Null |
|---|---|---|
| `drug_id` | `BIGINT UNSIGNED` | لا |
| `warehouse_id` | `BIGINT UNSIGNED` | لا |
| `batch_no` | `VARCHAR(40)` | لا |
| `expiry_date` | `DATE` | لا |
| `quantity_on_hand` | `INT` | لا |
| `quantity_reserved` | `INT` | لا |
| `unit_cost` | `DECIMAL(14,2)` | نعم |
| `status` | `VARCHAR(20)` | لا |

**قيد:** `UNIQUE(drug_id, warehouse_id, batch_no)`, `CHECK(quantity_on_hand >= 0)`.

### `stock_movements` (Append-only)

| العمود | النوع | Null |
|---|---|---|
| `batch_id` | `BIGINT UNSIGNED` | لا |
| `movement_type` | `VARCHAR(30)` | لا |
| `quantity` | `INT` | لا |
| `reference_type` | `VARCHAR(30)` | نعم |
| `reference_id` | `BIGINT UNSIGNED` | نعم |
| `performed_by` | `BIGINT UNSIGNED` | لا |
| `performed_at` | `DATETIME(6)` | لا |

### `dispensing_transactions` / `dispensing_items`

| الجدول | الأعمدة |
|---|---|
| `dispensing_transactions` | `prescription_id`, `dispensed_by`, `status`, `dispensed_at`, `notes` |
| `dispensing_items` | `dispensing_id`, `prescription_item_id`, `stock_batch_id`, `quantity`, `unit_price` |

### `inventory_items` / `suppliers` / `purchase_orders`

| الجدول | الأعمدة الخاصة |
|---|---|
| `inventory_items` | `code`, `name_ar`, `category`, `unit`, `reorder_level`, `active` |
| `suppliers` | `code`, `name_ar`, `phone`, `email`, `address`, `active` |
| `purchase_orders` | `order_no`, `supplier_id`, `status`, `ordered_at`, `expected_at`, `total_amount` |
| `purchase_order_items` | `purchase_order_id`, `item_id`, `quantity`, `unit_price`, `received_quantity` |
| `goods_receipts` | `purchase_order_id`, `received_by`, `received_at`, `notes` |
| `stock_counts` | `warehouse_id`, `status`, `started_at`, `closed_at` |
| `stock_count_lines` | `stock_count_id`, `batch_id`, `system_qty`, `counted_qty`, `variance` |

---

## 10. BIL

### `service_executions`

| العمود | النوع | Null | ملاحظات |
|---|---|---|---|
| `patient_id` | `BIGINT UNSIGNED` | لا | |
| `encounter_id` | `BIGINT UNSIGNED` | نعم | |
| `service_id` | `BIGINT UNSIGNED` | لا | |
| `performed_by` | `BIGINT UNSIGNED` | لا | |
| `performed_at` | `DATETIME(6)` | لا | |
| `price_snapshot` | `DECIMAL(14,2)` | لا | سعر وقت التنفيذ |
| `status` | `VARCHAR(20)` | لا | `EXECUTED`, `BILLED`, `CANCELLED` |

### `invoices`

| العمود | النوع | Null |
|---|---|---|
| `invoice_no` | `VARCHAR(30)` | لا |
| `patient_id` | `BIGINT UNSIGNED` | لا |
| `encounter_id` | `BIGINT UNSIGNED` | نعم |
| `subtotal` | `DECIMAL(14,2)` | لا |
| `discount_total` | `DECIMAL(14,2)` | لا |
| `grand_total` | `DECIMAL(14,2)` | لا |
| `paid_total` | `DECIMAL(14,2)` | لا |
| `status` | `VARCHAR(20)` | لا |
| `issued_at` | `DATETIME(6)` | لا |
| `closed_at` | `DATETIME(6)` | نعم |
| `void_reason` | `VARCHAR(500)` | نعم |

### `invoice_items`

| العمود | النوع | Null |
|---|---|---|
| `invoice_id` | `BIGINT UNSIGNED` | لا |
| `service_execution_id` | `BIGINT UNSIGNED` | نعم |
| `service_id` | `BIGINT UNSIGNED` | لا |
| `description` | `VARCHAR(255)` | لا |
| `unit_price` | `DECIMAL(14,2)` | لا |
| `quantity` | `DECIMAL(10,2)` | لا |
| `line_total` | `DECIMAL(14,2)` | لا |

### `payments`

| العمود | النوع | Null |
|---|---|---|
| `payment_no` | `VARCHAR(30)` | لا |
| `invoice_id` | `BIGINT UNSIGNED` | لا |
| `amount` | `DECIMAL(14,2)` | لا |
| `method` | `VARCHAR(20)` | لا |
| `idempotency_key` | `VARCHAR(64)` | لا |
| `receipt_no` | `VARCHAR(30)` | لا |
| `paid_at` | `DATETIME(6)` | لا |
| `cashier_id` | `BIGINT UNSIGNED` | لا |
| `cash_session_id` | `BIGINT UNSIGNED` | نعم |

### `payment_allocations` / `refunds`

| الجدول | الأعمدة |
|---|---|
| `payment_allocations` | `payment_id`, `invoice_id`, `amount` |
| `refunds` | `payment_id`, `amount`, `reason`, `approved_by`, `refunded_at`, `idempotency_key` |

### `cash_sessions` / `cash_movements`

| الجدول | الأعمدة |
|---|---|
| `cash_sessions` | `cashier_id`, `opened_at`, `closed_at`, `opening_float`, `expected_total`, `counted_total`, `variance`, `status` |
| `cash_movements` | `cash_session_id`, `movement_type`, `amount`, `reference_type`, `reference_id`, `created_at` |

---

## 11. HRM / ORS

### `employees` / `doctor_profiles`

| الجدول | الأعمدة الخاصة |
|---|---|
| `employees` | `user_id`, `employee_no`, `full_name`, `job_title`, `hire_date`, `status` |
| `doctor_profiles` | `employee_id`, `specialty`, `license_no`, `license_expiry`, `active` |

### `shifts`

| العمود | النوع | Null |
|---|---|---|
| `employee_id` | `BIGINT UNSIGNED` | لا |
| `department_id` | `BIGINT UNSIGNED` | لا |
| `shift_date` | `DATE` | لا |
| `start_time` | `TIME` | لا |
| `end_time` | `TIME` | لا |
| `status` | `VARCHAR(20)` | لا |

### `or_cases` / `or_team` / `or_checklists`

| الجدول | الأعمدة |
|---|---|
| `or_cases` | `patient_id`, `encounter_id`, `room_id`, `surgeon_id`, `status`, `scheduled_start`, `scheduled_end`, `actual_start`, `actual_end` |
| `or_team` | `or_case_id`, `employee_id`, `role` |
| `or_checklists` | `or_case_id`, `phase`, `items_json`, `completed_by`, `completed_at` |

---

## 12. NOT / RPT / ANL / DEC / INT

### `notifications` / `notification_deliveries` / `notification_templates` / `notification_preferences`

| الجدول | الأعمدة الخاصة |
|---|---|
| `notifications` | `recipient_user_id`, `event_id` (UK), `type`, `priority`, `title_key`, `body_key`, `params_json`, `link`, `read_at`, `expires_at` |
| `notification_deliveries` | `notification_id`, `channel`, `status`, `attempts`, `last_error`, `sent_at` |
| `notification_templates` | `type`, `locale`, `title_template`, `body_template`, `active` |
| `notification_preferences` | `user_id`, `type`, `channel`, `enabled` |

### `report_definitions` / `report_runs`

| الجدول | الأعمدة |
|---|---|
| `report_definitions` | `code`, `name_ar`, `category`, `query_key`, `parameters_json`, `active` |
| `report_runs` | `report_id`, `requested_by`, `parameters_json`, `status`, `started_at`, `finished_at`, `file_key`, `row_count` |

### `kpi_definitions` / `kpi_snapshots`

| الجدول | الأعمدة |
|---|---|
| `kpi_definitions` | `code`, `name_ar`, `description`, `formula`, `source_tables`, `granularity`, `target_value`, `owner_role` |
| `kpi_snapshots` | `kpi_id`, `snapshot_date`, `dimension_type`, `dimension_id`, `value`, `sample_size`, `computed_at` |

**فهرس فريد:** `(kpi_id, snapshot_date, dimension_type, dimension_id)`.

### `decision_rules` / `decision_cards` / `decision_card_actions`

| الجدول | الأعمدة |
|---|---|
| `decision_rules` | `code` (DR-001..), `name_ar`, `condition_json`, `action_template`, `severity`, `active` |
| `decision_cards` | `rule_id`, `status`, `priority`, `title_ar`, `evidence_json`, `confidence`, `period_from`, `period_to`, `approved_by`, `approved_at`, `executed_at` |
| `decision_card_actions` | `card_id`, `action`, `actor_id`, `reason`, `acted_at` |

### `data_quality_checks`

| العمود | النوع | Null |
|---|---|---|
| `check_code` | `VARCHAR(40)` | لا |
| `check_date` | `DATE` | لا |
| `total_records` | `INT` | لا |
| `failed_records` | `INT` | لا |
| `score` | `DECIMAL(5,2)` | لا |
| `details_json` | `JSON` | نعم |

### `integration_messages` / `external_identifiers`

| الجدول | الأعمدة |
|---|---|
| `integration_messages` | `direction`, `endpoint_id`, `message_type`, `payload_json`, `status`, `attempts`, `last_error`, `created_at` |
| `external_identifiers` | `entity_type`, `entity_id`, `system`, `external_id`, `version` |

---

## 13. سياسة الاحتفاظ

| الفئة | الجداول | المدة |
|---|---|---|
| سريرية | `encounters`, `clinical_notes`, `lab_results`, `prescriptions` | 10 سنوات (PD-012) |
| مالية | `invoices`, `payments`, `refunds` | 5 سنوات |
| تدقيق | `audit_logs`, `access_logs` | 7 سنوات |
| تشغيلية | `outbox_events`, `job_runs`, `login_attempts` | 1–2 سنة |
| إشعارات | `notifications` | 2 سنوات |
| مرفقات | `attachments` | تتبع المالك |

**قاعدة:** لا حذف فيزيائي لسجل موقع أو مالي؛ أرشفة فقط.

---

## 14. المراجع

- `01-erd.md` — العلاقات.
- `03-schema-constraints-indexes.md` — القيود والفهارس.
- `04-migrations-seed.md` — الترحيلات.
