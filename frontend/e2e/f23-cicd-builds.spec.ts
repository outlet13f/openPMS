// F23 CI/CD 빌드·배포(docs/superpowers/specs/2026-10-15-cicd-design.md, 2단계): GitLab의 빌드·배포 웹훅과 Jenkins 표준 알림이
// 작업의 개발 탭에 빌드 배지와 환경 줄(개발 ✓ · 스테이징 ✓ · 운영 –)로 나타나고, 환경 미지정 배포는 설정 › 환경에서 별칭으로 맞추며,
// 저장소를 떼어도 기록은 남고 빌드 기록 정리는 빌드 배지만 지운다.
// Needs the test code host (e2e/fake-git.mjs in GitLab mode, see its header; the backend reaches it as
// http://openpms-cicd-git:18090); skipped when it is not running. Its commit graph E1 ← E2 ← E3 answers the compare and
// ancestor questions; the shas below are the same as there and differ in their first digits, so an 8-character image tag
// names exactly one commit. Only E2 carries "#<번호>", so the work package has one linked commit.
// 개발 데이터 V1012: web-platform의 환경 개발(dev, development)·스테이징(stg, staging)·운영(prod, production).
import crypto from "node:crypto";
import type { APIRequestContext, Page } from "@playwright/test";
import { test, expect, login, logout, resetDb, axe, panel, api, post, send, runJob } from "./support";

const GIT_CHECK = `${process.env.FAKE_GIT_URL ?? "http://127.0.0.1:18090"}/health`;
const GIT_BASE = "http://openpms-cicd-git:18090";
const WEB_URL = `${GIT_BASE}/openpms-e2e/svc`;
const JENKINS = "http://jenkins.e2e.invalid";

const ADMIN = "seoyeon.kim@openpms.local";
const MINJI = "minji.choi@openpms.local"; // web-platform 참관자
const GITLAB = "E2E GitLab";
const JENKINS_NAME = "E2E Jenkins";
const TOKEN = "glpat-FAKE_E2E_not_a_real_token_QRST";
const PATH = "openpms-e2e/svc";

// the fake host's graph (fake-git.mjs GITLAB_GRAPHS 9201), oldest first
const E1 = "e1a23f0c5b7d9e1f2a3b4c5d6e7f8091a2b3c4d5";
const E2 = "e2b34a1d6c8e0f2a3b4c5d6e7f8091a2b3c4d5e6";
const E3 = "e3c45b2e7d9f1a3b4c5d6e7f8091a2b3c4d5e6f7";
const UNKNOWN = "e4d56c3f8e0a2b4c5d6e7f8091a2b3c4d5e6f7a8"; // not on the host: its compare answers 404
const LATE = "e5e67d4a9f1b3c5d6e7f8091a2b3c4d5e6f7a8b9"; // pushed while the repository is detached

let gitUp = false;
let projectId = "";
let wp = 0;
let gitlab = { key: "", secret: "" };
let jenkins = { key: "", secret: "" };

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
/** Today in Asia/Seoul, as the date field gives it (the cleanup takes what finished before its 00:00). */
const todaySeoul = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date());

/** A GitLab webhook with the connection's secret token. */
async function gitlabHook(request: APIRequestContext, event: string, payload: unknown) {
  const r = await request.post(`/api/v1/hooks/cicd/${gitlab.key}`, {
    data: Buffer.from(JSON.stringify(payload)),
    headers: { "Content-Type": "application/json", "X-Gitlab-Event": event, "X-Gitlab-Token": gitlab.secret, "X-Gitlab-Event-UUID": crypto.randomUUID() },
  });
  expect(r.status(), event).toBe(202);
  return (await r.json()) as { accepted: number };
}

const project = { id: 9201, path_with_namespace: PATH, web_url: WEB_URL };

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
      url: `${WEB_URL}/-/commit/${c.sha}`,
      author: { name: "박준호", email: "junho.park@openpms.local" },
    })),
  };
}

