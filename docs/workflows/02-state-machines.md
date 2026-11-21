# 02 — State Machines (SM)

| Field | Value |
|---|---|
| Document ID | WF-SM-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Rule | أي انتقال غير مذكور هنا = مرفوض في الخدمة (409 Conflict) |

---

## 1. لماذا آلات حالة صريحة؟

بدونها يتحول النظام إلى أعلام متناقضة: `is_paid=true` و`balance>0`، أو نتيجة `VERIFIED` و`editable=true`.
كل كيان له دورة حياة يُنمذج كآلة حالة، والانتقالات تُفحص مركزيًا عبر `StateMachineService`.

**التنفيذ:** `enum` للحالات + جدول انتقالات مسموحة + `transition(entity, event, actor)` تتحقق من:
1. الحالة الحالية.
2. الحدث.
3. الصلاحية.
4. الحراسات (Guards) مثل "لا صرف بلا مخزون".

---

## 2. SM-APPOINTMENT

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED
    SCHEDULED --> CONFIRMED: confirm
    SCHEDULED --> CHECKED_IN: check_in
    CONFIRMED --> CHECKED_IN: check_in
    SCHEDULED --> CANCELLED: cancel(reason)
    CONFIRMED --> CANCELLED: cancel(reason)
    CHECKED_IN --> IN_PROGRESS: start_encounter
    IN_PROGRESS --> COMPLETED: close_encounter
    CHECKED_IN --> NO_SHOW: mark_no_show
    SCHEDULED --> NO_SHOW: mark_no_show(after_window)
    COMPLETED --> [*]
    CANCELLED --> [*]
    NO_SHOW --> [*]
```

| From | Event | To | Guard | Permission |
|---|---|---|---|---|
| – | create | SCHEDULED | طبيب مفعّل + فترة صالحة | `SCH:CREATE` |
| SCHEDULED | confirm | CONFIRMED | – | `SCH:UPDATE` |
| SCHEDULED/CONFIRMED | check_in | CHECKED_IN | داخل نافذة الوصول | `SCH:CHECKIN` |
| CHECKED_IN | start_encounter | IN_PROGRESS | زيارة أُنشئت | `EMR:CREATE` |
| IN_PROGRESS | close_encounter | COMPLETED | الزيارة مغلقة | `EMR:SIGN` |
| SCHEDULED/CONFIRMED | cancel | CANCELLED | سبب إن < 24h | `SCH:CANCEL` |
| CHECKED_IN | mark_no_show | NO_SHOW | بعد 30 دقيقة | `SCH:NOSHOW` |
| COMPLETED | * | – | ممنوع | – |

**ممنوع صراحةً:** `COMPLETED → SCHEDULED`، `CANCELLED → CHECKED_IN`.

---

## 3. SM-ENCOUNTER

```mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> IN_CONSULTATION: start
    IN_CONSULTATION --> PENDING_RESULTS: order_tests
    PENDING_RESULTS --> IN_CONSULTATION: results_reviewed
    IN_CONSULTATION --> CLOSED: sign_and_close
    PENDING_RESULTS --> CLOSED: close_with_override
    OPEN --> CANCELLED: cancel
    CLOSED --> AMENDED: amend
    AMENDED --> CLOSED: reclose
```

| From | Event | To | Guard |
|---|---|---|---|
| – | create | OPEN | مريض + طبيب + قسم |
| OPEN | start | IN_CONSULTATION | – |
| IN_CONSULTATION | order_tests | PENDING_RESULTS | طلب واحد على الأقل |
| PENDING_RESULTS | results_reviewed | IN_CONSULTATION | كل النتائج الحرجة مقروءة |
| IN_CONSULTATION | sign_and_close | CLOSED | تشخيص + توقيع |
| PENDING_RESULTS | close_with_override | CLOSED | `EMR:CLOSE_OVERRIDE` + سبب |
| CLOSED | amend | AMENDED | `EMR:AMEND` + سبب |
| AMENDED | reclose | CLOSED | توقيع جديد |

---

## 4. SM-LAB-ORDER

```mermaid
stateDiagram-v2
    [*] --> ORDERED
    ORDERED --> COLLECTED: sample_collected
    COLLECTED --> RECEIVED: lab_received
    RECEIVED --> IN_PROCESS: processing
    IN_PROCESS --> RESULTED: all_results_entered
    RESULTED --> VERIFIED: supervisor_verify
    VERIFIED --> RELEASED: release
    ORDERED --> CANCELLED: cancel(reason)
    COLLECTED --> REJECTED: sample_rejected
    REJECTED --> COLLECTED: recollect
    RELEASED --> CORRECTED: amend
    CORRECTED --> RELEASED: reverify
