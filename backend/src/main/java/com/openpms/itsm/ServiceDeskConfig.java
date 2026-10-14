package com.openpms.itsm;

import static com.openpms.db.Tables.PRIORITY_MATRIX;
import static com.openpms.db.Tables.SERVICE_CALENDARS;
import static com.openpms.db.Tables.SLA_POLICIES;

import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Snapshot;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Service calendars (instance-wide) and each service desk's SLA policies and priority matrix. */
@Component
public class ServiceDeskConfig {
    public record CalendarDto(UUID id, String name, String mode, String timeZone, List<Map<String, Object>> hours) {}

    public record PolicyDto(UUID id, UUID typeId, UUID priorityId, UUID calendarId, int firstResponseMinutes, int resolveMinutes) {}

    public record MatrixCell(int impact, int urgency, UUID priorityId) {}

    public record Settings(List<PolicyDto> policies, List<MatrixCell> matrix) {}

    public record Policy(UUID id, Duration firstResponse, Duration resolve, SlaClock.Calendar calendar) {}

    private final DSLContext db;
    private final JsonMapper json;

    public ServiceDeskConfig(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /* ---------- calendars ---------- */

    public List<CalendarDto> calendars() {
        return db.selectFrom(SERVICE_CALENDARS).orderBy(SERVICE_CALENDARS.NAME).fetch(r -> new CalendarDto(r.getId(), r.getName(), r.getMode(), r.getTimeZone(), hours(r.getHours())));
    }

    public CalendarDto saveCalendar(UUID id, Map<String, Object> in) {
        var r = id == null ? db.newRecord(SERVICE_CALENDARS) : db.selectFrom(SERVICE_CALENDARS).where(SERVICE_CALENDARS.ID.eq(id)).fetchOne();
        if (r == null) throw ApiException.notFound("달력을 찾을 수 없습니다.");
        if (id == null || In.has(in, "name")) {
            var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
            if (name.isEmpty() || name.length() > 100) throw ApiException.field("name", "이름은 1~100자로 입력하세요.");
            if (db.fetchExists(SERVICE_CALENDARS, SERVICE_CALENDARS.NAME.eq(name).and(id == null ? org.jooq.impl.DSL.trueCondition() : SERVICE_CALENDARS.ID.ne(id))))
                throw ApiException.field("name", "같은 이름의 달력이 있습니다.");
            r.setName(name);
        }
        if (id == null || In.has(in, "mode")) {
            var mode = In.str(in, "mode");
            if (!"24x7".equals(mode) && !"business".equals(mode)) throw ApiException.field("mode", "24x7 또는 business만 쓸 수 있습니다.");
            r.setMode(mode);
        }
        if (id == null || In.has(in, "timeZone")) {
            var zone = In.str(in, "timeZone") == null ? "Asia/Seoul" : In.str(in, "timeZone");
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw ApiException.field("timeZone", "알 수 없는 시간대입니다.");
            }
            r.setTimeZone(zone);
        }
        if (id == null || In.has(in, "hours")) {
            @SuppressWarnings("unchecked")
            var hours = in.get("hours") instanceof List<?> l ? (List<Map<String, Object>>) l : List.<Map<String, Object>>of();
            try {
                var parsed = SlaClock.parseHours(hours);
                if ("business".equals(r.getMode())) SlaClock.Calendar.business(ZoneId.of(r.getTimeZone()), parsed);
            } catch (RuntimeException e) {
                throw ApiException.field("hours", e instanceof IllegalArgumentException ? e.getMessage() : "근무 시간 형식을 확인하세요.");
            }
            r.setHours(JSONB.valueOf(json.writeValueAsString(hours)));
        }
        if ("business".equals(r.getMode()) && hours(r.getHours()).isEmpty()) throw ApiException.field("hours", "근무 시간 달력에는 근무 시간이 하나 이상 있어야 합니다.");
        r.store();
        return new CalendarDto(r.getId(), r.getName(), r.getMode(), r.getTimeZone(), hours(r.getHours()));
    }

