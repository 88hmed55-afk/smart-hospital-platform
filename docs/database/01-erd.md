# 01 — Entity Relationship Diagram (ERD)

| Field | Value |
|---|---|
| Document ID | DB-ERD-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| DB | MySQL 8.0.38 · InnoDB · utf8mb4 |
| Notation | Crow's foot (Mermaid) |

> **ملاحظة:** هذا مخطط **منطقي**. الأنواع الدقيقة والقيود في `02-data-dictionary.md` و`03-schema-constraints-indexes.md`. كل جدول يحمل الأعمدة المشتركة (`id`, `public_id`, `created_at`, `created_by`, `updated_at`, `updated_by`, `version`) حتى لا تتكرر في كل رسم.

---

## 1. الخريطة العامة (9 نطاقات)

```mermaid
flowchart LR
    IAM[IAM<br/>Users/Roles/Permissions] --> PAT[PAT<br/>Patients]
    SYS[SYS<br/>Org/Services/Prices] --> PAT
    PAT --> SCH[SCH<br/>Appointments/Queue]
    SCH --> EMR[EMR<br/>Encounters/Notes/Orders]
    EMR --> LAB[LAB<br/>Orders/Specimens/Results]
    EMR --> PHR[PHR<br/>Prescriptions/Dispensing]
    EMR --> ADT[ADT<br/>Admissions/Beds]
    EMR --> BIL[BIL<br/>Invoices/Payments]
    LAB --> BIL
    PHR --> BIL
    ADT --> BIL
    PHR --> INV[INV<br/>Stock/Purchasing]
    LAB --> NOT[NOT<br/>Notifications]
    PHR --> NOT
    ADT --> NOT
    EMR --> ANL[ANL/DEC<br/>KPIs/Cards]
    BIL --> ANL
    NOT --> ANL
```

---

## 2. النطاق A — الهوية والمنصة (IAM / SYS)

```mermaid
erDiagram
    BRANCHES ||--o{ DEPARTMENTS : contains
    DEPARTMENTS ||--o{ CLINICS : has
    DEPARTMENTS ||--o{ WARDS : has
    WARDS ||--o{ ROOMS : contains
    ROOMS ||--o{ BEDS : contains
    USERS ||--o{ USER_ROLES : assigned
    ROLES ||--o{ USER_ROLES : granted
    ROLES ||--o{ ROLE_PERMISSIONS : has
    PERMISSIONS ||--o{ ROLE_PERMISSIONS : in
    USERS ||--o{ USER_SESSIONS : opens
    USERS ||--o{ LOGIN_ATTEMPTS : attempts
    USERS ||--o{ BREAK_GLASS_GRANTS : requests
    SERVICE_CATALOG ||--o{ SERVICE_PRICES : priced
    USERS ||--o{ AUDIT_LOGS : performs
    USERS ||--o{ ACCESS_LOGS : accesses

    USERS {
        bigint id PK
        char(26) public_id UK
        varchar username UK
        varchar password_hash
        varchar status
        bigint branch_id FK
    }
    ROLES {
        bigint id PK
        varchar code UK
        varchar name_ar
        varchar name_en
    }
    PERMISSIONS {
        bigint id PK
        varchar code UK
        varchar domain
        varchar action
    }
    SERVICE_CATALOG {
        bigint id PK
        varchar code UK
        varchar name_ar
        varchar category
        boolean active
    }
    SERVICE_PRICES {
        bigint id PK
        bigint service_id FK
        decimal amount
        date valid_from
        date valid_to
    }
```

| الجدول | الغرض |
|---|---|
| `branches` | الفرع (مستشفى واحد فعليًا، مع جاهزية التوسع) |
| `departments` | الأقسام الطبية/الإدارية |
| `clinics` | العيادات الخارجية |
| `wards` / `rooms` / `beds` | الهيكل التنويمي |
| `users` | حسابات المستخدمين |
| `roles` / `permissions` / `role_permissions` / `user_roles` | RBAC |
| `user_sessions` | تتبع الجلسات (لا تُخزَّن الجلسة) |
| `login_attempts` | محاولات الدخول |
| `break_glass_grants` | وصول طارئ |
| `settings` | إعدادات المستشفى (key/value مصنّف) |
| `service_catalog` / `service_prices` | الخدمات والأسعار بتاريخ سريان |
| `templates` | قوالب توثيق/إشعار/طباعة |
| `working_hours` / `holidays` | ساعات العمل |
| `attachments` | بيانات وصفية للملفات (الملف على القرص) |
| `audit_logs` / `access_logs` | تدقيق ووصول |
| `outbox_events` | الأحداث |
| `idempotency_keys` | منع التكرار |
| `job_runs` | سجل المهام المجدولة |

