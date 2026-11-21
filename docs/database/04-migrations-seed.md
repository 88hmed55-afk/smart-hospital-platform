# 04 — Migrations & Seed Data

| Field | Value |
|---|---|
| Document ID | DB-MIG-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Tool | SQL scripts + Ant task (لا Flyway/Liquibase) |
| Policy | Forward-only · Versioned · Reviewed |

> **لماذا لا Flyway؟** لتقليل الاعتماديات والبقاء على Ant. السكربتات SQL صريحة، والمشغّل صغير، وكل تغيير قابل للمراجعة في Git. هذا لا يمنع الانتقال إلى Flyway لاحقًا؛ الترتيب نفسه.

---

## 1. هيكل ملفات الترحيل

```
db/
├── migrations/
│   ├── V001__create_platform_iam.sql              // ✅ المرحلة 1: هيكل + IAM
│   ├── V002__create_audit_outbox.sql              // ✅ المرحلة 1: تدقيق + Outbox
│   ├── V003__create_iam_phase2.sql                // ✅ المرحلة 2: جلسات/تفويض/Break-Glass/TOTP/إعدادات
│   ├── V004__create_patients_scheduling_emr.sql   // ✅ المرحلة 3: مرضى/مواعيد/زيارة/وصفة/رسوم
│   ├── V005__create_adt_nursing.sql               // مخطط — المرحلة 4
│   ├── V006__create_lab_radiology.sql             // مخطط — المرحلة 4
│   ├── V007__create_pharmacy_inventory.sql        // مخطط — المرحلة 4
│   ├── V008__create_billing.sql                   // مخطط — المرحلة 5
│   ├── V009__create_notifications_reporting.sql   // مخطط — المرحلة 6/7
│   ├── V010__create_analytics_decision.sql        // مخطط — المرحلة 7
│   ├── V011__indexes_constraints.sql              // مخطط — يُضاف مع المرحلة التي تحتاجه
│   ├── V012__views_analytics.sql                  // مخطط
│   └── V013__seed_reference.sql                   // مخطط
├── seed/
│   ├── S000__platform_defaults.sql                // ✅ فرع/قسم/إعدادات أساسية
│   ├── S001__roles_permissions.sql                // ✅ 18 دورًا + كتالوج الصلاحيات
│   ├── S002__iam_defaults.sql                     // ✅ المرحلة 2: إعدادات IAM وروابط الأدوار
│   ├── S003__phase3_defaults.sql                  // ✅ المرحلة 3: عيادات/خدمات/أسعار/أدوية/إعدادات
│   └── S004__demo_data.sql                        // مخطط: بيانات تجريبية (لا تُزرع في الإنتاج)
├── verify/
│   ├── Q001__schema_checks.sql            // ✅ منفّذ
│   ├── Q002__data_quality.sql
│   └── Q003__counts.sql
├── tools/src/com/smarthospital/db/MigrationRunner.java
├── bootstrap.sql
└── build.xml
```

