// 프로젝트 › 릴리스 in the fake layer: the same rules and messages as the server's cicd/CicdReleaseBoard, CicdVerifications,
// CicdVerificationStates and CicdReleases. The board compares the source environment S with the target T: the work packages whose
// commits are in S but not yet in T (after T's first deployment, the baseline), with their current verification, the version check
// and the banner. Verifying writes one activity line and, for a failure, notifies the assignees who may see the work package.
// There is no deployment processing here (as in phase 2): the release records come from the seed only. Deviation, on purpose: a
// "failed" banner shows for a failed deployment that is the newest successful one of its environment (the server asks for its head).
import type { Api } from "../api";
import type {
  Id, IsoDateTime, ReleaseBoard, ReleaseCard, ReleaseColumn, ReleaseEnvironmentRef, ReleaseExcluded, ReleaseItem, ReleaseMissing, ReleaseNotice,
  ReleaseRow, ReleaseUnlinkedCommit, ReleaseVerification, ReleaseWarning, UserRef,
} from "../types";
import { ApiError } from "../errors";
import { nowIso, todayIso } from "@/lib/dates";
import { db, newId, type DbJournal, type DbProject, type DbUser, type DbWp } from "./db";
import { can, findProject, requirePerm, sessionUser } from "./permissions";
import { clone, projectRef, statusRef, userRef } from "./dto";
import { teamOf } from "./assignees";
import type { DbCicdEnvironment } from "./cicd-seed";
import type { DbCicdRelease, DbCicdVerification } from "./release-seed";
import { fieldError, run } from "./util";

const UNLINKED_LIMIT = 100;
const REASON_MAX = 2000;
const COLUMNS: ReleaseColumn[] = ["waiting", "reverify", "failed", "passed"];
const NOT_ON_BOARD = "이 보드에 없는 작업입니다. 이미 대상 환경에 들어갔거나 연결이 바뀌었습니다.";

const byTime = (a: string, b: string) => Date.parse(a) - Date.parse(b);

/* ---------- verification state (CicdVerificationStates) ---------- */

/**
 * "The work package's commits" (design 3): the commits of its links and the merge commit of a linked merged merge request;
 * dismissed links do not count, nor do the separate commits inside a merge request. wpIds absent = every work package.
 */
function workPackageCommits(wpIds?: Set<Id>): Map<Id, Set<Id>> {
  const out = new Map<Id, Set<Id>>();
  const add = (wp: Id, commit: Id) => {
    if (!out.has(wp)) out.set(wp, new Set());
    out.get(wp)!.add(commit);
  };
  for (const l of db.cicdLinks) {
    if (l.dismissedAt || (wpIds && !wpIds.has(l.wpId))) continue;
    if (l.commitId) add(l.wpId, l.commitId);
    const m = l.mergeRequestId ? db.cicdMergeRequests.find((x) => x.id === l.mergeRequestId) : undefined;
    if (m && m.state === "merged" && m.mergeCommitSha)
      for (const c of db.cicdCommits) if (c.repositoryId === m.repositoryId && c.sha === m.mergeCommitSha) add(l.wpId, c.id);
  }
  return out;
}

/** The newest verification (verifiedAt, then id) of each work package on the source environment; absent = none yet. */
export function currentVerifications(sourceEnvironment: Id, wpIds: Iterable<Id>): Map<Id, DbCicdVerification> {
  const wanted = new Set(wpIds);
  const out = new Map<Id, DbCicdVerification>();
  for (const v of db.cicdVerifications) {
    if (v.environmentId !== sourceEnvironment || !wanted.has(v.wpId)) continue;
    const old = out.get(v.wpId);
    if (!old || byTime(v.verifiedAt, old.verifiedAt) > 0 || (byTime(v.verifiedAt, old.verifiedAt) === 0 && v.id > old.id)) out.set(v.wpId, v);
  }
  return out;
}

/**
 * When each work package's commits last came into the source environment (보완 4): the newest addedAt of all its commits in it now,
 * repositories attached to the environment's project only. Absent: none of its commits is there.
 */
