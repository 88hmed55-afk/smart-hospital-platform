# 02 — Backend Architecture

| Field | Value |
|---|---|
| Document ID | ARC-BE-001 |
| Version | 0.1 |
| Status | Approved (baseline 0.1) |
| Runtime | EJB 4.0 (Jakarta EE 10) · JPA 3.1 · JTA 2.0 · JMS 3.1 |
| Build | Apache Ant + NetBeans 21 |

---

## 1. هيكل المشاريع (NetBeans / Ant)

```
SmartHospital/
├── smart-hospital-parent/          // build.xml مشترك + خصائص
├── smart-hospital-ejb/             // EJB Module (jar)
│   ├── src/java/com/smarthospital/...
│   ├── src/conf/META-INF/persistence.xml
│   └── build.xml
├── smart-hospital-war/             // Web Module (war)
│   ├── src/java/com/smarthospital/*/web/...
│   ├── web/                        // ناتج Vite + index.html
│   ├── WEB-INF/web.xml
│   └── build.xml
├── smart-hospital-ear/             // Enterprise Application (ear)
│   ├── src/conf/application.xml
│   └── build.xml
├── frontend/                       // React 19 + TS + Vite (npm)
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
├── db/
│   ├── migrations/V001__...sql
│   └── seed/
└── docs/
```

**ترتيب البناء:** `frontend (npm run build)` → نسخ `dist/` إلى `smart-hospital-war/web/` → `ant -f smart-hospital-ear/build.xml` → `smart-hospital-ear.ear`.

**قاعدة:** لا يستخدم Java أي أداة بناء غير Ant. npm للواجهة فقط.

---

## 2. طبقات الخادم

| الطبقة | الحزمة | المسؤولية | ممنوع |
|---|---|---|---|
| Resource | `*.web` (WAR) | HTTP، Validation، DTO in/out، Status codes | منطق أعمال، JPA |
| Application Service | `*.service` | تنسيق العملية، المعاملة، الصلاحية، الأحداث | تفاصيل HTTP |
| Domain | `*.domain` | القواعد، حالات الكيان، حسابات | استدعاء خارجي |
| Repository | `*.repo` | استعلامات JPA/Criteria | قواعد أعمال |
| Integration | `*.event` / `integration` | Outbox، JMS، Adapters | معاملات طويلة |

**قاعدة الاتجاه:** `web → service → (domain + repo)`. لا يعود الاتجاه أبدًا.

**نمط حقن موارد JAX-RS (مُتحقق في المرحلة 1):** مورد JAX-RS هو CDI bean:

```java
@Path("/health")
@RequestScoped
public class HealthResource {
    @Inject
    private HealthServiceLocal healthService;
}
```

يجب وجود `WEB-INF/beans.xml` (`bean-discovery-mode="annotated"`) في WAR. بدونه ينشئ Jersey المورد بنفسه ولا يُنفّذ `@EJB`/`@Inject` بصمت — يظهر ذلك كـ `NullPointerException` عند أول طلب. الخدمات نفسها تبقى EJBs وتُحقن عبر واجهاتها المحلية.

---

## 3. أنواع EJB واستخدامها

| النوع | الاستخدام | أمثلة | ملاحظات |
|---|---|---|---|
| `@Stateless` | معظم الخدمات | `PatientService`, `InvoiceService` | عديم الحالة، آمن للتزامن |
| `@Singleton` + `@Startup` | Dispatcher، إعدادات، مراقبة | `OutboxDispatcher`, `SettingsCache` | `@Lock(WRITE)` بحذر |
| `@MessageDriven` | مستهلكو JMS | `NotificationMDB`, `AnalyticsMDB` | `@TransactionAttribute(NOT_SUPPORTED)` عند الحاجة |
| `@Singleton` + `@Schedule` | مهام دورية | `KpiSnapshotJob`, `CleanupJob` | `persistent=false` في v1 |
| `@Stateful` | **لا يُستخدم** | — | الجلسة في HTTP، لا في EJB |

**قاعدة:** لا تُستخدم `@Stateful` لأن الجلسات تُدار في طبقة الويب، ولتفادي مشاكل الذاكرة وإعادة التوزيع.

---

## 4. حدود المعاملة

```java
@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class DispensingService implements DispensingApi {

    @PersistenceContext(unitName = "smartHospitalPU")
    private EntityManager em;

    @Resource
    private SessionContext ctx;

    @Override
    public DispensingResult dispense(DispenseCommand cmd, UserContext user) {
        authorization.require(Permission.PHR_DISPENSE, user);
        // ... قفل، تحقق، خصم، حركة، outbox
        return result;
    }
}
```

| الحالة | النوع | السبب |
|---|---|---|
| عملية كتابة عادية | `REQUIRED` | تشارك معاملة الطلب |
| تسجيل تدقيق/فشل | `REQUIRES_NEW` | يجب أن يُحفظ حتى لو تراجعت العملية |
| إرسال بريد/إشعار | `NOT_SUPPORTED` | لا يمسك معاملة أثناء I/O |
| قراءة تقرير | `SUPPORTS` | لا تفتح معاملة بلا داعٍ |
| MDB | `REQUIRED` | رسالة واحدة = معاملة واحدة |

