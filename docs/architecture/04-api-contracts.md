# 04 — API Contracts

| Field | Value |
|---|---|
| Document ID | ARC-API-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Base URL | `https://<host>/api/v1` |
| Format | JSON UTF-8 · camelCase · UTC ISO-8601 |
| Auth | Session Cookie + CSRF header |

> **العقد ليس اقتراحًا:** أي تغيير في المسار أو الحقول أو الأخطاء بعد الاعتماد يتطلب إصدارًا جديدًا (`/v2`) أو فترة إهلاك موثّقة.

---

## 1. القواعد العامة

| البند | القرار |
|---|---|
| المعرّفات | `public_id` (ULID) في المسارات، لا `id` رقمي |
| التواريخ | `2026-11-11T08:30:00Z` (UTC) |
| المبالغ | نص عشري `"125.50"`، لا `float` |
| التعدادات | `UPPER_SNAKE` |
| القيم الفارغة | `null` صريح، لا حذف الحقل |
| التسمية | `camelCase` |
| الترميز | UTF-8 |
| الضغط | gzip/br من Nginx |
| الإصدار | `/api/v1`، مع `Deprecation` + `Sunset` عند التغيير |
| الطلبات المتزامنة | `Idempotency-Key` للعمليات الحساسة |
| التزامن المتفائل | `version` في الجسم أو `If-Match` |
| الحد الأقصى للجسم | 1 MB (عدا الرفع) |
| المهلة | 30 ثانية للطلب العادي |

---

## 2. المصادقة وCSRF

| المسار | الطريقة | الوصف |
|---|---|---|
| `/auth/login` | POST | دخول + إنشاء جلسة |
| `/auth/logout` | POST | إنهاء الجلسة |
| `/auth/me` | GET | المستخدم + الأدوار + الصلاحيات + النطاقات |
| `/auth/csrf` | GET | تدوير رمز CSRF |
| `/auth/change-password` | POST | تغيير كلمة المرور |
| `/auth/mfa/verify` | POST | التحقق من TOTP (إن مفعّل) |

**تدفق الدخول:**

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "dr.ahmad", "password": "••••••••" }
```

```http
HTTP/1.1 200 OK
Set-Cookie: SH_SESSION=...; HttpOnly; Secure; SameSite=Strict; Path=/
Set-Cookie: SH_CSRF=...; Secure; SameSite=Strict; Path=/
Content-Type: application/json

{
  "user": {
    "publicId": "01J...",
    "displayName": "د. أحمد",
    "roles": ["DOCTOR"],
    "permissions": ["PAT:READ", "EMR:WRITE", "LAB:ORDER"],
    "scopes": [{ "type": "DEPARTMENT", "id": "01J..." }],
    "mustChangePassword": false,
    "mfaEnabled": false
  }
}
```

**قاعدة:** لا يُعاد أي توكن في الجسم. الجلسة في Cookie فقط (ADR-003).

---

## 3. شكل الاستجابة

### 3.1 كائن مفرد

```json
{ "publicId": "01J...", "fullName": "سارة محمد", "version": 3 }
```

### 3.2 قائمة مع Pagination

```json
{
  "items": [ { "publicId": "01J...", "fullName": "..." } ],
  "page": 0,
  "size": 20,
  "total": 1532,
  "totalPages": 77,
  "sort": "fullName,asc"
}
```

| المعامل | الافتراضي | الحد |
|---|---|---|
| `page` | 0 | ≥ 0 |
| `size` | 20 | 1–100 |
| `sort` | حسب المورد | `field,asc|desc` |
| `q` | — | بحث نصي |
| فلاتر | — | صريحة لكل مورد |

### 3.3 خطأ

```json
{
  "code": "PHR_BATCH_EXPIRED",
  "messageAr": "لا يمكن صرف دواء من دفعة منتهية الصلاحية.",
  "messageEn": "Cannot dispense from an expired batch.",
  "traceId": "9f2c...",
  "fieldErrors": [],
  "details": { "batchId": "01J...", "expiryDate": "2026-01-31" }
}
```

| HTTP | `code` مثال | متى |
|---|---|---|
| 400 | `VALIDATION_FAILED` | حقل ناقص/غير صالح |
| 401 | `AUTH_REQUIRED` | لا جلسة |
| 403 | `FORBIDDEN` | صلاحية/نطاق |
| 404 | `NOT_FOUND` | مورد غير موجود |
| 409 | `VERSION_CONFLICT` / `SCHEDULE_CONFLICT` | تعارض |
| 422 | `BUSINESS_RULE_VIOLATION` | قاعدة عمل |
| 429 | `RATE_LIMITED` | تجاوز الحد |
| 500 | `INTERNAL_ERROR` | خطأ غير متوقع |
| 503 | `SERVICE_UNAVAILABLE` | DB/JMS غير متاح |

---

## 4. Idempotency

| البند | القرار |
|---|---|
| الرأس | `Idempotency-Key: <UUID>` |
| النطاق | لكل مستخدم + مسار |
| التخزين | `idempotency_keys` لمدة 24 ساعة |
| السلوك | نفس المفتاح + نفس الجسم ⇒ نفس الاستجابة، لا تنفيذ ثانٍ |
| اختلاف الجسم | `409 IDEMPOTENCY_MISMATCH` |
| إلزامي على | الدفع، الصرف، الاسترداد، إنشاء فاتورة، الاعتماد |

```http
POST /api/v1/prescriptions/01J.../dispense
Idempotency-Key: 6f1c2a9e-...
```

---

## 5. التزامن

| الآلية | الاستخدام |
|---|---|
| `version` في جسم التحديث | `PATCH` عادي |
| `If-Match: "3"` | الموارد الحساسة (نتيجة مختبر، ملاحظة) |
| `409 VERSION_CONFLICT` | يعيد `currentVersion` والنسخة الحالية |
| `409 SCHEDULE_CONFLICT` | يعيد الفترات المتاحة |

---

## 6. كتالوج نقاط النهاية

> `PERM` = الصلاحية المطلوبة. كل نقطة تخضع أيضًا لنطاق البيانات.

### 6.1 SYS — المنصة

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/settings` | `SYS:READ` | إعدادات المستشفى |
| PUT | `/settings` | `SYS:WRITE` | تحديث الإعدادات |
| GET | `/branches` | `SYS:READ` | الفروع |
| POST | `/branches` | `SYS:WRITE` | إضافة فرع |
| GET | `/departments` | `SYS:READ` | الأقسام |
| POST | `/departments` | `SYS:WRITE` | إضافة قسم |
| GET | `/clinics` | `SYS:READ` | العيادات |
| POST | `/clinics` | `SYS:WRITE` | إضافة عيادة |
| GET | `/wards` | `SYS:READ` | الأجنحة |
| POST | `/wards` | `SYS:WRITE` | إضافة جناح |
| GET | `/beds` | `SYS:READ` | الأسرة |
| POST | `/beds` | `SYS:WRITE` | إضافة سرير |
| GET | `/services` | `SYS:READ` | كتالوج الخدمات |
| POST | `/services` | `SYS:WRITE` | إضافة خدمة |
| PUT | `/services/{id}/price` | `SYS:PRICE` | سعر جديد بسريان تاريخي |
| GET | `/templates` | `SYS:READ` | القوالب |
| POST | `/templates` | `SYS:WRITE` | إنشاء قالب |
| GET | `/working-hours` | `SYS:READ` | أيام وساعات العمل |
| PUT | `/working-hours` | `SYS:WRITE` | تحديث |

