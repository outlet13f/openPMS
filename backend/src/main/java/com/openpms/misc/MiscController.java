package com.openpms.misc;

import static com.openpms.db.Tables.NON_WORKING_DAYS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.core.Snapshot;
import com.openpms.core.Text;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Small read routes: the working-day calendar (S10, S13, S17, S18) and header search suggestions. */
@RestController
@RequestMapping("/api/v1")
public class MiscController {
    private final Current current;
    private final DSLContext db;

    public MiscController(Current current, DSLContext db) {
        this.current = current;
        this.db = db;
    }

    @GetMapping("/calendar/non-working-days")
    public Dto.CalendarDays nonWorkingDays(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        var ctx = current.ctx();
        if (to.isBefore(from) || from.plusYears(3).isBefore(to)) throw ApiException.unprocessable("기간을 확인하세요.", "기간은 3년 이하여야 합니다.");
        var days = db.selectFrom(NON_WORKING_DAYS).where(NON_WORKING_DAYS.DAY.between(from, to)).orderBy(NON_WORKING_DAYS.DAY).fetch().stream()
                .map(d -> new Dto.NonWorkingDay(d.getId(), d.getDay(), d.getName())).toList();
        return new Dto.CalendarDays(ctx.s().settings().workingDays(), days);
    }

    @GetMapping("/search/suggest")
    public List<Dto.SearchHit> suggest(@RequestParam(defaultValue = "") String q) {
        var ctx = current.ctx();
        var s = ctx.s();
        var needle = q.trim().replaceFirst("^#", "").toLowerCase();
        if (needle.isEmpty()) return List.of();
        var projects = ctx.visibleProjects("wp.view").stream().map(Snapshot.ProjectRow::id).toList();
        var cond = WORK_PACKAGES.PROJECT_ID.in(projects).and(WORK_PACKAGES.SUBJECT.containsIgnoreCase(needle));
        try {
            cond = WORK_PACKAGES.PROJECT_ID.in(projects).and(WORK_PACKAGES.SUBJECT.containsIgnoreCase(needle).or(WORK_PACKAGES.NUMBER.eq(Long.parseLong(needle))));
        } catch (NumberFormatException ignored) {
            // subject only
        }
        var out = new ArrayList<Dto.SearchHit>();
        for (var w : db.selectFrom(WORK_PACKAGES).where(cond).orderBy(WORK_PACKAGES.UPDATED_AT.desc()).limit(5).fetch()) {
            var p = s.project(w.getProjectId()).orElseThrow();
            var status = s.status(w.getStatusId()).map(Snapshot.StatusRow::name).orElse("");
            out.add(new Dto.SearchHit("work_package", "#" + w.getNumber() + " " + w.getSubject(), p.name() + " · " + status,
                    "/projects/" + p.identifier() + "/work-packages/" + w.getNumber() + "/activity"));
        }
        ctx.visibleProjects("project.view").stream().filter(p -> p.archivedAt() == null && p.name().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Snapshot.ProjectRow::name, Text.KOREAN)).limit(3)
                .forEach(p -> out.add(new Dto.SearchHit("project", p.name(), p.identifier(), "/projects/" + p.identifier())));
        return out;
    }
}
