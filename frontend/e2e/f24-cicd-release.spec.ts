// F24 CI/CD 릴리스 검증(docs/superpowers/specs/2026-10-20-cicd-phase3-release-design.md, 3단계): 스테이징에 들어오고 운영에 아직 없는
// 작업이 검증 보드에 카드로 나타나고, QA가 통과·실패를 남기며(실패는 담당자에게 알림), 새 커밋이 들어오면 재검증 필요로 옮겨 가고,
// "이미 운영에 있음"으로 뺀 작업은 되돌릴 수 있고, 운영 배포가 반영되면 릴리스 기록 한 줄이 남는다.
// Needs the test code host (e2e/fake-git.mjs in GitLab mode, see its header); skipped when it is not running. This spec has
// its own project and repository (mobile-app and openpms-e2e/rel, graph R1 ← R2 ← R3 ← R4) so that F23's web-platform —
// whose production sits in 확인 중 for about 15 minutes after F23-2 — never holds its deployments up.
// Times are relative to now, as in F23: the staging deployment of R4 must come after the pass on A for A to need a new check.
// 개발 데이터 V1012: mobile-app의 환경 개발(dev, development)·스테이징(stg, staging)·운영(prod, production).
// A work package of a private subproject the QA is not a member of rides along with B: on the board it is not there for her
// and in the record it is only counted (볼 수 없는 작업 1개).
import crypto from "node:crypto";
import type { APIRequestContext, Page } from "@playwright/test";
import { test, expect, login, logout, resetDb, axe, api, post, send, runJob } from "./support";

const GIT_CHECK = `${process.env.FAKE_GIT_URL ?? "http://127.0.0.1:18090"}/health`;
const GIT_BASE = "http://openpms-cicd-git:18090";
const REL_URL = `${GIT_BASE}/openpms-e2e/rel`;

const ADMIN = "seoyeon.kim@openpms.local";
const SEOA = "seoa.yoon@openpms.local"; // 윤서아, made QA of mobile-app here (V1013 makes her QA of web-platform only)
const JUNHO = "junho.park@openpms.local";
const JUNHO_ID = "c48ce4ad-3a64-55ab-a95c-2902395dbb46";
const SEOA_ID = "fe75bf4d-827d-553e-9e4a-fdb703b4a272";
const QA_ROLE = "55ecb595-b468-52b0-bfbd-52f40d6333a4";
const TOKEN = "glpat-FAKE_E2E_not_a_real_token_UVWX";
const PATH = "openpms-e2e/rel";
const SUB = "mobile-pay-e2e";

// the fake host's graph (fake-git.mjs GITLAB_GRAPHS 9301), oldest first
const R1 = "fec04df330da8b26b870f0aa0122945960636fa9";
const R2 = "038af310365bc1bda5aff9d5bf0523df9339332b";
const R3 = "8592afdb7f9a54644060117028b8aa2155697050";
const R4 = "ddeab3d8e20eac21a0e76d27d3ed5fc779389376";

let gitUp = false;
let gitlab = { key: "", secret: "" };
let a = 0; // 주문 화면, 박준호
let b = 0; // 결제 화면, 박준호
let hidden = 0; // in the private subproject

test.describe.configure({ mode: "serial" });
test.beforeAll(async () => {
  resetDb();
  gitUp = await fetch(GIT_CHECK).then((r) => r.ok, () => false);
});
test.afterAll(resetDb);
test.beforeEach(() => test.skip(!gitUp, "test code host (e2e/fake-git.mjs) is not running"));

const keyOf = (url: string) => url.split("/").pop()!;
/** GitLab's webhook time format: "2026-10-16 01:02:03 UTC". */
const gitlabTime = (d: Date) => d.toISOString().replace("T", " ").replace(/\.\d+Z$/, " UTC");
const minutesAgo = (n: number) => new Date(Date.now() - n * 60_000);

/** A GitLab webhook with the connection's secret token. */
async function gitlabHook(request: APIRequestContext, event: string, payload: unknown) {
  const r = await request.post(`/api/v1/hooks/cicd/${gitlab.key}`, {
    data: Buffer.from(JSON.stringify(payload)),
    headers: { "Content-Type": "application/json", "X-Gitlab-Event": event, "X-Gitlab-Token": gitlab.secret, "X-Gitlab-Event-UUID": crypto.randomUUID() },
  });
  expect(r.status(), event).toBe(202);
  return (await r.json()) as { accepted: number };
}

