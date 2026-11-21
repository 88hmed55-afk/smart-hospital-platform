-- Phase 6 slice 3: priority-aware delivery, priority inbox and notification templates.
--
-- 1) outbox_events.priority lets the claim query pick CRITICAL work first even
--    when a thousand INFO events are already waiting (NFR-OPS backlog).
-- 2) notifications.priority keeps the inbox ordered by importance, then time.
-- 3) notification_templates is the single place where the wording of a
--    notification lives, so producers send a code plus parameters instead of
--    hard-coding Arabic and English strings.

ALTER TABLE outbox_events
    ADD COLUMN priority TINYINT NOT NULL DEFAULT 0 AFTER attempts,
    ADD KEY ix_outbox_events_priority (status, next_attempt_at, priority, id);

ALTER TABLE notifications
    ADD COLUMN priority TINYINT NOT NULL DEFAULT 0 AFTER severity,
    ADD KEY ix_notifications_inbox (user_id, status, priority, created_at);

CREATE TABLE notification_templates (
    code             VARCHAR(60)  NOT NULL,
    category         VARCHAR(40)  NOT NULL,
    default_severity VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    title_ar         VARCHAR(200) NOT NULL,
    title_en         VARCHAR(200) NOT NULL,
    body_ar          VARCHAR(1000) NOT NULL,
    body_en          VARCHAR(1000) NOT NULL,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (code),
    CONSTRAINT ck_notification_templates_severity
        CHECK (default_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_notification_templates_enabled CHECK (enabled IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO notification_templates
    (code, category, default_severity, title_ar, title_en, body_ar, body_en)
VALUES
    ('LAB_CRITICAL', 'LAB', 'CRITICAL',
     'نتيجة مختبر حرجة',
     'Critical laboratory result',
     'نتيجة {test} للمريض {patient} خارج المدى الحرج وتحتاج مراجعة عاجلة.',
     'The {test} result for {patient} is critically out of range and needs urgent review.'),
    ('LAB_RELEASED', 'LAB', 'INFO',
     'صدرت نتيجة مختبر',
     'Laboratory result released',
     'صدرت نتيجة {test} للمريض {patient} وأصبحت متاحة للمراجعة.',
     'The {test} result for {patient} has been released for review.'),
    ('EWS_HIGH', 'CLINICAL', 'CRITICAL',
     'إنذار تدهور سريري',
     'Clinical deterioration alert',
     'درجة EWS للمريض {patient} بلغت {score} (خطورة {risk}). يلزم تقييم فوري.',
     'Patient {patient} reached an EWS score of {score} ({risk} risk). Immediate assessment is required.'),
    ('LOW_STOCK', 'INVENTORY', 'WARNING',
     'رصيد منخفض',
     'Low stock',
     'الصنف {item} وصل إلى {balance} عند حد إعادة الطلب {reorder}.',
     'Item {item} reached {balance} against a reorder level of {reorder}.'),
    ('INVOICE_OVERDUE', 'BILLING', 'WARNING',
     'فاتورة مستحقة',
     'Invoice overdue',
     'الفاتورة {invoice} للمريض {patient} بمبلغ {amount} ما زالت غير مسددة.',
     'Invoice {invoice} for {patient} remains unpaid at {amount}.'),
    ('BREAK_GLASS', 'SECURITY', 'CRITICAL',
     'وصول طارئ مُنشأ',
     'Break-glass access granted',
     'فُتح وصول طارئ للمستخدم {user} على {scope} بسبب: {reason}.',
     'Break-glass access was opened for {user} on {scope} because: {reason}.'),
    ('APPOINTMENT_REMINDER', 'SYSTEM', 'INFO',
     'تذكير بموعد',
     'Appointment reminder',
     'لدى المريض {patient} موعد مع {provider} في {when}.',
     'Patient {patient} has an appointment with {provider} at {when}.')
ON DUPLICATE KEY UPDATE code = code;
