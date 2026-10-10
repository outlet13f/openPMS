// F06 칸반 보드로 흐름 관리 (S14 → S15, S16)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

const column = (page: import("@playwright/test").Page, name: string) => page.locator("main section[aria-label]").filter({ has: page.getByRole("heading", { name }) });

test.describe("F06 보드", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F06-H1 keyboard drag a card to the next column: the status changes", async ({ page }) => {
    await page.goto("/projects/web-platform/boards");
    await axe(page, "S14 보드 목록");
    await page.getByRole("link", { name: "진행 현황" }).first().click();
    await expect(page.getByRole("region", { name: "신규" })).toBeVisible();
    await axe(page, "S15 보드");
    const grip = page.getByRole("button", { name: "#9 키보드만으로 보드 카드 옮기기 옮기기" });
    await grip.focus();
    await page.keyboard.press("Space");
    await page.waitForTimeout(200);
    await page.keyboard.press("ArrowRight");
    await page.waitForTimeout(300);
    await page.keyboard.press("Space");
    await expect(page.getByRole("region", { name: "진행 중" }).getByRole("link", { name: "키보드만으로 보드 카드 옮기기" })).toBeVisible();
    await expect.poll(() => api<string>(page, "return (await api.workPackages.get(9)).status.name")).toBe("진행 중");
  });

  test("F06-H2 make a new status board", async ({ page }) => {
    await page.goto("/projects/web-platform/boards");
    await page.getByRole("link", { name: "보드 만들기" }).first().click();
    await page.getByLabel(/^보드 이름/).fill("E2E 보드");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await expect(page.getByRole("heading", { name: "E2E 보드" })).toBeVisible();
    await expect(page.getByRole("region", { name: "신규" })).toBeVisible();
  });

  test("F06-N1 a viewer gets no drag handles", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "minji.choi@openpms.local");
    await page.goto("/projects/web-platform/boards");
    await page.getByRole("link", { name: "진행 현황" }).first().click();
    await expect(page.getByRole("region", { name: "신규" })).toBeVisible();
    await expect(page.getByRole("button", { name: /옮기기$/ })).toHaveCount(0);
    void column;
  });
});
