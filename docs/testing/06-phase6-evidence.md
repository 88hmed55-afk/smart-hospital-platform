# 06 — Phase 6 Evidence (Notifications · Outbox · SSE)

**دليل التحقق الجزئي للمرحلة 6: الإشعارات، طابور الأحداث، البث الحي، والمراقبة**

| Field | Value |
|---|---|
| Document ID | TST-P6-001 |
| Date | 2026-11-17 |
| Status | **Phase 6 verified — G6 claimed** (SSE، إغلاق البث، الأولوية والقوالب مكتملة) |
| Scope | TC-134..TC-139 + C10 + TC-176 · Outbox hardening · JMS Queue/MDB/DLQ · أولوية المطالبة · قوالب الإشعارات · SSE · تفضيلات المستخدم · مراقبة الطابور |
| Environment | Windows · JDK 17 · GlassFish 7 Full (`domain1`) · MySQL 8.0.38 (port 3307) · React 19 + Vite · Ant 1.10.18 |
| Build | `v0.6.0-phase6` · `ant -f smart-hospital-ear/build.xml full` |
| Migrations | `V001..V012` مطبّقة · **102 جدولًا** · `schema_version` = V012 |
| Related | `architecture/07-notification-architecture.md` · `architecture/04-api-contracts.md` §6.14/§6.14.1/§7.4 · `testing/06-phase6-run.log` · `testing/06-phase6-load.log` · `roadmap/01-implementation-roadmap.md` §Phase 6 |

> **حدود الدليل:** نتائج بيئة تطوير (DEV) ببيانات اصطناعية. لا تُعدّ اعتمادًا سريريًا أو قانونيًا، ولا تُغني عن UAT (G10). لم يُنشر Nginx فعليًا في DEV؛ أُثبت السلوك عبر ترويسات الوكيل التي يقرأها Nginx (`X-Accel-Buffering`/`Cache-Control`) وعبر ملف إعداد مرجعي جاهز (`deploy/nginx/smart-hospital.conf`)، ولم يُختبر عبر شبكة WAN.

---

## 1. حالة بوابة G6

| معيار G6 | الحالة | الدليل |
|---|---|---|
| TC-134..TC-139 + TC-176 ناجحة | ✅ **20/20 PASS** | §2، §3 |
| C10 — لا إشعار مكرر لنفس المستخدم/الحدث | ✅ **1/1 PASS** (8 أحداث متوازية → 8 صفوف، صف واحد لكل مفتاح) | §3 |
| TC-176 — فشل JMS بعد Commit | ✅ **2/2 PASS** — فشل الإرسال يُبقي الحدث `PENDING` بلا فقد، ورسالة غير قابلة للتسليم تُحجز في `jms/NotificationDLQ` | §12 |
| 0 حدث مفقود في 1,000 حدث اختباري | ✅ **1000/1000** بلا فقد وبلا تكرار (198 ثانية، 5.05 حدث/ث) | §3.1، §12.4 |
| SSE عبر Nginx + Fallback عند القطع | ✅ ترويسات آمنة للوكيل (`Cache-Control: no-cache, no-transform` + `X-Accel-Buffering: no`) وإعداد Nginx مرجعي؛ الواجهة تتحول إلى Polling عند القطع وتعود إلى `live` | §4، §13 |
| لا إشعار مكرر لنفس المستخدم/الحدث | ✅ Dedupe key + `UNIQUE` + C10 + حِمل 1,000 | §3، §3.1 |
| إغلاق البث عند إبطال الجلسة | ✅ القناة تُغلق عند نبضة الصيانة التالية بعد `DELETE /admin/sessions/{id}` (مُقاس: 8–9 ثوانٍ) | §3، §13 |
| أولوية التسليم والقوالب | ✅ مطالبة `ORDER BY priority DESC, id` (حرج يقفز 36 حدثًا) · 7 قوالب قابلة للتهيئة مع استبدال `{placeholders}` | §3، §13 |

**الخلاصة:** مسار Outbox → Dispatcher → JMS Queue → MDB → Inbox → SSE مكتمل ومُثبت وظيفيًا وتحت حِمل 1,000 حدث، مع DLQ للأحداث غير القابلة للتسليم، وأولوية مطالبة، وقوالب موحّدة، وبث يُغلق نفسه عند إبطال الجلسة. **G6 مجتاز في بيئة DEV** مع حدود Nginx/WAN الموضحة أعلاه.

---

## 2. نتيجة المجموعة

**الأمر:** `powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-phase6.ps1`
**السجل:** `testing/06-phase6-run.log` · **النتيجة:** `20 PASS / 0 FAIL / 0 SKIP`

| المجموعة | PASS | الفحوص |
|---|---:|---|
| TC-134 — Claim & dispatch | 2 | الحدث يُطالب به ويُسلّم → `SENT` + صف Inbox · بيانات الـlease (`claimedAt`/`processedAt`/`attempts`) ظاهرة في واجهة العمليات |
| TC-135 — Dedupe | 1 | حدثان بنفس `dedupeKey` → حدث واحد `SENT` وصف Inbox واحد |
| TC-136 — Poison event | 2 | حمولة عقدية غير صحيحة → `DEAD` مع `lastError` · `POST /admin/outbox/events/{id}/retry` يعيد الحدث إلى `PENDING` |
| TC-137 — التفضيلات | 3 | تعطيل فئة يمنع التسليم · `min_severity` يمنع الإشعارات الأقل خطورة · `GET /notifications/preferences` يعيد كل الفئات |
| TC-138 — SSE | 1 | `GET /notifications/stream` = 200 · `text/event-stream` · إطار `ready` |
| TC-139 — المراقبة | 2 | `stats` يعيد `counts` لكل حالة + `backlog.oldestAgeSeconds` + `deadSamples` + `pushConnections` · `status` غير معروف → 400 `OUTBOX_STATUS_INVALID` |
| C10 — التزامن | 1 | 8 أحداث متوازية → 8 صفوف Inbox بالضبط (صف واحد لكل `dedupeKey`) |
| TC-176 — JMS/MDB/DLQ | 2 | فشل إرسال JMS بعد الـcommit يُبقي الحدث `PENDING` ثم يُسلّم بعد التعافي · رسالة غير قابلة للتسليم تُحجز في `jms/NotificationDLQ` ويُعاد تشغيلها إلى `SENT` |
| TC-134 (أولوية المطالبة) | 1 | 60 حدث `INFO` متراكم + حدث `CRITICAL` واحد: الحرج يُسلّم أولًا وبقيت **36** حدثًا `INFO` في `PENDING` |
| TC-135 (القوالب) | 2 | قالب `LAB_CRITICAL` يستبدل `{test}`/`{patient}` ويورّث `CRITICAL` + أولوية 100 · كتالوج 7 قوالب عبر API و404 لقالب غير معروف |
| TC-139 (ترتيب الـInbox) | 1 | إدراج `INFO` أخيرًا لا يضعه أولًا: الترتيب `CRITICAL(0) < WARNING(4) < INFO(5)` |
| TC-137 (دورة حياة البث) | 2 | ترويسات `no-transform`/`X-Accel-Buffering: no` · البث يُغلق بعد إبطال الجلسة (8–9 ثوانٍ) |

