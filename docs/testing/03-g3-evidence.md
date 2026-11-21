# 03 — G3 Evidence (Phase 3)

**دليل اجتياز بوابة G3: المرضى + المواعيد + الزيارة/EMR + Charge Capture**

| Field | Value |
|---|---|
| Document ID | TST-G3-001 |
| Date | 2026-11-13 |
| Status | **G3 Passed** — DEV environment, synthetic data only |
| Scope | TC-017..TC-050 (PAT / SCH / EMR) + UAT-01 + NFR performance |
| Environment | Windows · JDK 17 · GlassFish 7 Full (`domain1`) · MySQL 8.0.38 (port 3307) · React 19 + Vite |
| Build | `v0.3.0-phase3` · `ant -f smart-hospital-ear/build.xml full` |
| Related | `roadmap/01-implementation-roadmap.md` §Phase 3 · `testing/02-acceptance-criteria.md` |

> **حدود الدليل:** هذه نتائج بيئة تطوير (DEV) ببيانات اصطناعية بالكامل. لا تُعدّ اعتمادًا سريريًا أو قانونيًا، ولا بديلًا عن UAT الرسمي مع المستخدمين (G10).

---

## 1. نتيجة البوابة

| معيار G3 | النتيجة | الدليل |
|---|---|---|
| TC-017..TC-050 ناجحة | ✅ **44/44 PASS** | §2 |
| UAT-01 (تسجيل → موعد → زيارة → وصفة) ≤ 5 دقائق | ✅ **0.31 ثانية** | §3 |
| بحث 100k مريض P95 ≤ 800ms | ✅ **P95 = 255.2ms** | §4 |
| لا ملاحظة قابلة للتعديل بعد التوقيع | ✅ 409 `NOTE_SIGNED` | §2 (signed note immutable) |
| لا موعدان فعّالان لنفس الطبيب/الفترة | ✅ 409 عند التعارض | §2 (appointment conflict rejected) |

---

## 2. الانحدار الخلفي — 44/44

**الأمر:** `powershell -NoProfile -ExecutionPolicy Bypass -File test-phase3.ps1`
**السجل:** `phase3-rerun.log` · **النتيجة:** `PASS=44 FAIL=0`

| المجموعة | الفحوص |
|---|---|
| الدخول والمرجعية | login · clinics list · providers list |
| المرضى | patient create · patient duplicate rejected · duplicate check · patient search · patient chart · patient timeline · allergy add |
| المواعيد | schedule create · slots list · appointment create · appointment conflict rejected · appointment day list · check-in · queue list · queue call · queue serve · waitlist add |
| الزيارة | encounter create · encounter workspace · vitals record · vitals out of range rejected · diagnosis add |
| الملاحظات | note create · note draft update · note sign · signed note immutable · note amend · note versions |
| الطلبات والوصفات | lab order create · order status update · prescription create · prescription issue · prescription read |
| الرسوم والإغلاق | charge capture · encounter print payload · encounter close · closed encounter rejects writes · second encounter after close · encounter cancel |
| السجل والتدقيق | patient prescription history · audit chain verify |

**نقاط سلوكية مثبتة:**
- تكرار الرقم الوطني يُرفض بـ **409**، و`POST /patients/check-duplicates` يعيد نتيجة التحقق قبل الحفظ.
- حجز نفس فترة الطبيب مرة أخرى يُرفض بـ **409** (قيد `(provider_id, slot_start, active_flag)`).
- العلامات الحيوية خارج النطاق تُرفض بـ **422**.
- تعديل ملاحظة موقّعة يُرفض بـ **409 `NOTE_SIGNED`**؛ التصحيح بنسخة `AMENDED` مع سبب.
- الزيارة المغلقة ترفض كل الكتابات بـ **409 `ENCOUNTER_CLOSED`**.
- سلسلة التدقيق: `GET /admin/audit/verify` → `valid=true`.

---

## 3. UAT-01 — التدفق الكامل

**المسار:** تسجيل مريض ← حجز فترة ← Check-In ← فتح زيارة ← إنشاء وصفة ← إصدار الوصفة.

| الخطوة | الناتج |
|---|---|
| تسجيل المريض | رقم طبي جديد + `publicId` |
| حجز الموعد | فترة اليوم `18:20` |
| Check-In | حالة `CHECKED_IN` + تذكرة طابور |
| فتح الزيارة | `encounter` بحالة `OPEN` |
| إنشاء + إصدار الوصفة | حالة `ISSUED` |

**الزمن الكلي: 0.31 ثانية** (الحد 5 دقائق). أُغلقت الزيارة بعد القياس لتنظيف البيانات.

---

## 4. أداء البحث — 100,000 مريض

**الطريقة:** إدراج 100,000 مريض اصطناعي (`PERF%`) في `patients`، ثم 50 استعلامًا متتاليًا بسبع صيغ بحث (اسم، اسم مُطبَّع، رقم طبي، هاتف، رقم وطني، أرقام جزئية)، مع استبعاد أول استعلامات الإحماء.

| المقياس | القيمة |
|---|---|
| عدد الاستعلامات | 50 |
| المتوسط | 233.1ms |
| P50 | 230.8ms |
| **P95** | **255.2ms** |
| الأقصى | 262.5ms |
| الحد المطلوب | 800ms |

**ملاحظة:** الصفوف الاصطناعية حُذفت بعد القياس، وعاد عدد المرضى إلى حالته الطبيعية. القياس على جهاز تطوير واحد، وليس على عتاد الإنتاج.

---

## 5. جولة الواجهة (UI Walkthrough)

| السيناريو | النتيجة |
|---|---|
| تسجيل مريض `P-2026-000004` | ✅ |
| حجز موعد + Check-In (تذكرة #3) | ✅ |
| فتح زيارة + علامات حيوية (37.2/88/18/120/80/98/72/175/3) | ✅ |
| تشخيص `J06.9` + ملاحظة موقّعة | ✅ |
| وصفة `AMOX-500` صادرة | ✅ |
| رسم `LAB-CBC` + إغلاق الزيارة | ✅ |
| حمولة الطباعة (مريض/علامات/تشخيص/وصفة/ملاحظة/رسوم) | ✅ |
| الزيارة المغلقة للقراءة فقط (لا نموذج رسوم/أزرار إنشاء) | ✅ |
| بطاقة المريض Timeline (موعد/زيارة/وصفة) | ✅ |
| التبديل عربي RTL ↔ إنجليزي LTR | ✅ |
| نموذج الجدول يعرض تسميات الوقت بالعربية | ✅ |

**إصلاح مثبت:** توليد الفترات أصبح يحسب بإزاحة الدقائق من بداية اليوم بدل تقديم `LocalTime` (الذي يلتف عند منتصف الليل ويسبب حلقة لا نهائية/OOM عند جدول ينتهي 23:59).

---

## 6. إعادة إنتاج الدليل

```powershell
# 1) تأكد من MySQL 3307 وGlassFish
Invoke-WebRequest http://localhost:8080/api/v1/health -UseBasicParsing

# 2) أعد تشغيل مجموعة الانحدار
powershell -NoProfile -ExecutionPolicy Bypass -File test-phase3.ps1

# 3) بناء ونشر
ant -f smart-hospital-ear/build.xml full
asadmin --user admin --passwordfile C:\glassfish7\userpass.txt deploy --force=true --name=smart-hospital <ear>
```

**تحذير:** لا تُشغّل سكربت الانحدار على قاعدة بيانات إنتاج؛ فهو ينشئ مرضى ومواعيد وزيارات ووصفات تجريبية.
