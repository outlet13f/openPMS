package com.openpms.cicd;

import com.openpms.common.ApiException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The commit number in a container image's tag ("web/portal:1a2b3c4"), by a connection's own pattern (Jenkins, Argo CD).
 * The tag is what follows the last ':' after the last '/', before any "@digest", at most 128 characters. The first group of
 * the pattern (or the whole match) must be 7-40 hex digits and not digits only (a date or build number is no commit).
 * A pattern is written by a delegated CI/CD 연동 관리자, so matching gives up after a fixed number of character reads.
 */
final class ImageTags {
    static final String DEFAULT_PATTERN = "[0-9a-f]{7,40}";
    static final int MAX_PATTERN = 200;
    static final int MAX_TAG = 128;
    static final long STEP_BUDGET = 200_000;
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private ImageTags() {}

    static String validate(String pattern) {
        if (pattern == null || pattern.isBlank()) return null;
        var p = pattern.strip();
        var bad = ApiException.field("imageTagPattern", "이미지 태그 규칙이 올바르지 않습니다.");
        if (p.length() > MAX_PATTERN || p.chars().anyMatch(c -> c < 0x20)) throw bad;
        try {
            Pattern.compile(p);
        } catch (PatternSyntaxException e) {
            throw bad;
        }
        return p;
    }

    static String tagOf(String image) {
        if (image == null) return "";
        var s = image.strip();
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        int slash = s.lastIndexOf('/');
        int colon = s.lastIndexOf(':');
        if (colon <= slash) return "";
        var tag = s.substring(colon + 1);
        return tag.isEmpty() || tag.length() > MAX_TAG ? "" : tag;
    }

    static Optional<String> sha(String image, String pattern) {
        var tag = tagOf(image);
        if (tag.isEmpty()) return Optional.empty();
        Pattern p;
        try {
            p = Pattern.compile(pattern == null || pattern.isBlank() ? DEFAULT_PATTERN : pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
        try {
            var m = p.matcher(new Bounded(tag, new long[] {STEP_BUDGET}));
            if (!m.find()) return Optional.empty();
            var g = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            var found = g.toLowerCase(Locale.ROOT);
            return HEX.matcher(found).matches() && !DIGITS.matcher(found).matches() ? Optional.of(found) : Optional.empty();
        } catch (TooSlow e) {
            return Optional.empty();
        }
    }

    private static final class TooSlow extends RuntimeException {
        TooSlow() { super(null, null, false, false); }
    }

    /** A CharSequence whose reads are counted; past the budget every read throws, which ends a runaway match. */
    private record Bounded(CharSequence text, long[] left) implements CharSequence {
        @Override public int length() { return text.length(); }
        @Override public char charAt(int i) {
            if (--left[0] < 0) throw new TooSlow();
            return text.charAt(i);
        }
        @Override public CharSequence subSequence(int s, int e) { return new Bounded(text.subSequence(s, e), left); }
        @Override public String toString() { return text.toString(); }
    }
}