---

## 3. النطاق B — المرضى (PAT)

```mermaid
erDiagram
    PATIENTS ||--o{ PATIENT_IDENTIFIERS : has
    PATIENTS ||--o{ PATIENT_CONTACTS : has
    PATIENTS ||--o{ PATIENT_ALLERGIES : has
    PATIENTS ||--o{ PATIENT_CONDITIONS : has
    PATIENTS ||--o{ PATIENT_FLAGS : has
    PATIENTS ||--o{ CONSENTS : gives
    PATIENTS ||--o{ PATIENT_MERGES : source
    PATIENTS ||--o{ ATTACHMENTS : owns

    PATIENTS {
        bigint id PK
        char(26) public_id UK
        varchar medical_number UK
        varchar full_name
        date birth_date
        boolean birth_date_estimated
        varchar gender
        varchar phone
        varchar national_id
        varchar status
        bigint merged_into_id FK
    }
    PATIENT_IDENTIFIERS {
        bigint id PK
        bigint patient_id FK
        varchar type
        varchar value
        boolean is_primary
    }
    PATIENT_ALLERGIES {
        bigint id PK
        bigint patient_id FK
        varchar substance
        varchar severity
        varchar reaction
        boolean active
    }
    PATIENT_MERGES {
        bigint id PK
        bigint source_patient_id FK
        bigint target_patient_id FK
        varchar reason
        bigint merged_by FK
        datetime merged_at
    }
```

| القاعدة | التطبيق |
|---|---|
| لا حذف مريض | `status = MERGED/INACTIVE` فقط |
| منع التكرار | فهرس على `national_id`, `phone`, وتطبيع الاسم |
| الحساسية | `active` + `severity` + `reaction` |
| الدمج | `patient_merges` + تحويل كل المراجع |

---

## 4. النطاق C — المواعيد والزيارة (SCH / EMR)

```mermaid
erDiagram
    PROVIDER_SCHEDULES ||--o{ SCHEDULE_EXCEPTIONS : has
    PATIENTS ||--o{ APPOINTMENTS : books
    PROVIDER_SCHEDULES ||--o{ APPOINTMENTS : generates
    APPOINTMENTS ||--o{ QUEUE_TICKETS : checks_in
    APPOINTMENTS ||--o| ENCOUNTERS : becomes
    PATIENTS ||--o{ ENCOUNTERS : has
    ENCOUNTERS ||--o{ CLINICAL_NOTES : contains
    CLINICAL_NOTES ||--o{ CLINICAL_NOTE_VERSIONS : versions
    ENCOUNTERS ||--o{ DIAGNOSES : has
    ENCOUNTERS ||--o{ VITALS : records
    ENCOUNTERS ||--o{ SERVICE_ORDERS : creates
    SERVICE_ORDERS ||--o{ ORDER_ITEMS : contains
    ENCOUNTERS ||--o{ PRESCRIPTIONS : prescribes
    PRESCRIPTIONS ||--o{ PRESCRIPTION_ITEMS : contains
    CONCEPTS ||--o{ CONCEPT_MAPPINGS : maps

    APPOINTMENTS {
        bigint id PK
        char(26) public_id UK
        bigint patient_id FK
        bigint provider_id FK
        datetime slot_start
        datetime slot_end
        varchar status
        varchar cancel_reason
    }
    ENCOUNTERS {
        bigint id PK
        char(26) public_id UK
        bigint patient_id FK
        bigint appointment_id FK
        bigint provider_id FK
        varchar type
        varchar status
        datetime started_at
        datetime closed_at
    }
    CLINICAL_NOTES {
        bigint id PK
        bigint encounter_id FK
        varchar note_type
        bigint current_version_id FK
        varchar status
    }
    CLINICAL_NOTE_VERSIONS {
        bigint id PK
        bigint note_id FK
        int version_no
        text content
        bigint amends_id FK
        varchar amend_reason
        bigint signed_by FK
        datetime signed_at
    }
```

| الحالة | القاعدة |
|---|---|
| الموعد | `BOOKED → CHECKED_IN → COMPLETED / CANCELLED / NO_SHOW` |
| الزيارة | `OPEN → CLOSED` (لا إعادة فتح بعد الإغلاق) |
| الملاحظة | `DRAFT → SIGNED` (التصحيح بنسخة جديدة) |
| التشخيص | أساسي واحد على الأكثر + ثانوي متعدد |
| الطلب | `service_orders` + `order_items` لأنواع المختبر/الأشعة/الإجراءات |

