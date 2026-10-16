// Test-only GitHub Enterprise stand-in for f22-cicd.spec.ts. Never for real use: any bearer token is accepted.
// The backend runs in a container, so this runs as one on the same network and the connection's address is
// http://openpms-cicd-git:18090 (GitHubAdapter calls {address}/api/v3/...). The browser never talks to it.
//   podman run -d --rm --name openpms-cicd-git --network openpms-dev -p 127.0.0.1:18090:18090 \
//     -v "$PWD/e2e:/e2e:ro" docker.io/library/node:24-alpine node /e2e/fake-git.mjs
// (from frontend/; the published port only lets the spec check that it is up, at FAKE_GIT_URL, default
// http://127.0.0.1:18090). If 18090 is taken on the host (e.g. colima on a Mac forwards it), publish another port,
// -p 127.0.0.1:18095:18090, and run the spec with FAKE_GIT_URL=http://127.0.0.1:18095. Stop: podman stop openpms-cicd-git
import http from "node:http";

const PORT = Number(process.env.FAKE_GIT_PORT ?? 18090);
const BASE = process.env.FAKE_GIT_BASE ?? "http://openpms-cicd-git:18090";
const REPOS = [
  { id: 9101, full_name: "openpms-e2e/web", html_url: `${BASE}/openpms-e2e/web` },
  { id: 9102, full_name: "openpms-e2e/tools", html_url: `${BASE}/openpms-e2e/tools` },
];

const json = (res, status, body) => (res.writeHead(status, { "Content-Type": "application/json" }), res.end(JSON.stringify(body)));

http
  .createServer((req, res) => {
    const url = new URL(req.url, BASE);
    if (url.pathname === "/health") return json(res, 200, { ok: true });
    if (!/^Bearer \S+/.test(req.headers.authorization ?? "")) return json(res, 401, { message: "Bad credentials" });
    if (url.pathname === "/api/v3/user") return json(res, 200, { login: "openpms-e2e", id: 1 });
    if (url.pathname === "/api/v3/user/repos") return json(res, 200, REPOS);
    const m = url.pathname.match(/^\/api\/v3\/repositories\/(\d+)(\/commits|\/pulls)?$/);
    if (m) {
      const repo = REPOS.find((r) => String(r.id) === m[1]);
      if (!repo) return json(res, 404, { message: "Not Found" });
      // polling (every 5 minutes) finds nothing new: the spec delivers by webhook
      return m[2] ? json(res, 200, []) : json(res, 200, repo);
    }
    json(res, 404, { message: "Not Found" });
  })
  .listen(PORT, () => console.log(`fake git on :${PORT} as ${BASE}`));