### 6.2 IAM — الهوية

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/admin/users` | `IAM:READ` | قائمة المستخدمين |
| POST | `/admin/users` | `IAM:WRITE` | إنشاء مستخدم |
| GET | `/admin/users/{id}` | `IAM:READ` | تفاصيل |
| PATCH | `/admin/users/{id}` | `IAM:WRITE` | تعديل |
| POST | `/admin/users/{id}/reset-password` | `IAM:RESET` | إعادة تعيين |
| POST | `/admin/users/{id}/disable` | `IAM:WRITE` | تعطيل + إبطال جلسات |
| POST | `/admin/users/{id}/enable` | `IAM:WRITE` | تفعيل |
| GET | `/admin/roles` | `IAM:READ` | الأدوار |
| POST | `/admin/roles` | `IAM:WRITE` | إنشاء دور |
| PUT | `/admin/roles/{id}/permissions` | `IAM:WRITE` | ربط صلاحيات |
| GET | `/admin/permissions` | `IAM:READ` | كتالوج الصلاحيات |
| GET | `/admin/sessions` | `IAM:READ` | الجلسات النشطة |
| DELETE | `/admin/sessions/{id}` | `IAM:WRITE` | إبطال جلسة |
| POST | `/admin/break-glass` | `IAM:BREAK_GLASS` | طلب وصول طارئ |
| POST | `/admin/break-glass/{id}/revoke` | `IAM:BREAK_GLASS` | إلغاء |
| GET | `/admin/audit` | `AUDIT:READ` | سجل التدقيق |
| GET | `/admin/login-attempts` | `AUDIT:READ` | محاولات الدخول |

### 6.3 PAT — المرضى

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/patients` | `PAT:READ` | بحث + Pagination |
| POST | `/patients` | `PAT:CREATE` | تسجيل مريض |
| GET | `/patients/{id}` | `PAT:READ` | بطاقة المريض |
| PATCH | `/patients/{id}` | `PAT:WRITE` | تعديل |
| GET | `/patients/{id}/timeline` | `PAT:READ` | الخط الزمني |
| GET | `/patients/{id}/summary` | `PAT:READ` | ملخص |
| POST | `/patients/check-duplicates` | `PAT:CREATE` | فحص تشابه |
| POST | `/patients/{id}/merge` | `PAT:MERGE` | دمج |
| GET | `/patients/{id}/allergies` | `PAT:READ` | الحساسيات |
| POST | `/patients/{id}/allergies` | `PAT:WRITE` | إضافة حساسية |
| DELETE | `/patients/{id}/allergies/{aid}` | `PAT:WRITE` | إلغاء (منطقي) |
| GET | `/patients/{id}/attachments` | `PAT:READ` | المرفقات |
| POST | `/patients/{id}/attachments` | `PAT:WRITE` | رفع مرفق |
| GET | `/attachments/{id}` | `PAT:READ` | تنزيل آمن |

