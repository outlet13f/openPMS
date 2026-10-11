package com.openpms.wp;

import static com.openpms.db.Tables.NOTIFICATIONS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.customfield.CustomFields;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy, move and bulk changes of work packages (architecture.md §4.2: /copy, /move, /bulk). Copies go through the normal create
 * rules; a move keeps the number and takes the subtree along; a bulk request is all or nothing.
 */
@Service
public class WorkPackageTransfers {
    private final DSLContext db;
    private final WorkPackageService service;
    private final Assembler assembler;
    private final Journals journals;
    private final CustomFields customFields;
    private final com.openpms.webhook.Webhooks webhooks;

    public WorkPackageTransfers(DSLContext db, WorkPackageService service, Assembler assembler, Journals journals, CustomFields customFields,
                                com.openpms.webhook.Webhooks webhooks) {
        this.webhooks = webhooks;
        this.db = db;
        this.service = service;
        this.assembler = assembler;
        this.journals = journals;
        this.customFields = customFields;
    }

    private List<WorkPackagesRecord> descendants(UUID id) {
        var out = new ArrayList<WorkPackagesRecord>();
        var frontier = List.of(id);
        while (!frontier.isEmpty()) {
            var kids = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PARENT_ID.in(frontier)).orderBy(WORK_PACKAGES.NUMBER).fetch();
            out.addAll(kids);
            frontier = kids.stream().map(WorkPackagesRecord::getId).toList();
        }
        return out;
    }

    /* ---------- copy ---------- */

    /** POST /work-packages/{n}/copy {targetProject?, subject?, includeChildren} — a new item made by the create rules. */
    @Transactional
    public Dto.WorkPackage copy(Ctx ctx, long number, Map<String, Object> in) {
        var source = service.find(ctx, number);
        var targetKey = In.str(in, "targetProject");
        var target = targetKey == null || targetKey.isBlank() ? source.project() : ctx.findProject(targetKey);
        var subject = In.str(in, "subject");
        var created = copyOne(ctx, source.wp(), target, subject == null || subject.isBlank() ? source.wp().getSubject() : subject.trim(), null);
        if (Boolean.TRUE.equals(In.bool(in, "includeChildren"))) copyChildren(ctx, source.wp().getId(), target, created.number());
        return service.get(ctx, created.number());
    }

    private void copyChildren(Ctx ctx, UUID sourceParent, ProjectRow target, long newParentNumber) {
        for (var kid : db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PARENT_ID.eq(sourceParent)).orderBy(WORK_PACKAGES.NUMBER).fetch()) {
            var made = copyOne(ctx, kid, target, kid.getSubject(), newParentNumber);
            copyChildren(ctx, kid.getId(), target, made.number());
        }
    }

    /** The create request a copy makes: what still fits the target (members, its versions and categories, its active fields). */
    private Dto.WorkPackage copyOne(Ctx ctx, WorkPackagesRecord w, ProjectRow target, String subject, Long parentNumber) {
        var s = ctx.s();
        if (!target.typeIds().contains(w.getTypeId())) {
            var type = s.type(w.getTypeId()).map(Snapshot.TypeRow::name).orElse("?");
            throw ApiException.field("targetProject", "#" + w.getNumber() + "의 유형 '" + type + "'을(를) " + target.name() + "에서 쓰지 않습니다.");
        }
        var in = new HashMap<String, Object>();
        in.put("typeId", w.getTypeId().toString());
        in.put("subject", subject);
        in.put("descriptionHtml", w.getDescriptionHtml());
        in.put("statusId", w.getStatusId().toString());
        in.put("priorityId", w.getPriorityId().toString());
        if (w.getAssigneeId() != null && s.isMember(w.getAssigneeId(), target.id())) in.put("assigneeId", w.getAssigneeId().toString());
        if (w.getResponsibleId() != null && s.isMember(w.getResponsibleId(), target.id())) in.put("responsibleId", w.getResponsibleId().toString());
        if (target.id().equals(w.getProjectId())) {
            if (w.getVersionId() != null && ctx.can(target, "wp.assign_versions")) in.put("versionId", w.getVersionId().toString());
            if (w.getCategoryId() != null) in.put("categoryId", w.getCategoryId().toString());
        }
        if (w.getStartDate() != null) in.put("startDate", w.getStartDate().toString());
        if (w.getDueDate() != null) in.put("dueDate", w.getDueDate().toString());
        if (parentNumber != null) in.put("parentNumber", parentNumber.intValue());
        else if (w.getParentId() != null && target.id().equals(w.getProjectId())) {
            var parent = db.select(WORK_PACKAGES.NUMBER).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(w.getParentId())).fetchOne(WORK_PACKAGES.NUMBER);
            if (parent != null && ctx.can(target, "wp.manage_subtasks")) in.put("parentNumber", parent.intValue());
        }
        var active = s.activeCustomFields(target.id(), w.getTypeId()).stream().map(f -> f.id().toString()).toList();
        var values = new LinkedHashMap<String, Object>();
        customFields.read(w.getCustomValues()).forEach((k, v) -> {
            if (active.contains(k)) values.put(k, v);
        });
        in.put("customValues", values);
        var made = service.create(ctx, target.id().toString(), in);
        if (w.getWorkHours() != null) {
            var patch = new HashMap<String, Object>();
            patch.put("lockVersion", made.lockVersion());
            patch.put("workHours", w.getWorkHours());
            made = service.update(ctx, made.number(), patch);
        }
        return made;
    }

    /* ---------- move ---------- */

    /**
     * POST /work-packages/{n}/move {targetProject, typeId?, lockVersion} — keeps its number and takes its subtree along. Things of the
     * old project (version, category) and people who are not members there are cleared; each moved item gets a journal entry.
     */
    @Transactional
    public Dto.WorkPackage move(Ctx ctx, long number, Map<String, Object> in) {
        var s = ctx.s();
        var f = service.find(ctx, number);
        var root = f.wp();
        var lockVersion = In.integer(in, "lockVersion", "lockVersion");
        if (lockVersion != null && lockVersion != root.getLockVersion())
            throw ApiException.conflict("다른 사람이 먼저 수정했습니다.", "최신 내용을 불러온 뒤 다시 옮기세요.", assembler.one(ctx, root));
        ctx.require(f.project(), root.getId(), "wp.move", "이 작업을 옮길 권한이 없습니다.");
        var targetKey = In.str(in, "targetProject");
        if (targetKey == null || targetKey.isBlank()) throw ApiException.field("targetProject", "옮길 프로젝트를 고르세요.");
        var target = ctx.findProject(targetKey);
        ctx.require(target, "wp.create", "대상 프로젝트에 작업을 만들 권한이 없습니다.");
        if (target.id().equals(root.getProjectId())) throw ApiException.field("targetProject", "지금 있는 프로젝트입니다.");
        var typeId = In.has(in, "typeId") ? In.uuid(in, "typeId", "type") : root.getTypeId();
        if (typeId == null || !target.typeIds().contains(typeId)) throw ApiException.field("type", "대상 프로젝트에서 쓰는 유형을 고르세요.");

        var moving = new ArrayList<WorkPackagesRecord>();
        moving.add(root);
        moving.addAll(descendants(root.getId()));
        for (var w : moving.subList(1, moving.size()))
            if (!target.typeIds().contains(w.getTypeId())) {
                var type = s.type(w.getTypeId()).map(Snapshot.TypeRow::name).orElse("?");
                throw ApiException.field("type", "하위 작업 #" + w.getNumber() + "의 유형 '" + type + "'을(를) 대상 프로젝트에서 쓰지 않습니다.");
            }
        for (var w : moving) {
            var changes = new LinkedHashMap<String, List<Object>>();
            changes.put("projectId", Journals.listOf(w.getProjectId().toString(), target.id().toString()));
            w.setProjectId(target.id());
            if (w == root && !typeId.equals(w.getTypeId())) {
                changes.put("typeId", Journals.listOf(w.getTypeId().toString(), typeId.toString()));
                w.setTypeId(typeId);
            }
            if (w.getVersionId() != null && s.version(w.getVersionId()).filter(v -> v.projectId().equals(target.id())).isEmpty()) {
                changes.put("versionId", Journals.listOf(w.getVersionId().toString(), null));
                w.setVersionId(null);
            }
            if (w.getCategoryId() != null) {
                changes.put("categoryId", Journals.listOf(w.getCategoryId().toString(), null));
                w.setCategoryId(null);
            }
            if (w.getAssigneeId() != null && !s.isMember(w.getAssigneeId(), target.id())) {
                changes.put("assigneeId", Journals.listOf(w.getAssigneeId().toString(), null));
                w.setAssigneeId(null);
            }
            if (w.getResponsibleId() != null && !s.isMember(w.getResponsibleId(), target.id())) {
                changes.put("responsibleId", Journals.listOf(w.getResponsibleId().toString(), null));
                w.setResponsibleId(null);
            }
            w.setLockVersion(w.getLockVersion() + 1);
            w.setUpdatedAt(OffsetDateTime.now());
            w.store();
            var j = journals.write(ctx, w, changes, "", null, false);
            // a move is a change of the item (its project); webhooks hear it as an update
            webhooks.enqueue(ctx, w, "work_package.updated", java.util.Map.of("changes", com.openpms.webhook.Webhooks.changes(journals.toDto(ctx, j, target))));
        }
        // notifications keep a copy of the project for their filters
        db.update(NOTIFICATIONS).set(NOTIFICATIONS.PROJECT_ID, target.id())
                .where(NOTIFICATIONS.WORK_PACKAGE_ID.in(moving.stream().map(WorkPackagesRecord::getId).toList())).execute();
        ctx.refresh();
        return service.get(ctx, number);
    }

    /* ---------- bulk ---------- */

    public record BulkResult(int count) {}

    /**
     * POST /work-packages/bulk {numbers, action: update|delete|move|copy, changes?, targetProject?} — every item or none. Failures come back
     * as a 422 listing each item ("#12") with its reason. A copy (to targetProject, or the same project) keeps the hierarchy among the
     * chosen items: a chosen parent's copy becomes the parent of its chosen children's copies.
     */
    @Transactional
    public BulkResult bulk(Ctx ctx, Map<String, Object> in) {
        var raw = in.get("numbers") instanceof List<?> l ? l : List.of();
        var numbers = raw.stream().map(o -> o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o))).distinct().toList();
        if (numbers.isEmpty()) throw ApiException.unprocessable("작업을 고르세요.", null);
        if (numbers.size() > 200) throw ApiException.unprocessable("한 번에 200건까지 바꿀 수 있습니다.", null);
        var action = In.str(in, "action");
        @SuppressWarnings("unchecked")
        var changes = in.get("changes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        if ("update".equals(action) && changes.isEmpty()) throw ApiException.unprocessable("바꿀 내용을 고르세요.", null);
        if (!List.of("update", "delete", "move", "copy").contains(action)) throw ApiException.unprocessable("알 수 없는 작업입니다.", action);
        if ("copy".equals(action)) numbers = parentsFirst(numbers);
        var copies = new HashMap<UUID, Long>(); // source id → number of its copy
        var allowed = List.of("statusId", "priorityId", "assigneeId", "responsibleId", "versionId", "categoryId", "startDate", "dueDate");
        for (var k : changes.keySet()) if (!allowed.contains(k)) throw ApiException.unprocessable("한꺼번에 바꿀 수 없는 항목입니다.", k);

        var failures = new ArrayList<ApiException.FieldError>();
        for (var n : numbers) {
            try {
                switch (action) {
                    case "update" -> {
                        var current = service.find(ctx, n).wp();
                        var patch = new HashMap<>(changes);
                        patch.put("lockVersion", current.getLockVersion());
                        service.update(ctx, n, patch);
                    }
                    case "move" -> {
                        // an item moved along with its parent earlier in this request is already there
                        var current = service.find(ctx, n).wp();
                        var target = ctx.findProject(In.str(in, "targetProject") == null ? "" : In.str(in, "targetProject"));
                        if (!current.getProjectId().equals(target.id())) move(ctx, n, Map.of("targetProject", target.id().toString()));
                    }
                    case "copy" -> {
                        var source = service.find(ctx, n);
                        var key = In.str(in, "targetProject");
                        var target = key == null || key.isBlank() ? source.project() : ctx.findProject(key);
                        var w = source.wp();
                        var parentCopy = w.getParentId() == null ? null : copies.get(w.getParentId());
                        var made = copyOne(ctx, w, target, w.getSubject(), parentCopy);
                        copies.put(w.getId(), made.number());
                    }
                    default -> {
                        if (db.fetchExists(WORK_PACKAGES, WORK_PACKAGES.NUMBER.eq(n))) service.remove(ctx, n, "reparent");
                    }
                }
            } catch (ApiException e) {
                failures.add(new ApiException.FieldError("#" + n, reason(e)));
            }
        }
        if (!failures.isEmpty())
            throw new ApiException(422, "아무것도 바꾸지 않았습니다.", failures.size() + "건에서 문제가 있어 전체를 되돌렸습니다.", failures, null);
        return new BulkResult(numbers.size());
    }

    /** The chosen numbers with every chosen parent before its chosen children (the rest in number order). */
    private List<Long> parentsFirst(List<Long> numbers) {
        var rows = db.select(WORK_PACKAGES.ID, WORK_PACKAGES.NUMBER, WORK_PACKAGES.PARENT_ID).from(WORK_PACKAGES).where(WORK_PACKAGES.NUMBER.in(numbers)).fetch();
        var byId = new HashMap<UUID, org.jooq.Record3<UUID, Long, UUID>>();
        rows.forEach(r -> byId.put(r.value1(), r));
        java.util.function.ToIntFunction<org.jooq.Record3<UUID, Long, UUID>> depth = r -> {
            int d = 0;
            for (var p = r.value3(); p != null && byId.containsKey(p) && d < 100; p = byId.get(p).value3()) d++;
            return d;
        };
        var known = rows.stream().sorted(java.util.Comparator.comparingInt(depth).thenComparing(r -> r.value2())).map(r -> r.value2()).toList();
        var out = new ArrayList<>(known);
        numbers.stream().filter(n -> !known.contains(n)).forEach(out::add); // unknown numbers fail in the loop with their reason
        return out;
    }

    private static String reason(ApiException e) {
        if (!e.errors().isEmpty()) return e.errors().get(0).message();
        return e.detail() == null ? e.title() : e.title() + " " + e.detail();
    }
}
