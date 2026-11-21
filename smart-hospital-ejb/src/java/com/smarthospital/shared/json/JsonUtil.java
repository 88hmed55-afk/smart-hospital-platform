package com.smarthospital.shared.json;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import java.util.Map;

/**
 * Small, deterministic JSON writer for audit payloads. Keys keep insertion
 * order, so the same logical map always produces the same string.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        return object(value).build().toString();
    }

    private static JsonObjectBuilder object(Map<?, ?> map) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                builder.addNull(key);
            } else if (value instanceof Map<?, ?> nested) {
                builder.add(key, object(nested));
            } else if (value instanceof Iterable<?> iterable) {
                builder.add(key, array(iterable));
            } else if (value instanceof Boolean bool) {
                builder.add(key, bool);
            } else if (value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte) {
                builder.add(key, ((Number) value).longValue());
            } else if (value instanceof Number number) {
                builder.add(key, number.doubleValue());
            } else {
                builder.add(key, String.valueOf(value));
            }
        }
        return builder;
    }

    private static JsonArrayBuilder array(Iterable<?> values) {
        JsonArrayBuilder builder = Json.createArrayBuilder();
        for (Object value : values) {
            if (value == null) {
                builder.addNull();
            } else if (value instanceof Map<?, ?> nested) {
                builder.add(object(nested));
            } else if (value instanceof Iterable<?> iterable) {
                builder.add(array(iterable));
            } else if (value instanceof Boolean bool) {
                builder.add(bool);
            } else if (value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte) {
                builder.add(((Number) value).longValue());
            } else if (value instanceof Number number) {
                builder.add(number.doubleValue());
            } else {
                builder.add(String.valueOf(value));
            }
        }
        return builder;
    }

    /** Exposed for tests: a value that is already a JSON literal is returned as-is. */
    public static JsonValue raw(String json) {
        return json == null ? JsonValue.NULL : Json.createReader(new java.io.StringReader(json)).readValue();
    }
}
