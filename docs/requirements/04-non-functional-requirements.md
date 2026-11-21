# 04 — Non-Functional Requirements (NFR)

| Field | Value |
|---|---|
| Document ID | REQ-NFR-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Rule | كل NFR له مقياس + طريقة قياس + بيئة قياس + افتراض حجم |

> **تنبيه منهجي:** الأرقام أدناه **أهداف تصميمية (Design Targets)** مبنية على الافتراضات `ASM-001` (مستشفى 200–400 سرير، ~600 مستخدم، ≤1500 زيارة/يوم) و`ASM-020` (خادم 8 vCPU / 16 GB / SSD). تُعاير بعد أول قياس فعلي ولا تُقدَّم كوعود مطلقة.

---

## 1. Security — NFR-SEC

| ID | Requirement | Metric / Target | How verified |
|---|---|---|---|
| NFR-SEC-001 | كل الطلبات عبر HTTPS في الإنتاج | 100% من الطلبات على 443؛ تحويل 80→443 | فحص إعداد + ZAP |
| NFR-SEC-002 | كلمات المرور مخزّنة بـ PBKDF2WithHmacSHA256 ≥ 600,000 تكرار + ملح عشوائي 16 بايت | 0 كلمة مرور نصية في DB | فحص DB + Unit test |
| NFR-SEC-003 | الجلسة: HttpOnly + Secure + SameSite=Strict، Rotation عند الدخول | 100% من الكوكيز مطابقة | فحص رؤوس الاستجابة |
| NFR-SEC-004 | CSRF: رفض أي طلب كتابة بلا رمز صالح | 0 نجاح بدون رمز | Security test |
| NFR-SEC-005 | التفويض يُطبّق على كل عملية backend | 100% من نقاط النهاية محمية | مصفوفة تغطية + اختبار IDOR |
| NFR-SEC-006 | حماية XSS: ترميز مخرجات + CSP بلا `unsafe-inline` في الإنتاج | 0 تنفيذ سكربت في اختبار الحقن | ZAP + مراجعة CSP |
| NFR-SEC-007 | Rate limiting على الدخول ونقاط النهاية الحساسة | ≤ 5 محاولات/دقيقة/حساب+IP | اختبار حمل |
| NFR-SEC-008 | رفع الملفات: نوع مسموح، حجم ≤ 10 MB، اسم عشوائي، تخزين خارج webroot | 0 ملف قابل للتنفيذ | اختبار رفع |
| NFR-SEC-009 | لا أسرار في المستودع | 0 سر في `git grep` | فحص آلي |
| NFR-SEC-010 | سجل تدقيق غير قابل للتعديل من التطبيق (Insert-only + Hash chain) | 0 صلاحية UPDATE/DELETE للمستخدم التطبيقي | فحص Grants |
| NFR-SEC-011 | إخفاء الحقول الحساسة حسب الصلاحية (Field masking) | 100% من الحقول المصنّفة | اختبار أدوار |
| NFR-SEC-012 | Break-Glass: مدة ≤ 60 دقيقة، سبب إلزامي، إشعار للمسؤول | 100% من الحالات مسجلة ومراجعة | اختبار + تقرير |

---

## 2. Privacy & Compliance — NFR-PRV

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-PRV-001 | مبدأ الحد الأدنى من البيانات: لا تُعرض بيانات مريض لا يحتاجها الدور | 0 حقل زائد في استجابة API لدور محدود | اختبار DTO لكل دور |
| NFR-PRV-002 | تسجيل كل وصول إلى سجل طبي (من، ماذا، متى، لماذا) | 100% من عمليات القراءة الحساسة | فحص `access_logs` |
| NFR-PRV-003 | عدم تسجيل بيانات مريض حساسة في Logs التطبيق | 0 اسم/هوية/تشخيص في Log | مراجعة Logs آليًا |
| NFR-PRV-004 | تشفير النسخ الاحتياطية | 100% من النسخ مشفرة | فحص ملف النسخة |
| NFR-PRV-005 | سياسة احتفاظ قابلة للتهيئة (مدة افتراضية مقترحة 10 سنوات سجلات طبية) | مطابقة سياسة معتمدة | مراجعة إعداد |
| NFR-PRV-006 | عدم ادعاء امتثال قانوني غير مُثبت | 0 عبارة "HIPAA compliant" في الوثائق | مراجعة نصية |

