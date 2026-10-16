// Test-only code host for f22-cicd.spec.ts (GitHub Enterprise mode) and f23-cicd-builds.spec.ts (GitLab mode). Never for
// real use: any token is accepted. GitHub mode answers {address}/api/v3/... with a Bearer token (GitHubAdapter), GitLab mode
// answers {address}/api/v4/... with a PRIVATE-TOKEN header (GitLabAdapter); a request without its tool's credential is 401.
// Every JSON body here is written by hand from the tools' public API documentation.
// The backend runs in a container, so this runs as one on the same network and the connection's address is
// http://openpms-cicd-git:18090. The browser never talks to it.
//   podman run -d --rm --name openpms-cicd-git --network openpms-dev -p 127.0.0.1:18090:18090 \
//     -v "$PWD/e2e:/e2e:ro" docker.io/library/node:24-alpine node /e2e/fake-git.mjs
// (from frontend/; the published port only lets the specs check that it is up, at FAKE_GIT_URL, default
// http://127.0.0.1:18090). If 18090 is taken on the host (e.g. colima on a Mac forwards it), publish another port,
// -p 127.0.0.1:18095:18090, and run the specs with FAKE_GIT_URL=http://127.0.0.1:18095. Stop: podman stop openpms-cicd-git
//
// Compare and ancestor questions are answered from fixed commit graphs (one straight line each, oldest first). F23's graph
// is E1 ← E2 ← E3 on main; the same shas are in f23-cicd-builds.spec.ts. A sha may be given in full or as a unique prefix
// (7+ characters, the way an image tag carries it); a commit not in the graph is 404, as on the real hosts.
import http from "node:http";

const PORT = Number(process.env.FAKE_GIT_PORT ?? 18090);
const BASE = process.env.FAKE_GIT_BASE ?? "http://openpms-cicd-git:18090";

// GitHub mode (F22)
const REPOS = [
  { id: 9101, full_name: "openpms-e2e/web", html_url: `${BASE}/openpms-e2e/web` },
  { id: 9102, full_name: "openpms-e2e/tools", html_url: `${BASE}/openpms-e2e/tools` },
];
const GITHUB_GRAPHS = {
  9101: [{ sha: "e2e0c1d2e3f405162738495a6b7c8d9e0f1a2b3c", message: "로그인 화면 설계 반영 (#3)", at: "2026-10-15T01:00:00Z" }],
};

// GitLab mode (F23)
const GITLAB_PROJECT = { id: 9201, path_with_namespace: "openpms-e2e/svc", web_url: `${BASE}/openpms-e2e/svc` };
const GITLAB_GRAPHS = {
  9201: [
    { sha: "e1a23f0c5b7d9e1f2a3b4c5d6e7f8091a2b3c4d5", message: "서비스 뼈대", at: "2026-10-14T01:00:00Z" },
    { sha: "e2b34a1d6c8e0f2a3b4c5d6e7f8091a2b3c4d5e6", message: "주문 API 추가", at: "2026-10-14T02:00:00Z" },
    { sha: "e3c45b2e7d9f1a3b4c5d6e7f8091a2b3c4d5e6f7", message: "설정 정리", at: "2026-10-14T03:00:00Z" },
  ],
};
const AUTHOR = { name: "박준호", email: "junho.park@openpms.local" };

const json = (res, status, body) => (res.writeHead(status, { "Content-Type": "application/json" }), res.end(JSON.stringify(body)));

/** Position of a full sha or unique prefix in the graph, -1 when it is not there. */
function find(graph, ref) {
  const r = String(ref ?? "").toLowerCase();
  if (r.length < 7) return -1;
  const hits = graph.flatMap((c, i) => (c.sha.startsWith(r) ? [i] : []));
  return hits.length === 1 ? hits[0] : -1;
}

/** Commits reachable from `to` and not from `from` on a straight line, newest first. */
const between = (graph, from, to) => graph.slice(from + 1, to + 1).reverse();

const gitlabCommit = (repo, c) => ({
  id: c.sha,
  short_id: c.sha.slice(0, 8),
  title: c.message.split("\n")[0],
  message: c.message,
  author_name: AUTHOR.name,
  author_email: AUTHOR.email,
  created_at: c.at,
  committed_date: c.at,
  web_url: `${repo.web_url}/-/commit/${c.sha}`,
});