export function latestIn(sourceEnvironment: Id, wpIds: Iterable<Id>): Map<Id, IsoDateTime> {
  const wanted = new Set(wpIds);
  const out = new Map<Id, IsoDateTime>();
  if (!wanted.size) return out;
  const env = db.cicdEnvironments.find((e) => e.id === sourceEnvironment);
  if (!env) return out;
  const commits = workPackageCommits(wanted);
  const there = new Map<Id, IsoDateTime>();
  for (const ec of db.cicdEnvironmentCommits) {
    if (ec.environmentId !== sourceEnvironment) continue;
    const repo = db.cicdRepositories.find((r) => r.id === ec.repositoryId);
    if (!repo || repo.detachedAt || repo.projectId !== env.projectId) continue;
    there.set(ec.commitId, ec.addedAt);
  }
  for (const [wp, set] of commits)
    for (const c of set) {
      const at = there.get(c);
      if (at && (!out.has(wp) || byTime(at, out.get(wp)!) > 0)) out.set(wp, at);
    }
  return out;
}

type EnvRow = { id: Id; position: number; production: boolean; hidden: boolean };

/** The first production environment that is not hidden, by position; null when there is none. */
export function defaultTarget(envs: EnvRow[]): Id | null {
  return envs.filter((e) => e.production && !e.hidden).sort((a, b) => a.position - b.position)[0]?.id ?? null;
}

/** The environment right before the target by position that is not hidden; null when there is none. */
export function defaultSource(envs: EnvRow[], target: Id): Id | null {
  const t = envs.find((e) => e.id === target);
  if (!t) return null;
  return envs.filter((e) => !e.hidden && e.id !== target && e.position < t.position).sort((a, b) => b.position - a.position)[0]?.id ?? null;
}

const stale = (c: DbCicdVerification, latest: IsoDateTime | undefined) => !!latest && !!c.basisAt && byTime(latest, c.basisAt) > 0;

/** The board column: waiting (no result), reverify (a commit came in after the result's basis), failed, passed. */
export function columnOf(c: DbCicdVerification | undefined, latest: IsoDateTime | undefined): ReleaseColumn {
  if (!c) return "waiting";
  if (stale(c, latest)) return "reverify";
  return c.result === "failed" ? "failed" : "passed";
}

/** The release warning, in the order of the columns: unverified, stale (even after a failure), failed, or null when passed. */
export function warningOf(c: DbCicdVerification | undefined, latest: IsoDateTime | undefined): ReleaseWarning | null {
  if (!c) return "unverified";
  if (stale(c, latest)) return "stale";
  return c.result === "failed" ? "failed" : null;
}

/* ---------- access ---------- */

/** the project visible with the module on and cicd.view, else the same 404 */
function viewable(user: DbUser, project: string): DbProject {
  const p = findProject(user, project);
  if (!can(user, p, "cicd.view")) throw new ApiError(404, "프로젝트를 찾을 수 없습니다.");
  return p;
}

const mayVerify = (user: DbUser, p: DbProject) => requirePerm(user, p, "release.verify", "릴리스를 검증할 권한이 없습니다.");

const projectById = (id: Id) => db.projects.find((p) => p.id === id);

function visible(user: DbUser, wp: DbWp | undefined): wp is DbWp {
  const project = wp && projectById(wp.projectId);
  return !!project && can(user, project, "wp.view", wp);
}

/** The work package, when it exists and I may see it; otherwise the same 404 (its existence does not show). */
function workPackage(user: DbUser, id: Id): { wp: DbWp; project: DbProject } {
  const wp = db.workPackages.find((w) => w.id === id);
  if (!visible(user, wp)) throw new ApiError(404, "작업을 찾을 수 없습니다.");
  return { wp, project: projectById(wp.projectId)! };
}

/* ---------- the board (CicdReleaseBoard) ---------- */

type Pair = { source: Id; target: Id } | null;

