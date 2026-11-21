# 02 — Software Requirements Specification (SRS)

| Field | Value |
|---|---|
| Document ID | REQ-SRS-001 |
| Standard reference | Structure inspired by IEEE 830 / ISO/IEC/IEEE 29148 (not a compliance claim) |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |

---

## 1. الغرض والنطاق

يحدد هذا المستند الإطار الكامل لمتطلبات **Smart Hospital**، ويجمع في مكان واحد:

- حدود النظام ومن هو خارج حدوده.
- الفاعلون وأدوارهم.
- القيود والاعتماديات.
- بنية المتطلبات وعلاقاتها.
- سياسة التغيير والتتبّع.

التفاصيل الكمية موجودة في ملفات مستقلة (FR / NFR / BR / RBAC / UC / Workflows / State Machines) لتجنّب تكرار غير محكوم.

---

## 2. حدود النظام (System Boundary)

```
                    ┌──────────────────────────────────────────┐
  Patients/Staff →  │  Browser (React SPA)                     │
                    └──────────────────┬───────────────────────┘
                                       │ HTTPS
                    ┌──────────────────▼───────────────────────┐
                    │  Smart Hospital System                    │
                    │  REST API · EJB · JPA · JMS · Scheduler   │
                    │  Audit · Notifications · Analytics        │
                    └───┬───────────────┬──────────────┬───────┘
                        │               │              │
                   MySQL (central)   JMS (internal)   File store
                        │
              ┌─────────▼──────────┐
              │ External (Future)  │  HL7/FHIR · DICOM/PACS · SMS/Email · LDAP/AD
              └────────────────────┘
```

**داخل الحدود:** كل منطق الأعمال، التحقق، الصلاحيات، التدقيق، الإشعارات، التحليلات، تخزين الملفات، إدارة الإعدادات.

**خارج الحدود (v1):** أنظمة التأمين، المختبرات الخارجية، PACS، بوابة المريض، الأجهزة الطبية، أنظمة الرواتب.

---

## 3. الفاعلون (Actors)

### 3.1 فاعلون بشريون

| Actor ID | Actor | الوصف | تفاعل أساسي |
|---|---|---|---|
| ACT-01 | Receptionist | تسجيل المرضى، حجز المواعيد، تسجيل الوصول | Patient, Appointment |
| ACT-02 | Doctor | زيارة، تشخيص، طلبات، وصفات، اعتماد | Encounter, Orders |
| ACT-03 | Nurse | علامات حيوية، مهام، إعطاء دواء | Nursing, Vitals |
| ACT-04 | Lab Technician | استلام عينة، إدخال نتيجة | Lab |
| ACT-05 | Lab Supervisor | مراجعة واعتماد النتائج | Lab |
| ACT-06 | Radiologist | تقرير أشعة واعتماده | Radiology |
| ACT-07 | Pharmacist | مراجعة وصفة، صرف، مخزون | Pharmacy |
| ACT-08 | Cashier | فاتورة، دفع، استرداد | Billing |
| ACT-09 | Finance Manager | تقارير مالية، إقفال، تسويات | Billing, Reports |
| ACT-10 | Storekeeper | استلام، صرف، جرد | Inventory |
| ACT-11 | HR Officer | موظفون، مناوبات، تراخيص | HR |
| ACT-12 | Department Manager | مؤشرات قسمه، قرارات تشغيلية | Analytics |
| ACT-13 | Hospital Administrator | إعدادات، مستخدمون، سياسات | IAM, SYS |
| ACT-14 | IT Administrator | تشغيل، مراقبة، نسخ احتياطي | Ops |
| ACT-15 | Privacy/Audit Officer | مراجعة وصول وتدقيق | Audit, Break-Glass |
| ACT-16 | Patient | (v1 محدود) استلام إيصال/تعليمات | Billing print |

### 3.2 فاعلون غير بشريين

| Actor ID | Actor | الوصف |
|---|---|---|
| ACT-S1 | Scheduler (EJB Timer) | مهام دورية: Snapshots، Outbox، تنبيهات |
| ACT-S2 | Outbox Dispatcher | نشر الأحداث إلى JMS |
| ACT-S3 | Notification Consumer (MDB) | إنشاء إشعارات وإرسالها |
| ACT-S4 | Analytics Engine | حساب KPIs وكشف الشذوذ وتوليد بطاقات القرار |
| ACT-S5 | External System (Future) | تكامل HL7/FHIR/DICOM |

---

## 4. بنية المتطلبات

```
Vision (01)
  └── FR-* (03)  ← Functional Requirements
        ├── BR-* (05)  ← Business Rules
        ├── UC-* (07)  ← Use Cases
        │     └── WF-* (workflows/01)
        │           └── SM-* (workflows/02)
        ├── API (architecture/04)
        ├── TBL (database/02)
        └── TC-* (testing/02)

NFR-* (04) ← Non-functional, applies across all FR
RBAC (06)  ← Who may execute each FR
KPI-* (analytics/01) ← Measurement of outcomes
```

