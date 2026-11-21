# 06 — RBAC Matrix, Data Scopes & Separation of Duties

| Field | Value |
|---|---|
| Document ID | REQ-RBAC-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Model | RBAC + Data Scope + Field Sensitivity + Break-Glass |

> **مبدأ:** الصلاحية تُفحص في EJB لكل عملية. إخفاء الزر في React تحسين تجربة فقط، وليس ضابطًا أمنيًا.

---

## 1. نموذج التفويض (Authorization Model)

```
User ──< user_roles >── Role ──< role_permissions >── Permission
                                                        │
                                     Permission = RESOURCE:ACTION
                                                        │
User ──> DataScope (branch / department / ward / own_patients / all)
                                                        │
                              Decision = Permission ∧ Scope ∧ RecordState
```

**قرار الوصول = (هل يملك الصلاحية؟) ∧ (هل السجل داخل نطاقه؟) ∧ (هل حالة السجل تسمح؟)**

مثال: طبيب يملك `EMR:READ` لكن مريضًا في قسم آخر ⇒ رفض، إلا بصلاحية `EMR:READ_ALL` أو Break-Glass.

---

## 2. الأدوار (Roles)

| Role ID | Role | الوصف | Default Landing | Data Scope الافتراضي |
|---|---|---|---|---|
| R-01 | `SYS_ADMIN` | مسؤول النظام التقني (نشر، مراقبة، إعدادات تقنية) | Admin Console | All (تقني، لا طبي) |
| R-02 | `HOSP_ADMIN` | مدير المستشفى (إعدادات، مستخدمون، سياسات) | Executive Dashboard | All |
| R-03 | `RECEPTION` | استقبال وتسجيل ومواعيد | Reception Desk | Branch |
| R-04 | `DOCTOR` | طبيب عيادة/قسم | My Clinic | Own patients + Department |
| R-05 | `CONSULTANT` | استشاري/رئيس قسم (اعتماد، رؤية أوسع) | Department Dashboard | Department + referrals |
| R-06 | `NURSE` | تمريض | Ward Board | Assigned ward |
| R-07 | `TRIAGE_NURSE` | تمريض فرز طوارئ | ER Board | ER |
| R-08 | `LAB_TECH` | فني مختبر | Lab Worklist | Lab |
| R-09 | `LAB_SUPERVISOR` | مشرف مختبر (اعتماد نتائج) | Lab Verification | Lab |
| R-10 | `RADIOLOGIST` | أخصائي أشعة | Radiology Worklist | Radiology |
| R-11 | `PHARMACIST` | صيدلي | Pharmacy Queue | Pharmacy |
| R-12 | `CASHIER` | محاسب/كاشير | Cashier Desk | Branch |
| R-13 | `FIN_MANAGER` | مدير مالي | Finance Dashboard | All (مالي) |
| R-14 | `STOREKEEPER` | أمين مخزن | Inventory | Warehouse(s) |
| R-15 | `HR_OFFICER` | موارد بشرية | HR | All (HR فقط) |
| R-16 | `AUDITOR` | مسؤول خصوصية/تدقيق | Audit Console | All (قراءة تدقيق) |
| R-17 | `DEPT_MANAGER` | مدير قسم (تحليلات تشغيلية) | Dept Dashboard | Department |
| R-18 | `IT_OPERATOR` | تشغيل ومراقبة (بلا بيانات طبية) | Ops Console | Technical only |

> الأدوار قابلة للإنشاء والتعديل كبيانات. هذه القائمة **Baseline** وليست ثابتة في الكود.

---

## 3. اصطلاح الصلاحيات (Permission Naming)

```
PERM-<DOMAIN>-<ACTION>[-<QUALIFIER>]
```

| Action | Meaning |
|---|---|
| `READ` | قراءة/بحث |
| `CREATE` | إنشاء |
| `UPDATE` | تعديل قبل الاعتماد |
| `SIGN` | توقيع/اعتماد سريري |
| `VERIFY` | اعتماد نتيجة/مستند |
| `APPROVE` | موافقة إدارية/مالية |
| `DISPENSE` | صرف دواء |
| `CANCEL` | إلغاء |
| `VOID` | إبطال مع سبب |
| `EXPORT` | تصدير بيانات |
| `CONFIG` | إعدادات |

**Qualifiers:** `_ALL` (خارج النطاق)، `_OVERRIDE` (تجاوز قاعدة)، `_SENSITIVE` (بيانات حساسة)، `_RETRO` (تعديل بأثر رجعي).

---

## 4. المصفوفة المختصرة (Roles × Capabilities)

**الرموز:** `F` كامل · `W` كتابة داخل النطاق · `R` قراءة فقط · `A` اعتماد/موافقة · `–` ممنوع

