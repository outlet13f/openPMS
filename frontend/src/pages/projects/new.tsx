// S04 새 프로젝트
import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useTranslation } from "react-i18next";
import { Copy, FilePlus2 } from "lucide-react";
import { isApiError } from "@/api";
import { useCreateProject } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox, RadioCards } from "@/components/ui/choice";
import { Field, Input, Textarea } from "@/components/ui/field";
import { Banner, useToast } from "@/components/ui/feedback";
import { PageBody, PageHeader, useDocumentTitle } from "@/app/page";
import { textToHtml } from "@/lib/text";

const slug = (name: string) =>
  /^[\x20-\x7e]+$/.test(name)
    ? name.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").replace(/^[^a-z]+/, "").slice(0, 100)
    : "";

export function NewProjectPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("projectNew.title"));
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreateProject();
  const [mode, setMode] = useState("blank");
  const [name, setName] = useState("");
  const [identifier, setIdentifier] = useState("");
  const [touchedId, setTouchedId] = useState(false);
  const [description, setDescription] = useState("");
  const [isPublic, setIsPublic] = useState(false);
  const fieldErr = (f: string) => (isApiError(create.error) ? create.error.fieldErrors.find((e) => e.field === f)?.message : undefined);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const p = await create.mutateAsync({ name, identifier, descriptionHtml: textToHtml(description), isPublic });
      toast({ tone: "success", title: t("projectNew.created"), description: p.name });
      navigate(`/projects/${p.identifier}`);
    } catch (err) {
      const first = isApiError(err) ? err.fieldErrors[0]?.field : undefined;
      if (first) document.getElementById(`np-${first}`)?.focus();
    }
  };

  return (
    <PageBody>
      <PageHeader title={t("projectNew.title")} breadcrumbs={[{ label: t("projects.title"), href: "/projects" }, { label: t("projectNew.title") }]} />
      <form onSubmit={submit} noValidate className="flex max-w-form flex-col gap-5">
        <RadioCards
          aria-label={t("projectNew.title")}
          value={mode}
          onValueChange={setMode}
          options={[
            { value: "blank", title: t("projectNew.blank"), description: t("projectNew.blankBody"), icon: <FilePlus2 /> },
            { value: "template", title: t("projectNew.template"), description: t("projectNew.templateBody"), icon: <Copy />, disabled: true },
          ]}
        />
        {isApiError(create.error) && !create.error.fieldErrors.length && <Banner tone="danger">{create.error.message}</Banner>}
        <Field label={t("projectNew.name")} required error={fieldErr("name")}>
          <Input
            id="np-name"
            value={name}
            maxLength={255}
            onChange={(e) => {
              setName(e.target.value);
              if (!touchedId) setIdentifier(slug(e.target.value));
            }}
          />
        </Field>
        <Field label={t("projectNew.identifier")} required hint={t("projectNew.identifierHint")} error={fieldErr("identifier")}>
          <Input
            id="np-identifier"
            value={identifier}
            maxLength={100}
            spellCheck={false}
            onChange={(e) => {
              setTouchedId(true);
              setIdentifier(e.target.value.toLowerCase());
            }}
          />
        </Field>
        <Field label={t("projectNew.description")}>
          <Textarea value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <Checkbox label={t("projectNew.isPublic")} description={t("projectNew.isPublicHint")} checked={isPublic} onCheckedChange={(v) => setIsPublic(v === true)} />
        <div className="flex gap-2">
          <Button type="submit" variant="primary" loading={create.isPending}>
            {t("app.create")}
          </Button>
          <Button asChild>
            <Link to="/projects">{t("app.cancel")}</Link>
          </Button>
        </div>
      </form>
    </PageBody>
  );
}
