# 12 — Benchmark & Gap Analysis

**مقارنة Smart Hospital بالأنظمة الصحية المؤسسية مفتوحة المصدر، وتحديد الفجوات الحقيقية**

| Field | Value |
|---|---|
| Document ID | REQ-BENCH-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Method | مراجعة وثائق المنتجات الرسمية + تصنيف قدرات + تحليل فجوات |
| Date | 2026-11-11 |

> **تنبيه منهجي:** هذه مقارنة هندسية وظيفية مبنية على الوثائق العامة للمنتجات، وليست شهادة اعتماد أو تقييمًا رسميًا لأي نظام. العلامات `●/◐/○` تقديرية وتخدم قرار التصميم، لا التسويق.

---

## 1. المنهجية

1. اختيار أنظمة مرجعية تغطي أنماطًا مختلفة: EMR مؤسسي، HIS متكامل، LIS، منصة FHIR، ERP صحي، ونظام Offline-first.
2. استخراج **القدرات** لا الشاشات: هل القدرة موجودة، جزئية، أم غائبة؟
3. مقارنة كل قدرة مع ما يطلبه مشروعك في المواصفات الأصلية.
4. تصنيف الفجوة: **حرجة** (تؤثر على السلامة/المال/الامتثال)، **مهمة** (تؤثر على الكفاءة)، **تحسينية**.
5. اقتراح معالجة عملية لكل فجوة، مع ربطها بوثيقة التصميم.

### المصادر التي روجعت

| # | المصدر | ما استُخلص منه |
|---|---|---|
| S1 | OpenMRS — <https://openmrs.org/> (وصف: 8000+ منشأة، 70+ دولة، 15M سجل، REST/FHIR APIs، قابلية ترجمة) | نموذج EMR معياري قابل للتوسع، قاموس مفاهيم، FHIR |
| S2 | Bahmni — <https://www.bahmni.org/> (OpenMRS + Odoo + OpenELIS + DICOM/PACS، تشغيل داخل المستشفى بلا إنترنت، SNOMED CT، 500+ موقع) | نموذج HIS متكامل On-Premises، فصل المكوّنات، دعم مصطلحات |
| S3 | GNU Health — <https://www.gnuhealth.org/> (منظومة صحة حرة، مستخدمة في مستشفيات وجامعات) | نموذج بيانات صحي شامل، صحة عامة، قابلية التخصيص |
| S4 | OpenELIS Global — <https://openelis-global.org/> (LIS، سلسلة حيازة العينة، تكامل أجهزة ASTM/HL7، FHIR-native) | عمق دورة المختبر وتتبّع العينة |
| S5 | HospitalRun — <https://github.com/HospitalRun/hospitalrun-frontend> (React + CouchDB/PouchDB، Offline-first، مؤرشف يناير 2023) | درس: العمل بلا اتصال، ولماذا أُغلق النموذج |
| S6 | قائمة البرمجيات الصحية مفتوحة المصدر — <https://en.wikipedia.org/wiki/List_of_open-source_health_software> | خريطة الأنظمة وتقنياتها |
| S7 | HL7 FHIR — <https://hl7.org/fhir/> | نموذج الموارد والتكامل المستقبلي |
| S8 | OWASP ASVS / Cheat Sheets — <https://owasp.org/> | ضوابط أمن التطبيقات والجلسات وكلمات المرور |

> لم أعتمد على أي رقم تسويقي في قرارات التصميم؛ الأرقام أعلاه ذُكرت فقط لبيان حجم التبنّي.

---

## 2. الأنظمة المرجعية ونمط كل واحد

