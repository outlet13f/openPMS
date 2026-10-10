package com.openpms.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The API's JSON shapes, field for field the same as frontend/src/api/types.ts. */
public final class Dto {
    private Dto() {}

    public record Page<T>(List<T> items, long total, int page, int pageSize) {}

    public record IdName(UUID id, String name) {}

    /* ---------- people ---------- */

    public record UserRef(UUID id, String name) {}

    /** digestTimes: "HH:MM" in the person's time zone; remindersPausedUntil: no date alerts up to that day */
    public record Preferences(boolean commentsNewestFirst, boolean warnOnUnsaved, boolean keyboardShortcuts, boolean immediateOnMention,
                              boolean digestEnabled, List<String> digestTimes, LocalDate remindersPausedUntil, List<String> myPageWidgets) {}

    /** S20: what a person is notified about (mentions and watched items always). Alert days: 0, 1, 3, 7 or null (off). */
    public record NotificationSetting(boolean assignee, boolean responsible, boolean shared, boolean workPackageCreated, boolean commented,
                                      boolean statusChanged, boolean priorityChanged, boolean datesChanged, Integer startDateAlertDays,
                                      Integer dueDateAlertDays, boolean overdueAlert) {}

    public record ProjectNotificationSetting(ProjectRef project, boolean assignee, boolean responsible, boolean shared, boolean workPackageCreated,
                                             boolean commented, boolean statusChanged, boolean priorityChanged, boolean datesChanged,
                                             Integer startDateAlertDays, Integer dueDateAlertDays, boolean overdueAlert) {}

    public record NotificationSettings(@JsonProperty("default") NotificationSetting defaultSetting, List<ProjectNotificationSetting> projects) {}

    public record Me(UUID id, String name, String email, String status, boolean isAdmin, String locale, String timeZone,
                     List<String> globalPermissions, Preferences preferences, String theme) {}

    public record Role(UUID id, String name, String scope, String builtin, List<String> permissions) {}

    /* ---------- projects ---------- */

    public record ProjectRef(UUID id, String identifier, String name) {}

    public record Project(UUID id, String identifier, String name, String descriptionHtml, String status, String statusExplanation,
                          boolean isPublic, boolean isTemplate, OffsetDateTime archivedAt, ProjectRef parent, List<String> enabledModules,
                          boolean favorite, List<String> myPermissions, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime latestActivityAt) {}

    public record OverviewMember(IdName user, List<String> roles) {}

    public record StatusCount(StatusRef status, long count) {}

    public record ProjectOverview(Project project, List<OverviewMember> members, List<StatusCount> countsByStatus, long openCount, long overdueCount) {}

    public record Principal(UUID id, String name, String kind, String email) {}

    public record Member(UUID id, Principal principal, List<IdName> roles, OffsetDateTime createdAt) {}

    public record PrincipalHit(UUID id, String name, String kind) {}

    /* ---------- work package configuration ---------- */

    public record TypeRef(UUID id, String name, String color, boolean isMilestone) {}

    public record StatusRef(UUID id, String name, String color, boolean isClosed, boolean isReadonly) {}

    public record PriorityRef(UUID id, String name, String color) {}

    /* ---------- work packages ---------- */

    public record WorkPackageRef(UUID id, long number, String subject, TypeRef type, StatusRef status, ProjectRef project) {}

    public record WorkPackageRefDated(UUID id, long number, String subject, TypeRef type, StatusRef status, ProjectRef project,
                                      LocalDate startDate, LocalDate dueDate) {}

    public record AllowedActions(boolean edit, boolean delete, boolean move, boolean comment, boolean manageRelations, boolean manageSubtasks,
                                 boolean watch, boolean viewWatchers, boolean addWatchers, boolean attach, boolean share) {}

    /** S08 주시자 탭: anyone may remove themselves; removing others needs wp.delete_watchers. */
    public record Watcher(UserRef user, boolean canRemove) {}

    /** sharedWithMe: my share role when the item reached me through a share (view, comment, edit), else null */
    public record Meta(AllowedActions allowedActions, List<String> editableFields, List<String> derived, boolean overdue, String sharedWithMe) {}

    public record WorkPackageShare(UUID id, UserRef user, String role, UserRef sharedBy, OffsetDateTime createdAt) {}

    public record WorkPackage(UUID id, long number, ProjectRef project, TypeRef type, StatusRef status, PriorityRef priority, String subject,
                              String descriptionHtml, UserRef author, UserRef assignee, UserRef responsible, IdName version, IdName category,
                              WorkPackageRef parent, int childCount, LocalDate startDate, LocalDate dueDate, Integer duration,
                              boolean ignoreNonWorkingDays, boolean scheduleManually, Double workHours, Double remainingHours, Integer doneRatio,
                              boolean watching, int watcherCount, int relationCount, int attachmentCount, Map<String, CustomValue> customValues,
                              Totals totals, int lockVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt, @JsonProperty("_meta") Meta meta) {}

    /** S28: one group of the detail's attributes, in order (built-in keys or cf:<id>) */
    public record AttributeGroup(String name, List<String> attributes) {}

    /** S28: a type's form; custom fields in no group and not hidden show in an automatic last group */
    public record TypeForm(List<AttributeGroup> groups, List<String> hidden) {}

