# 03 — Schema Constraints, Indexes & Integrity

| Field | Value |
|---|---|
| Document ID | DB-CON-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| DB | MySQL 8.0.38 · InnoDB |
| Principle | القيد في قاعدة البيانات، لا في الكود وحده |

> **القاعدة:** أي قاعدة يمكن للتطبيق أن ينساها، يجب أن تمنعها قاعدة البيانات. الكود خط الدفاع الأول، والقيد هو الأخير الذي لا يُنسى.

---

## 1. اصطلاحات التسمية

| العنصر | الصيغة | مثال |
|---|---|---|
| جدول | `snake_case` جمع | `lab_results` |
| عمود | `snake_case` مفرد | `patient_id` |
| PK | `pk_<table>` | `pk_patients` |
| FK | `fk_<table>_<ref>` | `fk_appointments_patients` |
| Unique | `uq_<table>_<cols>` | `uq_beds_room_bed_no` |
| Index | `ix_<table>_<cols>` | `ix_patients_name_norm` |
| Check | `ck_<table>_<rule>` | `ck_stock_qty_nonneg` |
| View | `v_<domain>_<purpose>` | `v_billing_daily` |

---

## 2. المفاتيح

| البند | القرار |
|---|---|
| PK | `BIGINT UNSIGNED AUTO_INCREMENT` |
| Public ID | `CHAR(26)` ULID، `UNIQUE` |
| FK | `BIGINT UNSIGNED`، نفس نوع المرجع |
| ON DELETE | `RESTRICT` افتراضيًا |
| ON UPDATE | `RESTRICT` |
| Cascade | **ممنوع** على البيانات السريرية/المالية |
| FK Index | إلزامي لكل FK |

**لماذا `RESTRICT`؟** الحذف المتسلسل يمحو تاريخًا طبيًا/ماليًا. الحذف المنطقي (`status`/`voided_at`) هو المسموح.

---

## 3. القيود الفريدة الحرجة

| الجدول | القيد | الغرض |
|---|---|---|
| `users` | `UNIQUE(username)` | لا تكرار اسم دخول |
| `users` | `UNIQUE(email)` | لا تكرار بريد (NULL مسموح متعدد) |
| `patients` | `UNIQUE(medical_number)` | رقم طبي فريد |
| `patients` | `UNIQUE(national_id)` | لا هوية مكررة (NULL متعدد) |
| `patient_identifiers` | `UNIQUE(type, value)` | لا معرف خارجي مكرر |
| `appointments` | `UNIQUE(provider_id, slot_start, active_flag)` | لا موعدان فعّالان لنفس الطبيب/الفترة |
| `bed_assignments` | `UNIQUE(bed_id, active_flag)` | سرير واحد نشط |
| `diagnoses` | `UNIQUE(encounter_id, active_flag)` للأساسي | تشخيص أساسي واحد |
| `stock_batches` | `UNIQUE(drug_id, warehouse_id, batch_no)` | لا دفعة مكررة |
| `payments` | `UNIQUE(idempotency_key)` | لا دفع مكرر |
| `payments` | `UNIQUE(receipt_no)` | إيصال فريد |
| `invoices` | `UNIQUE(invoice_no)` | فاتورة فريدة |
| `notifications` | `UNIQUE(recipient_user_id, event_id)` | لا إشعار مكرر |
| `idempotency_keys` | `UNIQUE(user_id, endpoint, key)` | لا تنفيذ مكرر |
| `outbox_events` | `UNIQUE(event_id)` | حدث واحد |
| `lab_result_versions` | `UNIQUE(result_id, version_no)` | نسخة واحدة |
| `clinical_note_versions` | `UNIQUE(note_id, version_no)` | نسخة واحدة |
| `kpi_snapshots` | `UNIQUE(kpi_id, snapshot_date, dimension_type, dimension_id)` | لقطة واحدة |
| `user_roles` | `PRIMARY KEY(user_id, role_id)` | لا تكرار دور |
| `role_permissions` | `PRIMARY KEY(role_id, permission_id)` | لا تكرار صلاحية |

### 3.1 نمط "الفريد الجزئي" عبر `active_flag`

MySQL لا يدعم Partial Unique Index. الحل:

