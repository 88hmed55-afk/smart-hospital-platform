# Smart Hospital

**نظام إدارة مستشفى مؤسسي — Modular Monolith · Jakarta EE 10 · React 19 · MySQL 8.0.38**

| Field | Value |
|---|---|
| Phase | **Phase 5 — Billing + Inventory ✅ (G5 passed 2026-11-17)** |
| Next | Phase 6 — Notifications + Outbox + SSE |
| Baseline | `docs/` — approved design baseline 0.1 (2026-11-12) |
| Runtime | JDK 17 · GlassFish 7 Full · MySQL 8.0.38 |
| Build | Apache Ant + NetBeans 21 (Java) · Vite (frontend) |
| Deployment | خادم مركزي واحد داخل LAN · HTTPS فقط |

> **تنبيه:** هذا المستودع قيد التنفيذ. لا يُعدّ جاهزًا سريريًا أو إنتاجيًا، ولا يدّعي أي اعتماد قانوني.
> المرجع الملزم للتصميم هو `docs/`؛ وأي خلاف بين الكود والوثائق يُحلّ لصالح الوثائق حتى يُعتمد تغيير موثّق.

---

## 1. البنية

```
.
├── smart-hospital-ejb/      EJB + JPA + JTA (business logic)
├── smart-hospital-war/      JAX-RS + Filters + static React build
├── smart-hospital-ear/      EAR packaging (EJB + WAR)
├── frontend/                React 19 + TypeScript + Vite
├── db/                      Migrations · Seed · Verify · JDBC runner
├── config/                  External configuration template
├── build.properties.example إعدادات البناء الخارجية (تُنسخ إلى build.properties)
└── docs/                    Approved design baseline (44 files)
```

**اتجاه الاعتماد:** `WAR (web) → EJB (service → domain/repo)`. لا JPA في WAR، ولا SQL في React.

---

## 2. المتطلبات المحلية

| الأداة | الإصدار | ملاحظة |
|---|---|---|
| JDK | 17 | `JAVA_HOME` مضبوط |
| Apache Ant | 1.10.x | `ANT_HOME` مضبوط |
| GlassFish | 7 Full | Jakarta EE 10 |
| MySQL | 8.0.38 | InnoDB · `utf8mb4` |
| Node.js | 20+ | للواجهة فقط |
| NetBeans | 21 | اختياري للتطوير |

---

## 3. الإعداد

1. انسخ الإعدادات الخارجية (خارج المستودع، لا تُرفع إلى Git):

```bash
# Linux (الإنتاج)
sudo mkdir -p /etc/smart-hospital
sudo cp config/hospital.properties.example /etc/smart-hospital/hospital.properties

# Windows (التطوير)
mkdir "%USERPROFILE%\smart-hospital"
copy config\hospital.properties.example "%USERPROFILE%\smart-hospital\hospital.properties"
```

2. أنشئ ملفات الإعداد المحلية من القوالب واضبط المسارات وبيانات الاتصال:

```bash
cp build.properties.example build.properties
cp db/db.properties.example db/db.properties
```

> `build.properties` يحدد مسار `jakarta.api.jar` و`mysql.connector.jar` و`glassfish.home`. الملفان مستبعدان من Git.

3. أنشئ قاعدة البيانات والمستخدمين (مرة واحدة على خادم MySQL):

```bash
mysql -u root -p < db/bootstrap.sql
```

4. طبّق الترحيلات والبيانات المرجعية (JDBC Runner، لا حاجة لـ `mysql` CLI):

```bash
ant -f db/build.xml db-migrate
ant -f db/build.xml db-seed
ant -f db/build.xml db-verify
```

5. ابنِ الواجهة ثم EAR (أمر واحد):

```bash
ant -f smart-hospital-ear/build.xml frontend ear
```

> إن لم تكن الواجهة قد تغيّرت يمكنك الاكتفاء بـ `ant -f smart-hospital-ear/build.xml ear`، بشرط وجود `frontend/dist` مسبقًا.

6. جهّز GlassFish (مرة واحدة):

