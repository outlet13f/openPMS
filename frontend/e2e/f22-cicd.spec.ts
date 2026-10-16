// F22 CI/CD 연동(docs/superpowers/specs/2026-10-15-cicd-design.md): 관리자가 GitHub 연동을 만들고, 프로젝트에 저장소를 붙이고,
// 서명한 웹훅으로 들어온 커밋이 작업의 개발 탭에 나타난다. 참관자는 보기만 하고, 모듈을 끄면 탭이 없고, 전역 역할
// "CI/CD 연동 관리자"를 받은 사람은 관리 › CI/CD 연동만 열 수 있으며 토큰 평문은 어디에도 없다. 저장소는 연동의 허용 프로젝트(V27)에서만
// 붙이고, 목록에 없는 프로젝트에는 연동이 보이지 않는다.
// Needs the test code host (e2e/fake-git.mjs, see its header; the backend reaches it as http://openpms-cicd-git:18090);
// skipped when it is not running (checked at FAKE_GIT_URL, its published port on the host). The webhook is posted to the
// app's own address (the base URL: the dev servers proxy /api, the built app serves it), unsigned by any session.
// 개발 데이터 V1010: web-platform #4 병합된 MR + 커밋 2, 개발용 GitLab 연동은 토큰이 없어 꺼져 있음(V1011: 허용 프로젝트 web-platform·mobile-app).
import crypto from "node:crypto";
import type { Page } from "@playwright/test";
import { test, expect, login, logout, resetDb, axe, panel } from "./support";

const GIT_CHECK = `${process.env.FAKE_GIT_URL ?? "http://127.0.0.1:18090"}/health`;
const GIT_BASE = "http://openpms-cicd-git:18090";

const ADMIN = "seoyeon.kim@openpms.local";
const MINJI = "minji.choi@openpms.local"; // web-platform 참관자
const JUNHO = "junho.park@openpms.local";
const CONNECTION = "E2E GitHub";
const TOKEN = "ghp_FAKE_E2E_not_a_real_token_WXYZ";
const SHA = "e2e0c1d2e3f405162738495a6b7c8d9e0f1a2b3c";
const TITLE = "로그인 화면 설계 반영 (#3)";
const WEB = "웹 플랫폼 개편";
const NOT_ALLOWED = "이 프로젝트에서 쓸 수 있는 연동이 없습니다. 관리자에게 요청하세요.";

let gitUp = false;
let hookUrl = "";
let secret = "";

test.describe.configure({ mode: "serial" });
test.beforeAll(async () => {
  resetDb();
  gitUp = await fetch(GIT_CHECK).then((r) => r.ok, () => false);
});
test.afterAll(resetDb);
test.beforeEach(() => test.skip(!gitUp, "test code host (e2e/fake-git.mjs) is not running"));

/** POST as the signed-in user, from the page (same CSRF handling as the app's client). */
async function post(page: Page, path: string, body?: unknown) {
  return page.evaluate(
    async ([p, b]) => {
      const xsrf = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/)?.[1];
      const headers: Record<string, string> = xsrf ? { "X-XSRF-TOKEN": decodeURIComponent(xsrf) } : {};
      if (b !== undefined) headers["Content-Type"] = "application/json";
      const r = await fetch(p as string, { method: "POST", headers, body: b === undefined ? undefined : JSON.stringify(b) });
      return { status: r.status, json: r.headers.get("content-type")?.includes("json") ? await r.json() : null };
    },
    [path, body] as const,
  );
}

/** Opens the connection's edit dialog from its row menu. */
async function editConnection(page: Page, name: string) {
  await page.getByRole("button", { name: `${name} 메뉴` }).click();
  await page.getByRole("menuitem", { name: "수정" }).click();
  return page.getByRole("dialog", { name: "연동 수정" });
}

/** Runs a dev-profile job now, as the signed-in administrator. */
async function runJob(page: Page, name: string) {
  expect((await post(page, `/api/v1/dev/jobs/${name}`)).status, `job ${name}`).toBe(200);
}

