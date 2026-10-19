// CI/CD 연동 in the fake layer: the same rules and messages as the server's cicd/CicdConnections, CicdRepositories, CicdEnvironments and
// DevTabController. A project sees only the connections whose allowed list holds it (V27). There is no webhook and no code host here: the seeded merge
// requests, commits, builds and deployments are what the development tab shows, the repository search is a fixed list of three, attaching only makes the
// repository. Deviations from the server, on purpose: an environment takes an unassigned deployment's commit at once (the server checks in the background);
// "failed" shows for a failed deployment of an environment that holds commits (the server asks for its head); the image tag rule is a JavaScript regular
// expression, so a rule's test result can differ from the server's.
import type { Api } from "../api";
import type {
  CicdBuild, CicdBuildStatus, CicdCommit, CicdConnection, CicdEnvironment, CicdEnvironmentStatus, CicdEnvState, CicdMergeRequest, CicdRepository, CicdTemplates,
  DevInfo, Id,
} from "../types";
import { ApiError } from "../errors";
import { nowIso, todayIso } from "@/lib/dates";
import { db, newId, type DbProject, type DbUser } from "./db";
import { can, findProject, findWp, globalPermissions, requirePerm, sessionUser } from "./permissions";
import { clone, userRef } from "./dto";
import type { DbCicdEnvironment, DbCicdMergeRequest, DbCicdPipeline } from "./cicd-seed";
import { fieldError, run } from "./util";

const COMMIT_LIMIT = 50;
const KINDS = ["gitlab", "github", "jenkins", "argocd"];
/** Jenkins and Argo CD tell openPMS by webhook: no API token, an address and an image tag rule instead */
const NOTICE_KINDS = ["jenkins", "argocd"];

/** what the code host would list: the same three paths for every connection, ids as the dev data uses them */
const REMOTE: { externalId: string; fullPath: string }[] = [
  { externalId: "101", fullPath: "web/portal" },
  { externalId: "103", fullPath: "web/admin" },
  { externalId: "102", fullPath: "mobile/app" },
];

/* ---------- connections (관리 › CI/CD 연동) ---------- */

function requireManager(user: DbUser) {
  if (!user.isAdmin && !globalPermissions(user).includes("global.manage_cicd")) throw new ApiError(403, "CI/CD 연동을 관리할 권한이 없습니다.");
}

const hint = (plain: string) => (plain.length <= 4 ? "****" : plain.slice(-4));

/** the server's SecureRandom 32 bytes, URL-safe Base64 */
function random() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** the allowed projects the person may see, by name (sorted); the others are counted only */
function allowedOf(user: DbUser, connectionId: Id) {
  const projects: { id: Id; name: string }[] = [];
  let hidden = 0;
  for (const row of db.cicdConnectionProjects.filter((x) => x.connectionId === connectionId)) {
    const p = db.projects.find((x) => x.id === row.projectId);
    if (!p) continue;
    if (user.isAdmin || can(user, p, "project.view")) projects.push({ id: p.id, name: p.name });
    else hidden += 1;
  }
  projects.sort((a, b) => a.name.localeCompare(b.name, "ko") || (a.id < b.id ? -1 : 1));
  return { projects, hiddenProjectCount: hidden };
}

const isAttached = (r: { detachedAt?: string | null }) => !r.detachedAt;

const toConnection = (user: DbUser, c: (typeof db.cicdConnections)[number]): CicdConnection => ({
  id: c.id, kind: c.kind, name: c.name, baseUrl: c.baseUrl, webhookUrl: `/api/v1/hooks/cicd/${c.hookKey}`, tokenHint: c.tokenHint, secretHint: c.secretHint,
  active: c.active, repositoryCount: db.cicdRepositories.filter((r) => r.connectionId === c.id && isAttached(r)).length, createdAt: c.createdAt,
  ...allowedOf(user, c.id), toolUrl: c.toolUrl ?? null, imageTagPattern: c.imageTagPattern ?? null,
});

const allows = (connectionId: Id, projectId: Id) => db.cicdConnectionProjects.some((x) => x.connectionId === connectionId && x.projectId === projectId);

function checkName(v: unknown) {
  const n = typeof v === "string" ? v.trim() : "";
  if (!n || n.length > 100) throw fieldError("name", "이름은 1자에서 100자 사이여야 합니다.");
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(n)) throw fieldError("name", "이름에 쓸 수 없는 문자가 있습니다.");
  return n;
}