### 6.4 SCH — المواعيد

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/providers/{id}/schedules` | `SCH:READ` | جداول الطبيب |
| POST | `/providers/{id}/schedules` | `SCH:WRITE` | إنشاء جدول |
| GET | `/providers/{id}/availability` | `SCH:READ` | الفترات المتاحة |
| GET | `/appointments` | `SCH:READ` | قائمة/تقويم |
| POST | `/appointments` | `SCH:CREATE` | حجز |
| PATCH | `/appointments/{id}` | `SCH:WRITE` | تعديل |
| POST | `/appointments/{id}/cancel` | `SCH:WRITE` | إلغاء بسبب |
| POST | `/appointments/{id}/reschedule` | `SCH:WRITE` | إعادة جدولة |
| POST | `/appointments/{id}/check-in` | `SCH:CHECKIN` | وصول |
| POST | `/appointments/{id}/no-show` | `SCH:WRITE` | عدم حضور |
| GET | `/queue` | `SCH:READ` | قائمة الانتظار الحية |
| POST | `/queue/{id}/call` | `SCH:WRITE` | نداء المريض |
| GET | `/waitlist` | `SCH:READ` | قائمة بديلة |
| POST | `/waitlist` | `SCH:WRITE` | إضافة |

### 6.5 EMR — السجل الطبي

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/encounters` | `EMR:READ` | زيارات |
| POST | `/encounters` | `EMR:WRITE` | فتح زيارة |
| GET | `/encounters/{id}` | `EMR:READ` | زيارة |
| PATCH | `/encounters/{id}` | `EMR:WRITE` | تحديث |
| POST | `/encounters/{id}/close` | `EMR:SIGN` | إغلاق |
| POST | `/encounters/{id}/cancel` | `EMR:WRITE` | إلغاء |
| GET | `/encounters/{id}/notes` | `EMR:READ` | ملاحظات |
| POST | `/encounters/{id}/notes` | `EMR:WRITE` | إنشاء ملاحظة |
| PATCH | `/encounters/{id}/notes/{noteId}` | `EMR:WRITE` | تعديل مسودة |
| POST | `/encounters/{id}/notes/{noteId}/sign` | `EMR:SIGN` | توقيع |
| POST | `/encounters/{id}/notes/{noteId}/amend` | `EMR:SIGN` | تصحيح بنسخة |
| GET | `/encounters/{id}/notes/{noteId}/versions` | `EMR:READ` | نسخ الملاحظة |
| GET | `/encounters/{id}/diagnoses` | `EMR:READ` | تشخيصات |
| POST | `/encounters/{id}/diagnoses` | `EMR:WRITE` | إضافة تشخيص |
| DELETE | `/encounters/{id}/diagnoses/{diagnosisId}` | `EMR:WRITE` | حذف تشخيص |
| GET | `/encounters/{id}/vitals` | `EMR:READ` | علامات حيوية |
| POST | `/encounters/{id}/vitals` | `EMR:WRITE` | تسجيل |
| GET | `/encounters/{id}/orders` | `EMR:READ` | أوامر |
| POST | `/encounters/{id}/orders` | `EMR:ORDER` | طلب خدمة |
| PATCH | `/encounters/{id}/orders/{orderId}` | `EMR:ORDER` | تحديث حالة الطلب |
| POST | `/encounters/{id}/prescriptions` | `PHR:PRESCRIBE` | إنشاء وصفة |
| POST | `/encounters/{id}/prescriptions/{rxId}/issue` | `PHR:PRESCRIBE` | إصدار |
| POST | `/encounters/{id}/prescriptions/{rxId}/cancel` | `PHR:PRESCRIBE` | إلغاء |
| POST | `/encounters/{id}/charges` | `BIL:CREATE` | Charge Capture |
| GET | `/encounters/{id}/print` | `EMR:READ` | حمولة الطباعة |
| GET | `/patients/{id}/records` | `EMR:READ` | السجل الكامل |
| POST | `/encounters/{id}/referrals` | `EMR:WRITE` | إحالة |