> **تحديث تنفيذي (2026-11-12):** عند التنفيذ الفعلي فُصل التدقيق/Outbox في `V002` ليأتي مبكرًا (تحتاجه كل الوحدات من أول معاملة)، وأُضيف `S000` للافتراضيات. لذلك أرقام الوحدات التالية أُزيحَت بمقدار واحد عن خط الأساس الأصلي. الترتيب المنطقي محفوظ؛ الترقيم المتسلسل بلا فجوات هو الملزم.
>
> **تحديث تنفيذي (2026-11-13):** أُضيف `V003__create_iam_phase2.sql` (جلسات، تفويض، Break-Glass، TOTP، إعدادات) و`V004__create_patients_scheduling_emr.sql` (المرحلة 3 كاملة)، و`S002__iam_defaults.sql` و`S003__phase3_defaults.sql`. الحالة الفعلية: `schema_version` = `V004` · 96 صلاحية · 361 ربط دور. الترحيل التالي سيكون `V005` (ADT/NUR). لا يُعاد ترقيم الملفات المطبَّقة.
>
> **تحديث تنفيذي (2026-11-17):** اكتملت المرحلتان 4 و5 بالأسماء الفعلية: `V005__create_er_adt_nur_lab_phr.sql` · `V006__fix_lab_item_status_check.sql` · `V007__create_billing_inventory.sql` · `V008__fix_invoice_balance.sql` (عمود رصيد مولّد غير سالب) · `V009__widen_number_sequence_period.sql` (`CHAR(8)`)، مع `S004__phase4_defaults.sql` و`S005__phase5_defaults.sql`. الحالة الفعلية: `schema_version` = `V009` · 100 جدولًا · 114 صلاحية · 494 ربط دور. أسماء الملفات في الشجرة أعلاه خطة أصلية لم تعد مطابقة؛ الملزم هو ما في `db/migrations/`.
>
> **تحديث تنفيذي (2026-11-17، المرحلة 6):** أُضيف `V010__notification_preferences_outbox_claim.sql` (`outbox_events.claimed_at` + `ix_outbox_events_claim` + جدول `notification_preferences`) و`V011__outbox_claim_token.sql` (`claim_token VARCHAR(40)` + `ix_outbox_events_ready (status, id)` للمطالبة الذرية). الحالة الفعلية: `schema_version` = `V011` · **101 جدولًا** · 114 صلاحية · 494 ربط دور. `notification_preferences` تُدار بالكود لا بالبذر.

**قاعدة التسمية:** `V<NNN>__<verb>_<noun>.sql` — أرقام متسلسلة بلا فجوات. لا تعديل على ملف مُطبَّق؛ يُضاف ملف جديد.

---

## 2. جدول الإصدارات

```sql
CREATE TABLE schema_version (
  version      VARCHAR(20) PRIMARY KEY,
  description  VARCHAR(200) NOT NULL,
  checksum     CHAR(64) NOT NULL,
  applied_at   DATETIME(6) NOT NULL,
  applied_by   VARCHAR(80) NOT NULL,
  execution_ms INT NOT NULL
);
```

| القاعدة | التفصيل |
|---|---|
| التحقق | `checksum` (SHA-256) لكل ملف |
| الاختلاف | يوقف الترحيل ويطلب مراجعة |
| الترتيب | تصاعدي صارم |
| التكرار | الملفات المُطبَّقة (`V*`) لا تُعاد؛ الـ Seed (`S*`) idempotent ويُعاد بأمان |
| السجل | لا يُحذف |

---

## 3. المشغّل (JDBC Runner عبر Ant)

المشغّل الفعلي هو `db/tools/src/com/smarthospital/db/MigrationRunner.java` (JDBC + MySQL Connector/J)، وليس `mysql` CLI. هذا يوحّد السلوك على Windows/Linux ويتيح التحقق من checksum برمجيًا.

```xml
<target name="db-migrate" depends="compile-runner, check-config">
  <java classname="com.smarthospital.db.MigrationRunner" fork="true" failonerror="true">
    <sysproperty key="db.root" value="${project.root}"/>
    <sysproperty key="db.properties" value="${db.config}"/>
    <classpath>
      <pathelement location="${tools.build}"/>
      <pathelement location="${mysql.connector.jar}"/>
    </classpath>
    <arg value="migrate"/>
  </java>
</target>
```

| الأمر | الوظيفة |
|---|---|
| `ant -f db/build.xml db-migrate` | يطبّق `migrations/V*.sql` بالترتيب ويسجّل checksum |
| `ant -f db/build.xml db-seed` | يعيد تشغيل `seed/S*.sql` (idempotent) |
| `ant -f db/build.xml db-verify` | ينفّذ `verify/Q*.sql` ويعرض النتائج |
| `ant -f db/build.xml db-status` | يعرض `schema_version` |

| الخطوة | ما يحدث |
|---|---|
| 1 | قراءة `schema_version` |
| 2 | مقارنة checksum للملفات المطبقة |
| 3 | تحديد الملفات الجديدة |
| 4 | تنفيذ كل ملف في معاملة (حيث ممكن) |
| 5 | تسجيل الإصدار |
| 6 | تشغيل `verify/Q001` |
| 7 | إيقاف عند أي خطأ |

