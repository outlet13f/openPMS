package com.openpms.wp;

import static com.openpms.db.Tables.RELATIONS;
import static com.openpms.db.Tables.WATCHERS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.jooq.impl.DSL.count;

import com.openpms.api.Dto;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * Work package rows → API DTOs (dto.ts toWp). Counts that need other tables (children, watchers, relations) are
 * fetched once per batch, so a list of 200 rows costs a handful of queries, not 200.
 */
@Component
public class Assembler {
    private final DSLContext db;

    public Assembler(DSLContext db) {
        this.db = db;
    }

    /** Editable fields in the order dto.ts lists them. */
    private static final List<String> EDITABLE = List.of("subject", "description", "type", "priority", "assignee", "responsible", "category", "dates",
            "work", "remainingWork", "doneRatio");

    public static Dto.WorkPackageRef ref(Snapshot s, WorkPackagesRecord w) {
        return new Dto.WorkPackageRef(w.getId(), w.getNumber(), w.getSubject(), Refs.type(s, w.getTypeId()), Refs.status(s, w.getStatusId()),
                Refs.project(s, w.getProjectId()));
    }

    public static Dto.WorkPackageRefDated dated(Snapshot s, WorkPackagesRecord w) {
        return new Dto.WorkPackageRefDated(w.getId(), w.getNumber(), w.getSubject(), Refs.type(s, w.getTypeId()), Refs.status(s, w.getStatusId()),
                Refs.project(s, w.getProjectId()), w.getStartDate(), w.getDueDate());
    }

    /** The statuses this user may move this work package to (architecture.md §3.2). */
    public static List<UUID> allowedStatusIds(Ctx ctx, UUID typeId, UUID statusId, UUID projectId, UUID authorId, UUID assigneeId) {
        var s = ctx.s();
        if (ctx.isAdmin()) return s.statuses().stream().map(Snapshot.StatusRow::id).filter(id -> !id.equals(statusId)).toList();
        var roleIds = s.memberRoleIds(ctx.userId(), projectId);
        var out = new ArrayList<UUID>();
        for (var w : s.workflows()) {
            if (!w.typeId().equals(typeId) || !w.from().equals(statusId) || !roleIds.contains(w.roleId())) continue;
            boolean applies = switch (w.appliesTo()) {
                case "author" -> ctx.userId().equals(authorId);
                case "assignee" -> ctx.userId().equals(assigneeId);
                default -> true;
            };
            if (applies && !out.contains(w.to())) out.add(w.to());
        }
        return out;
    }

    public static Double num(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    public Dto.WorkPackage one(Ctx ctx, WorkPackagesRecord w) {
        return many(ctx, List.of(w)).get(0);
    }

    public List<Dto.WorkPackage> many(Ctx ctx, List<WorkPackagesRecord> rows) {
        if (rows.isEmpty()) return List.of();
        var s = ctx.s();
        var ids = rows.stream().map(WorkPackagesRecord::getId).toList();

        Map<UUID, Integer> childCount = new HashMap<>();
        db.select(WORK_PACKAGES.PARENT_ID, count()).from(WORK_PACKAGES).where(WORK_PACKAGES.PARENT_ID.in(ids)).groupBy(WORK_PACKAGES.PARENT_ID)
                .fetch().forEach(r -> childCount.put(r.value1(), r.value2()));
        Map<UUID, Integer> watcherCount = new HashMap<>();
        Set<UUID> watching = new HashSet<>();
        db.select(WATCHERS.WORK_PACKAGE_ID, WATCHERS.USER_ID).from(WATCHERS).where(WATCHERS.WORK_PACKAGE_ID.in(ids)).fetch().forEach(r -> {
            watcherCount.merge(r.value1(), 1, Integer::sum);
            if (r.value2().equals(ctx.userId())) watching.add(r.value1());
        });
        Map<UUID, Integer> relationCount = new HashMap<>();
        db.select(RELATIONS.FROM_ID, RELATIONS.TO_ID).from(RELATIONS).where(RELATIONS.FROM_ID.in(ids).or(RELATIONS.TO_ID.in(ids))).fetch().forEach(r -> {
            relationCount.merge(r.value1(), 1, Integer::sum);
            relationCount.merge(r.value2(), 1, Integer::sum);
        });
        var parentIds = rows.stream().map(WorkPackagesRecord::getParentId).filter(x -> x != null).distinct().toList();
        Map<UUID, WorkPackagesRecord> parents = new HashMap<>();
        if (!parentIds.isEmpty()) db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(parentIds)).fetch().forEach(p -> parents.put(p.getId(), p));

        var out = new ArrayList<Dto.WorkPackage>(rows.size());
        for (var w : rows) {
            var project = s.project(w.getProjectId()).orElseThrow();
            var status = s.status(w.getStatusId()).orElseThrow();
            int kids = childCount.getOrDefault(w.getId(), 0);
            boolean canEdit = ctx.can(project, "wp.edit");

            var derived = new ArrayList<String>();
            if (!w.getScheduleManually() && kids > 0) derived.add("dates");
            var editable = new ArrayList<String>();
            if (canEdit) {
                if (!status.isReadonly()) {
                    editable.addAll(EDITABLE);
                    if (ctx.can(project, "wp.assign_versions")) editable.add("version");
                    if (ctx.can(project, "wp.manage_subtasks")) editable.add("parent");
                }
                editable.removeAll(derived);
                if (!allowedStatusIds(ctx, w.getTypeId(), w.getStatusId(), w.getProjectId(), w.getAuthorId(), w.getAssigneeId()).isEmpty()) editable.add("status");
            }
            var parent = w.getParentId() == null ? null : parents.get(w.getParentId());
            boolean overdue = w.getDueDate() != null && !status.isClosed() && w.getDueDate().isBefore(ctx.today());
            var meta = new Dto.Meta(
                    new Dto.AllowedActions(canEdit, ctx.can(project, "wp.delete"), ctx.can(project, "wp.move"), ctx.can(project, "wp.comment"),
                            ctx.can(project, "wp.manage_relations"), ctx.can(project, "wp.manage_subtasks"), true),
                    editable, derived, overdue);
            out.add(new Dto.WorkPackage(w.getId(), w.getNumber(), Refs.project(project), Refs.type(s, w.getTypeId()), Refs.status(status),
                    Refs.priority(s, w.getPriorityId()), w.getSubject(), w.getDescriptionHtml(), Refs.user(s, w.getAuthorId()), Refs.user(s, w.getAssigneeId()),
                    Refs.user(s, w.getResponsibleId()), Refs.version(s, w.getVersionId()), Refs.category(s, w.getCategoryId()),
                    parent == null ? null : ref(s, parent), kids, w.getStartDate(), w.getDueDate(), w.getDuration(), w.getIgnoreNonWorkingDays(),
                    w.getScheduleManually(), num(w.getWorkHours()), num(w.getRemainingHours()), w.getDoneRatio() == null ? null : w.getDoneRatio().intValue(),
                    watching.contains(w.getId()), watcherCount.getOrDefault(w.getId(), 0),
                    relationCount.getOrDefault(w.getId(), 0) + kids + (parent != null ? 1 : 0), w.getLockVersion(), w.getCreatedAt(), w.getUpdatedAt(), meta));
        }
        return out;
    }

    /** Rows by id, in the order given. */
    public List<WorkPackagesRecord> load(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var byId = new HashMap<UUID, WorkPackagesRecord>();
        db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.ID.in(ids)).fetch().forEach(r -> byId.put(r.getId(), r));
        return ids.stream().map(byId::get).filter(x -> x != null).toList();
    }
}