**قاعدة:** لا يوجد FR بلا: (1) صلاحية، (2) قاعدة عمل أو أكثر، (3) اختبار قبول واحد على الأقل. أي FR بلا هذه الثلاثة يُعتبر غير مكتمل.

---

## 5. القيود والاعتماديات

### 5.1 قيود تقنية (Tech Constraints)

| ID | Constraint | Verification |
|---|---|---|
| TECH-01 | JDK 17 فقط | `javac.source=17` في Ant، لا استخدام APIs أحدث |
| TECH-02 | Jakarta EE 10 APIs فقط (`jakarta.*`) | فحص imports آلي |
| TECH-03 | لا Spring / لا Microservices | مراجعة اعتماديات `lib/` |
| TECH-04 | EAR على GlassFish 7 Full | نشر فعلي على بيئة نظيفة |
| TECH-05 | Ant build | `ant -f build.xml` ينتج EAR |
| TECH-06 | MySQL 8.0.38 | `SELECT VERSION()` في اختبار التكامل |
| TECH-07 | React 19 + TS + Vite | `npm run build` ينتج أصولًا تُنسخ إلى WAR |
| TECH-08 | لا اتصال مباشر من React إلى DB | لا MySQL driver في `package.json` |
| TECH-09 | لا أسرار في الكود | فحص `git grep` + مراجعة |
| TECH-10 | JNDI DataSource | `persistence.xml` يستخدم `java:app/jdbc/smartHospitalDS` |

### 5.2 اعتماديات خارجية

| Dependency | Version | الاستخدام | إن فُقد |
|---|---|---|---|
| GlassFish | 7 Full Platform | تشغيل EAR + JMS + Timer | لا يعمل النظام |
| MySQL | 8.0.38 | البيانات | لا يعمل النظام |
| MySQL Connector/J | 8.x متوافق JDK17 | JDBC driver | لا اتصال |
| JDK | 17 LTS | تشغيل | لا يعمل |
| Node.js + npm | إصدار بيئة التطوير | بناء الواجهة فقط | لا يؤثر على الإنتاج |
| OpenMQ (embedded) | ضمن GlassFish | JMS | تعمل العمليات، تتوقف الإشعارات غير المتزامنة (Outbox يحتفظ) |

---

## 6. الافتراضات المرجعية

كل افتراض مؤثر مسجّل في `08-assumptions-register.md` بمعرّف `ASM-*` وأثر ومهلة تحقق.
أمثلة حاكمة:

- `ASM-001`: مستشفى واحد، ~200–400 سرير، ~600 مستخدم، ~1500 زيارة/يوم كحد أقصى.
- `ASM-004`: الشبكة المحلية مستقرة، والخادم في غرفة تحكم مع UPS.
- `ASM-009`: لا توجد بيانات مرضى حقيقية للاستيراد.
- `ASM-014`: لا توجد متطلبات امتثال قانوني محددة معلنة (تحتاج تأكيد المالك).

---

## 7. المتطلبات الوظيفية — قواعد الصياغة

كل FR يُكتب بالشكل:

```
FR-<DOMAIN>-<NNN>
  Title: ...
  Priority: Must | Should | Could (MoSCoW)
  Actor(s): ACT-xx
  Description: ...
  Rules: BR-xx, BR-yy
  Data: TBL...
  API: METHOD /path
  Acceptance: TC-xxx
  Notes/Limitations: ...
```

قواعد إلزامية:

1. لا FR بصيغة "النظام يجب أن يكون سهلًا" — غير قابل للاختبار.
2. كل FR له **نتيجة قابلة للملاحظة**.
3. كل عملية كتابة لها **صلاحية** و**قاعدة تحقق** و**أثر تدقيق**.
4. كل عملية مالية أو مخزنية لها **معاملة** و**سلوك عند الفشل**.
5. كل عملية طبية معتمدة لها **سياسة تصحيح** لا حذف.

---

## 8. المتطلبات غير الوظيفية — قواعد الصياغة

كل NFR يجب أن يكون:

- **قابلًا للقياس** (رقم + ظرف + طريقة قياس).
- **مرتبطًا بافتراض حجم** (لا رقم مطلق بلا سياق).
- **قابلًا للاختبار** في بيئة محددة (Dev / Test / Prod-like).

مثال مقبول: "زمن استجابة P95 لاستعلام قائمة مرضى بحجم 100 ألف سجل ≤ 800ms على خادم 8 vCPU / 16GB وبعد تسخين Pool."
مثال مرفوض: "يجب أن يكون النظام سريعًا."

---

## 9. إدارة التغيير (Change Control)

| نوع التغيير | الإجراء المطلوب | الموافقة |
|---|---|---|
| إضافة FR جديد | تحديث 03 + BR + UC + TC + Roadmap | مالك المشروع |
| تغيير قاعدة عمل | تحديث 05 + اختبارات متأثرة | مالك + مسؤول طبي إن كان سريريًا |
| تغيير جدول/عمود | Migration جديدة + تحديث ERD/Dictionary | مهندس البيانات |
| تغيير معمارية | ADR جديد + تحديث 01/02 + خطة ترحيل | مالك + معماري |
| تغيير تقنية معتمدة | ADR + تقييم أثر + موافقة صريحة | مالك المشروع |
| إصلاح خطأ بعد الاعتماد | Bugfix + اختبار انحدار | قائد التطوير |

