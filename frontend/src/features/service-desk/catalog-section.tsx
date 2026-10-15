// 프로젝트 설정 › 서비스 데스크 › 카탈로그 (ITSM 2단계): categories (add, rename, delete) and the items requesters pick
// on the portal, by category. An item has a description, a ticket type, a default priority, the group that gets the
// work, whether the portal shows it, its request form (custom fields on for this project and the item's type, in order,
// each required or not) and its approval steps (each step: the requester's 관리자 and/or the group's 책임자, whichever
// approves first). Read-only without service.manage; 422 messages under their fields.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, Eye, FolderPlus, LayoutList, MoreHorizontal, Pencil, Plus, Trash2, X } from "lucide-react";
import { isApiError, type ApprovalKind, type CatalogCategory, type CatalogItem, type CatalogItemInput, type Project, type ServiceCatalog } from "@/api";
import { useCatalogMutations, useFilterOptions, useServiceCatalog, useServiceDeskGroups } from "@/api/hooks";
import { Badge } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Banner, EmptyState } from "@/components/ui/feedback";
import { Field, Input } from "@/components/ui/field";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { RichText } from "@/components/domain/rich-text";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { AdminTable, ConfirmDialog, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { TICKET_TYPE_IDS } from "./settings-section";

const KINDS: ApprovalKind[] = ["requester_manager", "group_lead"];
const NONE = "none";
/** 서비스 요청 (server ItsmIds and the fake data layer's readable id): a new item's type */
const SERVICE_REQUEST_IDS = ["a3af2ccf-05c6-5b1c-84bc-4d815791e4eb", "t-service-request"];

/* ---------- 분류 ---------- */

function CategoryDialog({ project, editing, onClose }: { project: Project; editing: CatalogCategory | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useCatalogMutations(project.identifier);
  const runToast = useRunWithToast();
  const [name, setName] = useState(editing?.name ?? "");
  const mutation = editing ? m.updateCategory : m.createCategory;
  const save = async () => {
    const done = await runToast(editing ? m.updateCategory.mutateAsync({ id: editing.id, patch: { name } }) : m.createCategory.mutateAsync({ name }), t("serviceDesk.catalog.saved"));
    if (done) onClose();
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={editing ? t("serviceDesk.catalog.renameCategory") : t("serviceDesk.catalog.addCategory")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void save()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <Field label={t("admin.name")} required error={fieldErrorOf(mutation.error, "name")}>
        <Input data-autofocus value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      </Field>
    </Dialog>
  );
}

/* ---------- 항목 ---------- */

type FormField = { customFieldId: string; isRequired: boolean };

/** The item dialog: everything of one item, saved at once (fields and steps replace the lists). */
function ItemDialog({ project, catalog, editing, editable, onClose }: { project: Project; catalog: ServiceCatalog; editing: CatalogItem | null; editable: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const options = useFilterOptions(project.id).data;
  const groups = useServiceDeskGroups(project.identifier).data ?? [];
  const m = useCatalogMutations(project.identifier);
  const runToast = useRunWithToast();
  const types = (options?.types ?? []).filter((x) => TICKET_TYPE_IDS.has(x.id));
  const [name, setName] = useState(editing?.name ?? "");
  const [categoryId, setCategoryId] = useState(editing?.categoryId ?? NONE);
  const [descriptionHtml, setDescriptionHtml] = useState(editing?.descriptionHtml ?? "");
  const [typeId, setTypeId] = useState(editing?.typeId ?? "");
  const [priorityId, setPriorityId] = useState(editing?.defaultPriorityId ?? NONE);
  const [groupId, setGroupId] = useState(editing?.assignmentGroupId ?? NONE);
  const [isActive, setIsActive] = useState(editing?.isActive ?? true);
  const [fields, setFields] = useState<FormField[]>(() =>
    [...(editing?.fields ?? [])].sort((a, b) => a.position - b.position).map((f) => ({ customFieldId: f.customFieldId, isRequired: f.isRequired })),
  );
  // one entry per step, in order; each the kinds ticked
  const [steps, setSteps] = useState<ApprovalKind[][]>(() => {
    const out: ApprovalKind[][] = [];
    for (const s of editing?.steps ?? []) (out[s.stepNo - 1] ??= []).push(s.kind);
    return Array.from(out, (k) => k ?? []);
  });
  // a new item is a 서비스 요청 when the project has it, else the first ticket type it has
  const shownType = typeId || types.find((x) => SERVICE_REQUEST_IDS.includes(x.id))?.id || types[0]?.id || "";
  const mutation = editing ? m.updateItem : m.createItem;
  const err = (f: string) => fieldErrorOf(mutation.error, f);

  // fields on for this project (and its subprojects, the server checks) and the item's type
  const allFields = options?.customFields ?? [];
  const fieldName = (id: string) => allFields.find((f) => f.id === id)?.name ?? id;
  const pickable = allFields.filter((f) => f.typeIds.includes(shownType) && !fields.some((x) => x.customFieldId === f.id));

  // the steps as sent: empty steps dropped, the rest numbered from 1 (errors come back by the index sent)
  const nonEmpty = steps.map((kinds, i) => ({ kinds, i })).filter((s) => s.kinds.length > 0);
  const sentSteps = nonEmpty.flatMap((s, n) => KINDS.filter((k) => s.kinds.includes(k)).map((kind) => ({ stepNo: n + 1, kind, row: s.i })));
  const stepError = (row: number) =>
    sentSteps
      .map((s, idx) => (s.row === row ? (err(`steps[${idx}].kind`) ?? err(`steps[${idx}].stepNo`)) : undefined))
      .find(Boolean);

  const input: CatalogItemInput = {
    name,
    categoryId: categoryId === NONE ? null : categoryId,
    descriptionHtml,
    typeId: shownType,
    defaultPriorityId: priorityId === NONE ? null : priorityId,
    assignmentGroupId: groupId === NONE ? null : groupId,
    isActive,
    fields: fields.map((f, i) => ({ customFieldId: f.customFieldId, position: i + 1, isRequired: f.isRequired })),
    steps: sentSteps.map(({ stepNo, kind }) => ({ stepNo, kind })),
  };
  // errors under no field shown here still show (as a banner), never vanish
  const placed = (f: string) => ["name", "categoryId", "descriptionHtml", "typeId", "defaultPriorityId", "assignmentGroupId", "fields", "steps"].includes(f) || /^(fields|steps)\[\d+\]\./.test(f);
  const others = isApiError(mutation.error) ? mutation.error.fieldErrors.filter((f) => !placed(f.field)) : [];

  const save = async () => {
    const done = await runToast(editing ? m.updateItem.mutateAsync({ id: editing.id, patch: input }) : m.createItem.mutateAsync(input), t("serviceDesk.catalog.saved"));
    if (done) onClose();
  };
  const move = (i: number, by: number) =>
    setFields((cur) => {
      const next = [...cur];
      const [x] = next.splice(i, 1);
      next.splice(i + by, 0, x);
      return next;
    });

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="lg"
      title={editing ? (editable ? t("serviceDesk.catalog.editItem") : editing.name) : t("serviceDesk.catalog.addItem")}
      footer={
        editable ? (
          <>
            <Button onClick={onClose}>{t("app.cancel")}</Button>
            <Button variant="primary" loading={mutation.isPending} onClick={() => void save()}>
              {t("app.save")}
            </Button>
          </>
        ) : (
          <Button onClick={onClose}>{t("app.close")}</Button>
        )
      }
    >
      <div className="flex flex-col gap-5">
        {others.length > 0 && (
          <Banner tone="danger">
            {others.map((f) => (
              <p key={f.field}>{f.message}</p>
            ))}
          </Banner>
        )}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={t("serviceDesk.catalog.itemName")} required error={err("name")}>
            <Input data-autofocus value={name} maxLength={200} disabled={!editable} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label={t("serviceDesk.catalog.categories")} error={err("categoryId")}>
            <Select
              value={categoryId}
              disabled={!editable}
              onValueChange={setCategoryId}
              options={[{ value: NONE, label: t("serviceDesk.catalog.noCategory") }, ...catalog.categories.map((c) => ({ value: c.id, label: c.name }))]}
            />
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          {editable ? (
            <Field label={t("serviceDesk.catalog.description")} error={err("descriptionHtml")}>
              <RichTextEditor value={descriptionHtml} onChange={setDescriptionHtml} label={t("serviceDesk.catalog.description")} minRows={3} invalid={!!err("descriptionHtml")} />
            </Field>
          ) : (
            <>
              <span className="text-sm font-medium text-fg">{t("serviceDesk.catalog.description")}</span>
              {descriptionHtml ? <RichText html={descriptionHtml} className="text-sm" /> : <span className="text-sm text-fg-muted">-</span>}
            </>
          )}
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label={t("serviceDesk.catalog.type")} error={err("typeId")}>
            <Select value={shownType} disabled={!editable} onValueChange={setTypeId} options={types.map((x) => ({ value: x.id, label: x.name }))} />
          </Field>
          <Field label={t("serviceDesk.catalog.defaultPriority")} error={err("defaultPriorityId")}>
            <Select
              value={priorityId}
              disabled={!editable}
              onValueChange={setPriorityId}
              options={[{ value: NONE, label: t("serviceDesk.catalog.noPriority") }, ...(options?.priorities ?? []).map((x) => ({ value: x.id, label: x.name }))]}
            />
          </Field>
          <Field label={t("serviceDesk.catalog.assignmentGroup")} error={err("assignmentGroupId")}>
            <Select value={groupId} disabled={!editable} onValueChange={setGroupId} options={[{ value: NONE, label: t("serviceDesk.noGroup") }, ...groups.map((g) => ({ value: g.id, label: g.name }))]} />
          </Field>
        </div>
        <Checkbox label={t("serviceDesk.catalog.portalVisible")} checked={isActive} disabled={!editable} onCheckedChange={(c) => setIsActive(c === true)} />

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-fg">{t("serviceDesk.catalog.fields")}</legend>
          <p className="text-xs text-fg-muted">{t("serviceDesk.catalog.fieldsHint")}</p>
          {fields.length === 0 ? (
            <p className="text-sm text-fg-muted">{t("serviceDesk.catalog.noFields")}</p>
          ) : (
            <ol className="flex flex-col divide-y divide-border rounded-md border border-border">
              {fields.map((f, i) => {
                const label = fieldName(f.customFieldId);
                const message = err(`fields[${i}].customFieldId`) ?? err(`fields[${i}].isRequired`);
                return (
                  <li key={f.customFieldId} className="flex flex-col gap-1 px-3 py-1.5">
                    <div className="flex flex-wrap items-center gap-3">
                      <span className="min-w-0 flex-1 text-sm text-fg">{label}</span>
                      <Checkbox
                        label={t("serviceDesk.catalog.required")}
                        aria-label={`${label} ${t("serviceDesk.catalog.required")}`}
                        checked={f.isRequired}
                        disabled={!editable}
                        onCheckedChange={(c) => setFields((cur) => cur.map((x) => (x.customFieldId === f.customFieldId ? { ...x, isRequired: c === true } : x)))}
                      />
                      {editable && (
                        <span className="inline-flex gap-0.5">
                          <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("serviceDesk.catalog.moveUp", { name: label })} disabled={i === 0} onClick={() => move(i, -1)} />
                          <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("serviceDesk.catalog.moveDown", { name: label })} disabled={i === fields.length - 1} onClick={() => move(i, 1)} />
                          <Button variant="ghost" size="sm" iconOnly icon={<X />} aria-label={t("serviceDesk.catalog.removeField", { name: label })} onClick={() => setFields((cur) => cur.filter((x) => x.customFieldId !== f.customFieldId))} />
                        </span>
                      )}
                    </div>
                    {message && <p className="text-xs text-danger">{message}</p>}
                  </li>
                );
              })}
            </ol>
          )}
          {err("fields") && <p className="text-xs text-danger">{err("fields")}</p>}
          {editable && pickable.length > 0 && (
            <Select
              size="sm"
              className="w-60"
              aria-label={t("serviceDesk.catalog.addField")}
              placeholder={t("serviceDesk.catalog.addField")}
              value=""
              onValueChange={(id) => id && setFields((cur) => [...cur, { customFieldId: id, isRequired: false }])}
              options={pickable.map((f) => ({ value: f.id, label: f.name }))}
            />
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-fg">{t("serviceDesk.catalog.steps")}</legend>
          <p className="text-xs text-fg-muted">
            {t("serviceDesk.catalog.stepHint")} {t("serviceDesk.catalog.noSteps")}
          </p>
          {steps.length > 0 && (
            <ol className="flex flex-col divide-y divide-border rounded-md border border-border">
              {steps.map((kinds, i) => {
                const n = i + 1;
                const message = stepError(i);
                return (
                  <li key={i} className="flex flex-col gap-1 px-3 py-1.5">
                    <div role="group" aria-label={t("serviceDesk.catalog.step", { n })} className="flex flex-wrap items-center gap-4">
                      <span className="w-14 text-sm font-medium text-fg">{t("serviceDesk.catalog.step", { n })}</span>
                      {KINDS.map((k) => (
                        <Checkbox
                          key={k}
                          label={t(`serviceDesk.approvalKinds.${k}`)}
                          aria-label={`${t("serviceDesk.catalog.step", { n })} ${t(`serviceDesk.approvalKinds.${k}`)}`}
                          checked={kinds.includes(k)}
                          disabled={!editable}
                          onCheckedChange={(c) => setSteps((cur) => cur.map((s, j) => (j === i ? (c === true ? KINDS.filter((x) => x === k || s.includes(x)) : s.filter((x) => x !== k)) : s)))}
                        />
                      ))}
                      {editable && (
                        <Button
                          variant="ghost"
                          size="sm"
                          iconOnly
                          className="ml-auto"
                          icon={<Trash2 />}
                          aria-label={t("serviceDesk.catalog.removeStep", { n })}
                          onClick={() => setSteps((cur) => cur.filter((_, j) => j !== i))}
                        />
                      )}
                    </div>
                    {message && <p className="text-xs text-danger">{message}</p>}
                  </li>
                );
              })}
            </ol>
          )}
          {err("steps") && <p className="text-xs text-danger">{err("steps")}</p>}
          {editable && (
            <div>
              <Button size="sm" icon={<Plus />} onClick={() => setSteps((cur) => [...cur, ["requester_manager"]])}>
                {t("serviceDesk.catalog.addStep")}
              </Button>
            </div>
          )}
        </fieldset>
      </div>
    </Dialog>
  );
}

/** "1단계 관리자 · 2단계 그룹 책임자" for the list. */
function stepsText(item: CatalogItem, t: (k: string, o?: Record<string, unknown>) => string) {
  if (item.steps.length === 0) return t("serviceDesk.catalog.noStepsShort");
  const byStep = new Map<number, ApprovalKind[]>();
  for (const s of item.steps) byStep.set(s.stepNo, [...(byStep.get(s.stepNo) ?? []), s.kind]);
  return [...byStep.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([n, kinds]) => `${t("serviceDesk.catalog.step", { n })} ${KINDS.filter((k) => kinds.includes(k)).map((k) => t(`serviceDesk.approvalKinds.${k}`)).join("·")}`)
    .join(" → ");
}

function Catalog({ project, catalog }: { project: Project; catalog: ServiceCatalog }) {
  const { t } = useTranslation();
  const tt = t as unknown as (k: string, o?: Record<string, unknown>) => string;
  const options = useFilterOptions(project.id).data;
  const groups = useServiceDeskGroups(project.identifier).data ?? [];
  const m = useCatalogMutations(project.identifier);
  const runToast = useRunWithToast();
  const editable = can(project, "service.manage");
  const [categoryDialog, setCategoryDialog] = useState<{ editing: CatalogCategory | null } | null>(null);
  const [itemDialog, setItemDialog] = useState<{ editing: CatalogItem | null } | null>(null);
  const [removingCategory, setRemovingCategory] = useState<CatalogCategory | null>(null);
  const [removingItem, setRemovingItem] = useState<CatalogItem | null>(null);
  const typeName = (id: string) => options?.types.find((x) => x.id === id)?.name ?? "";
  const groupName = (id: string | null) => (id ? (groups.find((g) => g.id === id)?.name ?? "") : t("serviceDesk.noGroup"));
  const sections = [
    ...catalog.categories.map((c) => ({ id: c.id, name: c.name, items: catalog.items.filter((i) => i.categoryId === c.id) })),
    { id: NONE, name: t("serviceDesk.catalog.noCategory"), items: catalog.items.filter((i) => !i.categoryId || !catalog.categories.some((c) => c.id === i.categoryId)) },
  ].filter((s) => s.id !== NONE || s.items.length > 0);

  return (
    <div className="flex flex-col gap-6">
      {!editable && <Banner tone="info">{t("serviceDesk.catalog.readOnly")}</Banner>}

      <section aria-labelledby="sd-catalog-categories-h" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id="sd-catalog-categories-h" className="text-base font-semibold text-fg">
            {t("serviceDesk.catalog.categories")}
          </h3>
          {editable && (
            <Button size="sm" icon={<FolderPlus />} onClick={() => setCategoryDialog({ editing: null })}>
              {t("serviceDesk.catalog.addCategory")}
            </Button>
          )}
        </div>
        {catalog.categories.length === 0 ? (
          <p className="text-sm text-fg-muted">{t("serviceDesk.catalog.noCategories")}</p>
        ) : (
          <AdminTable minWidth={420}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={`${th} text-right`}>{t("serviceDesk.catalog.items")}</th>
                {editable && (
                  <th scope="col" className={`${th} w-12`}>
                    <span className="sr-only">{t("app.more")}</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {catalog.categories.map((c) => (
                <tr key={c.id} className={tbodyRow}>
                  <td className="px-3 text-fg">{c.name}</td>
                  <td className="px-3 text-right tabular-nums">{catalog.items.filter((i) => i.categoryId === c.id).length}</td>
                  {editable && (
                    <td className="px-2">
                      <Menu
                        align="end"
                        trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: c.name })} icon={<MoreHorizontal />} />}
                        items={[
                          { label: t("serviceDesk.catalog.renameCategory"), icon: <Pencil />, onSelect: () => setCategoryDialog({ editing: c }) },
                          { kind: "separator" },
                          { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemovingCategory(c) },
                        ]}
                      />
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </section>

      <section aria-labelledby="sd-catalog-items-h" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id="sd-catalog-items-h" className="text-base font-semibold text-fg">
            {t("serviceDesk.catalog.items")}
          </h3>
          {editable && (
            <Button size="sm" icon={<Plus />} onClick={() => setItemDialog({ editing: null })}>
              {t("serviceDesk.catalog.addItem")}
            </Button>
          )}
        </div>
        {catalog.items.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState compact icon={<LayoutList />} title={t("serviceDesk.catalog.noItems")} />
          </div>
        ) : (
          sections.map((s) => (
            <div key={s.id} className="flex flex-col gap-2">
              <h4 className="text-sm font-semibold text-fg">{s.name}</h4>
              {s.items.length === 0 ? (
                <p className="text-sm text-fg-muted">{t("serviceDesk.catalog.noItems")}</p>
              ) : (
                <AdminTable minWidth={720}>
                  <thead>
                    <tr className={theadRow}>
                      <th scope="col" className={th}>{t("serviceDesk.catalog.itemName")}</th>
                      <th scope="col" className={th}>{t("serviceDesk.catalog.type")}</th>
                      <th scope="col" className={th}>{t("serviceDesk.catalog.assignmentGroup")}</th>
                      <th scope="col" className={th}>{t("serviceDesk.catalog.steps")}</th>
                      <th scope="col" className={th}>{t("serviceDesk.catalog.portalVisible")}</th>
                      <th scope="col" className={`${th} w-12`}>
                        <span className="sr-only">{t("app.more")}</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {s.items.map((i) => (
                      <tr key={i.id} className={`${tbodyRow} align-top`}>
                        <td className="px-3 py-1.5 font-medium text-fg">{i.name}</td>
                        <td className="px-3 py-1.5 text-fg-muted">{typeName(i.typeId)}</td>
                        <td className="px-3 py-1.5 text-fg-muted">{groupName(i.assignmentGroupId)}</td>
                        <td className="px-3 py-1.5 text-fg-muted">{stepsText(i, tt)}</td>
                        <td className="px-3 py-1.5">{i.isActive ? <Badge tone="success">{t("serviceDesk.catalog.portalVisible")}</Badge> : <Badge>{t("serviceDesk.catalog.hidden")}</Badge>}</td>
                        <td className="px-2 py-1">
                          {editable ? (
                            <Menu
                              align="end"
                              trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: i.name })} icon={<MoreHorizontal />} />}
                              items={[
                                { label: t("app.edit"), icon: <Pencil />, onSelect: () => setItemDialog({ editing: i }) },
                                { kind: "separator" },
                                { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemovingItem(i) },
                              ]}
                            />
                          ) : (
                            <Button variant="ghost" size="sm" iconOnly aria-label={i.name} icon={<Eye />} onClick={() => setItemDialog({ editing: i })} />
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </AdminTable>
              )}
            </div>
          ))
        )}
      </section>

      {categoryDialog && <CategoryDialog project={project} editing={categoryDialog.editing} onClose={() => setCategoryDialog(null)} />}
      {itemDialog && <ItemDialog project={project} catalog={catalog} editing={itemDialog.editing} editable={editable} onClose={() => setItemDialog(null)} />}
      <ConfirmDialog
        open={!!removingCategory}
        onOpenChange={(o) => !o && setRemovingCategory(null)}
        title={removingCategory ? t("serviceDesk.catalog.deleteCategoryTitle", { name: removingCategory.name }) : ""}
        body={t("serviceDesk.catalog.deleteCategoryBody")}
        confirm={t("app.delete")}
        pending={m.deleteCategory.isPending}
        onConfirm={async () => {
          if (removingCategory) await runToast(m.deleteCategory.mutateAsync(removingCategory.id), t("admin.deleted"));
          setRemovingCategory(null);
        }}
      />
      <ConfirmDialog
        open={!!removingItem}
        onOpenChange={(o) => !o && setRemovingItem(null)}
        title={removingItem ? t("serviceDesk.catalog.deleteItemTitle", { name: removingItem.name }) : ""}
        body={t("serviceDesk.catalog.deleteItemBody")}
        confirm={t("app.delete")}
        pending={m.deleteItem.isPending}
        onConfirm={async () => {
          if (removingItem) await runToast(m.deleteItem.mutateAsync(removingItem.id), t("admin.deleted"));
          setRemovingItem(null);
        }}
      />
    </div>
  );
}

export function CatalogSection() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const q = useServiceCatalog(project.identifier);
  return (
    <section aria-labelledby="sd-catalog-h" className="flex flex-col gap-4">
      <div>
        <h2 id="sd-catalog-h" className="text-lg text-fg">
          {t("serviceDesk.catalog.title")}
        </h2>
        <p className="mt-1 text-sm text-fg-muted">{t("serviceDesk.catalog.hint")}</p>
      </div>
      {q.isPending ? <Skeleton className="h-40 w-full" /> : q.isError ? <ErrorState error={q.error} onRetry={() => q.refetch()} /> : <Catalog project={project} catalog={q.data} />}
    </section>
  );
}