const project = { id: 9301, path_with_namespace: PATH, web_url: REL_URL };

function push(commits: { sha: string; message: string; at: string }[]) {
  return {
    object_kind: "push",
    ref: "refs/heads/main",
    project,
    commits: commits.map((c) => ({
      id: c.sha,
      message: c.message,
      title: c.message.split("\n")[0],
      timestamp: c.at,
      url: `${REL_URL}/-/commit/${c.sha}`,
      author: { name: "박준호", email: "junho.park@openpms.local" },
    })),
  };
}

function deployment(id: number, sha: string, environment: string, at: Date) {
  return {
    object_kind: "deployment",
    status: "success",
    status_changed_at: gitlabTime(at),
    deployment_id: id,
    deployable_id: id + 1000,
    deployable_url: `${REL_URL}/-/jobs/${id + 1000}`,
    environment,
    project,
    short_sha: sha.slice(0, 8),
    commit_url: `${REL_URL}/-/commit/${sha}`,
  };
}

interface BoardCard {
  number: number;
  column: string;
}

/** The default board (스테이징 → 운영) as the signed-in administrator, who sees every card. */
async function board(page: Page) {
  const r = await send(page, "GET", "/api/v1/projects/mobile-app/releases/board");
  expect(r.status).toBe(200);
  return (r.json as { cards: BoardCard[] }).cards;
}

/**
 * Runs the job until the check on the administrator's board holds: a first deployment is decided in the run after it
 * starts, and a deployment waiting behind it goes in on the run after that.
 */
async function processUntil(page: Page, check: (cards: BoardCard[]) => void) {
  await expect(async () => {
    await runJob(page, "cicd-process");
    check(await board(page));
  }).toPass({ intervals: [500, 1000, 2000], timeout: 30_000 });
}

const column = (page: Page, name: string) => page.getByRole("region", { name: new RegExp(`^${name}`) });
const card = (page: Page, n: number) => page.getByRole("article", { name: new RegExp(`^#${n} `) });
const boardUrl = "/projects/mobile-app/releases";