function deployment(id: number, sha: string, environment: string) {
  return {
    object_kind: "deployment",
    status: "success",
    status_changed_at: gitlabTime(minutesAgo(1)),
    deployment_id: id,
    deployable_id: id + 1000,
    deployable_url: `${WEB_URL}/-/jobs/${id + 1000}`,
    environment,
    project,
    short_sha: sha.slice(0, 8),
    commit_url: `${WEB_URL}/-/commit/${sha}`,
  };
}

/** A Jenkins standard notice, signed with the connection's secret (as the Jenkinsfile's openssl step does). */
async function jenkinsNotice(request: APIRequestContext, notice: Record<string, unknown>) {
  const body = Buffer.from(JSON.stringify(notice));
  const signature = `sha256=${crypto.createHmac("sha256", jenkins.secret).update(body).digest("hex")}`;
  const r = await request.post(`/api/v1/hooks/cicd/${jenkins.key}`, { data: body, headers: { "Content-Type": "application/json", "X-OpenPMS-Signature": signature } });
  expect(r.status()).toBe(202);
  expect(await r.json()).toEqual({ accepted: 1 });
}

const devTab = () => `/projects/web-platform/work-packages/details/${wp}/dev`;
const envItem = (page: Page, name: string) => panel(page).getByRole("list", { name: "배포 환경" }).getByRole("listitem").filter({ hasText: name });
const commitRow = (page: Page, sha: string) => panel(page).getByRole("region", { name: /^커밋/ }).getByRole("listitem").filter({ hasText: sha.slice(0, 7) });

/**
 * Runs the job and reloads the tab until the check holds: a first deployment is decided in the run after it starts, and a
 * deployment waiting behind it goes in on the run after that.
 */
async function processUntil(page: Page, check: () => Promise<void>) {
  await expect(async () => {
    await runJob(page, "cicd-process");
    await page.goto(devTab());
    await check();
  }).toPass({ intervals: [500, 1000, 2000], timeout: 30_000 });
}

