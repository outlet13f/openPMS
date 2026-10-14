package com.openpms.wp;

import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.TYPES;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGE_SHARES;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.customfield.CustomFields;
import com.openpms.common.ApiException;
import com.openpms.common.Html;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.db.tables.records.JournalsRecord;
import com.openpms.db.tables.records.WorkPackageSharesRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Work packages (architecture.md §4.2 작업 패키지, F01·F02·F07·F08), ported rule for rule from the fake data layer. */
@Service
public class WorkPackageService {
    public record Found(WorkPackagesRecord wp, ProjectRow project) {}

    private final DSLContext db;
    private final Assembler assembler;
    private final Journals journals;
    private final ScheduleStore schedules;

    private final CustomFields customFields;
    private final com.openpms.webhook.Webhooks webhooks;
    private final Assignees assignees;
    private final com.openpms.itsm.ServiceDesk serviceDesk;

    public WorkPackageService(DSLContext db, Assembler assembler, Journals journals, ScheduleStore schedules, CustomFields customFields,
                              com.openpms.webhook.Webhooks webhooks, Assignees assignees, com.openpms.itsm.ServiceDesk serviceDesk) {
        this.serviceDesk = serviceDesk;
        this.webhooks = webhooks;
        this.customFields = customFields;
        this.db = db;
        this.assembler = assembler;
        this.journals = journals;
        this.schedules = schedules;
        this.assignees = assignees;
    }