**ملاحظة:** `DDL` في MySQL يسبب Commit ضمني؛ لذلك كل ملف DDL مستقل، ويُختبر على نسخة قبل الإنتاج.

---

## 4. بيئات التشغيل

| البيئة | قاعدة البيانات | بيانات | من ينفّذ |
|---|---|---|---|
| Dev | `smart_hospital_dev` | Demo مصغّر | المطور |
| Test | `smart_hospital_test` | Demo كامل | CI/QA |
| Staging | `smart_hospital_stg` | Demo كامل + سيناريوهات | QA |
| Prod | `smart_hospital` | مرجعية + بيانات فعلية | DBA/IT |

**قاعدة:** لا تُنسخ بيانات الإنتاج إلى Dev/Test. إن لزم، تُخفى الهوية أولًا.

---

## 5. البيانات المرجعية (Seed)

### 5.1 ما يُزرع دائمًا

| المجموعة | المحتوى | المصدر |
|---|---|---|
| الأدوار | R-01..R-18 | `06-rbac-matrix.md` |
| الصلاحيات | `DOMAIN:ACTION` | `04-api-contracts.md` §8 |
| ربط الأدوار | حسب المصفوفة | `06-rbac-matrix.md` |
| الخدمات | استشارات، مختبر، أشعة، إجراءات | كتالوج افتراضي |
| الأسعار | سعر مبدئي بتاريخ سريان | المالك يعدّله |
| فحوصات المختبر | 40–60 فحصًا شائعًا | كتالوج عام |
| الأدوية | 100–150 دواءً شائعًا | كتالوج عام |
| القوالب | ملاحظة تقدمية، وصفة، تقرير | الفريق |
| KPIs | KPI-001..036 | `analytics/01` |
| قواعد القرار | DR-001..006 | `analytics/03` |
| الإعدادات | اسم، عملة، توقيت، لغة | المالك |

**قاعدة:** كل Seed idempotent (`INSERT ... ON DUPLICATE KEY UPDATE` أو `WHERE NOT EXISTS`)، وقابل لإعادة التشغيل.

### 5.2 ما لا يُزرع في الإنتاج

- مرضى وهميون.
- زيارات/فواتير تجريبية.
- مستخدمون بكلمات مرور معروفة (عدا `admin` أولي يُغيّر عند أول دخول).
- بيانات 12 شهرًا (للتحليلات في العرض فقط).

---

## 6. مولّد البيانات الوهمية

| البند | القرار |
|---|---|
| الهدف | 12 شهرًا من البيانات المترابطة للتحليلات والعرض |
| الحجم | 8,000–12,000 مريض، 25,000–35,000 زيارة، 20,000 وصفة، 15,000 طلب مختبر |
| التوزيع | موسمي واقعي (شتاء أعلى للتنفسي) |
| الترابط | زيارة → طلب → نتيجة → وصفة → صرف → فاتورة → دفع |
| العشوائية | Seed ثابت (`RAND(42)`) لإعادة الإنتاج |
| الأسماء | عربية/إنجليزية مختلطة (ASM-012) |
| الأرقام | لا تستخدم أرقام هوية حقيقية |
| التحقق | `Q002` + `Q003` بعد التوليد |

**ممنوع:** استخدام بيانات مرضى حقيقيين، أو أسماء مشاهير، أو أرقام هوية صالحة.

---

## 7. أحجام البيانات المستهدفة (Demo)

| الجدول | الصفوف التقريبية |
|---|---|
| `patients` | 10,000 |
| `appointments` | 40,000 |
| `encounters` | 30,000 |
| `clinical_notes` | 35,000 |
| `lab_orders` | 18,000 |
| `lab_results` | 45,000 |
| `prescriptions` | 22,000 |
| `dispensing_transactions` | 18,000 |
| `admissions` | 3,000 |
| `invoices` | 25,000 |
| `payments` | 28,000 |
| `audit_logs` | 200,000 |
| `notifications` | 50,000 |

**الهدف:** كفاءة اختبار الأداء والفهارس، لا مجرد "شكل" البيانات.

---

## 8. إجراء الترحيل (Runbook)

### 8.1 بيئة جديدة

