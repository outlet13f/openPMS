// F11 관리자가 업무 규칙 구성 (S27 유형 → S29 워크플로, S30 상태)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F11 관리", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F11-H1 new type → its workflow → used in a project", async ({ page }) => {
    await page.goto("/admin/types");
    await axe(page, "S27 유형");
    await page.getByRole("button", { name: "유형 추가" }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("textbox", { name: "이름" }).fill("검토 요청");
    await dlg.getByRole("textbox", { name: "색", exact: true }).fill("#0b7575");
    await dlg.getByRole("button", { name: "저장" }).click();
    await dlg.getByRole("link", { name: "워크플로 설정하기" }).click();
    await axe(page, "S29 워크플로");
    await page.getByRole("checkbox", { name: "신규에서 진행 중(으)로 전환 허용" }).click();
    await page.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText(/저장했습니다/).first()).toBeVisible();
    await page.goto("/projects/web-platform/settings/work-packages");
    await page.getByRole("checkbox", { name: "검토 요청" }).click();
    await page.locator("section", { hasText: "사용할 유형" }).getByRole("button", { name: "저장" }).click();
    await expect(page.getByText(/저장했습니다/).first()).toBeVisible();
    const types = await api<string[]>(page, "return (await api.workPackages.form('web-platform')).types.map((t) => t.name)");
    expect(types).toContain("검토 요청");
  });

  test("F11-E1 a status in use cannot be deleted", async ({ page }) => {
    await page.goto("/admin/statuses");
    await axe(page, "S30 상태");
    await page.getByRole("button", { name: "진행 중 메뉴" }).click();
    await page.getByRole("menuitem", { name: "삭제" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "삭제" }).click();
    await expect(page.getByText(/쓰고 있는|사용 중/).first()).toBeVisible();
  });

  test("F11-E2 someone locked out by failed sign-ins shows as such and can be let in again", async ({ page }) => {
    // five wrong passwords lock the account for a while (관리 · 인증); the status itself stays active
    await api(page, "for (let i = 0; i < 5; i++) { try { await api.auth.login(args[0], 'wrong-' + i) } catch {} }", "doyoon.han@openpms.local");
    await page.goto("/admin/users");
    const row = page.getByRole("row").filter({ hasText: "doyoon.han@openpms.local" });
    await expect(row.getByText("일시 잠김")).toBeVisible();
    await expect(row.getByText(/로그인 실패가 많아 .+까지/)).toBeVisible();
    await page.getByRole("button", { name: "잠김", exact: true }).click();
    await expect(row).toBeVisible();
    await axe(page, "S35 사용자 (일시 잠김)");
    await row.getByRole("button", { name: /메뉴$/ }).click();
    await page.getByRole("menuitem", { name: "잠금 해제" }).click();
    await expect(page.getByText(/잠금을 풀었습니다/).first()).toBeVisible();
    await expect(row).toHaveCount(0);
  });

  test("F11-N1 a member cannot open administration", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "junho.park@openpms.local");
    await page.goto("/admin/types");
    await expect(page.getByText(/관리자만/).first()).toBeVisible();
    const r = await api<number>(page, "try { await api.admin.types.list(); return 200 } catch (e) { return e.status }");
    expect(r).toBe(403);
  });
});