```

| Guard | الوصف |
|---|---|
| sample_collected | عينة مسجلة + باركود |
| all_results_entered | كل بنود الطلب لها نتيجة |
| supervisor_verify | صلاحية `LAB:VERIFY` + `dual_control` |
| cancel | لا عينة بعد، أو صلاحية إلغاء |
| amend | سبب + نسخة جديدة |

**ممنوع:** `ORDERED → RESULTED` مباشرة.

---

## 5. SM-LAB-RESULT

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> RESULTED: submit
    RESULTED --> VERIFIED: verify
    VERIFIED --> RELEASED: release
    RELEASED --> CORRECTED: correct(reason)
    CORRECTED --> RELEASED: reverify
    DRAFT --> ENTERED_IN_ERROR: mark_error
    RESULTED --> ENTERED_IN_ERROR: mark_error
```

| Guard | الوصف |
|---|---|
| verify | ليس نفس مدخل النتيجة عند `dual_control` |
| correct | نتيجة `RELEASED` + سبب + نسخة جديدة |
| mark_error | قبل `RELEASED` فقط |

**قاعدة:** `RELEASED` لا يعود `DRAFT` أبدًا.

---

## 6. SM-PRESCRIPTION

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> SIGNED: sign
    SIGNED --> REVIEWED: pharmacist_review
    SIGNED --> REJECTED: pharmacist_reject(reason)
    REVIEWED --> PARTIALLY_DISPENSED: partial_dispense
    REVIEWED --> DISPENSED: full_dispense
    PARTIALLY_DISPENSED --> DISPENSED: dispense_remaining
    PARTIALLY_DISPENSED --> CANCELLED: cancel_remaining(reason)
    DISPENSED --> COMPLETED: close
    DRAFT --> CANCELLED: cancel
    SIGNED --> CANCELLED: cancel(reason)
```

| Guard | الوصف |
|---|---|
| sign | زيارة مفتوحة + طبيب مخوّل |
| pharmacist_review | صلاحية `PHR:REVIEW` |
| full_dispense | كل البنود صُرفت |
| cancel | لا صرف سابق (وإلا إرجاع) |

---

## 7. SM-DISPENSING

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> COMMITTED: dispense_commit
    PENDING --> FAILED: stock_error
    COMMITTED --> PARTIALLY_RETURNED: return_partial
    COMMITTED --> RETURNED: return_all
    COMMITTED --> VOIDED: void(reason, same_day)
```

| Guard | الوصف |
|---|---|
| dispense_commit | مخزون كافٍ + دفعات صالحة + صلاحية |
| void | نفس اليوم + `PHR:VOID` + عكس الحركات |

**ملاحظة:** `COMMITTED` هو الأثر المالي/المخزني؛ لا يُحذف.

---

## 8. SM-INVOICE

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> ISSUED: issue
    ISSUED --> PARTIALLY_PAID: partial_payment
    ISSUED --> PAID: full_payment
    PARTIALLY_PAID --> PAID: full_payment
    PAID --> REFUNDED: refund
    PARTIALLY_PAID --> REFUNDED: refund
    ISSUED --> VOID: void(no_payments)
    DRAFT --> VOID: void
    ISSUED --> WRITTEN_OFF: write_off(approve)
```

| Guard | الوصف |
|---|---|
| issue | بنود > 0 + خدمات منفذة |
| partial_payment | 0 < paid < total |
| full_payment | paid = total |
| void | لا دفعات + صلاحية |
| refund | ≤ paid + اعتماد |
| write_off | صلاحية مالية عليا + سبب |

**ممنوع:** `PAID → ISSUED`، حذف فاتورة.

---

## 9. SM-PAYMENT

```mermaid
stateDiagram-v2
    [*] --> POSTED
    POSTED --> REVERSED: reverse(reason)
    POSTED --> REFUNDED: refund(approve)
