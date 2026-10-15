package com.openpms.itsm;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.JOURNALS;
import static com.openpms.db.Tables.SERVICE_TICKETS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.wp.Assembler;
import com.openpms.wp.Assignees;
import com.openpms.wp.Journals;
import com.openpms.wp.WorkPackageService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The requester's side of the service desk (docs/superpowers/specs/2026-10-14-itsm-design.md §4). Every read and
 * write is narrowed to service_tickets.requester_id = the caller; anything else is the same 404.
 */
@Service
public class PortalService {
    /** A list option: the id travels in customValues, the value is what the requester reads. */
    public record PortalOption(UUID id, String value) {}

    public record PortalField(UUID id, String name, String format, boolean required, boolean multi, List<PortalOption> options, List<UUID> typeIds) {}

    /** user and version values point at people and plans the requester cannot see, so those fields stay off the portal. */
    private static final Set<String> NOT_ON_PORTAL = Set.of("user", "version");

    /** A catalog item the requester can ask for: its form is the item's fields (required = the item's flag). */
    public record PortalItem(UUID id, String name, String descriptionHtml, UUID typeId, List<PortalField> fields) {}

    /** A catalog category with its active items; items without a category sit under a made-up 기타 with no id. */
    public record PortalCategory(UUID id, String name, List<PortalItem> items) {}

    public record PortalDesk(String identifier, String name, String descriptionText, List<Dto.IdName> types, List<PortalField> fields,
                             List<PortalCategory> catalog) {}

    /** An approval step as the requester sees it: who and how far, never comments or skip reasons (a skipped row has no name). */
    public record PortalApproval(int stepNo, String approverName, String decision, OffsetDateTime decidedAt) {}

    public record PortalSummary(long number, String subject, String desk, String type, Dto.StatusRef status, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt, boolean hasNews) {}

    public record PortalEntry(UUID id, String author, boolean fromMe, String notesHtml, String statusFrom, String statusTo, OffsetDateTime at) {}

    public record PortalFile(UUID id, String fileName, long byteSize, OffsetDateTime createdAt) {}

    public record FieldValue(String name, String value) {}

    public record PortalTicket(long number, String subject, String descriptionHtml, String desk, Dto.IdName type, Dto.StatusRef status, boolean closed,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt, int lockVersion, List<Dto.IdName> allowedStatuses,
                               List<FieldValue> fields, List<PortalEntry> activity, List<PortalFile> files, Dto.IdName item,
                               List<PortalApproval> approvals) {}

    private static final String NOT_FOUND = "요청을 찾을 수 없습니다.";

    private final DSLContext db;
    private final WorkPackageService workPackages;
    private final Journals journals;
    private final Assignees assignees;
    private final com.openpms.customfield.CustomFields customFields;
    private final ServiceCatalog catalog;
    private final Approvals approvals;

    public PortalService(DSLContext db, WorkPackageService workPackages, Journals journals, Assignees assignees,
                         com.openpms.customfield.CustomFields customFields, ServiceCatalog catalog, Approvals approvals) {
        this.db = db;
        this.workPackages = workPackages;
        this.journals = journals;
        this.assignees = assignees;
        this.customFields = customFields;
        this.catalog = catalog;
        this.approvals = approvals;
    }

    /* ---------- desks ---------- */

    private List<Snapshot.ProjectRow> desks(Snapshot s) {
        return s.projects().values().stream().sorted(java.util.Comparator.comparing(Snapshot.ProjectRow::name)).filter(p -> p.archivedAt() == null && !p.isTemplate() && p.enabledModules().contains(ItsmIds.MODULE)).toList();
    }