| النظام | النمط | التقنية | أقوى ما فيه | أضعف ما فيه (بالنسبة لنا) |
|---|---|---|---|---|
| **OpenMRS** | EMR مؤسسي قابل للتوسع | Java + MySQL + وحدات | قاموس مفاهيم (Concept Dictionary)، نماذج نماذج (Forms)، FHIR، مجتمع ضخم | ليس HIS كاملًا (لا مالية/مخزون عميق) |
| **Bahmni** | HIS متكامل On-Prem | OpenMRS + Odoo + OpenELIS + PACS | تكامل EMR + ERP + LIS + صور، تشغيل بلا إنترنت | تركيب معقّد، عدة أنظمة تحت غطاء واحد |
| **OpenEMR** | EMR/PM للعيادات | PHP + MySQL | الفوترة، بوابة المريض، الوصفات الإلكترونية | ليس مؤسسيًا متعدد الأقسام بعمق |
| **GNU Health** | منظومة صحة | Python + Tryton + PostgreSQL | نموذج صحي شامل، صحة عامة، مرونة | اعتماد على إطار Tryton، خبرة Java محدودة |
| **HospitalRun** | HIS للبيئات محدودة الموارد | React + CouchDB/PouchDB | Offline-first، UX بسيط | مؤرشف 2023، لا يصلح كأساس مؤسسي |
| **OpenELIS** | LIS | Java/React + PostgreSQL | سلسلة حيازة العينة، تكامل أجهزة، FHIR | مختبر فقط |
| **Medplum / HAPI FHIR** | منصة FHIR | TypeScript/Java | نمذجة معيارية، APIs | ليست HIS تشغيلية جاهزة |
| **ERPNext/Odoo Health** | ERP + Healthcare | Python | مالية ومخزون ناضجان | العمق السريري محدود |

**الاستنتاج الأول:** لا يوجد نظام واحد يغطي كل شيء بعمق. الأنظمة الناجحة إما **EMR عميق + تكامل** (Bahmni) أو **ERP ناضج + طبقة سريرية** (Odoo/ERPNext). مشروعك يطمح للاثنين، لذا يجب أن يكون **Modular Monolith بحدود واضحة**، لا كتلة واحدة.

---

## 3. مصفوفة القدرات

**الرموز:** `●` موجود وناضج · `◐` جزئي/يحتاج إضافة · `○` غائب

| Capability | OpenMRS | Bahmni | OpenEMR | GNU Health | OpenELIS | HospitalRun | **Smart Hospital (هدف)** |
|---|---|---|---|---|---|---|---|
| Patient registry & identity | ● | ● | ● | ● | ◐ | ● | ● + EMPI |
| Duplicate detection / merge | ◐ | ◐ | ◐ | ◐ | ○ | ○ | ● (بدرجة تشابه) |
| Scheduling & queue | ◐ | ● | ● | ◐ | ○ | ◐ | ● |
| Clinical documentation | ● | ● | ● | ● | ○ | ◐ | ● + قوالب |
| CPOE / order sets | ◐ | ● | ◐ | ◐ | ○ | ○ | ● |
| Clinical decision support | ◐ | ◐ | ◐ | ◐ | ○ | ○ | ◐ (قواعد قابلة للتهيئة) |
| Laboratory (deep) | ◐ | ● | ◐ | ◐ | ● | ○ | ● |
| Specimen chain of custody | ○ | ◐ | ○ | ○ | ● | ○ | ● |
| Radiology | ◐ | ● | ◐ | ◐ | ○ | ○ | ◐ |
| Pharmacy & dispensing | ◐ | ● | ● | ● | ○ | ◐ | ● + FEFO |
| Closed-loop med admin (BCMA) | ○ | ◐ | ○ | ○ | ○ | ○ | ◐ (Should) |
| ADT & bed management | ◐ | ● | ◐ | ● | ○ | ● | ● + حالات تنظيف |
| Billing / revenue cycle | ○ | ● | ● | ◐ | ○ | ◐ | ● (نقدي) |
| Inventory & procurement | ○ | ● | ◐ | ● | ◐ | ◐ | ● |
| HR / scheduling staff | ○ | ◐ | ○ | ● | ○ | ○ | ◐ |
| Reporting & BI | ◐ | ● | ◐ | ● | ● | ◐ | ● |
| Terminology (ICD/LOINC/SNOMED) | ● | ● | ◐ | ◐ | ● | ○ | ◐ (طبقة أكواد) |
| Interop (HL7/FHIR/DICOM) | ● | ● | ◐ | ◐ | ● | ○ | ○ v1 / ◐ تصميم |
| Offline / LAN-only operation | ◐ | ● | ◐ | ◐ | ◐ | ● | ● (LAN، لا DB محلية) |
| Audit & access control | ◐ | ◐ | ◐ | ◐ | ◐ | ○ | ● (Hash chain + Break-Glass) |
| Quality & safety (incidents/HAI) | ○ | ○ | ○ | ◐ | ○ | ○ | ◐ (تصميم) |
| Smart analytics / decision support | ○ | ◐ | ○ | ○ | ◐ | ○ | ● (4 مستويات + بطاقات) |

