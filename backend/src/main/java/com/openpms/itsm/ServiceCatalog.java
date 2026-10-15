package com.openpms.itsm;

import static com.openpms.db.Tables.CATALOG_CATEGORIES;
import static com.openpms.db.Tables.CATALOG_ITEMS;
import static com.openpms.db.Tables.CATALOG_ITEM_APPROVAL_STEPS;
import static com.openpms.db.Tables.CATALOG_ITEM_FIELDS;
import static com.openpms.db.Tables.PROJECT_CUSTOM_FIELDS;

import com.openpms.common.ApiException;
import com.openpms.common.Html;
import com.openpms.common.In;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.CatalogItemsRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/** A service desk's catalog: categories, items, each item's request form (custom fields) and approval steps. */
@Component
public class ServiceCatalog {
    public record Category(UUID id, String name, int position) {}

    public record ItemField(UUID customFieldId, int position, boolean isRequired) {}

    public record Item(UUID id, UUID categoryId, String name, String descriptionHtml, UUID typeId, UUID defaultPriorityId, UUID assignmentGroupId,
                       boolean isActive, int position, List<ItemField> fields, List<Approvals.StepRule> steps) {}

    public record Catalog(List<Category> categories, List<Item> items) {}

    private static final List<String> KINDS = List.of(Approvals.MANAGER, Approvals.GROUP_LEAD);

    private final DSLContext db;

    public ServiceCatalog(DSLContext db) {
        this.db = db;
    }

    /* ---------- reading ---------- */

    public Catalog catalog(UUID projectId) {
        return catalogs(List.of(projectId)).get(projectId);
    }

    /** The catalogs of several projects with one query each for categories, items, item fields and steps (every id gets an entry). */
    public Map<UUID, Catalog> catalogs(java.util.Collection<UUID> projectIds) {
        var out = new java.util.HashMap<UUID, Catalog>();
        if (projectIds.isEmpty()) return out;
        var categories = db.selectFrom(CATALOG_CATEGORIES).where(CATALOG_CATEGORIES.PROJECT_ID.in(projectIds))
                .orderBy(CATALOG_CATEGORIES.POSITION, CATALOG_CATEGORIES.NAME).fetch().intoGroups(CATALOG_CATEGORIES.PROJECT_ID);
        var rows = db.selectFrom(CATALOG_ITEMS).where(CATALOG_ITEMS.PROJECT_ID.in(projectIds)).orderBy(CATALOG_ITEMS.POSITION, CATALOG_ITEMS.NAME).fetch();
        var ids = rows.map(CatalogItemsRecord::getId);
        var fields = db.selectFrom(CATALOG_ITEM_FIELDS).where(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID.in(ids)).orderBy(CATALOG_ITEM_FIELDS.POSITION).fetch()
                .intoGroups(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID);
        var steps = db.selectFrom(CATALOG_ITEM_APPROVAL_STEPS).where(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID.in(ids))
                .orderBy(CATALOG_ITEM_APPROVAL_STEPS.STEP_NO).fetch().intoGroups(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID);
        var items = new java.util.HashMap<UUID, List<Item>>();
        for (var r : rows) {
            var f = fields.get(r.getId());
            var st = steps.get(r.getId());
            items.computeIfAbsent(r.getProjectId(), k -> new ArrayList<>()).add(toItem(r,
                    f == null ? List.of() : f.stream().map(x -> new ItemField(x.getCustomFieldId(), x.getPosition(), x.getIsRequired())).toList(),
                    st == null ? List.of() : sorted(st.stream().map(x -> new Approvals.StepRule(x.getStepNo(), x.getApproverKind())).toList())));
        }
        for (var id : projectIds) {
            var cats = java.util.Optional.ofNullable(categories.get(id)).map(java.util.Collection::stream).orElseGet(java.util.stream.Stream::empty).map(r -> new Category(r.getId(), r.getName(), r.getPosition())).toList();
            out.put(id, new Catalog(cats, items.getOrDefault(id, List.of())));
        }
        return out;
    }

    public Optional<Item> item(UUID projectId, UUID itemId) {
        var r = db.selectFrom(CATALOG_ITEMS).where(CATALOG_ITEMS.ID.eq(itemId), CATALOG_ITEMS.PROJECT_ID.eq(projectId)).fetchOne();
        return Optional.ofNullable(r).map(this::load);
    }