```

| Guard | الوصف |
|---|---|
| POSTED | مبلغ > 0 + idempotency جديد |
| reverse | خطأ إدخال، نفس اليوم، صلاحية |
| refund | استرداد فعلي باعتماد |

**قاعدة:** `POSTED` لا يُعدّل مبلغه؛ يُعكس ويُسجّل جديد.

---

## 10. SM-ADMISSION

```mermaid
stateDiagram-v2
    [*] --> REQUESTED
    REQUESTED --> APPROVED: approve
    REQUESTED --> REJECTED: reject(reason)
    APPROVED --> ADMITTED: assign_bed
    ADMITTED --> TRANSFERRED: transfer
    TRANSFERRED --> ADMITTED: settle
    ADMITTED --> DISCHARGE_PENDING: request_discharge
    DISCHARGE_PENDING --> DISCHARGED: discharge
    DISCHARGED --> [*]
    REJECTED --> [*]
```

| Guard | الوصف |
|---|---|
| approve | زيارة + تشخيص + قسم |
| assign_bed | سرير متاح + لا تخصيص فعّال |
| discharge | ملخص خروج + فاتورة مسوّاة/استثناء |

---

## 11. SM-BED-ASSIGNMENT

```mermaid
stateDiagram-v2
    [*] --> ACTIVE
    ACTIVE --> ENDED: discharge_or_transfer
    ACTIVE --> CANCELLED: cancel_assignment(reason)
    ENDED --> [*]
    CANCELLED --> [*]
```

**قيد DB:** `UNIQUE(bed_id, active_flag)` حيث `active_flag=1` للتخصيص الفعّال و`NULL` للمنتهي.

---

## 12. SM-BED

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE
    AVAILABLE --> RESERVED: reserve
    RESERVED --> OCCUPIED: occupy
    RESERVED --> AVAILABLE: release_reservation
    OCCUPIED --> CLEANING: discharge
    CLEANING --> AVAILABLE: cleaned
    AVAILABLE --> MAINTENANCE: maintenance
    CLEANING --> MAINTENANCE: maintenance
    MAINTENANCE --> AVAILABLE: fixed
```

**قاعدة:** لا تخصيص إلا من `AVAILABLE` أو `RESERVED`.

---

## 13. SM-STOCK-BATCH

```mermaid
stateDiagram-v2
    [*] --> ACTIVE
    ACTIVE --> QUARANTINED: quarantine(reason)
    QUARANTINED --> ACTIVE: release
    ACTIVE --> EXPIRED: expiry_reached
    ACTIVE --> DEPLETED: qty_zero
    ACTIVE --> WRITTEN_OFF: wastage(reason)
    QUARANTINED --> WRITTEN_OFF: wastage(reason)
    EXPIRED --> WRITTEN_OFF: dispose
```

**قاعدة:** `EXPIRED`/`QUARANTINED` غير قابلة للصرف.

---

## 14. SM-PURCHASE-ORDER

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> SUBMITTED: submit
    SUBMITTED --> APPROVED: approve
    SUBMITTED --> REJECTED: reject(reason)
    APPROVED --> PARTIALLY_RECEIVED: receive_partial
    APPROVED --> RECEIVED: receive_all
    PARTIALLY_RECEIVED --> RECEIVED: receive_remaining
    DRAFT --> CANCELLED: cancel
    APPROVED --> CANCELLED: cancel(reason)
```

---

## 15. SM-RADIOLOGY-ORDER

```mermaid
stateDiagram-v2
    [*] --> ORDERED
    ORDERED --> SCHEDULED: schedule
    SCHEDULED --> PERFORMED: perform
    PERFORMED --> REPORTED: report_draft
    REPORTED --> VERIFIED: verify
    VERIFIED --> RELEASED: release
    ORDERED --> CANCELLED: cancel(reason)
    SCHEDULED --> CANCELLED: cancel(reason)
```

---

## 16. SM-OR-CASE

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED
    SCHEDULED --> CHECKED_IN: patient_ready
    CHECKED_IN --> IN_PROGRESS: start(time_out_done)
    IN_PROGRESS --> RECOVERY: finish
    RECOVERY --> COMPLETED: close
    SCHEDULED --> CANCELLED: cancel(reason)
```