---

## 3. أدلة السلوك

| الفحص | النتيجة المرصودة |
|---|---|
| المطالبة الذرية (V011) | `UPDATE outbox_events … ORDER BY id LIMIT n` واحد يختم الصفوف بـ`claim_token` (ULID). لا نافذة قراءة-ثم-كتابة بين عاملين، والمعاملة قصيرة جدًا |
| استعادة الـlease | `recoverStale(120s)` يعيد أي `PROCESSING` قديم إلى `PENDING` ويصفّر `claimed_at`/`claim_token` — لا حدث يبقى عالقًا بعد سقوط عامل |
| Backoff وDEAD | `attempts` تصاعدي، `next_attempt_at = NOW + min(300, 5·n²)` ثانية، وعند بلوغ `outbox.max.attempts` (افتراضي 8) → `DEAD` مع `last_error` |
| إعادة المحاولة اليدوية | `retry` يعيد `DEAD/FAILED` فقط إلى `PENDING` مع تصفير العدّاد والخطأ |
| منع التكرار | `dedupeKey` + `UNIQUE` على صف الإشعار؛ إعادة تسليم نفس الحدث لا تُنشئ صفًا ثانيًا (TC-135)، و8 أحداث متوازية تُنتج 8 صفوف لا أكثر (C10) |
| مسار JMS | الـDispatcher يتحقق من عقد الحمولة ثم ينشر إلى `jms/NotificationQueue` **داخل معاملة الحاوية** ويسجّل `SENT` بعده؛ `NotificationMdb` يستهلك ويستدعي `NotificationDelivery` (`REQUIRES_NEW`). فشل التسليم يزيد `attempts` على صف الـoutbox، وعند 3 محاولات تُحجز الرسالة في `jms/NotificationDLQ` ويصبح الحدث `DEAD` قابلًا لإعادة التشغيل |
| التفضيلات | `notification_preferences(user_id, category, enabled, min_severity)` — تُطبّق قبل إدراج صف Inbox؛ الفئة تُشتق من بادئة النوع (`LAB_*` → `LAB` …) |
| البث الحي | `NotificationPushRegistry` يوزّع على **خيط كاتب واحد** عبر طابور محدود (4096، سياسة إسقاط). كتابة SSE لا تحدث داخل معاملة الـcommit، والمستمع المتعطل يُغلق بعد 30 ثانية |
| المراقبة | `/admin/outbox/stats`: `counts` لكل حالة، `backlog.total/oldestAgeSeconds`، `backlog.criticalTotal`، `dead`، `deadSamples`، `pushConnections` · `/admin/outbox/events?status=…&limit=…` بقائمة بيضاء للحالات |
| أولوية المطالبة (V012) | `priority` تُشتق من خطورة الحمولة (`CRITICAL=100`, `WARNING=50`, `INFO=0`)، والمطالبة `UPDATE … ORDER BY priority DESC, id LIMIT n` — FIFO داخل كل نطاق أولوية. إثبات: 60 `INFO` + 1 `CRITICAL` → الحرج أُرسل أولًا وبقي 36 `INFO` |
| القوالب | `notification_templates` (7 قوالب عربية/إنجليزية) تُحلّ عبر `templateCode` + `params`، وتُستبدل `{placeholders}`؛ الخطورة الافتراضية للقالب تُستخدم إن لم تُحدَّد صراحة. تُدار عبر `GET/PUT /admin/outbox/templates` |
| ترتيب الـInbox | `ORDER BY priority DESC, created_at DESC, id DESC` — الحدث الحرج يتصدّر حتى لو وصل متأخرًا (TC-139) |
| دورة حياة البث | ترويسات `Cache-Control: no-cache, no-transform` + `X-Accel-Buffering: no` تمنع تخزين الوكيل للإطارات؛ وكل نبضة 20 ثانية تتحقق من `user_sessions.ended_at` وتغلق القناة بعد تسجيل الخروج أو الإبطال الإداري |

### 3.1 حِمل 1,000 حدث — صفر فقد وصفر تكرار

**الأمر:** `powershell -NoProfile -ExecutionPolicy Bypass -File tools\load-outbox.ps1`
**السجل:** `testing/06-phase6-load.log`

| القياس | النتيجة |
|---|---|
| أحداث أُدخلت في الطابور | 1,000 (دفعة `INSERT` واحدة) |
| `SENT` | **1,000 / 1,000** |
| `DEAD` / `PENDING` / `PROCESSING` | 0 / 0 / 0 |
| أحداث أُعيدت محاولتها (`attempts <> 1`) | **0** |
| صفوف Inbox | **1,000** بمفاتيح `dedupeKey` فريدة (1,000 distinct) |
| مطالبات متبقية (`claim_token`/`claimed_at`) | 0 |
| الزمن الكلي | **204 ثانية** (4.9 حدث/ث) |