```sql
active_flag TINYINT NULL,           -- 1 = نشط، NULL = منتهي
UNIQUE KEY uq_bed_active (bed_id, active_flag)
```

- الصف النشط: `active_flag = 1` ⇒ يمنع أي صف نشط ثانٍ لنفس `bed_id`.
- الصف المنتهي: `active_flag = NULL` ⇒ لا يتعارض (NULL لا يساوي NULL في Unique).

**يُطبَّق على:** `bed_assignments`, `appointments` (المواعيد الفعّالة), `diagnoses` (الأساسي), `patient_flags` (النشط).

**قاعدة التطبيق:** عند الإنهاء، يجب تعيين `active_flag = NULL` و`ended_at` في نفس التحديث.

---

## 4. قيود التحقق (CHECK)

| الجدول | القيد | التعبير |
|---|---|---|
| `patients` | تاريخ ميلاد ليس مستقبليًا | `birth_date IS NULL OR birth_date <= CURDATE()` |
| `vitals` | ضغط منطقي | `systolic IS NULL OR diastolic IS NULL OR systolic >= diastolic` |
| `vitals` | SpO2 | `spo2 IS NULL OR (spo2 BETWEEN 0 AND 100)` |
| `vitals` | ألم | `pain_score IS NULL OR (pain_score BETWEEN 0 AND 10)` |
| `stock_batches` | كمية غير سالبة | `quantity_on_hand >= 0` |
| `stock_batches` | محجوز ≤ متاح | `quantity_reserved <= quantity_on_hand` |
| `stock_movements` | كمية غير صفرية | `quantity <> 0` |
| `invoices` | إجمالي غير سالب | `grand_total >= 0` |
| `payments` | مبلغ موجب | `amount > 0` |
| `refunds` | مبلغ موجب | `amount > 0` |
| `lab_result_versions` | قيمة أو نص | `value_numeric IS NOT NULL OR value_text IS NOT NULL` |
| `clinical_note_versions` | سبب التصحيح | `amends_id IS NULL OR amend_reason IS NOT NULL` |
| `appointments` | نهاية بعد بداية | `slot_end > slot_start` |
| `admissions` | خروج بعد دخول | `discharged_at IS NULL OR discharged_at >= admitted_at` |
| `bed_assignments` | نهاية بعد بداية | `ended_at IS NULL OR ended_at >= started_at` |
| `break_glass_grants` | مدة ≤ 60 دقيقة | `expires_at IS NULL OR TIMESTAMPDIFF(MINUTE, granted_at, expires_at) <= 60` |
| `service_prices` | فترة صحيحة | `valid_to IS NULL OR valid_to >= valid_from` |
| `lab_reference_ranges` | مدى منطقي | `low_value IS NULL OR high_value IS NULL OR low_value <= high_value` |

**ملاحظة:** MySQL 8.0 يفرض `CHECK` فعليًا (بخلاف 5.7). تُختبر في Migration.

---

## 5. استراتيجية الفهارس

### 5.1 مبادئ

| المبدأ | التطبيق |
|---|---|
| كل FK له فهرس | يمنع Full Scan عند الحذف/الربط |
| فهرس مركب بترتيب الانتقائية | `(status, created_at)` لا العكس |
| تغطية الاستعلامات المتكررة | Include columns عند الحاجة |
| لا فهارس زائدة | كل فهرس يبطئ الكتابة |
| لا فهرس على أعمدة منخفضة التنوع وحدها | `gender` وحده بلا فائدة |
| `LIKE 'x%'` فقط | `LIKE '%x%'` لا يستخدم الفهرس |
| بحث نصي | `FULLTEXT` على `patients.full_name`/`name_normalized` |

### 5.2 الفهارس الأساسية

