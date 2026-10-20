// 검증 보드 (시안 B): the work packages whose commits are in the source environment and not yet in the target, in four
// columns (검증 대기 / 재검증 필요 / 실패 / 통과). Above: the version, source and target pickers (kept in the address, a
// default is left out), the summary row (빠짐 · 끼어듦 · 연결 없는 커밋 · 뺀 작업, each opens its list) and a warning when
// the list may be short. People with release.verify get 통과 · 실패… · 이미 운영에 있음 on each card (buttons only, no
// dragging) and 되돌리기 on an excluded one. The columns stack on a narrow screen.
import { useId, useRef, useState, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { Check, ExternalLink, EyeOff, Rocket, Undo2, X } from "lucide-react";
import {
  isApiError,
  type Id,
  type ReleaseBoard,
  type ReleaseBoardParams,
  type ReleaseCard,
  type ReleaseColumn,
  type ReleaseEnvironmentRef,
  type ReleaseExclusionInput,
} from "@/api";
import { useReleaseBoard, useReleaseMutations } from "@/api/hooks";
import { Badge, Counter, StatusPill } from "@/components/ui/badges";
import { Button } from "@/components/ui/button";
import { Banner, EmptyState, useToast } from "@/components/ui/feedback";
import { Select } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/spinner";
import { ErrorState } from "@/app/page";
import { can, useCurrentProject } from "@/app/project";
import { cn } from "@/lib/cn";
import { DEFAULT_TZ, formatDateTime, formatTime } from "@/lib/dates";
import { FailDialog } from "./fail-dialog";

const COLUMNS: ReleaseColumn[] = ["waiting", "reverify", "failed", "passed"];
const NO_VERSION = "none";

/** The first production environment that is not hidden (the server's default target). */
function defaultTarget(envs: ReleaseEnvironmentRef[]): Id | null {
  return envs.filter((e) => e.production && !e.hidden).sort((a, b) => a.position - b.position)[0]?.id ?? null;
}

/** The environment right before the target by position that is not hidden (the server's default source). */
function defaultSource(envs: ReleaseEnvironmentRef[], target: Id | null): Id | null {
  const t = envs.find((e) => e.id === target);
  if (!t) return null;
  return envs.filter((e) => !e.hidden && e.id !== t.id && e.position < t.position).sort((a, b) => b.position - a.position)[0]?.id ?? null;
}

function seoulParts(at: string) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: DEFAULT_TZ, month: "2-digit", day: "2-digit" }).formatToParts(new Date(at));
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return { month: part("month"), day: part("day") };
}
/** "10-17" (Asia/Seoul) */
function monthDay(at: string) {
  const { month, day } = seoulParts(at);
  return `${month}-${day}`;
}
/** "10-18 03:00" (Asia/Seoul) */
const shortWhen = (at: string) => `${monthDay(at)} ${formatTime(at)}`;

/** A link to the tool, in a new window, saying so to screen readers. */
function External({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  const { t } = useTranslation();
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={className ?? "text-accent hover:underline"}>
      {children}
      <ExternalLink className="ml-1 inline size-3 align-[-1px]" aria-hidden="true" />
      <span className="sr-only"> ({t("cicd.opensInNew")})</span>
    </a>
  );
}

const wpHref = (project: string, number: number) => `/projects/${project}/work-packages/${number}/activity`;

/* ---------- the pickers ---------- */