| Capability | R01 | R02 | R03 | R04 | R05 | R06 | R07 | R08 | R09 | R10 | R11 | R12 | R13 | R14 | R15 | R16 | R17 | R18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Patient Registry | R | R | W | R | R | R | W | R | R | R | R | R | R | – | – | R | R | – |
| Patient Merge | – | A | – | – | – | – | – | – | – | – | – | – | – | – | – | R | – | – |
| Appointments | – | R | W | W | W | R | W | – | – | – | – | – | – | – | – | R | R | – |
| Encounters / EMR | – | R | – | W | W | R | W | – | – | – | R | – | – | – | – | R | R | – |
| Diagnoses / Sign | – | – | – | A | A | – | – | – | – | – | – | – | – | – | – | R | – | – |
| Vitals | – | – | – | W | W | W | W | – | – | – | – | – | – | – | – | R | – | – |
| Lab Orders | – | – | – | W | W | – | W | R | R | – | – | – | – | – | – | R | – | – |
| Lab Results | – | – | – | R | R | R | R | W | A | – | – | – | – | – | – | R | – | – |
| Radiology | – | – | – | W | W | – | – | – | – | A | – | – | – | – | – | R | – | – |
| Prescriptions | – | – | – | W | W | R | – | – | – | – | R | – | – | – | – | R | – | – |
| Dispensing | – | – | – | – | – | – | – | – | – | – | A | – | – | – | – | R | – | – |
| Admissions / Beds | – | R | R | W | W | W | W | – | – | – | – | – | – | – | – | R | R | – |
| Nursing Tasks | – | – | – | R | R | W | W | – | – | – | – | – | – | – | – | R | – | – |
| Invoices | – | R | W | – | – | – | – | – | – | – | – | W | W | – | – | R | – | – |
| Payments / Refunds | – | – | W | – | – | – | – | – | – | – | – | W | A | – | – | R | – | – |
| Inventory | – | R | – | – | – | R | – | R | R | – | W | – | R | W | – | R | – | – |
| HR | – | R | – | – | – | – | – | – | – | – | – | – | – | – | W | R | – | – |
| Users & Roles | F | A | – | – | – | – | – | – | – | – | – | – | – | – | – | R | – | – |
| Audit Logs | R | R | – | – | – | – | – | – | – | – | – | – | – | – | – | R | – | – |
| Analytics | – | R | – | R | R | – | – | – | – | – | – | – | R | – | – | R | R | – |
| Decision Cards | – | A | – | R | R | – | – | – | – | – | – | – | A | – | – | R | A | – |
| System Config | F | A | – | – | – | – | – | – | – | – | – | – | – | – | – | R | – | R |
| Ops / Monitoring | F | R | – | – | – | – | – | – | – | – | – | – | – | – | – | – | – | F |

---

## 5. الصلاحيات التفصيلية حسب الدور (Permission Inventory)

### R-01 SYS_ADMIN
`SYS:CONFIG`, `SYS:OPS`, `IAM:READ`, `IAM:UPDATE` (بلا منح أدوار سريرية), `AUDIT:READ`, `MONITOR:READ`, `BACKUP:TRIGGER`.
**لا يملك:** قراءة سجلات طبية، اعتماد نتائج، صرف، دفع.

### R-02 HOSP_ADMIN
`SYS:CONFIG`, `IAM:*`, `ORG:*`, `SERVICE:*`, `PAT:READ`, `PAT:MERGE_APPROVE`, `RPT:READ`, `ANL:READ`, `DEC:APPROVE`, `AUDIT:READ`, `HR:READ`.
**لا يملك:** توقيع طبي، اعتماد نتائج مخبرية، صرف دواء، تسجيل دفع.

### R-03 RECEPTION
`PAT:CREATE`, `PAT:READ`, `PAT:UPDATE` (ديموغرافي فقط), `PAT:UPLOAD`, `SCH:*` (داخل الفرع), `QUEUE:*`, `ADT:READ`.
**لا يملك:** تشخيص، نتائج، صرف، مالية عدا عرض الرصيد.

### R-04 DOCTOR
`PAT:READ`, `EMR:CREATE`, `EMR:UPDATE` (مسودة), `EMR:SIGN`, `EMR:DIAGNOSE`, `LAB:ORDER`, `RAD:ORDER`, `PHR:PRESCRIBE`, `NUR:READ`, `ADT:REQUEST`, `RPT:READ` (نطاقه), `ANL:READ` (نطاقه).
**لا يملك:** اعتماد نتيجة مختبر، صرف، تعديل فاتورة، إدارة مستخدمين.

### R-05 CONSULTANT
كل صلاحيات R-04 + `EMR:READ_ALL` (قسمه/إحالته), `EMR:AMEND_LATE`, `LAB:VERIFY` (إن فُعّل), `ADT:APPROVE`, `DEC:REVIEW`, `RPT:DEPT`, `HR:READ` (أطباء قسمه).

