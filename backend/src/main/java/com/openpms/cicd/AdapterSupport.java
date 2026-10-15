package com.openpms.cicd;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/** Small helpers the two adapters share. */
final class AdapterSupport {
    private AdapterSupport() {}

    private static final DateTimeFormatter SPACED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    /** Text of a value node, "" when missing, null or not a plain value. */
    static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isValueNode()) return "";
        return n.asString();
    }

    static long number(JsonNode n) {
        return n != null && n.isNumber() ? n.longValue() : 0L;
    }

    static boolean flag(JsonNode n) {
        return n != null && n.isBoolean() && n.booleanValue();
    }

    /** ISO-8601, or GitLab's webhook form "2026-10-15 10:30:00 +0900" / "... UTC". Null when empty or unreadable. */
    static OffsetDateTime time(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s.endsWith(" UTC") ? s.substring(0, s.length() - 4) + " +0000" : s, SPACED);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** The same instant, spelled the same way whatever offset or fraction it came in (GitLab's webhook gives seconds, its REST API milliseconds; keys must match). */
    static String instant(OffsetDateTime t) {
        return t == null ? "" : t.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /** First line of a message, at most 500 characters. */
    static String title(String message) {
        if (message == null) return "";
        var s = message.strip();
        var nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).stripTrailing();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String trimSlash(String base) {
        var s = base == null ? "" : base.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