---

## 5. النطاق D — الطوارئ والتنويم والتمريض (ER / ADT / NUR)

```mermaid
erDiagram
    PATIENTS ||--o{ ER_VISITS : arrives
    ER_VISITS ||--o| TRIAGE_ASSESSMENTS : triaged
    ER_VISITS ||--o| ENCOUNTERS : creates
    PATIENTS ||--o{ ADMISSIONS : admitted
    ADMISSIONS ||--o{ BED_ASSIGNMENTS : occupies
    BEDS ||--o{ BED_ASSIGNMENTS : assigned
    ADMISSIONS ||--o{ BED_TRANSFERS : transfers
    ADMISSIONS ||--o| DISCHARGES : ends
    BEDS ||--o{ BED_STATUS_HISTORY : status
    ADMISSIONS ||--o{ NURSING_TASKS : tasks
    ADMISSIONS ||--o{ MEDICATION_ADMINISTRATIONS : meds
    ADMISSIONS ||--o{ INTAKE_OUTPUT : fluids

    ADMISSIONS {
        bigint id PK
        char(26) public_id UK
        bigint patient_id FK
        bigint encounter_id FK
        bigint admitting_doctor_id FK
        varchar status
        datetime admitted_at
        datetime discharged_at
    }
    BED_ASSIGNMENTS {
        bigint id PK
        bigint admission_id FK
        bigint bed_id FK
        tinyint active_flag
        datetime started_at
        datetime ended_at
    }
    NURSING_TASKS {
        bigint id PK
        bigint admission_id FK
        varchar task_type
        varchar status
        bigint assigned_to FK
        datetime due_at
    }
```

| القاعدة | التطبيق |
|---|---|
| سرير واحد نشط | `UNIQUE(bed_id, active_flag)` مع `NULL` للمنتهي |
| نقل | إغلاق تعيين + تعيين جديد في نفس المعاملة |
| خروج | `DISCHARGED` + تحرير السرير → `CLEANING` |
| مهمة تمريض | `PENDING → IN_PROGRESS → DONE / CANCELLED` |

---

## 6. النطاق E — المختبر والأشعة (LAB / RAD)

```mermaid
erDiagram
    LAB_TESTS ||--o{ LAB_ORDER_ITEMS : requested
    LAB_ORDERS ||--o{ LAB_ORDER_ITEMS : contains
    LAB_ORDERS ||--o{ SPECIMENS : collects
    SPECIMENS ||--o{ SPECIMEN_EVENTS : tracks
    LAB_ORDER_ITEMS ||--o{ LAB_RESULTS : results
    LAB_RESULTS ||--o{ LAB_RESULT_VERSIONS : versions
    LAB_TESTS ||--o{ LAB_REFERENCE_RANGES : ranges
    ENCOUNTERS ||--o{ LAB_ORDERS : originates
    ENCOUNTERS ||--o{ RADIOLOGY_ORDERS : originates
    RADIOLOGY_ORDERS ||--o| RADIOLOGY_STUDIES : performs
    RADIOLOGY_ORDERS ||--o| RADIOLOGY_REPORTS : reports

    LAB_ORDERS {
        bigint id PK
        char(26) public_id UK
        bigint encounter_id FK
        bigint patient_id FK
        bigint ordered_by FK
        varchar status
        datetime ordered_at
    }
    SPECIMENS {
        bigint id PK
        char(26) public_id UK
        bigint lab_order_id FK
        varchar barcode UK
        varchar specimen_type
        varchar status
        datetime collected_at
    }
    LAB_RESULTS {
        bigint id PK
        bigint lab_order_item_id FK
        bigint current_version_id FK
        varchar status
        varchar flag
        datetime released_at
    }
    LAB_RESULT_VERSIONS {
        bigint id PK
        bigint result_id FK
        int version_no
        decimal value_numeric
        varchar value_text
        bigint amends_id FK
        varchar amend_reason
        bigint verified_by FK
        datetime verified_at
    }
```

| الحالة | المسار |
|---|---|
| طلب | `ORDERED → COLLECTED → RECEIVED → IN_PROCESS → RESULTED → VERIFIED` |
| عينة | `COLLECTED → RECEIVED → IN_PROCESS → RESULTED → DISCARDED` |
| نتيجة | `DRAFT → VERIFIED → AMENDED` |
| أشعة | `ORDERED → SCHEDULED → PERFORMED → REPORTED → SIGNED` |

---

## 7. النطاق F — الصيدلية والمخزون (PHR / INV)