function Pickers({ board, params, onChange }: { board: ReleaseBoard; params: ReleaseBoardParams; onChange: (p: ReleaseBoardParams) => void }) {
  const { t } = useTranslation();
  const envLabel = (e: ReleaseEnvironmentRef) => (e.hidden ? t("releases.picker.hidden", { name: e.name }) : e.name);
  const envOptions = (other: Id | null) =>
    board.environments.map((e) => ({ value: e.id, label: envLabel(e), textValue: envLabel(e), disabled: !!other && e.id === other }));
  const versionOptions = [
    ...board.versions.map((v) => ({ value: v.id, label: v.name, textValue: v.name })),
    { value: NO_VERSION, label: t("releases.picker.noVersion"), textValue: t("releases.picker.noVersion") },
  ];
  const pickers = [
    {
      key: "version",
      label: t("releases.picker.version"),
      value: board.versionId ?? NO_VERSION,
      options: versionOptions,
      change: (v: string) => onChange({ ...params, version: v }),
    },
    {
      key: "source",
      label: t("releases.picker.source"),
      value: board.sourceId ?? "",
      options: envOptions(board.targetId),
      change: (v: string) => onChange({ ...params, source: v }),
    },
    {
      key: "target",
      label: t("releases.picker.target"),
      value: board.targetId ?? "",
      // any environment may be the target: picking the current source drops the source, so the new target takes its
      // default (the one right before it)
      options: envOptions(null),
      change: (v: string) => onChange({ ...params, target: v, source: params.source && params.source !== v ? params.source : undefined }),
    },
  ];
  return (
    <div className="flex flex-wrap items-end gap-3">
      {pickers.map((p) => (
        <PickerField key={p.key} label={p.label}>
          {(id) => <Select id={id} className="w-48" value={p.value} onValueChange={p.change} options={p.options} />}
        </PickerField>
      ))}
    </div>
  );
}

function PickerField({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-fg">
        {label}
      </label>
      {children(id)}
    </div>
  );
}

/* ---------- the summary row ---------- */

type Panel = "missing" | "interlopers" | "unlinked" | "excluded";

