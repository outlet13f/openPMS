package com.openpms.customfield;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Dates;
import com.openpms.core.Snapshot;
import com.openpms.core.Snapshot.CustomFieldRow;
import com.openpms.db.tables.records.WorkPackagesRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Work package custom fields (S34 정의, S25 프로젝트 활성화): which fields an item has, how values are checked and
 * shown. Values live in work_packages.custom_values as {"<field id>": value}; list and user values are option and
 * user ids. frontend/src/api/fake/custom-fields.ts holds the same rules and messages.
 */
@Component
public class CustomFields {
    public static final List<String> FORMATS = List.of("string", "text", "integer", "float", "list", "date", "bool", "user", "link");
    private static final Pattern LINK = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final JsonMapper json;

    public CustomFields(JsonMapper json) {
        this.json = json;
    }

    public static String key(UUID id) {
        return "cf:" + id;
    }

    /** "cf:<uuid>" → the id, or null for anything else. */
    public static UUID idOf(String key) {
        if (key == null || !key.startsWith("cf:")) return null;
        try {
            return UUID.fromString(key.substring(3));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /* ---------- DTOs ---------- */

    public Dto.CustomField dto(CustomFieldRow f) {
        return new Dto.CustomField(f.id(), f.name(), f.format(), f.isRequired(), f.isForAll(), f.isFilterable(), f.isMultiValue(), f.defaultValue(),
                f.minLength(), f.maxLength(), f.regexp(), f.position(), f.options().stream().map(o -> new Dto.CustomFieldOption(o.id(), o.value())).toList(),
                f.typeIds());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> read(JSONB values) {
        if (values == null) return new LinkedHashMap<>();
        var m = json.readValue(values.data(), Map.class);
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) m);
    }

    public JSONB write(Map<String, Object> values) {
        return JSONB.valueOf(json.writeValueAsString(values));
    }

    private static boolean empty(Object v) {
        return v == null || "".equals(v) || (v instanceof Collection<?> c && c.isEmpty());
    }

    private static List<String> ids(Object v) {
        if (v == null) return List.of();
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(v));
    }

    private static String number(Object v) {
        return new BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
    }

    /** Display form: option and user names, formatted dates, 예/아니요. */
    public String text(Snapshot s, CustomFieldRow f, Object v) {
        if (empty(v)) return "";
        return switch (f.format()) {
            case "list" -> String.join(", ", ids(v).stream()
                    .map(id -> f.options().stream().filter(o -> o.id().toString().equals(id)).map(Snapshot.OptionRow::value).findFirst().orElse(""))
                    .filter(x -> !x.isEmpty()).toList());
            case "user" -> String.join(", ", ids(v).stream()
                    .map(id -> {
                        try {
                            return s.user(UUID.fromString(id)).map(Snapshot.UserRow::displayName).orElse("");
                        } catch (IllegalArgumentException e) {
                            return "";
                        }
                    })
                    .filter(x -> !x.isEmpty()).toList());
            case "date" -> {
                try {
                    yield Dates.format(LocalDate.parse(String.valueOf(v)));
                } catch (RuntimeException e) {
                    yield String.valueOf(v);
                }
            }
            case "bool" -> Boolean.TRUE.equals(v) ? "예" : "아니요";
            case "integer", "float" -> number(v);
            default -> String.valueOf(v);
        };
    }

    /** Every field the item has, in order; empty ones with value null. */
    public Map<String, Dto.CustomValue> values(Snapshot s, WorkPackagesRecord w) {
        var stored = read(w.getCustomValues());
        var out = new LinkedHashMap<String, Dto.CustomValue>();
        for (var f : s.activeCustomFields(w.getProjectId(), w.getTypeId())) {
            var v = stored.get(f.id().toString());
            out.put(f.id().toString(), new Dto.CustomValue(v, text(s, f, v)));
        }
        return out;
    }

    /* ---------- checking ---------- */

