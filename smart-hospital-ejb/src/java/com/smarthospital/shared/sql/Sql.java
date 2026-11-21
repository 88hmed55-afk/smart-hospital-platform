package com.smarthospital.shared.sql;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JDBC helper for the clinical modules.
 *
 * <p>Phase 3 tables are high-volume and mostly append-only, so the repositories
 * use explicit SQL instead of one entity per table. The helper always runs on
 * the JTA connection owned by the current EJB transaction, so writes and audit
 * rows commit or roll back together. Aggregate roots keep a {@code version}
 * column and updates use {@code WHERE id = ? AND version = ?}, which gives the
 * same optimistic-concurrency guarantee as {@code @Version}.
 */
public final class Sql {

    private Sql() {
    }

    public static List<Map<String, Object>> query(EntityManager em, String sql, Object... params) {
        try (PreparedStatement statement = prepare(em, sql, params);
             ResultSet rs = statement.executeQuery()) {
            return read(rs);
        } catch (SQLException ex) {
            throw new IllegalStateException("Query failed: " + ex.getMessage(), ex);
        }
    }

    public static Map<String, Object> one(EntityManager em, String sql, Object... params) {
        List<Map<String, Object>> rows = query(em, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(EntityManager em, String sql, Object... params) {
        try (PreparedStatement statement = prepare(em, sql, params)) {
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Update failed: " + ex.getMessage(), ex);
        }
    }

    /** Executes an insert and returns the generated identity, or 0 if none. */
    public static long insert(EntityManager em, String sql, Object... params) {
        try (PreparedStatement statement = prepare(em, sql, params, Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Insert failed: " + ex.getMessage(), ex);
        }
    }

    /** Runs a sequence increment under a row lock and returns the new value. */
    public static int nextSequence(EntityManager em, String name, String period) {
        int updated = update(em,
                "UPDATE number_sequences SET current_value = current_value + 1 "
                        + "WHERE seq_name = ? AND period = ?", name, period);
        if (updated == 0) {
            try {
                insert(em,
                        "INSERT INTO number_sequences (seq_name, period, current_value) VALUES (?, ?, 1)",
                        name, period);
                return 1;
            } catch (IllegalStateException duplicate) {
                update(em,
                        "UPDATE number_sequences SET current_value = current_value + 1 "
                                + "WHERE seq_name = ? AND period = ?", name, period);
            }
        }
        Map<String, Object> row = one(em,
                "SELECT current_value FROM number_sequences WHERE seq_name = ? AND period = ?",
                name, period);
        if (row == null) {
            // Never fall back to 0: a zero sequence silently reuses document numbers.
            throw new IllegalStateException("Sequence missing after allocation: " + name + "/" + period);
        }
        return intValue(row, "currentValue", 0);
    }

    private static PreparedStatement prepare(EntityManager em, String sql, Object[] params)
            throws SQLException {
        return prepare(em, sql, params, Statement.NO_GENERATED_KEYS);
    }

    private static PreparedStatement prepare(EntityManager em, String sql, Object[] params, int keys)
            throws SQLException {
        Connection connection = em.unwrap(Connection.class);
        PreparedStatement statement = connection.prepareStatement(sql, keys);
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else if (value instanceof LocalDateTime dateTime) {
                statement.setObject(i + 1, dateTime);
            } else if (value instanceof LocalDate date) {
                statement.setObject(i + 1, date);
            } else if (value instanceof LocalTime time) {
                statement.setObject(i + 1, time);
            } else {
                statement.setObject(i + 1, value);
            }
        }
        return statement;
    }

    private static List<Map<String, Object>> read(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                row.put(camel(meta.getColumnLabel(i)), normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    private static String camel(String label) {
        String lower = label.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        boolean upper = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Typed readers
    // ------------------------------------------------------------------

    public static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }

    public static Long longValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static long longValue(Map<String, Object> row, String key, long fallback) {
        Long value = longValue(row, key);
        return value == null ? fallback : value;
    }

    public static int intValue(Map<String, Object> row, String key, int fallback) {
        Long value = longValue(row, key);
        return value == null ? fallback : value.intValue();
    }

    public static boolean boolValue(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && ("1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString()));
    }

    public static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
