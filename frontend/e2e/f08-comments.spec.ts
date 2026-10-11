// F08 코멘트와 @멘션 · F09 알림 처리 (S08 활동 탭 → S19)
import { test, expect, login, logout, resetDb, axe, panel, api } from "./support";

test.beforeAll(resetDb);

test.describe("F08·F09 코멘트, 멘션, 알림", () => {
  test("F08-H1 comment with a mention; F09-H1 the mentioned person opens it from the inbox", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/3/activity");
    const p = panel(page);
    const box = p.getByLabel("코멘트", { exact: true });
    await box.fill("@박");
    await page.getByRole("option", { name: "박준호" }).click();
    await box.press("End");
    await box.pressSequentially("확인 부탁드립니다 🙏");
    await p.getByRole("button", { name: "코멘트 남기기" }).click();
    await expect(p.getByText("확인 부탁드립니다 🙏")).toBeVisible();
    await logout(page);

    await login(page, "junho.park@openpms.local");
    await page.goto("/notifications");
    await axe(page, "S19 알림");
    const item = page.getByRole("list", { name: "알림" }).getByRole("link").filter({ hasText: "화면 설계 검토" }).first();
    await expect(item).toBeVisible();
    await item.click();
    await expect(page.getByRole("complementary", { name: "상세 패널" }).getByText("확인 부탁드립니다 🙏")).toBeVisible();
  });

  test("F08-E1 an empty comment cannot be sent", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/3/activity");
    await panel(page).getByRole("button", { name: "코멘트 남기기" }).click();
    await expect(panel(page).getByText("코멘트를 입력하세요.")).toBeVisible();
  });

  test("F09-E1 mark all read empties the unread list", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/notifications");
    await expect(page.getByRole("list", { name: "알림" }).getByRole("listitem").first()).toBeVisible();
    await page.getByRole("button", { name: "모두 읽음으로 표시" }).click();
    await expect(page.getByText("새 알림이 없습니다")).toBeVisible();
  });
  test("F08-H2 activity filter and sort; an internal comment only permitted roles read", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    const project = await api<{ id: string }>(page, "return await api.projects.get('web-platform')");
    await api(page, "await api.projects.update(args[0], { internalComments: true })", project.id);
    await page.goto("/projects/web-platform/work-packages/details/3/activity");
    const p = panel(page);
    await p.getByLabel("코멘트", { exact: true }).fill("관리자끼리만 보는 메모");
    await p.getByRole("checkbox", { name: /내부 코멘트/ }).click();
    await p.getByRole("button", { name: "코멘트 남기기" }).click();
    const note = p.getByRole("listitem").filter({ hasText: "관리자끼리만 보는 메모" });
    await expect(note.getByText("내부", { exact: true })).toBeVisible();
    await axe(page, "S07 활동 (내부 코멘트)");

    await p.getByRole("radio", { name: "변경만" }).click();
    await expect(p.getByText("관리자끼리만 보는 메모")).toHaveCount(0);
    await p.getByRole("radio", { name: "코멘트만" }).click();
    await expect(p.getByText("관리자끼리만 보는 메모")).toBeVisible();
    await p.getByRole("radio", { name: "모두" }).click();
    const before = await p.getByRole("listitem").first().textContent();
    await p.getByRole("button", { name: /정렬 바꾸기/ }).click();
    await expect(p.getByRole("button", { name: /지금: 최신 순/ })).toBeVisible();
    await expect.poll(async () => p.getByRole("listitem").first().textContent()).not.toBe(before);
    await logout(page);

    await login(page, "haneul.lee@openpms.local");
    await page.goto("/projects/web-platform/work-packages/details/3/activity");
    await expect(panel(page).getByRole("button", { name: "코멘트 남기기" })).toBeVisible();
    await expect(panel(page).getByText("관리자끼리만 보는 메모")).toHaveCount(0);
    await expect(panel(page).getByRole("checkbox", { name: /내부 코멘트/ })).toHaveCount(0);
  });
});
