# 08 — Architecture Decision Records (ADR-001..020)

| Field | Value |
|---|---|
| Document ID | ARC-ADR-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Format | Context → Decision → Alternatives → Consequences |
| Rule | لا يُغيَّر ADR مقبول دون ADR جديد يشير إليه |

> كل قرار هنا اتُخذ لسبب، وكل سبب له بديل رُفض. الهدف ليس توثيق "ماذا"، بل **لماذا**، حتى لا يُعاد النقاش نفسه بعد ستة أشهر.

---

## ADR-001 — Modular Monolith داخل EAR واحد

**Status:** Accepted · **Date:** 2026-11-11

**Context:** نظام مستشفى كامل، خادم واحد داخل LAN، فريق صغير، متطلبات Jakarta EE صريحة، ونطاق أكاديمي محدود.

**Decision:** تطبيق واحد `smart-hospital-ear` يضم `smart-hospital-ejb` + `smart-hospital-war`. الوحدات حدود منطقية داخل نفس العملية، لا خدمات مستقلة.

**Alternatives:**
- Microservices: مرونة نشر، لكن تعقيد شبكة/معاملات/مراقبة لا يتناسب مع خادم واحد وفريق صغير.
- WAR واحد بلا فصل EJB: أبسط، لكنه يخلط الويب بمنطق الأعمال.
- Modular Monolith متعدد EARs: يعقّد النشر بلا فائدة.

**Consequences:**
- ✅ نشر واحد، معاملة واحدة، تصحيح أخطاء أسهل، أداء أفضل.
- ⚠️ حدود الوحدات تُفرض بالحزم والمراجعة، لا بالشبكة.
- ⚠️ إعادة النشر تؤثر على كل النظام (مقبول في v1).

**Related:** ADR-002, `01-system-architecture.md`.

---

## ADR-002 — حدود وحدات صارمة بالحزم وواجهات API

**Status:** Accepted

**Context:** Modular Monolith يتحول إلى "كرة طينية" إن لم تُفرض الحدود.

**Decision:** كل وحدة `com.smarthospital.<module>` ولها `api/domain/repo/service/event`. من خارج الوحدة يُسمح فقط باستيراد `api.*`. الاتجاه من مستوى أعلى إلى أدنى فقط، بلا دورات.

**Alternatives:** حزم مسطحة حسب الطبقة (`controllers`, `services`) — سهلة البداية، مستحيلة الصيانة. ArchUnit — ممتاز لكنه اعتمادية إضافية؛ نستخدم سكربت فحص بسيط.

**Consequences:** ✅ حدود قابلة للاختبار، ✅ فصل مستقبلي ممكن، ⚠️ انضباط مطلوب في كل مراجعة.

**Related:** `01-system-architecture.md` §5.

---

## ADR-003 — مصادقة بجلسة على الخادم (Cookie) لا JWT

**Status:** Accepted

**Context:** واجهة React تُخدَم من نفس أصل API، والحاجة إلى إبطال فوري للوصول.

**Decision:** `HttpOnly; Secure; SameSite=Strict` session cookie، مع CSRF token مزدوج. لا JWT في المتصفح.

**Alternatives:**
- JWT في `localStorage`: عرضة لـ XSS، لا إبطال فوري.
- JWT في Cookie: ممكن، لكنه يضيف تعقيد تدوير المفاتيح بلا فائدة.
- OAuth2/OIDC: مبالغة لنظام داخلي.

**Consequences:** ✅ إبطال فوري، ✅ لا توكن قابل للسرقة من JS، ⚠️ جلسات In-memory تنتهي عند إعادة التشغيل (مقبول v1)، ⚠️ التوسع الأفقي يحتاج Sticky/Shared Session.

**Related:** ADR-006, `06-security-architecture.md` §3.

---

## ADR-004 — PBKDF2WithHmacSHA256 بدل Argon2id

**Status:** Accepted

**Context:** يجب تخزين كلمات المرور بأمان، مع تقليل الاعتماديات الخارجية والبقاء على JDK 17.

**Decision:** `PBKDF2WithHmacSHA256`، 600,000 تكرار، ملح 16 بايت، مع حقول خوارزمية/تكرارات قابلة للترقية.

**Alternatives:**
- Argon2id: أقوى ضد GPU، لكنه يتطلب مكتبة خارجية وترخيصًا/إدارة إضافية.
- bcrypt: مكتبة خارجية، حد 72 بايت.
- scrypt: مكتبة خارجية.

