# Smart Hospital — Documentation Index

**Enterprise Hospital Management, Intelligent Operations & Decision Support System**

| Field | Value |
|---|---|
| Document set version | 0.1 (Approved Design Baseline) |
| Date | 2026-11-11 · Approved 2026-11-12 |
| Status | **Approved — Phase 6 (Notifications + Outbox + SSE) complete, G6 passed 2026-11-17** |
| Project root | هذا المجلد الحالي (يحتوي `docs/`) — لا يوجد مجلد `SmartHospital/` منفصل |
| Architecture | Modular Monolith · Jakarta EE 10 · EAR (EJB + WAR) · React 19 SPA · MySQL 8.0.38 |
| Language of docs | Arabic prose + English technical identifiers |
| Owner | Project owner (صاحب المشروع) |
| Prepared by | Engineering team (Architecture / BA / Healthcare Systems / Java EE / React / DBA / Security / Network / UX / BI / QA / DevOps) |

---

## 1. كيف تُقرأ هذه الوثائق

هذه المجموعة ليست شرحًا نظريًا، بل **خط أساس تصميمي (Design Baseline)** قابل للتنفيذ والاختبار.
كل متطلب وكل قرار وكل جدول وكل اختبار له معرّف ثابت، وكل معرّف يُستخدم في بقية الوثائق.

ترتيب القراءة المقترح:

1. `EXECUTIVE-SUMMARY.md` — الملخص التنفيذي والقرارات المطلوبة منك.
2. `requirements/01-vision-scope.md` — الرؤية والنطاق.
3. `requirements/12-benchmark-gap-analysis.md` — ماذا ينقص مشروعك مقارنة بالأنظمة العالمية.
4. `requirements/13-smart-features-catalog.md` — الميزات الذكية المقترحة والأولوية.
5. `requirements/02-srs.md` ثم `03` و`04` و`05` و`06`.
6. `architecture/01..08` — المعمارية والعقود والأمن.
7. `database/01..04` — ERD وقاموس البيانات.
8. `analytics/01..04` — KPIs ومحرك القرار.
9. `deployment/01..06` — الخادم المركزي والشبكة والتركيب والنسخ الاحتياطي.
10. `testing/01..02` — خطة الاختبار ومعايير القبول.
11. `roadmap/01` و`roadmap/02` — خطة التنفيذ ومذكرة الإصدار.
12. `requirements/14-pending-decisions.md` — **القرارات التي أحتاجها منك للاعتماد**.

---

## 2. فهرس الملفات

### Requirements
| # | File | المحتوى |
|---|---|---|
| 01 | `requirements/01-vision-scope.md` | الرؤية، المشكلة، الأهداف، النطاق، أصحاب المصلحة، معايير النجاح |
| 02 | `requirements/02-srs.md` | SRS: البنية، الفاعلون، القيود، التتبّع، إدارة التغيير |
| 03 | `requirements/03-functional-requirements.md` | المتطلبات الوظيفية FR-* لكل وحدة |
| 04 | `requirements/04-non-functional-requirements.md` | المتطلبات غير الوظيفية NFR-* بمقاييس قابلة للقياس |
| 05 | `requirements/05-business-rules.md` | كتالوج قواعد العمل BR-* |
| 06 | `requirements/06-rbac-matrix.md` | الأدوار والصلاحيات ومصفوفة الوصول |
| 07 | `requirements/07-use-cases.md` | حالات الاستخدام UC-* |
| 08 | `requirements/08-assumptions-register.md` | سجل الافتراضات ASM-* |
| 09 | `requirements/09-decision-register.md` | سجل القرارات DEC-* (فهرس ADR) |
| 10 | `requirements/10-risk-register.md` | سجل المخاطر RSK-* |
| 11 | `requirements/11-scope-classification.md` | Core / Extended / Future + MoSCoW |
| 12 | `requirements/12-benchmark-gap-analysis.md` | تحليل مرجعي مقارن + فجوات + مصادر |
| 13 | `requirements/13-smart-features-catalog.md` | كتالوج الميزات الذكية المقترحة |
| 14 | `requirements/14-pending-decisions.md` | قرارات معلّقة تنتظر اعتمادك |