### ما تكشفه المصفوفة

1. **مشروعك أقوى من كل المراجع في محور التحليلات والقرارات** — لكنه كان أضعف في الطبقة السريرية الدقيقة.
2. **الفجوة الأخطر ليست ميزة غائبة، بل غياب المصطلحات (Terminology).** كل تقرير ذكي بلا أكواد موحّدة يصبح نصًا حرًا لا يُجمّع.
3. **Bahmni يثبت أن نموذج "مكوّنات متكاملة" يعمل**، لكنه يثبت أيضًا أن التركيب يصبح معقّدًا. Modular Monolith هو الوسط الصحيح.
4. **HospitalRun يثبت أن Offline-first مكلف**: نموذج CouchDB/PouchDB يتطلب حل تعارضات ومزامنة وهوية. مشروعك اختار مركزيًا — وهذا قرار صحيح، بشرط وجود **إجراءات بديلة** لا **قواعد بيانات محلية**.

---

## 4. الفجوات الحقيقية في مواصفات مشروعك (مرتبة بالأثر)

| # | الفجوة | الخطورة | لماذا مهمة | المعالجة في هذا التصميم |
|---|---|---|---|---|
| G-01 | **لا طبقة مصطلحات (ICD/LOINC/ATC/SNOMED)** | 🔴 حرجة | بدونها لا تجميع تشخيصات ولا مقارنة نتائج ولا تكامل | `code_systems`, `concept`, `concept_map` + `diagnosis.code_id`, `lab_test.loinc_code` (ADR-011) |
| G-02 | **لا فحص حساسية/تفاعل دوائي** | 🔴 حرجة | خطر سريري مباشر، وهو أساس أي نظام صيدلية | `drug_interactions`, `allergy_checks` + تحذير/منع قابل للتهيئة (FR-PHR-010) |
| G-03 | **لا Order Sets / قوالب توثيق** | 🟠 مهمة | الإدخال اليدوي المتكرر هو سبب الأخطاء والتأخير | `order_sets`, `document_templates` (FR-SYS-004) |
| G-04 | **لا تحقق هوية عند إعطاء الدواء (BCMA)** | 🔴 حرجة | إعطاء دواء لمريض خطأ من أشهر الحوادث | `medication_administrations` + باركود (FR-NUR-005) |
| G-05 | **لا سلسلة حيازة للعينة ولا TAT** | 🟠 مهمة | لا يمكن معرفة أين تأخرت العينة | `lab_sample_events` + `target_tat_minutes` (FR-LAB-004/011) |
| G-06 | **كشف التكرار بلا خوارزمية** | 🔴 حرجة | "تشابه الأسماء" ينتج أخطاء دمج كارثية | تسجيل مرجّح + عتبات + مراجعة بشرية (FR-PAT-002/003) |
| G-07 | **السرير ثنائي الحالة فقط** | 🟠 مهمة | السرير "المشغول" قد يكون قيد التنظيف أو الصيانة | `SM-BED` بخمس حالات (FR-ADT-003) |
| G-08 | **لا ملخص خروج ولا مصالحة أدوية** | 🟠 مهمة | استمرارية الرعاية بعد الخروج | `discharge_records` + `medication_reconciliation` (FR-ADT-007) |
| G-09 | **لا وحدة جودة/حوادث/عدوى** | 🟠 مهمة | إدارة المخاطر والاعتماد | تصميم `incidents`, `hai_cases` (Extended) |
| G-10 | **لا Charge Capture صريح** | 🔴 حرجة | الفاتورة اليدوية = فقد إيراد | `service_executions` → `invoice_items` (FR-BIL-001) |
| G-11 | **لا حوكمة جودة بيانات** | 🟠 مهمة | تقارير وتوقعات على بيانات ناقصة | `data_quality_checks` + لوحة (FR-ANL-006) |
| G-12 | **سجل تدقيق قابل للتعديل** | 🔴 حرجة | التدقيق الذي يُعدّل ليس تدقيقًا | Hash chain + Insert-only grants (ADR-009) |
| G-13 | **لا إدارة موافقات (Consent)** | 🟠 مهمة | خصوصية المريض ومشاركة السجل | `patient_consents` (Extended) |
| G-14 | **لا إحالات (Referrals)** | 🟡 تحسينية | استمرارية الرعاية بين الأقسام/المستشفيات | `referrals` (Extended) |
| G-15 | **لا إنذار تدهور (EWS)** | 🟠 مهمة | اكتشاف التدهور مبكرًا يقلل الوفيات | حساب من العلامات + عتبات قابلة للتهيئة (FR-NUR-006) |
| G-16 | **لا نسخ/إصدارات للمستندات الطبية** | 🔴 حرجة | التصحيح يجب ألا يمحو الأصل | `*_versions` + `amends_id` (ADR-012) |
| G-17 | **لا أهداف أداء/RPO/RTO** | 🟠 مهمة | لا يمكن قبول نظام بلا مقياس | NFR-PERF, NFR-REL-007/008 |
| G-18 | **لا استراتيجية طباعة عربية** | 🟠 مهمة | الفواتير والنتائج والتقارير ورقية في الواقع | قوالب HTML/CSS + خط مضمّن (ADR-014) |
| G-19 | **لا تصميم مراقبة تشغيلية** | 🟠 مهمة | لا نعرف أن النظام مريض قبل الشكوى | `/health`, metrics, Outbox backlog (deployment/05) |
| G-20 | **لا فصل صلاحيات بيانات (Scope)** | 🔴 حرجة | RBAC وحدها لا تمنع طبيبًا من قراءة كل المرضى | Data Scope + Field Sensitivity (requirements/06) |

