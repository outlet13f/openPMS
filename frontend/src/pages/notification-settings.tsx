// S20 알림 설정 (/my/notifications): what I am notified about — participation, date alerts, other activity —
// by default and per project, and how mail reaches me (mentions right away, the daily digest, pausing date alerts).
import { useState, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { BellOff, FolderPlus, Plus, Save, Trash2, X } from "lucide-react";
import { isApiError, type AlertDays, type NotificationSetting, type Preferences } from "@/api";
import { useAccountMutations, useMe, useNotificationSettings, useProjects } from "@/api/hooks";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/choice";
import { Field, Input } from "@/components/ui/field";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { Tabs } from "@/components/ui/tabs";
import { formatDate } from "@/lib/dates";
import { ErrorState, PageBody, PageHeader, useDocumentTitle } from "@/app/page";

const DAYS: AlertDays[] = [0, 1, 3, 7];
const PARTICIPATING = ["assignee", "responsible", "shared"] as const;
const OTHER = ["workPackageCreated", "commented", "statusChanged", "priorityChanged", "datesChanged"] as const;

function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="mb-1 text-base font-semibold text-fg">{title}</legend>
      {hint && <p className="-mt-1 mb-1 max-w-2xl text-sm text-fg-muted">{hint}</p>}
      {children}
    </fieldset>
  );
}

/** The switches of one setting (my default, or one project's). */
function SettingFields({ value, onChange }: { value: NotificationSetting; onChange: (v: NotificationSetting) => void }) {
  const { t } = useTranslation();
  const set = <K extends keyof NotificationSetting>(k: K, v: NotificationSetting[K]) => onChange({ ...value, [k]: v });
  const dayOptions = DAYS.map((d) => ({ value: String(d), label: t(`notificationSettings.days.${d}`) }));
  const dateRow = (key: "startDateAlertDays" | "dueDateAlertDays", label: string) => (
    <div className="flex flex-wrap items-center gap-3">
      <Checkbox label={label} checked={value[key] !== null} onCheckedChange={(c) => set(key, c === true ? 1 : null)} />
      <Select
        size="sm"
        className="w-32"
        aria-label={t("notificationSettings.when", { field: label })}
        disabled={value[key] === null}
        value={value[key] === null ? "1" : String(value[key])}
        onValueChange={(v) => set(key, Number(v) as AlertDays)}
        options={dayOptions}
      />
    </div>
  );
  return (
    <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
      <Section title={t("notificationSettings.participating")} hint={t("notificationSettings.participatingHint")}>
        <Checkbox label={t("notificationSettings.mentioned")} description={t("notificationSettings.always")} checked disabled />
        <Checkbox label={t("notificationSettings.watched")} description={t("notificationSettings.always")} checked disabled />
        {PARTICIPATING.map((k) => (
          <Checkbox key={k} label={t(`notificationSettings.${k}`)} checked={value[k]} onCheckedChange={(c) => set(k, c === true)} />
        ))}
      </Section>
      <Section title={t("notificationSettings.dateAlerts")} hint={t("notificationSettings.dateAlertsHint")}>
        {dateRow("startDateAlertDays", t("notificationSettings.startDate"))}
        {dateRow("dueDateAlertDays", t("notificationSettings.dueDate"))}
        <Checkbox label={t("notificationSettings.overdue")} checked={value.overdueAlert} onCheckedChange={(c) => set("overdueAlert", c === true)} />
      </Section>
      <Section title={t("notificationSettings.other")} hint={t("notificationSettings.otherHint")}>
        {OTHER.map((k) => (
          <Checkbox key={k} label={t(`notificationSettings.${k}`)} checked={value[k]} onCheckedChange={(c) => set(k, c === true)} />
        ))}
      </Section>
    </div>
  );
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function Card({ title, hint, actions, children }: { title: ReactNode; hint?: string; actions: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={typeof title === "string" ? title : undefined} className="rounded-lg border border-border p-4 sm:p-5">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg text-fg">{title}</h2>
          {hint && <p className="mt-0.5 text-sm text-fg-muted">{hint}</p>}
        </div>
        <div className="flex flex-wrap items-center gap-2">{actions}</div>
      </div>
      {children}
    </section>
  );
}

function ProjectSetting({ projectId, name, saved }: { projectId: string; name: string; saved: NotificationSetting }) {
  const { t } = useTranslation();
  const toast = useToast();
  const m = useAccountMutations();
  const [draft, setDraft] = useState<NotificationSetting | null>(null);
  const value = draft ?? saved;
  const dirty = !!draft && !same(draft, saved);
  return (
    <Card
      title={name}
      actions={
        <>
          {dirty && <span className="text-xs text-warning">{t("notificationSettings.unsaved")}</span>}
          <Button
            size="sm"
            variant="primary"
            icon={<Save />}
            disabled={!dirty}
            loading={m.saveProject.isPending && m.saveProject.variables?.projectId === projectId}
            aria-label={t("notificationSettings.saveProject", { name })}
            onClick={() =>
              m.saveProject.mutate(
                { projectId, setting: value },
                {
                  onSuccess: () => (setDraft(null), toast({ tone: "success", title: t("app.saved") })),
                  onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }),
                },
              )
            }
          >
            {t("app.save")}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            iconOnly
            icon={<Trash2 />}
            aria-label={t("notificationSettings.removeProject", { name })}
            onClick={() => m.removeProject.mutate(projectId, { onSuccess: () => toast({ tone: "success", title: t("notificationSettings.removed", { name }) }) })}
          />
        </>
      }
    >
      <SettingFields value={value} onChange={setDraft} />
    </Card>
  );
}

function NotificationsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const q = useNotificationSettings();
  const m = useAccountMutations();
  const projects = useProjects({ filter: "active", pageSize: 200 });
  const [draft, setDraft] = useState<NotificationSetting | null>(null);
  const [adding, setAdding] = useState("");

  if (q.isPending) return <Skeleton className="h-96 w-full" />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const settings = q.data;
  const value = draft ?? settings.default;
  const dirty = !!draft && !same(draft, settings.default);
  const taken = new Set(settings.projects.map((p) => p.project.id));
  const addable = (projects.data?.items ?? []).filter((p) => !taken.has(p.id));

  return (
    <div className="flex flex-col gap-8">
      <Card
        title={t("notificationSettings.defaultTitle")}
        hint={t("notificationSettings.defaultHint")}
        actions={
          <>
            {dirty && <span className="text-xs text-warning">{t("notificationSettings.unsaved")}</span>}
            <Button
              variant="primary"
              icon={<Save />}
              disabled={!dirty}
              loading={m.saveDefault.isPending}
              onClick={() =>
                m.saveDefault.mutate(value, {
                  onSuccess: () => (setDraft(null), toast({ tone: "success", title: t("app.saved") })),
                  onError: (e) => toast({ tone: "danger", title: isApiError(e) ? e.message : t("app.errorTitle") }),
                })
              }
            >
              {t("notificationSettings.saveDefault")}
            </Button>
          </>
        }
      >
        <SettingFields value={value} onChange={setDraft} />
      </Card>

      <section aria-labelledby="ns-projects" className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 id="ns-projects" className="text-lg text-fg">
              {t("notificationSettings.projects")}
            </h2>
            <p className="mt-0.5 max-w-2xl text-sm text-fg-muted">{t("notificationSettings.projectsHint")}</p>
          </div>
          <div className="flex items-center gap-2">
            <Select
              size="sm"
              className="w-56"
              aria-label={t("notificationSettings.addProject")}
              placeholder={t("notificationSettings.addProject")}
              value={adding}
              disabled={!addable.length}
              onValueChange={setAdding}
              options={addable.map((p) => ({ value: p.id, label: p.name }))}
            />
            <Button
              size="sm"
              icon={<FolderPlus />}
              disabled={!adding}
              loading={m.saveProject.isPending && m.saveProject.variables?.projectId === adding}
              onClick={() => {
                const name = addable.find((p) => p.id === adding)?.name ?? "";
                // a new project setting starts as a copy of my default
                m.saveProject.mutate({ projectId: adding, setting: settings.default }, { onSuccess: () => (setAdding(""), toast({ tone: "success", title: t("notificationSettings.added", { name }) })) });
              }}
            >
              {t("notificationSettings.add")}
            </Button>
          </div>
        </div>
        {settings.projects.length === 0 ? (
          <div className="rounded-lg border border-border">
            <EmptyState compact icon={<FolderPlus />} title={t("notificationSettings.projectsEmpty")}>
              {t("notificationSettings.projectsEmptyBody")}
            </EmptyState>
          </div>
        ) : (
          settings.projects.map((p) => {
            const { project, ...saved } = p;
            return <ProjectSetting key={project.id} projectId={project.id} name={project.name} saved={saved} />;
          })
        )}
      </section>
    </div>
  );
}

function EmailTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const me = useMe().data;
  const m = useAccountMutations();
  const [draft, setDraft] = useState<Partial<Preferences>>({});
  if (!me) return <Skeleton className="h-64 w-full" />;
  const v = { ...me.preferences, ...draft };
  const dirty = Object.entries(draft).some(([k, x]) => !same(x, me.preferences[k as keyof Preferences]));
  const set = <K extends keyof Preferences>(k: K, x: Preferences[K]) => setDraft((d) => ({ ...d, [k]: x }));
  const timesError = isApiError(m.preferences.error) ? m.preferences.error.fieldErrors.find((e) => e.field === "digestTimes")?.message : undefined;

  return (
    <div className="flex max-w-form flex-col gap-8">
      <Section title={t("notificationSettings.email.mention")}>
        <Checkbox
          label={t("notificationSettings.email.immediate")}
          description={t("notificationSettings.email.immediateHint")}
          checked={v.immediateOnMention}
          onCheckedChange={(c) => set("immediateOnMention", c === true)}
        />
      </Section>

      <Section title={t("notificationSettings.email.digest")} hint={t("notificationSettings.email.digestHint", { zone: me.timeZone })}>
        <Checkbox label={t("notificationSettings.email.digestEnabled")} checked={v.digestEnabled} onCheckedChange={(c) => set("digestEnabled", c === true)} />
        <ol className="flex flex-col gap-2">
          {v.digestTimes.map((time, i) => (
            <li key={i} className="flex items-center gap-2">
              <Input
                type="time"
                className="w-36"
                aria-label={t("notificationSettings.email.time", { n: i + 1 })}
                disabled={!v.digestEnabled}
                value={time}
                onChange={(e) => set("digestTimes", v.digestTimes.map((x, j) => (j === i ? e.target.value : x)))}
              />
              <Button
                variant="ghost"
                size="sm"
                iconOnly
                icon={<X />}
                aria-label={t("notificationSettings.email.removeTime", { n: i + 1 })}
                disabled={!v.digestEnabled || v.digestTimes.length === 1}
                onClick={() => set("digestTimes", v.digestTimes.filter((_, j) => j !== i))}
              />
            </li>
          ))}
        </ol>
        {timesError && <p className="text-xs text-danger">{timesError}</p>}
        {v.digestTimes.length < 4 && (
          <div>
            <Button size="sm" icon={<Plus />} disabled={!v.digestEnabled} onClick={() => set("digestTimes", [...v.digestTimes, "17:00"])}>
              {t("notificationSettings.email.addTime")}
            </Button>
          </div>
        )}
      </Section>

      <Section title={t("notificationSettings.email.pause")} hint={t("notificationSettings.email.pauseHint")}>
        <div className="flex flex-wrap items-end gap-2">
          <Field label={t("notificationSettings.email.pauseUntil")}>
            <Input type="date" className="w-44" value={v.remindersPausedUntil ?? ""} onChange={(e) => set("remindersPausedUntil", e.target.value || null)} />
          </Field>
          {v.remindersPausedUntil && (
            <Button size="sm" variant="ghost" icon={<BellOff />} onClick={() => set("remindersPausedUntil", null)}>
              {t("notificationSettings.email.pauseClear")}
            </Button>
          )}
        </div>
        {me.preferences.remindersPausedUntil && (
          <Banner tone="info">{t("notificationSettings.email.paused", { date: formatDate(me.preferences.remindersPausedUntil) })}</Banner>
        )}
      </Section>

      <div className="flex items-center gap-3">
        <Button
          variant="primary"
          icon={<Save />}
          disabled={!dirty}
          loading={m.preferences.isPending}
          onClick={() =>
            m.preferences.mutate(draft, {
              onSuccess: () => (setDraft({}), toast({ tone: "success", title: t("app.saved") })),
              onError: (e) => toast({ tone: "danger", title: isApiError(e) ? (e.fieldErrors[0]?.message ?? e.message) : t("app.errorTitle") }),
            })
          }
        >
          {t("notificationSettings.email.save")}
        </Button>
        {dirty && <span className="text-xs text-warning">{t("notificationSettings.unsaved")}</span>}
      </div>
    </div>
  );
}

export function NotificationSettingsPage() {
  const { t } = useTranslation();
  useDocumentTitle(t("nav.notificationSettings"));
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "email" ? "email" : "notifications";
  return (
    <PageBody>
      <PageHeader title={t("nav.notificationSettings")} />
      <Tabs
        aria-label={t("nav.notificationSettings")}
        value={tab}
        onValueChange={(v) => setParams(v === "email" ? { tab: "email" } : {}, { replace: true })}
        items={[
          { value: "notifications", label: t("notificationSettings.tabs.notifications"), content: <NotificationsTab /> },
          { value: "email", label: t("notificationSettings.tabs.email"), content: <EmailTab /> },
        ]}
      />
    </PageBody>
  );
}