```bash
# سائق MySQL داخل classpath الخادم (يجب إعادة تشغيل النطاق بعده)
cp mysql-connector-j-8.4.0.jar "$GLASSFISH_HOME/glassfish/domains/domain1/lib/"

# Pool + DataSource
asadmin create-jdbc-connection-pool --datasourceclassname com.mysql.cj.jdbc.MysqlDataSource \
  --restype javax.sql.DataSource \
  --property "user=sh_app:password=<APP_PASSWORD>:serverName=127.0.0.1:portNumber=3306:databaseName=smart_hospital:useSSL=false:allowPublicKeyRetrieval=true:characterEncoding=utf8" \
  smartHospitalPool
asadmin create-jdbc-resource --connectionpoolid smartHospitalPool jdbc/smartHospitalDS
asadmin ping-connection-pool smartHospitalPool
```

7. انشر التطبيق على جذر السياق `/`:

```bash
asadmin deploy --force=true --contextroot / --name smart-hospital \
  smart-hospital-ear/dist/smart-hospital.ear
```

8. تحقق:

```bash
curl -i http://localhost:8080/api/v1/health
curl -o /dev/null -w "%{http_code}\n" http://localhost:8080/login
curl -o /dev/null -w "%{http_code}\n" http://localhost:8080/app/dashboard
```

> الدخول الأول في بيئة التطوير: `admin` / `Admin@12345` (يُفرض تغييرها عند أول دخول). في `PROD` يرفض التطبيق الإقلاع بلا `iam.bootstrap.admin_password` صريح.

---

## 4. مخرجات المراحل المنجزة

### 4.1 المرحلة 1 — التأسيس التقني ✅

| المخرج | الحالة | الدليل |
|---|---|---|
| هيكل EAR/EJB/WAR + بناء Ant | ✅ | `ant clean ear` ينتج ثلاث حزم في `dist/` الخاصة بكل وحدة |
| `persistence.xml` + DataSource JNDI | ✅ | `smartHospitalPool` + `jdbc/smartHospitalDS`؛ `ping` ناجح |
| ترحيلات الأساس `V001`–`V002` + Seed أدوار/صلاحيات | ✅ | `schema_version` = V001/V002؛ 18 دورًا · 88 صلاحية |
| مشغّل ترحيل بإصدارات و checksum | ✅ | `MigrationRunner` عبر `ant -f db/build.xml` |
| `/api/v1/health` (DB + Disk) | ✅ | HTTP 200 · `database=UP` · `disk=UP` |
| Trace ID + رؤوس أمان + مغلّف أخطاء JSON | ✅ | `X-Trace-Id` · CSP/X-Frame-Options · `{"code":...}` |
| واجهة React RTL هيكلية + SPA fallback | ✅ | عربي RTL ↔ إنجليزي LTR · `/app/dashboard` = 200 |
| CI (`ant clean build` + `npm run build`) | ⏳ | تحقق يدوي مكتمل؛ خط CI مؤتمت مؤجّل إلى مرحلة لاحقة |

### 4.2 المرحلة 2 — الهوية والصلاحيات والتدقيق و SYS ✅

| المخرج | الحالة | الدليل |
|---|---|---|
| حسابات + سياسة كلمة مرور + أول دخول + تعطيل/تفعيل | ✅ | PBKDF2-SHA256 (600k) · `must_change_password` يفرض التغيير · اختبار واجهة كامل: إنشاء `ui.flow.test` ← دخول ← شاشة تغيير إلزامية ← دخول ناجح (عُطِّل الحساب بعد التحقق؛ الدخول بعده = 403) |
| 18 دورًا · 91 صلاحية · 338 ربطًا على 21 نطاقًا | ✅ | `S002` · تبويب «الأدوار والصلاحيات» يعرضها ويسمح بتعديل صلاحيات الدور مع تدقيق `ROLE_PERMISSIONS_UPDATED` |
| نطاقات بيانات + تفويض على الخادم | ✅ | `AuthorizationService` · NURSE لا يرى رابط الإدارة ويُمنع من `/app/admin` (403 + «لا تملك صلاحية الوصول») |
| جلسات Cookie + تدوير + إبطال فردي/جماعي | ✅ | `SH_SESSION` HttpOnly/SameSite=Strict · `user_sessions.session_hash` (SHA-256) · إبطال من تبويب الجلسات · 401 بعد الخروج |
| CSRF + Rate limit + قفل الحساب + رؤوس أمان | ✅ | POST بلا `X-CSRF-Token` = 403 · 5 محاولات فاشلة = قفل الحساب · الرمز مؤكد على طلب الخروج |
| Break-Glass ≤ 60 دقيقة + مراجعة | ✅ | سبب < 10 أحرف = 422 · طلب 120 دقيقة يُقصّ إلى 60 · الحالة تصبح `REVIEWED` بعد المراجعة |
| TOTP اختياري (RFC 6238) | ✅ | إعداد/تحقق SHA-1 · 6 أرقام · 30 ثانية · ±1 نافذة · نافذة «أمان الحساب» تعرض الحالة وتتيح الإعداد |
| تدقيق بسلسلة Hash + Insert-only | ✅ | `GET /admin/audit/verify` = «السلسلة سليمة» · Triggers تمنع UPDATE/DELETE · before/after لكل تغيير |
| إعدادات SYS + سجل تغييراتها | ✅ | 17 مفتاحًا في 3 مجموعات · التعديل يُسجَّل `SETTING_UPDATED` مع before/after · القيم الحساسة تُقنَّع |
| واجهة إدارة (6 تبويبات) + RTL/LTR | ✅ | مستخدمون · أدوار · تدقيق · جلسات · وصول طارئ · إعدادات · تبديل عربي RTL ↔ إنجليزي LTR |

