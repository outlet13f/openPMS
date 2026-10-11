package com.openpms.time;

import static com.openpms.db.Tables.NON_WORKING_DAYS;
import static com.openpms.db.Tables.TIME_ACTIVITIES;
import static com.openpms.db.Tables.TIME_ENTRIES;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Dates;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.TimeActivitiesRecord;
import com.openpms.db.tables.records.TimeEntriesRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.wp.Assembler;
import com.openpms.wp.WorkPackageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시간 기록(공수): time spent on work packages, per person, day and activity. The weekly sheet (내 공수) reads and writes
 * one person's week; a work package's 시간 tab lists its entries; the project's 공수 보고서 sums them. Logging needs
 * time.log in the work package's project (module time_tracking), seeing other people's entries time.view, changing
 * them time.manage. One person's day holds at most 24 hours; going past the instance's hours per day is only a warning.
 */
@RestController
@RequestMapping("/api/v1")
public class TimeController {
    public record Activity(UUID id, String name, int position, boolean isDefault, boolean active) {}

    public record Entry(UUID id, Dto.UserRef user, Dto.ProjectRef project, Dto.WorkPackageRef workPackage, Dto.IdName activity, LocalDate spentOn,
                        BigDecimal hours, String comment, boolean canEdit) {}

    public record Day(LocalDate date, boolean working, String holiday) {}

    public record Cell(LocalDate date, BigDecimal hours, int entries) {}

    /** canLog: I may still log time on it (the module may have been switched off, the role changed). */
    public record Row(Dto.WorkPackageRef workPackage, boolean canLog, List<Cell> cells) {}

    public record Week(LocalDate start, List<Day> days, int hoursPerDay, List<Row> rows) {}

    public record ReportRow(String key, String label, BigDecimal hours) {}

    public record Report(LocalDate from, LocalDate to, String groupBy, List<ReportRow> rows, BigDecimal total) {}

    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_ROWS = 2000;
    static final BigDecimal DAY_LIMIT = new BigDecimal("24");
    static final List<String> GROUPS = List.of("user", "workPackage", "activity", "project", "day");

    private final Current current;
    private final DSLContext db;
    private final WorkPackageService workPackages;

    public TimeController(Current current, DSLContext db, WorkPackageService workPackages) {
        this.current = current;
        this.db = db;
        this.workPackages = workPackages;
    }

    /* ---------- activities ---------- */

    private static Activity activity(TimeActivitiesRecord a) {
        return new Activity(a.getId(), a.getName(), a.getPosition(), a.getIsDefault(), a.getActive());
    }

    @GetMapping("/time-activities")
    public List<Activity> activities() {
        current.ctx();
        return db.selectFrom(TIME_ACTIVITIES).where(TIME_ACTIVITIES.ACTIVE.isTrue()).orderBy(TIME_ACTIVITIES.POSITION, TIME_ACTIVITIES.NAME).fetch(TimeController::activity);
    }

    private Ctx admin() {
        var ctx = current.ctx();
        if (!ctx.isAdmin()) throw ApiException.forbidden("관리자만 할 수 있습니다.");
        return ctx;
    }

    @GetMapping("/admin/time-activities")
    public List<Activity> allActivities() {
        admin();
        return db.selectFrom(TIME_ACTIVITIES).orderBy(TIME_ACTIVITIES.POSITION, TIME_ACTIVITIES.NAME).fetch(TimeController::activity);
    }

