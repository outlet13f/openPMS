// S12 내보내기: Excel, CSV and PDF (table, report, Gantt) of the list as it is filtered.
import { readFileSync } from "node:fs";
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);

test.describe("F14 내보내기", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F14-H1 a PDF report of the list downloads", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();
    await page.getByRole("button", { name: "보기 메뉴" }).click();
    await page.getByRole("menuitem", { name: "내보내기" }).click();
    const dlg = page.getByRole("dialog", { name: "내보내기" });
    await dlg.getByRole("radio", { name: /^PDF/ }).click();
    await expect(dlg.getByRole("radiogroup", { name: "PDF 종류" })).toBeVisible();
    await dlg.getByRole("radio", { name: /^보고서/ }).click();
    await expect(dlg.getByRole("list", { name: "내보낼 열" })).toHaveCount(0); // a report has no columns to choose
    await axe(page, "S12 내보내기 (PDF)");
    const download = page.waitForEvent("download");
    await dlg.getByRole("button", { name: "내보내기" }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.pdf$/);
    expect(readFileSync(await file.path()).subarray(0, 5).toString()).toBe("%PDF-");
  });
});