**Consequences:** ✅ صفر اعتماديات، ✅ متوافق مع JDK، ⚠️ أقل مقاومة من Argon2id، ✅ مسار ترقية واضح عند تغيير المعايير.

**Related:** NFR-SEC-002.

---

## ADR-005 — Transactional Outbox من اليوم الأول

**Status:** Accepted

**Context:** إرسال JMS داخل معاملة DB إما يفقد رسالة أو يرسل رسالة لمعاملة تراجعت.

**Decision:** جدول `outbox_events` يُكتب في نفس معاملة التغيير. Dispatcher منفصل يقرأ `SKIP LOCKED` ويرسل إلى JMS، ثم يعلّم `SENT`.

**Alternatives:**
- JMS مباشر داخل المعاملة: فقدان/رسائل يتيمة.
- XA (DB + JMS): تعقيد وهشاشة.
- CDC (Debezium): بنية إضافية كبيرة.

**Consequences:** ✅ ضمان at-least-once، ✅ فشل JMS لا يؤثر على العملية، ⚠️ تأخير بسيط (ثوانٍ)، ⚠️ يحتاج مراقبة backlog.

> **تحديث تنفيذي (2026-11-17):** المطالبة صارت بيان `UPDATE … ORDER BY priority DESC, id LIMIT n` واحدًا بختم `claim_token` بدل `SKIP LOCKED` (تفادي deadlock على gap locks، وإعطاء الأولوية للحرج). والمسار الكامل منفّذ: Dispatcher → `jms/NotificationQueue` → `NotificationMdb` → Inbox + SSE، مع `jms/NotificationDLQ` للأحداث غير القابلة للتسليم و`DEAD` قابل لإعادة التشغيل. التفصيل في `07-notification-architecture.md` §4/§5/§7 و`testing/06-phase6-evidence.md` §12/§13.

**Related:** ADR-006, `07-notification-architecture.md`.

---

## ADR-006 — SSE للتحديث الفوري + Polling احتياطي

**Status:** Accepted

**Context:** نحتاج إشعارات فورية في المتصفح دون تعقيد WebSocket.

**Decision:** `EventSource` على `/api/v1/notifications/stream`، مع Fallback Polling كل 20 ثانية.

**Alternatives:**
- WebSocket: ثنائي الاتجاه، لكنه يتطلب إدارة اتصال وبروتوكولًا وBackplane.
- Polling فقط: أبسط، لكنه يضغط الخادم ويؤخر الإشعارات.
- Long polling: حل وسط، تعقيد مماثل.

**Consequences:** ✅ أبسط من WebSocket، ✅ يعمل عبر Nginx، ⚠️ أحادي الاتجاه (كافٍ للإشعارات)، ⚠️ يحتاج Backplane عند التوسع.

> **تحديث تنفيذي (2026-11-17):** الترويسات `Cache-Control: no-cache, no-transform` + `X-Accel-Buffering: no` تمنع تخزين الوكيل، والملف `deploy/nginx/smart-hospital.conf` يضبط `proxy_buffering off` للمسار. كل نبضة 20 ثانية تغلق القناة بعد إبطال الجلسة (`user_sessions.ended_at`)، مع بقاء Polling كبديل. الإثبات: TC-137.

**Related:** ADR-005, `07-notification-architecture.md` §10.

---

## ADR-007 — BIGINT PK + public_id (ULID)

**Status:** Accepted

**Context:** المعرّفات المتسلسلة تكشف حجم البيانات وتسهّل التخمين؛ UUID الكامل يشتت الفهارس.

**Decision:** PK داخلي `BIGINT AUTO_INCREMENT`، ومعرّف عام `public_id CHAR(26)` ULID فريد، يُستخدم في API والباركود.

**Alternatives:**
- UUID v4 كـ PK: فهارس أسوأ، حجم أكبر.
- أرقام متسلسلة مكشوفة: تسريب معلومات.
- ULID كـ PK: ممكن، لكنه أعرض من BIGINT.

**Consequences:** ✅ فهارس مضغوطة، ✅ معرفات غير قابلة للتخمين، ✅ فرز زمني ممكن، ⚠️ حاجة لتوليد ULID في الخدمة.

**Related:** `database/03-schema-constraints-indexes.md`.

