// F11 관리자가 업무 규칙 구성 (S27 유형 → S29 워크플로, S30 상태)
import { test, expect, login, logout, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F11 관리", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F11-H1 new type → its workflow → used in a project", async ({ page }) => {
    await page.goto("/admin/types");
    await axe(page, "S27 유형");
    await page.getByRole("button", { name: "유형 추가" }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("textbox", { name: "이름" }).fill("검토 요청");
    await dlg.getByRole("textbox", { name: "색", exact: true }).fill("#0b7575");
    await dlg.getByRole("button", { name: "저장" }).click();
    await dlg.getByRole("link", { name: "워크플로 설정하기" }).click();
    await axe(page, "S29 워크플로");
    await page.getByRole("checkbox", { name: "신규에서 진행 중(으)로 전환 허용" }).click();
    await page.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText(/저장했습니다/).first()).toBeVisible();
    await page.goto("/projects/web-platform/settings/work-packages");
    await page.getByRole("checkbox", { name: "검토 요청" }).click();
    await page.locator("section", { hasText: "사용할 유형" }).getByRole("button", { name: "저장" }).click();
    await expect(page.getByText(/저장했습니다/).first()).toBeVisible();
    const types = await api<string[]>(page, "return (await api.workPackages.form('web-platform')).types.map((t) => t.name)");
    expect(types).toContain("검토 요청");
  });

  test("F11-E1 a status in use cannot be deleted", async ({ page }) => {
    await page.goto("/admin/statuses");
    await axe(page, "S30 상태");
    await page.getByRole("button", { name: "진행 중 메뉴" }).click();
    await page.getByRole("menuitem", { name: "삭제" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "삭제" }).click();
    await expect(page.getByText(/쓰고 있는|사용 중/).first()).toBeVisible();
  });

  test("F11-E2 someone locked out by failed sign-ins shows as such and can be let in again", async ({ page }) => {
    // five wrong passwords lock the account for a while (관리 · 인증); the status itself stays active
    await api(page, "for (let i = 0; i < 5; i++) { try { await api.auth.login(args[0], 'wrong-' + i) } catch {} }", "doyoon.han@openpms.local");
    await page.goto("/admin/users");
    const row = page.getByRole("row").filter({ hasText: "doyoon.han@openpms.local" });
    await expect(row.getByText("일시 잠김")).toBeVisible();
    await expect(row.getByText(/로그인 실패가 많아 .+까지/)).toBeVisible();
    await page.getByRole("button", { name: "잠김", exact: true }).click();
    await expect(row).toBeVisible();
    await axe(page, "S35 사용자 (일시 잠김)");
    await row.getByRole("button", { name: /메뉴$/ }).click();
    await page.getByRole("menuitem", { name: "잠금 해제" }).click();
    await expect(page.getByText(/잠금을 풀었습니다/).first()).toBeVisible();
    await expect(row).toHaveCount(0);
  });

  test("F11-H2 a global role given to a person lets them create projects", async ({ page }) => {
    await page.goto("/admin/users");
    const row = page.getByRole("row").filter({ hasText: "haneul.lee@openpms.local" });
    await row.getByRole("button", { name: /메뉴$/ }).click();
    await page.getByRole("menuitem", { name: "전역 역할" }).click();
    const dlg = page.getByRole("dialog", { name: "이하늘님의 전역 역할" });
    await dlg.getByRole("checkbox", { name: /프로젝트 생성자/ }).click();
    await axe(page, "S35 전역 역할");
    await dlg.getByRole("button", { name: "저장" }).click();
    await expect(dlg).toBeHidden();
    await expect(row.getByText("프로젝트 생성자")).toBeVisible();

    await logout(page);
    await login(page, "haneul.lee@openpms.local");
    await page.goto("/projects/new");
    await page.getByLabel(/^프로젝트 이름/).fill("하늘의 프로젝트");
    await page.getByLabel(/^식별자/).fill("haneul-project");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await page.waitForURL(/\/projects\/haneul-project/);
  });

  test("F11-H3 the permission report: every role against every permission, changed in place", async ({ page }) => {
    await page.goto("/admin/roles");
    await page.getByRole("link", { name: "권한 보고서" }).click();
    await expect(page.getByRole("heading", { name: "권한 보고서", level: 1 })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "멤버: 프로젝트 보기", exact: true })).toBeChecked();
    const cell = page.getByRole("checkbox", { name: "멤버: 내보내기", exact: true });
    const was = await cell.isChecked();
    await cell.click();
    await axe(page, "S31 권한 보고서");
    await page.getByRole("button", { name: "역할 1개 저장" }).click();
    await expect(page.getByText("역할 1개의 권한을 저장했습니다").first()).toBeVisible();
    const perms = await api<string[]>(page, "return (await api.admin.roles.list()).find((r) => r.name === '멤버').permissions");
    expect(perms.includes("wp.export")).toBe(!was);
  });

  test("F11-N2 a user manager who is not an administrator sees no global-role item and cannot hand roles out", async ({ page }) => {
    const users = await api<{ id: string; email: string }[]>(page, "return await api.admin.users.list()");
    const doyoon = users.find((u) => u.email === "doyoon.han@openpms.local")!.id;
    const role = await api<{ id: string }>(page, "return await api.admin.roles.create({ name: '사용자 관리자(e2e)', scope: 'global', permissions: ['global.manage_users'] })");
    await api(page, "await api.admin.users.setGlobalRoles(args[0], [args[1]])", doyoon, role.id);
    try {
      await logout(page);
      await login(page, "doyoon.han@openpms.local");
      await page.goto("/admin/users");
      const row = page.getByRole("row").filter({ hasText: "haneul.lee@openpms.local" });
      await row.getByRole("button", { name: /메뉴$/ }).click();
      await expect(page.getByRole("menuitem").first()).toBeVisible();
      await expect(page.getByRole("menuitem", { name: "전역 역할" })).toHaveCount(0);
      await page.keyboard.press("Escape");
      // and the server says no as well
      const refused = await api<number>(page, "try { await api.admin.users.setGlobalRoles(args[0], [args[1]]); return 200 } catch (e) { return e.status }", doyoon, role.id);
      expect(refused).toBe(403);
    } finally {
      await logout(page);
      await login(page, "seoyeon.kim@openpms.local");
      await api(page, "await api.admin.users.setGlobalRoles(args[0], []); await api.admin.roles.remove(args[1])", doyoon, role.id);
    }
  });
  test("F11-N1 a member cannot open administration", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "junho.park@openpms.local");
    await page.goto("/admin/types");
    await expect(page.getByText(/관리자만/).first()).toBeVisible();
    const r = await api<number>(page, "try { await api.admin.types.list(); return 200 } catch (e) { return e.status }");
    expect(r).toBe(403);
  });
});