    private Item load(CatalogItemsRecord r) {
        var fields = db.selectFrom(CATALOG_ITEM_FIELDS).where(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID.eq(r.getId())).orderBy(CATALOG_ITEM_FIELDS.POSITION)
                .fetch(f -> new ItemField(f.getCustomFieldId(), f.getPosition(), f.getIsRequired()));
        var steps = db.selectFrom(CATALOG_ITEM_APPROVAL_STEPS).where(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID.eq(r.getId()))
                .fetch(s -> new Approvals.StepRule(s.getStepNo(), s.getApproverKind()));
        return toItem(r, fields, sorted(steps));
    }

    private static List<Approvals.StepRule> sorted(List<Approvals.StepRule> steps) {
        return steps.stream().sorted(java.util.Comparator.comparingInt(Approvals.StepRule::stepNo).thenComparingInt(s -> KINDS.indexOf(s.kind()))).toList();
    }

    private static Item toItem(CatalogItemsRecord r, List<ItemField> fields, List<Approvals.StepRule> steps) {
        return new Item(r.getId(), r.getCategoryId(), r.getName(), r.getDescriptionHtml(), r.getTypeId(), r.getDefaultPriorityId(), r.getAssignmentGroupId(),
                r.getIsActive(), r.getPosition(), fields, steps);
    }

    /* ---------- categories ---------- */

    public Category saveCategory(UUID projectId, UUID id, Map<String, Object> in) {
        var r = id == null ? db.newRecord(CATALOG_CATEGORIES) : db.selectFrom(CATALOG_CATEGORIES)
                .where(CATALOG_CATEGORIES.ID.eq(id), CATALOG_CATEGORIES.PROJECT_ID.eq(projectId)).fetchOne();
        if (r == null) throw ApiException.notFound("분류를 찾을 수 없습니다.");
        if (id == null) {
            r.setProjectId(projectId);
            r.setPosition(0);
        }
        if (id == null || In.has(in, "name")) {
            var name = name(in);
            if (db.fetchExists(CATALOG_CATEGORIES, CATALOG_CATEGORIES.PROJECT_ID.eq(projectId), CATALOG_CATEGORIES.NAME.eq(name),
                    id == null ? DSL.trueCondition() : CATALOG_CATEGORIES.ID.ne(id)))
                throw ApiException.field("name", "같은 이름의 분류가 있습니다.");
            r.setName(name);
        }
        if (In.has(in, "position")) r.setPosition(position(in));
        r.store();
        return new Category(r.getId(), r.getName(), r.getPosition());
    }

    public void deleteCategory(UUID projectId, UUID id) {
        if (db.deleteFrom(CATALOG_CATEGORIES).where(CATALOG_CATEGORIES.ID.eq(id), CATALOG_CATEGORIES.PROJECT_ID.eq(projectId)).execute() == 0)
            throw ApiException.notFound("분류를 찾을 수 없습니다.");
    }

    /* ---------- items ---------- */