---

## 5. ميزات ذكية مستوحاة من الأنظمة المرجعية

هذه ليست "إضافات تجميلية"؛ كل واحدة تعالج فجوة موثّقة أعلاه.

| # | الميزة | Inspiration | القيمة | التعقيد | المرحلة |
|---|---|---|---|---|---|
| SF-01 | **EMPI Scoring** (اسم Soundex/Metaphone + ميلاد + هاتف + هوية) | OpenMRS patient matching | يمنع تكرار السجلات | متوسط | 3 |
| SF-02 | **Concept Dictionary خفيف** (أكواد + مرادفات عربية/إنجليزية) | OpenMRS | تجميع وتقارير قابلة للمقارنة | متوسط | 3 |
| SF-03 | **Order Sets** (حزمة طلبات بضغطة) | HIS تجاري | يقلل وقت الطبيب والأخطاء | منخفض | 4 |
| SF-04 | **BCMA** (مسح مريض + دواء + دفعة) | HIS تجاري | يمنع خطأ المريض/الدواء | متوسط | 5 |
| SF-05 | **Specimen Chain-of-Custody + TAT** | OpenELIS | يحدد مكان التأخير | متوسط | 4 |
| SF-06 | **Analyzer Adapter** (ASTM/HL7) | OpenELIS | إدخال نتائج آلي لاحقًا | مرتفع | Future |
| SF-07 | **Allergy / Interaction Checks** | OpenEMR, Bahmni | سلامة الوصف | متوسط | 4 |
| SF-08 | **EWS / NEWS2 Template** | معايير سريرية منشورة | إنذار تدهور مبكر | منخفض | 5 |
| SF-09 | **Bed Board + EVS states** | HIS تجاري | إشغال حقيقي لا وهمي | منخفض | 4 |
| SF-10 | **Discharge Summary + Med Reconciliation** | HIS تجاري | استمرارية الرعاية | متوسط | 5 |
| SF-11 | **Charge Capture** | RCM تجاري | يمنع فقد الإيراد | متوسط | 5 |
| SF-12 | **Incident / HAI Reporting** | أنظمة الجودة | إدارة مخاطر | متوسط | Extended |
| SF-13 | **Data Quality Dashboard** | DHIS2 | ثقة في التحليلات | منخفض | 7 |
| SF-14 | **Tamper-evident Audit** | OWASP / أمن مؤسسي | نزاهة التدقيق | متوسط | 2 |
| SF-15 | **Break-Glass + Review** | HIS تجاري | طوارئ بلا فتح دائم | منخفض | 2 |
| SF-16 | **Consent Management** | GDPR/صحة | خصوصية | متوسط | Extended |
| SF-17 | **Referral Tracking** | HIS تجاري | استمرارية | منخفض | Extended |
| SF-18 | **OR Utilization & First-Case On-Time** | HIS تجاري | كفاءة غرف العمليات | متوسط | Extended |
| SF-19 | **Demand Forecasting** (Seasonal Naive + Moving Average + Croston) | أدبيات التنبؤ التشغيلي | تخطيط موارد | متوسط | 7 |
| SF-20 | **Robust Anomaly Detection** (Median/MAD) | إحصاء تطبيقي | اكتشاف مبكر | منخفض | 7 |
| SF-21 | **Scenario Simulation** (What-if) | إدارة تشغيلية | قرار قبل التنفيذ | متوسط | 7 |
| SF-22 | **Smart Queue** (أولوية تشغيلية قابلة للتهيئة) | HIS تجاري | تقليل انتظار | منخفض | 4 |
| SF-23 | **Inventory Intelligence** (ROP، مخاطر نفاد، صلاحية) | ERP | تقليل النقص والهدر | متوسط | 7 |
| SF-24 | **Notification Prioritization & Dedupe** | أنظمة تشغيل | تقليل ضجيج الإشعارات | منخفض | 6 |
| SF-25 | **QR Patient Wristband** | مستشفيات | تحقق سريع | منخفض | 4 |
| SF-26 | **Read-only Snapshot / Print Fallback** | درس HospitalRun | عمل عند تعطل الخادم | منخفض | 8 |
| SF-27 | **Audit Analytics** (من قرأ ماذا) | أمن مؤسسي | كشف سلوك شاذ | منخفض | 7 |
| SF-28 | **Terminology Mapping UI** | OpenMRS/Bahmni | ربط النص الحر بالأكواد | متوسط | Extended |

