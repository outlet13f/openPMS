// S06 일괄 편집과 우클릭 메뉴: one row or the checked rows — change, copy, move, delete; all or nothing.
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

const total = (page: import("@playwright/test").Page) =>
  api<number>(
    page,
    "return (await api.workPackages.query({ v: 1, filters: [], columns: ['subject'], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }, { projectId: 'web-platform', page: 1, pageSize: 20 })).total",
  );

test.describe("F12 일괄 편집", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F12-H1 right click a row: change its status, or open it", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    const row = page.getByRole("row").filter({ hasText: "화면 설계 검토" });
    await row.click({ button: "right" });
    const menu = page.getByRole("menu");
    await expect(menu.getByRole("menuitem", { name: "상세 패널에서 열기" })).toBeVisible();
    await axe(page, "S06 우클릭 메뉴");
    await menu.getByRole("menuitem", { name: "상태" }).focus();
    await page.keyboard.press("ArrowRight");
    await page.getByRole("menuitem", { name: "검토 중" }).click();
    await expect(page.getByText("1건을 바꿨습니다").first()).toBeVisible();
    expect(await api<string>(page, "return (await api.workPackages.get(3)).status.name")).toBe("검토 중");

    await row.click({ button: "right" });
    await page.getByRole("menu").getByRole("menuitem", { name: "상세 패널에서 열기" }).click();
    await expect(page).toHaveURL(/\/details\/3\/overview/);
  });

  test("F12-H2 the checked rows: copy them from the row menu, keeping the hierarchy among them", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    const before = await total(page);
    await page.getByRole("checkbox", { name: /^#7 / }).check();
    const child = page.getByRole("row").filter({ has: page.getByRole("checkbox", { name: /^#9 / }) });
    await child.getByRole("checkbox").check();
    await child.click({ button: "right" });
    const menu = page.getByRole("menu");
    await expect(menu.getByText("2건 선택")).toBeVisible();
    await menu.getByRole("menuitem", { name: "복사" }).click();
    const dlg = page.getByRole("dialog", { name: "작업 2건 복사" });
    await expect(dlg.getByRole("combobox", { name: /대상 프로젝트/ })).toContainText("웹 플랫폼 개편");
    await dlg.getByRole("button", { name: "복사하기" }).click();
    await expect(page.getByText("2건을 복사했습니다").first()).toBeVisible();
    expect(await total(page)).toBe(before + 2);
    // seed: #9 is a child of #7, so the copy of #9 hangs under the copy of #7
    const copies = await api<{ number: number; parent: number | null }[]>(
      page,
      "const r = await api.workPackages.query({ v: 1, filters: [], columns: ['subject'], sort: [{ field: 'number', dir: 'desc' }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }, { projectId: 'web-platform', page: 1, pageSize: 2 }); return r.rows.map((x) => ({ number: x.wp.number, parent: x.wp.parent?.number ?? null }))",
    );
    expect(copies[0].parent).toBe(copies[1].number);
  });

  test("F12-N1 a copy that does not fit the target changes nothing and says which item", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    const before = await api<number>(page, "return (await api.workPackages.query({ v: 1, filters: [], columns: ['subject'], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }, { projectId: 'mobile-app', page: 1, pageSize: 20 })).total");
    for (const n of [11, 12]) await page.getByRole("checkbox", { name: new RegExp(`^#${n} `) }).check();
    await page.getByRole("region", { name: "선택한 작업 일괄 편집" }).getByRole("button", { name: "복사" }).click();
    const dlg = page.getByRole("dialog", { name: "작업 2건 복사" });
    await dlg.getByRole("combobox", { name: /대상 프로젝트/ }).click();
    await page.getByRole("option", { name: "모바일 앱 2.0" }).click();
    await dlg.getByRole("button", { name: "복사하기" }).click();
    await expect(page.getByRole("alert").getByText(/전체를 되돌렸습니다/)).toBeVisible();
    await expect(page.getByText(/#12:/)).toBeVisible();
    expect(await api<number>(page, "return (await api.workPackages.query({ v: 1, filters: [], columns: ['subject'], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }, { projectId: 'mobile-app', page: 1, pageSize: 20 })).total")).toBe(before);
  });
});