test.describe("F23 CI/CD 빌드·배포", () => {
  test("F23-1 GitLab 빌드·배포 웹훅 → 개발 탭의 빌드 배지와 환경 줄", async ({ page, request }) => {
    await login(page, ADMIN);
    projectId = await page.evaluate(async () => ((await (await fetch("/api/v1/projects/web-platform")).json()) as { id: string }).id);
    const made = await post(page, "/api/v1/admin/cicd/connections", { kind: "gitlab", name: GITLAB, baseUrl: GIT_BASE, apiToken: TOKEN });
    expect(made.status).toBe(201);
    const created = made.json as { connection: { id: string; webhookUrl: string }; webhookSecret: string };
    gitlab = { key: keyOf(created.connection.webhookUrl), secret: created.webhookSecret };
    expect((await send(page, "PUT", `/api/v1/admin/cicd/connections/${created.connection.id}/projects`, { projectIds: [projectId] })).status).toBe(200);

    // 프로젝트 설정 › 저장소: find the GitLab project and attach it
    await page.goto("/projects/web-platform/settings/repositories");
    await page.getByRole("button", { name: "저장소 붙이기" }).click();
    const attach = page.getByRole("dialog", { name: "저장소 붙이기" });
    await expect(attach.getByRole("combobox", { name: /^연동/ })).toContainText(GITLAB);
    await attach.getByLabel("저장소 찾기").fill("svc");
    await attach.getByRole("button", { name: `붙이기: ${PATH}` }).click();
    await expect(page.getByText("저장소를 붙였습니다", { exact: true })).toBeVisible();
    await expect(page.getByRole("table", { name: "붙은 저장소" }).getByRole("row").filter({ hasText: PATH })).toContainText(GITLAB);

    wp = await api<number>(
      page,
      "const f = await api.workPackages.form('web-platform'); const type = f.types.find((t) => t.name === '작업') ?? f.types[0]; return (await api.workPackages.create('web-platform', { typeId: type.id, subject: '주문 API 배포 확인' })).number",
    );

    // three commits on main, only E2 names the work package
    expect(
      await gitlabHook(request, "Push Hook", push([
        { sha: E1, message: "서비스 뼈대", at: "2026-10-14T01:00:00Z" },
        { sha: E2, message: `주문 API 추가 (#${wp})`, at: "2026-10-14T02:00:00Z" },
        { sha: E3, message: "설정 정리", at: "2026-10-14T03:00:00Z" },
      ])),
    ).toEqual({ accepted: 3 });
    await runJob(page, "cicd-process");
    await page.goto(devTab());
    await expect(commitRow(page, E2)).toContainText(`주문 API 추가 (#${wp})`);
    await expect(panel(page).getByText("서비스 뼈대")).toHaveCount(0); // not linked

    // the pipeline of E2 finished yesterday (F23-4 cleans it up)
    const yesterday = new Date(Date.now() - 24 * 3600_000);
    expect(
      await gitlabHook(request, "Pipeline Hook", {
        object_kind: "pipeline",
        object_attributes: {
          id: 701,
          name: "CI",
          ref: "main",
          sha: E2,
          status: "success",
          created_at: gitlabTime(new Date(yesterday.getTime() - 10 * 60_000)),
          finished_at: gitlabTime(yesterday),
          url: `${WEB_URL}/-/pipelines/701`,
        },
        project,
      }),
    ).toEqual({ accepted: 1 });
    await runJob(page, "cicd-process");
    await page.goto(devTab());
    await expect(commitRow(page, E2).getByRole("link", { name: /빌드 성공/ })).toHaveAttribute("href", `${WEB_URL}/-/pipelines/701`);

    // E2 deployed to "development" (an alias of 개발): the first deployment asks the host whether E2 is in it
    expect(await gitlabHook(request, "Deployment Hook", deployment(501, E2, "development"))).toEqual({ accepted: 1 });
    await processUntil(page, async () => {
      await expect(envItem(page, "개발")).toContainText("배포됨");
    });
    await expect(envItem(page, "개발").getByRole("link", { name: /개발/ })).toHaveAttribute("href", `${WEB_URL}/-/jobs/1501`);
    await expect(envItem(page, "스테이징")).toContainText("배포 안 됨");
    await expect(envItem(page, "운영")).toContainText("배포 안 됨");
    await expect(commitRow(page, E2)).toContainText("개발");
    await axe(page, "개발 탭 (빌드·배포)");
  });

  test("F23-2 Jenkins 표준 알림으로 운영에 배포하고 되돌린다", async ({ page, request }) => {
    await login(page, ADMIN);
    await page.goto("/admin/cicd");
    await page.getByRole("button", { name: "연동 만들기" }).click();
    const dlg = page.getByRole("dialog", { name: "연동 만들기" });
    await dlg.getByRole("radio", { name: "Jenkins" }).click();
    await dlg.getByLabel(/^이름/).fill(JENKINS_NAME);
    await dlg.getByLabel(/^도구 주소/).fill(JENKINS);
    await expect(dlg.getByLabel(/^API 토큰/)).toHaveCount(0);
    await dlg.getByRole("button", { name: "만들기", exact: true }).click();
    const shown = page.getByRole("dialog", { name: "웹훅 주소와 비밀" });
    const hookUrl = (await shown.getByRole("group", { name: "웹훅 주소" }).locator("code").textContent())!.trim();
    jenkins = { key: keyOf(hookUrl), secret: (await shown.getByRole("group", { name: "웹훅 비밀" }).locator("code").textContent())!.trim() };
    expect(jenkins.secret.length).toBeGreaterThanOrEqual(20);
    await shown.getByRole("button", { name: "확인" }).click();
    await expect(shown).toHaveCount(0);

    // the row menu's 알림 설정 예시: a Jenkinsfile snippet that posts to this connection's address
    await page.getByRole("button", { name: `${JENKINS_NAME} 메뉴` }).click();
    await page.getByRole("menuitem", { name: "알림 설정 예시" }).click();
    const tpl = page.getByRole("dialog", { name: `알림 설정 예시: ${JENKINS_NAME}` });
    await expect(tpl.locator("pre code")).toContainText(hookUrl);
    await expect(tpl.locator("pre code")).not.toContainText(jenkins.secret);
    await axe(page, "알림 설정 예시 (Jenkins)");
    await tpl.getByRole("button", { name: "확인" }).click();
    await expect(tpl).toHaveCount(0);

    const list = (await (await page.request.get("/api/v1/admin/cicd/connections")).json()) as { id: string; name: string }[];
    const jenkinsId = list.find((c) => c.name === JENKINS_NAME)!.id;
    expect((await send(page, "PUT", `/api/v1/admin/cicd/connections/${jenkinsId}/projects`, { projectIds: [projectId] })).status).toBe(200);

    // E3 goes to production: the image tag carries its first 8 characters
    await jenkinsNotice(request, {
      type: "deployment",
      repository: PATH,
      id: "svc-deploy-12",
      status: "success",
      environment: "production",
      image: `registry.e2e/svc:${E3.slice(0, 8)}`,
      at: minutesAgo(10).toISOString(),
      url: `${JENKINS}/job/svc-deploy/12/`,
    });
    await processUntil(page, async () => {
      await expect(envItem(page, "운영")).toContainText("배포됨");
    });
    await expect(envItem(page, "운영").getByRole("link", { name: /운영/ })).toHaveAttribute("href", `${JENKINS}/job/svc-deploy/12/`);

    // then back to E1 (a later deployment): E2 left production
    await jenkinsNotice(request, {
      type: "deployment",
      repository: PATH,
      id: "svc-deploy-13",
      status: "success",
      environment: "production",
      image: `registry.e2e/svc:${E1.slice(0, 8)}`,
      at: minutesAgo(5).toISOString(),
      url: `${JENKINS}/job/svc-deploy/13/`,
    });
    await processUntil(page, async () => {
      await expect(envItem(page, "운영")).toContainText("배포 안 됨");
    });
    await expect(envItem(page, "개발")).toContainText("배포됨");

    // a commit the code host does not know: the compare fails and is asked again later, meanwhile 운영 says 확인 중
    await jenkinsNotice(request, {
      type: "deployment",
      repository: PATH,
      id: "svc-deploy-14",
      status: "success",
      environment: "production",
      sha: UNKNOWN,
      at: minutesAgo(1).toISOString(),
      url: `${JENKINS}/job/svc-deploy/14/`,
    });
    await processUntil(page, async () => {
      await expect(envItem(page, "운영")).toContainText("확인 중");
    });
    await expect(envItem(page, "개발")).toContainText("배포됨");
  });

  test("F23-3 환경 미지정 배포를 별칭으로 맞추고, 참관자는 보기만 한다", async ({ page, request }) => {
    await login(page, ADMIN);
    expect(await gitlabHook(request, "Deployment Hook", deployment(502, E2, "qa-e2e"))).toEqual({ accepted: 1 });
    await runJob(page, "cicd-process");

    await page.goto("/projects/web-platform/settings/environments");
    const unassigned = page.getByRole("table", { name: "환경 미지정 배포" });
    const row = unassigned.getByRole("row").filter({ hasText: "qa-e2e" });
    await expect(row).toContainText("1건");
    await axe(page, "프로젝트 설정 환경 (미지정 배포)");
    await row.getByRole("combobox", { name: "qa-e2e을(를) 더할 환경" }).click();
    await page.getByRole("option", { name: "스테이징", exact: true }).click();
    await row.getByRole("button", { name: "별칭으로 더하기: qa-e2e" }).click();
    await expect(page.getByText("'qa-e2e'을(를) 스테이징 환경의 별칭으로 더했습니다", { exact: true })).toBeVisible();
    await expect(unassigned.getByRole("row").filter({ hasText: "qa-e2e" })).toHaveCount(0);
    await expect(page.getByRole("table", { name: "환경", exact: true }).getByRole("row").filter({ hasText: "스테이징" })).toContainText("qa-e2e");

    await processUntil(page, async () => {
      await expect(envItem(page, "스테이징")).toContainText("배포됨");
    });
    await expect(envItem(page, "개발")).toContainText("배포됨");

    // the same screen in the dark theme
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/projects/web-platform/settings/environments");
    await expect(page.getByRole("table", { name: "환경", exact: true })).toBeVisible();
    await page.waitForLoadState("networkidle");
    await axe(page, "프로젝트 설정 환경 (dark)");
    await page.emulateMedia({ colorScheme: "light" });
    await logout(page);

    // the observer sees the environments and the work's row, changes nothing
    await login(page, MINJI);
    await page.goto("/projects/web-platform/settings/environments");
    await expect(page.getByRole("table", { name: "환경", exact: true }).getByRole("row").filter({ hasText: "스테이징" })).toContainText("qa-e2e");
    await expect(page.getByRole("button", { name: "환경 추가" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^별칭으로 더하기/ })).toHaveCount(0);
    await page.goto(devTab());
    await expect(envItem(page, "스테이징")).toContainText("배포됨");
  });

  test("F23-4 저장소를 떼도 기록은 남고, 빌드 기록 정리는 빌드 배지만 지운다", async ({ page, request }) => {
    await login(page, ADMIN);
    await page.goto("/projects/web-platform/settings/repositories");
    await page.getByRole("button", { name: `떼기: ${PATH}` }).click();
    const confirm = page.getByRole("dialog", { name: `'${PATH}' 저장소를 뗄까요?` });
    await confirm.getByRole("button", { name: "떼기", exact: true }).click();
    await expect(page.getByText("저장소를 뗐습니다", { exact: true })).toBeVisible();
    const detached = page.getByRole("table", { name: "뗀 저장소" });
    await expect(detached.getByRole("row").filter({ hasText: PATH })).toBeVisible();

    await page.goto(devTab());
    await expect(commitRow(page, E2)).toContainText("뗀 저장소");
    await expect(commitRow(page, E2)).toContainText("빌드 성공");
    await expect(envItem(page, "개발")).toContainText("배포됨");

    // the code host still delivers: taken (202), but nothing new reaches the work package
    await gitlabHook(request, "Push Hook", push([{ sha: LATE, message: `떼고 난 뒤의 커밋 (#${wp})`, at: "2026-10-14T04:00:00Z" }]));
    await runJob(page, "cicd-process");
    await page.goto(devTab());
    await expect(commitRow(page, E2)).toBeVisible();
    await expect(panel(page).getByText("떼고 난 뒤의 커밋")).toHaveCount(0);

    // back from the detached group
    await page.goto("/projects/web-platform/settings/repositories");
    await detached.getByRole("button", { name: `다시 붙이기: ${PATH}` }).click();
    await expect(page.getByText("저장소를 다시 붙였습니다", { exact: true })).toBeVisible();
    await expect(page.getByRole("table", { name: "붙은 저장소" }).getByRole("row").filter({ hasText: PATH })).toBeVisible();

    // 빌드 기록 정리 up to today: E2's pipeline finished yesterday
    const today = todaySeoul();
    const counted = await page.evaluate(async (d) => (await (await fetch(`/api/v1/projects/web-platform/cicd/pipelines/cleanup?before=${d}`)).json()) as { count: number }, today);
    expect(counted.count).toBeGreaterThanOrEqual(1);
    await page.getByRole("button", { name: "빌드 기록 정리" }).click();
    const cleanup = page.getByRole("dialog", { name: "빌드 기록 정리" });
    await cleanup.getByLabel(/^이 날짜 앞까지/).fill(today);
    await expect(cleanup).toContainText(`빌드 기록 ${counted.count}건을 지웁니다.`);
    await axe(page, "빌드 기록 정리");
    await cleanup.getByRole("button", { name: "정리", exact: true }).click();
    await expect(page.getByText(`빌드 기록 ${counted.count}건을 지웠습니다.`, { exact: true })).toBeVisible();

    await page.goto(devTab());
    await expect(commitRow(page, E2)).toBeVisible();
    await expect(commitRow(page, E2).getByText("빌드 성공")).toHaveCount(0);
    await expect(envItem(page, "개발")).toContainText("배포됨");
  });
});