**تحقق وقت التشغيل (2026-11-12):**
- `GET /api/v1/health` = 200 (`database=UP`، `disk=UP`)؛ `/login` و`/app/dashboard` = 200.
- دخول `admin` ثم `/app/admin`: التبويبات الستة تعمل؛ 18 دورًا، 91 صلاحية، 17 إعدادًا؛ بلا أخطاء console.
- سلسلة التدقيق: `valid=true` (35 سجلًا وقت آخر فحص) وعرض before/after سليم.
- خروج 204 ← `GET /api/v1/auth/me` = 401؛ طلب الخروج حمل `x-csrf-token`.
- فحص حقن على معاملات البحث (`q`/`action`/`entityType`) = 200 بلا تنفيذ أو تسريب؛ `SH_SESSION` = HttpOnly + SameSite=Strict + تدوير؛ لا رمز مصادقة في `localStorage`.
- NURSE: لا رابط إدارة، و`/app/admin` يعرض بطاقة رفض الوصول.

**ملاحظات تشغيل مثبتة عمليًا (المرحلتان 1–2):**
- `smart-hospital-ear/src/conf/application.xml` يجب أن يستخدم `version="10"` (قيمة ثابتة في مخطط Jakarta EE 10).
- موارد JAX-RS تحتاج `WEB-INF/beans.xml` مع `@RequestScoped` + `@Inject`؛ بدونه Jersey ينشئ المورد بنفسه ولا يُنفّذ حقن EJB.
- `/api/*` يُرجع JSON دائمًا (404 مغلّف)، بينما `/app/*` و`/login` يُخدمان عبر `SpaFallbackFilter` بـ `index.html` وحالة 200.
- خصائص Ant غير قابلة للتغيير وتُورَّث عبر `<ant>`؛ لذلك يستخدم EAR أسماء `ear.build.dir`/`ear.dist.dir` مع `inheritall="false"`.
- جلسات GlassFish (HTTP session) قد تبقى بعد إعادة النشر بـ`--force`؛ لتبديل المستخدم يجب تسجيل الخروج صراحةً.
- `pattern` في HTML أصبح يُقيَّم بمحرّك `v`؛ الشرطة داخل صنف المحارف يجب أن تكون مهرّبة (`[a-z0-9._\-]{3,80}`).

### 4.3 المرحلة 3 — المرضى والمواعيد والزيارة والسجل الطبي ✅