    @PostMapping("/admin/time-activities")
    @Transactional
    public ResponseEntity<Activity> createActivity(@RequestBody Map<String, Object> in) {
        admin();
        var a = db.newRecord(TIME_ACTIVITIES);
        a.setName(activityName(in, null));
        a.setPosition(db.select(DSL.coalesce(DSL.max(TIME_ACTIVITIES.POSITION), 0)).from(TIME_ACTIVITIES).fetchOne().value1() + 1);
        a.setIsDefault(false);
        a.setActive(true);
        a.store();
        if (Boolean.TRUE.equals(In.bool(in, "isDefault"))) makeDefault(a.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(activity(db.fetchSingle(TIME_ACTIVITIES, TIME_ACTIVITIES.ID.eq(a.getId()))));
    }

    @PatchMapping("/admin/time-activities/{id}")
    @Transactional
    public Activity updateActivity(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        admin();
        var a = db.fetchOne(TIME_ACTIVITIES, TIME_ACTIVITIES.ID.eq(id));
        if (a == null) throw ApiException.notFound("활동을 찾을 수 없습니다.");
        if (In.has(in, "name")) a.setName(activityName(in, id));
        if (In.has(in, "position")) {
            var pos = In.integer(in, "position", "position");
            if (pos == null || pos < 0) throw ApiException.field("position", "0 이상의 숫자를 입력하세요.");
            a.setPosition(pos);
        }
        if (In.has(in, "active")) {
            boolean on = Boolean.TRUE.equals(In.bool(in, "active"));
            if (!on && a.getIsDefault()) throw ApiException.field("active", "기본 활동은 끌 수 없습니다. 다른 활동을 기본으로 정한 뒤 끄세요.");
            a.setActive(on);
        }
        a.store();
        if (Boolean.TRUE.equals(In.bool(in, "isDefault"))) {
            if (!a.getActive()) throw ApiException.field("isDefault", "꺼 둔 활동은 기본으로 정할 수 없습니다.");
            makeDefault(id);
        }
        return activity(db.fetchSingle(TIME_ACTIVITIES, TIME_ACTIVITIES.ID.eq(id)));
    }

    private String activityName(Map<String, Object> in, UUID self) {
        var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (name.isEmpty() || name.length() > 60) throw ApiException.field("name", "이름을 1~60자로 입력하세요.");
        if (db.fetchExists(TIME_ACTIVITIES, TIME_ACTIVITIES.NAME.eq(name).and(self == null ? DSL.trueCondition() : TIME_ACTIVITIES.ID.ne(self))))
            throw ApiException.field("name", "같은 이름의 활동이 있습니다.");
        return name;
    }

    private void makeDefault(UUID id) {
        db.update(TIME_ACTIVITIES).set(TIME_ACTIVITIES.IS_DEFAULT, false).where(TIME_ACTIVITIES.IS_DEFAULT.isTrue(), TIME_ACTIVITIES.ID.ne(id)).execute();
        db.update(TIME_ACTIVITIES).set(TIME_ACTIVITIES.IS_DEFAULT, true).where(TIME_ACTIVITIES.ID.eq(id)).execute();
    }

    private UUID defaultActivity() {
        return db.select(TIME_ACTIVITIES.ID).from(TIME_ACTIVITIES).where(TIME_ACTIVITIES.IS_DEFAULT.isTrue(), TIME_ACTIVITIES.ACTIVE.isTrue()).fetchOne(TIME_ACTIVITIES.ID);
    }

    private UUID activeActivity(Map<String, Object> in) {
        var id = In.uuid(in, "activityId", "activityId");
        if (id == null) return defaultActivity();
        if (!db.fetchExists(TIME_ACTIVITIES, TIME_ACTIVITIES.ID.eq(id), TIME_ACTIVITIES.ACTIVE.isTrue())) throw ApiException.field("activityId", "쓸 수 있는 활동을 고르세요.");
        return id;
    }

    /* ---------- who sees and changes what ---------- */

    private static boolean sees(Ctx ctx, Snapshot.ProjectRow p, UUID owner) {
        if (p == null) return false;
        return ctx.can(p, "time.view") || (owner.equals(ctx.userId()) && ctx.can(p, "time.log"));
    }

    private static boolean edits(Ctx ctx, Snapshot.ProjectRow p, UUID wpId, UUID owner) {
        if (p == null) return false;
        return ctx.can(p, "time.manage") || (owner.equals(ctx.userId()) && ctx.can(p, wpId, "time.log"));
    }

    private Map<UUID, WorkPackagesRecord> wpsOf(List<TimeEntriesRecord> entries) {
        var ids = entries.stream().map(TimeEntriesRecord::getWorkPackageId).distinct().toList();
        var out = new HashMap<UUID, WorkPackagesRecord>();
        if (!ids.isEmpty()) db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(ids)).fetch().forEach(w -> out.put(w.getId(), w));
        return out;
    }