**لا يُقبل Pull/Merge فيه تغيير سلوك بلا تحديث وثيقة واختبار.**

---

## 10. معايير القبول العامة (System-Level)

النظام يُقبل عندما تتحقق كل المجموعات:

| Gate | المعيار |
|---|---|
| G-01 Requirements | 100% من FR-Must لها TC ومربوطة بصلاحية |
| G-02 Architecture | النشر على بيئة نظيفة ينجح بـ EAR واحد |
| G-03 Data | كل جدول له PK وFK صحيحة وقاموس بيانات |
| G-04 Security | 0 وصول غير مصرح في اختبارات Security |
| G-05 Concurrency | 0 تعارض في 8 سيناريوهات تزامن |
| G-06 Reliability | 0 فقد بيانات في اختبار فشل الإشعار/DB |
| G-07 Performance | تحقيق NFR-PERF في بيئة Prod-like |
| G-08 Deployment | تركيب كامل من الصفر خلال ≤ 1 يوم عمل |
| G-09 Backup | استعادة ناجحة ومُتحقَّق منها |
| G-10 Documentation | كل وثيقة محدثة ومطابقة للسلوك الفعلي |

التفصيل والاختبارات: `testing/01-test-plan.md`, `testing/02-acceptance-criteria.md`.

---

## 11. الافتراضات المتعلقة بالمعايير الطبية (مهم)

النظام **لا يفرض** بروتوكولًا طبيًا. كل ما يلي يُنفّذ كـ **قالب/إعداد يعتمده المستشفى**:

| المجال | ما يوفره النظام | ما لا يقدمه |
|---|---|---|
| الفرز (Triage) | مستويات قابلة للتهيئة + أوقات مستهدفة | بروتوكول طبي جاهز |
| NEWS2 / MEWS | حساب من العلامات الحيوية + عتبات قابلة للتعديل | تشخيص أو قرار علاجي |
| التفاعلات الدوائية | تحقق بقواعد قابلة للتهيئة | قاعدة دوائية كاملة معتمدة |
| الجرعات | تحقق نطاقي عام | توصية جرعة |
| قوائم السلامة الجراحية | Checklist قابلة للتهيئة | ادعاء اعتماد WHO |
| المصطلحات | دعم أكواد ICD/LOINC/ATC كجداول إعداد | تضمين محتوى مرخّص دون ترخيص |

هذا الفصل مقصود لحماية المشروع من ادعاءات غير صحيحة ومن مسؤولية سريرية لا يملكها.

---

## 12. خريطة التتبّع (نموذج مصغّر)

| FR | BR | UC | API | Tables | Perm | KPI | TC |
|---|---|---|---|---|---|---|---|
| FR-PAT-001 تسجيل مريض | BR-PAT-001, BR-PAT-002 | UC-PAT-001 | POST /patients | `patients`, `patient_identifiers` | PERM-PAT-CREATE | KPI-001 | TC-011 |
| FR-SCH-003 حجز موعد | BR-SCH-001..003 | UC-SCH-001 | POST /appointments | `appointments`, `doctor_schedules` | PERM-SCH-BOOK | KPI-006 | TC-018 |
| FR-LAB-006 اعتماد نتيجة | BR-LAB-004, BR-LAB-005 | UC-LAB-003 | POST /lab-orders/{id}/results/{rid}/verify | `lab_results`, `lab_result_versions` | PERM-LAB-VERIFY | KPI-014 | TC-026 |
| FR-PHR-004 صرف FEFO | BR-PHR-001..004 | UC-PHR-002 | POST /prescriptions/{id}/dispense | `dispensing_transactions`, `stock_movements`, `drug_batches` | PERM-PHR-DISPENSE | KPI-021 | TC-031 |
| FR-ADT-004 تخصيص سرير | BR-ADT-002 | UC-ADT-002 | POST /admissions/{id}/bed-assignments | `bed_assignments` | PERM-ADT-ASSIGN | KPI-017 | TC-037 |
| FR-BIL-004 تسجيل دفعة | BR-BIL-003, BR-BIL-004 | UC-BIL-002 | POST /invoices/{id}/payments | `payments`, `invoices` | PERM-BIL-PAY | KPI-024 | TC-043 |

الخريطة الكاملة تُبنى تلقائيًا في Phase 2 من تعليقات الكود + هذه الجداول.

---

## 13. ما هو خارج هذا المستند

- تفاصيل ERD والحقول: `database/*`.
- تفاصيل نقاط النهاية: `architecture/04-api-contracts.md`.
- تفاصيل الشبكة والتركيب: `deployment/*`.
- تفاصيل KPIs: `analytics/01-kpi-catalog.md`.