    public void deleteCalendar(UUID id) {
        if (db.fetchExists(SLA_POLICIES, SLA_POLICIES.CALENDAR_ID.eq(id))) throw ApiException.conflict("SLA 정책이 쓰는 달력은 지울 수 없습니다.", "정책에서 다른 달력을 고른 뒤 지우세요.", null);
        if (db.deleteFrom(SERVICE_CALENDARS).where(SERVICE_CALENDARS.ID.eq(id)).execute() == 0) throw ApiException.notFound("달력을 찾을 수 없습니다.");
    }

    public SlaClock.Calendar calendarOf(UUID calendarId) {
        var r = db.selectFrom(SERVICE_CALENDARS).where(SERVICE_CALENDARS.ID.eq(calendarId)).fetchOne();
        var zone = ZoneId.of(r.getTimeZone());
        return "24x7".equals(r.getMode()) ? SlaClock.Calendar.always(zone) : SlaClock.Calendar.business(zone, SlaClock.parseHours(hours(r.getHours())));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hours(JSONB j) {
        return j == null ? List.of() : json.readValue(j.data(), List.class);
    }

    /* ---------- policies and matrix ---------- */

    public Settings settings(UUID projectId) {
        var policies = db.selectFrom(SLA_POLICIES).where(SLA_POLICIES.PROJECT_ID.eq(projectId)).orderBy(SLA_POLICIES.TYPE_ID, SLA_POLICIES.PRIORITY_ID)
                .fetch(r -> new PolicyDto(r.getId(), r.getTypeId(), r.getPriorityId(), r.getCalendarId(), r.getFirstResponseMinutes(), r.getResolveMinutes()));
        var matrix = db.selectFrom(PRIORITY_MATRIX).where(PRIORITY_MATRIX.PROJECT_ID.eq(projectId)).orderBy(PRIORITY_MATRIX.IMPACT, PRIORITY_MATRIX.URGENCY)
                .fetch(r -> new MatrixCell(r.getImpact(), r.getUrgency(), r.getPriorityId()));
        return new Settings(policies, matrix);
    }

    /** Replaces both lists. Policies are matched by (type, priority) so tickets keep pointing at a policy that stays. */
    public Settings replace(Snapshot s, UUID projectId, Map<String, Object> in) {
        var policies = list(in.get("policies"));
        var seen = new HashSet<String>();
        var keep = new ArrayList<UUID>();
        for (int i = 0; i < policies.size(); i++) {
            var p = policies.get(i);
            var at = "policies[" + i + "]";
            var typeId = In.uuid(p, "typeId", at + ".typeId");
            if (typeId == null || !ItsmIds.TICKET_TYPES.contains(typeId)) throw ApiException.field(at + ".typeId", "장애·서비스 요청 유형만 고를 수 있습니다.");
            var priorityId = In.uuid(p, "priorityId", at + ".priorityId");
            if (priorityId == null || s.priority(priorityId).isEmpty()) throw ApiException.field(at + ".priorityId", "알 수 없는 우선순위입니다.");
            var calendarId = In.uuid(p, "calendarId", at + ".calendarId");
            if (calendarId == null || !db.fetchExists(SERVICE_CALENDARS, SERVICE_CALENDARS.ID.eq(calendarId))) throw ApiException.field(at + ".calendarId", "알 수 없는 달력입니다.");
            var respond = In.integer(p, "firstResponseMinutes", at + ".firstResponseMinutes");
            var resolve = In.integer(p, "resolveMinutes", at + ".resolveMinutes");
            if (respond == null || respond <= 0) throw ApiException.field(at + ".firstResponseMinutes", "첫 응답 목표는 1분 이상이어야 합니다.");
            if (resolve == null || resolve < respond) throw ApiException.field(at + ".resolveMinutes", "해결 목표는 첫 응답 목표보다 짧을 수 없습니다.");
            if (!seen.add(typeId + "/" + priorityId)) throw ApiException.field(at + ".priorityId", "같은 유형·우선순위의 정책이 두 번 있습니다.");
            var id = db.insertInto(SLA_POLICIES)
                    .set(SLA_POLICIES.PROJECT_ID, projectId).set(SLA_POLICIES.TYPE_ID, typeId).set(SLA_POLICIES.PRIORITY_ID, priorityId)
                    .set(SLA_POLICIES.CALENDAR_ID, calendarId).set(SLA_POLICIES.FIRST_RESPONSE_MINUTES, respond).set(SLA_POLICIES.RESOLVE_MINUTES, resolve)
                    .onConflict(SLA_POLICIES.PROJECT_ID, SLA_POLICIES.TYPE_ID, SLA_POLICIES.PRIORITY_ID).doUpdate()
                    .set(SLA_POLICIES.CALENDAR_ID, calendarId).set(SLA_POLICIES.FIRST_RESPONSE_MINUTES, respond).set(SLA_POLICIES.RESOLVE_MINUTES, resolve)
                    .returning(SLA_POLICIES.ID).fetchOne(SLA_POLICIES.ID);
            keep.add(id);
        }
        db.deleteFrom(SLA_POLICIES).where(SLA_POLICIES.PROJECT_ID.eq(projectId), keep.isEmpty() ? org.jooq.impl.DSL.trueCondition() : SLA_POLICIES.ID.notIn(keep)).execute();

        var cells = list(in.get("matrix"));
        db.deleteFrom(PRIORITY_MATRIX).where(PRIORITY_MATRIX.PROJECT_ID.eq(projectId)).execute();
        var cellKeys = new HashSet<String>();
        for (int i = 0; i < cells.size(); i++) {
            var c = cells.get(i);
            var at = "matrix[" + i + "]";
            var impact = In.integer(c, "impact", at + ".impact");
            var urgency = In.integer(c, "urgency", at + ".urgency");
            if (impact == null || impact < 1 || impact > 3) throw ApiException.field(at + ".impact", "영향도는 1~3입니다.");
            if (urgency == null || urgency < 1 || urgency > 3) throw ApiException.field(at + ".urgency", "긴급도는 1~3입니다.");
            var priorityId = In.uuid(c, "priorityId", at + ".priorityId");
            if (priorityId == null || s.priority(priorityId).isEmpty()) throw ApiException.field(at + ".priorityId", "알 수 없는 우선순위입니다.");
            if (!cellKeys.add(impact + "/" + urgency)) throw ApiException.field(at + ".urgency", "같은 칸이 두 번 있습니다.");
            db.insertInto(PRIORITY_MATRIX).set(PRIORITY_MATRIX.PROJECT_ID, projectId).set(PRIORITY_MATRIX.IMPACT, impact.shortValue())
                    .set(PRIORITY_MATRIX.URGENCY, urgency.shortValue()).set(PRIORITY_MATRIX.PRIORITY_ID, priorityId).execute();
        }
        return settings(projectId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l)) throw ApiException.field("policies", "목록이어야 합니다.");
        return (List<Map<String, Object>>) l;
    }