```mermaid
erDiagram
    DRUGS ||--o{ STOCK_BATCHES : stocked_as
    WAREHOUSES ||--o{ STOCK_BATCHES : stores
    STOCK_BATCHES ||--o{ STOCK_MOVEMENTS : moves
    PRESCRIPTIONS ||--o{ DISPENSING_TRANSACTIONS : dispensed_by
    DISPENSING_TRANSACTIONS ||--o{ DISPENSING_ITEMS : contains
    STOCK_BATCHES ||--o{ DISPENSING_ITEMS : from_batch
    INVENTORY_ITEMS ||--o{ STOCK_BATCHES : item
    SUPPLIERS ||--o{ PURCHASE_ORDERS : supplies
    PURCHASE_ORDERS ||--o{ PURCHASE_ORDER_ITEMS : contains
    PURCHASE_ORDERS ||--o{ GOODS_RECEIPTS : received
    WAREHOUSES ||--o{ STOCK_COUNTS : counts
    STOCK_COUNTS ||--o{ STOCK_COUNT_LINES : lines

    DRUGS {
        bigint id PK
        varchar code UK
        varchar name_ar
        varchar name_en
        varchar form
        varchar strength
        decimal reorder_level
        boolean active
    }
    STOCK_BATCHES {
        bigint id PK
        char(26) public_id UK
        bigint drug_id FK
        bigint warehouse_id FK
        varchar batch_no
        date expiry_date
        int quantity_on_hand
        int quantity_reserved
        varchar status
    }
    STOCK_MOVEMENTS {
        bigint id PK
        bigint batch_id FK
        varchar movement_type
        int quantity
        varchar reference_type
        bigint reference_id
        bigint performed_by FK
        datetime performed_at
    }
    DISPENSING_TRANSACTIONS {
        bigint id PK
        char(26) public_id UK
        bigint prescription_id FK
        bigint dispensed_by FK
        varchar status
        datetime dispensed_at
    }
```

| القاعدة | التطبيق |
|---|---|
| FEFO | اختيار الدفعة الأقرب انتهاءً الصالحة |
| منع السالب | `CHECK(quantity_on_hand >= 0)` |
| حركة لكل تغيير | `stock_movements` Append-only |
| دفعة منتهية | `status = EXPIRED` ولا تُصرف |
| إرجاع | حركة `RETURN` مرتبطة بالأصل |

---

## 8. النطاق G — الفواتير والمالية (BIL)

```mermaid
erDiagram
    ENCOUNTERS ||--o{ SERVICE_EXECUTIONS : executes
    SERVICE_EXECUTIONS ||--o{ INVOICE_ITEMS : billed_as
    INVOICES ||--o{ INVOICE_ITEMS : contains
    INVOICES ||--o{ PAYMENTS : paid_by
    PAYMENTS ||--o{ PAYMENT_ALLOCATIONS : allocates
    INVOICES ||--o{ PAYMENT_ALLOCATIONS : receives
    PAYMENTS ||--o{ REFUNDS : refunded
    USERS ||--o{ CASH_SESSIONS : opens
    CASH_SESSIONS ||--o{ CASH_MOVEMENTS : records

    INVOICES {
        bigint id PK
        char(26) public_id UK
        varchar invoice_no UK
        bigint patient_id FK
        bigint encounter_id FK
        decimal subtotal
        decimal discount_total
        decimal grand_total
        decimal paid_total
        varchar status
        datetime issued_at
        datetime closed_at
    }
    INVOICE_ITEMS {
        bigint id PK
        bigint invoice_id FK
        bigint service_execution_id FK
        bigint service_id FK
        decimal unit_price
        decimal quantity
        decimal line_total
    }
    PAYMENTS {
        bigint id PK
        char(26) public_id UK
        bigint invoice_id FK
        decimal amount
        varchar method
        varchar idempotency_key UK
        varchar receipt_no UK
        datetime paid_at
    }
    SERVICE_EXECUTIONS {
        bigint id PK
        char(26) public_id UK
        bigint patient_id FK
        bigint encounter_id FK
        bigint service_id FK
        bigint performed_by FK
        datetime performed_at
        decimal price_snapshot
        varchar status
    }
```

| القاعدة | التطبيق |
|---|---|
| لا حذف فاتورة | `VOIDED` أو إشعار دائن |
| رصيد مشتق | `paid_total` يُحدَّث داخل معاملة الدفع |
| منع الدفع المكرر | `idempotency_key` فريد |
| إقفال | `cash_sessions` + منع تعديل بعد الإقفال |
| سعر تاريخي | `price_snapshot` في `service_executions` |

---

## 9. النطاق H — الموارد البشرية وغرف العمليات (HRM / ORS)