**قراءة النتيجة:** لا فقد ولا تكرار ولا حدث عالق. المعدل محدود بتصميم المؤقّت (25 حدثًا كل 5 ثوانٍ ≈ 5/ث) لا بقاعدة البيانات — الدفعة الواحدة تُسلَّم في أجزاء من الثانية. أُضيفت هذه الملاحظة كبند متابعة في §6.

> **بعد إضافة الـMDB:** أُعيد الحِمل نفسه عبر مسار JMS الكامل — **1000/1000 في 198 ثانية (5.05 حدث/ث)**، بلا `DEAD` وبلا إعادة تسليم، وسجلّه في `testing/06-phase6-load-jms.log` (التفصيل §12.4).

---

## 4. عيوب اكتُشفت وأُصلحت أثناء التحقق

| # | العرض | السبب الجذري | الإصلاح | التحقق |
|---|---|---|---|---|
| 1 | الطابور متوقف تمامًا: أحداث `PENDING` لا تُسلّم، والخادم يقيّد 50 ثانية ثم `Lock wait timeout` | **نشر قديم ثانٍ** (`smart-hospital-api` على `/smart-hospital-api`) كان يشغّل Poller ثانيًا على نفس القاعدة | `undeploy smart-hospital-api` — نشر واحد فقط مسموح | الحدث التجريبي انتقل إلى `SENT` بعد الإزالة |
| 2 | `claimBatch` ينتظر قفلًا يملكه خيطه نفسه | GlassFish **يتجاهل `NOT_SUPPORTED`** على callback المؤقّت، فيعمل الـpoller داخل معاملة حاوية؛ `recoverStale` كان ينضم إليها ويترك gap locks مفتوحة أثناء `claimBatch` (`REQUIRES_NEW`) | كل دوال `OutboxDispatcher` أصبحت `REQUIRES_NEW` بما فيها `recoverStale` | لا `Lock wait timeout` بعد النشر؛ الجدول يتصفّر كل دورة |
| 3 | توقّف المؤقّت نهائيًا بعد خطأ واحد (`EJB5119`) | استثناء نظامي في callback يلغي مؤقّتًا غير دائم | `poll()` يغلّف `drain()` في `catch (Throwable)` ويسجّل WARNING | الطابور استمر بعد أخطاء متتالية |
| 4 | `EJBTransactionRolledbackException` عند `markFailed` | `markFailed` كان يبتلع الاستثناء داخل معاملته فتصبح rollback-only | إزالة الابتلاع؛ الحاوية تتراجع، والـpoller يحتوي الفشل ويترك استعادة الـlease تعيد الجدولة | TC-136 |
| 5 | الـcommit ينتظر شبكة بطيئة (اتصال SSE نصف مفتوح) | كتابة SSE كانت متزامنة داخل `afterCompletion` فتُبقي أقفال الصفوف مفتوحة | `NotificationPushRegistry` غير متزامن (خيط واحد + طابور محدود + إغلاق المستمع المتعطل) | `pushConnections=1` وبث حي بلا تأثير على الطابور |
| 6 | `/notifications/stream` يعيد 500 | مرشّحات الويب غير مهيّأة للـasync | `<async-supported>true</async-supported>` + `ASYNC` dispatcher في `web.xml` | TC-138 |
| 7 | TC-139 يفشل بعد TC-136 | TC-136 يعيد إحياء الحدث الميت، فلا يبقى `DEAD` للإحصاء | TC-139 ينشئ حدثه الميت الخاص | 12/12 |
| 8 | MDB لا يصل إلى حد المحاولات أبدًا: الرسالة تُوجَّه إلى `mq.sys.dmq` قبل منطق الـDLQ | OpenMQ لا يعرض `JMSXDeliveryCount` بشكل موثوق هنا | عدّ المحاولات على صف الـoutbox (`noteQueueFailure` في معاملة `REQUIRES_NEW`) بدل خاصية الرسالة؛ الحجز يحدث عند المحاولة الثالثة | TC-176 (DLQ) |
| 9 | إعادة تشغيل حدث `DEAD` ترجع 400 `OUTBOX_ID_INVALID` | الاختبار مرّر `event_id` (ULID) بينما المسار يتوقع المعرّف الرقمي | ترجمة المعرّف قبل استدعاء `POST /admin/outbox/{id}/retry` | TC-176 (retry) |
| 10 | تعديلان متوازيان لملاحظة موقّعة → 500 بدل 409 | إدراج نسخة الملاحظة **قبل** قفل صف الأب، فتقاطعت أقفال الفجوة على `UNIQUE(note_id, version_no)` | `lockNote` (`SELECT … FOR UPDATE`) قبل الإدراج في مساري التعديل والتوقيع | TC-171 → 89/89 |

> **درس تشغيلي:** لا يجوز أن يعمل أكثر من نشر واحد على نفس القاعدة. أي `list-applications` يظهر فيه تطبيق ثانٍ = Poller ثانٍ.

---

## 5. جولة الواجهة (Live E2E)

| السيناريو | النتيجة |
|---|---|
| تسجيل الدخول ثم `/app/dashboard` | ✅ تحميل كامل، `pushConnections=1` |
| فتح جرس الإشعارات | ✅ مؤشر «مباشر» (`data-state=live`) |
| إدخال حدث `LAB_CRITICAL` في الطابور مباشرة | ✅ ظهر الشعار (badge=1) **بدون polling**، والعنصر ظهر في الـInbox بنص عربي ووقت عربي |
| «تعليم كمقروء» | ✅ اختفى الشعار و`unread-count = 0` |
| تفضيلات الإشعارات | ✅ تُعرض كل الفئات مع `enabled` و`min_severity` وتُحفظ عبر `PUT /notifications/preferences` |
| أخطاء الكونسول | **0** |

