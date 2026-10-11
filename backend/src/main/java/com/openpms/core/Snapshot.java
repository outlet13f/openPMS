package com.openpms.core;

import static com.openpms.db.Tables.CATEGORIES;
import static com.openpms.db.Tables.CUSTOM_FIELDS;
import static com.openpms.db.Tables.CUSTOM_FIELD_OPTIONS;
import static com.openpms.db.Tables.CUSTOM_FIELD_TYPES;
import static com.openpms.db.Tables.GLOBAL_ROLE_ASSIGNMENTS;
import static com.openpms.db.Tables.MEMBERS;
import static com.openpms.db.Tables.MEMBER_ROLES;
import static com.openpms.db.Tables.NON_WORKING_DAYS;
import static com.openpms.db.Tables.PRIORITIES;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.PROJECT_CUSTOM_FIELDS;
import static com.openpms.db.Tables.PROJECT_TYPES;
import static com.openpms.db.Tables.ROLES;
import static com.openpms.db.Tables.SETTINGS;
import static com.openpms.db.Tables.STATUSES;
import static com.openpms.db.Tables.TYPES;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_GROUPS;
import static com.openpms.db.Tables.USER_GROUP_MEMBERS;
import static com.openpms.db.Tables.VERSIONS;
import static com.openpms.db.Tables.WORKFLOWS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static com.openpms.db.Tables.WORK_PACKAGE_SHARES;

import com.openpms.common.Dates;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The small tables that permission checks and DTOs read all the time (people, roles, projects, members, types,
 * statuses, priorities, workflows, versions, categories, settings), loaded lazily once per request. An internal
 * instance has hundreds of these rows, not millions; work packages and journals are always queried directly.
 */
public final class Snapshot {
    public record UserRow(UUID id, String email, String name, String status, boolean isAdmin, String locale, String timeZone, String theme,
                          OffsetDateTime lastLoginAt, OffsetDateTime createdAt) {
        public String displayName() {
            return "deleted".equals(status) ? "삭제된 사용자" : name;
        }
    }

    public record GroupRow(UUID id, String name, Set<UUID> memberIds) {}

    public record RoleRow(UUID id, String name, String scope, String builtin, List<String> permissions, int position) {}

    public record ProjectRow(UUID id, String identifier, String name, String descriptionHtml, String status, String statusExplanation,
                             boolean isPublic, boolean isTemplate, OffsetDateTime archivedAt, UUID parentId, List<String> enabledModules,
                             List<UUID> typeIds, OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean internalComments) {}

    public record MemberRow(UUID id, UUID projectId, UUID userId, UUID groupId, List<UUID> roleIds, OffsetDateTime createdAt) {}

    public record TypeRow(UUID id, String name, String color, int position, boolean isMilestone, boolean isDefault, boolean isInRoadmap) {}

    public record StatusRow(UUID id, String name, String color, int position, boolean isDefault, boolean isClosed, boolean isReadonly,
                            int defaultDoneRatio, boolean excludedFromTotals) {}

    public record PriorityRow(UUID id, String name, String color, int position, boolean isDefault, boolean isActive) {}

    public record WorkflowRow(UUID typeId, UUID roleId, UUID from, UUID to, String appliesTo) {}

    public record VersionRow(UUID id, UUID projectId, String name, String description, String status, LocalDate startDate, LocalDate endDate) {}

    public record CategoryRow(UUID id, UUID projectId, String name, UUID defaultAssigneeId) {}

    public record Settings(List<Integer> workingDays, int hoursPerDay, int journalAggregationMinutes, com.openpms.wp.Progress.Settings progress) {}

    public record OptionRow(UUID id, String value, int position) {}

    /** A work package custom field (S34). defaultValue is the stored JSON value (String, Number, Boolean, List) or null. */
    public record CustomFieldRow(UUID id, String name, String format, boolean isRequired, boolean isForAll, boolean isFilterable, boolean isMultiValue,
                                 Object defaultValue, Integer minLength, Integer maxLength, String regexp, int position, List<OptionRow> options,
                                 List<UUID> typeIds) {}

