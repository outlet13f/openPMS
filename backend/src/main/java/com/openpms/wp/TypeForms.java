package com.openpms.wp;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.customfield.CustomFields;
import com.openpms.core.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/**
 * A type's form (S28 양식 구성): attribute groups in order, and hidden attributes. Stored in types.attribute_groups
 * as [{"name", "attributes"}…, {"hidden": […]}]; an empty array means DEFAULT. Custom fields not placed in any
 * group (and not hidden) show in an automatic last group. frontend/src/features/work-packages/type-form.ts mirrors it.
 */
public final class TypeForms {
    private TypeForms() {}

    public static final List<String> BUILT_IN = List.of("assignee", "responsible", "dates", "duration", "work", "remainingWork", "doneRatio", "type",
            "priority", "version", "category", "parent");

    public static final Dto.TypeForm DEFAULT = new Dto.TypeForm(List.of(
            new Dto.AttributeGroup("사람", List.of("assignee", "responsible")),
            new Dto.AttributeGroup("일정", List.of("dates", "duration")),
            new Dto.AttributeGroup("진행률", List.of("work", "remainingWork", "doneRatio")),
            new Dto.AttributeGroup("세부 정보", List.of("type", "priority", "version", "category", "parent"))), List.of());

    /** Stored value → the form, or null when it is the default. */
    @SuppressWarnings("unchecked")
    public static Dto.TypeForm read(JsonMapper json, JSONB stored) {
        if (stored == null) return null;
        var list = json.readValue(stored.data(), List.class);
        if (list == null || list.isEmpty()) return null;
        var groups = new ArrayList<Dto.AttributeGroup>();
        var hidden = new ArrayList<String>();
        for (var o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            if (m.containsKey("hidden")) ((Collection<Object>) m.get("hidden")).forEach(x -> hidden.add(String.valueOf(x)));
            else groups.add(new Dto.AttributeGroup(String.valueOf(m.get("name")), ((Collection<Object>) m.get("attributes")).stream().map(String::valueOf).toList()));
        }
        return new Dto.TypeForm(groups, hidden);
    }

    public static Dto.TypeForm resolve(JsonMapper json, JSONB stored) {
        var f = read(json, stored);
        return f == null ? DEFAULT : f;
    }

    public static JSONB write(JsonMapper json, Dto.TypeForm form) {
        if (form == null) return JSONB.valueOf("[]");
        var out = new ArrayList<Object>();
        for (var g : form.groups()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", g.name());
            m.put("attributes", g.attributes());
            out.add(m);
        }
        out.add(Map.of("hidden", form.hidden()));
        return JSONB.valueOf(json.writeValueAsString(out));
    }

    /** A request's {"groups": [{name, attributes}], "hidden": […]} → a checked form. */
    @SuppressWarnings("unchecked")
    public static Dto.TypeForm parse(Snapshot s, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw ApiException.field("form", "양식 구성을 확인하세요.");
        var groupsRaw = m.get("groups") instanceof Collection<?> c ? c : List.of();
        var hiddenRaw = m.get("hidden") instanceof Collection<?> c ? c : List.of();
        if (groupsRaw.size() > 20) throw ApiException.field("form", "그룹은 20개까지 만들 수 있습니다.");
        var seen = new HashSet<String>();
        var names = new HashSet<String>();
        java.util.function.Consumer<String> known = a -> {
            var cf = CustomFields.idOf(a);
            if (!BUILT_IN.contains(a) && (cf == null || s.customField(cf).isEmpty())) throw ApiException.field("form", "알 수 없는 항목입니다: " + a);
            if (!seen.add(a)) throw ApiException.field("form", "같은 항목이 두 번 있습니다.");
        };
        var groups = new ArrayList<Dto.AttributeGroup>();
        for (var g : groupsRaw) {
            if (!(g instanceof Map<?, ?> gm)) throw ApiException.field("form", "양식 구성을 확인하세요.");
            var name = gm.get("name") == null ? "" : String.valueOf(gm.get("name")).trim();
            if (name.isEmpty()) throw ApiException.field("form", "그룹 이름을 입력하세요.");
            if (name.length() > 60) throw ApiException.field("form", "그룹 이름은 60자까지 입력할 수 있습니다.");
            if (!names.add(name)) throw ApiException.field("form", "같은 이름의 그룹이 있습니다: " + name);
            var attrs = gm.get("attributes") instanceof Collection<?> ac ? ac.stream().map(String::valueOf).toList() : List.<String>of();
            attrs.forEach(known);
            groups.add(new Dto.AttributeGroup(name, attrs));
        }
        var hidden = ((Collection<Object>) hiddenRaw).stream().map(String::valueOf).toList();
        hidden.forEach(known);
        return new Dto.TypeForm(groups, hidden);
    }
}