> لقطات الشاشة غير متاحة (النافذة غير مرئية)؛ اعتُمد على فحص DOM المحسوب وقيم الـAPI.

---

## 6. متبقٍ قبل إعلان G6

> **مكتمل:** TC-176 (JMS/MDB/DLQ §12)، أولوية المطالبة، القوالب، ترتيب الـInbox بالأولوية، ترويسات الوكيل وإعداد Nginx، وإغلاق البث عند إبطال الجلسة (§13).

| البند | الحالة | الملاحظة |
|---|---|---|
| **قوالب وأولوية الإشعارات** | ✅ مكتمل | جدول `notification_templates` + استبدال `{placeholders}` + أولوية في المطالبة والـInbox (TC-135/139) |
| **أولوية المطالبة تحت الازدحام** | ✅ مكتمل | `ORDER BY priority DESC, id`؛ الحرج يقفز 36 حدثًا `INFO` في الاختبار (TC-134) |
| **SSE عبر Nginx + Fallback** | ✅ مكتمل (DEV) | ترويسات آمنة للوكيل + `deploy/nginx/smart-hospital.conf` + Polling تلقائي في الواجهة عند القطع |
| **إبطال الجلسة يغلق البث** | ✅ مكتمل | النبضة التالية تغلق القناة بعد `DELETE /admin/sessions/{id}` (8–9 ثوانٍ) |

**حدود متبقية للـUAT (G10):** النشر خلف Nginx حقيقي، وشبكة WAN، واستمرار الرسائل عبر إعادة تشغيل الوسيط، والتوسع الأفقي (مستهلكون متعددون).

---

## 7. حماية الانحدار

| المجموعة | الأمر | النتيجة |
|---|---|---|
| Phase 5 — الفوترة والمخزون | `tools\test-phase5.ps1` | ✅ **39 PASS / 0 FAIL / 0 SKIP** على بناء JMS النهائي |
| Phase 4 — ER/ADT/NUR/LAB/PHR + الإشعارات | `tools\test-phase4.ps1` | ✅ **89 PASS / 0 FAIL / 4 SKIP** (نفس البنود المؤجلة الموثقة في `04-g4-evidence.md`)، بما فيها فحوص Outbox/Inbox الثلاثة وTC-171 بعد إصلاح القفل |
| Phase 6 | `tools\test-phase6.ps1` | ✅ **20 PASS / 0 FAIL / 0 SKIP** (يشمل TC-176 والأولوية والقوالب وإغلاق البث) |
| Phase 6 — حِمل | `tools\load-outbox.ps1` | ✅ **1000/1000** بلا فقد أو تكرار عبر مسار JMS (السجل: `testing/06-phase6-load-jms.log`) |

> **إصلاح كشفه الانحدار:** في أول تشغيل بعد شريحة JMS ظهر **deadlock نادر** في تعديل ملاحظتين متوازيتين (TC-171) → 500 بدل 409. السبب: إدراج نسخة جديدة قبل قفل صف الملاحظة، فتتقاطع أقفال الفجوة على الفهرس الفريد. الإصلاح: قفل صف الملاحظة (`SELECT … FOR UPDATE`) **قبل** الإدراج في مساري التعديل والتوقيع. أعيد التشغيل: **89/89** و**39/39** (السجل: `testing/06-phase6-regression-p4-p5.log`).

---

## 8. إعادة إنتاج الدليل

```powershell
# 1) البنية التحتية
Invoke-WebRequest http://localhost:8080/api/v1/health -UseBasicParsing
ant -f db/build.xml db-migrate          # V010/V011/V012
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-jms.ps1   # NotificationQueue + NotificationDLQ (idempotent)

# 2) بناء ونشر (نشر واحد فقط!)
ant -f smart-hospital-ear/build.xml full
asadmin --user admin --passwordfile C:\glassfish7\userpass.txt deploy --force=true --name=smart-hospital smart-hospital-ear\dist\smart-hospital.ear
asadmin --user admin --passwordfile C:\glassfish7\userpass.txt list-applications

# 3) المجموعة
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-phase6.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-phase4.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-phase5.ps1

# 4) حِمل 1,000 حدث (ينظّف صفوفه تلقائيًا)
powershell -NoProfile -ExecutionPolicy Bypass -File tools\load-outbox.ps1
```

**ملاحظات:**
- السكربتات أحادية البايت (ASCII) بقصد (PowerShell 5.1 يقرأ `-File` بترميز ANSI).
- المجموعة تضبط `outbox.max.attempts = 1` مؤقتًا لاختبار `DEAD` ثم تعيده إلى 8، وتنظّف كل صفوف `tc6-*` في النهاية.
- اختبارا TC-176 يستخدمان مفتاحَي إعداد **للاختبار فقط** — `outbox.jms.simulate.failure` و`outbox.jms.simulate.delivery-failure` — يُدرجهما الهارنس ويحذفهما في `finally`. غيابهما هو الوضع الطبيعي.
- TC-134 (اختبار الأولوية) يستخدم مفتاح إعداد **للاختبار/الصيانة** — `outbox.dispatch.paused` — لإيقاف المطالبة أثناء بناء الرتل، ثم يحذفه في `finally`. غيابه هو الوضع الطبيعي.
- بعد إعادة تشغيل MySQL نفّذ `asadmin flush-connection-pool smartHospitalPool` أو أعد النشر.
- لا تُشغّل المجموعة على قاعدة إنتاج: تُدخل أحداثًا وإشعارات تجريبية.

---

## 9. تنسيق الواجهة — Design v2 (2026-11-17)

**الهدف:** إزالة تشتت التصميم وتوحيد اللغة البصرية عبر كل الوحدات، دون أي تغيير في منطق العمل أو الصلاحيات.

