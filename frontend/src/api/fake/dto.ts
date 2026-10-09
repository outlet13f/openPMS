// Turns fake database rows into the API's DTO shapes (src/api/types.ts).
import type { CustomValue, Journal, JournalDetail, Project, ProjectRef, StatusRef, TypeRef, UserRef, WorkPackage, WorkPackageRef, WpField } from "../types";
import { formatDate, todayIso } from "@/lib/dates";
import { db, type DbJournal, type DbProject, type DbUser, type DbWp } from "./db";
import { allowedStatusIds, can, projectPermissions } from "./permissions";
import { cfIdOf, cfText, customValuesOf } from "./custom-fields";

export const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));

export function userRef(id: string | null | undefined): UserRef | null {
  if (!id) return null;
  const u = db.users.find((x) => x.id === id);
  return u ? { id: u.id, name: u.status === "deleted" ? "삭제된 사용자" : u.name } : { id, name: "알 수 없는 사용자" };
}

export function projectRef(p: DbProject): ProjectRef {
  return { id: p.id, identifier: p.identifier, name: p.name };
}

export function typeRef(id: string): TypeRef {
  const t = db.types.find((x) => x.id === id)!;
  return { id: t.id, name: t.name, color: t.color, isMilestone: t.isMilestone };
}

export function statusRef(id: string): StatusRef {
  const s = db.statuses.find((x) => x.id === id)!;
  return { id: s.id, name: s.name, color: s.color, isClosed: s.isClosed, isReadonly: s.isReadonly };
}

export function priorityRef(id: string) {
  const p = db.priorities.find((x) => x.id === id)!;
  return { id: p.id, name: p.name, color: p.color };
}

export function wpRef(w: DbWp): WorkPackageRef {
  const p = db.projects.find((x) => x.id === w.projectId)!;
  return { id: w.id, number: w.number, subject: w.subject, type: typeRef(w.typeId), status: statusRef(w.statusId), project: projectRef(p) };
}

export function toProject(p: DbProject, user: DbUser): Project {
  const parent = p.parentId ? db.projects.find((x) => x.id === p.parentId) : null;
  return clone({
    ...projectRef(p),
    descriptionHtml: p.descriptionHtml,
    status: p.status,
    statusExplanation: p.statusExplanation,
    isPublic: p.isPublic,
    isTemplate: p.isTemplate,
    archivedAt: p.archivedAt,
    parent: parent ? projectRef(parent) : null,
    enabledModules: p.enabledModules,
    favorite: db.projectFavorites.some((f) => f.userId === user.id && f.projectId === p.id),
    myPermissions: [...projectPermissions(user, p)],
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
    latestActivityAt: p.latestActivityAt,
  });
}

export const children = (w: DbWp) => db.workPackages.filter((x) => x.parentId === w.id);

export function isOverdue(w: DbWp, tz: string) {
  const s = db.statuses.find((x) => x.id === w.statusId)!;
  return !!w.dueDate && !s.isClosed && w.dueDate < todayIso(tz);
}

export function toWp(w: DbWp, user: DbUser): WorkPackage {
  const project = db.projects.find((x) => x.id === w.projectId)!;
  const parent = w.parentId ? db.workPackages.find((x) => x.id === w.parentId) : null;
  const status = db.statuses.find((x) => x.id === w.statusId)!;
  const version = w.versionId ? db.versions.find((v) => v.id === w.versionId) : null;
  const category = w.categoryId ? db.categories.find((c) => c.id === w.categoryId) : null;
  const kids = children(w);
  const canEdit = can(user, project, "wp.edit");

  const derived: WpField[] = [];
  if (!w.scheduleManually && kids.length) derived.push("dates");

  let editable: WpField[] = [];
  if (canEdit) {
    editable = status.isReadonly
      ? []
      : ["subject", "description", "type", "priority", "assignee", "responsible", "category", "dates", "work", "remainingWork", "doneRatio"];
    if (can(user, project, "wp.assign_versions") && !status.isReadonly) editable.push("version");
    if (can(user, project, "wp.manage_subtasks") && !status.isReadonly) editable.push("parent");
    editable = editable.filter((f) => !derived.includes(f));
    if (allowedStatusIds(user, w).length) editable.push("status");
  }

  return clone({
    id: w.id,
    number: w.number,
    project: projectRef(project),
    type: typeRef(w.typeId),
    status: statusRef(w.statusId),
    priority: priorityRef(w.priorityId),
    subject: w.subject,
    descriptionHtml: w.descriptionHtml,
    author: userRef(w.authorId)!,
    assignee: userRef(w.assigneeId),
    responsible: userRef(w.responsibleId),
    version: version ? { id: version.id, name: version.name } : null,
    category: category ? { id: category.id, name: category.name } : null,
    parent: parent ? wpRef(parent) : null,
    childCount: kids.length,
    startDate: w.startDate,
    dueDate: w.dueDate,
    duration: w.duration,
    ignoreNonWorkingDays: w.ignoreNonWorkingDays,
    scheduleManually: w.scheduleManually,
    workHours: w.workHours,
    remainingHours: w.remainingHours,
    doneRatio: w.doneRatio,
    watching: db.watchers.some((x) => x.wpId === w.id && x.userId === user.id),
    watcherCount: db.watchers.filter((x) => x.wpId === w.id).length,
    relationCount: db.relations.filter((r) => r.fromId === w.id || r.toId === w.id).length + kids.length + (parent ? 1 : 0),
    attachmentCount: db.attachments.filter((a) => a.wpId === w.id).length,
    customValues: customValuesOf(w),
    lockVersion: w.lockVersion,
    createdAt: w.createdAt,
    updatedAt: w.updatedAt,
    _meta: {
      allowedActions: {
        edit: canEdit,
        delete: can(user, project, "wp.delete"),
        move: can(user, project, "wp.move"),
        comment: can(user, project, "wp.comment"),
        manageRelations: can(user, project, "wp.manage_relations"),
        manageSubtasks: can(user, project, "wp.manage_subtasks"),
        watch: true,
        viewWatchers: can(user, project, "wp.view_watchers"),
        addWatchers: can(user, project, "wp.add_watchers"),
        attach: can(user, project, "wp.attach"),
      },
      editableFields: editable,
      derived,
      overdue: isOverdue(w, user.timeZone),
    },
  });
}