    // not final: the lazy loaders below are field initialisers that read these (set once, in the constructor)
    private DSLContext db;
    private JsonMapper json;

    public Snapshot(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private static <T> Supplier<T> lazy(Supplier<T> s) {
        return new Supplier<>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) value = s.get();
                return value;
            }
        };
    }

    private final Supplier<Map<UUID, UserRow>> users = lazy(() -> this.db.selectFrom(USERS).fetch().stream()
            .map(r -> new UserRow(r.getId(), r.getEmail(), r.getName(), r.getStatus(), r.getIsAdmin(), r.getLocale(), r.getTimeZone(), r.getTheme(),
                    r.getLastLoginAt(), r.getCreatedAt()))
            .collect(Collectors.toMap(UserRow::id, u -> u, (a, b) -> a, LinkedHashMap::new)));

    private final Supplier<Map<UUID, GroupRow>> groups = lazy(() -> {
        var members = new HashMap<UUID, Set<UUID>>();
        this.db.selectFrom(USER_GROUP_MEMBERS).fetch().forEach(r -> members.computeIfAbsent(r.getGroupId(), k -> new HashSet<>()).add(r.getUserId()));
        return this.db.selectFrom(USER_GROUPS).orderBy(USER_GROUPS.NAME).fetch().stream()
                .map(r -> new GroupRow(r.getId(), r.getName(), members.getOrDefault(r.getId(), Set.of())))
                .collect(Collectors.toMap(GroupRow::id, g -> g, (a, b) -> a, LinkedHashMap::new));
    });

    private final Supplier<List<RoleRow>> roles = lazy(() -> this.db.selectFrom(ROLES).orderBy(ROLES.POSITION, ROLES.NAME).fetch().stream()
            .map(r -> new RoleRow(r.getId(), r.getName(), r.getScope(), r.getBuiltin(), Arrays.asList(r.getPermissions()), r.getPosition()))
            .toList());

    private final Supplier<Map<UUID, ProjectRow>> projects = lazy(() -> {
        var types = new HashMap<UUID, List<UUID>>();
        this.db.selectFrom(PROJECT_TYPES).fetch().forEach(r -> types.computeIfAbsent(r.getProjectId(), k -> new ArrayList<>()).add(r.getTypeId()));
        return this.db.selectFrom(PROJECTS).orderBy(PROJECTS.NAME).fetch().stream()
                .map(r -> new ProjectRow(r.getId(), r.getIdentifier(), r.getName(), r.getDescriptionHtml(), r.getStatus(), r.getStatusExplanation(),
                        r.getIsPublic(), r.getIsTemplate(), r.getArchivedAt(), r.getParentId(), Arrays.asList(r.getEnabledModules()),
                        types.getOrDefault(r.getId(), List.of()), r.getCreatedAt(), r.getUpdatedAt(), r.getInternalComments()))
                .collect(Collectors.toMap(ProjectRow::id, p -> p, (a, b) -> a, LinkedHashMap::new));
    });

    private final Supplier<List<MemberRow>> members = lazy(() -> {
        var roleIds = new HashMap<UUID, List<UUID>>();
        this.db.selectFrom(MEMBER_ROLES).fetch().forEach(r -> roleIds.computeIfAbsent(r.getMemberId(), k -> new ArrayList<>()).add(r.getRoleId()));
        return this.db.selectFrom(MEMBERS).orderBy(MEMBERS.CREATED_AT).fetch().stream()
                .map(r -> new MemberRow(r.getId(), r.getProjectId(), r.getUserId(), r.getGroupId(), roleIds.getOrDefault(r.getId(), List.of()), r.getCreatedAt()))
                .toList();
    });

    private final Supplier<List<TypeRow>> types = lazy(() -> this.db.selectFrom(TYPES).orderBy(TYPES.POSITION, TYPES.NAME).fetch().stream()
            .map(r -> new TypeRow(r.getId(), r.getName(), r.getColor(), r.getPosition(), r.getIsMilestone(), r.getIsDefault(), r.getIsInRoadmap()))
            .toList());

    private final Supplier<List<StatusRow>> statuses = lazy(() -> this.db.selectFrom(STATUSES).orderBy(STATUSES.POSITION, STATUSES.NAME).fetch().stream()
            .map(r -> new StatusRow(r.getId(), r.getName(), r.getColor(), r.getPosition(), r.getIsDefault(), r.getIsClosed(), r.getIsReadonly(),
                    r.getDefaultDoneRatio(), r.getExcludedFromTotals()))
            .toList());

    private final Supplier<List<PriorityRow>> priorities = lazy(() -> this.db.selectFrom(PRIORITIES).orderBy(PRIORITIES.POSITION, PRIORITIES.NAME).fetch().stream()
            .map(r -> new PriorityRow(r.getId(), r.getName(), r.getColor(), r.getPosition(), r.getIsDefault(), r.getIsActive()))
            .toList());

    private final Supplier<List<WorkflowRow>> workflows = lazy(() -> this.db.selectFrom(WORKFLOWS).fetch().stream()
            .map(r -> new WorkflowRow(r.getTypeId(), r.getRoleId(), r.getFromStatusId(), r.getToStatusId(), r.getAppliesTo()))
            .toList());

    private final Supplier<List<VersionRow>> versions = lazy(() -> this.db.selectFrom(VERSIONS).orderBy(VERSIONS.START_DATE.asc().nullsLast(), VERSIONS.NAME).fetch().stream()
            .map(r -> new VersionRow(r.getId(), r.getProjectId(), r.getName(), r.getDescription(), r.getStatus(), r.getStartDate(), r.getEndDate()))
            .toList());

    private final Supplier<List<CategoryRow>> categories = lazy(() -> this.db.selectFrom(CATEGORIES).orderBy(CATEGORIES.NAME).fetch().stream()
            .map(r -> new CategoryRow(r.getId(), r.getProjectId(), r.getName(), r.getDefaultAssigneeId()))
            .toList());

    private final Supplier<Settings> settings = lazy(() -> {
        var values = this.db.selectFrom(SETTINGS).fetchMap(SETTINGS.KEY, SETTINGS.VALUE);
        List<Integer> working = List.of(1, 2, 3, 4, 5);
        int hours = 8;
        int aggregation = 5;
        var progress = com.openpms.wp.Progress.Settings.DEFAULT;
        try {
            var mode = values.get("progress.mode") == null ? progress.mode() : this.json.readValue(values.get("progress.mode").data(), String.class);
            var closed = values.get("progress.closed_sets_100") != null && Boolean.parseBoolean(values.get("progress.closed_sets_100").data().trim());
            var totals = values.get("progress.totals") == null ? progress.totals() : this.json.readValue(values.get("progress.totals").data(), String.class);
            progress = new com.openpms.wp.Progress.Settings(mode, closed, totals);
        } catch (RuntimeException ignored) {
            // keep the defaults for an unreadable value
        }
        try {
            if (values.get("calendar.working_days") != null)
                working = List.of(this.json.readValue(values.get("calendar.working_days").data(), Integer[].class));
            if (values.get("calendar.hours_per_day") != null) hours = Integer.parseInt(values.get("calendar.hours_per_day").data().trim());
            if (values.get("journals.aggregation_minutes") != null) aggregation = Integer.parseInt(values.get("journals.aggregation_minutes").data().trim());
        } catch (RuntimeException ignored) {
            // keep defaults for an unreadable value
        }
        return new Settings(working, hours, aggregation, progress);
    });

    private final Supplier<Map<UUID, Set<UUID>>> globalRoles = lazy(() -> {
        var out = new HashMap<UUID, Set<UUID>>();
        this.db.selectFrom(GLOBAL_ROLE_ASSIGNMENTS).fetch().forEach(r -> out.computeIfAbsent(r.getUserId(), k -> new HashSet<>()).add(r.getRoleId()));
        return out;
    });

    private final Supplier<List<CustomFieldRow>> customFields = lazy(() -> {
        var options = new HashMap<UUID, List<OptionRow>>();
        this.db.selectFrom(CUSTOM_FIELD_OPTIONS).orderBy(CUSTOM_FIELD_OPTIONS.POSITION, CUSTOM_FIELD_OPTIONS.VALUE).fetch()
                .forEach(r -> options.computeIfAbsent(r.getCustomFieldId(), k -> new ArrayList<>()).add(new OptionRow(r.getId(), r.getValue(), r.getPosition())));
        var typeIds = new HashMap<UUID, List<UUID>>();
        this.db.selectFrom(CUSTOM_FIELD_TYPES).fetch().forEach(r -> typeIds.computeIfAbsent(r.getCustomFieldId(), k -> new ArrayList<>()).add(r.getTypeId()));
        return this.db.selectFrom(CUSTOM_FIELDS).where(CUSTOM_FIELDS.ENTITY.eq("work_package")).orderBy(CUSTOM_FIELDS.POSITION, CUSTOM_FIELDS.NAME).fetch().stream()
                .map(r -> new CustomFieldRow(r.getId(), r.getName(), r.getFormat(), r.getIsRequired(), r.getIsForAll(), r.getIsFilterable(), r.getIsMultiValue(),
                        r.getDefaultValue() == null ? null : this.json.readValue(r.getDefaultValue().data(), Object.class), r.getMinLength(), r.getMaxLength(),
                        r.getRegexp(), r.getPosition(), options.getOrDefault(r.getId(), List.of()), typeIds.getOrDefault(r.getId(), List.of())))
                .toList();
    });

    private final Supplier<Map<UUID, Set<UUID>>> projectCustomFields = lazy(() -> {
        var out = new HashMap<UUID, Set<UUID>>();
        this.db.selectFrom(PROJECT_CUSTOM_FIELDS).fetch().forEach(r -> out.computeIfAbsent(r.getProjectId(), k -> new HashSet<>()).add(r.getCustomFieldId()));
        return out;
    });

    private final Supplier<Set<LocalDate>> holidays = lazy(() -> new HashSet<>(this.db.select(NON_WORKING_DAYS.DAY).from(NON_WORKING_DAYS).fetch(NON_WORKING_DAYS.DAY)));

    /* ---------- accessors ---------- */

    /** One person's shares: work package id → role, and the projects those items are in. Loaded once per person. */
    public record Shares(Map<UUID, String> roles, Set<UUID> projectIds) {}

    private final Map<UUID, Shares> shares = new java.util.concurrent.ConcurrentHashMap<>();

    public Shares shares(UUID userId) {
        return shares.computeIfAbsent(userId, id -> {
            var roles = new HashMap<UUID, String>();
            var projectIds = new HashSet<UUID>();
            this.db.select(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID, WORK_PACKAGE_SHARES.ROLE, WORK_PACKAGES.PROJECT_ID).from(WORK_PACKAGE_SHARES)
                    .join(WORK_PACKAGES).on(WORK_PACKAGES.ID.eq(WORK_PACKAGE_SHARES.WORK_PACKAGE_ID))
                    .where(WORK_PACKAGE_SHARES.USER_ID.eq(id)).fetch().forEach(r -> {
                        roles.put(r.value1(), r.value2());
                        projectIds.add(r.value3());
                    });
            return new Shares(roles, projectIds);
        });
    }

    /** Work package custom fields, in order. */
    public List<CustomFieldRow> customFields() {
        return customFields.get();
    }

    public Optional<CustomFieldRow> customField(UUID id) {
        return customFields.get().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** Fields turned on for a project (isForAll ones are on everywhere and not listed). */
    public Set<UUID> projectCustomFieldIds(UUID projectId) {
        return projectCustomFields.get().getOrDefault(projectId, Set.of());
    }

    public boolean customFieldEnabledIn(CustomFieldRow f, UUID projectId) {
        return f.isForAll() || projectCustomFieldIds(projectId).contains(f.id());
    }

    /** The fields an item of this project and type has, in order. */
    public List<CustomFieldRow> activeCustomFields(UUID projectId, UUID typeId) {
        return customFields().stream().filter(f -> f.typeIds().contains(typeId) && customFieldEnabledIn(f, projectId)).toList();
    }

    public Map<UUID, UserRow> users() {
        return users.get();
    }

    public Optional<UserRow> user(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(users().get(id));
    }

    public Map<UUID, GroupRow> groups() {
        return groups.get();
    }

    public List<RoleRow> roles() {
        return roles.get();
    }

    public Optional<RoleRow> role(UUID id) {
        return roles().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public Map<UUID, ProjectRow> projects() {
        return projects.get();
    }

    public Optional<ProjectRow> project(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(projects().get(id));
    }

    public Optional<ProjectRow> projectByIdOrIdentifier(String idOrIdentifier) {
        try {
            var p = projects().get(UUID.fromString(idOrIdentifier));
            if (p != null) return Optional.of(p);
        } catch (IllegalArgumentException ignored) {
            // not a uuid: an identifier
        }
        return projects().values().stream().filter(p -> p.identifier().equals(idOrIdentifier)).findFirst();
    }

    public List<MemberRow> members() {
        return members.get();
    }

    public List<TypeRow> types() {
        return types.get();
    }

    public Optional<TypeRow> type(UUID id) {
        return types().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public List<StatusRow> statuses() {
        return statuses.get();
    }

    public Optional<StatusRow> status(UUID id) {
        return statuses().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public StatusRow defaultStatus() {
        return statuses().stream().filter(StatusRow::isDefault).findFirst().orElse(statuses().get(0));
    }

    public List<PriorityRow> priorities() {
        return priorities.get();
    }

    public Optional<PriorityRow> priority(UUID id) {
        return priorities().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public PriorityRow defaultPriority() {
        return priorities().stream().filter(PriorityRow::isDefault).findFirst().orElse(priorities().get(0));
    }

    public List<WorkflowRow> workflows() {
        return workflows.get();
    }

    public List<VersionRow> versions() {
        return versions.get();
    }

    public Optional<VersionRow> version(UUID id) {
        return id == null ? Optional.empty() : versions().stream().filter(v -> v.id().equals(id)).findFirst();
    }

    public List<CategoryRow> categories() {
        return categories.get();
    }

    public Optional<CategoryRow> category(UUID id) {
        return id == null ? Optional.empty() : categories().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Set<UUID> globalRoleIds(UUID userId) {
        return globalRoles.get().getOrDefault(userId, Set.of());
    }

    public Settings settings() {
        return settings.get();
    }

    public Dates.WorkCalendar calendar() {
        return new Dates.WorkCalendar(settings().workingDays(), holidays.get());
    }

    /* ---------- derived ---------- */

    /** Roles from direct membership plus membership through any of the user's groups (architecture.md §3.1). */
    public Set<UUID> memberRoleIds(UUID userId, UUID projectId) {
        var myGroups = groups().values().stream().filter(g -> g.memberIds().contains(userId)).map(GroupRow::id).collect(Collectors.toSet());
        var out = new HashSet<UUID>();
        for (var m : members()) {
            if (!m.projectId().equals(projectId)) continue;
            if (userId.equals(m.userId()) || (m.groupId() != null && myGroups.contains(m.groupId()))) out.addAll(m.roleIds());
        }
        return out;
    }

    public boolean isMember(UUID userId, UUID projectId) {
        return !memberRoleIds(userId, projectId).isEmpty();
    }

    /** Active people who are members of the project, directly or through a group (assignee candidates), by name. */
    public List<UserRow> projectUsers(UUID projectId) {
        var ids = new HashSet<UUID>();
        for (var m : members()) {
            if (!m.projectId().equals(projectId)) continue;
            if (m.userId() != null) ids.add(m.userId());
            if (m.groupId() != null && groups().containsKey(m.groupId())) ids.addAll(groups().get(m.groupId()).memberIds());
        }
        return ids.stream().map(id -> users().get(id)).filter(u -> u != null && "active".equals(u.status()))
                .sorted(Comparator.comparing(UserRow::name, Text.KOREAN)).toList();
    }

    public boolean isDescendant(UUID projectId, UUID ancestorId) {
        var cur = projects().get(projectId);
        for (int i = 0; cur != null && cur.parentId() != null && i < 50; i++) {
            if (cur.parentId().equals(ancestorId)) return true;
            cur = projects().get(cur.parentId());
        }
        return false;
    }
}
