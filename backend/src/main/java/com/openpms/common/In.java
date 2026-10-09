package com.openpms.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading JSON request bodies as maps, so "field absent" (leave it alone) and "field: null" (clear it) stay
 * different, as the PATCH routes need. Bad values become 422 field errors, never 500s.
 */
public final class In {
    private In() {}

    public static boolean has(Map<String, Object> m, String key) {
        return m != null && m.containsKey(key);
    }

    public static String str(Map<String, Object> m, String key) {
        var v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static UUID uuid(Map<String, Object> m, String key, String field) {
        var v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) return null;
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw ApiException.field(field, "알 수 없는 값입니다.");
        }
    }

    public static UUID uuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static LocalDate date(Map<String, Object> m, String key, String field) {
        var v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) return null;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (DateTimeParseException e) {
            throw ApiException.field(field, "날짜 형식이 올바르지 않습니다.");
        }
    }

    public static Integer integer(Map<String, Object> m, String key, String field) {
        var v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) return null;
        try {
            return v instanceof Number n ? Integer.valueOf(n.intValue()) : Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.field(field, "숫자를 입력하세요.");
        }
    }

    public static BigDecimal decimal(Map<String, Object> m, String key, String field) {
        var v = m == null ? null : m.get(key);
        if (v == null || "".equals(v)) return null;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.field(field, "숫자를 입력하세요.");
        }
    }

    public static Boolean bool(Map<String, Object> m, String key) {
        var v = m == null ? null : m.get(key);
        return v == null ? null : v instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static List<String> strings(Map<String, Object> m, String key) {
        var v = m == null ? null : m.get(key);
        if (!(v instanceof List<?> l)) return null;
        return l.stream().map(x -> x == null ? null : String.valueOf(x)).toList();
    }

    public static List<UUID> uuids(Map<String, Object> m, String key, String field) {
        var l = strings(m, key);
        if (l == null) return List.of();
        try {
            return l.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.field(field, "알 수 없는 값입니다.");
        }
    }
}