---

## ADR-008 — قفل متفائل افتراضي + متشائم انتقائي

**Status:** Accepted

**Context:** التزامن في الصرف/الأسرة/الدفع يسبب فسادًا، لكن قفل كل شيء يخنق النظام.

**Decision:** `@Version` لكل كيان. `FOR UPDATE` فقط في: دفعات الصرف (FEFO)، السرير، الفاتورة عند الدفع، عدّاد المستندات، اعتماد النتيجة.

**Alternatives:**
- قفل متشائم شامل: آمن لكن بطيء.
- قفل متفائل فقط: تعارضات متكررة في العمليات الحرجة.
- `SERIALIZABLE`: deadlocks وأداء سيئ.

**Consequences:** ✅ توازن، ⚠️ كل حالة حرجة تحتاج اختبار تزامن، ⚠️ رسائل 409 مفهومة إلزامية.

**Related:** `05-transactions-concurrency.md`.

---

## ADR-009 — سجل تدقيق بسلسلة Hash وInsert-only

**Status:** Accepted

**Context:** سجل تدقيق قابل للتعديل لا قيمة له في التحقيق.

**Decision:** `audit_logs` بحقول `prev_hash` و`hash`، وصلاحية DB: INSERT فقط للتطبيق. مهمة يومية تتحقق من السلسلة.

**Alternatives:**
- جدول عادي: بسيط، لكن التلاعب غير قابل للاكتشاف.
- Blockchain خارجي: مبالغة.
- WORM Storage: بنية إضافية.

**Consequences:** ✅ كشف التلاعب، ✅ امتثال تقني أقوى، ⚠️ حجم أكبر، ⚠️ يحتاج Partitioning وأرشفة.

**Related:** NFR-SEC-010, RSK-020.

---

## ADR-010 — تحليلات على Snapshot يومي

**Status:** Accepted

**Context:** استعلامات KPIs على الجداول الحية تُبطئ النظام وتتعارض مع المعاملات.

**Decision:** جداول `kpi_snapshots` و`fact_*` تُبنى ليلًا (01:00) بمهمة مجدولة. الواجهة تقرأ Snapshots. التحليلات قد تقرأ `v_*` للقراءة فقط.

**Alternatives:**
- استعلام مباشر: بسيط لكنه خطر على الأداء.
- Data Warehouse منفصل: مبالغة v1.
- Materialized Views: MySQL لا يدعمها أصلاً.

**Consequences:** ✅ استجابة سريعة، ✅ عزل الأحمال، ⚠️ بيانات حتى آخر لقطة، ⚠️ يجب عرض "آخر تحديث".

**Related:** `analytics/02-analytics-engine.md`, NFR-PERF-004.

---

## ADR-011 — طبقة مصطلحات خفيفة (concept + mappings)

**Status:** Accepted

**Context:** النص الحر يجعل التقارير والتحليلات ضعيفة؛ SNOMED كامل مكلف ومرخّص.

**Decision:** جدول `concepts` + `concept_mappings` + كتالوج أكواد محلي/مصغّر (ICD/LOINC/ATC اختياري). لا ادعاء ترخيص.

**Alternatives:**
- نص حر فقط: بسيط، تحليلات ضعيفة.
- SNOMED كامل: ترخيص وتكلفة.
- FHIR Terminology Server: بنية إضافية.

**Consequences:** ✅ تحليلات أفضل، ✅ مرونة، ⚠️ يحتاج إدارة مصطلحات، ⚠️ لا يغطي كل الحالات.

**Related:** SF-02, SF-28.

---

## ADR-012 — نسخ/إصدارات للسجلات الطبية

**Status:** Accepted

**Context:** تعديل ملاحظة موقّعة يمحو الحقيقة السريرية.

**Decision:** `clinical_notes` (الحالة) + `clinical_note_versions` (كل نسخة). التوقيع يجمّد النسخة. التصحيح ينشئ نسخة جديدة مع `amends_id` وسبب.

**Alternatives:**
- تعديل مباشر + Audit: يفقد النص السابق في العرض.
- تاريخ كامل بلا فصل: استعلامات أعقد.
- Event Sourcing كامل: مبالغة.

**Consequences:** ✅ سلامة سريرية، ✅ تتبع كامل، ⚠️ استعلامات تحتاج `current_version_id`، ⚠️ واجهة تعرض الفروقات.