| المخرج | الحالة | الدليل |
|---|---|---|
| مخطط المرضى/EMPI + المواعيد + الزيارة | ✅ | `V004__create_patients_scheduling_emr.sql`: مرضى، معرّفات، جهات اتصال، حساسيات، حالات مزمنة، دمج، جداول أطباء، مواعيد، طابور، قائمة انتظار، زيارات، علامات حيوية، تشخيصات، ملاحظات ونسخها، طلبات، وصفات وبنودها، خدمات منفذة، سجلات وصول |
| بيانات مرجعية للمرحلة | ✅ | `S003__phase3_defaults.sql`: 5 صلاحيات SCH، ربط أدوار، عيادتان، 5 خدمات وأسعارها، 5 أدوية، 7 إعدادات — DB: 96 صلاحية · 361 ربطًا |
| خدمة المرضى (FR-PAT-001..012) | ✅ | تسجيل + رقم طبي + منع تكرار (409 مع تجاوز صريح) + بحث + بطاقة + دمج + حساسيات/حالات + Timeline + سجل وصول لكل قراءة |
| خدمة المواعيد (FR-SCH-001..010) | ✅ | توليد فترات من الجداول، حجز بمنع تعارض، تعديل/إلغاء، Check-In وتذكرة طابور، Waitlist، No-Show، كشف يومي، استثناءات |
| خدمة الزيارة/EMR (FR-EMR-001..012) | ✅ | فتح زيارة، علامات حيوية، تشخيصات، ملاحظات Draft → Sign → Amend، طلبات، وصفات (إنشاء/إصدار/إلغاء)، إغلاق/إلغاء، حمولة طباعة كاملة |
| Charge Capture (ADR-016) | ✅ | `service_executions` عند إغلاق الزيارة (رسم استشارة تلقائي فقط عند عدم وجود رسوم) وعبر `POST /encounters/{id}/charges` بصلاحية `EMR:UPDATE` |
| واجهة المرضى | ✅ | `/app/patients`: بحث، ترقيم صفحات، تسجيل، تجاوز تكرار، منتقي مريض؛ `/app/patients/:id`: بيانات، معرّفات، جهات اتصال، حساسيات، حالات، Timeline، وصفات، تعديل/دمج |
| واجهة المواعيد | ✅ | `/app/appointments`: تبويبات اليوم/الطابور/قائمة الانتظار/جداول الأطباء، حجز وتعديل وإلغاء وCheck-In |
| واجهة الزيارة | ✅ | `/app/clinical/:id`: تبويبات نظرة عامة/علامات/تشخيصات/ملاحظات/طلبات/وصفات/رسوم/طباعة، مع قفل كامل للقراءة بعد الإغلاق |
| الطباعة الأولية | ✅ | حمولة `GET /encounters/{id}/print` + قالب طباعة عربي مع `@media print` (مريض، علامات، تشخيصات، وصفة، ملاحظة، رسوم) |
| بوابة G3 | ✅ | 44/44 فحصًا خلفيًا · UAT-01 = 0.31 ثانية · بحث 100k P95 = 255ms · منع تعديل ملاحظة موقّعة · منع تعارض موعدين |

