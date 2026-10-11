// F18 공동 담당자 (replica/fixes.md 6번): #4 결제 API 연동 is shared by 박준호 (24 h) and 이하늘 (16 h) in the dev data.
// The detail edits the list and hours, 내게 할당됨 finds co-assigned work, the list shows "+1", an assignee board shows
// the card in both people's columns and a move hands one place over, and the team planner has a bar in both rows.
import { test, expect, login, resetDb, axe, api, panel } from "./support";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => resetDb());
test.afterAll(() => resetDb());

test("F18-H1 the detail: everyone with their hours, edited in one dialog; the activity says so", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/projects/web-platform/work-packages/details/4/overview");
  const people = panel(page).getByRole("list", { name: "공동 담당자" });
  await expect(people).toContainText("이하늘");
  await expect(people).toContainText("16시간");
  await expect(people).toContainText("주 담당자 예상 24시간");
  await panel(page).getByRole("button", { name: "공동 담당자 편집" }).click();
  const dialog = page.getByRole("dialog", { name: "담당자와 예상 시간" });
  await expect(dialog.getByRole("textbox", { name: /박준호의 예상 시간/ }).or(dialog.getByRole("spinbutton", { name: /박준호의 예상 시간/ }))).toHaveValue("24");
  await axe(page, "공동 담당자 대화상자");
  await dialog.getByRole("combobox", { name: "담당자로 추가할 사람" }).click();
  await page.getByRole("option", { name: "최민지" }).click();
  await dialog.getByRole("button", { name: "추가", exact: true }).click();
  await dialog.getByRole("spinbutton", { name: /최민지의 예상 시간/ }).fill("4");
  await expect(dialog.getByText("3명 · 예상 합계 44시간")).toBeVisible();
  // 이하늘 becomes the main assignee
  await dialog.getByRole("button", { name: "이하늘을(를) 주 담당자로" }).click();
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect(dialog).toBeHidden();
  await expect.poll(() => api<string>(page, "const w = await api.workPackages.get(4); return w.assignees.map(a => a.user.name + ' ' + a.workHours).join(', ')"))
    .toBe("이하늘 16, 박준호 24, 최민지 4");
  await expect(panel(page).getByRole("button", { name: /^담당자: 이하늘/ })).toBeVisible();
  await page.goto("/projects/web-platform/work-packages/details/4/activity");
  await expect(panel(page).getByText(/담당자 목록/).first()).toBeVisible();
});

test("F18-H2 내게 할당됨 finds work someone shares; the list shows +1 beside the main assignee", async ({ page }) => {
  await login(page, "haneul.lee@openpms.local");
  await page.goto("/projects/web-platform/work-packages?view=default:assigned");
  await expect(page.getByRole("row").filter({ hasText: "결제 API 연동" })).toBeVisible();
  await page.goto("/projects/web-platform/work-packages?view=default:all");
  const row = page.getByRole("row").filter({ hasText: "결제 API 연동" });
  await expect(row.getByLabel(/^외 \d+명: /)).toBeVisible();
});

test("F18-H3 an assignee board: the card in each person's column; moving it from one hands that place over", async ({ page }) => {
  resetDb(); // #4 as in the dev data again (H1 changed its people)
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/projects/web-platform/boards");
  await page.getByRole("link", { name: "담당자별" }).first().click();
  const col = (name: string) => page.getByRole("region", { name });
  await expect(col("박준호").getByRole("link", { name: "결제 API 연동" })).toBeVisible();
  await expect(col("이하늘").getByRole("link", { name: "결제 API 연동" })).toBeVisible();
  await expect(col("박준호").getByRole("img", { name: /담당자: .*이하늘/ })).toBeVisible();
  // keyboard: lift the card in 이하늘's column, one column to the right (Alex Kim), drop
  const grip = col("이하늘").getByRole("button", { name: "#4 결제 API 연동 옮기기" });
  await grip.focus();
  await page.keyboard.press("Space");
  await page.waitForTimeout(200);
  await page.keyboard.press("ArrowRight");
  await page.waitForTimeout(300);
  await page.keyboard.press("Space");
  await expect(col("Alex Kim").getByRole("link", { name: "결제 API 연동" })).toBeVisible();
  await expect(col("이하늘").getByRole("link", { name: "결제 API 연동" })).toHaveCount(0);
  await expect(col("박준호").getByRole("link", { name: "결제 API 연동" })).toBeVisible();
  await expect.poll(() => api<string>(page, "const w = await api.workPackages.get(4); return w.assignees.map(a => a.user.name).join(', ')")).toBe("박준호, Alex Kim");
});

test("F18-H4 the team planner draws a shared item in both people's rows", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  // #4 back to 박준호 + 이하늘 for this check (the board test above handed 이하늘's place to Alex Kim)
  // and on this week's days, so the planner's first week shows it whatever today is
  await api(page, "const w = await api.workPackages.get(4); const d = new Date(); const iso = (x) => x.toISOString().slice(0, 10); const s = iso(d); d.setDate(d.getDate() + 1); await api.workPackages.update(4, { lockVersion: w.lockVersion, startDate: s, dueDate: iso(d), assignees: [{ userId: w.assignees[0].user.id, workHours: 24 }, { userId: 'f0712dee-a7e4-5b9a-a9c5-7d3682ce763a', workHours: 16 }] });");
  await page.goto("/projects/web-platform/team-planner");
  await page.getByRole("link", { name: "웹팀 주간 계획" }).first().click();
  await expect(page.getByRole("button", { name: /^#4 결제 API 연동, 박준호, / })).toBeVisible();
  await expect(page.getByRole("button", { name: /^#4 결제 API 연동, 이하늘, / })).toBeVisible();
});