**Related:** BR-EMR-005/006, SM-LAB-RESULT.

---

## ADR-013 — MySQL InnoDB + READ_COMMITTED + Unique عبر active_flag

**Status:** Accepted

**Context:** نحتاج قيود "سجل نشط واحد" (سرير، موعد) دون Partial Index.

**Decision:** `READ_COMMITTED` + `active_flag` (`1` للنشط، `NULL` للمنتهي) مع `UNIQUE(bed_id, active_flag)`.

**Alternatives:**
- `SERIALIZABLE`: خنق.
- Trigger: يعقّد الاختبار.
- تطبيق فقط: عرضة للتسرب.

**Consequences:** ✅ قيد DB حقيقي، ✅ لا Gap locks زائدة، ⚠️ كل جدول يحتاج عمودًا إضافيًا، ⚠️ يجب توثيق السلوك.

**Related:** `05-transactions-concurrency.md` §6.

---

## ADR-014 — طباعة عربية عبر HTML/CSS + خط مضمّن

**Status:** Accepted

**Context:** الفواتير والنتائج تحتاج طباعة RTL دقيقة دون اعتماديات ثقيلة.

**Decision:** قوالب HTML مخصصة + `@media print` + خط `Noto Naskh Arabic` مضمّن. PDF عبر "طباعة إلى PDF".

**Alternatives:**
- JasperReports: قوي، لكن اعتمادية وقوالب معقدة.
- iText/OpenPDF: تحكم، لكن ترخيص/خطوط/جهد.
- Canvas: جودة عربية رديئة.

**Consequences:** ✅ صفر اعتماديات، ✅ تحكم كامل، ⚠️ يعتمد على المتصفح/الطابعة، ⚠️ اختبار طباعة فعلي إلزامي.

**Related:** PD-006, RSK-014.

---

## ADR-015 — لا قاعدة بيانات محلية على الأجهزة

**Status:** Accepted

**Context:** Offline-first يضاعف التعقيد ويخلق تعارضات سريرية.

**Decision:** كل عميل متصفح فقط. لا تخزين محلي للبيانات. عند انقطاع الشبكة، لا عمل.

**Alternatives:**
- PouchDB/IndexedDB + Sync: تعارضات وحلول معقدة.
- تطبيق سطح مكتب: نشر وصيانة.
- Read-only snapshot: مؤجل SF-26.

**Consequences:** ✅ مصدر حقيقة واحد، ✅ أمان أبسط، ⚠️ الاعتماد على LAN مستقر، ⚠️ لا عمل عند انقطاع الشبكة.

**Related:** ASM-025, NFR-REL-006.

---

## ADR-016 — فصل service_execution عن invoice_item

**Status:** Accepted

**Context:** الفوترة من الشاشة تفصل التنفيذ عن الدفع وتسمح بأخطاء.

**Decision:** كل خدمة منفذة تُسجَّل في `service_executions` (من، ماذا، متى، أين). الفاتورة تُبنى منها عبر Charge Capture. `invoice_items` تشير إلى `service_execution_id`.

**Alternatives:**
- فوترة مباشرة من الشاشة: سهلة، لكنها قابلة للخطأ والنسيان.
- فوترة عند الطلب: تسبق التنفيذ.
- Trigger: مخفي وصعب الاختبار.

**Consequences:** ✅ لا خدمة بلا تسجيل، ✅ مطابقة مالية، ⚠️ كل وحدة تنفّذ خدمة يجب أن تنشر حدثًا/تسجيلًا، ⚠️ يحتاج مراجعة يومية.

**Related:** FR-BIL-001, SF-11, KPI-025.

---

## ADR-017 — تخزين الملفات خارج webroot

**Status:** Accepted

**Context:** تخزين المرفقات في `webroot` يفتح ثغرات تنفيذ/تسريب.

**Decision:** ملفات في `/opt/smarthospital/files`، أسماء عشوائية، وصول عبر API بعد فحص الملكية. لا BLOB في DB.

**Alternatives:**
- BLOB في DB: نسخ احتياطي أثقل، أداء أسوأ.
- webroot: خطر أمني.
- Object Storage: بنية إضافية.

**Consequences:** ✅ أمان، ✅ نسخ أسهل، ⚠️ يجب مزامنة الملفات مع النسخ الاحتياطي، ⚠️ لا وصول مباشر.

