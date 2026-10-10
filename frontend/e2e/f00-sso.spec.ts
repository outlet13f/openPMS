// F00 사내 SSO(OpenID Connect): 관리자가 공급자를 등록하고, 로그인 화면의 버튼으로 공급자를 거쳐 들어온다.
// Needs the test provider (e2e/fake-idp.mjs, see its header); skipped when it is not running.
// The backend's callback address follows OPENPMS_BASE_URL (dev: port 5173); cookies are per host, not per port,
// so the session started there is the session of this suite's 5174 server too.
import { test, expect, login, logout, resetDb, axe } from "./support";

const IDP_BROWSER = "http://localhost:18081";
const IDP_ISSUER = "http://openpms-idp:18080";

let idpUp = false;
test.beforeAll(async () => {
  resetDb();
  idpUp = await fetch(`${IDP_BROWSER}/jwks`).then((r) => r.ok, () => false);
});
test.beforeEach(() => test.skip(!idpUp, "test identity provider (e2e/fake-idp.mjs) is not running"));

/** From the login button through the provider's page and back; returns the path openPMS sent the browser to. */
async function viaProvider(page: import("@playwright/test").Page, who: string) {
  await page.goto("/login");
  await page.getByRole("link", { name: "사내 계정(으)로 로그인" }).click();
  await page.waitForURL(`${IDP_BROWSER}/authorize**`);
  await page.getByRole("button", { name: who, exact: true }).click();
  await page.waitForURL((u) => u.port !== "18081" && !u.pathname.startsWith("/api/"));
  const url = new URL(page.url());
  return url.pathname + url.search;
}

test.describe.serial("F00 SSO", () => {
  test("F00-S1 an administrator registers a provider", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/admin/authentication");
    const section = page.getByRole("region", { name: "사내 SSO (OpenID Connect)" });
    await expect(section.getByText("등록된 SSO 공급자가 없습니다")).toBeVisible();
    await section.getByRole("button", { name: "SSO 공급자 추가" }).click();
    const dlg = page.getByRole("dialog", { name: "SSO 공급자 추가" });
    await dlg.getByLabel(/^버튼 이름/).fill("사내 계정");
    await dlg.getByLabel(/^식별자/).fill("company");
    await dlg.getByLabel(/^발급자/).fill("http://127.0.0.1:9/not-there");
    await dlg.getByLabel(/^클라이언트 ID/).fill("openpms");
    await dlg.getByLabel(/^클라이언트 시크릿/).fill("dev-only-secret");
    await axe(page, "SSO 공급자 대화상자");
    await dlg.getByRole("button", { name: "저장" }).click();
    // an issuer without a discovery document is refused next to the field
    await expect(dlg.getByText(/발견 문서.*읽을 수 없습니다/)).toBeVisible();
    await dlg.getByLabel(/^발급자/).fill(IDP_ISSUER);
    await dlg.getByRole("button", { name: "저장" }).click();
    await expect(dlg).toBeHidden();
    await expect(section.getByRole("cell", { name: /^사내 계정/ }).first()).toBeVisible();
    await expect(section.getByText(/\/api\/v1\/auth\/oidc\/callback\/company$/)).toBeVisible();
    await axe(page, "관리 로그인·비밀번호 (SSO 공급자 있음)");
  });

  test("F00-S2 an existing account signs in through the provider", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("link", { name: "사내 계정(으)로 로그인" })).toBeVisible();
    await axe(page, "S01 로그인 (SSO 버튼)");
    expect(await viaProvider(page, "haneul")).toBe("/");
    await page.goto("/");
    await expect(page.getByRole("main").getByText(/이하늘/).first()).toBeVisible();
    await logout(page);
  });

  test("F00-S3 no account, or cancelled at the provider: back at the login page with the reason", async ({ page }) => {
    expect(await viaProvider(page, "stranger")).toBe("/login?sso_error=no_account");
    await page.goto("/login?sso_error=no_account");
    await expect(page.getByText("openPMS 계정이 없습니다. 관리자에게 초대를 요청하세요.")).toBeVisible();
    expect(await viaProvider(page, "deny")).toBe("/login?sso_error=cancelled");
    await page.goto("/login?sso_error=cancelled");
    await expect(page.getByText("로그인을 취소했거나 공급자가 허용하지 않았습니다.")).toBeVisible();
  });

  test("F00-S4 accounts created on first sign-in, when the provider allows it", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/admin/authentication");
    const section = page.getByRole("region", { name: "사내 SSO (OpenID Connect)" });
    await section.getByRole("button", { name: "사내 계정 메뉴" }).click();
    await page.getByRole("menuitem", { name: "수정" }).click();
    const dlg = page.getByRole("dialog", { name: "SSO 공급자 수정" });
    await expect(dlg.getByLabel(/^클라이언트 시크릿/)).toHaveAttribute("placeholder", "비워 두면 저장된 값을 그대로 씁니다");
    await dlg.getByRole("checkbox", { name: /계정이 없으면/ }).click();
    await dlg.getByRole("button", { name: "저장" }).click();
    await expect(dlg).toBeHidden();
    await logout(page);
    expect(await viaProvider(page, "newbie")).toBe("/");
    await page.goto("/my/account");
    await expect(page.getByLabel(/^이름/)).toHaveValue("정다은");
  });
});
