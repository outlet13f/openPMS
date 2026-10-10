// S28 관리: 유형 양식 구성 — which attributes the detail shows for a type, in which groups and order, which are
// hidden, and the description new items of the type start with.
import { useState } from "react";
import { useParams } from "react-router";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, EyeOff, FolderInput, MoreHorizontal, Plus, RotateCcw, Save, Trash2 } from "lucide-react";
import type { AdminCustomField, AdminType, TypeForm } from "@/api";
import { useAdminCustomFields, useAdminTypes, useTypeMutations } from "@/api/admin-hooks";
import { BUILT_IN_ATTRIBUTES, DEFAULT_TYPE_FORM } from "@/api/type-form";
import { TypeLabel } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Banner } from "@/components/ui/feedback";
import { Input } from "@/components/ui/field";
import { Menu } from "@/components/ui/overlay";
import { Skeleton } from "@/components/ui/spinner";
import { RichTextEditor } from "@/components/domain/rich-text-editor";
import { ErrorState, NotFound, PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { fieldErrorOf, useRunWithToast } from "@/features/admin/common";
import { useUnsavedWarning } from "@/lib/use-unsaved-warning";
import { AdminGuard } from "./index";

const HIDDEN = -1;
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function Editor({ type, fields }: { type: AdminType; fields: AdminCustomField[] }) {
  const { t } = useTranslation();
  const m = useTypeMutations();
  const runToast = useRunWithToast();
  const saved = type.form ?? DEFAULT_TYPE_FORM;
  const [form, setForm] = useState<TypeForm>(saved);
  const [template, setTemplate] = useState(type.descriptionTemplateHtml);
  const [templateKey, setTemplateKey] = useState(0);
  const dirty = !same(form, saved) || template !== type.descriptionTemplateHtml;
  useUnsavedWarning(dirty);

  const label = (a: string) => {
    if (!a.startsWith("cf:")) return t(`wp.fields.${a as (typeof BUILT_IN_ATTRIBUTES)[number]}`);
    return fields.find((f) => f.id === a.slice(3))?.name ?? t("customFields.unknown");
  };
  const placed = new Set([...form.groups.flatMap((g) => g.attributes), ...form.hidden]);
  // custom fields placed nowhere show in the automatic "추가 정보" group on the detail
  const automatic = fields.filter((f) => !placed.has(`cf:${f.id}`)).map((f) => `cf:${f.id}`);

  const without = (f: TypeForm, a: string): TypeForm => ({ groups: f.groups.map((g) => ({ ...g, attributes: g.attributes.filter((x) => x !== a) })), hidden: f.hidden.filter((x) => x !== a) });
  /** Moves an attribute to the end of group `to` (or hidden). */
  const moveTo = (a: string, to: number) =>
    setForm((f) => {
      const n = without(f, a);
      return to === HIDDEN ? { ...n, hidden: [...n.hidden, a] } : { ...n, groups: n.groups.map((g, i) => (i === to ? { ...g, attributes: [...g.attributes, a] } : g)) };
    });
  const shift = (gi: number, ai: number, by: number) =>
    setForm((f) => ({
      ...f,
      groups: f.groups.map((g, i) => {
        if (i !== gi) return g;
        const list = [...g.attributes];
        [list[ai], list[ai + by]] = [list[ai + by], list[ai]];
        return { ...g, attributes: list };
      }),
    }));
  const shiftGroup = (gi: number, by: number) =>
    setForm((f) => {
      const list = [...f.groups];
      [list[gi], list[gi + by]] = [list[gi + by], list[gi]];
      return { ...f, groups: list };
    });
  const rename = (gi: number, name: string) => setForm((f) => ({ ...f, groups: f.groups.map((g, i) => (i === gi ? { ...g, name } : g)) }));
  // a removed group's attributes go to hidden, so nothing silently comes back elsewhere
  const removeGroup = (gi: number) => setForm((f) => ({ groups: f.groups.filter((_, i) => i !== gi), hidden: [...f.hidden, ...f.groups[gi].attributes] }));
  const addGroup = () =>
    setForm((f) => {
      let n = 1;
      while (f.groups.some((g) => g.name === t("typeForm.newGroupName", { n }))) n++;
      return { ...f, groups: [...f.groups, { name: t("typeForm.newGroupName", { n }), attributes: [] }] };
    });

  const moveItems = (a: string, from: number) => [
    ...form.groups.flatMap((g, i) => (i === from ? [] : [{ label: t("typeForm.moveTo", { group: g.name }), icon: <FolderInput />, onSelect: () => moveTo(a, i) }])),
    ...(from === HIDDEN ? [] : [{ kind: "separator" as const }, { label: t("typeForm.hide"), icon: <EyeOff />, onSelect: () => moveTo(a, HIDDEN) }]),
  ];
  const attrMenu = (a: string, from: number) => (
    <Menu align="end" trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("typeForm.moveFor", { name: label(a) })} icon={<MoreHorizontal />} />} items={moveItems(a, from)} />
  );

  const save = async () => {
    const r = await runToast(
      m.update.mutateAsync({ id: type.id, patch: { form: same(form, DEFAULT_TYPE_FORM) ? null : form, descriptionTemplateHtml: template } }),
      t("app.saved"),
    );
    if (r) {
      setForm(r.form ?? DEFAULT_TYPE_FORM);
      setTemplate(r.descriptionTemplateHtml); // as the server cleaned it
    }
  };
  const formError = fieldErrorOf(m.update.error, "form");

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <section aria-labelledby="tf-groups" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="tf-groups" className="text-lg text-fg">{t("typeForm.groups")}</h2>
          <div className="flex gap-2">
            <Button variant="secondary" size="sm" icon={<RotateCcw />} onClick={() => setForm(DEFAULT_TYPE_FORM)} disabled={same(form, DEFAULT_TYPE_FORM)}>
              {t("typeForm.reset")}
            </Button>
            <Button variant="secondary" size="sm" icon={<Plus />} onClick={addGroup} disabled={form.groups.length >= 20}>
              {t("typeForm.addGroup")}
            </Button>
          </div>
        </div>
        <p className="text-sm text-fg-muted">{t("typeForm.groupsHint")}</p>
        {formError && <Banner tone="danger">{formError}</Banner>}
        {form.groups.length === 0 && <p className="rounded-md border border-dashed border-border px-3 py-4 text-sm text-fg-muted">{t("typeForm.noGroups")}</p>}
        {form.groups.map((g, gi) => (
          <section key={gi} aria-label={t("typeForm.groupLabel", { name: g.name || t("typeForm.unnamed") })} className="rounded-md border border-border">
            <div className="flex flex-wrap items-center gap-2 border-b border-border-muted bg-surface px-3 py-2">
              <Input aria-label={t("typeForm.groupName")} value={g.name} maxLength={60} onChange={(e) => rename(gi, e.target.value)} className="min-w-0 flex-1 basis-40" />
              <div className="flex gap-1">
                <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("typeForm.groupUp", { name: g.name })} disabled={gi === 0} onClick={() => shiftGroup(gi, -1)} />
                <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("typeForm.groupDown", { name: g.name })} disabled={gi === form.groups.length - 1} onClick={() => shiftGroup(gi, 1)} />
                <Button variant="ghost" size="sm" iconOnly icon={<Trash2 />} aria-label={t("typeForm.removeGroup", { name: g.name })} onClick={() => removeGroup(gi)} />
              </div>
            </div>
            {g.attributes.length === 0 ? (
              <p className="px-3 py-3 text-sm text-fg-muted">{t("typeForm.emptyGroup")}</p>
            ) : (
              <ul className="divide-y divide-border-muted">
                {g.attributes.map((a, ai) => (
                  <li key={a} className="flex items-center gap-2 px-3 py-1.5">
                    <span className="min-w-0 flex-1 truncate text-sm text-fg">
                      {label(a)}
                      {a.startsWith("cf:") && <span className="ml-2 text-xs text-fg-muted">{t("typeForm.customField")}</span>}
                    </span>
                    <Button variant="ghost" size="sm" iconOnly icon={<ArrowUp />} aria-label={t("typeForm.up", { name: label(a) })} disabled={ai === 0} onClick={() => shift(gi, ai, -1)} />
                    <Button variant="ghost" size="sm" iconOnly icon={<ArrowDown />} aria-label={t("typeForm.down", { name: label(a) })} disabled={ai === g.attributes.length - 1} onClick={() => shift(gi, ai, 1)} />
                    {attrMenu(a, gi)}
                  </li>
                ))}
              </ul>
            )}
          </section>
        ))}
      </section>

      {automatic.length > 0 && (
        <section aria-labelledby="tf-auto" className="flex flex-col gap-2">
          <h2 id="tf-auto" className="text-lg text-fg">{t("typeForm.automatic")}</h2>
          <p className="text-sm text-fg-muted">{t("typeForm.automaticHint")}</p>
          <ul className="divide-y divide-border-muted rounded-md border border-border">
            {automatic.map((a) => (
              <li key={a} className="flex items-center gap-2 px-3 py-1.5">
                <span className="min-w-0 flex-1 truncate text-sm text-fg">{label(a)}</span>
                <Menu
                  align="end"
                  trigger={<Button variant="ghost" size="sm" iconOnly aria-label={t("typeForm.moveFor", { name: label(a) })} icon={<MoreHorizontal />} />}
                  items={[...form.groups.map((g, i) => ({ label: t("typeForm.moveTo", { group: g.name }), icon: <FolderInput />, onSelect: () => moveTo(a, i) })), { kind: "separator" as const }, { label: t("typeForm.hide"), icon: <EyeOff />, onSelect: () => moveTo(a, HIDDEN) }]}
                />
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="tf-hidden" className="flex flex-col gap-2">
        <h2 id="tf-hidden" className="text-lg text-fg">{t("typeForm.hidden")}</h2>
        <p className="text-sm text-fg-muted">{t("typeForm.hiddenHint")}</p>
        {form.hidden.length === 0 ? (
          <p className="rounded-md border border-dashed border-border px-3 py-3 text-sm text-fg-muted">{t("typeForm.noHidden")}</p>
        ) : (
          <ul className="divide-y divide-border-muted rounded-md border border-border">
            {form.hidden.map((a) => (
              <li key={a} className="flex items-center gap-2 px-3 py-1.5">
                <span className="min-w-0 flex-1 truncate text-sm text-fg-muted">{label(a)}</span>
                {form.groups.length > 0 && attrMenu(a, HIDDEN)}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="tf-template" className="flex flex-col gap-2">
        <h2 id="tf-template" className="text-lg text-fg">{t("typeForm.template")}</h2>
        <p className="text-sm text-fg-muted">{t("typeForm.templateHint")}</p>
        <RichTextEditor value={template} resetKey={templateKey} onChange={setTemplate} label={t("typeForm.template")} minRows={5} />
      </section>

      <div className="flex flex-wrap gap-2">
        <Button variant="primary" icon={<Save />} disabled={!dirty} loading={m.update.isPending} onClick={() => void save()}>
          {t("app.save")}
        </Button>
        <Button
          variant="ghost"
          disabled={!dirty}
          onClick={() => {
            setForm(saved);
            setTemplate(type.descriptionTemplateHtml);
            setTemplateKey((k) => k + 1);
          }}
        >
          {t("typeForm.discard")}
        </Button>
      </div>
    </div>
  );
}

export function AdminTypeFormPage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const types = useAdminTypes();
  const fields = useAdminCustomFields();
  const type = types.data?.find((x) => x.id === id);
  useDocumentTitle(type ? t("typeForm.title", { name: type.name }) : t("admin.sections.types"));
  return (
    <PageBody>
      <PageHeader
        title={type ? <span className="flex items-center gap-2">{t("typeForm.heading")} <TypeLabel name={type.name} color={type.color} className="text-sm" /></span> : t("typeForm.heading")}
        breadcrumbs={[{ label: t("nav.admin"), href: "/admin" }, { label: t("admin.sections.types"), href: "/admin/types" }, { label: type?.name ?? "…" }]}
      />
      <AdminGuard>
        {types.isPending || fields.isPending ? (
          <Skeleton className="h-64 w-full" />
        ) : types.isError || fields.isError ? (
          <ErrorState error={types.error ?? fields.error} onRetry={() => (types.refetch(), fields.refetch())} />
        ) : !type ? (
          <NotFound />
        ) : (
          <Editor key={type.id} type={type} fields={fields.data} />
        )}
      </AdminGuard>
    </PageBody>
  );
}