---

## 3. Reliability & Integrity — NFR-REL

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-REL-001 | لا نجاح وهمي: لا استجابة 2xx قبل Commit | 0 حالة نجاح مع Rollback | اختبار فشل DB |
| NFR-REL-002 | العملية الطبية/المالية إما كاملة أو متراجعة | 0 سجل جزئي في 20 سيناريو فشل | Chaos tests |
| NFR-REL-003 | فشل الإشعار لا يفقد العملية الأصلية | 100% من النتائج محفوظة عند تعطيل JMS | اختبار Outbox |
| NFR-REL-004 | إعادة المحاولة بفاصل أسّي حتى 5 محاولات ثم DLQ | 100% من الرسائل الفاشلة في DLQ | اختبار قسري |
| NFR-REL-005 | منع ازدواج العمليات الحساسة (دفع/صرف) | 0 تكرار في 50 طلبًا متزامنًا | Concurrency test |
| NFR-REL-006 | استمرارية العمل عند قطع الإنترنت الخارجي | 0 وظيفة أساسية تعتمد على إنترنت | Network isolation test |
| NFR-REL-007 | RPO ≤ 15 دقيقة (Binary Logs) | استعادة تفقد ≤ 15 دقيقة | Restore drill |
| NFR-REL-008 | RTO ≤ 4 ساعات للعودة للخدمة | قياس فعلي في تمرين | Restore drill |
| NFR-REL-009 | صحة النظام قابلة للفحص آليًا | `/health` يعيد DB + JMS + Disk | Monitoring test |

---

## 4. Performance — NFR-PERF

| ID | Requirement | Metric (P95) | How verified |
|---|---|---|---|
| NFR-PERF-001 | استجابة API للقراءة البسيطة | ≤ 400 ms | k6/JMeter |
| NFR-PERF-002 | استجابة API للكتابة المعقدة (صرف/دفع) | ≤ 1200 ms | Load test |
| NFR-PERF-003 | قائمة مرضى (100k سجل) مع بحث وPagination | ≤ 800 ms | DB benchmark |
| NFR-PERF-004 | لوحة مؤشرات إدارية (Snapshot) | ≤ 2 s | Load test |
| NFR-PERF-005 | تحميل الواجهة الأولى (LAN، بلا CDN) | ≤ 3 s على 10 Mbps | Lighthouse/Throttle |
| NFR-PERF-006 | حجم حزمة JS الأولية | ≤ 450 KB gzip | Build report |
| NFR-PERF-007 | 200 مستخدم متزامن بلا أخطاء 5xx | 0 خطأ، CPU < 75% | Load test |
| NFR-PERF-008 | تقرير ثقيل لا يحجب الواجهة | Async + Progress/Polling | E2E test |
| NFR-PERF-009 | لا استعلام بلا Pagination على جداول > 10k | 0 نقطة نهاية ترجع كل السجلات | مراجعة API |
| NFR-PERF-010 | Connection Pool: 20–50 اتصالًا، Timeout 30s، Leak detection | 0 تسريب في 24h | Soak test |

---

