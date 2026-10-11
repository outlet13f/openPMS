package com.openpms.wp;

import static com.openpms.db.Tables.WORK_PACKAGES;
import static com.openpms.db.Tables.WORK_PACKAGE_ASSIGNEES;

import com.openpms.common.ApiException;
import com.openpms.core.Snapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 공동 담당자 (replica/fixes.md 6번): everyone who has a work package, in order, each with an optional estimate of their
 * own hours. The first is the main assignee, and work_packages.assignee_id always names that person (null when nobody
 * has it): sorting, grouping and older API clients see the main assignee; filters, notifications, workflow rules
 * ("담당자일 때"), date alerts, assignee boards and the team planner see everyone. Every change goes through here.
 */
@Component
public class Assignees {
    public static final int MAX = 20;

    public record Entry(UUID userId, BigDecimal workHours) {}

    private final DSLContext db;

    public Assignees(DSLContext db) {
        this.db = db;
    }

    /** Everyone on these items, in order. Items nobody has are absent. */
    public Map<UUID, List<Entry>> of(Collection<UUID> ids) {
        var out = new HashMap<UUID, List<Entry>>();
        if (ids.isEmpty()) return out;
        db.select(WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID, WORK_PACKAGE_ASSIGNEES.USER_ID, WORK_PACKAGE_ASSIGNEES.WORK_HOURS).from(WORK_PACKAGE_ASSIGNEES)
                .where(WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID.in(ids)).orderBy(WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID, WORK_PACKAGE_ASSIGNEES.POSITION)
                .fetch().forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(new Entry(r.value2(), r.value3())));
        return out;
    }

    public List<Entry> of(UUID id) {
        return of(List.of(id)).getOrDefault(id, List.of());
    }

    public static List<UUID> ids(List<Entry> list) {
        return list.stream().map(Entry::userId).toList();
    }

    /**
     * Stores the list as it is and points assignee_id at its first person. The caller has checked it (see {@link #read})
     * and bumps the item's lock version.
     */
    public void write(UUID workPackageId, List<Entry> list) {
        db.deleteFrom(WORK_PACKAGE_ASSIGNEES).where(WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID.eq(workPackageId)).execute();
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            db.insertInto(WORK_PACKAGE_ASSIGNEES).set(WORK_PACKAGE_ASSIGNEES.WORK_PACKAGE_ID, workPackageId).set(WORK_PACKAGE_ASSIGNEES.USER_ID, e.userId())
                    .set(WORK_PACKAGE_ASSIGNEES.POSITION, (short) i).set(WORK_PACKAGE_ASSIGNEES.WORK_HOURS, e.workHours()).execute();
        }
        db.update(WORK_PACKAGES).set(WORK_PACKAGES.ASSIGNEE_ID, list.isEmpty() ? null : list.get(0).userId())
                .where(WORK_PACKAGES.ID.eq(workPackageId)).execute();
    }

    /**
     * The "assignees" of a request: [{userId, workHours}] (or plain ids), in order. Everyone must be a member of the
     * project; nobody twice; hours from 0 to 9999 in steps of 0.01.
     */
    public static List<Entry> read(Object raw, Snapshot s, UUID projectId) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) throw ApiException.field("assignees", "담당자 목록 형식이 올바르지 않습니다.");
        if (items.size() > MAX) throw ApiException.field("assignees", "담당자는 " + MAX + "명까지 지정할 수 있습니다.");
        var out = new ArrayList<Entry>();
        var seen = new HashSet<UUID>();
        for (var item : items) {
            Object id = item instanceof Map<?, ?> m ? m.get("userId") : item;
            Object hours = item instanceof Map<?, ?> m ? m.get("workHours") : null;
            UUID userId;
            try {
                userId = UUID.fromString(String.valueOf(id));
            } catch (IllegalArgumentException e) {
                throw ApiException.field("assignees", "담당자를 확인하세요.");
            }
            if (!seen.add(userId)) throw ApiException.field("assignees", "같은 사람을 두 번 지정할 수 없습니다.");
            if (!s.isMember(userId, projectId)) throw ApiException.field("assignees", "프로젝트 멤버만 지정할 수 있습니다.");
            BigDecimal h = null;
            if (hours != null && !"".equals(hours)) {
                try {
                    h = new BigDecimal(String.valueOf(hours)).setScale(2, RoundingMode.HALF_UP);
                } catch (NumberFormatException e) {
                    throw ApiException.field("assignees", "예상 시간은 숫자로 적으세요.");
                }
                if (h.signum() < 0 || h.compareTo(BigDecimal.valueOf(9999)) > 0) throw ApiException.field("assignees", "예상 시간은 0–9999시간입니다.");
            }
            out.add(new Entry(userId, h));
        }
        return out;
    }

    /**
     * The list after an older-style change of the main assignee: nobody → nobody has it; someone already on it → they move
     * to the front (keeping their hours); someone new → they take the main assignee's place.
     */
    public static List<Entry> withMain(List<Entry> current, UUID main) {
        if (main == null) return List.of();
        var out = new ArrayList<Entry>();
        var mine = current.stream().filter(e -> e.userId().equals(main)).findFirst();
        out.add(mine.orElse(new Entry(main, null)));
        for (int i = 0; i < current.size(); i++) {
            var e = current.get(i);
            if (e.userId().equals(main)) continue;
            if (i == 0 && mine.isEmpty()) continue; // replaced
            out.add(e);
        }
        return out;
    }

    /** One person handed over to another (a card dragged between two people's columns): same place, the hours stay with the item. */
    public static List<Entry> swap(List<Entry> current, UUID from, UUID to) {
        if (from == null || from.equals(to)) return withMain(current, to);
        if (current.stream().noneMatch(e -> e.userId().equals(from))) return withMain(current, to);
        var out = new ArrayList<Entry>();
        for (var e : current) {
            if (e.userId().equals(to)) continue;
            out.add(e.userId().equals(from) ? new Entry(to, e.workHours()) : e);
        }
        return out;
    }

    public static boolean same(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).userId().equals(b.get(i).userId())) return false;
            var x = a.get(i).workHours();
            var y = b.get(i).workHours();
            if (!(x == null ? y == null : y != null && x.compareTo(y) == 0)) return false;
        }
        return true;
    }

    /** How the list goes into a journal: [{"userId": …, "workHours": …}]. */
    public static List<Map<String, Object>> journal(List<Entry> list) {
        return list.stream().map(e -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("userId", e.userId().toString());
            m.put("workHours", e.workHours() == null ? null : e.workHours().stripTrailingZeros().toPlainString());
            return (Map<String, Object>) m;
        }).toList();
    }

    /** Whether the list says anything the main assignee alone does not (a second person, or hours). */
    public static boolean beyondMain(List<Entry> list) {
        return list.size() > 1 || list.stream().anyMatch(e -> e.workHours() != null);
    }

    public static boolean has(List<Entry> list, UUID userId) {
        return list.stream().anyMatch(e -> Objects.equals(e.userId(), userId));
    }
}
