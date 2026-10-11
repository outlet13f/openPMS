// F10 프로젝트를 만들고 팀 꾸리기 (S03 → S04 → S23)
import { test, expect, login, logout, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F10 프로젝트와 멤버", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F10-H1 new project, then add a member", async ({ page }) => {
    await page.goto("/projects");
    await axe(page, "S03 프로젝트 목록");
    await page.getByRole("link", { name: /새 프로젝트|프로젝트 만들기/ }).first().click();
    await axe(page, "S04 새 프로젝트");
    await page.getByLabel(/^프로젝트 이름/).fill("E2E 프로젝트");
    await page.getByLabel(/^식별자/).fill("e2e-project");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await page.waitForURL(/\/projects\/e2e-project/);
    await page.goto("/projects/e2e-project/members");
    await page.getByRole("button", { name: "멤버 추가" }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByRole("checkbox", { name: "박준호" }).click();
    await dlg.getByRole("button", { name: "추가 (1)" }).click();
    await expect(page.getByRole("cell", { name: "박준호" }).first()).toBeVisible();
    await axe(page, "S23 멤버");
  });

  test("F10-H2 a project marked as template starts a new project with its work and members", async ({ page }) => {
    await page.goto("/projects/new");
    await expect(page.getByRole("radio", { name: /템플릿에서 시작/ })).toBeDisabled();
    await page.goto("/projects/web-platform/settings/general");
    await page.getByRole("checkbox", { name: "템플릿으로 쓰기" }).click();
    await page.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("저장했습니다").first()).toBeVisible();

    await page.goto("/projects/new");
    await page.getByRole("radio", { name: /템플릿에서 시작/ }).click();
    await expect(page.getByRole("combobox", { name: /^템플릿/ })).toContainText("웹 플랫폼 개편");
    await expect(page.getByRole("checkbox", { name: "작업(계층·관계 포함)" })).toBeChecked();
    await page.getByRole("checkbox", { name: "보드" }).click();
    await axe(page, "S04 새 프로젝트 (템플릿)");
    await page.getByLabel(/^프로젝트 이름/).fill("템플릿 프로젝트");
    await page.getByLabel(/^식별자/).fill("from-template");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await page.waitForURL(/\/projects\/from-template/);
    const wps = await api<{ total: number }>(page, "return await api.workPackages.query({ projectId: 'from-template', page: 1, pageSize: 20, spec: { v: 1, filters: [], columns: ['subject'], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false } })");
    expect(wps.total).toBeGreaterThan(5);
    const boards = await api<unknown[]>(page, "return await api.boards.list('from-template')");
    expect(boards).toHaveLength(0);
    await page.goto("/projects/from-template/members");
    await expect(page.getByRole("cell", { name: "박준호" }).first()).toBeVisible();
  });

  test("F10-H3 a subproject: pick the parent when creating it, change it in the settings", async ({ page }) => {
    await page.goto("/projects/new");
    await page.getByLabel(/^프로젝트 이름/).fill("하위 시험");
    await page.getByLabel(/^식별자/).fill("sub-test");
    await page.getByRole("combobox", { name: /^상위 프로젝트/ }).click();
    await page.getByRole("option", { name: "웹 플랫폼 개편" }).click();
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await page.waitForURL(/\/projects\/sub-test/);
    expect(await api<string | null>(page, "return (await api.projects.get('sub-test')).parent?.identifier ?? null")).toBe("web-platform");

    // 웹 플랫폼 cannot go under its own subproject: the settings do not offer it
    await page.goto("/projects/web-platform/settings/general");
    await page.getByRole("combobox", { name: /^상위 프로젝트/ }).click();
    await expect(page.getByRole("option", { name: "하위 시험" })).toHaveCount(0);
    await page.keyboard.press("Escape");

    await page.goto("/projects/sub-test/settings/general");
    await page.getByRole("combobox", { name: /^상위 프로젝트/ }).click();
    await page.getByRole("option", { name: "없음(최상위 프로젝트)" }).click();
    await page.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("저장했습니다").first()).toBeVisible();
    expect(await api<string | null>(page, "return (await api.projects.get('sub-test')).parent?.identifier ?? null")).toBeNull();
  });

  test("F10-H4 copy a project from its settings", async ({ page }) => {
    await page.goto("/projects/web-platform/settings/general");
    await page.getByRole("button", { name: "프로젝트 복사" }).click();
    const dlg = page.getByRole("dialog", { name: "웹 플랫폼 개편 복사" });
    await expect(dlg.getByLabel(/^프로젝트 이름/)).toHaveValue("웹 플랫폼 개편 (사본)");
    await expect(dlg.getByLabel(/^식별자/)).toHaveValue("web-platform-copy");
    await axe(page, "S24 프로젝트 복사");
    await dlg.getByRole("button", { name: "복사해서 만들기" }).click();
    await page.waitForURL(/\/projects\/web-platform-copy$/);
    const wps = await api<{ total: number }>(page, "return await api.workPackages.query({ v: 1, filters: [], columns: ['subject'], sort: [], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }, { projectId: 'web-platform-copy', page: 1, pageSize: 20 })");
    expect(wps.total).toBeGreaterThan(5);
  });

  test("F10-H6 a copy with a new start date moves the schedule in working days", async ({ page }) => {
    await page.goto("/projects/web-platform/settings/general");
    await page.getByRole("button", { name: "프로젝트 복사" }).click();
    const dlg = page.getByRole("dialog", { name: "웹 플랫폼 개편 복사" });
    await dlg.getByLabel(/^식별자/).fill("web-platform-2027");
    await dlg.getByLabel("새 시작일").fill("2027-01-04");
    await dlg.getByRole("button", { name: "복사해서 만들기" }).click();
    await page.waitForURL(/\/projects\/web-platform-2027$/);
    const spec = "{ v: 1, filters: [], columns: ['subject'], sort: [{ field: 'startDate', dir: 'asc' }], groupBy: null, hierarchy: false, sums: false, includeSubprojects: false }";
    const rows = await api<{ rows: { wp: { startDate: string | null; dueDate: string | null } }[] }>(
      page, `return await api.workPackages.query(${spec}, { projectId: 'web-platform-2027', page: 1, pageSize: 100 })`);
    const dates = rows.rows.map((r) => r.wp.startDate ?? r.wp.dueDate).filter((d): d is string => !!d).sort();
    expect(dates[0]).toBe("2027-01-04");
    expect(dates.every((d) => d >= "2027-01-04")).toBe(true);
  });

  test("F10-H5 the overview is a dashboard its managers arrange", async ({ page }) => {
    await page.goto("/projects/web-platform");
    for (const name of ["설명", "작업 현황", "프로젝트 상태", "멤버"]) await expect(page.getByRole("region", { name, exact: true })).toBeVisible();
    await page.getByRole("button", { name: "위젯 편집" }).click();
    const editor = page.getByRole("region", { name: "위젯 편집" });
    await editor.getByRole("checkbox", { name: "버전 진행" }).click();
    await editor.getByRole("checkbox", { name: "멤버" }).click();
    await editor.getByRole("checkbox", { name: "곧 완료일(14일 안)" }).click();
    await expect(page.getByRole("region", { name: "버전 진행" })).toBeVisible();
    await expect(page.getByRole("region", { name: "멤버", exact: true })).toHaveCount(0);
    await axe(page, "S05 개요 위젯 편집");
    await expect(editor.getByText("저장됨")).toBeVisible();
    await page.reload();
    await expect(page.getByRole("region", { name: "곧 완료일(14일 안)" })).toBeVisible();
    await expect(page.getByRole("region", { name: "멤버", exact: true })).toHaveCount(0);
    await logout(page);
    await login(page, "haneul.lee@openpms.local");
    await page.goto("/projects/web-platform");
    await expect(page.getByRole("region", { name: "버전 진행" })).toBeVisible();
    await expect(page.getByRole("button", { name: "위젯 편집" })).toHaveCount(0);
  });

  test("F10-E1 a taken identifier is refused next to the field", async ({ page }) => {
    await page.goto("/projects/new");
    await page.getByLabel(/^프로젝트 이름/).fill("중복 시험");
    await page.getByLabel(/^식별자/).fill("web-platform");
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    await expect(page.getByText(/이미|사용 중/).first()).toBeVisible();
  });

  test("F10-N1 the last project manager cannot be removed", async ({ page }) => {
    await page.goto("/projects/web-platform/members");
    await page.getByRole("button", { name: "김서연 멤버에서 빼기" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "삭제" }).click();
    await expect(page.getByText("마지막 프로젝트 관리자는 뺄 수 없습니다.").first()).toBeVisible();
  });

  test("F10-N2 someone else's private project is invisible (404, not 403)", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "doyoon.han@openpms.local");
    const r = await api<number>(page, "try { await api.projects.get('web-platform'); return 200 } catch (e) { return e.status }");
    expect(r).toBe(404);
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByText(/찾을 수 없|없는 페이지/).first()).toBeVisible();
  });
});
