// CI/CD 연동 in the fake layer: the same rules and messages as the server's cicd/CicdConnections, CicdRepositories and
// DevTabController. There is no webhook and no code host here: the seeded merge requests and commits are what the
// development tab shows, the repository search is a fixed list of three, attaching only makes the repository.
import type { Api } from "../api";
import type { CicdConnection, CicdMergeRequest, CicdRepository, DevInfo, Id } from "../types";
import { ApiError } from "../errors";
import { nowIso } from "@/lib/dates";
import { db, newId, type DbProject, type DbUser } from "./db";
import { can, findProject, findWp, globalPermissions, requirePerm, sessionUser } from "./permissions";
import { clone, userRef } from "./dto";
import type { DbCicdMergeRequest } from "./cicd-seed";
import { fieldError, run } from "./util";

const COMMIT_LIMIT = 50;
const KINDS = ["gitlab", "github"];

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

const toConnection = (c: (typeof db.cicdConnections)[number]): CicdConnection => ({
  id: c.id, kind: c.kind, name: c.name, baseUrl: c.baseUrl, webhookUrl: `/api/v1/hooks/cicd/${c.hookKey}`, tokenHint: c.tokenHint, secretHint: c.secretHint,
  active: c.active, repositoryCount: db.cicdRepositories.filter((r) => r.connectionId === c.id).length, createdAt: c.createdAt,
});

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

function activeConnection(id: Id | null | undefined) {
  const c = id ? db.cicdConnections.find((x) => x.id === id) : undefined;
  if (!c || !c.active) throw new ApiError(404, "연동을 찾을 수 없습니다.");
  if (c.tokenHint === null) throw new ApiError(422, "이 연동에는 API 토큰이 없어 저장소를 찾을 수 없습니다.");
  return c;
}

const toRepository = (r: (typeof db.cicdRepositories)[number]): CicdRepository => {
  const c = db.cicdConnections.find((x) => x.id === r.connectionId)!;
  return { id: r.id, connectionName: c.name, kind: c.kind, fullPath: r.fullPath, webUrl: r.webUrl, lastPolledAt: r.lastPolledAt };
};

const taken = (ownerProjectId: Id, p: DbProject) => new ApiError(409, ownerProjectId === p.id ? "이미 이 프로젝트에 붙은 저장소입니다." : "이미 다른 프로젝트에 붙은 저장소입니다.");

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