| الجدول | الفهرس | الاستخدام |
|---|---|---|
| `patients` | `(status, name_normalized)` | بحث القوائم |
| `patients` | `(phone)`, `(national_id)` | منع التكرار |
| `patients` | `FULLTEXT(full_name, name_normalized)` | بحث سريع |
| `appointments` | `(provider_id, slot_start, status)` | تقويم الطبيب |
| `appointments` | `(patient_id, slot_start DESC)` | تاريخ المريض |
| `appointments` | `(clinic_id, slot_start)` | كشف العيادة |
| `encounters` | `(patient_id, started_at DESC)` | Timeline |
| `encounters` | `(provider_id, status, started_at)` | لوحة الطبيب |
| `clinical_notes` | `(encounter_id, note_type)` | سجل الزيارة |
| `vitals` | `(patient_id, recorded_at DESC)` | اتجاه العلامات |
| `lab_orders` | `(status, ordered_at)` | Worklist |
| `lab_results` | `(status, released_at)` | نتائج معلّقة |
| `specimens` | `(barcode)` UK | مسح |
| `prescriptions` | `(status, issued_at)` | طابور الصيدلية |
| `stock_batches` | `(drug_id, warehouse_id, status, expiry_date)` | FEFO |
| `stock_batches` | `(expiry_date, status)` | تنبيه الانتهاء |
| `admissions` | `(status, admitted_at)` | لوحة التنويم |
| `bed_assignments` | `(bed_id, active_flag)` UK | السرير |
| `invoices` | `(patient_id, issued_at DESC)` | فواتير المريض |
| `invoices` | `(status, issued_at)` | ذمم |
| `payments` | `(cash_session_id, paid_at)` | إقفال |
| `audit_logs` | `(actor_user_id, created_at)` | تحقيق |
| `audit_logs` | `(entity_type, entity_public_id, created_at)` | تتبع سجل |
| `notifications` | `(recipient_user_id, read_at, created_at DESC)` | Inbox |
| `outbox_events` | `(status, next_attempt_at, id)` | Dispatcher |
| `kpi_snapshots` | `(kpi_id, snapshot_date)` | سلسلة زمنية |
| `decision_cards` | `(status, priority, created_at)` | لوحة القرار |

### 5.3 فهارس يجب تجنّبها

| النمط | السبب |
|---|---|
| فهرس على `is_active` وحده | تنوع منخفض |
| فهرس على `notes`/`TEXT` كامل | حجم كبير |
| 5+ فهارس على جدول كتابة كثيف | بطء إدراج |
| فهرس مكرر بنفس الأعمدة بترتيب مختلف | هدر |

---

## 6. Partitioning

| الجدول | النوع | المفتاح | لماذا |
|---|---|---|---|
| `audit_logs` | RANGE شهري | `created_at` | نمو سريع، أرشفة سهلة |
| `access_logs` | RANGE شهري | `accessed_at` | نفس السبب |
| `outbox_events` | RANGE شهري | `created_at` | تنظيف دوري |
| `notifications` | RANGE شهري | `created_at` | حجم كبير |

**قاعدة:** لا Partitioning لجداول المعاملات الأساسية في v1 (تعقيد بلا فائدة).

**الأرشفة:** نقل partitions أقدم من 12 شهرًا إلى جداول `*_archive` ثم حذف الـ partition.

---

## 7. Views للتحليلات

| View | الغرض | قراءة فقط |
|---|---|---|
| `v_visits_daily` | زيارات/يوم/قسم | ✅ |
| `v_bed_occupancy` | إشغال/جناح/يوم | ✅ |
| `v_lab_tat` | زمن دورة المختبر | ✅ |
| `v_pharmacy_dispensing` | صرف/دواء/يوم | ✅ |
| `v_revenue_daily` | إيراد/خدمة/يوم | ✅ |
| `v_ar_aging` | أعمار الذمم | ✅ |
| `v_stock_risk` | نقص/انتهاء | ✅ |
| `v_data_quality` | اكتمال/تناقض | ✅ |

**قاعدة:** `analytics` يقرأ هذه الـ Views فقط. لا `SELECT` على جداول النطاقات مباشرة.

---

## 8. الحذف المنطقي والأرشفة

| النوع | السياسة |
|---|---|
| مريض | `status = INACTIVE/MERGED` |
| موعد | `status = CANCELLED` |
| فاتورة | `status = VOIDED` أو إشعار دائن |
| دفعة | لا حذف؛ حركة `RETURN` |
| ملاحظة | لا حذف؛ `voided_at` + سبب |
| مستخدم | `status = DISABLED` |
| إعداد | لا حذف؛ قيمة جديدة + تدقيق |

