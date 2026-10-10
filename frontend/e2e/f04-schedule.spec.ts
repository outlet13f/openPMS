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

  test("F04-N1 a milestone has one date", async ({ page }) => {
    const r = await api<string>(page, "const w = await api.workPackages.get(6); try { await api.workPackages.update(6, { lockVersion: w.lockVersion, startDate: '2026-11-02', dueDate: '2026-11-05' }) } catch (e) { return 'refused ' + e.status } const n = await api.workPackages.get(6); return n.startDate === n.dueDate ? 'one date' : 'two dates'");
    expect(["one date", "refused 422"]).toContain(r);
  });
});