function toMr(linkId: Id, m: DbCicdMergeRequest, source: CicdMergeRequest["source"]): CicdMergeRequest {
  const repo = db.cicdRepositories.find((r) => r.id === m.repositoryId)!;
  return {
    linkId, repository: repo.fullPath, iid: m.iid, title: m.title, state: m.state, sourceBranch: m.sourceBranch, targetBranch: m.targetBranch,
    author: m.authorName, user: userRef(m.userId), mergedAt: m.mergedAt, updatedAt: m.updatedAt, webUrl: m.webUrl, source,
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

export const cicdApi: Api["cicd"] = {
  connections: () =>
    run(() => {
      requireManager(sessionUser());
      return db.cicdConnections.slice().sort((a, b) => a.name.localeCompare(b.name, "ko")).map(toConnection);
    }),
  createConnection: (input) =>
    run(() => {
      requireManager(sessionUser());
      if (!KINDS.includes(input.kind)) throw fieldError("kind", "지원하지 않는 도구입니다.");
      const name = checkName(input.name);
      const baseUrl = checkBaseUrl(input.baseUrl);
      if (db.cicdConnections.some((c) => c.name === name)) throw duplicate();
      const secret = random();
      const token = input.apiToken;
      const c = {
        id: newId("cicd-conn"), kind: input.kind, name, baseUrl, hookKey: random(), tokenHint: token && token.trim() ? hint(token) : null,
        secretHint: hint(secret), active: true, createdBy: db.sessionUserId, createdAt: nowIso(),
      };
      db.cicdConnections.push(c);
      return { connection: toConnection(c), webhookSecret: secret };
    }),
  updateConnection: (id, patch) =>
    run(() => {
      requireManager(sessionUser());
      const c = findConnection(id);
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
          next.tokenHint = null;
        }
      }
      if (patch.apiToken !== undefined) next.tokenHint = patch.apiToken === null || patch.apiToken === "" ? null : hint(patch.apiToken);
      if (patch.active !== undefined) {
        if (typeof patch.active !== "boolean") throw fieldError("active", "켜짐 여부는 참/거짓이어야 합니다.");
        next.active = patch.active;
      }
      Object.assign(c, next);
      return toConnection(c);
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
      if (db.cicdRepositories.some((r) => r.connectionId === id)) throw new ApiError(409, "저장소가 붙어 있는 연동은 지울 수 없습니다. 먼저 저장소를 떼세요.");
      db.cicdConnections = db.cicdConnections.filter((x) => x !== c);
    }),

  projectConnections: (project) =>
    run(() => {
      manageable(sessionUser(), project);
      return db.cicdConnections.filter((c) => c.active).sort((a, b) => a.name.localeCompare(b.name, "ko")).map((c) => ({ id: c.id, kind: c.kind, name: c.name }));
    }),
  searchRepositories: (project, connectionId, q) =>
    run(() => {
      manageable(sessionUser(), project);
      if (q && q.length > 100) throw fieldError("q", "검색어가 너무 깁니다.");
      const c = activeConnection(connectionId);
      const needle = (q ?? "").trim().toLowerCase();
      return REMOTE.filter((r) => r.fullPath.toLowerCase().includes(needle)).map((r) => {
        const out: { externalId: string; fullPath: string; webUrl: string; attachedProject?: boolean } = {
          externalId: r.externalId, fullPath: r.fullPath, webUrl: `${c.baseUrl}/${r.fullPath}`,
        };
        if (db.cicdRepositories.some((x) => x.connectionId === c.id && x.externalId === r.externalId)) out.attachedProject = true;
        return out;
      });
    }),
  repositories: (project) =>
    run(() => {
      const p = viewable(sessionUser(), project);
      return db.cicdRepositories.filter((r) => r.projectId === p.id).sort((a, b) => (a.fullPath < b.fullPath ? -1 : a.fullPath > b.fullPath ? 1 : 0)).map(toRepository);
    }),
  attachRepository: (project, input) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const externalId = input.externalId;
      if (typeof externalId !== "string" || !externalId.trim() || externalId.length > 200) throw fieldError("externalId", "저장소를 고르세요.");
      const c = activeConnection(input.connectionId);
      const existing = db.cicdRepositories.find((r) => r.connectionId === c.id && r.externalId === externalId);
      if (existing) throw taken(existing.projectId, p);
      // what is stored is what the host says: the fixed list here
      const remote = REMOTE.find((r) => r.externalId === externalId);
      if (!remote) throw new ApiError(422, "연동한 도구에서 저장소를 찾을 수 없습니다.");
      const row = {
        id: newId("cicd-repo"), connectionId: c.id, externalId, fullPath: remote.fullPath, webUrl: `${c.baseUrl}/${remote.fullPath}`, projectId: p.id,
        lastPolledAt: null, createdAt: nowIso(),
      };
      db.cicdRepositories.push(row);
      return toRepository(row);
    }),
  detachRepository: (project, repoId) =>
    run(() => {
      const p = manageable(sessionUser(), project);
      const repo = db.cicdRepositories.find((r) => r.id === repoId && r.projectId === p.id);
      if (!repo) throw new ApiError(404, "저장소를 찾을 수 없습니다.");
      // the database cascades: what the repository brought goes with it
      const commits = new Set(db.cicdCommits.filter((x) => x.repositoryId === repo.id).map((x) => x.id));
      const mrs = new Set(db.cicdMergeRequests.filter((x) => x.repositoryId === repo.id).map((x) => x.id));
      db.cicdLinks = db.cicdLinks.filter((l) => !(l.commitId && commits.has(l.commitId)) && !(l.mergeRequestId && mrs.has(l.mergeRequestId)));
      db.cicdCommits = db.cicdCommits.filter((x) => !commits.has(x.id));
      db.cicdMergeRequests = db.cicdMergeRequests.filter((x) => !mrs.has(x.id));
      db.cicdRepositories = db.cicdRepositories.filter((r) => r !== repo);
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
      const commits = active
        .flatMap((l) => {
          const c = l.commitId ? db.cicdCommits.find((x) => x.id === l.commitId) : undefined;
          return c ? [{ l, c }] : [];
        })
        .sort((a, b) => (a.c.committedAt < b.c.committedAt ? 1 : a.c.committedAt > b.c.committedAt ? -1 : a.c.id < b.c.id ? -1 : 1))
        .slice(0, COMMIT_LIMIT)
        .map(({ l, c }) => ({
          linkId: l.id, repository: db.cicdRepositories.find((r) => r.id === c.repositoryId)!.fullPath, shortSha: c.sha.slice(0, 7), title: c.title,
          author: c.authorName, user: userRef(c.userId), committedAt: c.committedAt, webUrl: c.webUrl, source: l.source,
        }));
      return clone({ mergeRequests, commits, canLink: can(user, project, "cicd.link") });
    }),
  linkByUrl: (number, url) =>
    run(() => {
      const user = sessionUser();
      const { wp, project } = linkableWp(user, number);
      const wanted = normalize(url);
      const tree = projectAncestors(project);
      const trim = (s: string) => s.replace(/\/+$/, "");
      const found = wanted
        ? db.cicdMergeRequests
            .filter((m) => {
              const repo = db.cicdRepositories.find((r) => r.id === m.repositoryId);
              if (!repo || !tree.includes(repo.projectId)) return false;
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
