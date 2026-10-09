package com.openpms.wp;

import com.openpms.common.Dates;
import com.openpms.common.Dates.WorkCalendar;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduling rules S1–S8 and S11 (architecture.md §3.3) over an in-memory graph: no database, no Spring.
 * Callers load the graph (ScheduleStore), run a rule, and write back the returned changes.
 */
public final class Schedule {
    public static final class Node {
        public final UUID id;
        public final long number;
        public final String subject;
        public UUID parentId;
        public final boolean milestone;
        public LocalDate start;
        public LocalDate due;
        public Integer duration;
        public boolean ignore;
        public boolean manual;

        public Node(UUID id, long number, String subject, UUID parentId, boolean milestone, LocalDate start, LocalDate due, Integer duration,
                    boolean ignore, boolean manual) {
            this.id = id;
            this.number = number;
            this.subject = subject;
            this.parentId = parentId;
            this.milestone = milestone;
            this.start = start;
            this.due = due;
            this.duration = duration;
            this.ignore = ignore;
            this.manual = manual;
        }
    }

    public record Link(UUID from, UUID to, int lag) {}

    public record Triple(LocalDate start, LocalDate due, Integer duration) {}

    /** What the user sent: each date field is either absent or given (possibly null). */
    public static final class Input {
        public boolean hasStart, hasDue, hasDuration;
        public LocalDate start, due;
        public Integer duration;
        public List<String> touched;
    }

    public record Change(Node node, Map<String, List<Object>> changes, Map<String, Object> cause) {}

    private final Map<UUID, Node> nodes;
    private final List<Link> precedes;
    private final WorkCalendar cal;
    private final Map<UUID, List<Node>> children = new HashMap<>();

    public Schedule(Collection<Node> nodes, List<Link> precedes, WorkCalendar cal) {
        this.nodes = new LinkedHashMap<>();
        nodes.forEach(n -> this.nodes.put(n.id, n));
        this.precedes = precedes;
        this.cal = cal;
        reindex();
    }

    public void reindex() {
        children.clear();
        for (var n : nodes.values()) if (n.parentId != null) children.computeIfAbsent(n.parentId, k -> new ArrayList<>()).add(n);
    }

    public Node node(UUID id) {
        return nodes.get(id);
    }

    public List<Node> kids(UUID id) {
        return children.getOrDefault(id, List.of());
    }

    /* ---------- S1–S3 ---------- */

    public Triple resolve(Node w, boolean milestone, boolean ignore, Input in) {
        var start = in.hasStart ? in.start : w.start;
        var due = in.hasDue ? in.due : w.due;
        var duration = in.hasDuration ? in.duration : w.duration;
        var touched = in.touched;
        if (touched == null) {
            touched = new ArrayList<>();
            if (in.hasStart) touched.add("startDate");
            if (in.hasDue) touched.add("dueDate");
            if (in.hasDuration) touched.add("duration");
        }
        if (milestone) {
            var d = start != null ? start : due;
            return new Triple(d, d, d != null ? 1 : null);
        }
        boolean keepDuration = touched.contains("duration");
        if (keepDuration && duration != null && start != null && !touched.contains("dueDate")) due = Dates.dueFromDuration(start, duration, cal, ignore);
        else if (keepDuration && duration != null && due != null && start == null) start = Dates.startFromDuration(due, duration, cal, ignore);
        if (start != null && due != null) {
            if (due.isBefore(start)) due = start;
            int n = Dates.countWorkingDays(start, due, cal, ignore);
            duration = n == 0 ? 1 : n;
        } else if (!keepDuration) duration = null;
        return new Triple(start, due, duration);
    }

    /* ---------- S4, S5, S7 ---------- */

    private record Pred(Node pred, int lag) {}

    /** Predecessors that constrain w: its own and its ancestors' (S5). */
    private List<Pred> predecessors(Node w) {
        var out = new ArrayList<Pred>();
        var cur = w;
        for (int i = 0; cur != null && i < 100; i++) {
            final var id = cur.id;
            for (var r : precedes) if (r.to().equals(id) && nodes.containsKey(r.from())) out.add(new Pred(nodes.get(r.from()), r.lag()));
            cur = cur.parentId == null ? null : nodes.get(cur.parentId);
        }
        return out;
    }

