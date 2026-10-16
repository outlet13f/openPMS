package com.openpms.cicd;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A tiny commit history for compare answers: commit(sha, message, parents...). Times go up by a minute per commit. */
final class CommitGraph {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-15T10:00:00Z");
    static final String WEB = "https://gitlab.example.com/g/r/-/commit/";

    private record Node(int index, String sha, String message, List<String> parents) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    CommitGraph commit(String sha, String message, String... parents) {
        nodes.put(sha, new Node(nodes.size(), sha, message, List.of(parents)));
        return this;
    }

    /** "%040x": a full-length sha for long chains. */
    String sha(int n) {
        return String.format("%040x", n);
    }

    private Set<String> reachable(String from) {
        var seen = new LinkedHashSet<String>();
        var todo = new ArrayList<String>(List.of(from));
        while (!todo.isEmpty()) {
            var s = todo.remove(todo.size() - 1);
            var n = nodes.get(s);
            if (n == null || !seen.add(s)) continue;
            todo.addAll(n.parents());
        }
        return seen;
    }

    /** git log base..head: reachable from head and not from base, newest first. */
    List<String> range(String base, String head) {
        var out = new ArrayList<>(reachable(head));
        out.removeAll(reachable(base));
        out.sort((a, b) -> Integer.compare(nodes.get(b).index(), nodes.get(a).index()));
        return out;
    }

    /** The newest common ancestor (a commit counts as its own ancestor); null when there is none. */
    String mergeBase(String a, String b) {
        var common = reachable(a);
        common.retainAll(reachable(b));
        return common.stream().max((x, y) -> Integer.compare(nodes.get(x).index(), nodes.get(y).index())).orElse(null);
    }

    boolean knows(String sha) {
        return nodes.containsKey(sha);
    }

    private OffsetDateTime timeOf(String sha) {
        return START.plusMinutes(nodes.get(sha).index());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String param(String rawQuery, String name) {
        for (var part : (rawQuery == null ? "" : rawQuery).split("&")) {
            var eq = part.indexOf('=');
            if (eq > 0 && URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static List<String> params(String rawQuery, String name) {
        var out = new ArrayList<String>();
        for (var part : (rawQuery == null ? "" : rawQuery).split("&")) {
            var eq = part.indexOf('=');
            if (eq > 0 && URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                out.add(URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static final FakeGitServer.Reply NOT_FOUND = new FakeGitServer.Reply(404, "{\"message\":\"404 Not Found\"}");

    /** GitLab repository/compare: from= and to=; the commits come oldest first, with the empty diffs list GitLab sends too. */
    FakeGitServer.Reply gitlabCompare(String rawQuery) {
        var from = param(rawQuery, "from");
        var to = param(rawQuery, "to");
        if (from == null || to == null || !knows(from) || !knows(to)) return NOT_FOUND;
        var range = new ArrayList<>(range(from, to));
        java.util.Collections.reverse(range);
        var items = new ArrayList<String>();
        for (var sha : range) {
            var n = nodes.get(sha);
            items.add("{\"id\":" + quote(sha) + ",\"short_id\":" + quote(sha.substring(0, Math.min(8, sha.length())))
                    + ",\"title\":" + quote(n.message()) + ",\"message\":" + quote(n.message())
                    + ",\"author_name\":\"박준호\",\"author_email\":\"junho.park@openpms.local\""
                    + ",\"committed_date\":" + quote(timeOf(sha).toString()) + ",\"web_url\":" + quote(WEB + sha) + "}");
        }
        return new FakeGitServer.Reply(200, "{\"commits\":[" + String.join(",", items) + "],\"diffs\":[],\"compare_timeout\":false,\"compare_same_ref\":"
                + from.equals(to) + "}");
    }

    /** GitLab repository/merge_base: refs[]=a&refs[]=b. */
    FakeGitServer.Reply gitlabMergeBase(String rawQuery) {
        var refs = params(rawQuery, "refs[]");
        if (refs.size() != 2 || !knows(refs.get(0)) || !knows(refs.get(1))) return NOT_FOUND;
        var base = mergeBase(refs.get(0), refs.get(1));
        if (base == null) return NOT_FOUND;
        var n = nodes.get(base);
        return new FakeGitServer.Reply(200, "{\"id\":" + quote(base) + ",\"title\":" + quote(n.message()) + "}");
    }

    /** GitHub compare/{base}...{head}: status ahead, behind, identical or diverged; commits oldest first. */
    FakeGitServer.Reply githubCompare(String path) {
        var spec = path.substring(path.lastIndexOf("/compare/") + "/compare/".length());
        var dots = spec.indexOf("...");
        if (dots < 0) return NOT_FOUND;
        var base = URLDecoder.decode(spec.substring(0, dots), StandardCharsets.UTF_8);
        var head = URLDecoder.decode(spec.substring(dots + 3), StandardCharsets.UTF_8);
        if (!knows(base) || !knows(head)) return NOT_FOUND;
        var added = range(base, head);
        var removed = range(head, base);
        String status = base.equals(head) ? "identical" : removed.isEmpty() ? "ahead" : added.isEmpty() ? "behind" : "diverged";
        var oldestFirst = new ArrayList<>(added);
        java.util.Collections.reverse(oldestFirst);
        var items = new ArrayList<String>();
        for (var sha : oldestFirst) {
            var n = nodes.get(sha);
            var when = quote(timeOf(sha).toString());
            items.add("{\"sha\":" + quote(sha) + ",\"html_url\":" + quote("https://github.com/g/r/commit/" + sha)
                    + ",\"commit\":{\"message\":" + quote(n.message()) + ",\"author\":{\"name\":\"박준호\",\"email\":\"junho.park@openpms.local\",\"date\":" + when
                    + "},\"committer\":{\"name\":\"박준호\",\"email\":\"junho.park@openpms.local\",\"date\":" + when + "}}}");
        }
        return new FakeGitServer.Reply(200, "{\"status\":" + quote(status) + ",\"ahead_by\":" + added.size() + ",\"behind_by\":" + removed.size()
                + ",\"total_commits\":" + added.size() + ",\"commits\":[" + String.join(",", items) + "],\"files\":[]}");
    }
}