**ممنوع:** `DELETE` من التطبيق على الجداول السريرية/المالية/التدقيق. صلاحية DB لا تمنح `DELETE` لهذه الجداول.

---

## 9. صلاحيات قاعدة البيانات

| المستخدم | الصلاحيات |
|---|---|
| `sh_app` | `SELECT, INSERT, UPDATE` على جداول الأعمال؛ `INSERT` فقط على `audit_logs`, `access_logs`, `stock_movements` |
| `sh_readonly` | `SELECT` على Views فقط |
| `sh_migrate` | `DDL` أثناء الترحيل فقط |
| `root` | إداري، لا يُستخدم من التطبيق |

**قاعدة:** التطبيق لا يملك `DROP`, `ALTER`, `DELETE` على جداول التدقيق.

---

## 10. Triggers

| Trigger | الغرض | مسموح؟ |
|---|---|---|
| منع `UPDATE` على `clinical_note_versions` الموقّعة | حماية التوقيع | ✅ |
| منع `DELETE` على `audit_logs` | حماية التدقيق | ✅ (احتياطي) |
| تحديث `updated_at` تلقائيًا | اتساق | ✅ |
| حساب إجمالي الفاتورة | منطق أعمال | ❌ (في الخدمة) |
| إرسال إشعار | تكامل | ❌ (Outbox) |

**قاعدة:** Trigger للحماية فقط، لا لمنطق أعمال.

---

## 11. مصفوفة السلامة المرجعية

| العلاقة | عند حذف الأب | عند تعطيل الأب |
|---|---|---|
| Patient → Encounter | RESTRICT | لا تأثير |
| Encounter → LabOrder | RESTRICT | لا تأثير |
| Invoice → Payment | RESTRICT | لا تأثير |
| Admission → BedAssignment | RESTRICT | لا تأثير |
| Role → UserRole | RESTRICT | منع التعطيل إن كان مستخدمًا |
| Service → ServicePrice | RESTRICT | إغلاق السعر |
| Drug → StockBatch | RESTRICT | منع الصرف |

---

## 12. ضوابط الأداء

| الضابط | القيمة |
|---|---|
| استعلام بلا `LIMIT` | ممنوع على جداول > 10k |
| `SELECT *` | ممنوع في الإنتاج |
| N+1 | ممنوع؛ استخدام Join/Fetch |
| استعلام > 500ms | يُسجَّل ويُراجع |
| فهرس غير مستخدم | يُراجع شهريًا |
| جدول > 10M | يُراجع Partitioning |
| Locks > 5s | يُسجَّل |

---

## 13. فحوص جودة البيانات (Data Quality)

| الفحص | الشرط | الإجراء |
|---|---|---|
| `DQ-001` | مريض بلا هاتف | تنبيه |
| `DQ-002` | زيارة بلا تشخيص | تنبيه |
| `DQ-003` | نتيجة معتمدة بلا مرجع | تنبيه |
| `DQ-004` | فاتورة بلا بنود | منع |
| `DQ-005` | دفعة بلا فاتورة | منع |
| `DQ-006` | سرير مشغول بلا تنويم نشط | منع/تصحيح |
| `DQ-007` | تاريخ ميلاد مستقبلي | منع |
| `DQ-008` | دواء منتهي في المخزون المتاح | منع الصرف |
| `DQ-009` | موعد لطبيب غير مفعّل | منع |
| `DQ-010` | سجل بلا `public_id` | منع |

---

## 14. قائمة تحقق المخطط

- [ ] كل جدول له PK و`public_id` (عدا المراجع الصغيرة).
- [ ] كل FK له فهرس و`ON DELETE RESTRICT`.
- [ ] كل قاعدة عمل حرجة لها قيد DB أو Trigger حماية.
- [ ] كل قيد UNIQUE جزئي يستخدم `active_flag`.
- [ ] كل مبلغ `DECIMAL(14,2)`.
- [ ] كل وقت `DATETIME(6)` UTC.
- [ ] لا `ENUM` (استخدام `VARCHAR` + `CHECK`).
- [ ] لا `DELETE` للتطبيق على الجداول الحساسة.
- [ ] كل View للقراءة فقط.
- [ ] كل فهرس مبرر.