التفصيل الكامل مع التقييم والحراسات: `13-smart-features-catalog.md`.

---

## 6. دروس معمارية مستخلصة (وما طبّقته)

| الدرس | المصدر | التطبيق في Smart Hospital |
|---|---|---|
| قاموس مفاهيم مركزي يمنع "كل شاشة بنموذجها" | OpenMRS | `concept` + `concept_map` + ربط الجداول بأكواد |
| الفصل بين EMR وERP يقلل التعقيد لكن يضاعف التركيب | Bahmni | Modular Monolith بحدود وحدات، لا عدة منتجات |
| Offline-first يحتاج حل تعارضات وهوية | HospitalRun | لا DB محلية؛ LAN مركزي + إجراءات بديلة + Snapshot للقراءة |
| سلسلة حيازة العينة ليست ترفًا | OpenELIS | `lab_sample_events` Append-only |
| FHIR ممتاز كطبقة تكامل، سيئ كقاعدة تشغيل داخلية | HL7/Medplum | لا نفرض FHIR داخليًا؛ Adapter مستقبلي |
| التخصيص يجب أن يكون بيانات لا فروع كود | GNU Health/Odoo | `settings`, `service_catalog`, `templates` |
| التقارير الثقيلة تقتل الأنظمة | الجميع | Snapshot يومي + Async + Pagination |
| الأمن يُفرض في الخدمة لا الواجهة | OWASP | `PermissionService` + Data Scope في EJB |

