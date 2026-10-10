// F00 로그인 (S01) — the gate every flow passes: wrong password, a locked account, an expired session, the return path.
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);

test.describe("F00 로그인", () => {
  test("F00-H1 sign in and land where you were going", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await page.waitForURL(/\/login\?next=/);
    await axe(page, "S01 로그인");
    await page.getByLabel("이메일").fill("seoyeon.kim@openpms.local");
    await page.getByLabel("비밀번호").fill("openpms!");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await page.waitForURL(/\/projects\/web-platform\/work-packages/);
  });

  test("F00-N1 a wrong password says so, without telling which part was wrong", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("이메일").fill("seoyeon.kim@openpms.local");
    await page.getByLabel("비밀번호").fill("not-the-password");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await expect(page.getByText("이메일 또는 비밀번호가 올바르지 않습니다.")).toBeVisible();
  });

  test("F00-N2 empty fields: the first empty one gets the focus", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await expect(page.getByLabel("이메일")).toBeFocused();
  });

  test("F00-E1 an expired session sends you back to sign in", async ({ page, context }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await context.clearCookies();
    await page.goto("/projects");
    await page.waitForURL(/\/login/);
  });

  test("F00-E2 an open redirect is not followed", async ({ page }) => {
    await page.goto("/login?next=//evil.example/x");
    await page.getByLabel("이메일").fill("seoyeon.kim@openpms.local");
    await page.getByLabel("비밀번호").fill("openpms!");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await page.waitForURL((u) => u.origin.includes("localhost") && !u.pathname.startsWith("/login"));
    expect(new URL(page.url()).host).toContain("localhost");
  });

  test("F00-E3 390px: the sign-in card fits", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/login");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  });
});