    /** Visible work package by number; invisible and missing are the same 404. */
    public Found find(Ctx ctx, long number) {
        var wp = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(number)).fetchOne();
        var project = wp == null ? null : ctx.s().project(wp.getProjectId()).orElse(null);
        if (wp == null || project == null || !ctx.can(project, wp.getId(), "wp.view")) throw ApiException.notFound("작업을 찾을 수 없습니다.");
        return new Found(wp, project);
    }

    private WorkPackagesRecord reload(UUID id) {
        return db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(id)).fetchOne();
    }

    /* ---------- read ---------- */

    public Dto.WorkPackage get(Ctx ctx, long number) {
        return assembler.one(ctx, find(ctx, number).wp());
    }

    public Dto.WorkPackageForm form(Ctx ctx, String projectId, String typeId) {
        var s = ctx.s();
        var p = ctx.findProject(projectId);
        ctx.require(p, "wp.create", "이 프로젝트에 작업을 만들 권한이 없습니다.");
        var types = s.types().stream().filter(t -> p.typeIds().contains(t.id())).toList();
        if (types.isEmpty()) throw ApiException.unprocessable("쓸 수 있는 유형이 없습니다.", "프로젝트 설정에서 유형을 하나 이상 켜세요.");
        var type = types.stream().filter(t -> t.id().toString().equals(typeId)).findFirst().orElse(types.get(0));
        var def = s.defaultStatus();
        var next = Assembler.allowedStatusIds(ctx, type.id(), def.id(), p.id(), ctx.userId(), List.of());
        return new Dto.WorkPackageForm(
                Refs.type(s, type.id()),
                types.stream().map(t -> Refs.type(s, t.id())).toList(),
                s.statuses().stream().filter(x -> x.id().equals(def.id()) || next.contains(x.id())).map(Refs::status).toList(),
                s.priorities().stream().filter(Snapshot.PriorityRow::isActive).map(x -> Refs.priority(s, x.id())).toList(),
                s.projectUsers(p.id()).stream().map(u -> new Dto.UserRef(u.id(), u.name())).toList(),
                s.versions().stream().filter(v -> v.projectId().equals(p.id()) && "open".equals(v.status())).map(v -> new Dto.IdName(v.id(), v.name())).toList(),
                s.categories().stream().filter(c -> c.projectId().equals(p.id())).map(c -> new Dto.CategoryOption(c.id(), c.name(), c.defaultAssigneeId())).toList(),
                // S28: new items of this type start with the type's description template
                new Dto.FormDefaults(def.id(), s.defaultPriority().id(),
                        db.select(TYPES.DESCRIPTION_TEMPLATE_HTML).from(TYPES).where(TYPES.ID.eq(type.id())).fetchOne(TYPES.DESCRIPTION_TEMPLATE_HTML)),
                List.of("subject"),
                s.activeCustomFields(p.id(), type.id()).stream().map(customFields::dto).toList());
    }

    public List<Dto.StatusRef> allowedStatuses(Ctx ctx, long number) {
        var w = find(ctx, number).wp();
        var ids = Assembler.allowedStatusIds(ctx, w.getTypeId(), w.getStatusId(), w.getProjectId(), w.getAuthorId(), Assignees.ids(assignees.of(w.getId())));
        return ctx.s().statuses().stream().filter(x -> ids.contains(x.id())).map(Refs::status).toList();
    }

    public List<Dto.WorkPackageRef> autocomplete(Ctx ctx, String q, String projectId) {
        var needle = (q == null ? "" : q.trim().replaceFirst("^#", "")).toLowerCase();
        var projects = ctx.visibleProjects("wp.view").stream().map(ProjectRow::id).toList();
        var cond = WORK_PACKAGES.PROJECT_ID.in(projects);
        if (projectId != null && !projectId.isBlank()) {
            var p = ctx.s().projectByIdOrIdentifier(projectId).orElse(null);
            cond = cond.and(WORK_PACKAGES.PROJECT_ID.eq(p == null ? new UUID(0, 0) : p.id()));
        }
        if (!needle.isEmpty()) {
            cond = cond.and(WORK_PACKAGES.NUMBER.cast(String.class).startsWith(needle).or(WORK_PACKAGES.SUBJECT.containsIgnoreCase(needle)));
        }
        var s = ctx.s();
        return db.selectFrom(WORK_PACKAGES).where(cond).orderBy(WORK_PACKAGES.NUMBER).limit(10).fetch().stream().map(w -> Assembler.ref(s, w)).toList();
    }

    public List<Dto.Journal> activities(Ctx ctx, long number) {
        var f = find(ctx, number);
        boolean internalOk = ctx.can(f.project(), "wp.view_internal_comments");
        return db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(f.wp().getId())).orderBy(JOURNALS.VERSION).fetch().stream()
                .filter(j -> !j.getIsInternal() || internalOk).map(j -> journals.toDto(ctx, j, f.project())).toList();
    }

    public Dto.RelationGroups relations(Ctx ctx, long number) {
        var s = ctx.s();
        var wp = find(ctx, number).wp();
        Map<String, String> reverse = Map.of("relates", "relates", "precedes", "follows", "duplicates", "duplicated", "blocks", "blocked", "includes", "partof",
                "requires", "required");
        var rels = db.selectFrom(RELATIONS).where(RELATIONS.FROM_ID.eq(wp.getId()).or(RELATIONS.TO_ID.eq(wp.getId()))).orderBy(RELATIONS.CREATED_AT).fetch();
        var otherIds = rels.stream().map(r -> r.getFromId().equals(wp.getId()) ? r.getToId() : r.getFromId()).toList();
        var others = new HashMap<UUID, WorkPackagesRecord>();
        assembler.load(otherIds).forEach(o -> others.put(o.getId(), o));
        var out = new ArrayList<Dto.Relation>();
        for (var r : rels) {
            boolean outgoing = r.getFromId().equals(wp.getId());
            var other = others.get(outgoing ? r.getToId() : r.getFromId());
            if (!visible(ctx, other)) continue;
            out.add(new Dto.Relation(r.getId(), outgoing ? r.getType() : reverse.get(r.getType()), Assembler.dated(s, other), r.getLag(), r.getDescription()));
        }
        var parent = wp.getParentId() == null ? null : reload(wp.getParentId());
        var children = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PARENT_ID.eq(wp.getId())).orderBy(WORK_PACKAGES.NUMBER).fetch().stream()
                .filter(k -> visible(ctx, k)).map(k -> Assembler.dated(s, k)).toList();
        return new Dto.RelationGroups(out, visible(ctx, parent) ? Assembler.dated(s, parent) : null, children);
    }

    private boolean visible(Ctx ctx, WorkPackagesRecord w) {
        return w != null && ctx.s().project(w.getProjectId()).map(p -> ctx.can(p, w.getId(), "wp.view")).orElse(false);
    }

    /* ---------- create ---------- */

    /** Who acts: a project member (permissions checked by the caller) or a requester at the portal (PortalService decided). */
    public enum Acting { MEMBER, PORTAL }

    @Transactional
    public Dto.WorkPackage create(Ctx ctx, String projectId, Map<String, Object> in) {
        var p = ctx.findProject(projectId);
        ctx.require(p, "wp.create", "이 프로젝트에 작업을 만들 권한이 없습니다.");
        return assembler.one(ctx, reload(insert(ctx, p, in, Acting.MEMBER).getId()));
    }

    /** A requester's ticket from the portal: PortalService decided who may and passes only portal fields. */
    @Transactional
    public WorkPackagesRecord createForPortal(Ctx ctx, Snapshot.ProjectRow p, Map<String, Object> in) {
        return insert(ctx, p, in, Acting.PORTAL);
    }

    private WorkPackagesRecord insert(Ctx ctx, Snapshot.ProjectRow p, Map<String, Object> in, Acting acting) {
        var s = ctx.s();
        var subject = In.str(in, "subject") == null ? "" : In.str(in, "subject").trim();
        if (subject.isEmpty()) throw ApiException.field("subject", "제목을 입력하세요.");
        if (subject.length() > 255) throw ApiException.field("subject", "제목은 255자 이하로 입력하세요.");
        var typeId = In.uuid(in, "typeId", "type");
        if (typeId == null || !p.typeIds().contains(typeId)) throw ApiException.field("type", "이 프로젝트에서 쓰지 않는 유형입니다.");
        // ITSM 1단계: a ticket in a service desk starts as 접수 with the matrix priority and gets its service_tickets row
        var ticket = serviceDesk.applies(p, typeId) ? serviceDesk.draft(ctx, p, in, acting == Acting.PORTAL ? "portal" : "agent") : null;
        // 공동 담당자: "assignees" (everyone, in order, with hours) or the older single "assigneeId"
        var team = In.has(in, "assignees") ? Assignees.read(in.get("assignees"), s, p.id()) : null;
        var assigneeId = team != null ? (team.isEmpty() ? null : team.get(0).userId()) : In.uuid(in, "assigneeId", "assignee");
        if (assigneeId != null && !s.isMember(assigneeId, p.id())) throw ApiException.field("assignee", "프로젝트 멤버만 지정할 수 있습니다.");
        var responsibleId = In.uuid(in, "responsibleId", "responsible");
        if (responsibleId != null && !s.isMember(responsibleId, p.id())) throw ApiException.field("responsible", "프로젝트 멤버만 지정할 수 있습니다.");
        var statusId = In.has(in, "statusId") ? In.uuid(in, "statusId", "status") : null;
        if (In.has(in, "statusId") && (statusId == null || s.status(statusId).isEmpty())) throw ApiException.field("status", "알 수 없는 상태입니다.");
        var priorityId = In.has(in, "priorityId") ? In.uuid(in, "priorityId", "priority") : null;
        if (In.has(in, "priorityId") && (priorityId == null || s.priority(priorityId).isEmpty())) throw ApiException.field("priority", "알 수 없는 우선순위입니다.");
        var categoryId = In.uuid(in, "categoryId", "category");
        var category = s.category(categoryId).orElse(null);
        if (categoryId != null && (category == null || !category.projectId().equals(p.id()))) throw ApiException.field("category", "이 프로젝트의 카테고리가 아닙니다.");
        var versionId = In.uuid(in, "versionId", "version");
        if (versionId != null) {
            ctx.require(p, "wp.assign_versions");
            if (s.version(versionId).filter(v -> v.projectId().equals(p.id())).isEmpty()) throw ApiException.field("version", "이 프로젝트의 버전이 아닙니다.");
        }
        if (ticket != null && statusId == null) statusId = com.openpms.itsm.ItsmIds.STATUS_RECEIVED;
        if (ticket != null && priorityId == null) priorityId = serviceDesk.priorityFor(p, ticket);
        // a category's default assignee fills an empty assignee (S25)
        if (assigneeId == null && category != null && category.defaultAssigneeId() != null && s.isMember(category.defaultAssigneeId(), p.id())) {
            assigneeId = category.defaultAssigneeId();
        }
        UUID parentId = null;
        var parentNumber = In.integer(in, "parentNumber", "parent");
        if (parentNumber != null && parentNumber > 0) {
            ctx.require(p, "wp.manage_subtasks");
            var parent = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(parentNumber.longValue())).fetchOne();
            if (parent == null || !visible(ctx, parent)) throw ApiException.field("parent", "상위 작업을 찾을 수 없습니다.");
            parentId = parent.getId();
        }

        // portal: a required field the requester cannot see must not block (or reveal itself in) the submission. User and
        // version fields are never on the portal form (PortalService.NOT_ON_PORTAL), whatever their portal flag says.
        var hidden = acting == Acting.PORTAL
                ? new java.util.HashSet<>(db.select(CUSTOM_FIELDS.ID).from(CUSTOM_FIELDS)
                        .where(CUSTOM_FIELDS.PORTAL_VISIBLE.isFalse().or(CUSTOM_FIELDS.FORMAT.in("user", "version"))).fetch(CUSTOM_FIELDS.ID))
                : java.util.Set.<UUID>of();
        var custom = customFields.initial(s, p.id(), typeId, customInput(in), hidden);

        schedules.lock();
        var graph = schedules.load(ctx);
        var milestone = s.type(typeId).map(Snapshot.TypeRow::isMilestone).orElse(false);
        var draft = new Schedule.Node(UUID.randomUUID(), 0, subject, parentId, milestone, null, null, null, false, true);
        var input = new Schedule.Input();
        input.hasStart = In.has(in, "startDate");
        input.start = In.date(in, "startDate", "startDate");
        input.hasDue = In.has(in, "dueDate");
        input.due = In.date(in, "dueDate", "dueDate");
        var dates = graph.resolve(draft, milestone, false, input);

        var html = Html.sanitize(In.str(in, "descriptionHtml"));
        var now = OffsetDateTime.now();
        var w = db.newRecord(WORK_PACKAGES);
        w.setProjectId(p.id());
        w.setTypeId(typeId);
        w.setStatusId(statusId != null ? statusId : s.defaultStatus().id());
        w.setPriorityId(priorityId != null ? priorityId : s.defaultPriority().id());
        w.setSubject(subject);
        w.setDescriptionHtml(html);
        w.setDescriptionText(Html.text(html));
        w.setAuthorId(ctx.userId());
        w.setAssigneeId(assigneeId);
        w.setResponsibleId(responsibleId);
        w.setVersionId(versionId);
        w.setCategoryId(categoryId);
        w.setParentId(parentId);
        w.setStartDate(dates.start());
        w.setDueDate(dates.due());
        w.setDuration(dates.duration());
        w.setCustomValues(customFields.write(custom));
        var status = s.status(w.getStatusId()).orElseThrow();
        var progress = Progress.derive(s.settings().progress(), status.isClosed(), status.defaultDoneRatio(), null, null, null, false, false);
        w.setDoneRatio(progress.doneRatio() == null ? null : progress.doneRatio().shortValue());
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        w.store();
        w.refresh();
        if (team != null && !team.isEmpty()) assignees.write(w.getId(), team);
        else if (assigneeId != null) assignees.write(w.getId(), List.of(new Assignees.Entry(assigneeId, null)));

        if (ticket != null) serviceDesk.created(ctx, w, ticket);
        var j = journals.write(ctx, w, new LinkedHashMap<>(Map.of(Journals.CREATED, Journals.listOf(null, true))), "", null, false);
        journals.fanOut(ctx, w, j, List.of());
        webhooks.enqueue(ctx, w, "work_package.created", null);
        var g = schedules.load(ctx);
        schedules.apply(ctx, g.propagate(List.of(w.getId()), null, false));
        return w;
    }

    /** The request's customValues object ({fieldId: value}), or 400 when it is something else. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> customInput(Map<String, Object> in) {
        var v = in.get("customValues");
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> m)) throw new ApiException(400, "customValues는 객체여야 합니다.");
        return (Map<String, Object>) m;
    }

    /* ---------- update (applyPatch) ---------- */

    private static Object jsonValue(Object v) {
        if (v == null) return null;
        if (v instanceof UUID || v instanceof LocalDate) return v.toString();
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().doubleValue();
        if (v instanceof Short sh) return sh.intValue();
        return v;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }

    @Transactional
    public Dto.WorkPackage update(Ctx ctx, long number, Map<String, Object> patch) {
        var f = find(ctx, number);
        ctx.require(f.project(), f.wp().getId(), "wp.edit", "이 작업을 수정할 권한이 없습니다.");
        return assembler.one(ctx, apply(ctx, f, patch, Acting.MEMBER));
    }

    /** The requester moves their own ticket (answered, confirmed, reopened, cancelled) under the 요청자 role's workflow. */
    @Transactional
    public WorkPackagesRecord statusForPortal(Ctx ctx, Found f, UUID statusId, int lockVersion) {
        var patch = new java.util.HashMap<String, Object>();
        patch.put("lockVersion", lockVersion);
        patch.put("statusId", statusId.toString());
        return apply(ctx, f, patch, Acting.PORTAL);
    }

    /** The saved record (f.wp() when nothing changed). A portal conflict carries no body: a requester never gets the assembled work package. */
    private WorkPackagesRecord apply(Ctx ctx, Found f, Map<String, Object> patch, Acting acting) {
        var s = ctx.s();
        var wp = f.wp();
        var project = f.project();
        var lockVersion = In.integer(patch, "lockVersion", "lockVersion");
        if (lockVersion == null || lockVersion != wp.getLockVersion()) {
            throw ApiException.conflict("다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 저장하세요.", acting == Acting.PORTAL ? null : assembler.one(ctx, wp));
        }
        var status = s.status(wp.getStatusId()).orElseThrow();
        var given = patch.keySet().stream().filter(k -> !k.equals("lockVersion") && !k.equals("touched")).toList();
        if (status.isReadonly() && given.stream().anyMatch(k -> !k.equals("statusId"))) {
            throw ApiException.unprocessable("읽기 전용 상태입니다.", "'" + status.name() + "' 상태에서는 상태만 바꿀 수 있습니다.");
        }

        var changes = new LinkedHashMap<String, List<Object>>();
        var before = wp.copy();
        var team = assignees.of(wp.getId());
        List<Assignees.Entry> nextTeam = null;
        Setter set = (field, current, next, apply) -> {
            if (!same(current, next)) {
                changes.put(field, Journals.listOf(jsonValue(current), jsonValue(next)));
                apply.run();
            }
        };

        if (In.has(patch, "statusId")) {
            var next = In.uuid(patch, "statusId", "status");
            if (next != null && !next.equals(wp.getStatusId())) {
                var allowed = acting == Acting.PORTAL
                        ? Assembler.allowedFor(ctx.s(), java.util.Set.of(com.openpms.itsm.ItsmIds.ROLE_REQUESTER), ctx.userId(), wp.getTypeId(), wp.getStatusId(),
                                ctx.userId(), Assignees.ids(team))
                        : Assembler.allowedStatusIds(ctx, wp.getTypeId(), wp.getStatusId(), wp.getProjectId(), wp.getAuthorId(), Assignees.ids(team));
                if (!allowed.contains(next)) {
                    var to = s.status(next).map(Snapshot.StatusRow::name).orElse("?");
                    throw ApiException.unprocessable("허용되지 않는 상태 전환입니다.", "내 역할로는 '" + status.name() + "'에서 '" + to + "'(으)로 바꿀 수 없습니다.");
                }
                set.put("statusId", wp.getStatusId(), next, () -> wp.setStatusId(next));
            }
        }
        if (In.has(patch, "subject")) {
            var subject = In.str(patch, "subject") == null ? "" : In.str(patch, "subject").trim();
            if (subject.isEmpty()) throw ApiException.field("subject", "제목을 입력하세요.");
            if (subject.length() > 255) throw ApiException.field("subject", "제목은 255자 이하로 입력하세요.");
            set.put("subject", wp.getSubject(), subject, () -> wp.setSubject(subject));
        }
        if (In.has(patch, "descriptionHtml")) {
            var html = Html.sanitize(In.str(patch, "descriptionHtml"));
            set.put("descriptionHtml", wp.getDescriptionHtml(), html, () -> {
                wp.setDescriptionHtml(html);
                wp.setDescriptionText(Html.text(html));
            });
        }
        if (In.has(patch, "typeId")) {
            var next = In.uuid(patch, "typeId", "type");
            if (next == null || !project.typeIds().contains(next)) throw ApiException.field("type", "이 프로젝트에서 쓰지 않는 유형입니다.");
            set.put("typeId", wp.getTypeId(), next, () -> wp.setTypeId(next));
        }
        if (In.has(patch, "priorityId")) {
            var next = In.uuid(patch, "priorityId", "priority");
            if (next == null || s.priority(next).isEmpty()) throw ApiException.field("priority", "알 수 없는 우선순위입니다.");
            set.put("priorityId", wp.getPriorityId(), next, () -> wp.setPriorityId(next));
        }
        // 공동 담당자: the whole list ("assignees"), or the main assignee the older way ("assigneeId")
        if (In.has(patch, "assignees")) nextTeam = Assignees.read(patch.get("assignees"), s, project.id());
        else if (In.has(patch, "assigneeId")) {
            var next = In.uuid(patch, "assigneeId", "assignee");
            if (next != null && !s.isMember(next, project.id())) throw ApiException.field("assignee", "프로젝트 멤버만 지정할 수 있습니다.");
            nextTeam = Assignees.withMain(team, next);
        }
        if (nextTeam != null && !Assignees.same(team, nextTeam)) {
            var main = nextTeam.isEmpty() ? null : nextTeam.get(0).userId();
            set.put("assigneeId", wp.getAssigneeId(), main, () -> wp.setAssigneeId(main));
            if (Assignees.beyondMain(team) || Assignees.beyondMain(nextTeam)) changes.put("assignees", Journals.listOf(Assignees.journal(team), Assignees.journal(nextTeam)));
        } else nextTeam = null;
        if (In.has(patch, "responsibleId")) {
            var next = In.uuid(patch, "responsibleId", "responsible");
            if (next != null && !s.isMember(next, project.id())) throw ApiException.field("responsible", "프로젝트 멤버만 지정할 수 있습니다.");
            set.put("responsibleId", wp.getResponsibleId(), next, () -> wp.setResponsibleId(next));
        }
        if (In.has(patch, "versionId")) {
            ctx.require(project, "wp.assign_versions");
            var next = In.uuid(patch, "versionId", "version");
            if (next != null && s.version(next).filter(v -> v.projectId().equals(project.id())).isEmpty()) throw ApiException.field("version", "이 프로젝트의 버전이 아닙니다.");
            set.put("versionId", wp.getVersionId(), next, () -> wp.setVersionId(next));
        }
        if (In.has(patch, "categoryId")) {
            var next = In.uuid(patch, "categoryId", "category");
            if (next != null && s.category(next).filter(c -> c.projectId().equals(project.id())).isEmpty()) throw ApiException.field("category", "이 프로젝트의 카테고리가 아닙니다.");
            set.put("categoryId", wp.getCategoryId(), next, () -> wp.setCategoryId(next));
        }

        schedules.lock();
        Schedule graph = null;
        if (In.has(patch, "parentNumber")) {
            ctx.require(project, "wp.manage_subtasks");
            var n = In.integer(patch, "parentNumber", "parent");
            WorkPackagesRecord parent = null;
            if (n != null) {
                parent = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.eq(n.longValue())).fetchOne();
                if (parent == null || !visible(ctx, parent)) throw ApiException.field("parent", "상위 작업을 찾을 수 없습니다.");
                graph = schedules.load(ctx);
                if (parent.getId().equals(wp.getId()) || graph.wouldCycle(wp.getId(), parent.getId())) {
                    throw ApiException.field("parent", "자기 자신이나 하위 작업을 상위 작업으로 지정할 수 없습니다.");
                }
            }
            var next = parent == null ? null : parent.getId();
            set.put("parentId", wp.getParentId(), next, () -> wp.setParentId(next));
        }
        if (In.has(patch, "scheduleManually")) {
            var next = Boolean.TRUE.equals(In.bool(patch, "scheduleManually"));
            set.put("scheduleManually", wp.getScheduleManually(), next, () -> wp.setScheduleManually(next));
        }
        if (In.has(patch, "ignoreNonWorkingDays")) {
            var next = Boolean.TRUE.equals(In.bool(patch, "ignoreNonWorkingDays"));
            set.put("ignoreNonWorkingDays", wp.getIgnoreNonWorkingDays(), next, () -> wp.setIgnoreNonWorkingDays(next));
        }
        if (In.has(patch, "startDate") || In.has(patch, "dueDate") || In.has(patch, "duration")) {
            boolean hasKids = db.fetchExists(WORK_PACKAGES, WORK_PACKAGES.PARENT_ID.eq(wp.getId()));
            if (hasKids && !wp.getScheduleManually()) {
                throw ApiException.unprocessable("날짜는 하위 작업에서 정해집니다.", "자동 일정인 상위 작업의 날짜는 하위 작업의 날짜를 바꿔서 조정하세요.");
            }
            if (graph == null) graph = schedules.load(ctx);
            var r = graph.resolve(nodeOf(wp, s), s.type(wp.getTypeId()).map(Snapshot.TypeRow::isMilestone).orElse(false), wp.getIgnoreNonWorkingDays(),
                    input(patch));
            set.put("startDate", wp.getStartDate(), r.start(), () -> wp.setStartDate(r.start()));
            set.put("dueDate", wp.getDueDate(), r.due(), () -> wp.setDueDate(r.due()));
            set.put("duration", wp.getDuration(), r.duration(), () -> wp.setDuration(r.duration()));
        }
        progress(patch, wp, set, s);
        if (In.has(patch, "customValues")) {
            // against the type as patched: a type change in the same request decides which fields exist
            var r = customFields.apply(s, project.id(), wp.getTypeId(), customFields.read(wp.getCustomValues()), customInput(patch));
            if (!r.changes().isEmpty()) {
                changes.putAll(r.changes());
                wp.setCustomValues(customFields.write(r.values()));
            }
        }

        if (changes.isEmpty()) return wp;

        wp.setLockVersion(before.getLockVersion() + 1);
        wp.setUpdatedAt(OffsetDateTime.now());
        int n = db.executeUpdate(wp, WORK_PACKAGES.ID.eq(wp.getId()).and(WORK_PACKAGES.LOCK_VERSION.eq(before.getLockVersion())));
        if (n == 0) {
            throw ApiException.conflict("다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 저장하세요.", acting == Acting.PORTAL ? null : assembler.one(ctx, reload(wp.getId())));
        }
        if (nextTeam != null) assignees.write(wp.getId(), nextTeam);
        var saved = reload(wp.getId());
        serviceDesk.updated(ctx, before, saved);
        var j = journals.write(ctx, saved, changes, "", null, false);
        journals.fanOut(ctx, saved, j, List.of());
        webhooks.enqueue(ctx, saved, "work_package.updated", Map.of("changes", com.openpms.webhook.Webhooks.changes(journals.toDto(ctx, j, ctx.s().project(saved.getProjectId()).orElseThrow(), changes))));

        // S7: dates, mode or hierarchy changes ripple to successors and automatic parents
        if (changes.containsKey("startDate") || changes.containsKey("dueDate") || changes.containsKey("parentId") || changes.containsKey("scheduleManually")) {
            var g = schedules.load(ctx);
            var seeds = new ArrayList<UUID>();
            seeds.add(saved.getId());
            var oldParent = before.getParentId();
            if (oldParent != null && !oldParent.equals(saved.getParentId())) g.kids(oldParent).stream().findFirst().ifPresent(k -> seeds.add(k.id));
            schedules.apply(ctx, g.propagate(seeds, null, false));
        }
        return reload(wp.getId());
    }

    @FunctionalInterface
    private interface Setter {
        void put(String field, Object current, Object next, Runnable apply);
    }

    /** P1: work, remaining work and % done (two given → the third follows). */
    /** P1–P3 with the S32 settings: typed values first, then what follows from them and from the (new) status. */
    private static void progress(Map<String, Object> patch, WorkPackagesRecord wp, Setter set, Snapshot s) {
        var settings = s.settings().progress();
        var hasWork = In.has(patch, "workHours");
        var hasRatio = In.has(patch, "doneRatio");
        var hasRemaining = In.has(patch, "remainingHours");
        if (hasWork) {
            var v = In.decimal(patch, "workHours", "work");
            if (v != null && v.signum() < 0) throw ApiException.field("work", "0 이상으로 입력하세요.");
            set.put("workHours", wp.getWorkHours(), v, () -> wp.setWorkHours(v));
        }
        var status = s.status(wp.getStatusId()).orElseThrow();
        if ((hasRatio || hasRemaining) && Progress.locked(settings, status.isClosed()))
            throw ApiException.unprocessable("완료 %는 상태를 따릅니다.", "진행률 설정(상태 기반, 또는 닫힘 상태면 100%) 때문에 완료 %와 남은 작업은 직접 바꿀 수 없습니다.");
        if (hasRatio) {
            var v = In.integer(patch, "doneRatio", "doneRatio");
            if (v != null && (v < 0 || v > 100)) throw ApiException.field("doneRatio", "0에서 100 사이로 입력하세요.");
            var cur = wp.getDoneRatio() == null ? null : wp.getDoneRatio().intValue();
            set.put("doneRatio", cur, v, () -> wp.setDoneRatio(v == null ? null : v.shortValue()));
        }
        if (hasRemaining) {
            var v = In.decimal(patch, "remainingHours", "remainingWork");
            if (v != null && v.signum() < 0) throw ApiException.field("remainingWork", "0 이상으로 입력하세요.");
            if (v != null && wp.getWorkHours() != null && v.compareTo(wp.getWorkHours()) > 0) throw ApiException.field("remainingWork", "남은 작업은 작업량보다 클 수 없습니다.");
            set.put("remainingHours", wp.getRemainingHours(), v, () -> wp.setRemainingHours(v));
        }
        var cur = wp.getDoneRatio() == null ? null : wp.getDoneRatio().intValue();
        var next = Progress.derive(settings, status.isClosed(), status.defaultDoneRatio(), wp.getWorkHours(), cur, wp.getRemainingHours(), hasRemaining, hasWork || hasRatio);
        set.put("doneRatio", cur, next.doneRatio(), () -> wp.setDoneRatio(next.doneRatio() == null ? null : next.doneRatio().shortValue()));
        set.put("remainingHours", wp.getRemainingHours(), next.remainingHours(), () -> wp.setRemainingHours(next.remainingHours()));
    }

    private static Schedule.Node nodeOf(WorkPackagesRecord w, Snapshot s) {
        return new Schedule.Node(w.getId(), w.getNumber(), w.getSubject(), w.getParentId(), s.type(w.getTypeId()).map(Snapshot.TypeRow::isMilestone).orElse(false),
                w.getStartDate(), w.getDueDate(), w.getDuration(), w.getIgnoreNonWorkingDays(), w.getScheduleManually());
    }

    private static Schedule.Input input(Map<String, Object> patch) {
        var in = new Schedule.Input();
        in.hasStart = In.has(patch, "startDate");
        in.start = In.date(patch, "startDate", "startDate");
        in.hasDue = In.has(patch, "dueDate");
        in.due = In.date(patch, "dueDate", "dueDate");
        in.hasDuration = In.has(patch, "duration");
        in.duration = In.integer(patch, "duration", "duration");
        if (in.duration != null && in.duration < 1) throw ApiException.field("duration", "기간은 1일 이상이어야 합니다.");
        in.touched = In.strings(patch, "touched");
        return in;
    }

    /* ---------- delete ---------- */

    @Transactional
    public void remove(Ctx ctx, long number, String childrenMode) {
        var f = find(ctx, number);
        ctx.require(f.project(), "wp.delete", "이 작업을 삭제할 권한이 없습니다.");
        schedules.lock();
        var wp = f.wp();
        Set<UUID> doomed = new HashSet<>(List.of(wp.getId()));
        if ("delete".equals(childrenMode)) {
            var frontier = List.of(wp.getId());
            while (!frontier.isEmpty()) {
                var next = db.select(WORK_PACKAGES.ID).from(WORK_PACKAGES).where(WORK_PACKAGES.PARENT_ID.in(frontier)).fetch(WORK_PACKAGES.ID);
                next.removeIf(doomed::contains);
                doomed.addAll(next);
                frontier = next;
            }
        } else {
            db.update(WORK_PACKAGES).set(WORK_PACKAGES.PARENT_ID, wp.getParentId()).where(WORK_PACKAGES.PARENT_ID.eq(wp.getId())).execute();
        }
        // delivery rows keep their payload, so the item may go now
        for (var gone : db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(doomed)).fetch()) webhooks.enqueue(ctx, gone, "work_package.deleted", null);
        db.deleteFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(doomed)).execute();
        // an automatic parent re-derives its dates from the children that are left
        if (wp.getParentId() != null) {
            var g = schedules.load(ctx);
            g.kids(wp.getParentId()).stream().findFirst().ifPresent(k -> schedules.apply(ctx, g.propagate(List.of(k.id), null, false)));
        }
    }

    /* ---------- comments, watchers ---------- */

    /** internal: a comment only roles with wp.view_internal_comments read (the project must have them on). */
    @Transactional
    public Dto.Journal comment(Ctx ctx, long number, String bodyHtml, boolean internal) {
        var f = find(ctx, number);
        ctx.require(f.project(), f.wp().getId(), "wp.comment", "코멘트를 쓸 권한이 없습니다.");
        if (internal) {
            if (!f.project().internalComments()) throw ApiException.unprocessable("이 프로젝트는 내부 코멘트를 쓰지 않습니다.", "프로젝트 설정 · 작업 설정에서 켤 수 있습니다.");
            ctx.require(f.project(), "wp.add_internal_comments", "내부 코멘트를 쓸 권한이 없습니다.");
        }
        return journals.toDto(ctx, addComment(ctx, f, bodyHtml, internal), f.project());
    }

    /** A requester's public comment on their own ticket. */
    @Transactional
    public JournalsRecord commentForPortal(Ctx ctx, Found f, String bodyHtml) {
        return addComment(ctx, f, bodyHtml, false);
    }

    private JournalsRecord addComment(Ctx ctx, Found f, String bodyHtml, boolean internal) {
        var html = Html.sanitize(bodyHtml);
        if (Html.text(html).isEmpty()) throw ApiException.field("comment", "코멘트를 입력하세요.");
        var j = journals.write(ctx, f.wp(), new LinkedHashMap<>(), html, null, internal);
        journals.fanOut(ctx, f.wp(), j, Html.mentions(html));
        serviceDesk.commented(ctx, f.wp(), internal);
        // internal comments stay inside openPMS
        if (!internal) webhooks.enqueue(ctx, f.wp(), "work_package.commented", Map.of("comment", Map.of("text", Html.text(html), "journalId", j.getId())));
        return j;
    }

    @Transactional
    public Dto.Journal editComment(Ctx ctx, UUID journalId, String bodyHtml) {
        var j = db.selectFrom(JOURNALS).where(JOURNALS.ID.eq(journalId)).fetchOne();
        if (j == null) throw ApiException.notFound("코멘트를 찾을 수 없습니다.");
        var wp = reload(j.getWorkPackageId());
        var f = find(ctx, wp.getNumber());
        if (!journals.toDto(ctx, j, f.project()).canEdit()) throw ApiException.forbidden("이 코멘트를 수정할 권한이 없습니다.");
        var html = Html.sanitize(bodyHtml);
        if (Html.text(html).isEmpty()) throw ApiException.field("comment", "코멘트를 입력하세요.");
        j.setNotesHtml(html);
        j.setNotesText(Html.text(html));
        j.setUpdatedAt(OffsetDateTime.now());
        j.store();
        return journals.toDto(ctx, j, f.project());
    }

    /* ---------- shares (S07/S08 공유) ---------- */

    private static final List<String> SHARE_ROLES = List.of("view", "comment", "edit");

    private Dto.WorkPackageShare shareDto(Ctx ctx, WorkPackageSharesRecord r) {
        return new Dto.WorkPackageShare(r.getId(), Refs.user(ctx.s(), r.getUserId()), r.getRole(), r.getCreatedBy() == null ? null : Refs.user(ctx.s(), r.getCreatedBy()),
                r.getCreatedAt());
    }

    public List<Dto.WorkPackageShare> shares(Ctx ctx, long number) {
        var f = find(ctx, number);
        ctx.require(f.project(), "wp.share", "작업을 공유할 권한이 없습니다.");
        return db.selectFrom(WORK_PACKAGE_SHARES).where(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID.eq(f.wp().getId())).fetch().stream()
                .map(r -> shareDto(ctx, r))
                .sorted((a, b) -> com.openpms.core.Text.KOREAN.compare(a.user().name(), b.user().name()))
                .toList();
    }

    /** Shares the item, or changes the role of an existing share. Only a new share notifies (reason "shared"). */
    @Transactional
    public Dto.WorkPackageShare share(Ctx ctx, long number, UUID userId, String role) {
        var f = find(ctx, number);
        ctx.require(f.project(), "wp.share", "작업을 공유할 권한이 없습니다.");
        if (role == null || !SHARE_ROLES.contains(role)) throw ApiException.field("role", "공유 권한을 고르세요.");
        if (userId == null) throw ApiException.field("userId", "공유할 사람을 고르세요.");
        if (userId.equals(ctx.userId())) throw ApiException.field("userId", "자기 자신에게는 공유할 수 없습니다.");
        if (ctx.s().user(userId).filter(u -> "active".equals(u.status())).isEmpty()) throw ApiException.field("userId", "활성 사용자에게만 공유할 수 있습니다.");
        var existing = db.selectFrom(WORK_PACKAGE_SHARES).where(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID.eq(f.wp().getId()), WORK_PACKAGE_SHARES.USER_ID.eq(userId)).fetchOne();
        if (existing != null) {
            existing.setRole(role);
            existing.store();
            return shareDto(ctx, existing);
        }
        var r = db.newRecord(WORK_PACKAGE_SHARES);
        r.setWorkPackageId(f.wp().getId());
        r.setUserId(userId);
        r.setRole(role);
        r.setCreatedBy(ctx.userId());
        r.store();
        r.refresh();
        journals.notifyShared(ctx, f.wp(), userId);
        return shareDto(ctx, r);
    }

    private WorkPackageSharesRecord findShare(Ctx ctx, UUID id) {
        var r = db.selectFrom(WORK_PACKAGE_SHARES).where(WORK_PACKAGE_SHARES.ID.eq(id)).fetchOne();
        if (r == null) throw ApiException.notFound("공유를 찾을 수 없습니다.");
        var wp = reload(r.getWorkPackageId());
        var f = find(ctx, wp.getNumber());
        ctx.require(f.project(), "wp.share", "작업을 공유할 권한이 없습니다.");
        return r;
    }

    @Transactional
    public Dto.WorkPackageShare updateShare(Ctx ctx, UUID id, String role) {
        var r = findShare(ctx, id);
        if (role == null || !SHARE_ROLES.contains(role)) throw ApiException.field("role", "공유 권한을 고르세요.");
        r.setRole(role);
        r.store();
        return shareDto(ctx, r);
    }

    @Transactional
    public void removeShare(Ctx ctx, UUID id) {
        findShare(ctx, id).delete();
    }

    public List<Dto.Watcher> watchers(Ctx ctx, long number) {
        var f = find(ctx, number);
        ctx.require(f.project(), "wp.view_watchers", "주시자를 볼 권한이 없습니다.");
        boolean canDelete = ctx.can(f.project(), "wp.delete_watchers");
        var collator = java.text.Collator.getInstance(java.util.Locale.KOREAN);
        return db.select(WATCHERS.USER_ID).from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(f.wp().getId())).fetch(WATCHERS.USER_ID).stream()
                .map(id -> new Dto.Watcher(Refs.user(ctx.s(), id), canDelete || id.equals(ctx.userId())))
                .sorted((a, b) -> collator.compare(a.user().name(), b.user().name()))
                .toList();
    }

    /** Yourself with wp.view; someone else with wp.add_watchers, and only if they can see the item. */
    @Transactional
    public void addWatcher(Ctx ctx, long number, UUID userId) {
        var f = find(ctx, number);
        if (!userId.equals(ctx.userId())) ctx.require(f.project(), "wp.add_watchers", "주시자를 추가할 권한이 없습니다.");
        var u = ctx.s().user(userId).filter(x -> "active".equals(x.status())).orElse(null);
        if (u == null || !ctx.can(u, f.project(), f.wp().getId(), "wp.view"))
            throw ApiException.field("userId", "이 작업을 볼 수 없는 사람은 주시자로 추가할 수 없습니다.");
        db.insertInto(WATCHERS).set(WATCHERS.WORK_PACKAGE_ID, f.wp().getId()).set(WATCHERS.USER_ID, userId).onConflictDoNothing().execute();
    }

    @Transactional
    public void removeWatcher(Ctx ctx, long number, UUID userId) {
        var f = find(ctx, number);
        if (!userId.equals(ctx.userId())) ctx.require(f.project(), "wp.delete_watchers", "주시자를 뺄 권한이 없습니다.");
        db.deleteFrom(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.eq(f.wp().getId()), WATCHERS.USER_ID.eq(userId)).execute();
    }

    /* ---------- relations ---------- */

    @Transactional
    public void addRelation(Ctx ctx, long number, String type, long toNumber, Integer lag) {
        var a = find(ctx, number);
        var b = find(ctx, toNumber);
        ctx.require(a.project(), "wp.manage_relations", "관계를 관리할 권한이 없습니다.");
        ctx.require(b.project(), "wp.manage_relations", "상대 작업의 관계를 관리할 권한이 없습니다.");
        if (a.wp().getId().equals(b.wp().getId())) throw ApiException.field("toNumber", "자기 자신과는 관계를 맺을 수 없습니다.");
        var wp = a.wp();
        var other = b.wp();
        record Canon(WorkPackagesRecord from, WorkPackagesRecord to, String type) {}
        var canon = switch (type == null ? "" : type) {
            case "relates" -> new Canon(wp, other, "relates");
            case "precedes" -> new Canon(wp, other, "precedes");
            case "follows" -> new Canon(other, wp, "precedes");
            case "duplicates" -> new Canon(wp, other, "duplicates");
            case "duplicated" -> new Canon(other, wp, "duplicates");
            case "blocks" -> new Canon(wp, other, "blocks");
            case "blocked" -> new Canon(other, wp, "blocks");
            case "includes" -> new Canon(wp, other, "includes");
            case "partof" -> new Canon(other, wp, "includes");
            case "requires" -> new Canon(wp, other, "requires");
            case "required" -> new Canon(other, wp, "requires");
            default -> throw ApiException.field("type", "알 수 없는 관계입니다.");
        };
        boolean exists = db.fetchExists(RELATIONS, RELATIONS.FROM_ID.eq(wp.getId()).and(RELATIONS.TO_ID.eq(other.getId()))
                .or(RELATIONS.FROM_ID.eq(other.getId()).and(RELATIONS.TO_ID.eq(wp.getId()))));
        if (exists) throw new ApiException(409, "이미 관계가 있습니다.", "두 작업 사이에는 관계를 하나만 둘 수 있습니다.");
        schedules.lock();
        if (canon.type().equals("precedes") && schedules.load(ctx).wouldCycle(canon.from().getId(), canon.to().getId())) {
            throw ApiException.unprocessable("순환 관계가 됩니다.", "상위·하위 작업 사이나 이미 이어진 선후행 작업끼리는 선후행 관계를 맺을 수 없습니다.");
        }
        int l = canon.type().equals("precedes") ? Math.max(0, lag == null ? 0 : lag) : 0;
        db.insertInto(RELATIONS).set(RELATIONS.FROM_ID, canon.from().getId()).set(RELATIONS.TO_ID, canon.to().getId()).set(RELATIONS.TYPE, canon.type())
                .set(RELATIONS.LAG, l).set(RELATIONS.CREATED_BY, ctx.userId()).execute();
        if (canon.type().equals("precedes")) {
            var g = schedules.load(ctx);
            schedules.apply(ctx, g.propagate(List.of(canon.from().getId()), null, false));
        }
    }

    @Transactional
    public void removeRelation(Ctx ctx, UUID relationId) {
        var r = db.selectFrom(RELATIONS).where(RELATIONS.ID.eq(relationId)).fetchOne();
        var from = r == null ? null : reload(r.getFromId());
        if (r == null || !visible(ctx, from)) throw ApiException.notFound("관계를 찾을 수 없습니다.");
        ctx.require(ctx.s().project(from.getProjectId()).orElseThrow(), "wp.manage_relations", "관계를 관리할 권한이 없습니다.");
        db.deleteFrom(RELATIONS).where(RELATIONS.ID.eq(relationId)).execute();
    }

    /* ---------- schedule preview (S10) ---------- */

    public Dto.SchedulePreview schedulePreview(Ctx ctx, long number, Map<String, Object> patch) {
        var wp = find(ctx, number).wp();
        var graph = schedules.load(ctx);
        var node = graph.node(wp.getId());
        if (In.has(patch, "scheduleManually")) node.manual = Boolean.TRUE.equals(In.bool(patch, "scheduleManually"));
        if (In.has(patch, "ignoreNonWorkingDays")) node.ignore = Boolean.TRUE.equals(In.bool(patch, "ignoreNonWorkingDays"));
        var r = graph.resolve(node, node.milestone, node.ignore, input(patch));
        var overrides = new HashMap<UUID, Schedule.Triple>();
        overrides.put(wp.getId(), r);
        var affected = graph.propagate(List.of(wp.getId()), overrides, true).stream().map(c -> {
            var t = overrides.get(c.node().id);
            return new Dto.Affected(c.node().number, c.node().subject, t.start(), t.due());
        }).sorted(Comparator.comparingLong(Dto.Affected::number)).toList();
        return new Dto.SchedulePreview(r.start(), r.due(), r.duration(), affected);
    }
}