**ممنوع:** استدعاء طريقة `@TransactionAttribute` داخل نفس الكائن (self-invocation) — لا يمر عبر Proxy، فلا يُطبَّق. استخدم Service منفصلًا أو `SessionContext.getBusinessObject`.

---

## 5. نموذج الكيانات (JPA)

### 5.1 القاعدة المشتركة

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, length = 26)
    private String publicId;          // ULID

    @Version
    private long version;             // Optimistic locking

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
```

| القاعدة | التطبيق |
|---|---|
| PK رقمي داخلي | `BIGINT`، لا يُعرض للعميل |
| معرف عام | `public_id` ULID، يُستخدم في API |
| قفل متفائل | `@Version` افتراضيًا |
| وقت UTC | `Instant` + `DATETIME(6)` |
| المال | `BigDecimal` + `DECIMAL(14,2)` |
| لا `double/float` للمال | مراجعة كود |
| `equals/hashCode` | على `id` فقط، مع مراعاة `null` |

### 5.2 الكيانات ذات الإصدارات (Clinical)

- `clinical_notes` (الحالة الحالية) + `clinical_note_versions` (كل نسخة).
- التوقيع ينشئ نسخة غير قابلة للتعديل.
- التصحيح ينشئ نسخة جديدة مع `amends_id` و`amend_reason`.
- لا `UPDATE` على نسخة موقّعة (يفرضه Trigger + صلاحية DB).

### 5.3 العلاقات

- `@ManyToOne(fetch = LAZY)` دائمًا.
- لا `@OneToMany` كبير بلا `@BatchSize` أو استعلام صريح.
- لا Cascade delete على بيانات سريرية/مالية.
- DTOs لا تحتوي كيانات JPA (منع LazyInitializationException وتسريب الحقول).

---

## 6. المستودعات والاستعلامات

| النمط | الاستخدام |
|---|---|
| Derived query | استعلامات بسيطة (`findByPublicId`) |
| JPQL | استعلامات نطاقية مقروءة |
| Criteria | فلاتر ديناميكية (بحث المرضى، التقارير) |
| Native SQL | فقط للـ Views والتقارير المُعتمدة |
| Pagination | `PageRequest` إلزامي على كل قائمة |
| Projection | DTO projection للقراءة الثقيلة |

**ممنوع:** إرجاع `List` بلا حد أقصى؛ `SELECT *` في Native؛ استعلام داخل حلقة (N+1).

---

## 7. التحقق (Validation)

| المستوى | الأداة | مثال |
|---|---|---|
| DTO | Jakarta Bean Validation | `@NotBlank`, `@Past`, `@DecimalMin` |
| Service | قواعد أعمال | تاريخ الميلاد ليس مستقبليًا، الجرعة > 0 |
| DB | قيود | `CHECK`, `UNIQUE`, `FK` |
| Cross-field | Validator مخصص | نهاية ≥ بداية، الدفعة غير منتهية |

**قاعدة:** التحقق في الواجهة للراحة فقط؛ الخادم هو الحكم. أي قاعدة في `05-business-rules.md` تُفرض في الخادم أو DB.

---

## 8. الأخطاء والاستجابات

```java
public class BusinessException extends RuntimeException {
    private final ErrorCode code;
    private final Map<String, Object> details;
}
```

| النوع | HTTP | مثال |
|---|---|---|
| `ValidationException` | 400 | حقل ناقص |
| `AuthenticationException` | 401 | جلسة منتهية |
| `AuthorizationException` | 403 | صلاحية ناقصة |
| `NotFoundException` | 404 | مريض غير موجود |
| `ConflictException` | 409 | تعارض موعد / نسخة قديمة |
| `BusinessRuleException` | 422 | صرف من دفعة منتهية |
| `RateLimitException` | 429 | محاولات دخول كثيرة |
| `SystemException` | 500 | فشل غير متوقع (بلا تفاصيل حساسة) |

**قاعدة:** كل خطأ له `code` ثابت + رسالة عربية + رسالة إنجليزية + `traceId`. لا تُسرَّب Stack traces أو SQL أو أسماء جداول للعميل.

---

## 9. الأمان في الخادم

| الطبقة | الآلية |
|---|---|
| المصادقة | `SecurityFilter` يتحقق من الجلسة + يبني `UserContext` |
| التخويل | `AuthorizationService.require(permission, user)` في كل خدمة |
| نطاق البيانات | `DataScopeService.apply(criteria, user)` |
| CSRF | رمز مزدوج لكل طلب كتابة |
| التدقيق | `AuditService.record(...)` تلقائيًا عبر Interceptor |
| الحقول الحساسة | `@Sensitive` + Masking في DTO |

**ممنوع:** الاعتماد على إخفاء زر في الواجهة؛ تمرير `userId` من العميل؛ قراءة كيان حساس بلا فحص نطاق.

---

## 10. Outbox والأحداث

```java
// داخل نفس المعاملة
outbox.publish(DomainEvent.of("LabResultReleased")
    .aggregate("lab_result", result.getPublicId())
    .payload(Map.of("patientId", ..., "critical", ...))
    .build());