### Workflows
| # | File | المحتوى |
|---|---|---|
| 01 | `workflows/01-clinical-workflows.md` | مسارات العمل WF-* خطوة بخطوة |
| 02 | `workflows/02-state-machines.md` | دورات الحالة SM-* والانتقالات الممنوعة |

### Architecture
| # | File | المحتوى |
|---|---|---|
| 01 | `architecture/01-system-architecture.md` | المعمارية الكلية، الوحدات، الاعتماديات، مخطط النشر |
| 02 | `architecture/02-backend-architecture.md` | EJB / JPA / الحزم / الحدود بين الوحدات |
| 03 | `architecture/03-frontend-architecture.md` | React، الحالة، التوجيه، التصميم، RTL |
| 04 | `architecture/04-api-contracts.md` | عقود REST، الأخطاء، الصفحات، Idempotency |
| 05 | `architecture/05-transactions-concurrency.md` | استراتيجية JTA والقفل والتزامن |
| 06 | `architecture/06-security-architecture.md` | المصادقة، التفويض، الجلسات، التهديدات |
| 07 | `architecture/07-notification-architecture.md` | Outbox / JMS / MDB / SSE / إعادة المحاولة |
| 08 | `architecture/08-adr.md` | ADR-001..ADR-020 |

### Database
| # | File | المحتوى |
|---|---|---|
| 01 | `database/01-erd.md` | ERD لكل نطاق + الخريطة العامة |
| 02 | `database/02-data-dictionary.md` | قاموس البيانات |
| 03 | `database/03-schema-constraints-indexes.md` | القيود، الفهارس، قواعد السلامة |
| 04 | `database/04-migrations-seed.md` | الترحيلات، الإصدارات، البيانات التجريبية |

### Analytics & Decisions
| # | File | المحتوى |
|---|---|---|
| 01 | `analytics/01-kpi-catalog.md` | KPI-001..KPI-036 بتعريفات حسابية |
| 02 | `analytics/02-analytics-engine.md` | Descriptive / Diagnostic / Predictive / Prescriptive |
| 03 | `analytics/03-decision-card-model.md` | نموذج بطاقة القرار ودورة حياتها |
| 04 | `analytics/04-ai-guardrails.md` | حدود الذكاء الاصطناعي والامتثال |

### Deployment & Operations
| # | File | المحتوى |
|---|---|---|
| 01 | `deployment/01-server-architecture.md` | الخادم المركزي، الأحجام، الخدمات |
| 02 | `deployment/02-network-topology.md` | الطوبولوجيا، VLAN، IP Plan، Firewall |
| 03 | `deployment/03-installation.md` | دليل التركيب خطوة بخطوة |
| 04 | `deployment/04-backup-recovery.md` | النسخ الاحتياطي، RPO/RTO، الاستعادة |
| 05 | `deployment/05-monitoring-operations.md` | المراقبة، السجلات، الصيانة |
| 06 | `deployment/06-portability-configuration.md` | نقل النظام لمستشفى آخر دون تعديل كود |

