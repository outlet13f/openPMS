// F10 프로젝트를 만들고 팀 꾸리기 (S03 → S04 → S23)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F10 프로젝트와 멤버", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F10-H1 new project, then add a member", async ({ page }) => {
    await page.goto("/projects");
    await axe(page, "S03 프로젝트 목록");
    await page.getByRole("link", { name: /새 프로젝트|프로젝트 만들기/ }).first().click();
    await axe(page, "S04 새 프로젝트");
    await page.getByLabel(/^프로젝트 이름/).fill("E2E 프로젝트");
    await page.getByLabel(/^식별자/).fill("e2e-project");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await page.waitForURL(/\/projects\/e2e-project/);
    await page.goto("/projects/e2e-project/members");
    await page.getByRole("button", { name: "멤버 추가" }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("checkbox", { name: "박준호" }).click();
    await dlg.getByRole("button", { name: "추가 (1)" }).click();
    await expect(page.getByRole("cell", { name: "박준호" }).first()).toBeVisible();
    await axe(page, "S23 멤버");
  });

  test("F10-E1 a taken identifier is refused next to the field", async ({ page }) => {
    await page.goto("/projects/new");
    await page.getByLabel(/^프로젝트 이름/).fill("중복 시험");
    await page.getByLabel(/^식별자/).fill("web-platform");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await expect(page.getByText(/이미|사용 중/).first()).toBeVisible();
  });

  test("F10-N1 the last project manager cannot be removed", async ({ page }) => {
    await page.goto("/projects/web-platform/members");
    await page.getByRole("button", { name: "김서연 멤버에서 빼기" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "삭제" }).click();
    await expect(page.getByText("마지막 프로젝트 관리자는 뺄 수 없습니다.").first()).toBeVisible();
  });

  test("F10-N2 someone else's private project is invisible (404, not 403)", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "doyoon.han@openpms.local");
    const r = await api<number>(page, "try { await api.projects.get('web-platform'); return 200 } catch (e) { return e.status }");
    expect(r).toBe(404);
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByText(/찾을 수 없|없는 페이지/).first()).toBeVisible();
  });
});