| المحور | قبل | بعد |
|---|---|---|
| العنوان | عنوان مكرّر في الشريط العلوي وفي الصفحة | الشريط العلوي مسار تنقّل صغير (المجموعة / الوحدة)، والعنوان مرة واحدة في الصفحة |
| التبويبات | حبيبات ملوّنة داخل صندوق مع التفاف لأسطر | تبويبات سفلية بصف واحد، تمرير أفقي، مؤشر نشط بخط 2px |
| البطاقات | ظل + حدود + إطار جدول مزدوج | بطاقات مسطّحة بحد واحد، والجدول بلا إطار داخلي |
| شريط الفلاتر | عناصر متفرقة بأحجام مختلفة | شريط موحّد بخلفية هادئة داخل البطاقة |
| لوحة التشغيل | 4 مؤشرات «—» + ملاحظة تطويرية | شبكة وحدات حسب صلاحيات المستخدم + بطاقة حالة النظام |
| التفاصيل | حشوات ومقاسات مختلفة | `h1` واحد، عنوان فرعي موحّد، والإجراءات دائمًا داخل `.header-actions` |

**التحقق (DOM محسوب، بلا لقطات):** 12 مسارًا — `dashboard, patients, appointments, clinical, er, admission, nursing, laboratory, pharmacy, billing, inventory, analytics, admin`:
- `overflowX = 0` في كل الصفحات.
- التبويبات في صف واحد (`rows = 1`) وقابلة للتمرير أفقيًا عند الحاجة.
- التبويب النشط: لون `rgb(10, 98, 92)` + مؤشر `rgb(13, 125, 116)`.
- بطاقات بلا ظل (`box-shadow: none`)، والجدول داخل البطاقة بلا إطار (`0px`).
- عربي RTL وإنجليزي LTR سليمان، **0 أخطاء كونسول**، ولا مفاتيح ترجمة ظاهرة.

**الملفات:** `frontend/src/styles/design-system.css`، `frontend/src/styles/tokens.css`، `frontend/src/shared/layout/AppShell.tsx`، `frontend/src/modules/dashboard/Dashboard.tsx`، وتوحيد `tab-panel` → `tab-content` في المواعيد والسجل الطبي والإدارة.

---

## 10. تنسيق الواجهة — Design v3 (2026-11-17)

**الهدف:** تعميق التوحيد بعد v2: إزالة الصناديق المتداخلة والظلال والتدرّجات المتبقية، وتوحيد كل صفحة على نمط واحد «عنوان صفحة + بطاقة واحدة تحوي المحتوى».

| المحور | قبل v3 | بعد v3 |
|---|---|---|
| الخلفية | تدرّجات شعاعية ثابتة خلف كل الصفحات | لون مسطّح واحد (`background-image: none`) |
| الأزرار | تدرّج + ظل داخلي + ظل خارجي | لون واحد مسطّح، بلا ظل، ارتفاع 36px |
| رأس البطاقة | عنوان بلا فاصل | فاصل سفلي رقيق، ويُلغى تلقائيًا إذا كان الرأس وحده |
| الفلاتر | بطاقة بحث منفصلة عن بطاقة الجدول | بطاقة واحدة: شريط فلترة بخلفية هادئة + جدول + ترقيم |
| تبويبات الإدارة | `tab-content` بلا سطح موحّد | كل تبويب `section.card` بنفس حشوة وبطاقات بقية النظام |
| إجراءات الصفوف | 20 زرًا مؤطرًا داخل الجدول | إجراءات نصية هادئة تظهر خلفيتها عند المرور فقط |
| القوائم اللوحية | صناديق متداخلة داخل البطاقة | قائمة واحدة بفواصل أفقية (ER، المختبر، التمريض) |
| الخط الزمني والملاحظات | صندوق داخل صندوق داخل بطاقة | صفوف مفصولة بخط رقيق داخل البطاقة نفسها |
| الشبكات الوصفية | لوحة ملوّنة مؤطّرة داخل البطاقة | شبكة مسطّحة بلا إطار أو خلفية داخل البطاقة |
| الرسائل الفارغة | صندوق متقطّع الحدود داخل البطاقة | نص هادئ متمركز بلا إطار |
| رأس ثانٍ داخل بطاقة | يبدو كعنوان بطاقة ثانية | فاصل قسم بعنوان أصغر ولون أهدأ |
| النوافذ المنبثقة | حقول داخل إطار داخل نافذة | مجموعات بخلفية هادئة + فاصل قبل أزرار الحفظ |
| الإعدادات | مجموعات متلاصقة بلا فواصل | فاصل واضح بين كل مجموعة وأخرى |

**التحقق (DOM محسوب، بلا لقطات) — 11 مسارًا:** `dashboard, patients, clinical, er, admission, nursing, laboratory, pharmacy, billing, inventory, admin`:
- `overflowX = 0` و`documentOverflow = 0` في كل الصفحات.
- `body background-image = none`، وظل الزر الأساسي `none`.
- `.card > .meta-grid` و`.card > .empty-inline`: حد `0px` وخلفية شفافة.
- إجراءات صفوف الجدول: خلفية وحدود شفافة (`rgba(0,0,0,0)`).
- تبويبات الإدارة: 6 تبويبات، كل واحد بطاقة واحدة بلا بطاقات متداخلة (`nested = 0`).
- عربي RTL وإنجليزي LTR سليمان، **0 أخطاء كونسول**.

**الانحدار بعد النشر:** `tools\test-phase6.ps1` → **12 PASS / 0 FAIL / 1 SKIP** (كان TC-176 لا يزال SKIP آنذاك؛ صار المجموع **14/14** بعد شريحة JMS — §12، ثم **20/20** بعد الأولوية/القوالب/دورة البث — §13) — السجل في `testing/06-phase6-ui-v3-regression.log`.

**الملفات:** `frontend/src/styles/design-system.css` (الطبقة الأساسية)، `frontend/src/modules/patients/PatientsPage.tsx` و`frontend/src/modules/clinical/EncountersPage.tsx` (دمج بطاقة الفلترة مع الجدول)، و`frontend/src/modules/admin/{Users,Roles,Audit,Sessions,Settings}Tab.tsx` (جذر كل تبويب `section.card`).

---

## 11. تنسيق الواجهة — Design v4 (2026-11-17)

