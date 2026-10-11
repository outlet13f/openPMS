// 키보드 단축키: ? help, / search, n new work package, g + a letter to go; off while typing or when switched off in the account.
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F13 키보드 단축키", () => {
  test.beforeEach(async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    // the seed account has them off; start from on
    await api(page, "await api.account.updatePreferences({ keyboardShortcuts: true })");
  });

  test("F13-H1 help, search, go to a page, a new work package", async ({ page }) => {
    await page.goto("/projects/web-platform");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await page.keyboard.press("?");
    const help = page.getByRole("dialog", { name: "키보드 단축키" });
    await expect(help.getByText("작업 목록으로")).toBeVisible();
    await axe(page, "단축키 도움말");
    await page.keyboard.press("Escape");
    await page.keyboard.press("g");
    await page.keyboard.press("w");
    await expect(page).toHaveURL(/\/projects\/web-platform\/work-packages/);
    await page.keyboard.press("n");
    await expect(page).toHaveURL(/\/projects\/web-platform\/work-packages\/new/);
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await page.keyboard.press("/");
    const search = page.getByRole("combobox", { name: "전체 검색" });
    await expect(search).toBeFocused();
    // typing in a field types, it does not jump
    await page.keyboard.type("gp");
    await expect(search).toHaveValue("gp");
    await expect(page).toHaveURL(/\/$/);
  });

  test("F13-N1 switched off in the account settings, the keys do nothing", async ({ page }) => {
    await page.goto("/my/account");
    await page.getByRole("checkbox", { name: /키보드 단축키/ }).click();
    await expect(page.getByRole("checkbox", { name: /키보드 단축키/ })).not.toBeChecked();
    await page.locator("main").click({ position: { x: 5, y: 5 } });
    await page.keyboard.press("g");
    await page.keyboard.press("p");
    await expect(page).toHaveURL(/\/my\/account/);
    await page.keyboard.press("?");
    await expect(page.getByRole("dialog", { name: "키보드 단축키" })).toHaveCount(0);
  });
});