---

## 7. ما رفضته ولماذا (مهم بقدر ما أضفت)

| مرفوض | السبب |
|---|---|
| Microservices / Kubernetes | مستشفى بخادم واحد، فريق صغير، تعقيد تشغيلي بلا فائدة |
| Offline-first بقاعدة محلية لكل جهاز | يتطلب مزامنة وحل تعارضات وهوية موزعة — يخالف "مصدر واحد" |
| JWT في `localStorage` | عرضة لـ XSS، ولا إبطال فوري؛ الجلسة على الخادم أنسب لنفس الأصل |
| Argon2id كمكتبة إضافية في v1 | اعتمادية إضافية؛ PBKDF2 مدمج وكافٍ مع مسار ترقية |
| تشخيص آلي / توصية جرعة | خطر سريري وقانوني؛ خارج النطاق صراحةً |
| تخزين SNOMED CT كاملًا | ترخيص وملكية؛ نستخدم أكوادًا يوفّرها المستشفى |
| بناء PACS/DICOM viewer | منتج مستقل؛ نكتفي بمعرّفات وروابط |
| WebSocket لكل شيء | SSE أبسط ويكفي للاتجاه الواحد |
| حذف السجلات الطبية | يخالف نزاهة السجل |
| تقارير SQL مباشرة من React | يخالف فصل الطبقات والأمن |
| ادعاء HIPAA/اعتماد | لا أساس قانوني؛ نذكر الضوابط لا الامتثال |

---

## 8. الخلاصة التنفيذية

1. مشروعك في المواصفات الأصلية **أقوى من المتوسط** في التغطية الوظيفية، لكنه كان **أضعف في 6 نقاط حرجة**: المصطلحات، سلامة الدواء، نزاهة السجل، سلسلة العينة، Charge Capture، ونطاق الصلاحيات.
2. أضفت لهذه النقاط حلولًا تصميمية محددة، لا وعودًا.
3. أضفت 28 ميزة ذكية، منها 12 فقط تدخل النسخة الأكاديمية، والباقي مصمّم ومؤجّل.
4. أبقيت المعمارية **بسيطة عمدًا**: EAR واحد، قاعدة واحدة، خادم واحد، وحدود واضحة.
5. النتيجة: نظام يمكن الدفاع عنه هندسيًا وأكاديميًا، ويمكن تطويره لمنتج حقيقي دون إعادة كتابة.

---

## 9. المراجع الكاملة

- OpenMRS. <https://openmrs.org/> — تمت المراجعة 2026-11-11.
- Bahmni. <https://www.bahmni.org/> — تمت المراجعة 2026-11-11.
- GNU Health. <https://www.gnuhealth.org/> — تمت المراجعة 2026-11-11.
- OpenELIS Global. <https://openelis-global.org/> — تمت المراجعة 2026-11-11.
- HospitalRun (archived). <https://github.com/HospitalRun/hospitalrun-frontend> — تمت المراجعة 2026-11-11.
- Wikipedia, List of open-source health software. <https://en.wikipedia.org/wiki/List_of_open-source_health_software>
- HL7 FHIR. <https://hl7.org/fhir/>
- OWASP. <https://owasp.org/>