    /** P4/P5: an item and its descendants */
    public record Totals(Double workHours, Double remainingHours, Integer doneRatio) {}

    /** S32 */
    public record ProgressSettings(String mode, boolean closedSets100, String totals, Integer recalculated) {}

    /* ---------- custom fields (S34, S25) ---------- */

    /** value as stored (option/user ids, ISO date, number, boolean, text); text for display */
    public record CustomValue(Object value, String text) {}

    public record CustomFieldOption(UUID id, String value) {}

    public record CustomField(UUID id, String name, String format, boolean isRequired, boolean isForAll, boolean isFilterable, boolean isMultiValue,
                              Object defaultValue, Integer minLength, Integer maxLength, String regexp, int position, List<CustomFieldOption> options,
                              List<UUID> typeIds) {}

    public record AdminCustomField(UUID id, String name, String format, boolean isRequired, boolean isForAll, boolean isFilterable, boolean isMultiValue,
                                   Object defaultValue, Integer minLength, Integer maxLength, String regexp, int position, List<CustomFieldOption> options,
                                   List<UUID> typeIds, int projectCount) {}

    public record ProjectCustomFields(List<CustomField> fields, List<UUID> enabledIds) {}

    public record CategoryOption(UUID id, String name, UUID defaultAssigneeId) {}

    public record FormDefaults(UUID statusId, UUID priorityId, String descriptionHtml) {}

    public record WorkPackageForm(TypeRef type, List<TypeRef> types, List<StatusRef> statuses, List<PriorityRef> priorities, List<UserRef> assignees,
                                  List<IdName> versions, List<CategoryOption> categories, FormDefaults defaults, List<String> required,
                                  List<CustomField> customFields) {}

    public record JournalDetail(String field, String label, String from, String to) {}

    public record Journal(UUID id, int version, UserRef user, String notesHtml, boolean isInternal, List<JournalDetail> details,
                          Map<String, Object> cause, OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean canEdit) {}

    public record Relation(UUID id, String type, WorkPackageRefDated other, int lag, String description) {}

    public record RelationGroups(List<Relation> relations, WorkPackageRefDated parent, List<WorkPackageRefDated> children) {}

    public record Affected(long number, String subject, LocalDate startDate, LocalDate dueDate) {}

    public record SchedulePreview(LocalDate startDate, LocalDate dueDate, Integer duration, List<Affected> affected) {}

    /* ---------- queries ---------- */

    /** groupKey: the row's group when the list is grouped (QueryGroup.key), else null. */
    public record QueryRow(WorkPackage wp, int depth, boolean hasChildren, boolean context, String groupKey) {}

    public record TimelineLink(long fromNumber, long toNumber, int lag) {}

    /** A group of a grouped list, over every matching item (not just the page). sums: per summable visible column. */
    public record QueryGroup(String key, String label, String color, long count, Map<String, BigDecimal> sums) {}

    /** groups: only when grouped; sums: totals over every matching item, only when sums are on. */
    public record QueryResult(List<QueryRow> rows, long total, int page, int pageSize, List<TimelineLink> relations, List<QueryGroup> groups,
                              Map<String, BigDecimal> sums) {}

    public record SavedQuery(String id, String name, String kind, String view, Object spec, boolean favorite, UUID projectId, boolean canEdit) {}

    public record FilterOptions(List<StatusRef> statuses, List<TypeRef> types, List<PriorityRef> priorities, List<UserRef> users,
                                List<IdName> versions, List<IdName> categories, List<IdName> groups, List<IdName> roles, List<IdName> projects,
                                List<CustomField> customFields, Map<String, TypeForm> typeForms) {}

    /* ---------- boards ---------- */

    public record BoardSummary(UUID id, String name, String kind, boolean isPublic, OffsetDateTime createdAt) {}

    public record BoardColumn(UUID id, int position, String title, String color, UUID valueId, List<WorkPackage> cards) {}

    public record Board(UUID id, String name, String kind, boolean isPublic, OffsetDateTime createdAt, UUID projectId, List<BoardColumn> columns,
                        boolean canManage) {}

    /* ---------- notifications, search, calendar ---------- */

    public record NotificationItem(UUID id, String reason, boolean read, UserRef actor, String summary, OffsetDateTime createdAt) {}

    public record NotificationGroup(WorkPackageRef workPackage, List<NotificationItem> items, int unread, OffsetDateTime latestAt) {}

    public record SearchHit(String kind, String title, String subtitle, String href) {}

    /** S22: kind "work_package"; matchedIn number, subject, description or comment; snippet: plain text around the match */
    public record WorkPackageResult(String kind, long number, String subject, TypeRef type, StatusRef status, ProjectRef project, String matchedIn,
                                    String snippet, OffsetDateTime updatedAt) {}

    public record ProjectResult(String kind, ProjectRef project, String snippet) {}

    public record SearchCounts(int workPackages, int projects) {}

    /** items: WorkPackageResult and ProjectResult (projects first in "all") */
    public record SearchResults(String q, String scope, SearchCounts counts, List<Object> items, int total, int page, int pageSize) {}

    public record NonWorkingDay(UUID id, LocalDate date, String name) {}

    public record CalendarDays(List<Integer> workingDays, List<NonWorkingDay> days) {}
}