function checkBaseUrl(v: unknown) {
  const bad = fieldError("baseUrl", "주소 형식이 올바르지 않습니다.");
  if (typeof v !== "string") throw bad;
  const s = v.trim().replace(/\/+$/, "");
  let u: URL;
  try {
    u = new URL(s);
  } catch {
    throw bad;
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") throw bad;
  if (!u.hostname || u.username || u.password || u.search || u.hash || /[?#@]/.test(s)) throw bad;
  return s;
}

const duplicate = () => new ApiError(409, "같은 이름의 연동이 있습니다.");

function findConnection(id: Id) {
  const c = db.cicdConnections.find((x) => x.id === id);
  if (!c) throw new ApiError(404, "연동을 찾을 수 없습니다.");
  return c;
}

/** Jenkins and Argo CD take no API token (a blank one is no token) */
function rejectToken(token: unknown) {
  if (typeof token === "string" && token.trim()) throw fieldError("apiToken", "Jenkins·Argo CD 연동에는 API 토큰을 쓰지 않습니다.");
}

/* ---------- the image tag rule (Jenkins, Argo CD) ---------- */

const DEFAULT_TAG_PATTERN = "[0-9a-f]{7,40}";
const HEX_SHA = /^[0-9a-f]{7,40}$/;
const DIGITS_ONLY = /^[0-9]+$/;

/** null for a blank rule (the default); a rule over 200 characters, with a control character or not a regular expression is 422 */
function checkTagPattern(v: unknown): string | null {
  if (v === null || v === undefined || (typeof v === "string" && !v.trim())) return null;
  const bad = fieldError("imageTagPattern", "이미지 태그 규칙이 올바르지 않습니다.");
  if (typeof v !== "string") throw bad;
  const p = v.trim();
  // eslint-disable-next-line no-control-regex
  if (p.length > 200 || /[\u0000-\u001f]/.test(p)) throw bad;
  try {
    new RegExp(p);
  } catch {
    throw bad;
  }
  return p;
}

/** what follows the last ':' after the last '/', before any "@digest", at most 128 characters */
function tagOf(image: string | null | undefined) {
  let s = (image ?? "").trim();
  const at = s.indexOf("@");
  if (at >= 0) s = s.slice(0, at);
  const slash = s.lastIndexOf("/");
  const colon = s.lastIndexOf(":");
  if (colon <= slash) return "";
  const tag = s.slice(colon + 1);
  return !tag || tag.length > 128 ? "" : tag;
}

/** the first group of the rule (or the whole match) must be 7-40 hex digits and not digits only (a date or build number is no commit) */
function shaOfImage(image: string | null | undefined, pattern: string | null): string | null {
  const tag = tagOf(image);
  if (!tag) return null;
  for (const m of tag.matchAll(new RegExp(pattern ?? DEFAULT_TAG_PATTERN, "gi"))) {
    const found = (m[1] ?? m[0]).toLowerCase();
    if (HEX_SHA.test(found) && !DIGITS_ONLY.test(found)) return found;
  }
  return null;
}

/** the part an administrator pastes (the real texts are longer): the webhook secret is never in it, only a placeholder or a reference */
function templatesOf(kind: string, webhookUrl: string): CicdTemplates {
  if (kind === "jenkins")
    return {
      kind, argocd: null,
      jenkinsfile: [
        "// openPMS에 빌드·배포 결과를 알린다. Jenkins 자격 증명(Secret text) 'openpms-webhook-secret'에 <웹훅 비밀>을 넣어 두세요.",
        "def notifyOpenPms(Map n) {",
        "  withCredentials([string(credentialsId: 'openpms-webhook-secret', variable: 'OPENPMS_SECRET')]) {",
        `    // POST ${webhookUrl} (X-OpenPMS-Signature: sha256=...)`,
        "  }",
        "}",
      ].join("\n"),
    };
  if (kind === "argocd")
    return {
      kind, jenkinsfile: null,
      argocd: [
        "# openPMS에 배포 결과를 알린다. Argo CD Notifications의 설정이다.",
        "apiVersion: v1",
        "kind: ConfigMap",
        "metadata:",
        "  name: argocd-notifications-cm",
        "data:",
        "  service.webhook.openpms: |",
        `    url: ${webhookUrl}`,
        "    headers:",
        "    - name: X-OpenPMS-Token",
        "      value: $openpms-webhook-secret",
      ].join("\n"),
    };
  return { kind: kind as CicdTemplates["kind"], jenkinsfile: null, argocd: null };
}

/* ---------- repositories (프로젝트 › CI/CD) ---------- */

/** the project visible with the module on and cicd.view, else the same 404 */
function viewable(user: DbUser, project: string): DbProject {
  const p = findProject(user, project);
  if (!can(user, p, "cicd.view")) throw new ApiError(404, "프로젝트를 찾을 수 없습니다.");
  return p;
}
function manageable(user: DbUser, project: string): DbProject {
  const p = viewable(user, project);
  requirePerm(user, p, "cicd.manage", "저장소를 관리할 권한이 없습니다.");
  return p;
}

function activeConnection(id: Id | null | undefined, p: DbProject) {
  const c = id ? db.cicdConnections.find((x) => x.id === id) : undefined;
  // not on the allowed list: the same 404, the connection's existence is not revealed
  if (!c || !c.active || !allows(c.id, p.id)) throw new ApiError(404, "연동을 찾을 수 없습니다.");
  if (c.tokenHint === null) throw new ApiError(422, "이 연동에는 API 토큰이 없어 저장소를 찾을 수 없습니다.");
  return c;
}

const toRepository = (r: (typeof db.cicdRepositories)[number]): CicdRepository => {
  const c = db.cicdConnections.find((x) => x.id === r.connectionId)!;
  return { id: r.id, connectionName: c.name, kind: c.kind, fullPath: r.fullPath, webUrl: r.webUrl, lastPolledAt: r.lastPolledAt, detachedAt: r.detachedAt ?? null };
};

const taken = (ownerProjectId: Id, p: DbProject) => new ApiError(409, ownerProjectId === p.id ? "이미 이 프로젝트에 붙은 저장소입니다." : "이미 다른 프로젝트에 붙은 저장소입니다.");

/* ---------- build records (프로젝트 › CI/CD › 빌드 기록 정리) ---------- */

/** 00:00 Asia/Seoul of the chosen day (as a time); a missing or non-date value is 422, and so is a day after today */
function cutoff(before: unknown): number {
  const bad = fieldError("before", "날짜를 고르세요.");
  if (typeof before !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(before)) throw bad;
  const utc = new Date(`${before}T00:00:00Z`);
  if (Number.isNaN(utc.getTime()) || utc.toISOString().slice(0, 10) !== before) throw bad;
  if (before > todayIso("Asia/Seoul", new Date(nowIso()))) throw fieldError("before", "오늘이나 그 앞 날짜를 고르세요.");
  return Date.parse(`${before}T00:00:00+09:00`);
}

/** the project's builds (of its repositories, detached ones too) that ended, or were last heard of, before the cutoff */
function oldBuilds(p: DbProject, cut: number) {
  const repos = new Set(db.cicdRepositories.filter((r) => r.projectId === p.id).map((r) => r.id));
  return db.cicdPipelines.filter((x) => repos.has(x.repositoryId) && Date.parse(x.finishedAt ?? x.updatedAt) < cut);
}

/* ---------- environments (프로젝트 › CI/CD › 환경) ---------- */

function envManageable(user: DbUser, project: string): DbProject {
  const p = viewable(user, project);
  requirePerm(user, p, "cicd.manage", "환경을 관리할 권한이 없습니다.");
  return p;
}

/** the same three the server gives a project whose module was just switched on (or a copy); nothing when it has an environment already */
export function ensureCicdEnvironments(projectId: Id) {
  if (db.cicdEnvironments.some((e) => e.projectId === projectId)) return;
  const defaults: [string, boolean, string[]][] = [["개발", false, ["dev", "development"]], ["스테이징", false, ["stg", "staging"]], ["운영", true, ["prod", "production"]]];
  defaults.forEach(([name, production, aliases], i) =>
    db.cicdEnvironments.push({ id: newId("cicd-env"), projectId, name, position: i + 1, production, aliases, hidden: false }));
}

// eslint-disable-next-line no-control-regex
const hasControl = (s: string) => /[\u0000-\u001f\u007f-\u009f]/.test(s);

function envName(raw: unknown) {
  const n = typeof raw === "string" ? raw.trim() : "";
  if (!n || n.length > 50 || hasControl(n)) throw fieldError("name", "환경 이름은 1자에서 50자 사이여야 합니다.");
  return n;
}

/** an array of strings, each 1-100 characters without commas or control characters; trimmed, lower-cased, no repeats, 20 at most */
function envAliases(raw: unknown): string[] {
  const bad = fieldError("aliases", "별칭이 올바르지 않습니다.");
  if (!Array.isArray(raw)) throw bad;
  const out = new Set<string>();
  for (const o of raw) {
    if (typeof o !== "string") throw bad;
    const a = o.trim().toLowerCase();
    if (!a || a.length > 100 || a.includes(",") || hasControl(a)) throw bad;
    out.add(a);
  }
  if (out.size > 20) throw bad;
  return [...out];
}

const sameEnvName = () => new ApiError(409, "같은 이름의 환경이 있습니다.");

/** a name or alias another environment of the project already answers to is refused: it would match two */
function checkClash(others: DbCicdEnvironment[], name: string, aliases: string[]) {
  const lower = name.toLowerCase();
  for (const o of others) {
    if (o.aliases.includes(lower) && o.name.toLowerCase() !== lower) throw fieldError("name", `다른 환경이 이미 쓰는 이름입니다: ${name}`);
    for (const a of aliases) if (o.name.toLowerCase() === a || o.aliases.includes(a)) throw fieldError("aliases", `다른 환경이 이미 쓰는 이름입니다: ${a}`);
  }
}

function findEnvironment(p: DbProject, id: Id) {
  const e = db.cicdEnvironments.find((x) => x.id === id && x.projectId === p.id);
  if (!e) throw new ApiError(404, "환경을 찾을 수 없습니다.");
  return e;
}

const toEnvironment = (e: DbCicdEnvironment): CicdEnvironment => ({
  id: e.id, name: e.name, position: e.position, production: e.production, aliases: [...e.aliases], hidden: e.hidden,
  deploymentCount: db.cicdDeployments.filter((d) => d.environmentId === e.id).length,
});

/**
 * An alias was added: this project's unassigned deployments with that name join the environment (repositories whose current project this is,
 * detached ones too). A successful deployment's commit is in the environment at once.
 */
function reassign(p: DbProject, environmentId: Id, names: string[]) {
  const repos = new Set(db.cicdRepositories.filter((r) => r.projectId === p.id).map((r) => r.id));
  for (const d of db.cicdDeployments) {
    if (d.environmentId !== null || !repos.has(d.repositoryId) || !names.includes(d.environmentName.toLowerCase())) continue;
    d.environmentId = environmentId;
    d.inclusion = d.status === "success" ? (d.sha ? "compared" : "unknown_commit") : null;
    const commit = d.status === "success" && d.sha ? db.cicdCommits.find((c) => c.repositoryId === d.repositoryId && c.sha === d.sha) : undefined;
    if (commit && !db.cicdEnvironmentCommits.some((x) => x.environmentId === environmentId && x.commitId === commit.id))
      db.cicdEnvironmentCommits.push({ environmentId, repositoryId: d.repositoryId, commitId: commit.id, deploymentId: d.id });
  }
}

function environmentsView(user: DbUser, p: DbProject) {
  const environments = db.cicdEnvironments.filter((e) => e.projectId === p.id)
    .sort((a, b) => a.position - b.position || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)).map(toEnvironment);
  const repos = new Set(db.cicdRepositories.filter((r) => r.projectId === p.id).map((r) => r.id));
  const groups = new Map<string, { count: number; lastAt: string }>();
  for (const d of db.cicdDeployments) {
    if (d.environmentId !== null || !repos.has(d.repositoryId)) continue;
    const name = d.environmentName.toLowerCase();
    const g = groups.get(name);
    if (!g) groups.set(name, { count: 1, lastAt: d.deployedAt });
    else {
      g.count += 1;
      if (d.deployedAt > g.lastAt) g.lastAt = d.deployedAt;
    }
  }
  const unassigned = [...groups].map(([name, g]) => ({ name, count: g.count, lastAt: g.lastAt }))
    .sort((a, b) => b.count - a.count || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)).slice(0, 50);
  return { environments, unassigned, canManage: can(user, p, "cicd.manage") };
}

/* ---------- the development tab ---------- */

/** the work package must be visible and the person needs cicd.view (module on): else the same 404 */
function viewableWp(user: DbUser, number: number) {
  const found = findWp(user, number);
  if (!can(user, found.project, "cicd.view")) throw new ApiError(404, "작업을 찾을 수 없습니다.");
  return found;
}
function linkableWp(user: DbUser, number: number) {
  const found = viewableWp(user, number);
  requirePerm(user, found.project, "cicd.link", "개발 연결을 고칠 권한이 없습니다.");
  return found;
}

/** one badge for several workflows: any failure shows, then running; all canceled is canceled */
function summarize(latestPerName: CicdBuildStatus[]): CicdBuildStatus {
  if (latestPerName.includes("failed")) return "failed";
  if (latestPerName.includes("running")) return "running";
  if (latestPerName.every((s) => s === "canceled")) return "canceled";
  return "success";
}

/** the badge of a commit (field sha) or a branch (field ref): the newest build of each workflow name, summed up */
function buildOf(repositoryId: Id, field: "sha" | "ref", value: string | null | undefined): CicdBuild | null {
  if (!value) return null;
  const latest = new Map<string, DbCicdPipeline>();
  for (const x of db.cicdPipelines) {
    if (x.repositoryId !== repositoryId || x[field] !== value) continue;
    const old = latest.get(x.name);
    if (!old || x.updatedAt > old.updatedAt) latest.set(x.name, x);
  }
  if (!latest.size) return null;
  const list = [...latest.values()];
  const status = summarize(list.map((x) => x.status));
  const newest = list.filter((x) => x.status === status).reduce((a, b) => (b.updatedAt > a.updatedAt ? b : a));
  return { status, webUrl: newest.webUrl, at: newest.finishedAt ?? newest.updatedAt, source: newest.source, count: list.length };
}

function toMr(linkId: Id, m: DbCicdMergeRequest, source: CicdMergeRequest["source"]): CicdMergeRequest {
  const repo = db.cicdRepositories.find((r) => r.id === m.repositoryId)!;
  return {
    linkId, repository: repo.fullPath, iid: m.iid, title: m.title, state: m.state, sourceBranch: m.sourceBranch, targetBranch: m.targetBranch,
    author: m.authorName, user: userRef(m.userId), mergedAt: m.mergedAt, updatedAt: m.updatedAt, webUrl: m.webUrl, source,
    build: buildOf(m.repositoryId, "ref", m.sourceBranch), detached: !isAttached(repo),
  };
}

/** Trim, drop a #fragment and a ?query, drop trailing slashes (server: DevTabController.normalize). */
function normalize(raw: string | null | undefined) {
  let s = (raw ?? "").trim();
  const hash = s.indexOf("#");
  if (hash >= 0) s = s.slice(0, hash);
  const q = s.indexOf("?");
  if (q >= 0) s = s.slice(0, q);
  return s.replace(/\/+$/, "");
}

/** a repository covers its project and the sub-projects: the work package's project and its ancestors */
function projectAncestors(p: DbProject): Id[] {
  const out: Id[] = [];
  for (let x: DbProject | undefined = p; x && !out.includes(x.id); x = db.projects.find((y) => y.id === x!.parentId)) out.push(x.id);
  return out;
}

/** the project and its ancestors whose CI/CD this person may see (a member of a private child is not shown the parent's) */
function visibleAncestors(user: DbUser, p: DbProject): Id[] {
  return projectAncestors(p).filter((id) => {
    const x = db.projects.find((y) => y.id === id);
    return !!x && can(user, x, "cicd.view");
  });
}

/**
 * The environment row: for each project that has linked commits, its visible environments in order, with how many of those commits are in.
 * Linked commits: commit links and the merge commit of linked merged merge requests, in repositories of the visible projects.
 */
function environmentRow(user: DbUser, wpId: Id, project: DbProject, shown: Id[]) {
  const visible = new Set(visibleAncestors(user, project));
  const active = db.cicdLinks.filter((l) => l.wpId === wpId && !l.dismissedAt);
  const ids = new Set<Id>();
  for (const l of active) {
    if (l.commitId) ids.add(l.commitId);
    const m = l.mergeRequestId ? db.cicdMergeRequests.find((x) => x.id === l.mergeRequestId) : undefined;
    if (m && m.state === "merged" && m.mergeCommitSha)
      for (const c of db.cicdCommits) if (c.repositoryId === m.repositoryId && c.sha === m.mergeCommitSha) ids.add(c.id);
  }
  const linked = [...ids].flatMap((id) => {
    const commit = db.cicdCommits.find((c) => c.id === id);
    const repo = commit && db.cicdRepositories.find((r) => r.id === commit.repositoryId);
    return commit && repo && visible.has(repo.projectId) ? [{ commit, repo }] : [];
  });
  const names = new Map<Id, string[]>();
  if (!linked.length) return { environments: [] as CicdEnvironmentStatus[], names };
  const projectIds = new Set(linked.map((l) => l.repo.projectId));
  const envs = db.cicdEnvironments.filter((e) => projectIds.has(e.projectId) && !e.hidden);
  if (!envs.length) return { environments: [] as CicdEnvironmentStatus[], names };
  const envById = new Map(envs.map((e) => [e.id, e]));
  const commitOf = new Map(linked.map((l) => [l.commit.id, l]));
  // what is in each environment (only environments of the commit's own current project)
  const includedIn = new Map<Id, Set<Id>>();
  const partialCheck = new Set<Id>();
  const latest = new Map<Id, { deployedAt: string; webUrl: string }>();
  const commitEnvs = new Map<Id, DbCicdEnvironment[]>();
  for (const x of db.cicdEnvironmentCommits) {
    const env = envById.get(x.environmentId);
    const l = commitOf.get(x.commitId);
    const d = db.cicdDeployments.find((y) => y.id === x.deploymentId);
    if (!env || !l || !d || env.projectId !== l.repo.projectId) continue;
    if (!includedIn.has(env.id)) includedIn.set(env.id, new Set());
    includedIn.get(env.id)!.add(l.commit.id);
    commitEnvs.set(l.commit.id, [...(commitEnvs.get(l.commit.id) ?? []), env]);
    if (d.inclusion === "partial") partialCheck.add(env.id);
    const old = latest.get(env.id);
    if (!old || d.deployedAt > old.deployedAt) latest.set(env.id, { deployedAt: d.deployedAt, webUrl: d.webUrl });
  }
  const repositoryIds = new Set(linked.map((l) => l.repo.id));
  const waiting = db.cicdDeployments.filter((d) => {
    if (!d.environmentId || !envById.has(d.environmentId) || !repositoryIds.has(d.repositoryId)) return false;
    return d.inclusion === "checking" || (d.inclusion === "first" && !!d.firstCutoffAt) || (d.inclusion === "failed" && db.cicdEnvironmentCommits.some((x) => x.environmentId === d.environmentId));
  });
  const projectNames = new Map<Id, string>();
  for (const l of linked) projectNames.set(l.repo.projectId, db.projects.find((p) => p.id === l.repo.projectId)?.name ?? "");
  const out: CicdEnvironmentStatus[] = [];
  const order = [...projectNames].sort((a, b) => a[1].localeCompare(b[1], "ko") || (a[0] < b[0] ? -1 : 1));
  for (const [projectId, projectName] of order) {
    const commits = linked.filter((l) => l.repo.projectId === projectId);
    const repos = new Set(commits.map((l) => l.repo.id));
    for (const env of envs.filter((e) => e.projectId === projectId).sort((a, b) => a.position - b.position)) {
      const inSet = includedIn.get(env.id) ?? new Set<Id>();
      const total = commits.length;
      const included = commits.filter((l) => inSet.has(l.commit.id)).length;
      const mine = waiting.filter((d) => d.environmentId === env.id && repos.has(d.repositoryId));
      // what is known stays shown: every linked commit already in is in (with a note while a newer deployment is compared)
      const checking = mine.some((d) => d.inclusion === "checking");
      let state: CicdEnvState;
      if (included === total) state = "in";
      else if (checking) state = "checking";
      else if (included > 0) state = "partial";
      else if (mine.some((d) => d.inclusion === "failed")) state = "failed";
      else if (beforeFirst(commits, mine)) state = "before_first";
      else state = "none";
      const last = latest.get(env.id);
      out.push({
        id: env.id, name: env.name, production: env.production, state, included, total, partialCheck: partialCheck.has(env.id), checking,
        deployedAt: last?.deployedAt ?? null, webUrl: last?.webUrl ?? "", project: projectNames.size > 1 ? projectName : null,
      });
    }
  }
  for (const id of shown) {
    const list = commitEnvs.get(id);
    if (list) names.set(id, [...list].sort((a, b) => b.position - a.position).map((e) => e.name));
  }
  return { environments: out, names };
}

/** every linked commit is older than the cutoff of its repository's first deployment (the check could not look further back) */
function beforeFirst(commits: { commit: { committedAt: string }; repo: { id: Id } }[], deployments: { repositoryId: Id; inclusion: string | null; firstCutoffAt?: string | null }[]) {
  const cutoffs = new Map<Id, string>();
  for (const d of deployments) {
    if (d.inclusion !== "first" || !d.firstCutoffAt) continue;
    const old = cutoffs.get(d.repositoryId);
    if (!old || d.firstCutoffAt < old) cutoffs.set(d.repositoryId, d.firstCutoffAt);
  }
  return commits.length > 0 && commits.every((l) => {
    const c = cutoffs.get(l.repo.id);
    return !!c && l.commit.committedAt < c;
  });
}

export const cicdApi: Api["cicd"] = {
  connections: () =>
    run(() => {
      const user = sessionUser();
      requireManager(user);
      return db.cicdConnections.slice().sort((a, b) => a.name.localeCompare(b.name, "ko")).map((c) => toConnection(user, c));
    }),
  createConnection: (input) =>
    run(() => {
      const user = sessionUser();
      requireManager(user);
      if (!KINDS.includes(input.kind)) throw fieldError("kind", "지원하지 않는 도구입니다.");
      const name = checkName(input.name);
      const baseUrl = checkBaseUrl(input.baseUrl);
      const notice = NOTICE_KINDS.includes(input.kind);
      if (notice) rejectToken(input.apiToken);
      const pattern = notice ? checkTagPattern(input.imageTagPattern) : null;
      if (db.cicdConnections.some((c) => c.name === name)) throw duplicate();
      const secret = random();
      const token = input.apiToken;
      const c = {
        id: newId("cicd-conn"), kind: input.kind, name, baseUrl, hookKey: random(), tokenHint: !notice && token && token.trim() ? hint(token) : null,
        secretHint: hint(secret), active: true, createdBy: db.sessionUserId, createdAt: nowIso(), toolUrl: notice ? baseUrl : null, imageTagPattern: pattern,
      };
      db.cicdConnections.push(c);
      return { connection: toConnection(user, c), webhookSecret: secret };
    }),
  updateConnection: (id, patch) =>
    run(() => {
      const user = sessionUser();
      requireManager(user);
      const c = findConnection(id);
      const notice = NOTICE_KINDS.includes(c.kind);
      if (notice && patch.apiToken !== undefined) rejectToken(patch.apiToken);
      const next = { ...c };
      if (patch.name !== undefined) {
        const name = checkName(patch.name);
        if (name !== c.name && db.cicdConnections.some((x) => x.name === name)) throw duplicate();
        next.name = name;
      }
      if (patch.baseUrl !== undefined) {
        const url = checkBaseUrl(patch.baseUrl);
        // a stored token must never follow the connection to another address
        if (url !== c.baseUrl) {
          next.baseUrl = url;
          if (notice) next.toolUrl = url;
          next.tokenHint = null;
        }
      }
      if (notice && patch.imageTagPattern !== undefined) next.imageTagPattern = checkTagPattern(patch.imageTagPattern);
      if (!notice && patch.apiToken !== undefined) next.tokenHint = patch.apiToken === null || patch.apiToken === "" ? null : hint(patch.apiToken);
      if (patch.active !== undefined) {
        if (typeof patch.active !== "boolean") throw fieldError("active", "켜짐 여부는 참/거짓이어야 합니다.");
        next.active = patch.active;
      }
      Object.assign(c, next);
      return toConnection(user, c);
    }),
  setConnectionProjects: (id, projectIds) =>
    run(() => {
      const user = sessionUser();
      requireManager(user);
      if (!user.isAdmin) throw new ApiError(403, "허용 프로젝트는 시스템 관리자만 바꿀 수 있습니다.");
      const c = findConnection(id);
      if (!Array.isArray(projectIds)) throw fieldError("projectIds", "허용 프로젝트 목록이 올바르지 않습니다.");
      if (projectIds.length > 1000) throw fieldError("projectIds", "허용 프로젝트가 너무 많습니다.");
      const wanted = [...new Set(projectIds)];
      if (wanted.some((pid) => !db.projects.some((p) => p.id === pid))) throw fieldError("projectIds", "없는 프로젝트가 있습니다.");
      const current = db.cicdConnectionProjects.filter((x) => x.connectionId === c.id).map((x) => x.projectId);
      const removed = current.filter((pid) => !wanted.includes(pid));
      // only a repository that is attached blocks it; a detached one keeps its history and no longer needs the project on the list
      if (db.cicdRepositories.some((r) => r.connectionId === c.id && isAttached(r) && removed.includes(r.projectId)))
        throw new ApiError(409, "이 연동의 저장소가 붙어 있는 프로젝트는 뺄 수 없습니다. 먼저 저장소를 떼세요.");
      db.cicdConnectionProjects = [
        ...db.cicdConnectionProjects.filter((x) => x.connectionId !== c.id),
        ...wanted.map((projectId) => ({ connectionId: c.id, projectId })),
      ];
      return toConnection(user, c);
    }),
  rotateSecret: (id) =>
    run(() => {
      requireManager(sessionUser());
      const c = findConnection(id);
      const secret = random();
      c.secretHint = hint(secret);
      return { webhookSecret: secret };
    }),
  checkConnection: (id) =>
    run(() => {
      requireManager(sessionUser());
      return findConnection(id).tokenHint === null ? { ok: false, status: 0 } : { ok: true, status: 200 };
    }),
  deleteConnection: (id) =>
    run(() => {
      requireManager(sessionUser());
      const c = findConnection(id);
      // detached repositories keep their history (builds, deployments), so they count too
      if (db.cicdRepositories.some((r) => r.connectionId === id)) throw new ApiError(409, "기록이 남은 저장소(뗀 저장소 포함)가 있는 연동은 지울 수 없습니다. 대신 끄세요.");
      db.cicdConnections = db.cicdConnections.filter((x) => x !== c);
      db.cicdConnectionProjects = db.cicdConnectionProjects.filter((x) => x.connectionId !== c.id);
    }),
  connectionTemplates: (id) =>
    run(() => {
      requireManager(sessionUser());
      const c = findConnection(id);
      return templatesOf(c.kind, `/api/v1/hooks/cicd/${c.hookKey}`);
    }),
  testImageTag: (pattern, image) =>
    run(() => {
      requireManager(sessionUser());
      const rule = checkTagPattern(pattern);
      if (typeof image === "string" && image.length > 500) throw fieldError("image", "이미지 이름이 너무 깁니다.");
      return { sha: shaOfImage(image, rule) };
    }),

  projectConnections: (project) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      // code hosts only: Jenkins·Argo CD connections send notices and have no repositories to attach
      return db.cicdConnections.filter((c) => c.active && (c.kind === "gitlab" || c.kind === "github") && allows(c.id, p.id)).sort((a, b) => a.name.localeCompare(b.name, "ko")).map((c) => ({ id: c.id, kind: c.kind, name: c.name }));
    }),
  searchRepositories: (project, connectionId, q) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      if (q && q.length > 100) throw fieldError("q", "검색어가 너무 깁니다.");
      const c = activeConnection(connectionId, p);
      const needle = (q ?? "").trim().toLowerCase();
      return REMOTE.filter((r) => r.fullPath.toLowerCase().includes(needle)).map((r) => {
        const out: { externalId: string; fullPath: string; webUrl: string; attachedProject?: boolean } = {
          externalId: r.externalId, fullPath: r.fullPath, webUrl: `${c.baseUrl}/${r.fullPath}`,
        };
        // a detached repository is free to attach again
        if (db.cicdRepositories.some((x) => x.connectionId === c.id && x.externalId === r.externalId && isAttached(x))) out.attachedProject = true;
        return out;
      });
    }),
  repositories: (project) =>
    run(() => {
      const p = viewable(sessionUser(), project);
      // attached first, then by path
      return db.cicdRepositories.filter((r) => r.projectId === p.id)
        .sort((a, b) => Number(!isAttached(a)) - Number(!isAttached(b)) || (a.fullPath < b.fullPath ? -1 : a.fullPath > b.fullPath ? 1 : 0)).map(toRepository);
    }),
  attachRepository: (project, input) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const externalId = input.externalId;
      if (typeof externalId !== "string" || !externalId.trim() || externalId.length > 200) throw fieldError("externalId", "저장소를 고르세요.");
      const c = activeConnection(input.connectionId, p);
      const existing = db.cicdRepositories.find((r) => r.connectionId === c.id && r.externalId === externalId);
      if (existing && isAttached(existing)) throw taken(existing.projectId, p);
      // what is stored is what the host says: the fixed list here
      const remote = REMOTE.find((r) => r.externalId === externalId);
      if (!remote) throw new ApiError(422, "연동한 도구에서 저장소를 찾을 수 없습니다.");
      const webUrl = `${c.baseUrl}/${remote.fullPath}`;
      if (existing) {
        // a detached row: the same row comes back (history kept), here, whichever project it was on
        if (existing.projectId !== p.id) {
          // the environments belong to the old project: what they hold of this repository goes; builds and deployments stay
          db.cicdEnvironmentCommits = db.cicdEnvironmentCommits.filter((x) => x.repositoryId !== existing.id);
        }
        Object.assign(existing, { projectId: p.id, fullPath: remote.fullPath, webUrl, detachedAt: null });
        return toRepository(existing);
      }
      const row = {
        id: newId("cicd-repo"), connectionId: c.id, externalId, fullPath: remote.fullPath, webUrl, projectId: p.id,
        lastPolledAt: null, detachedAt: null, createdAt: nowIso(),
      };
      db.cicdRepositories.push(row);
      return toRepository(row);
    }),
  detachRepository: (project, repoId) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const repo = db.cicdRepositories.find((r) => r.id === repoId && r.projectId === p.id && isAttached(r));
      if (!repo) throw new ApiError(404, "저장소를 찾을 수 없습니다.");
      // soft: the row, commits, merge requests, links, builds and deployments stay; only new events stop coming in
      repo.detachedAt = nowIso();
    }),
  reattachRepository: (project, repoId) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const repo = db.cicdRepositories.find((r) => r.id === repoId && r.projectId === p.id && !isAttached(r));
      if (!repo) throw new ApiError(404, "저장소를 찾을 수 없습니다.");
      const c = db.cicdConnections.find((x) => x.id === repo.connectionId);
      // no call to the host, so a connection without a token works; but it must be on and still allow the project
      if (!c || !c.active || !allows(c.id, p.id)) throw new ApiError(404, "연동을 찾을 수 없습니다.");
      repo.detachedAt = null;
      return toRepository(repo);
    }),
  cleanupCount: (project, before) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      return { count: oldBuilds(p, cutoff(before)).length };
    }),
  cleanup: (project, before) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const gone = new Set(oldBuilds(p, cutoff(before)).map((x) => x.id));
      db.cicdPipelines = db.cicdPipelines.filter((x) => !gone.has(x.id));
      return { deleted: gone.size };
    }),

  environments: (project) =>
    run(() => {
      const user = sessionUser();
      return environmentsView(user, viewable(user, project));
    }),
  createEnvironment: (project, input) =>
    run(() => {
      const user = sessionUser();
      const p = envManageable(user, project);
      const name = envName(input.name);
      const aliases = "aliases" in input ? envAliases(input.aliases) : [];
      const others = db.cicdEnvironments.filter((e) => e.projectId === p.id);
      if (others.some((o) => o.name.toLowerCase() === name.toLowerCase())) throw sameEnvName();
      checkClash(others, name, aliases);
      const env: DbCicdEnvironment = {
        id: newId("cicd-env"), projectId: p.id, name, position: Math.max(0, ...others.map((o) => o.position)) + 1, production: input.production === true, aliases, hidden: false,
      };
      db.cicdEnvironments.push(env);
      reassign(p, env.id, [...new Set([name.toLowerCase(), ...aliases])]);
      return toEnvironment(env);
    }),
  updateEnvironment: (project, id, patch) =>
    run(() => {
      const user = sessionUser();
      const p = envManageable(user, project);
      const env = findEnvironment(p, id);
      const others = db.cicdEnvironments.filter((e) => e.projectId === p.id && e.id !== id);
      const name = "name" in patch ? envName(patch.name) : env.name;
      if ("name" in patch && others.some((o) => o.name.toLowerCase() === name.toLowerCase())) throw sameEnvName();
      const oldAliases = env.aliases;
      const aliases = "aliases" in patch ? envAliases(patch.aliases) : oldAliases;
      checkClash(others, name, aliases);
      const production = "production" in patch ? patch.production === true : env.production;
      const hidden = "hidden" in patch ? patch.hidden === true : env.hidden;
      const wasHidden = env.hidden;
      const oldName = env.name;
      Object.assign(env, { name, aliases: [...aliases], production, hidden });
      if (!hidden) {
        // what the environment now answers to that it did not before (a new alias, a new name, or everything once visible again)
        const names = new Set<string>();
        if (wasHidden) {
          names.add(name.toLowerCase());
          aliases.forEach((a) => names.add(a));
        } else {
          if (name.toLowerCase() !== oldName.toLowerCase()) names.add(name.toLowerCase());
          aliases.filter((a) => !oldAliases.includes(a)).forEach((a) => names.add(a));
        }
        if (names.size) reassign(p, env.id, [...names]);
      }
      return toEnvironment(env);
    }),
  reorderEnvironments: (project, ids) =>
    run(() => {
      const user = sessionUser();
      const p = envManageable(user, project);
      const bad = fieldError("ids", "환경 순서가 올바르지 않습니다.");
      const all = db.cicdEnvironments.filter((e) => e.projectId === p.id);
      if (!Array.isArray(ids) || ids.some((x) => typeof x !== "string") || ids.length !== all.length || !all.every((e) => ids.includes(e.id))) throw bad;
      ids.forEach((id, i) => {
        all.find((e) => e.id === id)!.position = i + 1;
      });
      return environmentsView(user, p);
    }),
  removeEnvironment: (project, id) =>
    run(() => {
      const user = sessionUser();
      const p = envManageable(user, project);
      const env = findEnvironment(p, id);
      // history: hidden instead of deleted
      if (db.cicdDeployments.some((d) => d.environmentId === id)) {
        env.hidden = true;
        return { deleted: false };
      }
      db.cicdEnvironments = db.cicdEnvironments.filter((e) => e !== env);
      db.cicdEnvironmentCommits = db.cicdEnvironmentCommits.filter((x) => x.environmentId !== id);
      return { deleted: true };
    }),

  devInfo: (number) =>
    run((): DevInfo => {
      const user = sessionUser();
      const { wp, project } = viewableWp(user, number);
      const active = db.cicdLinks.filter((l) => l.wpId === wp.id && !l.dismissedAt);
      const mergeRequests = active
        .flatMap((l) => {
          const m = l.mergeRequestId ? db.cicdMergeRequests.find((x) => x.id === l.mergeRequestId) : undefined;
          return m ? [{ l, m }] : [];
        })
        .sort((a, b) => (a.m.updatedAt < b.m.updatedAt ? 1 : a.m.updatedAt > b.m.updatedAt ? -1 : a.m.id < b.m.id ? -1 : 1))
        .map(({ l, m }) => toMr(l.id, m, l.source));
      const shown = active
        .flatMap((l) => {
          const c = l.commitId ? db.cicdCommits.find((x) => x.id === l.commitId) : undefined;
          return c ? [{ l, c }] : [];
        })
        .sort((a, b) => (a.c.committedAt < b.c.committedAt ? 1 : a.c.committedAt > b.c.committedAt ? -1 : a.c.id < b.c.id ? -1 : 1))
        .slice(0, COMMIT_LIMIT);
      const row = environmentRow(user, wp.id, project, shown.map(({ c }) => c.id));
      const commits: CicdCommit[] = shown.map(({ l, c }) => {
        const repo = db.cicdRepositories.find((r) => r.id === c.repositoryId)!;
        return {
          linkId: l.id, repository: repo.fullPath, shortSha: c.sha.slice(0, 7), title: c.title, author: c.authorName, user: userRef(c.userId),
          committedAt: c.committedAt, webUrl: c.webUrl, source: l.source, build: buildOf(c.repositoryId, "sha", c.sha), environments: row.names.get(c.id) ?? [],
          detached: !isAttached(repo),
        };
      });
      return clone({ mergeRequests, commits, canLink: can(user, project, "cicd.link"), environments: row.environments });
    }),
  linkByUrl: (number, url) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = linkableWp(user, number);
      const wanted = normalize(url);
      const tree = visibleAncestors(user, project);
      const trim = (s: string) => s.replace(/\/+$/, "");
      const found = wanted
        ? db.cicdMergeRequests
            .filter((m) => {
              const repo = db.cicdRepositories.find((r) => r.id === m.repositoryId);
              if (!repo || !isAttached(repo) || !tree.includes(repo.projectId)) return false;
              const base = trim(repo.webUrl);
              return trim(m.webUrl) === wanted || `${base}/-/merge_requests/${m.iid}` === wanted || `${base}/pull/${m.iid}` === wanted;
            })
            .sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : a.updatedAt > b.updatedAt ? -1 : 0))[0]
        : undefined;
      if (!found) throw new ApiError(422, "이 프로젝트에 붙은 저장소의 MR/PR 주소가 아니거나 아직 받지 않은 MR/PR입니다.");
      // a dismissed link is revived (source becomes 'manual': the person chose it); an active one stays as it is
      let link = db.cicdLinks.find((l) => l.wpId === wp.id && l.mergeRequestId === found.id);
      if (link?.dismissedAt) Object.assign(link, { dismissedAt: null, dismissedBy: null, source: "manual", createdBy: user.id });
      if (!link) {
        link = { id: newId("cicd-link"), wpId: wp.id, commitId: null, mergeRequestId: found.id, source: "manual", createdBy: user.id, createdAt: nowIso(), dismissedBy: null, dismissedAt: null };
        db.cicdLinks.push(link);
      }
      return clone(toMr(link.id, found, link.source));
    }),
  unlink: (number, linkId) =>
    run(() => {
      const user = sessionUser();
      const { wp } = linkableWp(user, number);
      const link = db.cicdLinks.find((l) => l.id === linkId && l.wpId === wp.id && !l.dismissedAt);
      if (!link) throw new ApiError(404, "연결을 찾을 수 없습니다.");
      link.dismissedAt = nowIso();
      link.dismissedBy = user.id;
    }),
};