### R-06 NURSE
`PAT:READ`, `EMR:READ` (جناحها), `VITALS:CREATE`, `NUR:TASK_*`, `NUR:NOTE_SIGN`, `MED:ADMINISTER` (إن فُعّل BCMA), `ADT:READ`, `ADT:BED_STATUS`, `LAB:COLLECT` (إن فُعّل).
**لا يملك:** تشخيص، وصف، اعتماد نتيجة، مالية.

### R-07 TRIAGE_NURSE
كل R-06 + `ER:CREATE`, `ER:TRIAGE`, `ER:QUEUE`, `PAT:CREATE_TEMP`.

### R-08 LAB_TECH
`LAB:READ`, `LAB:RECEIVE`, `LAB:PROCESS`, `LAB:RESULT_ENTER`, `LAB:PRINT`, `PAT:READ` (الحد الأدنى).
**لا يملك:** `LAB:VERIFY` (إن فُعّل dual control), تعديل طلب، مالية.

### R-09 LAB_SUPERVISOR
كل R-08 + `LAB:VERIFY`, `LAB:AMEND`, `LAB:CATALOG`, `LAB:REPORT_RELEASE`, `ANL:LAB`.

### R-10 RADIOLOGIST
`RAD:READ`, `RAD:REPORT`, `RAD:VERIFY`, `RAD:SCHEDULE`, `PAT:READ`, `EMR:READ` (طلب الأشعة).

### R-11 PHARMACIST
`PHR:READ`, `PHR:REVIEW`, `PHR:DISPENSE`, `PHR:RETURN`, `INV:READ` (صيدلية), `INV:ADJUST` (صيدلية), `MED:CATALOG_READ`, `PAT:READ` (الحد الأدنى).
**لا يملك:** وصف، تشخيص، تعديل فاتورة (يمكنه عرضها).

### R-12 CASHIER
`BIL:CREATE`, `BIL:READ`, `BIL:PAYMENT`, `BIL:PRINT`, `BIL:DISCOUNT_LIMITED`, `PAT:READ` (مالي فقط).
**لا يملك:** استرداد بلا موافقة، تعديل خدمة، حذف.

### R-13 FIN_MANAGER
كل R-12 + `BIL:REFUND_APPROVE`, `BIL:DISCOUNT`, `BIL:VOID`, `BIL:CLOSE_PERIOD`, `BIL:PRICE_LIST`, `RPT:FIN`, `ANL:FIN`, `DEC:APPROVE_FIN`.

### R-14 STOREKEEPER
`INV:READ`, `INV:RECEIVE`, `INV:ISSUE`, `INV:TRANSFER`, `INV:COUNT`, `INV:SUPPLIER`, `INV:PO_CREATE`, `PHR:BATCH_READ`.

### R-15 HR_OFFICER
`HR:*` (موظفون، مناوبات، تراخيص), `IAM:READ` (ربط الحساب), `RPT:HR`.
**لا يملك:** بيانات طبية.

### R-16 AUDITOR
`AUDIT:READ`, `ACCESS_LOG:READ`, `BREAK_GLASS:REVIEW`, `IAM:READ`, `RPT:AUDIT`, `ANL:DATA_QUALITY`.
**قراءة فقط — لا كتابة على أي بيانات تشغيلية.**

### R-17 DEPT_MANAGER
`RPT:DEPT`, `ANL:DEPT`, `DEC:REVIEW_DEPT`, `EMR:READ_AGGREGATE` (بلا تفاصيل هوية), `HR:READ` (قسمه).

### R-18 IT_OPERATOR
`MONITOR:READ`, `SYS:OPS`, `LOG:READ` (تقني بلا PHI), `BACKUP:TRIGGER`, `SESSION:READ`.
**لا يملك:** أي بيانات مريض أو مالية.

---

## 6. نطاقات البيانات (Data Scopes)

| Scope | التعريف | يُطبّق على |
|---|---|---|
| `SCOPE_ALL` | كل المستشفى | R-02, R-13, R-16 |
| `SCOPE_BRANCH` | فرع المستخدم | R-03, R-12 |
| `SCOPE_DEPARTMENT` | أقسام مرتبطة بالمستخدم | R-04, R-05, R-17 |
| `SCOPE_WARD` | أجنحة مسندة | R-06, R-07 |
| `SCOPE_OWN_PATIENTS` | مرضى الطبيب (Encounter طبيب = المستخدم) | R-04 (افتراضي) |
| `SCOPE_LAB` | طلبات المختبر فقط | R-08, R-09 |
| `SCOPE_PHARMACY` | وصفات ومخزون الصيدلية | R-11 |
| `SCOPE_WAREHOUSE` | مخازن مسندة | R-14 |
| `SCOPE_TECHNICAL` | بلا بيانات تشغيلية | R-01, R-18 |
| `SCOPE_AGGREGATE` | تجميعات بلا هوية | R-17 |