**Related:** NFR-SEC-008, `06-security-architecture.md` §8.

---

## ADR-018 — لا HA/Clustering في v1

**Status:** Accepted

**Context:** خادم واحد كافٍ لحجم 200–400 سرير، وHA يضاعف التعقيد.

**Decision:** خادم واحد + UPS + نسخ + RTO ≤ 4h. التصميم يسمح بإضافة خادم تطبيق ثانٍ لاحقًا (JNDI، جلسات Sticky).

**Alternatives:**
- GlassFish Cluster + MySQL InnoDB Cluster: توافر أعلى، تعقيد كبير.
- خادم احتياطي بارد: ممكن لاحقًا.
- سحابة: يخالف النشر الداخلي.

**Consequences:** ✅ بساطة، ✅ تكلفة أقل، ⚠️ توقف كامل عند فشل الخادم، ⚠️ RTO يعتمد على النسخ.

**Related:** PD-011, RSK-006, NFR-SCL-003.

---

## ADR-019 — الوقت UTC في DB والعرض بالتوقيت المحلي

**Status:** Accepted

**Context:** فروق التوقيت/التوقيت الصيفي تسبب أخطاء في المواعيد والتحليلات.

**Decision:** كل `DATETIME(6)` يُخزَّن UTC. التحويل إلى توقيت المستشفى في الواجهة/التقارير. `TIMESTAMP` يُستخدم فقط للتدقيق إن لزم.

**Alternatives:**
- تخزين محلي: أخطاء DST ومقارنات.
- `TIMESTAMP` فقط: نطاق محدود 2038.
- تخزين نصي: أسوأ.

**Consequences:** ✅ مقارنات صحيحة، ✅ تقارير متسقة، ⚠️ كل شاشة يجب أن تحوّل، ⚠️ NTP إلزامي.

**Related:** BR-GEN-007, NFR-OPS.

---

## ADR-020 — لا ادعاء توافق FHIR/HL7/DICOM قبل اختبار رسمي

**Status:** Accepted

**Context:** ادعاء التوافق يخلق التزامات قانونية/تجارية لا نستطيع الوفاء بها.

**Decision:** طبقة `integration` مع Adapter و`external_identifiers` فقط. لا ادعاء توافق في الوثائق أو العرض قبل اختبار رسمي موثّق.

**Alternatives:**
- FHIR R4 كامل: مشروع كبير.
- HL7 v2: تكامل أجهزة.
- ادعاء تسويقي: مرفوض.

**Consequences:** ✅ صدق هندسي، ✅ حماية قانونية، ⚠️ قد يبدو النطاق أقل "تقدمًا"، ✅ قابل للتوسع لاحقًا.

**Related:** PD-009, FR-INT-004, NFR-INT-004.

---

## جدول ملخص

| ADR | القرار | الحالة | البديل المرفوض |
|---|---|---|---|
| 001 | Modular Monolith EAR | A | Microservices |
| 002 | حدود حزم + API | A | حزم مسطحة |
| 003 | Session Cookie | A | JWT في localStorage |
| 004 | PBKDF2 | A | Argon2id |
| 005 | Outbox | A | JMS مباشر / XA |
| 006 | SSE + Polling | A | WebSocket |
| 007 | BIGINT + ULID | A | UUID PK |
| 008 | Optimistic + Selective Pessimistic | A | قفل شامل |
| 009 | Hash-chain Audit | A | جدول عادي |
| 010 | Daily Snapshot | A | استعلام مباشر |
| 011 | Lightweight Concepts | A | SNOMED كامل |
| 012 | Versioned Clinical Records | A | تعديل مباشر |
| 013 | READ_COMMITTED + active_flag | A | SERIALIZABLE |
| 014 | HTML/CSS Arabic Print | A | JasperReports |
| 015 | No Local DB | A | Offline-first |
| 016 | service_execution منفصل | A | فوترة من الشاشة |
| 017 | Files خارج webroot | A | BLOB / webroot |
| 018 | No HA v1 | A | Cluster |
| 019 | UTC Storage | A | توقيت محلي |
| 020 | No FHIR Claim | A | ادعاء توافق |

---

## سجل التغييرات

| التاريخ | ADR | التغيير | السبب |
|---|---|---|---|
| 2026-11-11 | 001–020 | الإصدار الأول | خط الأساس |
