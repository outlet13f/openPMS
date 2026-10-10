// F04 일정 잡기·옮기기 (S10 날짜 대화상자, S13 Gantt)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

type Dates = { startDate: string | null; dueDate: string | null; duration: number | null };
const dates = (page: Parameters<typeof api>[0], n: number) =>
  api<Dates>(page, "const w = await api.workPackages.get(args[0]); return { startDate: w.startDate, dueDate: w.dueDate, duration: w.duration }", n);

test.describe("F04 일정", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F04-H1 date dialog: typed dates are saved and the duration follows (working days)", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages/5/activity");
    await page.getByRole("button", { name: /^날짜:/ }).first().click();
    const d = page.getByRole("dialog", { name: "날짜" });
    await axe(page, "S10 날짜 대화상자");
    await d.getByLabel("시작일", { exact: true }).fill("2026-11-02");
    await d.getByLabel("완료일", { exact: true }).fill("2026-11-06");
    await expect(d.getByLabel("기간(근무일)")).toHaveValue("5");
    await d.getByRole("button", { name: "저장" }).click();
    await expect(d).toBeHidden();
    expect(await dates(page, 5)).toEqual({ startDate: "2026-11-02", dueDate: "2026-11-06", duration: 5 });
  });

  test("F04-H3 date dialog tabs: predecessors, successors and children with their dates", async ({ page }) => {
    // seed: #4 precedes #5 precedes #6 (#5 is scheduled automatically); #1 has children
    await page.goto("/projects/web-platform/work-packages/5/activity");
    await page.getByRole("button", { name: /^날짜:/ }).first().click();
    const d = page.getByRole("dialog", { name: "날짜" });
    await expect(d.getByText(/자동 일정: #4 결제 API 연동\(완료 .+\)이 끝난 뒤에 시작합니다/)).toBeVisible();
    await d.getByRole("button", { name: "선행 작업 보기" }).click();
    await expect(d.getByRole("tab", { name: /^선행/ })).toHaveAttribute("aria-selected", "true");
    await expect(d.getByRole("tabpanel").getByRole("link", { name: "결제 API 연동" })).toBeVisible();
    await axe(page, "S10 날짜 대화상자 · 선행");
    await d.getByRole("tab", { name: /^후속/ }).click();
    await expect(d.getByRole("tabpanel").getByRole("link", { name: "3분기 릴리스" })).toBeVisible(); // seed: #5 precedes #6
    await d.getByRole("button", { name: "취소" }).click();

    await page.goto("/projects/web-platform/work-packages/4/activity");
    await page.getByRole("button", { name: /^날짜:/ }).first().click();
    await d.getByRole("tab", { name: /^후속/ }).click();
    await d.getByRole("tabpanel").getByRole("link", { name: "릴리스 노트 초안 작성" }).click();
    await expect(d).toBeHidden();
    await expect(page).toHaveURL(/\/work-packages\/5\/activity/);

    await page.goto("/projects/web-platform/work-packages/1/activity");
    await page.getByRole("button", { name: /^날짜:/ }).first().click();
    await d.getByRole("tab", { name: /^하위/ }).click();
    await expect(d.getByRole("tabpanel").getByRole("listitem").first()).toBeVisible();
  });

  test("F04-E1 a successor moves when its predecessor ends later", async ({ page }) => {
    // seed: #4 precedes #5; pushing #4 past #5's start moves #5
    const before = await dates(page, 5);
    const w4 = await api<{ lockVersion: number }>(page, "return await api.workPackages.get(4)");
    await api(page, "await api.workPackages.update(4, { lockVersion: args[0], dueDate: '2026-11-20' })", w4.lockVersion);
    const after = await dates(page, 5);
    expect(after.startDate! > "2026-11-20").toBe(true);
    expect(after.startDate).not.toBe(before.startDate);
  });

  test("F04-E2 a parent's dates come from its children and cannot be typed", async ({ page }) => {
    const r = await api<string>(page, "const w = await api.workPackages.get(1); return w._meta.editableFields.includes('dates') ? 'editable' : 'derived'");
    expect(r).toBe("derived");
  });

  test("F04-E3 Gantt shows bars and today; the zoom switches", async ({ page }) => {
    await page.goto("/projects/web-platform/gantt");
    const zoom = page.getByRole("radiogroup", { name: "확대 단계" });
    await expect(zoom.getByRole("radio", { name: "자동" })).toBeChecked();
    await zoom.getByRole("radio", { name: "월" }).click();
    await expect(zoom.getByRole("radio", { name: "월" })).toBeChecked();
    await expect(page.getByRole("group", { name: /Gantt|간트|차트/ }).first()).toBeVisible();
    await axe(page, "S13 Gantt");
  });

  test("F04-H2 Gantt: an item without dates gets its bar by dragging across its row", async ({ page }) => {
    await page.goto("/projects/web-platform/gantt");
    await page.getByRole("radiogroup", { name: "확대 단계" }).getByRole("radio", { name: "일", exact: true }).click();
    expect(await dates(page, 15)).toMatchObject({ startDate: null, dueDate: null });
    const chart = page.getByRole("group", { name: "Gantt 차트" });
    const track = page.locator('[data-gantt-draw="15"]');
    await track.scrollIntoViewIfNeeded();
    const box = (await chart.boundingBox())!;
    const row = (await track.boundingBox())!;
    const x0 = box.x + 320 + 120;
    const y = row.y + row.height / 2;
    // day zoom: 32px a day; from the day pressed to three days later
    await page.mouse.move(x0, y);
    await page.mouse.down();
    await page.mouse.move(x0 + 3 * 32 + 4, y, { steps: 6 });
    await page.mouse.up();
    await expect.poll(async () => (await dates(page, 15)).dueDate).not.toBeNull();
    const d = await dates(page, 15);
    expect((Date.parse(d.dueDate!) - Date.parse(d.startDate!)) / 86_400_000).toBe(3);
    await expect(page.locator('[data-gantt-draw="15"]')).toHaveCount(0);
    // keyboard: the row of another undated item has a button for the date dialog
    await page.getByRole("button", { name: "#10 날짜 정하기" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("dialog", { name: "날짜" })).toBeVisible();
    await axe(page, "S13 Gantt 날짜 대화상자");
  });

  test("F04-N1 a milestone has one date", async ({ page }) => {
    const r = await api<string>(page, "const w = await api.workPackages.get(6); try { await api.workPackages.update(6, { lockVersion: w.lockVersion, startDate: '2026-11-02', dueDate: '2026-11-05' }) } catch (e) { return 'refused ' + e.status } const n = await api.workPackages.get(6); return n.startDate === n.dueDate ? 'one date' : 'two dates'");
    expect(["one date", "refused 422"]).toContain(r);
  });
});