**قاعدة التطبيق:** يُبنى الاستعلام بـ `WHERE` إلزامية من الـ Scope، ولا يُسمح باستعلام "ثم الفلترة في Java".

---

## 7. فصل المهام (Separation of Duties)

| SoD ID | القاعدة | الأدوار المتأثرة |
|---|---|---|
| SOD-01 | من يدخل نتيجة مختبر لا يعتمدها إذا فُعّل `dual_control` | R-08 / R-09 |
| SOD-02 | من ينشئ بطاقة قرار لا يعتمدها | R-17 / R-02 |
| SOD-03 | من يسجل الدفع لا يعتمد الاسترداد | R-12 / R-13 |
| SOD-04 | من ينشئ المستخدم لا يمنح نفسه دورًا أعلى | R-01 / R-02 |
| SOD-05 | من يعدّل السعر لا يعتمد الفاتورة الناتجة | R-13 / R-12 |
| SOD-06 | مسؤول النظام لا يقرأ السجلات الطبية | R-01 |
| SOD-07 | من يصرف الدواء لا يعدّل الوصفة | R-11 / R-04 |
| SOD-08 | من يعتمد وصول Break-Glass ليس صاحبه | R-16 |

> SoD تُفرض في الخدمة، وتُختبر في `testing/01` ضمن Security tests.

---

## 8. تصنيف حساسية الحقول (Field Sensitivity)

| Class | الوصف | أمثلة | من يرى |
|---|---|---|---|
| S0 | عام | اسم القسم، الخدمة، السعر | الكل |
| S1 | إداري | اسم المريض، رقم طبي، هاتف | من يملك `PAT:READ` |
| S2 | سريري | تشخيص، نتائج، وصفات | أدوار سريرية + نطاق |
| S3 | حساس | صحة نفسية، أمراض معدية، اختبارات معينة، مرفقات | `*_SENSITIVE` فقط |
| S4 | أمني | تجزئة كلمة المرور، أسرار، مفاتيح | لا يُعرض أبدًا عبر API |

**قاعدة:** حقول S3 تُفلتر في DTO حسب الصلاحية، لا في React.

---

## 9. Break-Glass (الوصول الاستثنائي)

| الخاصية | القيمة |
|---|---|
| من يطلبه | طبيب/ممرض في الطوارئ |
| المتطلبات | سبب ≥ 20 حرفًا، مريض محدد، مدة ≤ 60 دقيقة |
| ما يمنحه | `EMR:READ` على مريض واحد فقط |
| ما لا يمنحه | كتابة، وصف، مالية، تصدير |
| الإشعار | R-16 + R-02 فورًا |
| المراجعة | خلال 24 ساعة، نتيجة: مبرر / غير مبرر |
| السجل | `break_glass_grants` + `access_logs` |
| التكرار | 3 حالات غير مبررة ⇒ تعليق الصلاحية ومراجعة |

---

## 10. مصفوفة اختبار الصلاحيات (نموذج)

| TC | السيناريو | المتوقع |
|---|---|---|
| TC-011a | Receptionist يحاول `POST /lab-orders/{id}/verify` | 403 + Audit |
| TC-011b | Doctor يقرأ مريضًا خارج قسمه بلا `READ_ALL` | 403 |
| TC-011c | Cashier يحاول حذف فاتورة | 403 (لا صلاحية VOID) |
| TC-011d | SYS_ADMIN يفتح `/patients/123` | 403 (SCOPE_TECHNICAL) |
| TC-011e | Nurse يعتمد نتيجة مختبر | 403 |
| TC-011f | Doctor يعدّل ملاحظة موقّعة مباشرة | 409/403 حسب السياسة |
| TC-011g | Auditor يحاول تعديل إعداد | 403 (Read-only) |
| TC-011h | مستخدم بجلسة منتهية ينفذ دفعًا | 401 + لا أثر مالي |

---

## 11. ملاحظات تنفيذية

1. تُخزَّن الصلاحيات كصفوف بيانات، وتُحمَّل في `@Singleton` Cache مع إبطال عند التعديل.
2. يُفضّل `@RolesAllowed` للفحص الخام + `PermissionService.require(...)` للصلاحيات الدقيقة والنطاق.
3. كل رفض يُسجَّل في `security_logs` (وليس فقط `audit_logs`).
4. لا تُستخدم أسماء أدوار في الكود (`if role == "DOCTOR"`)؛ تُستخدم صلاحيات فقط.