    /** Creates (id null) or updates an item. Only the keys sent change; sending fields or steps replaces the whole list. */
    public Item saveItem(Snapshot s, Snapshot.ProjectRow p, UUID id, Map<String, Object> in) {
        var r = id == null ? db.newRecord(CATALOG_ITEMS) : db.selectFrom(CATALOG_ITEMS)
                .where(CATALOG_ITEMS.ID.eq(id), CATALOG_ITEMS.PROJECT_ID.eq(p.id())).fetchOne();
        if (r == null) throw ApiException.notFound("항목을 찾을 수 없습니다.");
        if (id == null) {
            r.setProjectId(p.id());
            r.setTypeId(ItsmIds.TYPE_SERVICE_REQUEST);
            r.setPosition(0);
            r.setIsActive(true);
            r.setDescriptionHtml("");
            r.setDescriptionText("");
        }
        if (id == null || In.has(in, "name")) {
            var name = name(in);
            if (db.fetchExists(CATALOG_ITEMS, CATALOG_ITEMS.PROJECT_ID.eq(p.id()), CATALOG_ITEMS.NAME.eq(name), id == null ? DSL.trueCondition() : CATALOG_ITEMS.ID.ne(id)))
                throw ApiException.field("name", "같은 이름의 항목이 있습니다.");
            r.setName(name);
        }
        if (In.has(in, "typeId")) {
            var typeId = In.uuid(in, "typeId", "typeId");
            if (typeId == null || !ItsmIds.TICKET_TYPES.contains(typeId) || !p.typeIds().contains(typeId))
                throw ApiException.field("typeId", "이 프로젝트에서 켠 장애·서비스 요청 유형만 고를 수 있습니다.");
            r.setTypeId(typeId);
        } else if (id == null && !p.typeIds().contains(r.getTypeId())) {
            throw ApiException.field("typeId", "이 프로젝트에서 서비스 요청 유형이 꺼져 있습니다.");
        }
        if (In.has(in, "categoryId")) {
            var categoryId = In.uuid(in, "categoryId", "categoryId");
            if (categoryId != null && !db.fetchExists(CATALOG_CATEGORIES, CATALOG_CATEGORIES.ID.eq(categoryId), CATALOG_CATEGORIES.PROJECT_ID.eq(p.id())))
                throw ApiException.field("categoryId", "이 프로젝트의 분류가 아닙니다.");
            r.setCategoryId(categoryId);
        }
        if (In.has(in, "defaultPriorityId")) {
            var priorityId = In.uuid(in, "defaultPriorityId", "defaultPriorityId");
            if (priorityId != null && s.priority(priorityId).isEmpty()) throw ApiException.field("defaultPriorityId", "알 수 없는 우선순위입니다.");
            r.setDefaultPriorityId(priorityId);
        }
        if (In.has(in, "assignmentGroupId")) {
            var groupId = In.uuid(in, "assignmentGroupId", "assignmentGroupId");
            if (groupId != null && !s.groups().containsKey(groupId)) throw ApiException.field("assignmentGroupId", "알 수 없는 그룹입니다.");
            r.setAssignmentGroupId(groupId);
        }
        if (In.has(in, "descriptionHtml")) {
            var html = Html.sanitize(In.str(in, "descriptionHtml") == null ? "" : In.str(in, "descriptionHtml"));
            r.setDescriptionHtml(html);
            r.setDescriptionText(Html.text(html));
        }
        if (In.has(in, "isActive")) r.setIsActive(In.bool(in, "isActive") == null ? Boolean.TRUE : In.bool(in, "isActive"));
        if (In.has(in, "position")) r.setPosition(position(in));

        List<ItemField> fields = null;
        if (In.has(in, "fields")) fields = fields(s, p, r.getTypeId(), in.get("fields"));
        else if (id != null && In.has(in, "typeId")) {
            // the type changed: the form that is kept must still fit it
            var kept = load(r).fields().stream().map(f -> Map.<String, Object>of("customFieldId", f.customFieldId().toString())).toList();
            try {
                fields(s, p, r.getTypeId(), kept);
            } catch (ApiException e) {
                throw ApiException.field("typeId", "항목 양식의 필드가 새 유형에 맞지 않습니다. 양식을 먼저 고치세요.");
            }
        }
        List<Approvals.StepRule> steps = In.has(in, "steps") ? steps(in.get("steps")) : null;

        r.store();
        if (fields != null) {
            db.deleteFrom(CATALOG_ITEM_FIELDS).where(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID.eq(r.getId())).execute();
            for (var f : fields)
                db.insertInto(CATALOG_ITEM_FIELDS).set(CATALOG_ITEM_FIELDS.CATALOG_ITEM_ID, r.getId()).set(CATALOG_ITEM_FIELDS.CUSTOM_FIELD_ID, f.customFieldId())
                        .set(CATALOG_ITEM_FIELDS.POSITION, f.position()).set(CATALOG_ITEM_FIELDS.IS_REQUIRED, f.isRequired()).execute();
        }
        if (steps != null) {
            db.deleteFrom(CATALOG_ITEM_APPROVAL_STEPS).where(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID.eq(r.getId())).execute();
            for (var st : steps)
                db.insertInto(CATALOG_ITEM_APPROVAL_STEPS).set(CATALOG_ITEM_APPROVAL_STEPS.CATALOG_ITEM_ID, r.getId())
                        .set(CATALOG_ITEM_APPROVAL_STEPS.STEP_NO, (short) st.stepNo()).set(CATALOG_ITEM_APPROVAL_STEPS.APPROVER_KIND, st.kind()).execute();
        }
        return load(db.selectFrom(CATALOG_ITEMS).where(CATALOG_ITEMS.ID.eq(r.getId())).fetchOne());
    }

