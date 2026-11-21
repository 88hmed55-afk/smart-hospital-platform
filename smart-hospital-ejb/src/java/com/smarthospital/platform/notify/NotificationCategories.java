package com.smarthospital.platform.notify;

import java.util.List;
import java.util.Locale;

/**
 * Notification categories exposed to users as inbox preferences (FR-NOT-004).
 *
 * <p>A category is derived from the notification type prefix so producers do
 * not have to declare one: {@code LAB_CRITICAL -> LAB}, {@code BIL_ISSUED ->
 * BILLING}, and so on. Anything unrecognised falls back to {@code SYSTEM}.
 */
public final class NotificationCategories {

    public static final String CLINICAL = "CLINICAL";
    public static final String LAB = "LAB";
    public static final String PHARMACY = "PHARMACY";
    public static final String BILLING = "BILLING";
    public static final String INVENTORY = "INVENTORY";
    public static final String SECURITY = "SECURITY";
    public static final String SYSTEM = "SYSTEM";

    public static final List<String> ALL = List.of(
            CLINICAL, LAB, PHARMACY, BILLING, INVENTORY, SECURITY, SYSTEM);

    public static final List<String> SEVERITIES = List.of("INFO", "WARNING", "CRITICAL");

    private NotificationCategories() {
    }

    public static boolean known(String category) {
        return category != null && ALL.contains(category.toUpperCase(Locale.ROOT));
    }

    public static String of(String type) {
        if (type == null || type.isBlank()) {
            return SYSTEM;
        }
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.startsWith("LAB")) {
            return LAB;
        }
        if (upper.startsWith("PHR") || upper.startsWith("MED")) {
            return PHARMACY;
        }
        if (upper.startsWith("BIL")) {
            return BILLING;
        }
        if (upper.startsWith("INV") || upper.startsWith("STK")) {
            return INVENTORY;
        }
        if (upper.startsWith("AUTH") || upper.startsWith("IAM") || upper.startsWith("SEC")) {
            return SECURITY;
        }
        if (upper.startsWith("EMR") || upper.startsWith("ADT") || upper.startsWith("NUR")
                || upper.startsWith("ER") || upper.startsWith("RAD")) {
            return CLINICAL;
        }
        return SYSTEM;
    }

    public static int rank(String severity) {
        if (severity == null) {
            return 1;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    public static String normalizeSeverity(String severity) {
        if (severity == null) {
            return "INFO";
        }
        String upper = severity.toUpperCase(Locale.ROOT);
        return SEVERITIES.contains(upper) ? upper : "INFO";
    }
}