    /**
     * A client's value → as stored, or a 422 on "cf:<id>". projectId is null when checking a default value in
     * administration (people and required are not checked then).
     */
    public Object check(Snapshot s, CustomFieldRow f, Object raw, UUID projectId) {
        java.util.function.Function<String, ApiException> err = m -> ApiException.field(key(f.id()), f.name() + ": " + m);
        if (empty(raw)) {
            if (f.isRequired() && projectId != null) throw err.apply("값을 입력하세요.");
            return null;
        }
        switch (f.format()) {
            case "string", "text" -> {
                if (!(raw instanceof String str)) throw err.apply("글자를 입력하세요.");
                var v = "string".equals(f.format()) ? str.trim() : str;
                int max = f.maxLength() != null ? f.maxLength() : ("string".equals(f.format()) ? 255 : 10000);
                if (f.minLength() != null && v.length() < f.minLength()) throw err.apply(f.minLength() + "자 이상 입력하세요.");
                if (v.length() > max) throw err.apply(max + "자까지 입력할 수 있습니다.");
                if (f.regexp() != null && !f.regexp().isBlank()) {
                    try {
                        if (!Pattern.compile(f.regexp()).matcher(v).find()) throw err.apply("형식이 맞지 않습니다.");
                    } catch (PatternSyntaxException e) {
                        throw err.apply("형식이 맞지 않습니다.");
                    }
                }
                return v;
            }
            case "integer", "float" -> {
                BigDecimal n;
                try {
                    n = raw instanceof Number num ? new BigDecimal(num.toString()) : new BigDecimal(String.valueOf(raw).trim());
                } catch (NumberFormatException e) {
                    throw err.apply("숫자를 입력하세요.");
                }
                if ("integer".equals(f.format())) {
                    if (n.stripTrailingZeros().scale() > 0) throw err.apply("정수를 입력하세요.");
                    try {
                        return n.longValueExact();
                    } catch (ArithmeticException e) {
                        throw err.apply("정수를 입력하세요.");
                    }
                }
                var d = n.doubleValue();
                if (!Double.isFinite(d)) throw err.apply("숫자를 입력하세요.");
                return d;
            }
            case "date" -> {
                if (!(raw instanceof String str) || !DATE.matcher(str).matches()) throw err.apply("날짜를 YYYY-MM-DD 형식으로 입력하세요.");
                try {
                    LocalDate.parse(str);
                } catch (RuntimeException e) {
                    throw err.apply("날짜를 YYYY-MM-DD 형식으로 입력하세요.");
                }
                return str;
            }
            case "bool" -> {
                if (!(raw instanceof Boolean b)) throw err.apply("예 또는 아니요를 고르세요.");
                return b;
            }
            case "link" -> {
                if (!(raw instanceof String str) || !LINK.matcher(str.trim()).matches()) throw err.apply("http:// 또는 https://로 시작하는 주소를 입력하세요.");
                return str.trim();
            }
            default -> { // list, user
                var list = raw instanceof Collection<?> c ? new ArrayList<Object>(c) : List.of(raw);
                if (!f.isMultiValue() && list.size() > 1) throw err.apply("하나만 고를 수 있습니다.");
                if (list.stream().anyMatch(x -> !(x instanceof String))) throw err.apply("선택지를 고르세요.");
                var unique = new ArrayList<>(new LinkedHashSet<>(list.stream().map(String::valueOf).toList()));
                if ("list".equals(f.format()) && unique.stream().anyMatch(id -> f.options().stream().noneMatch(o -> o.id().toString().equals(id))))
                    throw err.apply("없는 선택지입니다.");
                if ("user".equals(f.format())) {
                    for (var id : unique) {
                        UUID u;
                        try {
                            u = UUID.fromString(id);
                        } catch (IllegalArgumentException e) {
                            throw err.apply("이 프로젝트의 멤버가 아닙니다.");
                        }
                        boolean ok = projectId != null ? s.isMember(u, projectId) : s.user(u).filter(x -> "active".equals(x.status())).isPresent();
                        if (!ok) throw err.apply("이 프로젝트의 멤버가 아닙니다.");
                    }
                }
                return f.isMultiValue() ? unique : unique.get(0);
            }
        }
    }

    public record Applied(Map<String, Object> values, Map<String, List<Object>> changes) {}

    /**
     * A client's customValues against an item of this project and type: the new stored map and the journal changes.
     * Nothing is written; unknown or inactive fields are a 422.
     */
    public Applied apply(Snapshot s, UUID projectId, UUID typeId, Map<String, Object> current, Map<String, Object> input) {
        var values = new LinkedHashMap<>(current);
        var changes = new LinkedHashMap<String, List<Object>>();
        var active = s.activeCustomFields(projectId, typeId);
        for (var e : input.entrySet()) {
            var f = active.stream().filter(x -> x.id().toString().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> ApiException.field("cf:" + e.getKey(), "이 작업에서 쓰지 않는 사용자 정의 필드입니다."));
            var next = check(s, f, e.getValue(), projectId);
            var prev = values.get(e.getKey());
            if (Objects.equals(normal(prev), normal(next))) continue;
            if (next == null) values.remove(e.getKey());
            else values.put(e.getKey(), next);
            var change = new ArrayList<Object>();
            change.add(prev);
            change.add(next);
            changes.put(key(f.id()), change);
        }
        return new Applied(values, changes);
    }

    /** Numbers compare by value (1 == 1.0 == 1L), everything else as is. */
    private static Object normal(Object v) {
        return v instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros() : v;
    }

    /** New items: defaults for fields not given, then the required check for every active field. */
    public Map<String, Object> initial(Snapshot s, UUID projectId, UUID typeId, Map<String, Object> input) {
        var out = new LinkedHashMap<String, Object>();
        var active = s.activeCustomFields(projectId, typeId);
        for (var id : input.keySet())
            if (active.stream().noneMatch(f -> f.id().toString().equals(id))) throw ApiException.field("cf:" + id, "이 작업에서 쓰지 않는 사용자 정의 필드입니다.");
        for (var f : active) {
            var raw = input.containsKey(f.id().toString()) ? input.get(f.id().toString()) : f.defaultValue();
            var v = check(s, f, raw, projectId);
            if (v != null) out.put(f.id().toString(), v);
        }
        return out;
    }

    /* ---------- lists ---------- */

    public static List<String> ops(CustomFieldRow f) {
        return switch (f.format()) {
            case "list", "user" -> List.of("eq", "neq", "any", "none");
            case "integer", "float" -> List.of("eq", "gte", "lte", "any", "none");
            case "date" -> List.of("today", "this_week", "next_days", "last_days", "on", "between", "any", "none");
            case "bool" -> List.of("eq");
            default -> List.of("contains", "not_contains", "any", "none");
        };
    }

    /** Sort key of one value; each format uses one Comparable type, with empty values last. */
    public Comparable<?> sortKey(Snapshot s, CustomFieldRow f, Object v) {
        boolean none = empty(v);
        var first = none ? null : ids(v).get(0);
        return switch (f.format()) {
            case "list" -> none ? Integer.MAX_VALUE : f.options().stream().map(o -> o.id().toString()).toList().indexOf(first);
            case "integer", "float" -> none ? Double.MAX_VALUE : Double.parseDouble(number(v));
            case "bool" -> none ? 2 : (Boolean.TRUE.equals(v) ? 0 : 1);
            case "user" -> none ? "￿" : text(s, f, first);
            default -> none ? "￿" : String.valueOf(v);
        };
    }
}