    public Optional<Policy> policyFor(UUID projectId, UUID typeId, UUID priorityId) {
        return Optional.ofNullable(db.selectFrom(SLA_POLICIES).where(SLA_POLICIES.PROJECT_ID.eq(projectId), SLA_POLICIES.TYPE_ID.eq(typeId), SLA_POLICIES.PRIORITY_ID.eq(priorityId)).fetchOne())
                .map(this::toPolicy);
    }

    /** A policy by its id (a ticket keeps the policy it was given). */
    public Optional<Policy> policy(UUID policyId) {
        return Optional.ofNullable(db.selectFrom(SLA_POLICIES).where(SLA_POLICIES.ID.eq(policyId)).fetchOne()).map(this::toPolicy);
    }

    private Policy toPolicy(com.openpms.db.tables.records.SlaPoliciesRecord r) {
        return new Policy(r.getId(), Duration.ofMinutes(r.getFirstResponseMinutes()), Duration.ofMinutes(r.getResolveMinutes()), calendarOf(r.getCalendarId()));
    }

    public Optional<UUID> matrixPriority(UUID projectId, int impact, int urgency) {
        return Optional.ofNullable(db.select(PRIORITY_MATRIX.PRIORITY_ID).from(PRIORITY_MATRIX)
                .where(PRIORITY_MATRIX.PROJECT_ID.eq(projectId), PRIORITY_MATRIX.IMPACT.eq((short) impact), PRIORITY_MATRIX.URGENCY.eq((short) urgency))
                .fetchOne(PRIORITY_MATRIX.PRIORITY_ID));
    }
}
