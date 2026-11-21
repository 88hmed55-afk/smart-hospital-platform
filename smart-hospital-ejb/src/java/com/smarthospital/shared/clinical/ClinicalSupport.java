package com.smarthospital.shared.clinical;

import com.smarthospital.shared.error.AppException;
import com.smarthospital.shared.json.JsonUtil;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared parsing, validation and formatting helpers for clinical services. */
public final class ClinicalSupport {

    private ClinicalSupport() {
    }

    public static String required(Map<String, Object> body, String key, String messageAr, String messageEn) {
        String value = optional(body, key);
        if (value == null || value.isBlank()) {
            throw AppException.badRequest("VALIDATION_FAILED", messageAr, messageEn);
        }
        return value;
    }

    public static String optional(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static String optionalTrim(Map<String, Object> body, String key, int maxLength) {
        String value = optional(body, key);
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw AppException.unprocessable("FIELD_TOO_LONG",
                    "قيمة الحقل " + key + " أطول من الحد المسموح.",
                    "Field " + key + " exceeds the maximum length of " + maxLength + ".");
        }
        return value;
    }

    public static Long optionalLong(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية غير صحيحة في الحقل " + key + ".",
                    "Invalid numeric value for " + key + ".");
        }
    }

    public static Integer optionalInt(Map<String, Object> body, String key) {
        Long value = optionalLong(body, key);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "قيمة رقمية خارج النطاق في الحقل " + key + ".",
                    "Numeric value out of range for " + key + ".");
        }
        return value.intValue();
    }

    public static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && ("true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString()));
    }

    public static LocalDate date(Map<String, Object> body, String key, boolean required) {
        String value = optional(body, key);
        if (value == null) {
            if (required) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "الحقل " + key + " مطلوب.", "Field " + key + " is required.");
            }
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة التاريخ غير صحيحة في الحقل " + key + ".",
                    "Invalid date format for " + key + ".");
        }
    }

    public static LocalDateTime dateTime(Map<String, Object> body, String key, boolean required) {
        String value = optional(body, key);
        if (value == null) {
            if (required) {
                throw AppException.badRequest("VALIDATION_FAILED",
                        "الحقل " + key + " مطلوب.", "Field " + key + " is required.");
            }
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة الوقت غير صحيحة في الحقل " + key + ".",
                    "Invalid date-time format for " + key + ".");
        }
    }

    /**
     * Normalises a person name for duplicate detection and search:
     * removes diacritics, unifies Arabic letter variants and collapses spaces.
     */
    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        decomposed = decomposed
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ة', 'ه')
                .replace('ى', 'ي')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي');
        decomposed = decomposed.replaceAll("[^\\p{L}\\p{N}]+", " ");
        return decomposed.trim().replaceAll("\\s+", " ");
    }

    public static Integer age(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        return Math.max(0, Period.between(birthDate, LocalDate.now()).getYears());
    }

    public static int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    public static Map<String, Object> pageResult(List<Map<String, Object>> items, int page, int size,
                                                 long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        result.put("totalPages", size == 0 ? 0 : (int) Math.ceil((double) total / size));
        return result;
    }

    public static String json(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return JsonUtil.toJson(map);
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