### 6.6 ER — الطوارئ

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/er/triage-levels` | `ER:READ` | مستويات الفرز المعتمدة |
| POST | `/er/visits` | `ER:CREATE` / `ER:WRITE` | وصول طارئ (بيانات دنيا) |
| GET | `/er/board` | `ER:READ` | لوحة حية |
| GET | `/er/visits/{id}` | `ER:READ` | تفاصيل زيارة |
| POST | `/er/visits/{id}/triage` | `ER:TRIAGE` | فرز |
| POST | `/er/visits/{id}/assess` | `ER:WRITE` | تقييم سريري (إضافي) |
| POST | `/er/visits/{id}/disposition` | `ER:WRITE` | تحويل/خروج/LWBS |

**أخطاء مميزة:** `409 ER_VISIT_EXISTS` (زيارة نشطة مكررة) · `400 VALIDATION_FAILED` (وجهة أو سبب ناقص).

### 6.7 ADT — التنويم

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/admissions` | `ADT:READ` | تنويمات |
| POST | `/admissions` | `ADT:REQUEST` / `ADT:WRITE` | طلب تنويم |
| GET | `/admissions/{id}` | `ADT:READ` | تفاصيل + سجل الأسرّة + وجود ملخص |
| POST | `/admissions/{id}/approve` | `ADT:APPROVE` | اعتماد |
| POST | `/admissions/{id}/reject` | `ADT:APPROVE` | رفض (من `REQUESTED` فقط) |
| POST | `/admissions/{id}/assign-bed` | `ADT:ASSIGN` / `ADT:WRITE` | تخصيص سرير |
| POST | `/admissions/{id}/transfer` | `ADT:WRITE` | نقل |
| POST | `/admissions/{id}/discharge` | `ADT:WRITE` | خروج (يفحص إخلاء الفواتير) |
| GET | `/admissions/{id}/summary` | `ADT:READ` | ملخص خروج |
| POST | `/admissions/{id}/summary` | `ADT:WRITE` | حفظ ملخص خروج (إضافي) |
| GET | `/beds/board` | `ADT:READ` | لوحة الأسرة |
| POST | `/beds/{id}/status` | `ADT:HOUSEKEEPING` / `ADT:BED_STATUS` | تنظيف/صيانة/حجز/إتاحة |

**أخطاء مميزة:** `409 INVALID_STATE` (رفض طلب غير `REQUESTED`) · `409 BED_NOT_AVAILABLE` · `409 BILLING_NOT_CLEARED` · `409 STALE_VERSION`. الاستجابات المتغيّرة تعيد `{admission, bedHistory, hasSummary}`.

### 6.8 NUR — التمريض

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/wards/{id}/patients` | `NUR:READ` / `ADT:READ` | مرضى الجناح |
| GET | `/nursing/tasks` | `NUR:READ` | المهام (بحث وترقيم) |
| POST | `/nursing/tasks` | `NUR:WRITE` | إنشاء مهمة |
| GET | `/nursing/tasks/{id}` | `NUR:READ` | تفاصيل مهمة |
| PATCH | `/nursing/tasks/{id}` | `NUR:WRITE` | تعديل |
| POST | `/nursing/tasks/{id}/assign` | `NUR:WRITE` | إسناد |
| POST | `/nursing/tasks/{id}/start` | `NUR:WRITE` | بدء |
| POST | `/nursing/tasks/{id}/complete` | `NUR:WRITE` | إكمال |
| POST | `/nursing/tasks/{id}/cancel` | `NUR:WRITE` | إلغاء |
| GET | `/nursing/admissions/{id}/overview` | `NUR:READ` | ملف التنويم + أحدث العلامات + EWS |
| POST | `/nursing/admissions/{id}/vitals` | `NUR:WRITE` | تسجيل علامات حيوية (422 خارج النطاق) |
| GET | `/nursing/admissions/{id}/ews` | `NUR:READ` | سجل EWS |
| POST | `/medication-administrations` | `NUR:MED` | تسجيل إعطاء (`GIVEN/REFUSED/HELD/ERROR`) |

**قواعد:** `GIVEN` فقط يخصم المخزون FEFO؛ الباركود إلزامي عند `nur.med.barcode.required=1` (`422 BARCODE_MISMATCH`). EWS عالٍ يرفع `EWS_HIGH` حرجًا للممرضين.

### 6.9 LAB — المختبر

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/lab/tests` | `LAB:READ` | كتالوج الفحوصات |
| POST | `/lab/tests` | `LAB:WRITE` / `LAB:CATALOG` | إضافة فحص |
| GET | `/lab/orders` | `LAB:READ` | الطلبات (تصفية وترقيم) |
| POST | `/lab/orders` | `LAB:ORDER` | إنشاء طلب |
| GET | `/lab/orders/{id}` | `LAB:READ` | تفاصيل |
| POST | `/lab/orders/{id}/collect` | `LAB:COLLECT` | سحب عينة |
| POST | `/lab/orders/{id}/process` | `LAB:PROCESS` | بدء التحليل |
| POST | `/lab/orders/{id}/result` | `LAB:RESULT_ENTER` / `LAB:RESULT` | إدخال/تعديل نتيجة |
| POST | `/lab/specimens/{id}/receive` | `LAB:RECEIVE` | استلام |
| POST | `/lab/specimens/{id}/reject` | `LAB:RECEIVE` | رفض عينة |
| POST | `/lab/results/{id}/verify` | `LAB:VERIFY` | اعتماد |
| POST | `/lab/results/{id}/release` | `LAB:REPORT_RELEASE` / `LAB:VERIFY` | إصدار |
| POST | `/lab/results/{id}/amend` | `LAB:AMEND` / `LAB:VERIFY` | تصحيح |
| GET | `/lab/worklist` | `LAB:READ` | Worklist |
| GET | `/lab/alerts` | `LAB:READ` | القيم الحرجة |
| POST | `/lab/alerts/{id}/acknowledge` | `LAB:VERIFY` / `LAB:RESULT` | إقرار تنبيه |
| GET | `/lab/results/{id}/report` | `LAB:PRINT` / `LAB:READ` | تقرير للطباعة |
| GET | `/lab/orders/{id}/report` | `LAB:PRINT` / `LAB:READ` | تقرير الطلب (إضافي) |