**الهدف:** إكمال توحيد الصفحات المتبقية التي بقيت على نمط «بطاقة فلترة + بطاقة جدول»، وتوحيد ترتيب إجراءات الرأس.

| المحور | قبل v4 | بعد v4 |
|---|---|---|
| المواعيد (4 تبويبات) | بطاقة فلترة منفصلة عن بطاقة القائمة | بطاقة واحدة: شريط فلترة + جدول/حالة فارغة |
| الوصول الطارئ | جدول الطلبات خارج أي بطاقة | بطاقة واحدة تحوي شريط الحالة + الجدول |
| إعدادات النظام | 13 مجموعة متراصة بلا تنقّل (5,000px) | شريط فلترة بالفئة + عدّاد، وعرض مجموعة واحدة عند الاختيار (368px) |
| حالات التحميل/الخطأ/الفراغ في الإعدادات | خارج البطاقة | داخل البطاقة نفسها |
| إجراءات الرأس | الإجراء الأساسي في الوسط/البداية | الأساسي دائمًا الأخير، والإجراء الخطر بإطار هادئ لا زر أحمر صريح |
| سهم الرجوع | يشير لليسار في الواجهة العربية | ينعكس تلقائيًا لليمين عبر `[dir="rtl"] .back-arrow` |
| إصدار النظام في لوحة التشغيل | `0.1.0-phase1` (قديم) | `0.6.0-phase6` من `hospital.properties` |

**التحقق:** 12 مسارًا (`dashboard, appointments, patients, clinical, er, admission, nursing, laboratory, pharmacy, billing, inventory, admin`) — `overflowX = 0`، **0 أخطاء كونسول**، بطاقة واحدة لكل تبويب قائمة، وفلترة الإعدادات تعمل (13 → 1 مجموعة).

**الملفات:** `frontend/src/modules/scheduling/AppointmentsPage.tsx`، `frontend/src/modules/admin/BreakGlassTab.tsx`، `frontend/src/modules/admin/SettingsTab.tsx`، `frontend/src/modules/clinical/EncounterPage.tsx`، `frontend/src/modules/patients/PatientCardPage.tsx`، `frontend/src/styles/design-system.css`، و`frontend/src/shared/i18n/index.tsx`.

---

## 12. مسار JMS — TC-176 (2026-11-17)

**الهدف:** إثبات أن فشل الوسيط بعد الـcommit لا يُفقد حدثًا، وأن الرسالة التي لا يمكن تسليمها تُحجز في DLQ بدل أن تُسقط بصمت.

### 12.1 البنية التحتية

| المورد | النوع | الإعداد |
|---|---|---|
| `NotificationQueue` | Queue (فيزيائي) | `max-redelivery-limit=3` |
| `NotificationDLQ` | Queue (فيزيائي) | حجز الرسائل الميتة |
| `jms/NotificationQueue` | JNDI | `jakarta.jms.Queue` → `NotificationQueue` |
| `jms/NotificationDLQ` | JNDI | `jakarta.jms.Queue` → `NotificationDLQ` |
| `jms/__defaultConnectionFactory` | Connection Factory | الافتراضي المضمّن |

**الإنشاء:** `powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-jms.ps1` — idempotent (يتحقق من `list-jmsdest` و`list-jms-resources` قبل الإنشاء)، ومُتحقَّق بتشغيله مرتين متتاليتين.

### 12.2 المسار

```
outbox_events(PENDING)
   └─ Dispatcher: تحقّق من عقد الحمولة
        └─ enqueue داخل معاملة الحاوية → jms/NotificationQueue
             └─ SENT (بعد قبول الوسيط)
NotificationMdb (consumer)
   └─ NotificationDelivery.deliverEvent (REQUIRES_NEW)
        ├─ نجاح → صف Inbox + SSE بعد الـcommit
        └─ فشل → noteQueueFailure (attempts++ على صف الـoutbox)
                  └─ عند بلوغ عدّاد المحاولات 3: parkInDeadLetter → jms/NotificationDLQ + markDead → DEAD
```

| الضمان | كيف يتحقق |
|---|---|
| فشل الإرسال بعد الـcommit لا يفقد الحدث | الإرسال داخل معاملة الـDispatcher؛ أي استثناء يُرجع الصف إلى `PENDING` مع `attempts++` و`last_error` |
| لا تسليم مزدوج | `NotificationDelivery` يستخدم `INSERT IGNORE` + `UNIQUE(user_id, dedupe_key)`؛ الرسالة المعاد تسليمها لا تُنشئ صفًا ثانيًا |
| لا رسالة تُسقط بصمت | بعد 3 محاولات: نسخة على `jms/NotificationDLQ` + الحالة `DEAD` مع سبب الفشل، و`POST /admin/outbox/{id}/retry` يعيدها |
| عدّ المحاولات موثوق | العدّاد على صف الـoutbox (`noteQueueFailure`) لا على خاصية الرسالة — OpenMQ لا يعرض `JMSXDeliveryCount` بشكل موثوق هنا |

### 12.3 نتائج TC-176

| الفحص | النتيجة المرصودة |
|---|---|
| فشل JMS بعد الـcommit (محقون عبر `outbox.jms.simulate.failure`) | الحدث بقي **`PENDING`** مع `attempts=1` و`last_error` يذكر الانقطاع، بلا صف Inbox، والصف محفوظ في القاعدة |
| التعافي | بعد إزالة المحقون: الحدث → **`SENT`** وظهر صف Inbox واحد |
| رسالة غير قابلة للتسليم (محقون عبر `outbox.jms.simulate.delivery-failure`) | عند بلوغ عدّاد المحاولات 3: الحدث → **`DEAD`** مع `Queue delivery failed…`، ونسخة على **`jms/NotificationDLQ`** (`dlq=2` تراكميًا)، بلا صف Inbox |
| إعادة التشغيل | `POST /admin/outbox/{id}/retry` → 200، الحدث → **`SENT`**، صف Inbox واحد |