### Testing
| # | File | المحتوى |
|---|---|---|
| 01 | `testing/01-test-plan.md` | خطة الاختبار بكل المستويات |
| 02 | `testing/02-acceptance-criteria.md` | 190 حالة قبول TC-001..TC-190 |
| 03 | `testing/03-g3-evidence.md` | دليل اجتياز G3: 44 فحصًا، UAT-01، أداء 100k، جولة الواجهة |
| 04 | `testing/04-g4-evidence.md` | دليل اجتياز G4: 89/89، التزامن C1/C2/C5/C6/C7، Outbox، تقرير عربي RTL |
| 04b | `testing/04-g4-run.log` | سجل تشغيل انحدار Phase 4 الكامل (run `1115004921`) |
| 05 | `testing/05-g5-evidence.md` | دليل اجتياز G5: 39/39، التزامن C3/C4/C8/C9، معادلة الرصيد، لا مخزون سالب، جولة الواجهة |
| 05b | `testing/05-g5-run.log` | سجل تشغيل انحدار Phase 5 الكامل (run `1117164150`) |
| 06 | `testing/06-phase6-evidence.md` | دليل Phase 6: 20/20 بما فيها TC-176، المطالبة الذرية حسب الأولوية، JMS/MDB/DLQ، DEAD/Retry، القوالب، التفضيلات، SSE، المراقبة، عيوب وإصلاحات، Design v5 |
| 06b | `testing/06-phase6-run.log` | سجل تشغيل مجموعة Phase 6 (2026-11-17) |
| 06c | `testing/06-phase6-load.log` | سجل حِمل 1,000 حدث قبل JMS: 1000/1000 بلا فقد أو تكرار (204 ثانية) |
| 06d | `testing/06-phase6-load-jms.log` | سجل حِمل 1,000 حدث عبر مسار JMS بعد إضافة الـMDB |
| 06e | `testing/06-phase6-regression-p4-p5.log` | سجل انحدار Phase 4 (**89/89**) وPhase 5 (**39/39**) على بناء JMS النهائي |

### Roadmap & Release
| # | File | المحتوى |
|---|---|---|
| 01 | `roadmap/01-implementation-roadmap.md` | 11 مرحلة، المخرجات، بوابات الجودة |
| 02 | `roadmap/02-release-notes-v0.1.md` | نطاق الإصدار، القيود، حزمة التسليم |

---

## 3. اصطلاحات المعرّفات (ID Conventions)

| النوع | الصيغة | مثال | يُستخدم في |
|---|---|---|---|
| Functional Requirement | `FR-<DOMAIN>-<NNN>` | `FR-PHR-004` | SRS, UC, TC, Roadmap |
| Non-Functional Requirement | `NFR-<CAT>-<NNN>` | `NFR-SEC-003` | Test Plan, Deployment |
| Business Rule | `BR-<DOMAIN>-<NNN>` | `BR-BIL-002` | FR, State Machines |
| Use Case | `UC-<DOMAIN>-<NNN>` | `UC-LAB-002` | Workflows, TC |
| Workflow | `WF-<NN>` | `WF-07` | UC, State Machines |
| State Machine | `SM-<ENTITY>` | `SM-LAB-ORDER` | Workflows |
| Architecture Decision | `ADR-<NNN>` | `ADR-007` | Decision Register |
| Assumption | `ASM-<NNN>` | `ASM-012` | Risk Register |
| Risk | `RSK-<NNN>` | `RSK-004` | Roadmap |
| Pending Decision | `PD-<NNN>` | `PD-003` | Pending Decisions |
| Table | `snake_case` جمع | `lab_result_versions` | ERD, Dictionary |
| KPI | `KPI-<NNN>` | `KPI-011` | Decision Cards |
| Test Case | `TC-<NNN>` | `TC-042` | Acceptance |
| Decision Rule | `DR-<NNN>` | `DR-003` | Analytics |

**رموز النطاقات (DOMAIN):** `SYS` منصة/إعدادات · `IAM` هوية وصلاحيات · `PAT` مرضى · `SCH` مواعيد واستقبال · `EMR` سجل طبي · `ER` طوارئ وفرز · `ADT` تنويم وأسرة · `NUR` تمريض · `LAB` مختبر · `RAD` أشعة · `PHR` صيدلية · `ORS` غرف عمليات · `BIL` فواتير ومالية · `INV` مخزون ومشتريات · `HRM` موارد بشرية · `NOT` إشعارات · `RPT` تقارير · `ANL` تحليلات · `DEC` دعم قرار · `INT` تكامل.

---

## 4. حالة الوثائق (Document Status)