```mermaid
erDiagram
    USERS ||--o| EMPLOYEES : profile
    EMPLOYEES ||--o| DOCTOR_PROFILES : doctor
    DOCTOR_PROFILES ||--o{ SHIFTS : scheduled
    CLINICS ||--o{ DOCTOR_PROFILES : works_in
    ENCOUNTERS ||--o| OR_CASES : surgery
    OR_CASES ||--o{ OR_TEAM : team
    OR_CASES ||--o{ OR_CHECKLISTS : checklist

    EMPLOYEES {
        bigint id PK
        bigint user_id FK
        varchar employee_no UK
        varchar full_name
        varchar job_title
        date hire_date
        varchar status
    }
    DOCTOR_PROFILES {
        bigint id PK
        bigint employee_id FK
        varchar specialty
        varchar license_no
        date license_expiry
        boolean active
    }
    OR_CASES {
        bigint id PK
        char(26) public_id UK
        bigint patient_id FK
        bigint encounter_id FK
        bigint room_id FK
        bigint surgeon_id FK
        varchar status
        datetime scheduled_start
        datetime scheduled_end
    }
```

---

## 10. النطاق I — الإشعارات والتحليلات والتكامل (NOT / RPT / ANL / DEC / INT)

```mermaid
erDiagram
    USERS ||--o{ NOTIFICATIONS : receives
    NOTIFICATIONS ||--o{ NOTIFICATION_DELIVERIES : attempts
    NOTIFICATION_TEMPLATES ||--o{ NOTIFICATIONS : renders
    USERS ||--o{ NOTIFICATION_PREFERENCES : prefers
    KPI_DEFINITIONS ||--o{ KPI_SNAPSHOTS : snapshots
    DECISION_RULES ||--o{ DECISION_CARDS : generates
    DECISION_CARDS ||--o{ DECISION_CARD_ACTIONS : actions
    REPORT_DEFINITIONS ||--o{ REPORT_RUNS : runs
    INTEGRATION_ENDPOINTS ||--o{ INTEGRATION_MESSAGES : exchanges
    PATIENTS ||--o{ EXTERNAL_IDENTIFIERS : mapped

    NOTIFICATIONS {
        bigint id PK
        char(26) public_id UK
        bigint recipient_user_id FK
        char(26) event_id UK
        varchar type
        varchar priority
        varchar link
        datetime read_at
    }
    KPI_DEFINITIONS {
        bigint id PK
        varchar code UK
        varchar name_ar
        varchar formula
        varchar source
        varchar granularity
    }
    KPI_SNAPSHOTS {
        bigint id PK
        bigint kpi_id FK
        date snapshot_date
        decimal value
        bigint sample_size
        varchar dimensions_hash
    }
    DECISION_CARDS {
        bigint id PK
        char(26) public_id UK
        bigint rule_id FK
        varchar status
        varchar priority
        decimal confidence
        json evidence
        bigint approved_by FK
        datetime approved_at
    }
    DECISION_CARD_ACTIONS {
        bigint id PK
        bigint card_id FK
        varchar action
        bigint actor_id FK
        varchar reason
        datetime acted_at
    }
```

---

## 11. خريطة العلاقات الحرجة

| العلاقة | Cardinality | قاعدة |
|---|---|---|
| Patient → Encounter | 1:N | زيارة واحدة لكل لقاء |
| Appointment → Encounter | 1:0..1 | لا زيارة بلا مريض |
| Encounter → LabOrder | 1:N | طلب واحد قد يحمل عدة فحوصات |
| LabOrder → Specimen | 1:N | عينة واحدة قد تخدم عدة فحوصات |
| Prescription → Dispensing | 1:N | صرف جزئي مسموح |
| Admission → BedAssignment | 1:N | نقل يغلق القديم ويفتح جديدًا |
| Invoice → Payment | 1:N | دفع جزئي مسموح |
| Encounter → ServiceExecution | 1:N | كل خدمة منفذة قابلة للفوترة |
| User → Role | M:N | عبر `user_roles` |
| Role → Permission | M:N | عبر `role_permissions` |

---

## 12. ما لا يظهر في ERD

- جداول Audit/Outbox/Idempotency (مشتركة، لا علاقات أعمال).
- جداول Views التحليلية (`v_*`) — ليست كيانات.
- جداول مؤقتة للمهام.
- جداول الإعدادات key/value.

---

## 13. المراجع

- `02-data-dictionary.md` — الأعمدة والأنواع.
- `03-schema-constraints-indexes.md` — القيود والفهارس.
- `04-migrations-seed.md` — الترحيلات والبيانات.
