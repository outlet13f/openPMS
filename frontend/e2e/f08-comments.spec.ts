// F08 코멘트와 @멘션 · F09 알림 처리 (S08 활동 탭 → S19)
import { test, expect, login, logout, resetDb, axe, panel } from "./support";

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
});