function Summary({
  board,
  project,
  pendingRestore,
  onRestore,
}: {
  board: ReleaseBoard;
  project: string;
  pendingRestore: Id | null;
  onRestore: (workPackageId: Id) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState<Panel | null>(null);
  const baseId = useId();
  const interlopers = board.cards.filter((c) => c.interloper);
  const toggles: { key: Panel; label: string }[] = [
    { key: "missing", label: t("releases.summary.missing", { count: board.missing.length }) },
    { key: "interlopers", label: t("releases.summary.interlopers", { count: board.interloperCount }) },
    { key: "unlinked", label: t("releases.summary.unlinked", { count: board.unlinkedCount }) },
    { key: "excluded", label: t("releases.summary.excluded", { count: board.excluded.length }) },
  ];
  const none = <p className="text-sm text-fg-muted">{t("releases.summary.none")}</p>;
  const missingGroup = (group: "developing" | "no_code", title: string) => {
    const rows = board.missing.filter((m) => m.group === group);
    return (
      <div className="flex min-w-0 flex-col gap-1.5">
        <h3 className="text-sm font-semibold text-fg">
          {title} <span className="font-normal text-fg-muted tabular-nums">{rows.length}</span>
        </h3>
        {rows.length === 0 ? (
          none
        ) : (
          <ul className="flex flex-col gap-1">
            {rows.map((m) => (
              <li key={m.workPackageId} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                <Link to={wpHref(project, m.number)} className="min-w-0 text-accent hover:underline">
                  <span className="tabular-nums">#{m.number}</span> {m.subject}
                </Link>
                <StatusPill size="sm" name={m.status.name} color={m.status.color} />
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <ul aria-label={t("releases.summary.label")} className="flex flex-wrap items-center gap-2">
        {toggles.map((x) => (
          <li key={x.key}>
            <Button
              size="sm"
              variant={open === x.key ? "secondary" : "ghost"}
              className={cn("border", open === x.key ? "border-accent" : "border-border")}
              aria-expanded={open === x.key}
              aria-controls={`${baseId}-${x.key}`}
              onClick={() => setOpen(open === x.key ? null : x.key)}
            >
              {x.label}
            </Button>
          </li>
        ))}
      </ul>
      {toggles.map((x) => (
        <div key={x.key} id={`${baseId}-${x.key}`} hidden={open !== x.key} className="rounded-md border border-border bg-surface p-3">
          {open === x.key && x.key === "missing" && (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              {missingGroup("developing", t("releases.summary.developing"))}
              {missingGroup("no_code", t("releases.summary.noCode"))}
            </div>
          )}
          {open === x.key && x.key === "interlopers" &&
            (interlopers.length === 0 ? (
              none
            ) : (
              <ul className="flex flex-wrap gap-x-3 gap-y-1 text-sm">
                {interlopers.map((c) => (
                  <li key={c.workPackageId}>
                    <Link to={wpHref(c.project.identifier, c.number)} title={c.subject} className="text-accent tabular-nums hover:underline">
                      #{c.number}
                    </Link>
                  </li>
                ))}
              </ul>
            ))}
          {open === x.key && x.key === "unlinked" && (
            <div className="flex flex-col gap-2 text-sm">
              <p className="text-fg-muted">{t("releases.summary.unlinkedNote")}</p>
              {board.unlinked.length === 0 ? (
                none
              ) : (
                <ul className="flex flex-col gap-1">
                  {board.unlinked.map((c) => (
                    <li key={`${c.repository}:${c.shortSha}`} className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-fg-muted">{c.repository}</span>
                      <External href={c.webUrl} className="font-mono text-accent hover:underline">
                        {c.shortSha}
                      </External>
                      <span className="min-w-0 break-words text-fg">{c.title}</span>
                    </li>
                  ))}
                </ul>
              )}
              {board.unlinkedCount > board.unlinked.length && <p className="text-fg-muted">{t("releases.summary.unlinkedCapped")}</p>}
            </div>
          )}
          {open === x.key && x.key === "excluded" &&
            (board.excluded.length === 0 ? (
              none
            ) : (
              <ul className="flex flex-col gap-1.5 text-sm">
                {board.excluded.map((e) => (
                  <li key={e.workPackageId} className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <Link to={wpHref(project, e.number)} className="min-w-0 text-accent hover:underline">
                      <span className="tabular-nums">#{e.number}</span> {e.subject}
                    </Link>
                    <span className="text-fg-muted">
                      {t("releases.summary.excludedBy", { by: e.by?.name ?? t("releases.card.deletedUser"), date: monthDay(e.at) })}
                    </span>
                    {board.canVerify && (
                      <Button
                        size="sm"
                        icon={<Undo2 />}
                        loading={pendingRestore === e.workPackageId}
                        aria-label={t("releases.summary.restoreLabel", { number: e.number })}
                        onClick={() => onRestore(e.workPackageId)}
                      >
                        {t("releases.summary.restore")}
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            ))}
        </div>
      ))}
    </div>
  );
}

/* ---------- a card ---------- */

type Action = "pass" | "fail" | "exclude";

function Card({
  card,
  project,
  source,
  canVerify,
  pending,
  onPass,
  onFail,
  onExclude,
}: {
  card: ReleaseCard;
  project: string;
  source: string;
  canVerify: boolean;
  pending: Action | null;
  onPass: () => void;
  onFail: () => void;
  onExclude: () => void;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  const last = card.last;
  return (
    <article aria-labelledby={titleId} className="flex flex-col gap-1.5 rounded-md border border-border bg-bg p-3 text-sm">
      <h3 id={titleId} className="font-medium text-fg">
        <Link to={wpHref(card.project.identifier, card.number)} className="hover:underline">
          <span className="text-fg-muted tabular-nums">#{card.number}</span> {card.subject}
        </Link>
      </h3>
      <div className="flex flex-wrap items-center gap-1.5">
        <StatusPill size="sm" name={card.status.name} color={card.status.color} />
        {card.interloper && <Badge tone="neutral">{t("releases.card.interloper")}</Badge>}
        {card.project.identifier !== project && (
          <span className="text-xs text-fg-muted">
            <span className="sr-only">{t("releases.card.project")}: </span>
            {card.project.name}
          </span>
        )}
      </div>
      <p className="text-fg-muted">
        <span className="sr-only">{t("releases.card.assignees")}: </span>
        {card.assignees.length ? card.assignees.map((a) => a.name).join(", ") : t("releases.card.noAssignee")}
      </p>
      <p className="text-fg-muted">
        {card.deploymentUrl ? (
          <External href={card.deploymentUrl}>
            <time dateTime={card.cameInAt} title={formatDateTime(card.cameInAt)}>
              {t("releases.card.cameIn", { environment: source, when: shortWhen(card.cameInAt) })}
            </time>
          </External>
        ) : (
          <time dateTime={card.cameInAt} title={formatDateTime(card.cameInAt)}>
            {t("releases.card.cameIn", { environment: source, when: shortWhen(card.cameInAt) })}
          </time>
        )}
      </p>
      {last && (
        <div className="flex flex-col gap-0.5">
          <p className={cn("inline-flex items-center gap-1", last.result === "failed" ? "text-danger" : "text-fg")}>
            {last.result === "failed" ? <X className="size-3.5 shrink-0" aria-hidden="true" /> : <Check className="size-3.5 shrink-0 text-success" aria-hidden="true" />}
            <span className="sr-only">{t("releases.card.lastVerification")}: </span>
            {t(last.result === "failed" ? "releases.card.lastFailed" : "releases.card.lastPassed", {
              by: last.by?.name ?? t("releases.card.deletedUser"),
              date: monthDay(last.at),
            })}
          </p>
          {last.result === "failed" && last.reason && (
            <p className="line-clamp-2 break-words text-fg-muted" title={last.reason}>
              {last.reason}
            </p>
          )}
        </div>
      )}
      {canVerify && (
        <div className="mt-1 flex flex-wrap gap-1.5">
          <Button size="sm" icon={<Check />} loading={pending === "pass"} disabled={!!pending && pending !== "pass"} aria-label={t("releases.card.passLabel", { number: card.number })} onClick={onPass}>
            {t("releases.card.pass")}
          </Button>
          <Button size="sm" icon={<X />} disabled={!!pending} aria-label={t("releases.card.failLabel", { number: card.number })} onClick={onFail}>
            {t("releases.card.fail")}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            icon={<EyeOff />}
            loading={pending === "exclude"}
            disabled={!!pending && pending !== "exclude"}
            aria-label={t("releases.card.excludeLabel", { number: card.number })}
            onClick={onExclude}
          >
            {t("releases.card.exclude")}
          </Button>
        </div>
      )}
    </article>
  );
}

function Column({ column, cards, children }: { column: ReleaseColumn; cards: ReleaseCard[]; children: (c: ReleaseCard) => ReactNode }) {
  const { t } = useTranslation();
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className="flex min-w-0 flex-col gap-2 rounded-md bg-surface-sunken p-2">
      <h2 id={titleId} className="flex items-center gap-2 px-1 text-sm font-semibold text-fg">
        {t(`releases.columns.${column}`)} <Counter tone={column === "failed" && cards.length ? "danger" : "neutral"}>{cards.length}</Counter>
      </h2>
      {cards.length === 0 ? <p className="px-1 pb-1 text-sm text-fg-muted">{t("releases.columns.empty")}</p> : cards.map((c) => <div key={c.workPackageId}>{children(c)}</div>)}
    </section>
  );
}

/* ---------- the board ---------- */

/** ?version=&source=&target= → the board's params (absent = the server's default) */
function readParams(sp: URLSearchParams): ReleaseBoardParams {
  const p: ReleaseBoardParams = {};
  const version = sp.get("version");
  const source = sp.get("source");
  const target = sp.get("target");
  if (version) p.version = version;
  if (source) p.source = source;
  if (target) p.target = target;
  return p;
}

export function ReleaseBoardView() {
  const { t } = useTranslation();
  const project = useCurrentProject();
  const toast = useToast();
  const [search, setSearch] = useSearchParams();
  const params = readParams(search);
  const q = useReleaseBoard(project.identifier, params);
  const m = useReleaseMutations(project.identifier);
  // keep the last board on screen while the next pickers' board loads (the pickers keep their focus)
  const shown = useRef<ReleaseBoard | undefined>(undefined);
  if (q.data) shown.current = q.data;
  // the version the server picked when none was asked: picking it again leaves ?version= out
  const defaultVersion = useRef<string | undefined>(undefined);
  if (q.data && !params.version) defaultVersion.current = q.data.versionId ?? NO_VERSION;
  const [pending, setPending] = useState<{ id: Id; action: Action | "restore" } | null>(null);
  const [failing, setFailing] = useState<ReleaseCard | null>(null);

  const board = q.data ?? (q.isFetching ? shown.current : undefined);
  if (!board) {
    if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
    return <Skeleton className="h-64 w-full" />;
  }

  const setParams = (next: ReleaseBoardParams) => {
    const envs = board.environments;
    const target = next.target && next.target !== defaultTarget(envs) ? next.target : undefined;
    const source = next.source && next.source !== defaultSource(envs, next.target ?? defaultTarget(envs)) ? next.source : undefined;
    const version = next.version && next.version !== defaultVersion.current ? next.version : undefined;
    const sp = new URLSearchParams();
    if (version) sp.set("version", version);
    if (source) sp.set("source", source);
    if (target) sp.set("target", target);
    setSearch(sp, { replace: true });
  };

  const pair = board.sourceId && board.targetId ? { environmentId: board.sourceId, targetEnvironmentId: board.targetId } : null;
  const source = board.environments.find((e) => e.id === board.sourceId)?.name ?? "";

  const fail = (e: unknown) =>
    toast({ tone: "danger", title: isApiError(e) ? e.title : t("app.errorTitle"), description: isApiError(e) && e.message !== e.title ? e.message : undefined });
  const run = async (id: Id, action: Action | "restore", work: () => Promise<unknown>, done: string) => {
    setPending({ id, action });
    try {
      await work();
      toast({ tone: "success", title: done });
    } catch (e) {
      fail(e);
    } finally {
      setPending(null);
    }
  };
  const input = (workPackageId: Id): ReleaseExclusionInput | null => (pair ? { workPackageId, ...pair } : null);
  const pass = (c: ReleaseCard) => {
    const i = input(c.workPackageId);
    if (i) void run(c.workPackageId, "pass", () => m.verify.mutateAsync({ ...i, result: "passed" }), t("releases.toast.passed"));
  };
  const exclude = (c: ReleaseCard) => {
    const i = input(c.workPackageId);
    if (i) void run(c.workPackageId, "exclude", () => m.exclude.mutateAsync(i), t("releases.toast.excluded"));
  };
  const restore = (workPackageId: Id) => {
    const i = input(workPackageId);
    if (i) void run(workPackageId, "restore", () => m.restore.mutateAsync(i), t("releases.toast.restored"));
  };

  const loading = !q.data && q.isFetching;
  return (
    <div className={cn("flex flex-col gap-4", loading && "opacity-60")} aria-busy={loading || undefined}>
      <Pickers board={board} params={params} onChange={setParams} />
      {!pair ? (
        <div className="rounded-md border border-border">
          <EmptyState
            icon={<Rocket />}
            title={t("releases.empty.noPair")}
            action={
              can(project, "cicd.manage") ? (
                <Link to={`/projects/${project.identifier}/settings/environments`} className="text-sm text-accent hover:underline">
                  {t("releases.empty.settingsLink")}
                </Link>
              ) : undefined
            }
          />
        </div>
      ) : (
        // the last board stays in view while the next one loads, but nothing on it can be pressed (its pair may be gone)
        <div className="flex flex-col gap-4" inert={loading}>
          <Summary
            board={board}
            project={project.identifier}
            pendingRestore={pending?.action === "restore" ? pending.id : null}
            onRestore={restore}
          />
          {board.notices.length > 0 && (
            <Banner tone="warning">
              {board.notices.length === 1 ? (
                t(`releases.notice.${board.notices[0].kind}`, { environment: board.notices[0].environment })
              ) : (
                <ul className="flex list-disc flex-col gap-0.5 pl-4">
                  {board.notices.map((n) => (
                    <li key={`${n.environmentId}:${n.kind}`}>{t(`releases.notice.${n.kind}`, { environment: n.environment })}</li>
                  ))}
                </ul>
              )}
            </Banner>
          )}
          {board.cards.length === 0 ? (
            <div className="rounded-md border border-border">
              <EmptyState icon={<Rocket />} title={t("releases.empty.noCards")} />
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
              {COLUMNS.map((col) => (
                <Column key={col} column={col} cards={board.cards.filter((c) => c.column === col)}>
                  {(c) => (
                    <Card
                      card={c}
                      project={project.identifier}
                      source={source}
                      canVerify={board.canVerify}
                      pending={pending?.id === c.workPackageId && pending.action !== "restore" ? pending.action : null}
                      onPass={() => pass(c)}
                      onFail={() => setFailing(c)}
                      onExclude={() => exclude(c)}
                    />
                  )}
                </Column>
              ))}
            </div>
          )}
        </div>
      )}
      {failing && pair && (
        <FailDialog
          card={failing}
          pending={m.verify.isPending}
          onClose={() => setFailing(null)}
          onSubmit={async (reason) => {
            await m.verify.mutateAsync({ workPackageId: failing.workPackageId, ...pair, result: "failed", reason });
            setFailing(null);
            toast({ tone: "success", title: t("releases.toast.failed") });
          }}
        />
      )}
    </div>
  );
}