**قياس الـDLQ:** `imqcmd list dst -t q -b localhost:7676 -u admin -passfile <file>` — العمود السادس هو عدد الرسائل. (`imqcmd metrics dst` يتوقف هنا؛ لا يُستخدم.)

### 12.4 الحِمل عبر JMS

**الأمر:** `powershell -NoProfile -ExecutionPolicy Bypass -File tools\load-outbox.ps1`
**السجل:** `testing/06-phase6-load-jms.log`

| القياس | النتيجة |
|---|---|
| `SENT` | **1,000 / 1,000** |
| `DEAD` / `PENDING` / `PROCESSING` | 0 / 0 / 0 |
| أحداث أُعيدت محاولتها (`attempts <> 1`) | **0** |
| صفوف Inbox | **1,000** (1,000 مفتاح `dedupeKey` فريد) |
| مطالبات متبقية | 0 |
| الزمن الكلي | **198 ثانية** (5.05 حدث/ث) |

**قراءة النتيجة:** إضافة الـMDB لم تُغيّر الإنتاجية لأنها محدودة بمؤقّت الـDispatcher (25 حدثًا كل 5 ثوانٍ)، ولم تُنتج فقدًا أو تكرارًا أو إعادة تسليم واحدة.

### 12.5 حدود

- الوسيط **مضمّن في JVM الخاص بـGlassFish**، لذا لا يمكن إيقافه/تشغيله بأمان داخل المجموعة؛ استُخدمت مفاتيح إعداد للاختبار فقط (`outbox.jms.simulate.failure` / `outbox.jms.simulate.delivery-failure`) تُدرج وتُحذف في `finally`. سلوك الإنتاج عند انقطاع وسيط خارجي يتبع نفس المسار: استثناء في الإرسال → `PENDING`.
- لم يُختبر استمرار الرسائل عبر إعادة تشغيل الوسيط (Broker restart)، ولا التوسع الأفقي (مستهلكون متعددون).

---

## 13. الأولوية والقوالب ودورة حياة البث (2026-11-17)

**الهدف:** إغلاق بنود G6 المتبقية: أن يقفز الحدث الحرج أي ازدحام، وأن تكون صياغة الإشعار قالبًا قابلًا للتهيئة، وأن يتصدّر الحرج صندوق الوارد، وأن يُغلق البث عند إبطال الجلسة.

| المحور | التنفيذ | الملف |
|---|---|---|
| مخطط البيانات | `V012`: `outbox_events.priority` + فهرس `(status, next_attempt_at, priority, id)`، `notifications.priority` + فهرس `(user_id, status, priority, created_at)`، وجدول `notification_templates` بـ7 قوالب مزروعة | `db/migrations/V012__notification_priority_templates.sql` |
| اشتقاق الأولوية | `CRITICAL=100`، `WARNING=50`، `INFO=0` من خطورة الحمولة؛ يمكن للمُنتج تمرير `priority` صريح (0–100) | `OutboxService` |
| المطالبة | `UPDATE … ORDER BY priority DESC, id LIMIT n` — الأولوية أولًا، وFIFO داخل النطاق نفسه | `OutboxDispatcher.claimBatch` |
| القوالب | `templateCode` + `params` → استبدال `{placeholder}` في العنوان والنص (عربي/إنجليزي)، والخطورة الافتراضية للقالب تُستخدم إن لم تُحدَّد | `NotificationDelivery` |
| إدارة القوالب | `GET /admin/outbox/templates` · `PUT /admin/outbox/templates/{code}` بصلاحية `SYS:CONFIG`، و404 لقالب غير موجود | `OutboxResource` |
| ترتيب الـInbox | `ORDER BY priority DESC, created_at DESC, id DESC` | `NotificationRepository` |
| ترويسات الوكيل | `Cache-Control: no-cache, no-transform` + `X-Accel-Buffering: no` + `Connection: keep-alive` | `NotificationResource.stream` |
| إغلاق البث | كل نبضة 20 ثانية تتحقق من `user_sessions.ended_at` عبر `sessionActive(sessionHash)` وتغلق `SseEventSink` | `NotificationResource` · `NotificationService` |
| إعداد Nginx مرجعي | `proxy_buffering off` · `proxy_read_timeout 1h` · `Connection ""` لمسار `/api/v1/notifications/stream` | `deploy/nginx/smart-hospital.conf` |

**النتائج المرصودة:**

| الفحص | النتيجة |
|---|---|
| TC-134 — حرج وسط ازدحام | 60 حدث `INFO` + 1 `CRITICAL` → الحرج `SENT` وبقي **36** حدثًا `INFO` في `PENDING` |
| TC-135 — قالب `LAB_CRITICAL` | النص يحوي `CBC` و`P-001` بلا `{test}`، والخطورة `CRITICAL` والأولوية `100` |
| TC-135 — الكتالوج | 7 قوالب عبر API · قالب غير معروف → 404 |
| TC-139 — ترتيب الوارد | `CRITICAL(0) < WARNING(4) < INFO(5)` رغم إدراج `INFO` أخيرًا |
| TC-137 — ترويسات | `cache-control=no-cache, no-transform` · `x-accel-buffering=no` |
| TC-137 — إبطال الجلسة | البث أُغلق بعد **8–9 ثوانٍ** من `DELETE /admin/sessions/{id}` |

**المجموع بعد الإضافة:** `20 PASS / 0 FAIL / 0 SKIP` (`testing/06-phase6-run.log`).

---

## 14. تنسيق الواجهة — Design v5 «الكثافة الهادئة» (2026-11-17)

**الهدف:** إيقاع بصري واحد يُفهم في ثلاث ثوانٍ: شريط عنوان بخط رقيق ثم 20px هواء، نصف قطر واحد للبطاقات، صفوف جدول 44px برأس ثابت، وأزرار 40px، وبلا تدرّجات أو ظلال زخرفية.

