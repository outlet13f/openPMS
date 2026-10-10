// F09 알림 처리: 이유별·프로젝트별 필터 (S19)
import { test, expect, login, logout, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

const cards = (page: import("@playwright/test").Page) => page.getByRole("list", { name: "알림" }).getByRole("link");

test.describe("F09 알림 필터", () => {
  test("F09-H2 filter by reason and by project; with a filter, mark all read reads only what is shown", async ({ page }) => {
    // someone assigns seoyeon an item in another project: a notification there too
    await login(page, "junho.park@openpms.local");
    await api(
      page,
      "const me = (await api.members.list('mobile-app')).find((m) => m.principal.name === '김서연'); const w = await api.workPackages.get(16); await api.workPackages.update(16, { lockVersion: w.lockVersion, assigneeId: me.principal.id })",
    );
    await logout(page);

    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/notifications");
    await expect(cards(page)).toHaveCount(3); // seed: mention on #3, assigned #5, and #16 now
    await page.getByRole("combobox", { name: "프로젝트로 거르기" }).click();
    await page.getByRole("option", { name: "모바일 앱 2.0 (1)" }).click();
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText("#16");
    await expect(page).toHaveURL(/project=mobile-app/);

    await page.getByRole("button", { name: "필터 지우기" }).click();
    await page.getByRole("combobox", { name: "이유로 거르기" }).click();
    await expect(page.getByRole("option", { name: "담당 (2)" })).toBeVisible();
    await page.getByRole("option", { name: "언급 (1)" }).click();
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText("화면 설계 검토");
    await axe(page, "S19 알림 (필터)");

    // the filter survives opening an item and closing it again
    await page.goto("/notifications?reason=assigned");
    await expect(cards(page)).toHaveCount(2);
    await cards(page).filter({ hasText: "#16" }).click();
    await expect(page).toHaveURL(/\/notifications\/details\/16\/activity\?reason=assigned/);

    // 모두 읽음 under 담당: the mention stays unread
    await page.goto("/notifications?reason=assigned");
    await page.getByRole("button", { name: "모두 읽음으로 표시" }).click();
    await expect(page.getByText("조건에 맞는 알림이 없습니다")).toBeVisible();
    await page.goto("/notifications");
    await expect(cards(page)).toHaveCount(1);
    await expect(cards(page).first()).toContainText("화면 설계 검토");
  });
});