| المجموعة | الحالة | ملاحظة |
|---|---|---|
| Requirements | ✅ Approved (baseline 0.1) | PD-001..012 = A · MVP B: 122 FR / 94 Must |
| Workflows | ✅ Approved (baseline 0.1) | WF-* وSM-* ملزمة للتنفيذ |
| Architecture | ✅ Approved (baseline 0.1) | ADR-001..020 معتمدة · DEC-021..036 مقبولة |
| Database | ✅ Approved + Implemented | `V001`..`V011` مطبّقة · `schema_version` = V011 · 101 جدولًا · 114 صلاحية · 494 ربط دور |
| Analytics | ✅ Approved (baseline 0.1) | KPI + Decision Model (تنفيذها في Phase 7) |
| Deployment | ✅ Approved (baseline 0.1) | PD-001 = Ubuntu Server 24.04 LTS |
| Testing | ✅ Approved + Run | 190 TC؛ المرحلة 2: TC-007..016 + TC-001/006 + TC-179/181/182/185؛ المرحلة 3: TC-017..050 (44/44)؛ المرحلة 4: TC-051..101 + TC-167/168/171/172/173 (89/89 · 4 SKIP)؛ المرحلة 5: TC-108..127 + TC-169/170/174/175 (39/39)؛ المرحلة 6: TC-134..139 + C10 + TC-176 (20/20) + حِمل 1,000 حدث (1000/1000) |
| Roadmap | ✅ Approved + Phase 0..6 | G0/G1/G2 (2026-11-12) وG3 (2026-11-13) وG4 (2026-11-15) وG5 (2026-11-17) وG6 (2026-11-17) مجتازة · Phase 7 هي التالية |
| Release Notes | ✅ Draft v0.1 | v0.1.0 مخطط، لم يُنشر |

> **قاعدة الحوكمة:** أي تغيير في المعمارية أو قاعدة البيانات أو نطاق الوظائف بعد الاعتماد يتطلب:
> تحديث الوثيقة + تسجيل ADR أو Change Request + إعادة تشغيل الاختبارات المتأثرة.
> لا يُقبل كود يخالف وثيقة معتمدة دون تحديثها أولًا.

---

## 5. مبدأ التتبّع (Traceability)

كل ميزة يجب أن تسير في هذا المسار الكامل، وأي حلقة مفقودة تعني أن الميزة غير مكتملة:

```
Vision → FR → BR → UC/WF → SM → API → Table → Permission → KPI (إن وجد) → TC
```

مثال محسوس (صرف الدواء):

```
FR-PHR-004 (صرف بسياسة FEFO)
  → BR-PHR-004 (سياسة FEFO ومنع الدفعة المنتهية)
  → UC-PHR-002 (صرف وصفة)
  → WF-10 (مسار الصرف)
  → SM-PRESCRIPTION / SM-STOCK-BATCH
  → POST /api/v1/prescriptions/{id}/dispense
  → dispensing_transactions + stock_movements
  → PERM-PHR-DISPENSE
  → KPI-021 (نسبة الصرف من المخزون المتاح)
  → TC-093 / TC-094 / TC-095
```

هذا المبدأ هو ما يمنع المشروع من التحول إلى "شاشات وجداول" بلا منطق مؤسسي.

---

## 6. ما لا تجده في هذه الوثائق (مقصود)

- **لا كود ولا SQL نهائي** — هذه مرحلة تصميم، والكود يبدأ بعد الاعتماد.
- **لا ادعاء امتثال قانوني** (HIPAA/GDPR/اعتماد محلي) — الوثائق تذكر الضوابط التقنية، والامتثال القانوني يحتاج مراجعة قانونية محلية.
- **لا بروتوكولات طبية مُختلقة** — كل ما هو طبي (الفرز، العلامات الحرجة، الجرعات) يُعرّف كـ **قوالب قابلة للتهيئة** يعتمدها المستشفى.
- **لا أرقام أداء عشوائية** — كل هدف أداء مرتبط بافتراض حجم وموارد موثّق في `ASM` ويمكن تعديله بعد قياس فعلي.