const FIELD_LABEL: Record<string, string> = {
  subject: "제목",
  descriptionHtml: "설명",
  typeId: "유형",
  statusId: "상태",
  priorityId: "우선순위",
  assigneeId: "담당자",
  responsibleId: "책임자",
  versionId: "버전",
  categoryId: "카테고리",
  parentId: "상위 작업",
  startDate: "시작일",
  dueDate: "완료일",
  duration: "기간",
  scheduleManually: "일정 방식",
  ignoreNonWorkingDays: "근무일만 계산",
  workHours: "작업량",
  remainingHours: "남은 작업",
  doneRatio: "완료 %",
  attachment: "첨부 파일",
};

function display(field: string, v: unknown): string | null {
  if (v === null || v === undefined || v === "") return null;
  switch (field) {
    case "statusId":
      return db.statuses.find((s) => s.id === v)?.name ?? null;
    case "typeId":
      return db.types.find((s) => s.id === v)?.name ?? null;
    case "priorityId":
      return db.priorities.find((s) => s.id === v)?.name ?? null;
    case "assigneeId":
    case "responsibleId":
      return userRef(v as string)?.name ?? null;
    case "versionId":
      return db.versions.find((s) => s.id === v)?.name ?? null;
    case "categoryId":
      return db.categories.find((s) => s.id === v)?.name ?? null;
    case "parentId": {
      const p = db.workPackages.find((s) => s.id === v);
      return p ? `#${p.number} ${p.subject}` : null;
    }
    case "startDate":
    case "dueDate":
      return formatDate(v as string);
    case "duration":
      return `${v}일`;
    case "scheduleManually":
      return v ? "수동" : "자동";
    case "ignoreNonWorkingDays":
      return v ? "아니요" : "예";
    case "workHours":
    case "remainingHours":
      return `${v}시간`;
    case "doneRatio":
      return `${v}%`;
    case "descriptionHtml":
      return null;
    default:
      return String(v);
  }
}

export function toJournal(j: DbJournal, user: DbUser): Journal {
  const details: JournalDetail[] = Object.entries(j.changes)
    .filter(([f]) => f !== "_created" && f !== "lockVersion")
    .flatMap(([field, [from, to]]): JournalDetail[] => {
      const cfId = cfIdOf(field);
      if (cfId) {
        // a custom field: its current name and display form; a deleted field's history is not shown
        const f = db.customFields.find((x) => x.id === cfId);
        return f ? [{ field, label: f.name, from: cfText(f, from as CustomValue) || null, to: cfText(f, to as CustomValue) || null }] : [];
      }
      const base = field.split(":")[0]; // "attachment:<id>": one key per file
      return [{ field, label: FIELD_LABEL[base] ?? field, from: display(base, from), to: display(base, to) }];
    });
  if (j.changes._created) details.unshift({ field: "_created", label: "작업을 만들었습니다", from: null, to: null });
  const wp = db.workPackages.find((w) => w.id === j.wpId)!;
  const project = db.projects.find((p) => p.id === wp.projectId)!;
  return clone({
    id: j.id,
    version: j.version,
    user: userRef(j.userId)!,
    notesHtml: j.notesHtml,
    isInternal: j.isInternal,
    details,
    cause: j.cause,
    createdAt: j.createdAt,
    updatedAt: j.updatedAt,
    canEdit: !!j.notesHtml && ((j.userId === user.id && can(user, project, "wp.edit_own_comments")) || can(user, project, "wp.edit_comments")),
  });
}