```

| العنصر | القرار |
|---|---|
| الجدول | `outbox_events` |
| الحالة | `PENDING → PROCESSING → SENT / FAILED / DEAD` |
| الالتقاط | Dispatcher `@Schedule` كل 2 ثانية |
| التزامن | `SELECT ... FOR UPDATE SKIP LOCKED` |
| إعادة المحاولة | Backoff أسّي، 5 محاولات، ثم DLQ |
| الترتيب | حسب `aggregate_id` + `created_at` |
| التنظيف | أرشفة `SENT` بعد 30 يومًا |

التفصيل: `07-notification-architecture.md`.

---

## 11. المهام المجدولة

| المهمة | التوقيت | المعاملة | الفشل |
|---|---|---|---|
| `OutboxDispatcher` | كل 2 ثانية | لكل دفعة | تسجيل + إعادة محاولة |
| `KpiSnapshotJob` | 01:00 يوميًا | دفعة/نطاق | تنبيه + إعادة يدوية |
| `AppointmentNoShowJob` | كل 15 دقيقة | REQUIRED | تسجيل |
| `ExpiryAlertJob` | 06:00 يوميًا | REQUIRED | تسجيل |
| `BackupVerificationJob` | بعد النسخ | NOT_SUPPORTED | تنبيه حرج |
| `AuditArchiveJob` | شهريًا | REQUIRES_NEW | تنبيه |

**قاعدة:** كل مهمة Idempotent، وقابلة لإعادة التشغيل، ولها سجل `job_runs`.

---

## 12. حدود الوحدات في الكود

| القاعدة | الفحص |
|---|---|
| لا استيراد `domain/repo/service` من وحدة أخرى | مراجعة + سكربت `check-imports` |
| الواجهات العامة في `api` فقط | مراجعة |
| لا حقول JPA في DTOs | مراجعة |
| لا استدعاء HTTP داخلي بين الوحدات | مراجعة |
| لا معاملة موزعة | مراجعة |
| لا منطق مالي في الواجهة | مراجعة |

**استثناء موثّق:** `analytics` يقرأ `v_*` عبر Native SQL، للقراءة فقط.

---

## 13. معايير الكود

| البند | القاعدة |
|---|---|
| اللغة | Java 17، `var` محليًا فقط عند الوضوح |
| التسمية | `PascalCase` للفئات، `camelCase` للأعضاء، `UPPER_SNAKE` للثوابت |
| الحزمة | `com.smarthospital.<module>.<layer>` |
| الطول | دالة ≤ 40 سطرًا، فئة ≤ 400 سطرًا (هدف) |
| التعليقات | تشرح "لماذا"، لا "ماذا" |
| السجلات | SLF4J، بلا بيانات مريض (NFR-PRV-003) |
| الاستثناءات | لا `catch (Exception e) {}` صامت |
| التاريخ | `Instant` في الكود، `ZonedDateTime` للعرض فقط |
| المال | `BigDecimal` + `RoundingMode.HALF_UP` |
| الاختبارات | JUnit 5، اسم يصف السلوك |

---

## 14. مثال هيكل وحدة (Patient)

```
patient/
├── api/
│   ├── PatientApi.java
│   ├── PatientSummary.java
│   ├── RegisterPatientCommand.java
│   └── PatientSearchQuery.java
├── domain/
│   ├── Patient.java
│   ├── PatientIdentifier.java
│   ├── PatientAllergy.java
│   └── DuplicateMatcher.java
├── repo/
│   ├── PatientRepository.java
│   └── PatientSearchRepository.java
├── service/
│   ├── PatientService.java
│   ├── PatientMergeService.java
│   └── PatientAuditInterceptor.java
└── event/
    └── PatientRegisteredEvent.java
```

---

## 15. ما لا يفعله الخادم

- لا يولّد HTML للواجهة (عدا قوالب الطباعة).
- لا يحتفظ بحالة شاشة.
- لا يعتمد على `HttpSession` لتخزين كيانات.
- لا ينفّذ قرارًا سريريًا/ماليًا آليًا.
- لا يتصل بالإنترنت في مسار العمل الأساسي.
- لا يخزّن ملفات في `webroot`.
- لا يستخدم `System.out.println`.

---

## 16. المراجع

- `01-system-architecture.md` — الحدود والمستويات.
- `04-api-contracts.md` — DTO/HTTP.
- `05-transactions-concurrency.md` — JTA والأقفال.
- `06-security-architecture.md` — التخويل.
- `07-notification-architecture.md` — Outbox/JMS.
