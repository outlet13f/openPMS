package com.openpms.misc;

import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.falseCondition;

import com.openpms.api.Dto;
import com.openpms.common.Html;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Text;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S22 검색 결과: work packages by #number, title, description or comment (title matches first, then newest), and
 * projects by name, identifier or description. Only what the person can see, including items shared with them.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {
    private static final int PAGE_SIZE = 20;
    /** More than this many work packages is a sign the words are too general; the counts say so. */
    private static final int LIMIT = 1000;
    private static final List<String> RANK = List.of("number", "subject", "description", "comment");

    private final Current current;
    private final DSLContext db;

    public SearchController(Current current, DSLContext db) {
        this.current = current;
        this.db = db;
    }

    /** About 50 characters either side of the first match, with … where text was cut (frontend: snippetAround). */
    static String snippet(String text, String needle) {
        var t = text.replaceAll("\\s+", " ").trim();
        int i = t.toLowerCase().indexOf(needle);
        if (i < 0) return t.substring(0, Math.min(t.length(), 100));
        int start = Math.max(0, i - 50);
        int end = Math.min(t.length(), i + needle.length() + 50);
        return (start > 0 ? "…" : "") + t.substring(start, end) + (end < t.length() ? "…" : "");
    }

    /** Items I may see: projects with wp.view (not archived) and items shared with me there. */
    private Condition visible(Ctx ctx) {
        var s = ctx.s();
        var projects = ctx.visibleProjects("wp.view").stream().filter(p -> p.archivedAt() == null).map(Snapshot.ProjectRow::id).toList();
        Condition c = projects.isEmpty() ? falseCondition() : WORK_PACKAGES.PROJECT_ID.in(projects);
        var shared = s.shares(ctx.userId()).roles().keySet();
        var open = s.projects().values().stream().filter(p -> p.archivedAt() == null).map(Snapshot.ProjectRow::id).toList();
        if (!shared.isEmpty()) c = c.or(WORK_PACKAGES.ID.in(shared).and(WORK_PACKAGES.PROJECT_ID.in(open)));
        return c;
    }

    @GetMapping("/search")
    public Dto.SearchResults search(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String scope,
                                    @RequestParam(defaultValue = "1") int page) {
        var ctx = current.ctx();
        var s = ctx.s();
        scope = List.of("all", "work_packages", "projects").contains(scope) ? scope : "all";
        page = Math.max(1, page);
        var needle = q.trim().toLowerCase();
        if (needle.isEmpty()) return new Dto.SearchResults(q, scope, new Dto.SearchCounts(0, 0), List.of(), 0, 1, PAGE_SIZE);

        // work packages: number, title, description in one query; comments in a second
        Long number = null;
        try {
            number = Long.parseLong(needle.replaceFirst("^#", ""));
        } catch (NumberFormatException ignored) {
            // words only
        }
        var visible = visible(ctx);
        Condition text = WORK_PACKAGES.SUBJECT.containsIgnoreCase(needle).or(WORK_PACKAGES.DESCRIPTION_TEXT.containsIgnoreCase(needle));
        if (number != null) text = text.or(WORK_PACKAGES.NUMBER.eq(number));
        var hits = new LinkedHashMap<UUID, Dto.WorkPackageResult>();
        for (var w : db.selectFrom(WORK_PACKAGES).where(visible.and(text)).orderBy(WORK_PACKAGES.UPDATED_AT.desc()).limit(LIMIT).fetch()) {
            String where;
            String snip = null;
            if (number != null && w.getNumber() == number) where = "number";
            else if (w.getSubject().toLowerCase().contains(needle)) where = "subject";
            else {
                where = "description";
                snip = snippet(w.getDescriptionText(), needle);
            }
            hits.put(w.getId(), result(s, w, where, snip));
        }
        var commented = db.select(JOURNALS.WORK_PACKAGE_ID, JOURNALS.NOTES_TEXT, JOURNALS.IS_INTERNAL).from(JOURNALS)
                .join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(JOURNALS.WORK_PACKAGE_ID))
                .where(visible, JOURNALS.NOTES_TEXT.containsIgnoreCase(needle)).orderBy(JOURNALS.CREATED_AT).limit(LIMIT).fetch();
        record Note(String text, boolean internal) {}
        var notes = new LinkedHashMap<UUID, List<Note>>();
        for (var r : commented) if (!hits.containsKey(r.value1())) notes.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(new Note(r.value2(), r.value3()));
        if (!notes.isEmpty()) {
            for (var w : db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(notes.keySet())).fetch()) {
                var project = s.project(w.getProjectId()).orElseThrow();
                // internal comments only for those who may read them
                notes.get(w.getId()).stream().filter(n -> !n.internal() || ctx.can(project, "wp.view_internal_comments")).findFirst()
                        .ifPresent(n -> hits.put(w.getId(), result(s, w, "comment", snippet(n.text(), needle))));
            }
        }
        var wps = new ArrayList<>(hits.values());
        wps.sort(Comparator.comparingInt((Dto.WorkPackageResult r) -> RANK.indexOf(r.matchedIn())).thenComparing(Dto.WorkPackageResult::updatedAt, Comparator.reverseOrder()));

        var projects = new ArrayList<Dto.ProjectResult>();
        for (var p : ctx.visibleProjects("project.view")) {
            if (p.archivedAt() != null) continue;
            var desc = Html.text(p.descriptionHtml());
            if (p.name().toLowerCase().contains(needle) || p.identifier().toLowerCase().contains(needle)) projects.add(new Dto.ProjectResult("project", Refs.project(p), null));
            else if (desc.toLowerCase().contains(needle)) projects.add(new Dto.ProjectResult("project", Refs.project(p), snippet(desc, needle)));
        }
        projects.sort(Comparator.comparing(r -> r.project().name(), Text.KOREAN));

        List<Object> all = new ArrayList<>();
        if (!scope.equals("work_packages")) all.addAll(projects);
        if (!scope.equals("projects")) all.addAll(wps);
        int from = Math.min(all.size(), (page - 1) * PAGE_SIZE);
        return new Dto.SearchResults(q, scope, new Dto.SearchCounts(wps.size(), projects.size()), all.subList(from, Math.min(all.size(), from + PAGE_SIZE)),
                all.size(), page, PAGE_SIZE);
    }

    private static Dto.WorkPackageResult result(Snapshot s, WorkPackagesRecord w, String where, String snippet) {
        return new Dto.WorkPackageResult("work_package", w.getNumber(), w.getSubject(), Refs.type(s, w.getTypeId()), Refs.status(s, w.getStatusId()),
                Refs.project(s, w.getProjectId()), where, snippet, w.getUpdatedAt());
    }
}
