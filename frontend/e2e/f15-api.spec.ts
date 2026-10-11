// 외부 연동 API: a token made in the account settings works once, as its owner and within its scope; administrators see
// everyone's tokens; the API page lists the main routes.
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);

/** A request the way an integration makes it: no cookies, a bearer token. */
async function call(page: import("@playwright/test").Page, method: string, path: string, token: string, body?: unknown) {
  return page.evaluate(
    async ([m, p, tk, b]) => {
      const r = await fetch(p as string, {
        method: m as string,
        credentials: "omit",
        headers: { Authorization: `Bearer ${tk}`, ...(b ? { "Content-Type": "application/json" } : {}) },
        body: b ? JSON.stringify(b) : undefined,
      });
      return r.status;
    },
    [method, path, token, body] as const,
  );
}

test.describe("F15 API 토큰", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F15-H1 make a read-only token, use it, revoke it", async ({ page }) => {
    await page.goto("/my/account");
    await page.getByRole("button", { name: "토큰 만들기" }).click();
    const dlg = page.getByRole("dialog", { name: "토큰 만들기" });
    await dlg.getByLabel(/^토큰 이름/).fill("배포 알림 봇");
    await expect(dlg.getByRole("radio", { name: /읽기 전용/ })).toBeChecked();
    await axe(page, "S37 API 토큰 만들기");
    await dlg.getByRole("button", { name: "만들기" }).click();
    const made = page.getByRole("dialog", { name: "토큰을 만들었습니다" });
    const secret = (await made.getByLabel("토큰 값").textContent())!.trim();
    expect(secret).toMatch(/^opms_/);
    await made.getByRole("button", { name: "확인" }).click();
    await expect(page.getByText("배포 알림 봇")).toBeVisible();

    expect(await call(page, "GET", "/api/v1/work-packages/3", secret)).toBe(200);
    expect(await call(page, "PATCH", "/api/v1/work-packages/3", secret, { lockVersion: 0, subject: "x" })).toBe(403);
    expect(await call(page, "GET", "/api/v1/me/api-tokens", secret)).toBe(403);

    await page.goto("/admin/integrations");
    await expect(page.getByRole("cell", { name: "배포 알림 봇", exact: true })).toBeVisible();
    await axe(page, "관리 · API와 웹훅");

    await page.goto("/my/account");
    await page.getByRole("button", { name: "배포 알림 봇 토큰 폐기" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "폐기" }).click();
    await expect(page.getByText("토큰을 폐기했습니다").first()).toBeVisible();
    expect(await call(page, "GET", "/api/v1/work-packages/3", secret)).toBe(401);
  });

  test("F15-H2 the API page lists the main routes", async ({ page }) => {
    await page.goto("/help/api");
    await expect(page.getByRole("heading", { name: "API 문서", level: 1 })).toBeVisible();
    await expect(page.getByRole("cell", { name: "/api/v1/work-packages/query" })).toBeVisible();
    await axe(page, "API 문서");
  });
  test("F15-H3 a webhook: made with a signing secret shown once, a test send recorded in its deliveries", async ({ page }) => {
    await page.goto("/admin/integrations");
    await page.getByRole("button", { name: "웹훅 추가" }).click();
    const dlg = page.getByRole("dialog", { name: "웹훅 추가" });
    await dlg.getByLabel(/^이름/).fill("배포 알림");
    // the app itself refuses a POST here: a failing receiver, without needing one
    await dlg.getByLabel(/^받는 주소/).fill("http://127.0.0.1:8090/actuator/health");
    await expect(dlg.getByRole("checkbox", { name: /작업 생성/ })).toBeChecked();
    await axe(page, "관리 · 웹훅 추가");
    await dlg.getByRole("button", { name: "저장" }).click();
    const secret = page.getByRole("dialog", { name: "서명 키" });
    await expect(secret.getByLabel("서명 키")).toContainText("whsec_");
    await secret.getByRole("button", { name: "확인" }).click();
    await expect(page.getByText("배포 알림", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "테스트 전송" }).click();
    await expect(page.getByText("테스트 전송이 실패했습니다").first()).toBeVisible();
    const log = page.getByRole("list", { name: "배포 알림 전송 기록" });
    await expect(log.getByText("ping")).toBeVisible();
    await expect(log.getByText("실패")).toBeVisible();
  });
});
