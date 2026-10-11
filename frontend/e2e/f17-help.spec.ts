// F17 도움말 (replica/fixes.md 5번): 이 화면 도움말 opens the manual's section for the screen in a panel, the manuals
// are readable and searchable inside the app, and every screen-to-section link points at a section that exists.
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(() => resetDb());

const helpMenu = (page: import("@playwright/test").Page) => page.getByRole("button", { name: "도움말", exact: true });

test("F17-H1 이 화면 도움말: the board's section opens over the board, links switch sections, the full manual opens there", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/projects/web-platform/boards");
  await expect(page.getByRole("heading", { name: "보드", exact: true })).toBeVisible();
  await helpMenu(page).click();
  await page.getByRole("menuitem", { name: "이 화면 도움말" }).click();
  const help = page.getByRole("dialog", { name: "7.2 보드" });
  await expect(help).toBeVisible();
  await expect(help.getByText("카드 옮기기")).toBeVisible();
  await axe(page, "도움말 패널");
  // the board is still there underneath; closing returns to it
  await page.keyboard.press("Escape");
  await expect(help).toBeHidden();
  await expect(page).toHaveURL(/\/projects\/web-platform\/boards$/);

  // a cross reference inside the section shows that section in the panel
  await helpMenu(page).click();
  await page.getByRole("menuitem", { name: "이 화면 도움말" }).click();
  const cross = help.locator('a[href^="/help#"]').first();
  const to = decodeURIComponent((await cross.getAttribute("href"))!.replace("/help#", ""));
  await cross.click();
  await expect(page.getByRole("dialog")).not.toHaveAccessibleName("7.2 보드");
  await page.getByRole("dialog").getByRole("link", { name: "전체 매뉴얼에서 보기" }).click();
  await expect(page).toHaveURL(new RegExp(`/help#${encodeURIComponent(to).replace(/[()]/g, "\\$&")}$`));
  await expect(page.locator(`[id="${to}"]`)).toBeInViewport();
});

test("F17-H2 사용자 매뉴얼: contents, search, and an address that goes to a section", async ({ page }) => {
  await login(page, "junho.park@openpms.local");
  await page.goto("/help");
  await expect(page.getByRole("navigation", { name: "목차" })).toBeVisible();
  await axe(page, "도움말 · 사용자 매뉴얼");
  await page.getByRole("searchbox", { name: "매뉴얼 검색" }).fill("기준선");
  const results = page.getByRole("region", { name: "검색 결과" });
  await expect(results.getByText(/\d+개 절/)).toBeVisible();
  await results.getByRole("link", { name: /6\.9 기준선 비교/ }).click();
  await expect(page).toHaveURL(/\/help#69-/);
  await expect(page.getByRole("heading", { name: "6.9 기준선 비교" })).toBeInViewport();
  await page.getByRole("searchbox", { name: "매뉴얼 검색" }).fill("없는말쯤되는검색어");
  await expect(results.getByText("찾는 말이 들어간 절이 없습니다.")).toBeVisible();
  // the table of contents
  await page.getByRole("searchbox", { name: "매뉴얼 검색" }).fill("");
  await page.getByRole("navigation", { name: "목차" }).getByRole("link", { name: "7.1 Gantt" }).click();
  await expect(page.getByRole("heading", { name: /^7\.1 Gantt/ })).toBeInViewport();
  // a member has no operator manual in the help menu
  await helpMenu(page).click();
  await expect(page.getByRole("menuitem", { name: "사용자 매뉴얼" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "운영자 매뉴얼" })).toHaveCount(0);
});

test("F17-H3 every screen's help points at a section that exists", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  const missing = await page.evaluate(async () => {
    const { ALL_TOPICS } = await import("/src/features/help/topics.ts" as string);
    const { loadManual } = await import("/src/features/help/manuals.ts" as string);
    const { sectionByNumber } = await import("/src/features/help/markdown.ts" as string);
    const manuals = { user: await loadManual("user"), admin: await loadManual("admin") };
    return (ALL_TOPICS as { manual: "user" | "admin"; section: string }[])
      .filter((t) => !sectionByNumber(manuals[t.manual], t.section))
      .map((t) => `${t.manual} ${t.section}`);
  });
  expect(missing).toEqual([]);
  // and a few addresses get the section meant for them
  const topics = await page.evaluate(async () => {
    const { topicFor } = await import("/src/features/help/topics.ts" as string);
    return ["/projects/web-platform/work-packages", "/projects/web-platform/work-packages/details/3/activity", "/projects/web-platform/gantt",
      "/my/timesheet", "/admin/users", "/admin", "/"].map((p) => `${p} → ${topicFor(p)?.manual} ${topicFor(p)?.section}`);
  });
  expect(topics).toEqual([
    "/projects/web-platform/work-packages → user 6",
    "/projects/web-platform/work-packages/details/3/activity → user 5.2",
    "/projects/web-platform/gantt → user 7.1",
    "/my/timesheet → user 15.1",
    "/admin/users → admin 9.2",
    "/admin → admin 9.1",
    "/ → user 3.1",
  ]);
});

test("F17-H4 관리: the operator manual for admin screens, and what openPMS can do", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/admin/users");
  await helpMenu(page).click();
  await page.getByRole("menuitem", { name: "이 화면 도움말" }).click();
  const help = page.getByRole("dialog", { name: /^9\.2 사용자/ });
  await expect(help).toBeVisible();
  await expect(help.getByText("운영자 매뉴얼")).toBeVisible();
  await page.keyboard.press("Escape");
  await page.goto("/admin");
  const can = page.getByRole("region", { name: "openPMS로 할 수 있는 일" });
  await expect(can).toBeVisible();
  await axe(page, "관리 · 할 수 있는 일");
  await can.getByRole("link", { name: "Gantt", exact: false }).first().click();
  await expect(page).toHaveURL(/\/help#71-gantt/);
  await page.goto("/help/admin");
  await expect(page.getByRole("heading", { name: /^9\.17 시스템 정보/ })).toBeAttached();
});
