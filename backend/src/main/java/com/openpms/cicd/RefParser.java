package com.openpms.cicd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Work package numbers in commit messages, MR/PR titles and descriptions ("#123") and branch names ("feature/123-login").
 * A branch under release/ or hotfix/, or one whose first part looks like a version (v1.2), is not read: "release/2026-10"
 * is a date, not work package 2026. At most 20 numbers per event, 1–12 digits.
 */
public final class RefParser {
    public record Ref(long number, String source) {}

    static final int MAX = 20;
    // "#" must follow start of line, whitespace, "(", "[" or ","; the digits must not run into a word character.
    private static final Pattern TEXT = Pattern.compile("(?<=^|[\\s(\\[,])#(\\d{1,12})(?![\\w\\d])", Pattern.MULTILINE);
    private static final Pattern TOO_LONG = Pattern.compile("#\\d{13,}");
    private static final Pattern BRANCH_PART = Pattern.compile("^(\\d{1,12})(?:[-_].*)?$");
    private static final Pattern SKIP_BRANCH = Pattern.compile("^(release|hotfix|v\\d)", Pattern.CASE_INSENSITIVE);

    private RefParser() {}

    public static List<Ref> inText(String text, String source) {
        var out = new ArrayList<Ref>();
        if (text == null) return out;
        var cleaned = TOO_LONG.matcher(text).replaceAll(" ");
        var m = TEXT.matcher(cleaned);
        while (m.find() && out.size() < MAX) out.add(new Ref(Long.parseLong(m.group(1)), source));
        return merge(out);
    }

    public static List<Ref> inBranch(String branch) {
        if (branch == null || branch.isBlank() || SKIP_BRANCH.matcher(branch).find()) return List.of();
        var parts = branch.split("/");
        if (parts.length == 0) return List.of();   // "/" alone splits to nothing
        var m = BRANCH_PART.matcher(parts[parts.length - 1]);
        return m.matches() ? List.of(new Ref(Long.parseLong(m.group(1)), "branch")) : List.of();
    }

    @SafeVarargs
    public static List<Ref> merge(List<Ref>... lists) {
        var byNumber = new LinkedHashMap<Long, Ref>();
        for (var l : lists) for (var r : l) if (byNumber.size() < MAX) byNumber.putIfAbsent(r.number(), r);
        return List.copyOf(byNumber.values());
    }
}