**قواعد:** انتقالات العينة `COLLECTED→RECEIVED→IN_PROCESS`؛ الحالة تُشتق من النتائج (`RESULTED/VERIFIED/RELEASED/CORRECTED`)؛ التعديل بعد الإصدار المباشر يُرفض `409 RESULT_VERIFIED`؛ الإصدار يرسل `LAB_RESULT_RELEASED` إلى الطالب مرة واحدة.

### 6.10 RAD — الأشعة

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/radiology/orders` | `RAD:READ` | الطلبات |
| POST | `/radiology/orders` | `RAD:ORDER` | طلب |
| POST | `/radiology/orders/{id}/schedule` | `RAD:WRITE` | جدولة |
| POST | `/radiology/orders/{id}/perform` | `RAD:PERFORM` | تنفيذ |
| POST | `/radiology/orders/{id}/report` | `RAD:REPORT` | تقرير |
| POST | `/radiology/reports/{id}/sign` | `RAD:SIGN` | اعتماد |

### 6.11 PHR — الصيدلية

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/pharmacy/drugs` | `PHR:READ` | كتالوج الأدوية |
| POST | `/pharmacy/drugs` | `PHR:WRITE` | إضافة دواء |
| GET | `/prescriptions/{id}` | `EMR:READ` | وصفة (عرض سريري) |
| GET | `/prescriptions/patient/{patientId}` | `EMR:READ` | وصفات المريض النشطة |
| GET | `/prescriptions/{id}/pharmacy` | `PHR:READ` | عرض صيدلاني (حساسية + تفاعلات) |
| POST | `/prescriptions/{id}/review` | `PHR:REVIEW` | مراجعة |
| POST | `/prescriptions/{id}/reject` | `PHR:REVIEW` | رفض (يُشعر الواصف) |
| POST | `/prescriptions/{id}/dispense` | `PHR:DISPENSE` | صرف FEFO |
| GET | `/dispensings/{id}` | `PHR:READ` | تفاصيل صرف + بنود + مرتجعات |
| POST | `/dispensings/{id}/return` | `PHR:RETURN` | إرجاع |
| GET | `/pharmacy/queue` | `PHR:READ` | طابور الصرف |
| GET | `/stock/batches` | `PHR:BATCH_READ` / `PHR:READ` | الدفعات |
| POST | `/stock/batches` | `PHR:RECEIVE` | استلام دفعة |
| GET | `/stock/movements` | `PHR:READ` | حركات المخزون |
| GET | `/stock/alerts` | `PHR:READ` | نقص/انتهاء |

**قواعد:** الصرف معاملة JTA واحدة: قفل الوصفة + دفعات FEFO + `quantity_available >= ?`؛ أي فشل يتراجع كاملًا (`409 NO_STOCK`، `422 QUANTITY_EXCEEDS_REMAINING`). الإرجاع محدود بنافذة `phr.return.window_hours` وبالكمية المصروفة. المراجعة تمنع التفاعل `CONTRAINDICATED` إلا مع `acknowledgeInteractions=true`.

### 6.12 BIL — الفواتير