    public List<PortalDesk> desks(Ctx ctx) {
        var s = ctx.s();
        var list = desks(s);
        // one read each for the portal-visible fields and for every desk's catalog, so the cost does not grow with the number of desks
        var visible = portalVisibleFields();
        var catalogs = catalog.catalogs(list.stream().map(Snapshot.ProjectRow::id).toList());
        return list.stream().map(p -> new PortalDesk(p.identifier(), p.name(), com.openpms.common.Html.text(p.descriptionHtml()),
                p.typeIds().stream().filter(ItsmIds.TICKET_TYPES::contains).map(t -> s.type(t).map(x -> new Dto.IdName(x.id(), x.name())).orElseThrow()).toList(),
                fields(s, p, visible), catalog(s, p, catalogs.get(p.id())))).toList();
    }

    /** An item can be asked for while it is active and its type is a ticket type the desk still uses. */
    private static boolean requestable(Snapshot.ProjectRow p, ServiceCatalog.Item item) {
        return item.isActive() && ItsmIds.TICKET_TYPES.contains(item.typeId()) && p.typeIds().contains(item.typeId());
    }

    private List<PortalCategory> catalog(Snapshot s, Snapshot.ProjectRow p, ServiceCatalog.Catalog c) {
        var items = c.items().stream().filter(i -> requestable(p, i)).toList();
        var out = new ArrayList<PortalCategory>();
        var known = new HashSet<UUID>();
        for (var cat : c.categories()) {
            known.add(cat.id());
            var mine = items.stream().filter(i -> cat.id().equals(i.categoryId())).map(i -> portalItem(s, p, i)).toList();
            if (!mine.isEmpty()) out.add(new PortalCategory(cat.id(), cat.name(), mine));
        }
        var loose = items.stream().filter(i -> i.categoryId() == null || !known.contains(i.categoryId())).map(i -> portalItem(s, p, i)).toList();
        if (!loose.isEmpty()) out.add(new PortalCategory(null, "기타", loose));
        return out;
    }

    private PortalItem portalItem(Snapshot s, Snapshot.ProjectRow p, ServiceCatalog.Item item) {
        return new PortalItem(item.id(), item.name(), item.descriptionHtml(), item.typeId(), itemFields(s, p, item));
    }

    /**
     * An item's form: its fields whether or not they are marked for the portal, while they are still on for the desk and
     * the item's type. User and version fields stay off the portal here too (they point at people and plans).
     */
    List<PortalField> itemFields(Snapshot s, Snapshot.ProjectRow p, ServiceCatalog.Item item) {
        var active = new HashMap<UUID, Snapshot.CustomFieldRow>();
        for (var cf : s.activeCustomFields(p.id(), item.typeId())) active.put(cf.id(), cf);
        var out = new ArrayList<PortalField>();
        for (var f : item.fields().stream().sorted(Comparator.comparingInt(ServiceCatalog.ItemField::position)).toList()) {
            var cf = active.get(f.customFieldId());
            if (cf == null || NOT_ON_PORTAL.contains(cf.format())) continue;
            out.add(new PortalField(cf.id(), cf.name(), cf.format(), f.isRequired(), cf.isMultiValue(),
                    cf.options().stream().map(o -> new PortalOption(o.id(), o.value())).toList(), List.of(item.typeId())));
        }
        return out;
    }

    private Set<UUID> portalVisibleFields() {
        return new HashSet<>(db.select(CUSTOM_FIELDS.ID).from(CUSTOM_FIELDS).where(CUSTOM_FIELDS.PORTAL_VISIBLE).fetch(CUSTOM_FIELDS.ID));
    }

    private List<PortalField> fields(Snapshot s, Snapshot.ProjectRow p) {
        return fields(s, p, portalVisibleFields());
    }

    private List<PortalField> fields(Snapshot s, Snapshot.ProjectRow p, Set<UUID> visible) {
        return s.customFields().stream()
                .filter(cf -> visible.contains(cf.id()) && !NOT_ON_PORTAL.contains(cf.format()) && s.customFieldEnabledIn(cf, p.id())
                        && cf.typeIds().stream().anyMatch(ItsmIds.TICKET_TYPES::contains))
                .map(cf -> new PortalField(cf.id(), cf.name(), cf.format(), cf.isRequired(), cf.isMultiValue(),
                        cf.options().stream().map(o -> new PortalOption(o.id(), o.value())).toList(),
                        cf.typeIds().stream().filter(ItsmIds.TICKET_TYPES::contains).toList()))
                .toList();
    }

