// F02 상태 진행시키기 (S06–S08 상태 pill, S15 보드)
import { test, expect, login, logout, resetDb, axe, api, panel } from "./support";

test.beforeAll(resetDb);

test.describe("F02 상태 진행시키기", () => {
  test("F02-H1 status pill: allowed statuses only, the change is in the activity", async ({ page }) => {
    await login(page, "junho.park@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/4/overview");
    const p = panel(page);
    await p.getByRole("button", { name: /^상태: 진행 중\. 상태 바꾸기$/ }).click();
    // the menu lists the current status too (checked)
    const offered = (await page.getByRole("menuitem").allInnerTexts()).map((s) => s.trim()).filter((s) => s !== "진행 중").sort();
    const allowed = (await api<string[]>(page, "return (await api.workPackages.allowedStatuses(4)).map((s) => s.name)")).filter((s) => s !== "진행 중").sort();
    expect(offered).toEqual(allowed);
    await page.getByRole("menuitem", { name: "검토 중" }).click();
    await expect(p.getByRole("button", { name: /^상태: 검토 중/ })).toBeVisible();
    await p.getByRole("tab", { name: "활동" }).click();
    await expect(p.getByText("상태: 진행 중 → 검토 중")).toBeVisible();
    await axe(page, "S07 활동 탭");
  });

  test("F02-E1 a transition the workflow does not allow is refused by the API", async ({ page }) => {
    await login(page, "junho.park@openpms.local");
    const r = await api<string>(
      page,
      "const w = await api.workPackages.get(4); const allowed = (await api.workPackages.allowedStatuses(4)).map((s) => s.id); const all = (await api.workPackages.filterOptions()).statuses; const other = all.find((s) => !allowed.includes(s.id) && s.id !== w.status.id); if (!other) return 'none'; try { await api.workPackages.update(4, { lockVersion: w.lockVersion, statusId: other.id }); return 'ok' } catch (e) { return String(e.status) }",
    );
    expect(["422", "none"]).toContain(r);
  });

  test("F02-E2 two tabs: the second save is a conflict, not a silent overwrite", async ({ page, context }) => {
    await login(page, "seoyeon.kim@openpms.local");
    const other = await context.newPage();
    await page.goto("/projects/web-platform/work-packages/details/3/overview");
    await other.goto("/projects/web-platform/work-packages/details/3/overview");
    await panel(page).getByRole("button", { name: /^상태: 진행 중\. 상태 바꾸기$/ }).click();
    await page.getByRole("menuitem", { name: "검토 중" }).click();
    await expect(panel(page).getByRole("button", { name: /^상태: 검토 중/ })).toBeVisible();
    // the other tab still holds the old version
    const r = await api<number>(other, "try { await api.workPackages.update(3, { lockVersion: 0, subject: '덮어쓰기' }); return 0 } catch (e) { return e.status }");
    expect(r).toBe(409);
    await other.close();
  });

  test("F02-E3 a read-only status locks everything but the status", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/14/overview");
    await expect(panel(page).getByText(/'취소' 상태에서는 상태만 바꿀 수 있습니다/)).toBeVisible();
    const r = await api<number>(page, "const w = await api.workPackages.get(14); try { await api.workPackages.update(14, { lockVersion: w.lockVersion, subject: 'x' }); return 0 } catch (e) { return e.status }");
    expect(r).toBe(422);
  });

  test("F02-N1 a viewer cannot change the status", async ({ page }) => {
    await login(page, "minji.choi@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/3/overview");
    await expect(panel(page).getByText(/진행 중|검토 중/).first()).toBeVisible();
    await expect(panel(page).getByRole("button", { name: /상태 바꾸기$/ })).toHaveCount(0);
    await logout(page);
  });
});