**Guard:** `start` يتطلب إكمال Checklist (Time-Out) إن كانت مفعّلة.

---

## 17. SM-DECISION-CARD

```mermaid
stateDiagram-v2
    [*] --> DETECTED
    DETECTED --> ANALYZED: analyze
    ANALYZED --> RECOMMENDED: recommend
    RECOMMENDED --> UNDER_REVIEW: assign_reviewer
    UNDER_REVIEW --> APPROVED: approve
    UNDER_REVIEW --> REJECTED: reject(reason)
    UNDER_REVIEW --> DEFERRED: defer(review_date)
    UNDER_REVIEW --> NEEDS_DATA: request_data
    NEEDS_DATA --> ANALYZED: data_ready
    APPROVED --> EXECUTING: start_execution
    EXECUTING --> EXECUTED: complete
    EXECUTED --> VALIDATED: measure_outcome
    DETECTED --> INSUFFICIENT_DATA: low_sample
    INSUFFICIENT_DATA --> [*]
    REJECTED --> [*]
    VALIDATED --> [*]
```

| Guard | الوصف |
|---|---|
| recommend | أدلة + خط أساس + ثقة محسوبة |
| approve | دور مخوّل ≠ منشئ التوصية |
| start_execution | خطة + مسؤول + تاريخ |
| measure_outcome | نفس KPI ونفس نافذة القياس |

---

## 18. SM-USER-ACCOUNT

```mermaid
stateDiagram-v2
    [*] --> PENDING_ACTIVATION
    PENDING_ACTIVATION --> ACTIVE: activate
    ACTIVE --> LOCKED: failed_attempts
    LOCKED --> ACTIVE: unlock_or_timeout
    ACTIVE --> SUSPENDED: suspend(reason)
    SUSPENDED --> ACTIVE: reinstate
    ACTIVE --> DISABLED: disable
    DISABLED --> [*]
```

**قاعدة:** أي انتقال إلى `LOCKED/SUSPENDED/DISABLED` يُبطل الجلسات.

---

## 19. SM-BREAK-GLASS

```mermaid
stateDiagram-v2
    [*] --> REQUESTED
    REQUESTED --> GRANTED: grant
    GRANTED --> EXPIRED: time_limit
    GRANTED --> REVOKED: revoke
    EXPIRED --> REVIEWED: auditor_review
    REVOKED --> REVIEWED: auditor_review
    REVIEWED --> [*]
```

**Guard:** `GRANTED` ≤ 60 دقيقة + سبب + مريض واحد + إشعار.

---

## 20. جدول الانتقالات الممنوعة عالميًا

| Entity | Transition | السبب |
|---|---|---|
| Invoice | PAID → ISSUED | يمحو أثر الدفع |
| LabResult | RELEASED → DRAFT | يمحو الاعتماد |
| ClinicalNote | SIGNED → DRAFT | يمحو التوقيع |
| Payment | POSTED → (deleted) | يمحو أثر مالي |
| Admission | DISCHARGED → ADMITTED (نفس السجل) | يُفسد مدة الإقامة |
| StockBatch | EXPIRED → ACTIVE | خطر صرف منتهٍ |
| DecisionCard | REJECTED → APPROVED | يتجاوز قرارًا موثقًا |
| UserAccount | DISABLED → ACTIVE (مباشرة) | يتجاوز مراجعة أمنية |

---

## 21. ربط آلات الحالة بالاختبارات

| SM | TC |
|---|---|
| SM-APPOINTMENT | TC-030..TC-035 |
| SM-ENCOUNTER | TC-039..TC-049 |
| SM-LAB-ORDER/RESULT | TC-072..TC-082 |
| SM-PRESCRIPTION/DISPENSING | TC-090..TC-101 |
| SM-INVOICE/PAYMENT | TC-108..TC-117 |
| SM-ADMISSION/BED | TC-057..TC-065 |
| SM-DECISION-CARD | TC-154..TC-162 |
| SM-USER-ACCOUNT / SM-BREAK-GLASS | TC-007..TC-016 |