function environmentsOf(p: DbProject): DbCicdEnvironment[] {
  return db.cicdEnvironments.filter((e) => e.projectId === p.id).sort((a, b) => a.position - b.position || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

const attachedRepositories = (p: DbProject) => db.cicdRepositories.filter((r) => r.projectId === p.id && !r.detachedAt).map((r) => r.id);

/** The source and target, defaults filled in; null when there is no default. 404 for another project's environment, 422 for the same one twice. */
function pairOf(envs: DbCicdEnvironment[], source?: Id | null, target?: Id | null): Pair {
  const ids = new Set(envs.map((e) => e.id));
  if ((source && !ids.has(source)) || (target && !ids.has(target))) throw new ApiError(404, "환경을 찾을 수 없습니다.");
  const t = target || defaultTarget(envs);
  const s = source || (t ? defaultSource(envs, t) : null);
  if (!s || !t) return null;
  if (s === t) throw new ApiError(422, "비교 원본과 대상은 다른 환경이어야 합니다.");
  return { source: s, target: t };
}

/** this project and its sub-projects (CicdLinker's tree) */
function tree(p: DbProject): Set<Id> {
  const out = new Set<Id>([p.id]);
  for (let grew = true; grew; ) {
    grew = false;
    for (const x of db.projects)
      if (x.parentId && out.has(x.parentId) && !out.has(x.id)) {
        out.add(x.id);
        grew = true;
      }
  }
  return out;
}

/** The baseline of each attached repository: the first successful deployment to T (보완 3); a commit that came into S before it is out already. */
function baselines(repos: Id[], target: Id): Map<Id, IsoDateTime> {
  const out = new Map<Id, IsoDateTime>();
  for (const d of db.cicdDeployments) {
    if (d.environmentId !== target || d.status !== "success" || !repos.includes(d.repositoryId)) continue;
    const old = out.get(d.repositoryId);
    if (!old || byTime(d.deployedAt, old) < 0) out.set(d.repositoryId, d.deployedAt);
  }
  return out;
}

type Candidate = { wp: DbWp; deployedAt: IsoDateTime; deploymentUrl: string };

/**
 * S − T per attached repository after the baseline: the visible work packages of P's tree (newest first, a work package's first row is
 * its card's) and the commits with no link at all (a commit linked only to an invisible or out-of-tree work package is not "unlinked").
 */
function difference(user: DbUser, p: DbProject, pair: { source: Id; target: Id }) {
  const repos = attachedRepositories(p);
  const baseline = baselines(repos, pair.target);
  const inTarget = new Set(db.cicdEnvironmentCommits.filter((x) => x.environmentId === pair.target).map((x) => x.commitId));
  const diff = db.cicdEnvironmentCommits.filter((s) => {
    if (s.environmentId !== pair.source || !repos.includes(s.repositoryId) || inTarget.has(s.commitId)) return false;
    const b = baseline.get(s.repositoryId);
    return !(b && byTime(s.addedAt, b) < 0);
  });
  const commitOf = new Map(db.cicdCommits.map((c) => [c.id, c]));
  diff.sort((a, b) => byTime(b.addedAt, a.addedAt) || ((commitOf.get(a.commitId)?.sha ?? "") < (commitOf.get(b.commitId)?.sha ?? "") ? -1 : 1));
  const wpsOf = new Map<Id, Id[]>();
  for (const [wp, commits] of workPackageCommits()) for (const c of commits) wpsOf.set(c, [...(wpsOf.get(c) ?? []), wp]);
  const inTree = tree(p);
  const candidates = new Map<Id, Candidate>();
  const hidden = new Set<Id>();
  const unlinked: ReleaseUnlinkedCommit[] = [];
  for (const row of diff) {
    const commit = commitOf.get(row.commitId);
    const dep = db.cicdDeployments.find((d) => d.id === row.deploymentId);
    const repo = db.cicdRepositories.find((r) => r.id === row.repositoryId);
    if (!commit || !dep || !repo) continue;
    const linked = wpsOf.get(row.commitId) ?? [];
    if (!linked.length) {
      unlinked.push({ repository: repo.fullPath, shortSha: commit.sha.slice(0, 7), title: commit.title, webUrl: commit.webUrl, cameInAt: dep.deployedAt });
      continue;
    }
    for (const id of linked) {
      const wp = db.workPackages.find((w) => w.id === id);
      if (!wp || !inTree.has(wp.projectId) || candidates.has(id) || hidden.has(id)) continue;
      if (!visible(user, wp)) {
        hidden.add(id);
        continue;
      }
      candidates.set(id, { wp, deployedAt: dep.deployedAt, deploymentUrl: dep.webUrl });
    }
  }
  return { candidates, unlinked, repos, baseline };
}

/** Exclusions of this board that still hold: no commit of the work package came into S after it (addedBefore). */
function validExclusions(pair: { source: Id; target: Id }, latest: Map<Id, IsoDateTime>) {
  const out = new Map<Id, (typeof db.cicdBoardExclusions)[number]>();
  for (const x of db.cicdBoardExclusions) {
    if (x.environmentId !== pair.source || x.targetEnvironmentId !== pair.target) continue;
    const at = latest.get(x.wpId);
    if (at && byTime(at, x.addedBefore) <= 0) out.set(x.wpId, x);
  }
  return out;
}

/** The work packages on the board (visible, in the difference; without the excluded ones unless asked) and when their commits last came into S. */
function onBoard(user: DbUser, p: DbProject, pair: Pair, withoutExcluded: boolean): Map<Id, IsoDateTime | undefined> {
  const out = new Map<Id, IsoDateTime | undefined>();
  if (!pair) return out;
  const { candidates } = difference(user, p, pair);
  const latest = latestIn(pair.source, candidates.keys());
  const excluded = withoutExcluded ? validExclusions(pair, latest) : new Map();
  for (const id of candidates.keys()) if (!excluded.has(id)) out.set(id, latest.get(id));
  return out;
}

/**
 * The chosen version: an id of this project's versions, "none" (no version), or by default (보완 7) the open version ending soonest
 * from today, else the one that ended last, else the first by name without an end date, else none.
 */
function chooseVersion(user: DbUser, versions: (typeof db.versions)[number][], asked: string | undefined): Id | null {
  if (asked === "none") return null;
  if (asked !== undefined && asked !== null && asked !== "") {
    if (!versions.some((v) => v.id === asked)) throw new ApiError(404, "버전을 찾을 수 없습니다.");
    return asked;
  }
  const today = todayIso(user.timeZone, new Date(nowIso()));
  const open = versions.filter((v) => v.status === "open");
  const byName = (a: { name: string }, b: { name: string }) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const coming = open.filter((v) => v.endDate && v.endDate >= today).sort((a, b) => (a.endDate! < b.endDate! ? -1 : a.endDate! > b.endDate! ? 1 : byName(a, b)))[0];
  const ended = open.filter((v) => v.endDate).sort((a, b) => (a.endDate! > b.endDate! ? -1 : a.endDate! < b.endDate! ? 1 : byName(b, a)))[0];
  const undated = open.filter((v) => !v.endDate).sort(byName)[0];
  return (coming ?? ended ?? undated)?.id ?? null;
}

/**
 * Planned work packages (open, of the version in any project, visible) that are neither on the board nor excluded from it, and not out
 * already: every commit (in P's attached repositories) in T, or in S before the baseline. One without such commits stays missing.
 */
function missingOf(user: DbUser, pair: { source: Id; target: Id }, versionId: Id, onBoardIds: Set<Id>, repos: Id[], baseline: Map<Id, IsoDateTime>): ReleaseMissing[] {
  const closed = new Set(db.statuses.filter((s) => s.isClosed).map((s) => s.id));
  const planned = db.workPackages.filter((w) => w.versionId === versionId && !closed.has(w.statusId) && !onBoardIds.has(w.id) && visible(user, w));
  if (!planned.length) return [];
  const commits = workPackageCommits(new Set(planned.map((w) => w.id)));
  const inTarget = new Set(db.cicdEnvironmentCommits.filter((x) => x.environmentId === pair.target).map((x) => x.commitId));
  const out: ReleaseMissing[] = [];
  for (const w of planned) {
    const mine = [...(commits.get(w.id) ?? [])].filter((id) => repos.includes(db.cicdCommits.find((c) => c.id === id)?.repositoryId ?? ""));
    const shipped = mine.filter((id) => inTarget.has(id) || db.cicdEnvironmentCommits.some((s) => {
      if (s.environmentId !== pair.source || s.commitId !== id) return false;
      const b = baseline.get(s.repositoryId);
      return !!b && byTime(s.addedAt, b) < 0;
    }));
    if (mine.length > 0 && shipped.length === mine.length) continue;
    const linked = db.cicdLinks.some((l) => l.wpId === w.id && !l.dismissedAt);
    out.push({ workPackageId: w.id, number: w.number, subject: w.subject, status: statusRef(w.statusId), group: linked ? "developing" : "no_code" });
  }
  return out.sort((a, b) => (a.group === b.group ? 0 : a.group === "developing" ? -1 : 1) || a.number - b.number);
}

/** S first, then T; for each: checking, failed, first_cutoff, partial (the development tab's judgment). */
function noticesOf(pair: { source: Id; target: Id }, names: Map<Id, string>, repos: Id[]): ReleaseNotice[] {
  if (!repos.length) return [];
  const out: ReleaseNotice[] = [];
  for (const env of [pair.source, pair.target]) {
    const mine = db.cicdDeployments.filter((d) => d.environmentId === env && repos.includes(d.repositoryId));
    const newest = new Map<Id, Id>();
    for (const d of mine.filter((x) => x.status === "success").sort((a, b) => byTime(a.deployedAt, b.deployedAt))) newest.set(d.repositoryId, d.id);
    const checking = mine.some((d) => d.inclusion === "checking");
    const failed = mine.some((d) => d.inclusion === "failed" && newest.get(d.repositoryId) === d.id);
    const cutoff = mine.some((d) => d.inclusion === "first" && !!d.firstCutoffAt);
    const partial = db.cicdEnvironmentCommits.some((ec) => ec.environmentId === env && repos.includes(ec.repositoryId)
      && db.cicdDeployments.find((d) => d.id === ec.deploymentId)?.inclusion === "partial");
    const name = names.get(env) ?? "";
    if (checking) out.push({ environmentId: env, environment: name, kind: "checking" });
    if (failed) out.push({ environmentId: env, environment: name, kind: "failed" });
    if (cutoff) out.push({ environmentId: env, environment: name, kind: "first_cutoff" });
    if (partial) out.push({ environmentId: env, environment: name, kind: "partial" });
  }
  return out;
}

const envRef = (e: DbCicdEnvironment): ReleaseEnvironmentRef => ({ id: e.id, name: e.name, position: e.position, production: e.production, hidden: e.hidden });

function verificationDto(v: DbCicdVerification): ReleaseVerification {
  return {
    id: v.id, result: v.result, reason: v.reason, by: userRef(v.verifiedBy), at: v.verifiedAt,
    environment: db.cicdEnvironments.find((e) => e.id === v.environmentId)?.name ?? "",
  };
}

function board(user: DbUser, p: DbProject, source?: Id, target?: Id, version?: string): ReleaseBoard {
  const envs = environmentsOf(p);
  const pair = pairOf(envs, source, target);
  const versions = db.versions.filter((v) => v.projectId === p.id)
    .sort((a, b) => (a.endDate ?? "9999-12-31") < (b.endDate ?? "9999-12-31") ? -1 : (a.endDate ?? "9999-12-31") > (b.endDate ?? "9999-12-31") ? 1 : a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const versionId = chooseVersion(user, versions, version);
  const environments = envs.map(envRef);
  const versionRefs = versions.map((v) => ({ id: v.id, name: v.name, status: v.status, endDate: v.endDate }));
  const canVerify = can(user, p, "release.verify");
  if (!pair)
    return { environments, sourceId: null, targetId: null, versions: versionRefs, versionId, cards: [], missing: [], interloperCount: 0, unlinkedCount: 0, unlinked: [], excluded: [], notices: [], canVerify };

  const names = new Map(envs.map((e) => [e.id, e.name]));
  const { candidates, unlinked, repos, baseline } = difference(user, p, pair);
  const latest = latestIn(pair.source, candidates.keys());
  const exclusions = validExclusions(pair, latest);
  const shown = [...candidates.values()].filter((c) => !exclusions.has(c.wp.id));
  const current = currentVerifications(pair.source, shown.map((c) => c.wp.id));
  let interloperCount = 0;
  const cards: ReleaseCard[] = shown.map((c) => {
    const now = current.get(c.wp.id);
    const interloper = versionId !== null && versionId !== c.wp.versionId;
    if (interloper) interloperCount += 1;
    return {
      workPackageId: c.wp.id, number: c.wp.number, subject: c.wp.subject, status: statusRef(c.wp.statusId),
      assignees: teamOf(c.wp).map((t) => userRef(t.userId)).filter((u): u is UserRef => u !== null), project: projectRef(projectById(c.wp.projectId)!),
      column: columnOf(now, latest.get(c.wp.id)), cameInAt: c.deployedAt, deploymentUrl: c.deploymentUrl, last: now ? verificationDto(now) : null, interloper,
    };
  });
  cards.sort((a, b) => COLUMNS.indexOf(a.column) - COLUMNS.indexOf(b.column) || a.number - b.number);
  const excluded: ReleaseExcluded[] = [...candidates.values()].filter((c) => exclusions.has(c.wp.id)).map((c) => {
    const x = exclusions.get(c.wp.id)!;
    return { workPackageId: c.wp.id, number: c.wp.number, subject: c.wp.subject, by: userRef(x.excludedBy), at: x.excludedAt };
  }).sort((a, b) => byTime(b.at, a.at) || a.number - b.number);
  const missing = versionId === null ? [] : missingOf(user, pair, versionId, new Set(candidates.keys()), repos, baseline);
  return {
    environments, sourceId: pair.source, targetId: pair.target, versions: versionRefs, versionId, cards, missing, interloperCount,
    unlinkedCount: unlinked.length, unlinked: unlinked.slice(0, UNLINKED_LIMIT), excluded, notices: noticesOf(pair, names, repos), canVerify,
  };
}

/* ---------- verify, exclude, restore (CicdVerifications) ---------- */

function request(input: { workPackageId?: unknown; environmentId?: unknown; targetEnvironmentId?: unknown }) {
  const id = (v: unknown) => (typeof v === "string" && v.trim() ? v : null);
  const wp = id(input.workPackageId);
  const source = id(input.environmentId);
  const target = id(input.targetEnvironmentId);
  if (!wp) throw fieldError("workPackageId", "작업을 고르세요.");
  if (!source) throw fieldError("environmentId", "환경을 고르세요.");
  if (!target) throw fieldError("targetEnvironmentId", "환경을 고르세요.");
  return { wp, source, target };
}

/** 보완 11: one activity line (no field changes, no webhook) */
function writeVerificationJournal(user: DbUser, wp: DbWp, cause: DbJournal["cause"]): DbJournal {
  const now = nowIso();
  const version = db.journals.filter((j) => j.wpId === wp.id).length + 1;
  const j: DbJournal = { id: newId("j"), wpId: wp.id, version, userId: user.id, notesHtml: "", isInternal: false, changes: {}, cause, createdAt: now, updatedAt: now };
  db.journals.push(j);
  const p = projectById(wp.projectId);
  if (p) p.latestActivityAt = now;
  return j;
}

/** 보완 10: the assignees and co-assignees who are active and may see the work package, not the one who verified (always on, as mentions) */
function notifyAssignees(user: DbUser, wp: DbWp, journal: DbJournal) {
  const project = projectById(wp.projectId)!;
  for (const userId of new Set(teamOf(wp).map((t) => t.userId))) {
    if (userId === user.id) continue;
    const u = db.users.find((x) => x.id === userId && x.status === "active");
    if (!u || !can(u, project, "wp.view", wp)) continue;
    db.notifications.push({ id: newId("n"), recipientId: userId, wpId: wp.id, journalId: journal.id, actorId: user.id, reason: "verification_failed", readAt: null, createdAt: journal.updatedAt });
  }
}

/* ---------- release records (CicdReleases) ---------- */

function item(user: DbUser, i: (typeof db.cicdReleaseItems)[number]): ReleaseItem | null {
  const wp = i.wpId ? db.workPackages.find((w) => w.id === i.wpId) : undefined;
  if (!visible(user, wp)) return null;
  const v = i.verificationId ? db.cicdVerifications.find((x) => x.id === i.verificationId) : undefined;
  return { workPackageId: i.wpId, number: i.number, subject: i.subject, verification: v ? verificationDto(v) : null, warning: i.warning };
}

function recordView(user: DbUser, r: DbCicdRelease): { row: ReleaseRow; items: ReleaseItem[] } {
  const all = db.cicdReleaseItems.filter((i) => i.releaseId === r.id).sort((a, b) => a.number - b.number);
  const items = all.map((i) => item(user, i)).filter((i): i is ReleaseItem => i !== null);
  const env = db.cicdEnvironments.find((e) => e.id === r.environmentId)!;
  const row: ReleaseRow = {
    id: r.id, deployedAt: r.deployedAt, environment: envRef(env),
    sourceEnvironment: r.sourceEnvironmentId ? db.cicdEnvironments.find((e) => e.id === r.sourceEnvironmentId)?.name ?? null : null,
    repository: db.cicdRepositories.find((x) => x.id === r.repositoryId)?.fullPath ?? "", deploymentUrl: db.cicdDeployments.find((d) => d.id === r.deploymentId)?.webUrl ?? "",
    partial: r.partial, contentFailed: r.contentFailed, itemCount: items.length, warningCount: items.filter((i) => i.warning !== null).length,
    hiddenCount: all.length - items.length,
  };
  return { row, items };
}

export const releasesApi: Api["releases"] = {
  board: (project, params) =>
    run(() => {
      const user = sessionUser();
      return clone(board(user, viewable(user, project), params?.source, params?.target, params?.version));
    }),
  verify: (project, input) =>
    run(() => {
      const user = sessionUser();
      const p = viewable(user, project);
      mayVerify(user, p);
      const result = input.result;
      if (result !== "passed" && result !== "failed") throw fieldError("result", "결과는 통과(passed)나 실패(failed)여야 합니다.");
      // trimmed once; the same value is checked and stored
      let reason = "";
      if (result === "failed") {
        reason = typeof input.reason === "string" ? input.reason.trim() : "";
        if (!reason) throw fieldError("reason", "실패 사유를 적으세요.");
        if ([...reason].length > REASON_MAX) throw fieldError("reason", "실패 사유는 2,000자까지 적을 수 있습니다.");
      }
      const req = request(input);
      const { wp } = workPackage(user, req.wp);
      const pair = pairOf(environmentsOf(p), req.source, req.target);
      const basisAt = onBoard(user, p, pair, true).get(req.wp);
      if (!pair || !basisAt) throw new ApiError(422, NOT_ON_BOARD);
      const v: DbCicdVerification = { id: newId("cicd-ver"), wpId: wp.id, environmentId: pair.source, result, reason, verifiedBy: user.id, verifiedAt: nowIso(), basisAt };
      db.cicdVerifications.push(v);
      const environment = db.cicdEnvironments.find((e) => e.id === pair.source)!.name;
      const journal = writeVerificationJournal(user, wp, result === "failed"
        ? { kind: "verification_failed", environment, reason }
        : { kind: "verification_passed", environment });
      if (result === "failed") notifyAssignees(user, wp, journal);
      return clone(verificationDto(v));
    }),
  exclude: (project, input) =>
    run(() => {
      const user = sessionUser();
      const p = viewable(user, project);
      mayVerify(user, p);
      const req = request(input);
      const { wp } = workPackage(user, req.wp);
      const pair = pairOf(environmentsOf(p), req.source, req.target);
      // on the board before exclusions: excluding it again just renews the row
      const addedBefore = onBoard(user, p, pair, false).get(req.wp);
      if (!pair || !addedBefore) throw new ApiError(422, NOT_ON_BOARD);
      const at = nowIso();
      const row = { wpId: wp.id, environmentId: pair.source, targetEnvironmentId: pair.target, excludedBy: user.id, excludedAt: at, addedBefore };
      const old = db.cicdBoardExclusions.find((x) => x.wpId === wp.id && x.environmentId === pair.source && x.targetEnvironmentId === pair.target);
      if (old) Object.assign(old, row);
      else db.cicdBoardExclusions.push(row);
      return clone({ workPackageId: wp.id, number: wp.number, subject: wp.subject, by: userRef(user.id), at });
    }),
  restore: (project, input) =>
    run(() => {
      const user = sessionUser();
      const p = viewable(user, project);
      mayVerify(user, p);
      workPackage(user, input.workPackageId);
      // only this project's boards
      const mine = new Set(db.cicdEnvironments.filter((e) => e.projectId === p.id).map((e) => e.id));
      const before = db.cicdBoardExclusions.length;
      db.cicdBoardExclusions = db.cicdBoardExclusions.filter((x) =>
        !(x.wpId === input.workPackageId && x.environmentId === input.environmentId && x.targetEnvironmentId === input.targetEnvironmentId && mine.has(x.environmentId)));
      if (db.cicdBoardExclusions.length === before) throw new ApiError(404, "뺀 작업을 찾을 수 없습니다.");
    }),
  records: (project, page = 1, pageSize = 20) =>
    run(() => {
      const user = sessionUser();
      const p = viewable(user, project);
      const pg = Math.max(1, Math.floor(page));
      const size = Math.min(100, Math.max(1, Math.floor(pageSize)));
      const all = db.cicdReleases.filter((r) => r.projectId === p.id).sort((a, b) => byTime(b.deployedAt, a.deployedAt) || (a.id < b.id ? -1 : 1));
      const items = all.slice((pg - 1) * size, pg * size).map((r) => recordView(user, r).row);
      return clone({ items, total: all.length, page: pg, pageSize: size });
    }),
  record: (project, id) =>
    run(() => {
      const user = sessionUser();
      const p = viewable(user, project);
      const r = db.cicdReleases.find((x) => x.id === id && x.projectId === p.id);
      if (!r) throw new ApiError(404, "릴리스 기록을 찾을 수 없습니다.");
      const { row, items } = recordView(user, r);
      return clone({ release: row, items });
    }),
};
