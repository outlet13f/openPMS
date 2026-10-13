// F03 목록을 그룹으로 나누고 합계 보기 (S06 보기 메뉴 · 그룹화, 합계 표시)
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);

test.describe("F03 그룹화와 합계", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  async function viewMenu(page: import("@playwright/test").Page, path: string[]) {
    // the saved view arrives with the list: a change made before that would be replaced by it
    await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();
    await page.getByRole("button", { name: "보기 메뉴" }).click();
    // by keyboard: a pointer moving from a submenu trigger to its items can close the submenu on the way
    for (const [i, name] of path.entries()) {
      const item = page.getByRole(i === path.length - 1 ? "menuitemcheckbox" : "menuitem", { name, exact: true });
      await item.focus();
      await page.keyboard.press(i === path.length - 1 ? "Enter" : "ArrowRight");
    }
    // the menu fades out: an axe scan during that half-transparent moment flags its items for contrast
    await expect(page.getByRole("menu")).toHaveCount(0);
  }

  test("F03-G1 group by status: one header per status with its count, groups fold, the list is flat", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByRole("button", { name: /계층 표시/ })).toHaveAttribute("aria-pressed", "true");
    await viewMenu(page, ["그룹화", "상태"]);
    const table = page.getByRole("table");
    const headers = table.getByRole("button", { name: /그룹:/ });
    await expect(headers.first()).toBeVisible();
    expect(await headers.count()).toBeGreaterThan(1);
    await expect(headers.first()).toContainText(/\d+건/);
    // grouping turns the hierarchy off
    await expect(page.getByRole("button", { name: /계층 표시/ })).toHaveAttribute("aria-pressed", "false");
    await axe(page, "S06 상태별 그룹");

    const first = headers.first();
    const rowsBefore = await table.getByRole("row").count();
    await first.click();
    await expect(first).toHaveAttribute("aria-expanded", "false");
    expect(await table.getByRole("row").count()).toBeLessThan(rowsBefore);
    await first.click();
    await expect(first).toHaveAttribute("aria-expanded", "true");

    // the view (with its grouping) is in the URL: a reload keeps it
    await page.reload();
    await expect(table.getByRole("button", { name: /그룹:/ }).first()).toBeVisible();

    // hierarchy back on: no more groups
    await page.getByRole("button", { name: /계층 표시/ }).click();
    await expect(table.getByRole("button", { name: /그룹:/ })).toHaveCount(0);
  });

  test("F03-G2 sums: work and remaining work per group and in total", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await viewMenu(page, ["표시할 열", "작업량"]);
    await viewMenu(page, ["표시할 열", "남은 작업"]);
    await viewMenu(page, ["그룹화", "담당자"]);
    await viewMenu(page, ["합계 표시"]);
    const table = page.getByRole("table");
    await expect(table.getByRole("columnheader", { name: /작업량/ })).toBeVisible();
    const total = table.getByRole("row").filter({ hasText: "전체 합계" });
    await expect(total).toBeVisible();
    await expect(total).toContainText(/\d+(\.\d+)?시간/);
    await expect(table.getByText(/ 합계$/).first()).toBeVisible();
    await axe(page, "S06 합계 표시");

    // off again: the sums rows go
    await viewMenu(page, ["합계 표시"]);
    await expect(table.getByText("전체 합계")).toHaveCount(0);
  });
  test("F03-G7 highlighting: whole rows in the status colour, or the priority and due date cells", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await viewMenu(page, ["강조 표시", "행 색: 상태"]);
    const row = page.getByRole("row").filter({ hasText: "화면 설계 검토" });
    await expect(row).toHaveAttribute("style", /color-mix/);
    await expect(page).toHaveURL(/[?&]q=/);
    await axe(page, "S06 행 강조");
    await viewMenu(page, ["강조 표시", "셀 강조(우선순위, 완료일 지남·임박)"]);
    await expect(row).not.toHaveAttribute("style", /color-mix/);
    await expect(row.locator("span[style*='color-mix']").first()).toBeVisible();
    await axe(page, "S06 셀 강조");
  });
});