test.describe("F22 CI/CD 연동", () => {
  test("F22-1 연동 → 저장소 → 웹훅 → 개발 탭 → 연결 끊기", async ({ page, request }) => {
    await login(page, ADMIN);
    await page.goto("/admin/cicd");
    const devRow = page.getByRole("table", { name: "CI/CD 연동 목록" }).getByRole("row").filter({ hasText: "사내 GitLab(개발용)" }); // V1010
    await expect(devRow).toContainText(`모바일 앱 2.0, ${WEB}`); // V1011
    await page.getByRole("button", { name: "연동 만들기" }).click();
    const dlg = page.getByRole("dialog", { name: "연동 만들기" });
    await dlg.getByRole("radio", { name: "GitHub" }).click();
    await dlg.getByLabel(/^이름/).fill(CONNECTION);
    await dlg.getByLabel(/^주소/).fill(GIT_BASE);
    await dlg.getByLabel(/^API 토큰/).fill(TOKEN);
    await axe(page, "CI/CD 연동 만들기");
    await dlg.getByRole("button", { name: "만들기", exact: true }).click();

    // the address and the secret, shown this once
    const shown = page.getByRole("dialog", { name: "웹훅 주소와 비밀" });
    hookUrl = (await shown.getByRole("group", { name: "웹훅 주소" }).locator("code").textContent())!.trim();
    secret = (await shown.getByRole("group", { name: "웹훅 비밀" }).locator("code").textContent())!.trim();
    expect(hookUrl).toMatch(/\/api\/v1\/hooks\/cicd\/[\w-]+$/);
    expect(secret.length).toBeGreaterThanOrEqual(20);
    await axe(page, "CI/CD 웹훅 주소와 비밀");
    await shown.getByRole("button", { name: "확인" }).click();
    await expect(shown).toHaveCount(0);

    const row = page.getByRole("table", { name: "CI/CD 연동 목록" }).getByRole("row").filter({ hasText: CONNECTION });
    await expect(row.getByText(`끝 ${TOKEN.slice(-4)}`)).toBeVisible();
    await row.getByRole("button", { name: `연결 확인: ${CONNECTION}` }).click();
    await expect(row.getByText("연결 확인: 성공 (200)")).toBeVisible();
    // a new connection starts with no allowed project: web-platform does not see it yet
    await expect(row).toContainText("없음");
    await page.goto("/projects/web-platform/settings/repositories");
    await expect(page.getByRole("table", { name: "붙은 저장소" }).getByText("web/portal")).toBeVisible(); // V1010
    await expect(page.getByText(NOT_ALLOWED)).toBeVisible(); // the dev connection allows it but is off
    await expect(page.getByRole("button", { name: "저장소 붙이기" })).toHaveCount(0);

    // the administrator allows web-platform
    await page.goto("/admin/cicd");
    const edit = await editConnection(page, CONNECTION);
    await edit.getByRole("button", { name: /^허용 프로젝트/ }).click();
    // the list asks the server for what is typed (by name or identifier), so every project is reachable
    await page.getByRole("searchbox", { name: "목록에서 찾기" }).fill("web-plat");
    await expect(page.getByRole("checkbox", { name: "모바일 앱 2.0" })).toHaveCount(0);
    await page.getByRole("checkbox", { name: WEB }).click();
    await page.keyboard.press("Escape"); // closes the list, not the dialog
    await expect(edit.getByRole("button", { name: `허용 프로젝트: ${WEB}` })).toBeVisible();
    await axe(page, "CI/CD 연동 수정 (허용 프로젝트)");
    await edit.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("연동을 저장했습니다", { exact: true })).toBeVisible();
    await expect(edit).toHaveCount(0);
    await expect(row).toContainText(WEB);

    // 프로젝트 설정 › 저장소: search the code host and attach
    await page.goto("/projects/web-platform/settings/repositories");
    await expect(page.getByRole("table", { name: "붙은 저장소" }).getByText("web/portal")).toBeVisible(); // V1010
    await page.getByRole("button", { name: "저장소 붙이기" }).click();
    const attach = page.getByRole("dialog", { name: "저장소 붙이기" });
    // only active connections can take a new repository: ours is the one offered and already picked
    await expect(attach.getByRole("combobox", { name: /^연동/ })).toContainText(CONNECTION);
    await attach.getByLabel("저장소 찾기").fill("web");
    await attach.getByRole("button", { name: "붙이기: openpms-e2e/web" }).click();
    await expect(page.getByText("저장소를 붙였습니다", { exact: true })).toBeVisible();
    await expect(page.getByRole("table", { name: "붙은 저장소" }).getByRole("row").filter({ hasText: "openpms-e2e/web" })).toContainText(CONNECTION);

    // mobile-app is not on our connection's list: no choice, and the search answers as if there were no such connection
    await page.goto("/projects/mobile-app/settings/repositories");
    await expect(page.getByText(NOT_ALLOWED)).toBeVisible();
    await expect(page.getByRole("button", { name: "저장소 붙이기" })).toHaveCount(0);
    const hidden = await page.evaluate(async (name) => {
      const list = (await (await fetch("/api/v1/admin/cicd/connections")).json()) as { id: string; name: string }[];
      const id = list.find((c) => c.name === name)!.id;
      const choices = await (await fetch("/api/v1/projects/mobile-app/cicd/connections")).text();
      const search = await fetch(`/api/v1/projects/mobile-app/cicd/connections/${id}/search?q=web`);
      return { offered: choices.includes(id), search: search.status };
    }, CONNECTION);
    expect(hidden).toEqual({ offered: false, search: 404 });

    // web-platform has a repository of this connection: it cannot leave the list
    await page.goto("/admin/cicd");
    const again = await editConnection(page, CONNECTION);
    await again.getByRole("button", { name: /^허용 프로젝트/ }).click();
    await page.getByRole("checkbox", { name: WEB }).click();
    await page.keyboard.press("Escape");
    await again.getByRole("button", { name: "저장", exact: true }).click();
    await expect(again.getByRole("alert")).toHaveText("이 연동의 저장소가 붙어 있는 프로젝트는 뺄 수 없습니다. 먼저 저장소를 떼세요.");
    await again.getByRole("button", { name: "취소" }).click();
    await expect(row).toContainText(WEB);

    // GitHub delivers a signed push; the job turns it into a commit linked to #3
    const key = hookUrl.split("/").pop();
    const body = Buffer.from(
      JSON.stringify({
        ref: "refs/heads/feature/3-login",
        repository: { id: 9101, full_name: "openpms-e2e/web" },
        commits: [
          {
            id: SHA,
            message: `${TITLE}\n\n검토 의견을 반영했습니다.`,
            timestamp: "2026-10-15T01:00:00Z",
            url: `${GIT_BASE}/openpms-e2e/web/commit/${SHA}`,
            author: { name: "박준호", email: JUNHO },
          },
        ],
      }),
    );
    const sign = (s: string) => `sha256=${crypto.createHmac("sha256", s).update(body).digest("hex")}`;
    const headers = { "Content-Type": "application/json", "X-GitHub-Event": "push", "X-GitHub-Delivery": crypto.randomUUID() };
    // a wrong signature is the same 404 as an unknown address
    const forged = await request.post(`/api/v1/hooks/cicd/${key}`, { data: body, headers: { ...headers, "X-Hub-Signature-256": sign("not-the-secret") } });
    expect(forged.status()).toBe(404);
    const hook = await request.post(`/api/v1/hooks/cicd/${key}`, { data: body, headers: { ...headers, "X-Hub-Signature-256": sign(secret) } });
    expect(hook.status()).toBe(202);
    expect(await hook.json()).toEqual({ accepted: 1 });
    await runJob(page, "cicd-process");

    await page.goto("/projects/web-platform/work-packages/details/3/dev");
    const p = panel(page);
    await expect(p.getByRole("tab", { name: "개발", selected: true })).toBeVisible();
    const commits = p.getByRole("region", { name: /^커밋/ });
    await expect(commits.getByRole("link", { name: new RegExp(TITLE.replace(/[()#]/g, "\\$&")) })).toBeVisible();
    await expect(commits.getByText(SHA.slice(0, 7), { exact: true })).toBeVisible();
    await expect(commits.getByText("커밋 메시지에서 찾음", { exact: false })).toBeVisible();
    await expect(commits.getByRole("listitem")).toContainText(/openpms-e2e\/web.*박준호/); // the author's email is a user's: shown as that user (with an avatar)

    await commits.getByRole("button", { name: `연결 끊기: ${SHA.slice(0, 7)} ${TITLE}` }).click();
    await expect(page.getByText("연결을 끊었습니다", { exact: true })).toBeVisible();
    await expect(p.getByText(TITLE)).toHaveCount(0);
    await expect(p.getByText(/아직 연결된 개발 내역이 없습니다/)).toBeVisible();
  });

  test("F22-2 참관자는 개발 탭을 보기만 하고, 모듈을 끄면 탭이 없다", async ({ page }) => {
    await login(page, MINJI);
    await page.goto("/projects/web-platform/work-packages/details/4/dev");
    const p = panel(page);
    await expect(p.getByRole("tab", { name: "개발", selected: true })).toBeVisible();
    await expect(p.getByText("결제 API 클라이언트 추가 (#4)")).toBeVisible();
    await expect(p.getByText("결제 API 연동 (#4)")).toBeVisible();
    await expect(p.getByRole("button", { name: /^연결 끊기/ })).toHaveCount(0);
    await expect(p.getByLabel(/^MR\/PR 주소로 연결/)).toHaveCount(0);
    await logout(page);

    await login(page, ADMIN);
    await page.goto("/projects/web-platform/settings/modules");
    await page.getByRole("checkbox", { name: /^CI\/CD/ }).click();
    await expect(page.getByRole("checkbox", { name: /^CI\/CD/ })).not.toBeChecked();
    await page.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("저장했습니다", { exact: true })).toBeVisible();
    await logout(page);

    await login(page, MINJI);
    await page.goto("/projects/web-platform/work-packages/details/4/overview");
    await expect(p.getByRole("tab", { name: "요약" })).toBeVisible();
    await expect(p.getByRole("tab", { name: "개발" })).toHaveCount(0);
    // an old link to the tab falls back to the first one
    await page.goto("/projects/web-platform/work-packages/details/4/dev");
    await expect(p.getByRole("tab", { name: "요약", selected: true })).toBeVisible();
    await expect(p.getByText("결제 API 클라이언트 추가 (#4)")).toHaveCount(0);
  });

  test("F22-3 전역 역할 'CI/CD 연동 관리자'를 받으면 CI/CD 연동만 관리하고, 토큰 평문은 없다", async ({ page }) => {
    await login(page, ADMIN);
    if (!secret) {
      // run on its own (-g F22-3): make the connection F22-1 would have made
      const made = await post(page, "/api/v1/admin/cicd/connections", { kind: "github", name: CONNECTION, baseUrl: GIT_BASE, apiToken: TOKEN });
      expect(made.status).toBe(201);
      secret = (made.json as { webhookSecret: string }).webhookSecret;
    }
    expect(secret.length).toBeGreaterThanOrEqual(20);
    await page.goto("/admin/users");
    await page.getByRole("button", { name: "박준호 메뉴" }).click();
    await page.getByRole("menuitem", { name: "전역 역할" }).click();
    const dlg = page.getByRole("dialog", { name: "박준호님의 전역 역할" });
    await dlg.getByRole("checkbox", { name: /^CI\/CD 연동 관리자/ }).click();
    await dlg.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("저장했습니다", { exact: true })).toBeVisible();
    await logout(page);

    await login(page, JUNHO);
    // not an administrator: no 관리 entry, but CI/CD 연동 in the module menu
    await page.getByRole("button", { name: "모듈 메뉴" }).click();
    await expect(page.getByRole("menuitem", { name: "관리", exact: true })).toHaveCount(0);
    await page.getByRole("menuitem", { name: "CI/CD 연동" }).click();
    await expect(page).toHaveURL(/\/admin\/cicd$/);
    await expect(page.getByRole("heading", { name: "CI/CD 연동", level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "CI/CD 연동" }).first()).toBeVisible();
    const row = page.getByRole("table", { name: "CI/CD 연동 목록" }).getByRole("row").filter({ hasText: CONNECTION });
    await expect(row.getByText(`끝 ${TOKEN.slice(-4)}`)).toBeVisible();
    await expect(page.locator("body")).not.toContainText(TOKEN);
    // nor in what the API hands the screen
    const listed = await page.evaluate(async () => (await fetch("/api/v1/admin/cicd/connections")).text());
    expect(listed).toContain(CONNECTION);
    expect(listed).not.toContain(TOKEN);
    expect(listed).not.toContain(secret);
    // the other admin screens stay closed
    const users = await page.evaluate(async () => (await fetch("/api/v1/admin/users")).status);
    expect(users).toBe(403);
    await axe(page, "CI/CD 연동 (연동 관리자)");
    // the allowed projects are the system administrator's: read-only here
    const edit = await editConnection(page, CONNECTION);
    await expect(edit.getByText("허용 프로젝트는 시스템 관리자만 바꿀 수 있습니다.")).toBeVisible();
    await expect(edit.getByRole("button", { name: /^허용 프로젝트/ })).toHaveCount(0);
    await edit.getByRole("button", { name: "취소" }).click();
  });
});