const githubCommit = (repo, c) => ({
  sha: c.sha,
  html_url: `${repo.html_url}/commit/${c.sha}`,
  commit: { message: c.message, author: { ...AUTHOR, date: c.at }, committer: { ...AUTHOR, date: c.at } },
});

function gitlab(req, res, url) {
  if (!/\S/.test(req.headers["private-token"] ?? "")) return json(res, 401, { message: "401 Unauthorized" });
  const path = url.pathname.slice("/api/v4".length);
  if (path === "/user") return json(res, 200, { id: 1, username: "openpms-e2e" });
  if (path === "/projects") {
    const q = (url.searchParams.get("search") ?? "").toLowerCase();
    return json(res, 200, GITLAB_PROJECT.path_with_namespace.includes(q) ? [GITLAB_PROJECT] : []);
  }
  const m = path.match(/^\/projects\/(\d+)(\/.*)?$/);
  if (!m || m[1] !== String(GITLAB_PROJECT.id)) return json(res, 404, { message: "404 Project Not Found" });
  const graph = GITLAB_GRAPHS[m[1]];
  switch (m[2] ?? "") {
    case "":
      return json(res, 200, GITLAB_PROJECT);
    // polling (every 5 minutes) finds nothing new: the spec delivers by webhook
    case "/repository/commits":
    case "/merge_requests":
    case "/pipelines":
    case "/deployments":
      return json(res, 200, []);
    case "/repository/compare": {
      const from = find(graph, url.searchParams.get("from"));
      const to = find(graph, url.searchParams.get("to"));
      if (from < 0 || to < 0) return json(res, 404, { message: "404 Commit Not Found" });
      return json(res, 200, { commits: between(graph, from, to).map((c) => gitlabCommit(GITLAB_PROJECT, c)), diffs: [] });
    }
    case "/repository/merge_base": {
      const refs = url.searchParams.getAll("refs[]").map((r) => find(graph, r));
      if (refs.length !== 2 || refs.some((i) => i < 0)) return json(res, 404, { message: "404 Commit Not Found" });
      return json(res, 200, gitlabCommit(GITLAB_PROJECT, graph[Math.min(...refs)]));
    }
    default:
      return json(res, 404, { message: "404 Not Found" });
  }
}

function github(req, res, url) {
  if (!/^Bearer \S+/.test(req.headers.authorization ?? "")) return json(res, 401, { message: "Bad credentials" });
  if (url.pathname === "/api/v3/user") return json(res, 200, { login: "openpms-e2e", id: 1 });
  if (url.pathname === "/api/v3/user/repos") return json(res, 200, REPOS);
  const m = url.pathname.match(/^\/api\/v3\/repositories\/(\d+)(\/commits|\/pulls|\/compare\/([^/]+)\.\.\.([^/]+))?$/);
  const repo = m && REPOS.find((r) => String(r.id) === m[1]);
  if (!repo) return json(res, 404, { message: "Not Found" });
  if (m[3] !== undefined) {
    const graph = GITHUB_GRAPHS[m[1]] ?? [];
    const base = find(graph, decodeURIComponent(m[3]));
    const head = find(graph, decodeURIComponent(m[4]));
    if (base < 0 || head < 0) return json(res, 404, { message: "Not Found" });
    const ahead = Math.max(0, head - base);
    const behind = Math.max(0, base - head);
    const status = ahead === 0 && behind === 0 ? "identical" : behind === 0 ? "ahead" : "behind";
    // GitHub lists the compared commits oldest first
    const commits = between(graph, base, head).reverse().map((c) => githubCommit(repo, c));
    return json(res, 200, { status, ahead_by: ahead, behind_by: behind, total_commits: ahead, commits });
  }
  // polling (every 5 minutes) finds nothing new: the spec delivers by webhook
  return m[2] ? json(res, 200, []) : json(res, 200, repo);
}

http
  .createServer((req, res) => {
    const url = new URL(req.url, BASE);
    if (url.pathname === "/health") return json(res, 200, { ok: true });
    if (url.pathname.startsWith("/api/v4/")) return gitlab(req, res, url);
    if (url.pathname.startsWith("/api/v3/")) return github(req, res, url);
    json(res, 404, { message: "Not Found" });
  })
  .listen(PORT, () => console.log(`fake git on :${PORT} as ${BASE}`));