**تحقق وقت التشغيل (2026-11-13):**
- `GET /api/v1/health` = 200 (`database=UP`، `disk=UP`)؛ `/login` وكل مسارات `/app/*` تخدم حزمة Phase 3 (`v0.3.0-phase3`).
- جولة واجهة كاملة: تسجيل مريض `P-2026-000004` ← حجز ← Check-In (تذكرة #3) ← زيارة ← علامات حيوية (37.2/88/18/120/80/98/72/175/3) ← تشخيص `J06.9` ← ملاحظة موقّعة ← وصفة `AMOX-500` صادرة ← رسم `LAB-CBC` ← إغلاق ← طباعة.
- الزيارة المغلقة للقراءة فقط: لا نموذج رسوم ولا أزرار إنشاء، وتغيير حالة الطلب/إصدار/إلغاء الوصفة مخفي.
- بطاقة المريض: Timeline يعرض الموعد والزيارة والوصفة؛ التبديل عربي RTL ↔ إنجليزي LTR يعمل ويترجم التنقل.
- إصلاحات مثبتة: توليد الفترات يحسب بإزاحة الدقائق (كان يتجاوز منتصف الليل ويسبب حلقة لا نهائية)، ونموذج الجدول يعرض تسميات الوقت بالعربية.
- اختبار الانحدار: `test-phase3.ps1` = **44/44 PASS**؛ سلسلة التدقيق `valid=true`.
- الأداء: 100,000 مريض اصطناعي، 50 استعلامًا بسبع صيغ → متوسط 233ms · P50 231ms · **P95 255ms** · أقصى 263ms (الحد 800ms)؛ حُذفت الصفوف بعد القياس.

### 4.4 المرحلة 4 — الطوارئ والتنويم والتمريض والمختبر والصيدلية ✅

| المخرج | الحالة | الدليل |
|---|---|---|
| المخطط والبيانات المرجعية | ✅ | `V005__create_er_adt_nur_lab_phr.sql` + `V006` + `S004__phase4_defaults.sql` — 76 جدولًا · `schema_version` = V006 |
| الخدمات والواجهات | ✅ | طوارئ وفرز · تنويم ولوحة أسرّة وADT · تمريض وEWS وBCMA · مختبر وتقرير عربي RTL · صيدلية FEFO وإرجاع — مسارات `/app/er`، `/app/admission`، `/app/nursing`، `/app/laboratory`، `/app/pharmacy` |
| بوابة G4 | ✅ | **89/89 PASS · 0 FAIL · 4 SKIP موثقة** · التزامن C1/C2/C5/C6/C7 · تقرير مختبر عربي · لا مخزون سالب/سرير مزدوج/اعتماد مزدوج |

### 4.5 المرحلة 5 — الفوترة والمخزون ✅

| المخرج | الحالة | الدليل |
|---|---|---|
| المخطط والبيانات المرجعية | ✅ | `V007__create_billing_inventory.sql` · `V008__fix_invoice_balance.sql` · `V009__widen_number_sequence_period.sql` · `S005__phase5_defaults.sql` — 100 جدولًا · 114 صلاحية · 494 ربط دور · `schema_version` = V009 |
| الفوترة (FR-BIL-001..010) | ✅ | Charge Capture من `service_executions` · تثبيت السعر لحظة التنفيذ · خصم بحد وسبب · دفع/إيصال · Idempotency · استرداد بموافقة · إشعار دائن · رصيد مولّد غير سالب · جلسات صندوق وإقفال · تقرير تحصيل يومي |
| المخزون (FR-INV-001..010) | ✅ | كتالوج ومستودعات وموردون · استلام بدفعات وصلاحية · صرف/تحويل FEFO بمستند · إتلاف · جرد وموافقة · حد إعادة الطلب · دورة شراء PR→PO→GRN · تقييم واستهلاك |
| الواجهات | ✅ | `/app/billing` (فواتير/صندوق/أسعار/تقرير) · `/app/inventory` (أصناف/مستودعات/مخزون/حركات/جرد/مشتريات/تنبيهات/تقارير) |
| بوابة G5 | ✅ | **39/39 PASS · 0 FAIL · 0 SKIP** · C3/C4/C8/C9 (3 تكرارات) · معادلة رصيد 100% · لا مخزون سالب · انحدار G4 89/89 |

**تحقق وقت التشغيل (2026-11-17):**
- `GET /api/v1/health` = 200؛ `/login` وكل مسارات `/app/*` تخدم حزمة Phase 5 (`v0.5.0-phase5`).
- جولة واجهة: فواتير (`INV-…`) ← صندوق (`CSH-20261117-0001..0006` مغلقة بفرق 0.00) ← قائمة أسعار (`CONSULT-GEN 20.00 JOD` · `PROC-ECG 92.00 JOD`) ← تقرير تحصيل؛ وأصناف ← تنبيهات (0 نقص · 3 قريبة · تحذير 60 يومًا) ← حركات، مع تبديل عربي RTL ↔ إنجليزي LTR وصفر أخطاء كونسول.
- إصلاحات موثّقة: توسيع `number_sequences.period` إلى `CHAR(8)`، `Locale.ROOT` في أرقام المستندات، رمي خطأ بدل تسلسل 0، إضافة `item_id`/`batch_id` لبنود الجرد، وقفل `lockBatchByNumber` لمنع تصادم أرقام الدفعات المتزامنة.
- التفصيل الكامل: `docs/testing/04-g4-evidence.md` و`docs/testing/05-g5-evidence.md`.

---

## 5. مبادئ غير قابلة للتفاوض

1. لا أسرار في Git، ولا بيانات مرضى حقيقية.
2. كل عملية حساسة داخل معاملة، ومع Idempotency حيث يلزم.
3. كل تغيير على بيانات سريرية/مالية قابل للتدقيق.
4. لا تنفيذ قرار آلي دون اعتماد بشري.
5. العربية RTL هي الافتراضي، والإنجليزية كاملة.

---

## 6. المراجع

- `docs/README.md` — فهرس الوثائق.
- `docs/architecture/02-backend-architecture.md` — الحزم والطبقات.
- `docs/database/04-migrations-seed.md` — الترحيلات.
- `docs/roadmap/01-implementation-roadmap.md` — المراحل والبوابات.