## 5. Scalability — NFR-SCL

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-SCL-001 | نمو البيانات 5 سنوات بلا إعادة تصميم | 10M encounters, 50M audit rows | حجم تقديري + Partitioning |
| NFR-SCL-002 | فصل App/DB ممكن دون تغيير كود | تغيير Host في JNDI فقط | Deployment test |
| NFR-SCL-003 | إضافة خادم تطبيق ثانٍ ممكنة لاحقًا | جلسات قابلة للمشاركة أو Sticky | ADR + تصميم |
| NFR-SCL-004 | إضافة فرع جديد دون Migration بنيوي | صف جديد في `branches` | Functional test |
| NFR-SCL-005 | الترقية بنسخة EAR جديدة دون إعادة تثبيت الأجهزة | 0 تغيير على العميل | Deployment test |

---

## 6. Maintainability — NFR-MNT

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-MNT-001 | فصل الطبقات: لا JPA في WAR، لا SQL في React | 0 مخالفة | ArchUnit-like review |
| NFR-MNT-002 | حدود وحدات: لا وصول لحزمة داخلية من وحدة أخرى | 0 اعتماد مباشر غير مصرح | Dependency report |
| NFR-MNT-003 | تغطية اختبارات الخدمات الحرجة | ≥ 70% للخدمات المالية/الطبية | JaCoCo |
| NFR-MNT-004 | كل جدول/عمود موثّق في Data Dictionary | 100% | مراجعة آلية |
| NFR-MNT-005 | كل قرار معماري له ADR | 100% من القرارات المؤثرة | مراجعة |
| NFR-MNT-006 | رسائل خطأ مفهومة بالعربية والإنجليزية مع كود | 100% من الأخطاء | مراجعة API |
| NFR-MNT-007 | لا تكرار منطق أعمال بين WAR وEJB | 0 نسخة ثانية من القاعدة | مراجعة كود |
| NFR-MNT-008 | زمن بناء كامل (Frontend + Ant EAR) | ≤ 5 دقائق | CI timing |

---

## 7. Usability & Accessibility — NFR-USA / NFR-A11Y

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-USA-001 | العربية RTL افتراضيًا، والإنجليزية LTR كاملة | 0 عنصر مكسور الاتجاه | Visual test |
| NFR-USA-002 | كل شاشة تعمل على 1366×768 فأعلى | 0 تمرير أفقي غير مقصود | Responsive test |
| NFR-USA-003 | حالات: Loading / Empty / Error / Success لكل شاشة بيانات | 100% من الشاشات | UX review |
| NFR-USA-004 | البحث والتصفية والفرز في كل جدول رئيسي | 100% | Functional test |
| NFR-USA-005 | إدخال مريض جديد ≤ 2 دقيقة لمستخدم مدرّب | قياس 5 مستخدمين | Usability test |
| NFR-USA-006 | أخطاء النموذج تظهر عند الحقل مع رسالة عربية | 100% من الحقول الإلزامية | UX review |
| NFR-A11Y-001 | تنقل كامل بلوحة المفاتيح | 0 عنصر غير قابل للوصول | Keyboard test |
| NFR-A11Y-002 | تباين ألوان WCAG AA (≥ 4.5:1 للنص) | 100% من النصوص | Axe |
| NFR-A11Y-003 | تسميات ARIA للحقول والأزرار الأيقونية | 100% | Axe |
| NFR-A11Y-004 | عدم الاعتماد على اللون وحده لنقل الحالة | 100% من مؤشرات الحالة | Review |

---

## 8. Operability — NFR-OPS

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-OPS-001 | نشر/تحديث EAR موثّق وقابل للتكرار | ≤ 30 دقيقة | Deployment drill |
| NFR-OPS-002 | سجلات منظمة (JSON أو نمط موحد) مع Trace ID | 100% من الطلبات | Log review |
| NFR-OPS-003 | فصل سجلات: Application / Security / Medical Audit | 3 مسارات منفصلة | Config review |
| NFR-OPS-004 | مراقبة: DB، JMS، Disk، Pool، Outbox backlog | 100% من المؤشرات | Dashboard |
| NFR-OPS-005 | نسخ احتياطي يومي كامل + Binary Logs كل 15 دقيقة | 100% من الأيام | Backup report |
| NFR-OPS-006 | تمرين استعادة موثّق كل 3 أشهر | 4 مرات/سنة | Restore log |
| NFR-OPS-007 | لا يتطلب العميل أي تثبيت | 0 برنامج على جهاز القسم | Client test |