    /**
     * After {@code seeds} moved, recompute automatic successors and parents in dependency order. With
     * {@code dryRun} nothing on the nodes changes; {@code overrides} holds the draft values (preview).
     */
    public List<Change> propagate(Collection<UUID> seeds, Map<UUID, Triple> overrides, boolean dryRun) {
        var ov = overrides == null ? new HashMap<UUID, Triple>() : overrides;
        var result = new LinkedHashMap<UUID, Change>();
        var queue = new ArrayDeque<UUID>(seeds);
        int guard = 0;

        while (!queue.isEmpty() && guard++ < 5000) {
            var w = nodes.get(queue.poll());
            if (w == null) continue;

            for (var r : precedes) {
                if (!r.from().equals(w.id)) continue;
                var succ = nodes.get(r.to());
                if (succ == null || succ.manual || !kids(succ.id).isEmpty()) continue;
                var sv = view(succ, ov);
                LocalDate earliest = null;
                for (var p : predecessors(succ)) {
                    var pv = view(p.pred(), ov);
                    if (pv.due() == null) continue;
                    var candidate = Dates.nthWorkingDayAfter(pv.due(), p.lag() + 1, cal, succ.ignore);
                    if (earliest == null || candidate.isAfter(earliest)) earliest = candidate;
                }
                if (earliest == null) continue;
                int duration = sv.duration() != null ? sv.duration()
                        : (sv.start() != null && sv.due() != null ? Dates.countWorkingDays(sv.start(), sv.due(), cal, succ.ignore) : 1);
                var start = Dates.nextWorkingDay(earliest, cal, succ.ignore);
                var due = succ.milestone ? start : Dates.dueFromDuration(start, duration, cal, succ.ignore);
                if (record(result, ov, succ, new Triple(start, due, duration), cause("predecessor_moved", w.number))) queue.add(succ.id);
            }

            if (w.parentId != null) {
                var parent = nodes.get(w.parentId);
                if (parent != null && !parent.manual) {
                    LocalDate start = null, due = null;
                    for (var k : kids(parent.id)) {
                        var kv = view(k, ov);
                        if (kv.start() != null && (start == null || kv.start().isBefore(start))) start = kv.start();
                        if (kv.due() != null && (due == null || kv.due().isAfter(due))) due = kv.due();
                    }
                    Integer duration = start != null && due != null ? Dates.countWorkingDays(start, due, cal, parent.ignore) : null;
                    if (record(result, ov, parent, new Triple(start, due, duration), cause("child_moved", w.number))) queue.add(parent.id);
                }
            }
        }

        var changes = new ArrayList<>(result.values());
        if (!dryRun) {
            for (var c : changes) {
                var t = ov.get(c.node().id);
                c.node().start = t.start();
                c.node().due = t.due();
                c.node().duration = t.duration();
            }
        }
        return changes;
    }

    public Triple view(Node n, Map<UUID, Triple> ov) {
        var t = ov.get(n.id);
        return t != null ? t : new Triple(n.start, n.due, n.duration);
    }

    private static Map<String, Object> cause(String kind, long number) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        m.put("workPackageNumber", number);
        return m;
    }

    private boolean record(Map<UUID, Change> result, Map<UUID, Triple> ov, Node w, Triple next, Map<String, Object> cause) {
        var cur = view(w, ov);
        if (Objects.equals(cur.start(), next.start()) && Objects.equals(cur.due(), next.due())) return false;
        var entry = result.computeIfAbsent(w.id, k -> new Change(w, new LinkedHashMap<>(), cause));
        if (!Objects.equals(cur.start(), next.start())) {
            var before = entry.changes().containsKey("startDate") ? entry.changes().get("startDate").get(0) : iso(cur.start());
            entry.changes().put("startDate", Journals.listOf(before, iso(next.start())));
        }
        if (!Objects.equals(cur.due(), next.due())) {
            var before = entry.changes().containsKey("dueDate") ? entry.changes().get("dueDate").get(0) : iso(cur.due());
            entry.changes().put("dueDate", Journals.listOf(before, iso(next.due())));
        }
        ov.put(w.id, next);
        return true;
    }

    static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }

    /* ---------- S8 ---------- */

    /** Would "from precedes to" (or making from the parent of to) create a cycle? */
    public boolean wouldCycle(UUID fromId, UUID toId) {
        if (ancestors(toId).contains(fromId) || ancestors(fromId).contains(toId)) return true;
        var seen = new HashSet<UUID>();
        var stack = new ArrayDeque<UUID>();
        stack.push(toId);
        while (!stack.isEmpty()) {
            var id = stack.pop();
            if (id.equals(fromId)) return true;
            if (!seen.add(id)) continue;
            for (var r : precedes) if (r.from().equals(id)) stack.push(r.to());
            for (var k : kids(id)) stack.push(k.id);
        }
        return false;
    }

    private Set<UUID> ancestors(UUID id) {
        var out = new HashSet<UUID>();
        var cur = nodes.get(id);
        for (int i = 0; cur != null && cur.parentId != null && i < 100; i++) {
            out.add(cur.parentId);
            cur = nodes.get(cur.parentId);
        }
        return out;
    }

    /* ---------- S11 ---------- */

    /** After working days or holidays change: keep start and duration, recompute finish dates, then follow S7. */
    public List<Change> rescheduleAll() {
        var moved = new ArrayList<Change>();
        Map<String, Object> cause = new LinkedHashMap<>();
        cause.put("kind", "calendar_changed");
        for (var w : nodes.values()) {
            if (w.start == null || w.duration == null || w.milestone || (!w.manual && !kids(w.id).isEmpty())) continue;
            var start = Dates.nextWorkingDay(w.start, cal, w.ignore);
            var due = Dates.dueFromDuration(start, w.duration, cal, w.ignore);
            if (start.equals(w.start) && due.equals(w.due)) continue;
            var ch = new LinkedHashMap<String, List<Object>>();
            if (!start.equals(w.start)) ch.put("startDate", Journals.listOf(iso(w.start), iso(start)));
            if (!due.equals(w.due)) ch.put("dueDate", Journals.listOf(iso(w.due), iso(due)));
            w.start = start;
            w.due = due;
            moved.add(new Change(w, ch, cause));
        }
        var follow = propagate(moved.stream().map(c -> c.node().id).toList(), null, false).stream()
                .map(c -> new Change(c.node(), c.changes(), cause)).toList();
        var all = new ArrayList<>(moved);
        all.addAll(follow);
        return all;
    }
}