**الالتقاط:** `POST /encounters/{publicId}/charges` (EMR) يسجل تنفيذ خدمة؛ الفاتورة تُبنى من التنفيذات غير المفوترة.

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/billing/invoices` | `BIL:READ` | الفواتير (ترقيم + بحث + حالة) |
| POST | `/billing/invoices` | `BIL:CREATE` | إنشاء من Charge Capture |
| GET | `/billing/invoices/{id}` | `BIL:READ` | تفاصيل + بنود + مدفوعات |
| POST | `/billing/invoices/{id}/discount` | `BIL:DISCOUNT` أو `BIL:DISCOUNT_LIMITED` | خصم نسبي بسبب إلزامي |
| POST | `/billing/invoices/{id}/void` | `BIL:VOID` | إلغاء فاتورة بلا حركات مالية |
| POST | `/billing/invoices/{id}/credit-notes` | `BIL:VOID` | إشعار دائن |
| POST | `/billing/invoices/{id}/payments` | `BIL:PAYMENT` | دفع مع `idempotencyKey` |
| GET | `/billing/payments/{id}/receipt` | `BIL:PRINT` أو `BIL:READ` | إيصال الدفع |
| POST | `/billing/payments/{id}/refunds` | `BIL:REFUND` (+ معتمد `BIL:REFUND_APPROVE`) | استرداد |
| GET | `/billing/cash-sessions` | `BIL:CASH_SESSION` | جلسات الصندوق |
| GET | `/billing/cash-sessions/current` | `BIL:CASH_SESSION` | الوردية الحالية |
| POST | `/billing/cash-sessions` | `BIL:CASH_SESSION` | فتح وردية |
| POST | `/billing/cash-sessions/{id}/close` | `BIL:CASH_SESSION` | إقفال |
| GET | `/billing/reports/daily` | `BIL:READ` | تقرير تحصيل يومي |
| GET | `/billing/service-prices` | `BIL:READ` | قائمة الأسعار السارية |
| POST | `/billing/service-prices` | `BIL:PRICE_LIST` | إضافة سعر ساري |

**أخطاء العقود:** 422 `PAYMENT_EXCEEDS_BALANCE` · 422 `INVOICE_ALREADY_PAID` · 409 `INVOICE_NOT_PAYABLE` (ملغاة/دائنة) · 409 `INVOICE_HAS_PAYMENTS` · 409 `INVOICE_ALREADY_VOID` · 409 `INVOICE_CLOSED` · 409 `CASH_SESSION_CLOSED` · 409 `IDEMPOTENCY_KEY_REUSED` · 409 `IDEMPOTENCY_IN_PROGRESS` · 403 `REFUND_APPROVAL_REQUIRED` · 422 `DISCOUNT_LIMIT_EXCEEDED`.

### 6.13 INV — المخزون

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/inventory/warehouses` | `INV:READ` | المخازن |
| POST | `/inventory/warehouses` | `INV:WRITE` | إضافة |
| PATCH | `/inventory/warehouses/{id}` | `INV:WRITE` | تعديل/تعطيل |
| GET | `/inventory/items` | `INV:READ` | الأصناف (ترقيم + بحث) |
| GET | `/inventory/items/{id}` | `INV:READ` | تفاصيل صنف |
| POST | `/inventory/items` | `INV:WRITE` | إضافة صنف |
| PATCH | `/inventory/items/{id}` | `INV:WRITE` | تعديل صنف |
| GET | `/inventory/suppliers` | `INV:READ` | الموردون |
| POST | `/inventory/suppliers` | `INV:SUPPLIER` | إضافة مورد |
| PATCH | `/inventory/suppliers/{id}` | `INV:SUPPLIER` | تعديل مورد |
| GET | `/inventory/batches` | `INV:READ` | الدفعات والأرصدة |
| POST | `/inventory/batches` | `INV:RECEIVE` | استلام دفعة |
| POST | `/inventory/issues` | `INV:ISSUE` | صرف بمستند |
| POST | `/inventory/write-offs` | `INV:WRITE_OFF` | إتلاف تالف |
| POST | `/inventory/transfers` | `INV:TRANSFER` | تحويل بين مستودعين |
| GET | `/inventory/transfers` | `INV:READ` | التحويلات |
| GET | `/inventory/movements` | `INV:READ` | سجل الحركات |
| GET | `/inventory/alerts` | `INV:READ` | نقص + قرب انتهاء |
| GET | `/inventory/counts` | `INV:READ` | الجرد |
| GET | `/inventory/counts/{id}` | `INV:READ` | تفاصيل الجرد |
| POST | `/inventory/counts` | `INV:COUNT` | بدء جرد (لقطة) |
| PATCH | `/inventory/counts/{id}/lines/{lineId}` | `INV:COUNT` | إدخال كمية معدودة |
| POST | `/inventory/counts/{id}/post` | `INV:COUNT` (+ معتمد `INV:ADJUST`) | ترحيل الفروق |
| GET | `/inventory/purchase-requests` | `INV:READ` | طلبات الشراء |
| POST | `/inventory/purchase-requests` | `INV:REQUEST` | إنشاء طلب |
| POST | `/inventory/purchase-requests/{id}/approve` | `INV:APPROVE` | اعتماد طلب |
| GET | `/inventory/purchase-orders` | `INV:READ` | أوامر الشراء |
| GET | `/inventory/purchase-orders/{id}` | `INV:READ` | تفاصيل + إيصالات |
| POST | `/inventory/purchase-orders` | `INV:PO_CREATE` | إنشاء أمر |
| POST | `/inventory/purchase-orders/{id}/receive` | `INV:RECEIVE` | استلام (GRN) |
| GET | `/inventory/reports/valuation` | `INV:READ` | تقييم المخزون |
| GET | `/inventory/reports/consumption` | `INV:READ` | تقرير الاستهلاك |

**أخطاء العقود:** 400 `DOCUMENT_REQUIRED` · 400 `EXPIRY_REQUIRED` · 400 `REASON_REQUIRED` · 422 `INSUFFICIENT_STOCK` · 422 `BATCH_EXPIRED` · 422 `OVER_RECEIPT` · 422 `PURCHASE_REQUEST_REQUIRED` · 409 `REQUEST_NOT_APPROVED` · 409 `REQUEST_NOT_SUBMITTED` · 409 `PO_NOT_RECEIVABLE` · 409 `COUNT_NOT_OPEN` · 403 `ADJUST_APPROVAL_REQUIRED`.