    private Map<UUID, String> activityNames() {
        var out = new HashMap<UUID, String>();
        db.select(TIME_ACTIVITIES.ID, TIME_ACTIVITIES.NAME).from(TIME_ACTIVITIES).fetch().forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }

    private List<Entry> toDtos(Ctx ctx, List<TimeEntriesRecord> entries) {
        var s = ctx.s();
        var wps = wpsOf(entries);
        var names = activityNames();
        var out = new ArrayList<Entry>();
        for (var e : entries) {
            var p = s.project(e.getProjectId()).orElse(null);
            var w = wps.get(e.getWorkPackageId());
            if (w == null || !sees(ctx, p, e.getUserId())) continue;
            out.add(new Entry(e.getId(), Refs.user(s, e.getUserId()), new Dto.ProjectRef(p.id(), p.identifier(), p.name()), Assembler.ref(s, w),
                    e.getActivityId() == null ? null : new Dto.IdName(e.getActivityId(), names.get(e.getActivityId())), e.getSpentOn(), e.getHours(),
                    e.getComment(), edits(ctx, p, w.getId(), e.getUserId())));
        }
        return out;
    }

    /* ---------- entries ---------- */

    private static LocalDate parseDate(String s, String field) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw ApiException.field(field, "날짜 형식(YYYY-MM-DD)으로 입력하세요.");
        }
    }

    /** The range a list or report covers; open ends default to this month, at most a year. */
    private static LocalDate[] range(Ctx ctx, String from, String to) {
        var f = parseDate(from, "from");
        var t = parseDate(to, "to");
        if (f == null) f = (t != null ? t : ctx.today()).withDayOfMonth(1);
        if (t == null) t = f.plusMonths(1).minusDays(1);
        if (t.isBefore(f)) throw ApiException.field("to", "끝 날짜가 시작 날짜보다 빠릅니다.");
        if (ChronoUnit.DAYS.between(f, t) >= MAX_RANGE_DAYS) throw ApiException.field("to", "한 번에 1년까지만 볼 수 있습니다.");
        return new LocalDate[] {f, t};
    }

    private Condition owner(Ctx ctx, String user) {
        if (user == null || user.isBlank() || "all".equals(user)) return DSL.noCondition();
        if ("me".equals(user)) return TIME_ENTRIES.USER_ID.eq(ctx.userId());
        var id = In.uuidOrNull(user);
        if (id == null) throw ApiException.field("user", "사람을 확인하세요.");
        return TIME_ENTRIES.USER_ID.eq(id);
    }

    @GetMapping("/time-entries")
    public List<Entry> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                            @RequestParam(required = false) Long workPackage, @RequestParam(required = false) String project,
                            @RequestParam(required = false) String user) {
        var ctx = current.ctx();
        Condition c = owner(ctx, user);
        if (workPackage != null) {
            // one work package: all of its time, newest first
            var f = workPackages.find(ctx, workPackage);
            c = c.and(TIME_ENTRIES.WORK_PACKAGE_ID.eq(f.wp().getId()));
        } else {
            var r = range(ctx, from, to);
            c = c.and(TIME_ENTRIES.SPENT_ON.between(r[0], r[1]));
        }
        if (project != null && !project.isBlank()) c = c.and(TIME_ENTRIES.PROJECT_ID.eq(ctx.findProject(project).id()));
        var rows = db.selectFrom(TIME_ENTRIES).where(c).orderBy(TIME_ENTRIES.SPENT_ON.desc(), TIME_ENTRIES.CREATED_AT.desc()).limit(MAX_ROWS).fetch();
        return toDtos(ctx, rows);
    }

    private static BigDecimal hours(Map<String, Object> in, boolean zeroAllowed) {
        var h = In.decimal(in, "hours", "hours");
        if (h == null) throw ApiException.field("hours", "시간을 입력하세요.");
        return checkHours(h, zeroAllowed, "hours");
    }

    static BigDecimal checkHours(BigDecimal h, boolean zeroAllowed, String field) {
        if (h.signum() < 0 || (!zeroAllowed && h.signum() == 0) || h.compareTo(DAY_LIMIT) > 0)
            throw ApiException.field(field, zeroAllowed ? "0에서 24 사이로 입력하세요." : "0보다 크고 24 이하로 입력하세요.");
        if (h.stripTrailingZeros().scale() > 2) throw ApiException.field(field, "소수 둘째 자리까지 입력하세요.");
        return h.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** One person's total on a day, without the entries being replaced. */
    private BigDecimal dayTotal(UUID user, LocalDate day, List<UUID> leaving) {
        var c = TIME_ENTRIES.USER_ID.eq(user).and(TIME_ENTRIES.SPENT_ON.eq(day));
        if (!leaving.isEmpty()) c = c.and(TIME_ENTRIES.ID.notIn(leaving));
        var sum = db.select(DSL.sum(TIME_ENTRIES.HOURS)).from(TIME_ENTRIES).where(c).fetchOne().value1();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private void checkDay(UUID user, LocalDate day, BigDecimal adding, List<UUID> leaving, String field) {
        if (dayTotal(user, day, leaving).add(adding).compareTo(DAY_LIMIT) > 0) throw ApiException.field(field, "하루에 24시간을 넘게 기록할 수 없습니다.");
    }

    @PostMapping("/time-entries")
    @Transactional
    public ResponseEntity<Entry> create(@RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var number = In.integer(in, "workPackageNumber", "workPackageNumber");
        if (number == null) throw ApiException.field("workPackageNumber", "작업을 고르세요.");
        var f = workPackages.find(ctx, number);
        var user = In.has(in, "userId") ? In.uuid(in, "userId", "userId") : ctx.userId();
        if (user == null) user = ctx.userId();
        if (user.equals(ctx.userId())) ctx.require(f.project(), f.wp().getId(), "time.log", "이 작업에 시간을 기록할 권한이 없습니다.");
        else {
            ctx.require(f.project(), "time.manage", "다른 사람의 시간을 기록할 권한이 없습니다.");
            var other = user;
            if (ctx.s().user(other).filter(u -> "active".equals(u.status())).isEmpty()) throw ApiException.field("userId", "활성 사용자를 고르세요.");
        }
        var day = In.date(in, "spentOn", "spentOn");
        if (day == null) throw ApiException.field("spentOn", "날짜를 입력하세요.");
        var h = hours(in, false);
        checkDay(user, day, h, List.of(), "hours");
        var e = db.newRecord(TIME_ENTRIES);
        e.setUserId(user);
        e.setProjectId(f.project().id());
        e.setWorkPackageId(f.wp().getId());
        e.setActivityId(activeActivity(in));
        e.setSpentOn(day);
        e.setHours(h);
        e.setComment(comment(in));
        e.store();
        e.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(toDtos(ctx, List.of(e)).get(0));
    }

    private static String comment(Map<String, Object> in) {
        var c = In.str(in, "comment") == null ? "" : In.str(in, "comment").trim();
        if (c.length() > 1000) throw ApiException.field("comment", "메모는 1000자 이하로 입력하세요.");
        return c;
    }

    private TimeEntriesRecord editable(Ctx ctx, UUID id) {
        var e = db.fetchOne(TIME_ENTRIES, TIME_ENTRIES.ID.eq(id));
        var p = e == null ? null : ctx.s().project(e.getProjectId()).orElse(null);
        if (e == null || !sees(ctx, p, e.getUserId())) throw ApiException.notFound("시간 기록을 찾을 수 없습니다.");
        if (!edits(ctx, p, e.getWorkPackageId(), e.getUserId())) throw ApiException.forbidden("이 시간 기록을 바꿀 권한이 없습니다.");
        return e;
    }

    @PatchMapping("/time-entries/{id}")
    @Transactional
    public Entry update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var e = editable(ctx, id);
        if (In.has(in, "spentOn")) {
            var day = In.date(in, "spentOn", "spentOn");
            if (day == null) throw ApiException.field("spentOn", "날짜를 입력하세요.");
            e.setSpentOn(day);
        }
        if (In.has(in, "hours")) e.setHours(hours(in, false));
        if (In.has(in, "activityId")) e.setActivityId(activeActivity(in));
        if (In.has(in, "comment")) e.setComment(comment(in));
        checkDay(e.getUserId(), e.getSpentOn(), e.getHours(), List.of(e.getId()), "hours");
        e.store();
        e.refresh();
        return toDtos(ctx, List.of(e)).get(0);
    }

    @DeleteMapping("/time-entries/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        editable(current.ctx(), id).delete();
        return ResponseEntity.noContent().build();
    }

    /* ---------- the weekly sheet ---------- */

    private Week week(Ctx ctx, LocalDate start) {
        var s = ctx.s();
        var end = start.plusDays(6);
        var cal = s.calendar();
        var holidays = new HashMap<LocalDate, String>();
        db.select(NON_WORKING_DAYS.DAY, NON_WORKING_DAYS.NAME).from(NON_WORKING_DAYS).where(NON_WORKING_DAYS.DAY.between(start, end)).fetch()
                .forEach(r -> holidays.put(r.value1(), r.value2()));
        var days = new ArrayList<Day>();
        for (var d = start; !d.isAfter(end); d = d.plusDays(1)) days.add(new Day(d, cal.isWorking(d, false), holidays.get(d)));

        var mine = db.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.USER_ID.eq(ctx.userId()), TIME_ENTRIES.SPENT_ON.between(start, end)).fetch();
        var wps = wpsOf(mine);
        var byWp = new LinkedHashMap<UUID, Map<LocalDate, BigDecimal[]>>();
        for (var e : mine) {
            var cells = byWp.computeIfAbsent(e.getWorkPackageId(), k -> new HashMap<>());
            var c = cells.computeIfAbsent(e.getSpentOn(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            c[0] = c[0].add(e.getHours());
            c[1] = c[1].add(BigDecimal.ONE);
        }
        var rows = new ArrayList<Row>();
        for (var entry : byWp.entrySet()) {
            var w = wps.get(entry.getKey());
            var p = w == null ? null : s.project(w.getProjectId()).orElse(null);
            if (w == null || p == null) continue;
            var cells = new ArrayList<Cell>();
            for (var d : days) {
                var c = entry.getValue().get(d.date());
                cells.add(new Cell(d.date(), c == null ? BigDecimal.ZERO : c[0], c == null ? 0 : c[1].intValue()));
            }
            rows.add(new Row(Assembler.ref(s, w), ctx.can(p, w.getId(), "time.log"), cells));
        }
        rows.sort(Comparator.comparing((Row r) -> r.workPackage().project().name(), com.openpms.core.Text.KOREAN).thenComparingLong(r -> r.workPackage().number()));
        return new Week(start, days, s.settings().hoursPerDay(), rows);
    }

    private static LocalDate weekStart(Ctx ctx, String start) {
        var d = parseDate(start, "start");
        return Dates.startOfWeek(d == null ? ctx.today() : d);
    }

    @GetMapping("/time-entries/week")
    public Week getWeek(@RequestParam(required = false) String start) {
        var ctx = current.ctx();
        return week(ctx, weekStart(ctx, start));
    }

    /**
     * Saves the sheet: each cell sets my hours on a work package that day. A cell with no entry makes one (default activity),
     * a cell with one entry changes it (0 removes it), a cell holding several entries can only stay as it is — those are
     * changed in the work package's 시간 tab. All or nothing.
     */
    @PutMapping("/time-entries/week")
    @Transactional
    public Week saveWeek(@RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var start = weekStart(ctx, In.str(in, "start"));
        var end = start.plusDays(6);
        var cells = in.get("cells") instanceof List<?> l ? l : List.of();
        var failures = new ArrayList<ApiException.FieldError>();
        var activity = defaultActivity();
        var touched = new java.util.TreeSet<LocalDate>();
        var found = new HashMap<Long, WorkPackageService.Found>();
        for (var raw : cells) {
            if (!(raw instanceof Map<?, ?> m)) continue;
            @SuppressWarnings("unchecked")
            var cell = (Map<String, Object>) m;
            var number = In.integer(cell, "workPackageNumber", "workPackageNumber");
            var key = "#" + number + " " + In.str(cell, "date");
            try {
                if (number == null) throw ApiException.field("workPackageNumber", "작업을 고르세요.");
                var day = In.date(cell, "date", "date");
                if (day == null || day.isBefore(start) || day.isAfter(end)) throw ApiException.field("date", "이 주의 날짜가 아닙니다.");
                var h = In.decimal(cell, "hours", "hours");
                h = checkHours(h == null ? BigDecimal.ZERO : h, true, "hours");
                var f = found.computeIfAbsent(number.longValue(), n -> workPackages.find(ctx, n));
                var existing = db.selectFrom(TIME_ENTRIES).where(TIME_ENTRIES.USER_ID.eq(ctx.userId()), TIME_ENTRIES.WORK_PACKAGE_ID.eq(f.wp().getId()),
                        TIME_ENTRIES.SPENT_ON.eq(day)).fetch();
                var now = existing.stream().map(TimeEntriesRecord::getHours).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (now.compareTo(h) == 0) continue;
                if (!ctx.can(f.project(), f.wp().getId(), "time.log")) throw ApiException.forbidden("이 작업에 시간을 기록할 권한이 없습니다.");
                if (existing.size() > 1) throw ApiException.field("hours", "여러 건으로 기록된 칸입니다. 작업의 시간 탭에서 고치세요.");
                if (existing.isEmpty()) {
                    var e = db.newRecord(TIME_ENTRIES);
                    e.setUserId(ctx.userId());
                    e.setProjectId(f.project().id());
                    e.setWorkPackageId(f.wp().getId());
                    e.setActivityId(activity);
                    e.setSpentOn(day);
                    e.setHours(h);
                    e.setComment("");
                    e.store();
                } else if (h.signum() == 0) {
                    existing.get(0).delete();
                } else {
                    existing.get(0).setHours(h);
                    existing.get(0).store();
                }
                touched.add(day);
            } catch (ApiException e) {
                failures.add(new ApiException.FieldError(key, e.detail() != null ? e.detail() : e.title()));
            }
        }
        for (var day : touched)
            if (dayTotal(ctx.userId(), day, List.of()).compareTo(DAY_LIMIT) > 0) failures.add(new ApiException.FieldError(day.toString(), "하루에 24시간을 넘게 기록할 수 없습니다."));
        if (!failures.isEmpty()) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new ApiException(422, "저장하지 않았습니다.", failures.size() + "칸에서 문제가 있어 이 주의 변경을 모두 되돌렸습니다.", failures, null);
        }
        return week(ctx, start);
    }

    /* ---------- report and export ---------- */

    private List<Entry> visibleIn(Ctx ctx, String from, String to, String project, String user, LocalDate[] r) {
        Condition c = TIME_ENTRIES.SPENT_ON.between(r[0], r[1]).and(owner(ctx, user));
        if (project != null && !project.isBlank()) c = c.and(TIME_ENTRIES.PROJECT_ID.eq(ctx.findProject(project).id()));
        var rows = db.selectFrom(TIME_ENTRIES).where(c).orderBy(TIME_ENTRIES.SPENT_ON, TIME_ENTRIES.CREATED_AT).limit(50_000).fetch();
        return toDtos(ctx, rows);
    }

    @GetMapping("/time-entries/report")
    public Report report(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                         @RequestParam(required = false) String project, @RequestParam(required = false) String user,
                         @RequestParam(defaultValue = "user") String groupBy) {
        var ctx = current.ctx();
        if (!GROUPS.contains(groupBy)) throw ApiException.field("groupBy", "묶는 기준을 확인하세요.");
        var r = range(ctx, from, to);
        var sums = new LinkedHashMap<String, Object[]>();
        var total = BigDecimal.ZERO;
        for (var e : visibleIn(ctx, from, to, project, user, r)) {
            String key, label;
            switch (groupBy) {
                case "workPackage" -> {
                    key = String.valueOf(e.workPackage().number());
                    label = "#" + e.workPackage().number() + " " + e.workPackage().subject();
                }
                case "activity" -> {
                    key = e.activity() == null ? "" : e.activity().id().toString();
                    label = e.activity() == null ? "활동 없음" : e.activity().name();
                }
                case "project" -> {
                    key = e.project().id().toString();
                    label = e.project().name();
                }
                case "day" -> {
                    key = e.spentOn().toString();
                    label = Dates.format(e.spentOn());
                }
                default -> {
                    key = e.user().id().toString();
                    label = e.user().name();
                }
            }
            var acc = sums.computeIfAbsent(key, k -> new Object[] {label, BigDecimal.ZERO});
            acc[1] = ((BigDecimal) acc[1]).add(e.hours());
            total = total.add(e.hours());
        }
        var rows = sums.entrySet().stream().map(x -> new ReportRow(x.getKey(), (String) x.getValue()[0], (BigDecimal) x.getValue()[1])).toList();
        var sorted = new ArrayList<>(rows);
        if ("day".equals(groupBy)) sorted.sort(Comparator.comparing(ReportRow::key));
        else sorted.sort(Comparator.comparing(ReportRow::hours).reversed().thenComparing(ReportRow::label, com.openpms.core.Text.KOREAN));
        return new Report(r[0], r[1], groupBy, sorted, total);
    }

    @GetMapping("/time-entries/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String project, @RequestParam(required = false) String user) throws IOException {
        var ctx = current.ctx();
        var r = range(ctx, from, to);
        var entries = visibleIn(ctx, from, to, project, user, r);
        try (var wb = new SXSSFWorkbook(200); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("공수");
            var head = sheet.createRow(0);
            var titles = List.of("날짜", "사람", "프로젝트", "번호", "작업", "활동", "시간", "메모");
            for (int i = 0; i < titles.size(); i++) head.createCell(i).setCellValue(titles.get(i));
            int n = 1;
            var total = BigDecimal.ZERO;
            for (var e : entries) {
                var row = sheet.createRow(n++);
                row.createCell(0).setCellValue(e.spentOn().toString());
                row.createCell(1).setCellValue(e.user().name());
                row.createCell(2).setCellValue(e.project().name());
                row.createCell(3).setCellValue(e.workPackage().number());
                row.createCell(4).setCellValue(e.workPackage().subject());
                row.createCell(5).setCellValue(e.activity() == null ? "" : e.activity().name());
                row.createCell(6).setCellValue(e.hours().doubleValue());
                row.createCell(7).setCellValue(e.comment());
                total = total.add(e.hours());
            }
            var sum = sheet.createRow(n);
            sum.createCell(0).setCellValue("합계");
            sum.createCell(6).setCellValue(total.doubleValue());
            wb.write(out);
            wb.dispose();
            var name = "공수 " + r[0] + "~" + r[1] + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"time.xlsx\"; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }

    /** Moving a work package to another project takes its time entries along (WorkPackageTransfers). */
    public static void follow(DSLContext db, UUID workPackage, UUID project) {
        db.update(TIME_ENTRIES).set(TIME_ENTRIES.PROJECT_ID, project).where(TIME_ENTRIES.WORK_PACKAGE_ID.eq(workPackage), TIME_ENTRIES.PROJECT_ID.ne(project)).execute();
    }
}
