// F11 관리 · 달력과 날짜 · 날짜 형식: date and time formats and the first weekday, for every screen
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);
test.afterAll(resetDb); // the formats apply to everyone: leave the defaults for the next spec

test.describe("F11 날짜 형식", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F11-D1 an administrator switches to ISO dates, a 12-hour clock and Monday weeks", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByRole("cell", { name: /^\d{4}\.\d{2}\.\d{2}$/ }).first()).toBeVisible();

    await page.goto("/admin/calendar");
    const section = page.getByRole("region", { name: "날짜 형식" });
    await expect(section).toBeVisible();
    await section.getByRole("combobox", { name: "날짜" }).click();
    await page.getByRole("option", { name: /^YYYY-MM-DD/ }).click();
    await section.getByRole("combobox", { name: "시간" }).click();
    await page.getByRole("option", { name: /12시간/ }).click();
    await section.getByRole("combobox", { name: /주 시작 요일/ }).click();
    await page.getByRole("option", { name: "월요일" }).click();
    await expect(section.getByText(/미리 보기: \d{4}-\d{2}-\d{2} 오후 3:05/)).toBeVisible();
    await axe(page, "S33 달력과 날짜");
    await section.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText("날짜 형식을 저장했습니다").first()).toBeVisible();

    // the list writes dates the new way
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByRole("cell", { name: /^\d{4}-\d{2}-\d{2}$/ }).first()).toBeVisible();
    await expect(page.getByRole("cell", { name: /^\d{4}\.\d{2}\.\d{2}$/ })).toHaveCount(0);

    // the exact time of an activity entry (its tooltip) on a 12-hour clock
    await page.goto("/projects/web-platform/work-packages/3/activity");
    await expect(page.locator("main time[title]").first()).toHaveAttribute("title", /^\d{4}-\d{2}-\d{2} (오전|오후) \d{1,2}:\d{2}$/);

    // the date picker's month grid starts on Monday
    await page.goto("/projects/web-platform/work-packages/5/activity");
    await page.getByRole("button", { name: /^날짜:/ }).first().click();
    const grid = page.getByRole("dialog", { name: "날짜" }).getByRole("grid").first();
    await expect(grid.getByRole("columnheader").first()).toHaveText(/월/);
  });
});