| المحور | قبل v5 | بعد v5 |
|---|---|---|
| شريط العنوان | 52px بلا فاصل، محاذاة سفلية | 65px بمحاذاة وسطية وخط فاصل رقيق أسفله |
| إيقاع الصفحة | 18px | 20px موحّد |
| نصف القطر | مزيج 16/12/10/9/8 | 14 بطاقات · 12 جدول · 10 عناصر تحكم · 8 شرائح · Pills كما هي |
| الجداول | 40px بلا رأس ثابت | صف 44px + رأس لاصق داخل منطقة تمرير 68vh |
| عناصر التحكم | 36px | 40px (والأزرار الصغيرة 32px) |
| الشريط الجانبي | تدرّج + ظل خارجي | لون مسطّح `#0b2136` بلا ظل |
| لوحة التشغيل | 6 بلاطات مؤطّرة + 27 صندوقًا مؤطّرًا | **مصفوفة متكاملة** بفواصل 1px: 12 بلاطة، 3 صفوف، بلا خلايا فارغة، **15** صندوقًا مؤطّرًا |
| التدرّجات داخل التطبيق | 3 (الشريط، الشعار، الصورة الرمزية) | **0** (تبقى في شاشة الدخول كعلامة هوية) |
| الظلال داخل التطبيق | 6 | **3** (هالات مؤشرات الحالة فقط) |
| التبويبات | تمرير أفقي بمؤشر مخفي | التفاف تلقائي تحت 1180px بدل إخفاء التبويبات |

**التحقق (DOM محسوب، بلا لقطات) — 12 مسارًا** (`dashboard, patients, appointments, clinical, er, admission, nursing, laboratory, pharmacy, billing, inventory, admin`):
- `overflowX = 0` في كل المسارات · **0 أخطاء كونسول** · `documentHeight` للمخزون انخفض من 2,180px إلى 1,010px بفضل الجداول ذات التمرير الداخلي والرأس الثابت.
- المصفوفة: 12 بلاطة في 3 صفوف × 4 أعمدة، `gap = 0`، حدود البلاطات `0px`، ولا خلايا رمادية فارغة.
- نصف القطر السائد `14px` للبطاقات و`10px` لعناصر التحكم؛ لا ظلال على البطاقات.
- الشريط الجانبي مسطّح، والصورة الرمزية بلون واحد.

**الملفات:** `frontend/src/styles/design-system-v5.css` (طبقة أخيرة فوق v1–v4) و`frontend/src/main.tsx` (الاستيراد الأخير).

**حدود:** v5 طبقة CSS فقط — لا تغيير في أي منطق عمل أو صلاحية. إزالة الاستيراد تُرجع المظهر السابق حرفيًا.

---

## 15. تنسيق الواجهة — Design v6 «مفردات واحدة» (2026-11-17)

**الهدف:** لم تكن المشكلة في اللون أو الظل، بل في **اختلاف البنية بين الشاشات**: بعض البطاقات بترويسة `card-header` وأخرى بعنوان `<h2>` عارٍ أو بشريط أدوات بلا عنوان، وتبويبات بخط سفلي تتفاوت بين صفحة وأخرى، ومؤشرات KPI كبلاطات منفصلة، وصناديق تعريفات رمادية بخط `mono` لكل قيمة. v6 يفرض مفردات واحدة على كل الوحدات دون لمس أي منطق عمل.

| المحور | قبل v6 | بعد v6 |
|---|---|---|
| التبويبات | خط سفلي + التفاف/تمرير متفاوت | **شريط مقسّم واحد** (Segmented 12px): التبويب النشط سطح أبيض بحد رقيق وظل 1px، والتفاف منضبط |
| ترويسة البطاقة | مزيج `card-header` و`<h2>` عارية وtoolbar بلا عنوان | **شكل واحد**: عنوان 0.97rem + خط فاصل رقيق + الإجراء على الطرف |
| مؤشرات KPI | بلاطات منفصلة بحدود وظل | **شريط واحد** داخل إطار 14px بفواصل hairline، وأرقام `tabular-nums` |
| شبكات التعريف | صناديق رمادية و`dd` بخط `mono` لكل قيمة | **مصفوفة عمودين** بحد رقيق، والقيمة بخط الواجهة، و`mono` فقط عند `.mono` |
| عناصر التحكم | 40px | **36px** (وصغير 30px)، وحقول شريط الأدوات 32px |
| الجداول | رأس 10px و`letter-spacing` على العربية | رأس 9px/13px، **`letter-spacing: normal`** للعربية، صف 42px |
| التنبيهات | ثلاثة أشكال مختلفة | شكل واحد بحد جانبي 3px (`alert-danger/success/warn`) |
| لوحة الطوارئ | صفحة بطول **2,474px** | **1,281px** — قوائم البطاقات بحد 360px وتمرير داخلي |
| صفحة المخزون | 1,010px | **924px** |
| الطباعة | كسر قسري محتمل للبطاقة الطويلة | **بلا `break-inside: avoid`** — تقرير المختبر العربي يتدفق بين الصفحات بلا قص |

**التحقق (DOM محسوب، بلا لقطات) — 12 مسارًا:** `overflowX = 0` في كل المسارات · **0 أخطاء كونسول** · كل التبويبات في **صف واحد** (بما فيها 8 تبويبات للمخزون بعرض 599px) · شرائط KPI بعدد 4/5/6 بفواصل 0.8px · نصف قطر البطاقات `14px` في كل الشاشات · أزرار 36px وإجراءات صفوف 26px النصية الهادئة.

**الحزمة المخدومة:** `index-ChVd2dd-.js` و`index-DGN5W4q9.css` — مطابقة لآخر بناء.

**الملفات:** `frontend/src/styles/design-system-v6.css` (طبقة أخيرة فوق v1–v5) و`frontend/src/main.tsx` (الاستيراد الأخير).

**حدود:** v6 طبقة CSS فقط — لا تغيير في أي منطق عمل أو صلاحية أو عقد API. إزالة الاستيراد تُرجع مظهر v5 حرفيًا.
