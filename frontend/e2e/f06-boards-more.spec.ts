// F06 보드 보강: 빈(수동) 보드, 열에서 바로 만들기(그 열의 값), 보드 필터 (S15, S16)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F06 보드 보강", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F06-B1 a basic board: own lists, put work packages in, order them, take them off", async ({ page }) => {
    await page.goto("/projects/web-platform/boards/new");
    await page.getByLabel(/^보드 이름/).fill("이번 주");
    await page.getByRole("radio", { name: /빈 보드/ }).click();
    await page.getByRole("button", { name: "만들기", exact: true }).click();
    const todo = page.getByRole("region", { name: "할 일" });
    await expect(todo).toBeVisible();
    await page.getByRole("button", { name: "열 추가" }).click();
    await page.getByRole("dialog").getByLabel(/^열 이름/).fill("진행");
    await page.getByRole("dialog").getByRole("button", { name: "저장" }).click();
    await expect(page.getByRole("region", { name: "진행" })).toBeVisible();

    await todo.getByRole("button", { name: "할 일에 작업 추가" }).click();
    await page.getByRole("menuitem", { name: "기존 작업 넣기" }).click();
    const dlg = page.getByRole("dialog", { name: "'할 일'에 작업 넣기" });
    await dlg.getByLabel("제목이나 #번호로 찾기").fill("화면 설계");
    await dlg.getByRole("button", { name: /화면 설계 검토 넣기/ }).click();
    await expect(dlg.getByText("보드에 있음")).toBeVisible();
    await axe(page, "S15 기존 작업 넣기");
    await dlg.getByRole("button", { name: "닫기" }).last().click();
    await expect(todo.getByRole("link", { name: "화면 설계 검토" })).toBeVisible();
    await axe(page, "S15 빈 보드");

    // off the board: the work package itself is unchanged
    const before = await api<string>(page, "return (await api.workPackages.get(3)).status.name");
    await todo.getByRole("button", { name: "#3 보드에서 빼기" }).click();
    await expect(todo.getByRole("link", { name: "화면 설계 검토" })).toHaveCount(0);
    expect(await api<string>(page, "return (await api.workPackages.get(3)).status.name")).toBe(before);
  });

  test("F06-B2 + in a status column makes a card with that status", async ({ page }) => {
    await page.goto("/projects/web-platform/boards");
    await page.getByRole("link", { name: "진행 현황" }).first().click();
    const doing = page.getByRole("region", { name: "진행 중" });
    await doing.getByRole("button", { name: "진행 중에 작업 추가" }).click();
    await doing.getByRole("textbox", { name: "진행 중에 새 작업" }).fill("열에서 바로 만든 작업");
    await page.keyboard.press("Enter");
    await expect(page.getByText(/을\(를\) 만들었습니다/).first()).toBeVisible();
    await expect(doing.getByRole("link", { name: "열에서 바로 만든 작업" })).toBeVisible();
    // empty title: refused in place, nothing made
    await page.keyboard.press("Enter");
    await expect(doing.getByText("제목을 입력하세요").first()).toBeVisible();
  });

  test("F06-B3 board filters: look through them, save them for everyone", async ({ page }) => {
    await page.goto("/projects/web-platform/boards");
    await page.getByRole("link", { name: "진행 현황" }).first().click();
    await expect(page.getByRole("region", { name: "신규" })).toBeVisible();
    await page.getByRole("button", { name: /^필터/ }).click();
    await page.getByPlaceholder("제목이나 #번호로 찾기").fill("릴리스");
    await expect(page.getByText("저장하지 않은 필터로 보고 있습니다")).toBeVisible();
    await expect(page.getByRole("link", { name: "화면 설계 검토" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "릴리스 노트 초안 작성" })).toBeVisible();
    await axe(page, "S15 보드 필터");
    await page.getByRole("button", { name: "필터 저장" }).click();
    await expect(page.getByText("보드 필터를 저장했습니다").first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole("button", { name: /^필터 1/ })).toBeVisible();
    await expect(page.getByRole("link", { name: "화면 설계 검토" })).toHaveCount(0);
  });
});