### 6.14 NOT — الإشعارات

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/notifications` | `NOT:READ` | Inbox (ترقيم + `unreadOnly`) |
| GET | `/notifications/unread-count` | `NOT:READ` | عدّاد الجرس |
| POST | `/notifications/{id}/read` | `NOT:READ` | مقروء |
| POST | `/notifications/read-all` | `NOT:READ` | الكل مقروء |
| GET | `/notifications/stream` | `NOT:READ` | بث SSE حيّ (بديل فوري عن Polling) |
| GET | `/notifications/preferences` | `NOT:READ` | تفضيلات الفئات والحد الأدنى للأهمية |
| PUT | `/notifications/preferences` | `NOT:READ` | حفظ التفضيلات (`{categories:[{category,enabled,minSeverity}]}`) |

**التسليم:** يبثّ `GET /notifications/stream` حدث `ready` عند الاتصال ثم حدث `notification` بعد كل commit، مع تعليق `keep-alive` كل 20 ثانية. تبقى الواجهة على Polling كل 30 ثانية كبديل تلقائي عند انقطاع البث (ADR-006).

### 6.14.1 مراقبة الـ Outbox (تشغيلي)

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/admin/outbox/stats` | `SYS:CONFIG` | عدّادات الحالات + backlog + أقدم حدث + اتصالات SSE |
| GET | `/admin/outbox/events?status=&limit=` | `SYS:CONFIG` | آخر الأحداث (PENDING/PROCESSING/SENT/FAILED/DEAD) |
| POST | `/admin/outbox/{id}/retry` | `SYS:CONFIG` | إعادة إرسال حدث DEAD/FAILED |

### 6.15 RPT — التقارير

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/reports/catalog` | `RPT:READ` | كتالوج التقارير |
| POST | `/reports/{code}/run` | `RPT:RUN` | تشغيل (Async إن ثقيل) |
| GET | `/reports/runs/{id}` | `RPT:READ` | حالة التشغيل |
| GET | `/reports/runs/{id}/download` | `RPT:READ` | تنزيل CSV/Excel |
| GET | `/reports/runs/{id}/print` | `RPT:READ` | قالب طباعة |

### 6.16 ANL — التحليلات

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/analytics/kpis` | `ANL:READ` | كل المؤشرات |
| GET | `/analytics/kpis/{code}` | `ANL:READ` | تعريف + قيمة |
| GET | `/analytics/kpis/{code}/series` | `ANL:READ` | سلسلة زمنية |
| GET | `/analytics/compare` | `ANL:READ` | مقارنة قسم/فترة |
| GET | `/analytics/forecast` | `ANL:READ` | توقع + فاصل ثقة |
| GET | `/analytics/anomalies` | `ANL:READ` | شذوذ |
| GET | `/analytics/data-quality` | `ANL:READ` | جودة البيانات |
| POST | `/analytics/export` | `ANL:EXPORT` | تصدير مع القيود |

### 6.17 DEC — القرارات

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/decisions/cards` | `DEC:READ` | البطاقات |
| GET | `/decisions/cards/{id}` | `DEC:READ` | بطاقة + أدلة |
| POST | `/decisions/cards/{id}/accept` | `DEC:ACT` | قبول |
| POST | `/decisions/cards/{id}/reject` | `DEC:ACT` | رفض بسبب |
| POST | `/decisions/cards/{id}/defer` | `DEC:ACT` | تأجيل |
| POST | `/decisions/cards/{id}/request-data` | `DEC:ACT` | طلب بيانات |
| POST | `/decisions/cards/{id}/approve` | `DEC:APPROVE` | اعتماد |
| POST | `/decisions/cards/{id}/execute` | `DEC:EXECUTE` | تسجيل تنفيذ |
| GET | `/decisions/rules` | `DEC:READ` | DR-001..006 |

### 6.18 INT — التكامل (تصميم)

| Method | Path | PERM | الوصف |
|---|---|---|---|
| GET | `/integration/messages` | `INT:READ` | سجل الرسائل |
| POST | `/integration/messages/{id}/retry` | `INT:WRITE` | إعادة محاولة |
| GET | `/integration/endpoints` | `INT:READ` | نقاط التكامل |

### 6.19 النظام

| Method | Path | Auth | الوصف |
|---|---|---|---|
| GET | `/health` | لا | فحص عام (بلا تفاصيل) |
| GET | `/health/ready` | داخلي | DB + JMS + Disk |
| GET | `/version` | لا | إصدار + بناء |
| GET | `/stream/notifications` | جلسة | SSE |

---

## 7. أمثلة عقود حرجة

### 7.1 تسجيل مريض

```http
POST /api/v1/patients
Content-Type: application/json
X-CSRF-Token: ...

