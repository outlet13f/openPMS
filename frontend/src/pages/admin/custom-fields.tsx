// S34 관리: 사용자 정의 필드 — work package fields the company adds: format, types, where they are on,
// required/default, list options, length and pattern rules, order.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowUp, ListPlus, MoreHorizontal, Pencil, Plus, Trash2, X } from "lucide-react";
import type { AdminCustomField, CustomField, CustomFieldFormat, CustomFieldInput } from "@/api";
import { NOT_ON_PORTAL } from "@/api";
import { useAdminCustomFields, useAdminTypes, useCustomFieldMutations } from "@/api/admin-hooks";
import { Badge, TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { EmptyState } from "@/components/ui/feedback";
import { Dialog, Menu } from "@/components/ui/overlay";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { AdminTable, ConfirmDialog, OrderMenu, fieldErrorOf, tbodyRow, th, theadRow, useRunWithToast } from "@/features/admin/common";
import { CustomFieldInput as ValueInput } from "@/features/work-packages/custom-fields";
import { AdminGuard } from "./index";

const FORMATS: CustomFieldFormat[] = ["string", "text", "integer", "float", "list", "date", "bool", "user", "link"];

const EMPTY: CustomFieldInput = {
  name: "", format: "string", isRequired: false, isForAll: true, isFilterable: true, isMultiValue: false, defaultValue: null,
  minLength: null, maxLength: null, regexp: null, options: [], typeIds: [], portalVisible: false,
};

const toInput = (f: AdminCustomField): CustomFieldInput => ({
  name: f.name, format: f.format, isRequired: f.isRequired, isForAll: f.isForAll, isFilterable: f.isFilterable, isMultiValue: f.isMultiValue,
  defaultValue: f.defaultValue, minLength: f.minLength, maxLength: f.maxLength, regexp: f.regexp, options: f.options.map((o) => ({ id: o.id, value: o.value })),
  typeIds: f.typeIds, portalVisible: f.portalVisible,
});

const intOrNull = (v: string) => (v.trim() === "" ? null : Number(v));

function FieldDialog({ editing, onClose }: { editing: AdminCustomField | null; onClose: () => void }) {
  const { t } = useTranslation();
  const m = useCustomFieldMutations();
  const types = useAdminTypes().data ?? [];
  const runToast = useRunWithToast();
  const [v, setV] = useState<CustomFieldInput>(editing ? toInput(editing) : EMPTY);
  const mutation = editing ? m.update : m.create;
  const err = (k: string) => fieldErrorOf(mutation.error, k);
  const set = <K extends keyof CustomFieldInput>(k: K, val: CustomFieldInput[K]) => setV((x) => ({ ...x, [k]: val }));
  const refs = v.format === "list" || v.format === "user";
  const lengths = v.format === "string" || v.format === "text";
  // The default is picked against the options as typed; new options are named by their text until saved.
  const preview: CustomField = { ...v, id: editing?.id ?? "new", position: 0, options: v.options.map((o) => ({ id: o.id ?? o.value, value: o.value })) };

  const submit = async () => {
    const input: CustomFieldInput = { ...v, options: v.format === "list" ? v.options : [], isMultiValue: refs && v.isMultiValue };
    if (editing) {
      const { format: _format, ...patch } = input;
      if (await runToast(m.update.mutateAsync({ id: editing.id, patch }), t("app.saved"))) onClose();
    } else if (await runToast(m.create.mutateAsync(input), t("customFields.admin.created"))) onClose();
  };

  const moveOption = (i: number) =>
    set("options", v.options.map((o, j) => (j === i - 1 ? v.options[i] : j === i ? v.options[i - 1] : o)));

  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="lg"
      title={editing ? t("customFields.form.editTitle", { name: editing.name }) : t("customFields.form.newTitle")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" loading={mutation.isPending} onClick={() => void submit()}>
            {t("app.save")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={t("admin.name")} required error={err("name")}>
            <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => set("name", e.target.value)} />
          </Field>
          <Field label={t("customFields.admin.format")} required error={err("format")} hint={editing ? t("customFields.form.formatFixed") : undefined}>
            <Select
              value={v.format}
              disabled={!!editing}
              onValueChange={(f) => setV((x) => ({ ...x, format: f as CustomFieldFormat, defaultValue: null, isMultiValue: false }))}
              options={FORMATS.map((f) => ({ value: f, label: t(`customFields.formats.${f}`) }))}
            />
          </Field>
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium text-fg">{t("customFields.form.types")}</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2">
            {types.map((ty) => (
              <Checkbox
                key={ty.id}
                label={<TypeLabel name={ty.name} color={ty.color} className="text-sm" />}
                checked={v.typeIds.includes(ty.id)}
                onCheckedChange={(c) => set("typeIds", c === true ? [...v.typeIds, ty.id] : v.typeIds.filter((x) => x !== ty.id))}
              />
            ))}
          </div>
          {err("typeIds") && <p className="text-xs text-danger">{err("typeIds")}</p>}
        </fieldset>

        <div className="flex flex-col gap-3">
          <Checkbox label={t("customFields.form.isForAll")} description={t("customFields.form.isForAllHint")} checked={v.isForAll} onCheckedChange={(c) => set("isForAll", c === true)} />
          <Checkbox label={t("customFields.form.isRequired")} description={t("customFields.form.isRequiredHint")} checked={v.isRequired} onCheckedChange={(c) => set("isRequired", c === true)} />
          <Checkbox label={t("customFields.form.isFilterable")} checked={v.isFilterable} onCheckedChange={(c) => set("isFilterable", c === true)} />
          {/* user and version fields never go on the portal (the server stores them unmarked) */}
          {!NOT_ON_PORTAL.includes(v.format) && (
            <Checkbox label={t("customFields.form.portalVisible")} description={t("customFields.form.portalVisibleHint")} checked={!!v.portalVisible} onCheckedChange={(c) => set("portalVisible", c === true)} />
          )}
          {refs && <Checkbox label={t("customFields.form.isMultiValue")} checked={v.isMultiValue} onCheckedChange={(c) => setV((x) => ({ ...x, isMultiValue: c === true, defaultValue: null }))} />}
        </div>

        {v.format === "list" && (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium text-fg">{t("customFields.form.options")}</legend>
            <ol className="flex flex-col gap-2">
              {v.options.map((o, i) => (
                <li key={o.id ?? `new-${i}`} className="flex items-center gap-2">
                  <Input
                    aria-label={t("customFields.form.option", { n: i + 1 })}
                    value={o.value}
                    maxLength={255}
                    onChange={(e) => set("options", v.options.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))}
                  />
                  <Button variant="ghost" size="sm" iconOnly disabled={i === 0} aria-label={t("customFields.form.moveOptionUp", { n: i + 1 })} icon={<ArrowUp />} onClick={() => moveOption(i)} />
                  <Button variant="ghost" size="sm" iconOnly aria-label={t("customFields.form.removeOption", { n: i + 1 })} icon={<X />} onClick={() => set("options", v.options.filter((_, j) => j !== i))} />
                </li>
              ))}
            </ol>
            <div>
              <Button size="sm" icon={<ListPlus />} onClick={() => set("options", [...v.options, { value: "" }])}>
                {t("customFields.form.addOption")}
              </Button>
            </div>
            {err("options") && <p className="text-xs text-danger">{err("options")}</p>}
          </fieldset>
        )}

        {lengths && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label={t("customFields.form.minLength")} error={err("minLength")}>
              <Input type="number" min={0} value={v.minLength ?? ""} onChange={(e) => set("minLength", intOrNull(e.target.value))} />
            </Field>
            <Field label={t("customFields.form.maxLength")} error={err("maxLength")}>
              <Input type="number" min={1} value={v.maxLength ?? ""} onChange={(e) => set("maxLength", intOrNull(e.target.value))} />
            </Field>
            <Field label={t("customFields.form.regexp")} error={err("regexp")} hint={t("customFields.form.regexpHint")}>
              <Input value={v.regexp ?? ""} onChange={(e) => set("regexp", e.target.value || null)} />
            </Field>
          </div>
        )}

        {v.format !== "user" && (
          <Field label={t("customFields.form.defaultValue")} hint={t("customFields.form.defaultValueHint")} error={err("defaultValue")}>
            <ValueInput field={{ ...preview, isRequired: false }} label={t("customFields.form.defaultValue")} value={v.defaultValue} people={[]} onChange={(d) => set("defaultValue", d)} />
          </Field>
        )}
      </div>
    </Dialog>
  );
}