test.describe("F24 CI/CD 릴리스 검증", () => {
  test("F24-1 QA가 보드에서 통과·실패를 남긴다", async ({ page, request }) => {
    await login(page, ADMIN);
    const mobileId = await page.evaluate(async () => ((await (await fetch("/api/v1/projects/mobile-app")).json()) as { id: string }).id);
    const made = await post(page, "/api/v1/admin/cicd/connections", { kind: "gitlab", name: "E2E GitLab 릴리스", baseUrl: GIT_BASE, apiToken: TOKEN });
    expect(made.status).toBe(201);
    const created = made.json as { connection: { id: string; webhookUrl: string }; webhookSecret: string };
    gitlab = { key: keyOf(created.connection.webhookUrl), secret: created.webhookSecret };
    expect((await send(page, "PUT", `/api/v1/admin/cicd/connections/${created.connection.id}/projects`, { projectIds: [mobileId] })).status).toBe(200);
    expect((await post(page, "/api/v1/projects/mobile-app/cicd/repositories", { connectionId: created.connection.id, externalId: "9301" })).status).toBe(201);
    expect((await post(page, "/api/v1/projects/mobile-app/members", { principalIds: [SEOA_ID], roleIds: [QA_ROLE] })).status).toBeLessThan(300);

    // a private subproject 윤서아 is not a member of: its work package is linked like any other, and hidden from her
    expect((await post(page, "/api/v1/projects", { name: "모바일 결제 모듈 (e2e)", identifier: SUB, parentId: mobileId })).status).toBe(201);

    const create = (identifier: string, subject: string, assigneeId: string | null) =>
      api<number>(
        page,
        "const [p, subject, assigneeId] = args; const f = await api.workPackages.form(p); const type = f.types.find((t) => t.name === '작업') ?? f.types[0]; return (await api.workPackages.create(p, { typeId: type.id, subject, assigneeId })).number",
        identifier,
        subject,
        assigneeId,
      );
    a = await create("mobile-app", "주문 화면 만들기", JUNHO_ID);
    b = await create("mobile-app", "결제 화면 만들기", JUNHO_ID);
    hidden = await create(SUB, "결제 모듈 내부 정리", null);

    expect(
      await gitlabHook(request, "Push Hook", push([
        { sha: R1, message: "릴리스 뼈대", at: "2026-10-19T01:00:00Z" },
        { sha: R2, message: `주문 화면 (#${a})`, at: "2026-10-19T02:00:00Z" },
        { sha: R3, message: `결제 화면 (#${b}, #${hidden})`, at: "2026-10-19T03:00:00Z" },
      ])),
    ).toEqual({ accepted: 3 });
    // 운영 has R1, 스테이징 R3 (each a first deployment: the host answers which linked commits are in it)
    expect(await gitlabHook(request, "Deployment Hook", deployment(801, R1, "production", minutesAgo(40)))).toEqual({ accepted: 1 });
    expect(await gitlabHook(request, "Deployment Hook", deployment(802, R3, "staging", minutesAgo(35)))).toEqual({ accepted: 1 });
    await processUntil(page, (cards) => {
      expect(cards.map((c) => [c.number, c.column]).sort()).toEqual([[a, "waiting"], [b, "waiting"], [hidden, "waiting"]].sort());
    });
    await logout(page);

    await login(page, SEOA);
    await page.goto(boardUrl);
    await expect(column(page, "검증 대기").getByRole("heading", { level: 2 })).toHaveText(/검증 대기\s*2$/);
    await expect(card(page, a)).toBeVisible();
    await expect(card(page, b)).toBeVisible();
    await expect(page.getByText(`#${hidden}`)).toHaveCount(0);
    await expect(page.getByText(/목록이 바뀔 수 있습니다|목록이 덜 찼을 수 있습니다/)).toHaveCount(0);
    await page.waitForLoadState("networkidle");
    await axe(page, "릴리스 검증 보드");

    await page.getByRole("button", { name: `#${a} 통과` }).click();
    await expect(page.getByText("통과로 남겼습니다.", { exact: true })).toBeVisible();
    await expect(column(page, "통과").getByRole("heading", { level: 2 })).toHaveText(/통과\s*1$/);
    await expect(column(page, "통과").getByRole("article", { name: new RegExp(`^#${a} `) })).toBeVisible();
    // the card left its column: the focus goes to the card now in its place
    await expect(card(page, b).getByRole("link").first()).toBeFocused();

    await page.getByRole("button", { name: `#${b} 실패…` }).click();
    const dlg = page.getByRole("dialog", { name: `#${b} 실패로 남기기` });
    await expect(dlg).toContainText("작업 담당자에게 알림이 갑니다.");
    await dlg.getByRole("button", { name: "실패로 남기기", exact: true }).click();
    await expect(dlg.getByText("실패 사유를 적으세요.")).toBeVisible();
    await axe(page, "실패로 남기기 대화상자");
    await dlg.getByLabel(/^실패 사유/).fill("결제 버튼이 눌리지 않음");
    await dlg.getByRole("button", { name: "실패로 남기기", exact: true }).click();
    await expect(dlg).toHaveCount(0);
    await expect(page.getByText("실패로 남겼습니다. 담당자에게 알렸습니다.", { exact: true })).toBeVisible();
    await expect(column(page, "실패").getByRole("heading", { level: 2 })).toHaveText(/실패\s*1$/);
    await expect(column(page, "실패").getByRole("article", { name: new RegExp(`^#${b} `) })).toContainText("결제 버튼이 눌리지 않음");
    // its column is empty now: the focus goes to the column's heading
    await expect(column(page, "검증 대기").getByRole("heading", { level: 2 })).toBeFocused();
    await logout(page);

    // the assignee is told
    await login(page, JUNHO);
    await page.goto("/notifications");
    await expect(page.getByText("윤서아님이 스테이징 검증을 실패로 남겼습니다").first()).toBeVisible();
  });

  test("F24-2 새 커밋이 들어오면 재검증 필요, 뺀 작업은 되돌린다", async ({ page, request }) => {
    await login(page, ADMIN);
    expect(await gitlabHook(request, "Push Hook", push([{ sha: R4, message: `주문 화면 고침 (#${a})`, at: "2026-10-19T04:00:00Z" }]))).toEqual({ accepted: 1 });
    // after the pass on A (F24-1): A's newest commit came into 스테이징 later than its check
    expect(await gitlabHook(request, "Deployment Hook", deployment(803, R4, "staging", new Date()))).toEqual({ accepted: 1 });
    await processUntil(page, (cards) => {
      expect(cards.find((c) => c.number === a)?.column).toBe("reverify");
    });
    await logout(page);

    await login(page, SEOA);
    await page.goto(boardUrl);
    await expect(column(page, "재검증 필요").getByRole("article", { name: new RegExp(`^#${a} `) })).toBeVisible();
    await expect(column(page, "실패").getByRole("article", { name: new RegExp(`^#${b} `) })).toBeVisible();

    await page.getByRole("button", { name: `#${b} 이미 운영에 있음` }).click();
    await expect(page.getByText("보드에서 뺐습니다. '뺀 작업'에서 되돌릴 수 있습니다.", { exact: true })).toBeVisible();
    await expect(card(page, b)).toHaveCount(0);
    await expect(column(page, "실패").getByRole("heading", { level: 2 })).toBeFocused();
    await page.getByRole("button", { name: "뺀 작업 1" }).click();
    await page.getByRole("button", { name: `#${b} 되돌리기` }).click();
    await expect(page.getByText("보드로 되돌렸습니다.", { exact: true })).toBeVisible();
    await expect(column(page, "실패").getByRole("article", { name: new RegExp(`^#${b} `) })).toBeVisible();
    // the restored card takes the focus
    await expect(card(page, b).getByRole("link").first()).toBeFocused();
  });

  test("F24-3 운영 배포가 릴리스 기록이 된다", async ({ page, request }) => {
    await login(page, ADMIN);
    expect(await gitlabHook(request, "Deployment Hook", deployment(804, R4, "production", new Date()))).toEqual({ accepted: 1 });
    await processUntil(page, (cards) => {
      expect(cards).toEqual([]);
    });
    await logout(page);

    await login(page, SEOA);
    await page.goto(boardUrl);
    await expect(page.getByText("검증할 작업이 없습니다.", { exact: false })).toBeVisible();

    await page.getByRole("tab", { name: "릴리스 기록" }).click();
    await expect(page).toHaveURL(/\/projects\/mobile-app\/releases\/records$/);
    const rows = page.getByRole("table", { name: "릴리스 기록 (최근 배포부터)" }).getByRole("row");
    await expect(rows).toHaveCount(2); // the header and one record
    const row = rows.nth(1);
    await expect(row.getByRole("cell").nth(0)).toHaveText("운영");
    await expect(row.getByRole("cell").nth(1)).toHaveText(PATH);
    await expect(row.getByRole("cell").nth(2)).toHaveText("2"); // the hidden one is not counted
    await expect(row).toContainText("경고 2");
    await page.waitForLoadState("networkidle");
    await axe(page, "릴리스 기록");

    await row.getByRole("link").first().click();
    await expect(page).toHaveURL(/\/releases\/records\/[0-9a-f-]+$/);
    const items = page.getByRole("table", { name: "이 배포로 나간 작업" });
    await expect(items.getByRole("row").filter({ hasText: `#${a} ` })).toContainText("재검증 없이 나감");
    await expect(items.getByRole("row").filter({ hasText: `#${b} ` })).toContainText("실패인 채 나감");
    await expect(items.getByRole("row").filter({ hasText: `#${b} ` })).toContainText("결제 버튼이 눌리지 않음");
    await expect(items.getByRole("row")).toHaveCount(3); // the header, A and B
    await expect(page.getByText("볼 수 없는 작업 1개", { exact: true })).toBeVisible();
    await page.waitForLoadState("networkidle");
    await axe(page, "릴리스 기록 (열린 기록)");

    // the same record in the dark theme
    await page.emulateMedia({ colorScheme: "dark" });
    await page.reload();
    await expect(page.getByText("볼 수 없는 작업 1개", { exact: true })).toBeVisible();
    await page.waitForLoadState("networkidle");
    await axe(page, "릴리스 기록 (dark)");
    await page.emulateMedia({ colorScheme: "light" });
  });
});