```text
1. إنشاء DB + مستخدم sh_app
2. ant db-migrate -Ddb.env=dev
3. ant db-seed   -Ddb.env=dev
4. ant db-verify -Ddb.env=dev
5. تشغيل التطبيق + فحص /health
```

### 8.2 ترقية إنتاج

| الخطوة | المسؤول | التحقق |
|---|---|---|
| 1. نسخة احتياطية كاملة | IT | `restore` على بيئة اختبار |
| 2. إيقاف الكتابة (وضع صيانة) | IT | رسالة للمستخدمين |
| 3. تنفيذ الترحيل | DBA | `schema_version` |
| 4. تشغيل Verify | DBA | 0 أخطاء |
| 5. نشر EAR | IT | `/health` = UP |
| 6. اختبار دخان | QA | 10 سيناريوهات |
| 7. إعادة الكتابة | IT | مراقبة 30 دقيقة |

**RTO للترقية:** ≤ 60 دقيقة. **Rollback:** استعادة النسخة (لا Rollback SQL في الإنتاج).

---

## 9. سياسة Rollback

| الحالة | الإجراء |
|---|---|
| فشل قبل النشر | إصلاح السكربت وإعادة التنفيذ |
| فشل بعد النشر | استعادة النسخة الاحتياطية + نشر EAR السابق |
| بيانات خاطئة | سكربت تصحيح موثّق (لا حذف) |
| DDL جزئي | استعادة كاملة (MySQL DDL غير معاملاتي) |

**قاعدة:** لا `DOWN` scripts في الإنتاج. الاستعادة هي خطة التراجع.

---

## 10. الفحوص (Verify)

| الاستعلام | يتحقق من |
|---|---|
| `Q001` | وجود كل الجداول والفهارس والقيود |
| `Q002` | جودة البيانات (DQ-001..010) |
| `Q003` | أعداد متوقعة + لا تكرار |
| `Q004` | صلاحيات DB (لا DELETE على التدقيق) |
| `Q005` | كل جدول له `public_id` حيث يلزم |

**شرط النجاح:** 0 أخطاء حرجة، وكل تحذير موثّق.

---

## 11. اختبار الترحيلات

| المستوى | الأداة | ما يُختبر |
|---|---|---|
| محلي | MySQL Docker/نظيف | الترحيل من الصفر |
| CI | سكربت | الترحيل + Seed + Verify |
| ترقية | نسخة من Staging | من الإصدار السابق |
| أداء | بيانات Demo | زمن الترحيل والفهارس |

**قاعدة:** كل PR يغيّر المخطط يجب أن يضيف Migration + Verify + تحديث `02-data-dictionary.md`.

---

## 12. إدارة التغيير

| التغيير | يحتاج |
|---|---|
| جدول جديد | Migration + Dictionary + ERD |
| عمود جديد | Migration + Dictionary |
| قيد جديد | Migration + اختبار بيانات موجودة |
| فهرس جديد | Migration + قياس أداء |
| حذف عمود | إهلاك 2 إصدارات + تأكيد عدم الاستخدام |
| تغيير نوع | Migration + نسخ بيانات + تحقق |

**ممنوع:** تعديل Migration مُطبَّق. يُضاف `V015` وهكذا.

---

## 13. قائمة تحقق قبل الإنتاج

- [ ] نسخة احتياطية تم اختبار استعادتها.
- [ ] Migration يعمل على نسخة نظيفة.
- [ ] Migration يعمل على نسخة من الإنتاج.
- [ ] Verify يمر.
- [ ] الفهارس موجودة.
- [ ] صلاحيات DB صحيحة.
- [ ] Seed المرجعي مكتمل.
- [ ] لا بيانات وهمية في الإنتاج.
- [ ] `schema_version` محدّث.
- [ ] خطة Rollback موثّقة.
- [ ] الدليل محدّث.

---

## 14. المراجع

- `02-data-dictionary.md` — الأعمدة.
- `03-schema-constraints-indexes.md` — القيود.
- `deployment/03-installation.md` — التركيب.
- `deployment/04-backup-recovery.md` — النسخ والاستعادة.