export function AdminCustomFieldsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("admin.sections.customFields"));
  const fields = useAdminCustomFields();
  const types = useAdminTypes().data ?? [];
  const m = useCustomFieldMutations();
  const runToast = useRunWithToast();
  const [dialog, setDialog] = useState<{ editing: AdminCustomField | null } | null>(null);
  const [removing, setRemoving] = useState<AdminCustomField | null>(null);

  return (
    <PageBody>
      <PageHeader
        title={t("admin.sections.customFields")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.customFields") }]}
        actions={
          <Button variant="primary" icon={<Plus />} onClick={() => setDialog({ editing: null })}>
            {t("customFields.admin.new")}
          </Button>
        }
      />
      <AdminGuard>
        <p className="mb-4 max-w-3xl text-sm text-fg-muted">{t("customFields.admin.intro")}</p>
        {fields.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : fields.isError ? (
          <ErrorState error={fields.error} onRetry={() => fields.refetch()} />
        ) : fields.data.length === 0 ? (
          <div className="rounded-md border border-border">
            <EmptyState icon={<ListPlus />} title={t("customFields.admin.empty")}>
              {t("customFields.admin.emptyBody")}
            </EmptyState>
          </div>
        ) : (
          <AdminTable minWidth={820}>
            <thead>
              <tr className={theadRow}>
                <th scope="col" className={`${th} w-12`}>
                  <span className="sr-only">{t("admin.order")}</span>
                </th>
                <th scope="col" className={th}>{t("admin.name")}</th>
                <th scope="col" className={th}>{t("customFields.admin.format")}</th>
                <th scope="col" className={th}>{t("customFields.admin.types")}</th>
                <th scope="col" className={th}>{t("customFields.admin.scope")}</th>
                <th scope="col" className={`${th} w-12`}>
                  <span className="sr-only">{t("app.more")}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {fields.data.map((f) => (
                <tr key={f.id} className={tbodyRow}>
                  <td className="px-2">
                    <OrderMenu items={fields.data} item={f} label={f.name} onReorder={(ids) => void runToast(m.reorder.mutateAsync(ids))} />
                  </td>
                  <td className="px-3">
                    <span className="font-medium text-fg">{f.name}</span>
                    {f.isRequired && (
                      <Badge tone="warning" className="ml-2">
                        {t("customFields.admin.required")}
                      </Badge>
                    )}
                  </td>
                  <td className="px-3 text-fg-muted">
                    {t(`customFields.formats.${f.format}`)}
                    {f.isMultiValue && ` · ${t("customFields.form.isMultiValue")}`}
                  </td>
                  <td className="px-3">
                    <span className="flex flex-wrap gap-x-3 gap-y-1">
                      {types.filter((ty) => f.typeIds.includes(ty.id)).map((ty) => (
                        <TypeLabel key={ty.id} name={ty.name} color={ty.color} />
                      ))}
                    </span>
                  </td>
                  <td className="px-3 text-fg-muted">{f.isForAll ? t("customFields.admin.forAll") : t("customFields.admin.projects", { count: f.projectCount })}</td>
                  <td className="px-2">
                    <Menu
                      align="end"
                      trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("admin.actionsFor", { name: f.name })} icon={<MoreHorizontal />} />}
                      items={[
                        { label: t("app.edit"), icon: <Pencil />, onSelect: () => setDialog({ editing: f }) },
                        { kind: "separator" },
                        { label: t("app.delete"), icon: <Trash2 />, danger: true, onSelect: () => setRemoving(f) },
                      ]}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </AdminTable>
        )}
      </AdminGuard>
      {dialog && <FieldDialog key={dialog.editing?.id ?? "new"} editing={dialog.editing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={t("customFields.admin.deleteTitle")}
        body={removing && t("customFields.admin.deleteBody", { name: removing.name })}
        confirm={t("app.delete")}
        pending={m.remove.isPending}
        onConfirm={async () => {
          if (removing) await runToast(m.remove.mutateAsync(removing.id), t("admin.deleted"));
          setRemoving(null);
        }}
      />
    </PageBody>
  );
}