    private Snapshot.ProjectRow desk(Snapshot s, String identifier) {
        return desks(s).stream().filter(p -> p.identifier().equals(identifier)).findFirst().orElseThrow(() -> ApiException.notFound("서비스 데스크를 찾을 수 없습니다."));
    }

    /* ---------- submit ---------- */

    @Transactional
    public PortalTicket submit(Ctx ctx, String identifier, Map<String, Object> in) {
        var s = ctx.s();
        var p = desk(s, identifier);
        // a catalog item decides the type, the form, the priority, the group and the approvals
        var itemId = In.uuid(in, "catalogItemId", "catalogItem");
        var item = itemId == null ? null : catalog.item(p.id(), itemId).filter(i -> requestable(p, i))
                .orElseThrow(() -> ApiException.field("catalogItem", "요청할 수 없는 항목입니다."));
        var typeId = item != null ? item.typeId() : In.uuid(in, "typeId", "type");
        if (typeId == null || !ItsmIds.TICKET_TYPES.contains(typeId) || !p.typeIds().contains(typeId)) throw ApiException.field("type", "장애 신고나 서비스 요청을 고르세요.");
        // only what the portal offers goes on: no assignee, status, priority, dates or hidden fields
        var form = item != null ? itemFields(s, p, item) : fields(s, p).stream().filter(f -> f.typeIds().contains(typeId)).toList();
        var allowedFields = form.stream().map(f -> f.id().toString()).collect(java.util.stream.Collectors.toSet());
        var custom = new HashMap<String, Object>();
        if (in.get("customValues") instanceof Map<?, ?> m) m.forEach((k, v) -> { if (allowedFields.contains(String.valueOf(k))) custom.put(String.valueOf(k), v); });
        // the item's form says what is required, whatever the fields' own flags (those are waived for an item request)
        if (item != null) {
            for (var fld : form) {
                if (fld.required() && blank(custom.get(fld.id().toString()))) throw ApiException.field("cf:" + fld.id(), fld.name() + ": 값을 입력하세요.");
            }
        }
        var safe = new HashMap<String, Object>();
        safe.put("typeId", typeId.toString());
        safe.put("subject", In.str(in, "subject"));
        safe.put("descriptionHtml", In.str(in, "descriptionHtml"));
        if (In.has(in, "impact")) safe.put("impact", in.get("impact"));
        if (In.has(in, "urgency")) safe.put("urgency", in.get("urgency"));
        safe.put("customValues", custom);
        if (item == null) {
            var w = workPackages.createForPortal(ctx, p, safe);
            return ticket(ctx, new WorkPackageService.Found(w, p), false);
        }
        var planned = approvals.planFor(item.id(), ctx.userId());
        // 승인 대기 is an SLA pause: ServiceDesk.created starts the request paused
        safe.put("statusId", (Approvals.needsApproval(planned) ? ItsmIds.STATUS_AWAITING_APPROVAL : ItsmIds.STATUS_RECEIVED).toString());
        if (item.defaultPriorityId() != null && s.priority(item.defaultPriorityId()).isPresent()) safe.put("priorityId", item.defaultPriorityId().toString());
        var waived = new HashSet<UUID>();
        for (var cf : s.customFields()) waived.add(cf.id());
        var w = workPackages.createForPortal(ctx, p, safe, waived);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.CATALOG_ITEM_ID, item.id()).set(SERVICE_TICKETS.ASSIGNMENT_GROUP_ID, item.assignmentGroupId())
                .where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(w.getId())).execute();
        approvals.store(ctx, w, planned);
        return ticket(ctx, new WorkPackageService.Found(w, p), false);
    }

    private static boolean blank(Object v) {
        return v == null || (v instanceof String str && str.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
    }

    /* ---------- my tickets ---------- */

    public WorkPackageService.Found find(Ctx ctx, long number) {
        var w = db.select(WORK_PACKAGES.fields()).from(WORK_PACKAGES).join(SERVICE_TICKETS).on(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(WORK_PACKAGES.ID))
                .where(WORK_PACKAGES.NUMBER.eq(number), SERVICE_TICKETS.REQUESTER_ID.eq(ctx.userId())).fetchOneInto(WORK_PACKAGES);
        if (w == null) throw ApiException.notFound(NOT_FOUND);
        var p = ctx.s().project(w.getProjectId()).filter(x -> x.enabledModules().contains(ItsmIds.MODULE) && x.archivedAt() == null).orElse(null);
        if (p == null) throw ApiException.notFound(NOT_FOUND);
        return new WorkPackageService.Found(w, p);
    }

    /** Public news: a comment or a status change by someone else, after the requester last looked. */
    private org.jooq.Condition news(UUID requesterId) {
        return DSL.exists(DSL.selectOne().from(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID), JOURNALS.IS_INTERNAL.isFalse(),
                JOURNALS.USER_ID.ne(requesterId), JOURNALS.UPDATED_AT.gt(DSL.coalesce(SERVICE_TICKETS.REQUESTER_SEEN_AT, SERVICE_TICKETS.CREATED_AT)),
                JOURNALS.NOTES_TEXT.ne("").or(DSL.condition("jsonb_exists({0}, 'statusId')", JOURNALS.CHANGES))));
    }

    public List<PortalSummary> list(Ctx ctx, String state) {
        var s = ctx.s();
        var closed = s.statuses().stream().filter(Snapshot.StatusRow::isClosed).map(Snapshot.StatusRow::id).toList();
        var where = SERVICE_TICKETS.REQUESTER_ID.eq(ctx.userId());
        // 해결 waits for the requester's confirmation, so it stays among the open ones
        if ("open".equals(state)) where = where.and(WORK_PACKAGES.STATUS_ID.notIn(closed).or(WORK_PACKAGES.STATUS_ID.eq(ItsmIds.STATUS_RESOLVED)));
        if ("closed".equals(state)) where = where.and(WORK_PACKAGES.STATUS_ID.in(closed).and(WORK_PACKAGES.STATUS_ID.ne(ItsmIds.STATUS_RESOLVED)));
        var hasNews = DSL.field(news(ctx.userId())).as("has_news");
        return db.select(WORK_PACKAGES.NUMBER, WORK_PACKAGES.SUBJECT, WORK_PACKAGES.PROJECT_ID, WORK_PACKAGES.TYPE_ID, WORK_PACKAGES.STATUS_ID,
                        WORK_PACKAGES.CREATED_AT, WORK_PACKAGES.UPDATED_AT, hasNews)
                .from(SERVICE_TICKETS).join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(SERVICE_TICKETS.WORK_PACKAGE_ID))
                .where(where).orderBy(WORK_PACKAGES.UPDATED_AT.desc()).limit(200)
                .fetch().stream().filter(r -> live(s, r.value3())).map(r -> new PortalSummary(r.value1(), r.value2(), s.project(r.value3()).map(Snapshot.ProjectRow::name).orElse(""),
                        s.type(r.value4()).map(Snapshot.TypeRow::name).orElse(""), Refs.status(s, r.value5()), r.value6(), r.value7(),
                        Boolean.TRUE.equals(r.value8()))).toList();
    }

    private static boolean live(Snapshot s, UUID projectId) {
        return s.project(projectId).filter(x -> x.enabledModules().contains(ItsmIds.MODULE) && x.archivedAt() == null).isPresent();
    }

    @Transactional
    public PortalTicket open(Ctx ctx, long number) {
        var f = find(ctx, number);
        db.update(SERVICE_TICKETS).set(SERVICE_TICKETS.REQUESTER_SEEN_AT, OffsetDateTime.now()).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(f.wp().getId())).execute();
        return ticket(ctx, f, true);
    }

    PortalTicket ticket(Ctx ctx, WorkPackageService.Found f, boolean withActivity) {
        var s = ctx.s();
        var w = f.wp();
        var status = s.status(w.getStatusId()).orElseThrow();
        var allowed = Assembler.allowedFor(s, Set.of(ItsmIds.ROLE_REQUESTER), ctx.userId(), w.getTypeId(), w.getStatusId(), ctx.userId(),
                Assignees.ids(assignees.of(w.getId()))).stream().map(id -> s.status(id).map(x -> new Dto.IdName(x.id(), x.name())).orElseThrow()).toList();
        var itemId = db.select(SERVICE_TICKETS.CATALOG_ITEM_ID).from(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(w.getId()))
                .fetchOne(SERVICE_TICKETS.CATALOG_ITEM_ID);
        var item = itemId == null ? null : catalog.item(f.project().id(), itemId).orElse(null);
        var visible = fields(s, f.project()).stream().map(PortalField::id).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        // the item's form is shown with the request even where the fields are not marked for the portal
        if (item != null) itemFields(s, f.project(), item).forEach(x -> visible.add(x.id()));
        var values = customFields.values(s, w).entrySet().stream()
                .filter(e -> visible.contains(UUID.fromString(e.getKey())))
                .map(e -> new FieldValue(s.customField(UUID.fromString(e.getKey())).map(Snapshot.CustomFieldRow::name).orElse(""), display(e.getValue())))
                .toList();
        var activity = withActivity ? activity(ctx, w.getId()) : List.<PortalEntry>of();
        var files = db.selectFrom(ATTACHMENTS).where(ATTACHMENTS.WORK_PACKAGE_ID.eq(w.getId())).orderBy(ATTACHMENTS.CREATED_AT)
                .fetch(a -> new PortalFile(a.getId(), a.getFileName(), a.getByteSize(), a.getCreatedAt()));
        return new PortalTicket(w.getNumber(), w.getSubject(), w.getDescriptionHtml(), f.project().name(),
                s.type(w.getTypeId()).map(t -> new Dto.IdName(t.id(), t.name())).orElseThrow(), Refs.status(s, w.getStatusId()),
                status.isClosed(), w.getCreatedAt(), w.getUpdatedAt(), w.getLockVersion(), allowed, values, activity, files,
                item == null ? null : new Dto.IdName(item.id(), item.name()), portalApprovals(s, w.getId()));
    }

    List<PortalApproval> portalApprovals(Snapshot s, UUID wpId) {
        return approvals.of(wpId).stream().map(a -> new PortalApproval(a.stepNo(),
                "skipped".equals(a.decision()) || a.approverId() == null ? null : Refs.user(s, a.approverId()).name(), a.decision(), a.decidedAt())).toList();
    }

    private List<PortalEntry> activity(Ctx ctx, UUID wpId) {
        var s = ctx.s();
        var out = new ArrayList<PortalEntry>();
        for (var j : db.selectFrom(JOURNALS).where(JOURNALS.WORK_PACKAGE_ID.eq(wpId), JOURNALS.IS_INTERNAL.isFalse()).orderBy(JOURNALS.CREATED_AT).fetch()) {
            var changes = journals.changesOf(j);
            var st = changes.get("statusId");
            String from = null, to = null;
            if (st != null) {
                from = st.get(0) == null ? null : s.status(UUID.fromString(String.valueOf(st.get(0)))).map(Snapshot.StatusRow::name).orElse(null);
                to = st.get(1) == null ? null : s.status(UUID.fromString(String.valueOf(st.get(1)))).map(Snapshot.StatusRow::name).orElse(null);
            }
            if (j.getNotesHtml().isEmpty() && to == null) continue;
            out.add(new PortalEntry(j.getId(), Refs.user(s, j.getUserId()).name(), j.getUserId().equals(ctx.userId()), j.getNotesHtml(), from, to,
                    j.getCreatedAt()));
        }
        return out;
    }

    static String display(Object v) {
        if (v instanceof Dto.CustomValue cv) return String.valueOf(cv.text());
        return String.valueOf(v);
    }
}