---

## 9. Portability & Configurability — NFR-PRT

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-PRT-001 | 0 قيمة خاصة بمستشفى في الكود (اسم/شعار/سعر/قسم) | 0 نتيجة في فحص القيم الثابتة | Code scan |
| NFR-PRT-002 | كل إعدادات البيئة عبر JNDI/متغيرات/ملف إعداد خارج EAR | 100% | Config review |
| NFR-PRT-003 | نفس EAR يعمل في مستشفى آخر بعد Migrations + إعدادات | نشر ثانٍ ناجح | Deployment test |
| NFR-PRT-004 | العملة واللغة والتوقيت قابلة للتهيئة | 2 عملات/لغتين في اختبار | Functional test |
| NFR-PRT-005 | أسماء الأقسام والخدمات والأسعار كلها بيانات لا كود | 100% | Review |

---

## 10. Interoperability Readiness — NFR-INT

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-INT-001 | لا منطق أعمال يعتمد مباشرة على نظام خارجي | 0 استدعاء خارجي داخل Domain Services | Review |
| NFR-INT-002 | كل تكامل عبر Adapter + سجل رسائل | 100% | Design review |
| NFR-INT-003 | حقول معرفات خارجية موجودة (FHIR id / Study UID) | موجودة في المخطط | DB review |
| NFR-INT-004 | لا ادعاء توافق قبل اختبار رسمي | 0 ادعاء | Doc review |

---

## 11. Data Quality — NFR-DAT

| ID | Requirement | Metric | How verified |
|---|---|---|---|
| NFR-DAT-001 | اكتمال الحقول الإلزامية في السجلات الطبية | ≥ 95% | Data Quality dashboard |
| NFR-DAT-002 | 0 سجل زيارة بلا مريض أو طبيب | 0 | DB constraint |
| NFR-DAT-003 | 0 تاريخ ميلاد في المستقبل أو تاريخ زيارة قبل الميلاد | 0 | Check constraint + test |
| NFR-DAT-004 | 0 صرف بكمية سالبة أو دفعة منتهية | 0 | Constraint + audit |
| NFR-DAT-005 | 0 فاتورة برصيد سالب غير مبرر | 0 | Reconciliation query |
| NFR-DAT-006 | 0 سرير مخصص لمريضين في وقت واحد | 0 | Unique + concurrency test |

---

## 12. مصفوفة التحقق (كيف تُختبر هذه المتطلبات)

| NFR group | Test type | Phase |
|---|---|---|
| SEC / PRV | Security testing + code review + DB grants | 2, 9 |
| REL | Fault injection + restore drills | 4, 6, 9 |
| PERF | Load/Soak with seeded data | 9 |
| SCL | Data volume simulation | 9 |
| MNT | Static review + coverage | كل مرحلة |
| USA / A11Y | UX review + Axe + keyboard | 3–9 |
| OPS | Deployment + monitoring drill | 8–10 |
| PRT | Second-environment deployment | 10 |
| DAT | Data quality queries + constraints | 3–9 |

---

## 13. الأرقام التي لم أضعها (وسبب ذلك)

| لم أضع | السبب | متى تُحدد |
|---|---|---|
| نسبة توفر 99.9% | تتطلب HA غير موجودة في v1 | بعد قرار PD-011 |
| زمن استجابة < 100ms | غير واقعي مع JTA + JPA + تقارير | بعد قياس فعلي |
| حد أقصى دقيق للمستخدمين | يعتمد على الخادم والاستعلامات | بعد Load test |
| مدة احتفاظ قانونية | تعتمد على jurisdiction | بعد قرار PD-012 |
| متطلبات MFA إلزامية | سياسة مؤسسية | بعد قرار PD-008 |