{
  "fullName": "سارة محمد علي",
  "gender": "FEMALE",
  "birthDate": "1992-04-18",
  "birthDateEstimated": false,
  "phone": "+9627...",
  "nationalId": "*********",
  "emergencyContact": { "name": "محمد علي", "relation": "FATHER", "phone": "+9627..." }
}
```

```http
HTTP/1.1 201 Created
Location: /api/v1/patients/01J9Z...
```

```json
{
  "publicId": "01J9Z...",
  "medicalNumber": "P-2026-000421",
  "fullName": "سارة محمد علي",
  "duplicateCheck": { "status": "NO_MATCH", "score": 0 }
}
```

### 7.2 صرف دواء (Idempotent)

```http
POST /api/v1/prescriptions/01J.../dispense
Idempotency-Key: 6f1c2a9e-8d4b-4f1a-9c2e-1b7a3d5f9e21
```

```json
{
  "items": [
    { "prescriptionItemId": "01J...", "quantity": 10, "batchPreference": "FEFO" }
  ],
  "notes": "صرف كامل"
}
```

```json
{
  "dispensingId": "01J...",
  "dispensedAt": "2026-11-11T09:12:44Z",
  "lines": [
    { "drugName": "Amoxicillin 500mg", "batchNo": "L24091", "expiryDate": "2027-03-31", "quantity": 10 }
  ],
  "stockWarnings": [],
  "invoiceId": "01J..."
}
```

### 7.3 تعارض نسخة

```http
HTTP/1.1 409 Conflict
```

```json
{
  "code": "VERSION_CONFLICT",
  "messageAr": "تم تعديل السجل من مستخدم آخر. أعد التحميل ثم حاول مجددًا.",
  "messageEn": "The record was modified by another user.",
  "traceId": "...",
  "details": { "currentVersion": 7, "yourVersion": 6 }
}
```

### 7.4 SSE (مفعّل — Phase 6)

> **حالة v1.1:** البث الحيّ مفعّل عبر `GET /notifications/stream`، ويبقى Polling كل 30 ثانية كبديل تلقائي عند انقطاع الاتصال (ADR-006). تُبثّ الإشعارات **بعد** commit فقط عبر `TransactionSynchronizationRegistry`، فلا يصل للعميل حدث الغته rollback.

```http
GET /api/v1/notifications/stream
Accept: text/event-stream
```

```
event: ready
data: {"unread":3}

event: notification
data: {"publicId":"01J...","type":"LAB_CRITICAL","severity":"CRITICAL","titleAr":"نتيجة حرجة","titleEn":"Critical result","bodyAr":"...","bodyEn":"...","entityType":"LAB_RESULT","entityPublicId":"01J...","createdAt":"2026-11-11T09:15:00Z"}

: keep-alive
```

**قواعد:** تعليق `connected` عند فتح الاتصال، ثم `ready` بعدد غير المقروء؛ `keep-alive` كل 20 ثانية؛ إعادة الاتصال تلقائية من المتصفح؛ تسجيل/إلغاء تسجيل المشتركين في سجل فردي (Singleton) بلا أقفال على مستوى الحاوية.

---

## 8. الصلاحيات: اصطلاح التسمية

```
<DOMAIN>:<ACTION>
```

| المجال | أمثلة |
|---|---|
| `PAT` | `READ`, `CREATE`, `WRITE`, `MERGE` |
| `EMR` | `READ`, `WRITE`, `SIGN`, `ORDER` |
| `LAB` | `READ`, `ORDER`, `COLLECT`, `RECEIVE`, `PROCESS`, `RESULT_ENTER`, `RESULT`, `VERIFY`, `REPORT_RELEASE`, `AMEND`, `PRINT`, `CATALOG` |
| `PHR` | `READ`, `PRESCRIBE`, `REVIEW`, `DISPENSE`, `RETURN`, `BATCH_READ`, `RECEIVE`, `WRITE` |
| `BIL` | `READ`, `CREATE`, `PAY`, `REFUND`, `VOID`, `DISCOUNT` |
| `ADT` | `READ`, `REQUEST`, `WRITE`, `APPROVE`, `ASSIGN`, `HOUSEKEEPING`, `BED_STATUS` |
| `ER` | `READ`, `WRITE`, `CREATE`, `TRIAGE`, `QUEUE` |
| `NUR` | `READ`, `WRITE`, `MED`, `NOTE_SIGN` |
| `NOT` | `READ` |
| `MED` | `ADMINISTER`, `CATALOG_READ` |
| `DEC` | `READ`, `ACT`, `APPROVE`, `EXECUTE` |
| `IAM` | `READ`, `WRITE`, `RESET`, `BREAK_GLASS` |
| `AUDIT` | `READ` |

---

## 9. الحدود والمعدلات

| النطاق | الحد |
|---|---|
| الدخول | 5 محاولات/دقيقة لكل حساب+IP |
| البحث | 30 طلبًا/دقيقة/مستخدم |
| التصدير | 5 عمليات/ساعة/مستخدم |
| رفع الملفات | 10 ملفات/دقيقة/مستخدم |
| SSE | اتصالان/مستخدم (مؤجل في v1) |
| API عام | 300 طلب/دقيقة/مستخدم |

**رؤوس الاستجابة:** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`.

---

## 10. الإهلاك والإصدارات

| الحالة | الإجراء |
|---|---|
| حقل جديد اختياري | إضافة متوافقة داخل v1 |
| حقل يُحذف | إهلاك 90 يومًا + `Deprecation` + `Sunset` |
| تغيير سلوك | إصدار جديد `/v2` |
| خطأ جديد | كود جديد، لا تغيير معنى القديم |

---

## 11. قائمة تحقق العقد

- [ ] كل نقطة لها صلاحية ونطاق بيانات.
- [ ] كل عملية كتابة لها تحقق ورسالة خطأ مفهومة.
- [ ] كل عملية حساسة Idempotent.
- [ ] كل قائمة Paginated.
- [ ] كل تاريخ UTC.
- [ ] كل مبلغ نص عشري.
- [ ] لا تُرجع الكيانات JPA مباشرة.
- [ ] لا تكشف أسماء جداول أو SQL.
- [ ] موثّقة بأمثلة طلب/استجابة.