    public void deleteItem(UUID projectId, UUID id) {
        if (db.deleteFrom(CATALOG_ITEMS).where(CATALOG_ITEMS.ID.eq(id), CATALOG_ITEMS.PROJECT_ID.eq(projectId)).execute() == 0)
            throw ApiException.notFound("항목을 찾을 수 없습니다.");
    }

    /** A form field must be a work package custom field that is on for this project and for the item's type. */
    private List<ItemField> fields(Snapshot s, Snapshot.ProjectRow p, UUID typeId, Object raw) {
        var list = list(raw, "fields");
        var enabled = new HashSet<>(db.select(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID).from(PROJECT_CUSTOM_FIELDS).where(PROJECT_CUSTOM_FIELDS.PROJECT_ID.eq(p.id()))
                .fetch(PROJECT_CUSTOM_FIELDS.CUSTOM_FIELD_ID));
        var seen = new HashSet<UUID>();
        var out = new ArrayList<ItemField>();
        for (int i = 0; i < list.size(); i++) {
            var at = "fields[" + i + "]";
            var in = list.get(i);
            var cfId = In.uuid(in, "customFieldId", at + ".customFieldId");
            var cf = cfId == null ? Optional.<Snapshot.CustomFieldRow>empty() : s.customField(cfId);
            if (cf.isEmpty()) throw ApiException.field(at + ".customFieldId", "알 수 없는 사용자 정의 필드입니다.");
            if (!cf.get().isForAll() && !enabled.contains(cfId)) throw ApiException.field(at + ".customFieldId", "이 프로젝트에서 켠 필드만 고를 수 있습니다.");
            if (!cf.get().typeIds().contains(typeId)) throw ApiException.field(at + ".customFieldId", "이 항목의 유형에 쓰는 필드만 고를 수 있습니다.");
            if (!seen.add(cfId)) throw ApiException.field(at + ".customFieldId", "같은 필드가 두 번 있습니다.");
            var position = In.has(in, "position") ? In.integer(in, "position", at + ".position") : Integer.valueOf(i);
            out.add(new ItemField(cfId, position == null ? i : position, Boolean.TRUE.equals(In.bool(in, "isRequired"))));
        }
        return out;
    }

    private static List<Approvals.StepRule> steps(Object raw) {
        var list = list(raw, "steps");
        var seen = new HashSet<String>();
        var out = new ArrayList<Approvals.StepRule>();
        for (int i = 0; i < list.size(); i++) {
            var at = "steps[" + i + "]";
            var in = list.get(i);
            var stepNo = In.integer(in, "stepNo", at + ".stepNo");
            if (stepNo == null || stepNo < 1 || stepNo > 9) throw ApiException.field(at + ".stepNo", "단계는 1~9입니다.");
            var kind = In.str(in, "kind");
            if (kind == null || !KINDS.contains(kind)) throw ApiException.field(at + ".kind", "승인자 종류는 요청자의 관리자 또는 그룹 리더입니다.");
            if (!seen.add(stepNo + "/" + kind)) throw ApiException.field(at + ".kind", "같은 단계에 같은 승인자 종류가 두 번 있습니다.");
            out.add(new Approvals.StepRule(stepNo, kind));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object raw, String field) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l) || !l.stream().allMatch(x -> x instanceof Map)) throw ApiException.field(field, "목록이어야 합니다.");
        return (List<Map<String, Object>>) l;
    }

    private static String name(Map<String, Object> in) {
        var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (name.isEmpty() || name.length() > 100) throw ApiException.field("name", "이름은 1~100자로 입력하세요.");
        return name;
    }

    private static int position(Map<String, Object> in) {
        var v = In.integer(in, "position", "position");
        return v == null ? 0 : v;
    }
}
