// F03 필터한 보기를 저장해 팀과 공유 (S06, S11)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F03 보기 저장·공유", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F03-H1 filter by priority, save as a public view, it shows in the sidebar", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: /^필터/ }).first().click();
    const filters = page.getByRole("region", { name: "필터" });
    await filters.getByRole("combobox", { name: "필터 추가" }).click();
    await page.getByRole("option", { name: "우선순위", exact: true }).click();
    await filters.getByRole("button", { name: "우선순위 값" }).click();
    await page.getByRole("checkbox", { name: "긴급" }).click();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("link", { name: "검색 결과가 날짜순으로 정렬되지 않음" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "화면 설계 검토" })).toHaveCount(0);
    await axe(page, "S06 필터 열림");
    await page.getByRole("button", { name: "보기 메뉴" }).click();
    await page.getByRole("menuitem", { name: "다른 이름으로 저장" }).click();
    const dlg = page.getByRole("dialog");
    await dlg.getByLabel(/^보기 이름/).fill("긴급 건");
    await dlg.getByRole("checkbox").first().click();
    await dlg.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText("보기를 저장했습니다").first()).toBeVisible();
    await expect(page.getByRole("navigation", { name: "프로젝트 메뉴" }).getByRole("link", { name: "긴급 건" })).toBeVisible();
  });

  test("F03-E1 a filter with no results says so", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: /^필터/ }).first().click();
    await page.getByPlaceholder("제목이나 #번호로 찾기").fill("존재하지않는제목xyz");
    await expect(page.getByText("조건에 맞는 작업이 없습니다").first()).toBeVisible();
  });

  test("F03-E2 the URL carries the view: a second tab shows the same list", async ({ page, context }) => {
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: /^필터/ }).first().click();
    const filters = page.getByRole("region", { name: "필터" });
    await filters.getByRole("combobox", { name: "필터 추가" }).click();
    await page.getByRole("option", { name: "우선순위", exact: true }).click();
    await filters.getByRole("button", { name: "우선순위 값" }).click();
    const unfiltered = await page.getByText(/총 \d+건/).first().innerText();
    await page.getByRole("checkbox", { name: "높음" }).click();
    await page.keyboard.press("Escape");
    await expect(page).toHaveURL(/[?&]q=/);
    await expect(page.getByText(/총 \d+건/).first()).not.toHaveText(unfiltered);
    const total = await page.getByText(/총 \d+건/).first().innerText();
    const other = await context.newPage();
    await other.goto(page.url());
    await expect(other.getByText(total).first()).toBeVisible();
    await other.close();
  });

  test("F03-N1 a member without public-view rights gets no public checkbox", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "junho.park@openpms.local");
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: "보기 메뉴" }).click();
    await page.getByRole("menuitem", { name: "다른 이름으로 저장" }).click();
    await expect(page.getByRole("dialog").getByLabel(/^보기 이름/)).toBeVisible();
    await expect(page.getByRole("dialog").getByRole("checkbox")).toHaveCount(0);
  });
  test("F03-B1 baseline: what changed since yesterday is marked with the value it had then, new items too", async ({ page }) => {
    const w = await api<{ lockVersion: number }>(page, "return await api.workPackages.get(12)");
    const high = await api<string>(page, "return (await api.workPackages.filterOptions('web-platform')).priorities.find((p) => p.name === '긴급').id");
    await api(page, "await api.workPackages.update(12, { lockVersion: args[0], priorityId: args[1] })", w.lockVersion, high);
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: "기준선" }).click();
    await page.getByRole("menuitemcheckbox", { name: "어제" }).click();
    await expect(page.getByText(/기준선 .+ 이후의 변경을 표시합니다/)).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: "알림 메일 하루 요약" });
    await expect(row.getByText("기준선 때: 보통")).toBeAttached();
    await axe(page, "S06 기준선");
    await page.getByRole("button", { name: "기준선 끄기" }).click();
    await expect(row.getByText("기준선 때: 보통")).toHaveCount(0);
  });

  test("F03-B3 baseline: an item that matched the list then but not now is listed below it, with then → now", async ({ page }) => {
    const opts = await api<{ priorities: { id: string; name: string }[] }>(page, "return await api.workPackages.filterOptions('web-platform')");
    const normal = opts.priorities.find((p) => p.name === "보통")!.id;
    const high = opts.priorities.find((p) => p.name === "긴급")!.id;
    const w = await api<{ lockVersion: number; priority: { id: string } }>(page, "return await api.workPackages.get(12)");
    if (w.priority.id !== high) await api(page, "await api.workPackages.update(12, { lockVersion: args[0], priorityId: args[1] })", w.lockVersion, high);
    const spec = { v: 1, filters: [{ field: "priority", op: "eq", values: [normal] }], columns: ["number", "subject", "status", "priority"], sort: [{ field: "number", dir: "asc" }],
      groupBy: null, hierarchy: false, sums: false, includeSubprojects: false, baseline: { kind: "yesterday" } };
    await page.goto(`/projects/web-platform/work-packages?q=${Buffer.from(JSON.stringify(spec)).toString("base64url")}`);
    const removed = page.getByRole("region", { name: /기준선 이후 목록에서 빠진 작업 \d+건/ });
    await expect(removed).toBeVisible();
    const item = removed.getByRole("listitem").filter({ hasText: "알림 메일 하루 요약" });
    await expect(item.getByText("보통 → 긴급")).toBeVisible();
    await expect(item.getByRole("link", { name: "#12" })).toHaveAttribute("href", "/projects/web-platform/work-packages/12/activity");
    await expect(page.getByRole("row").filter({ hasText: "알림 메일 하루 요약" })).toHaveCount(0);
    await axe(page, "S06 기준선 빠진 작업");
  });
});
