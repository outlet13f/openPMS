// F19 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): an administrator makes an account without mail,
// passes the password on as a ready message, looks it up again until it is changed, and the person sets their own at the first sign-in.
import { test, expect, login, logout, resetDb, axe, api } from "./support";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => resetDb());
test.afterAll(() => resetDb());

const OWN = "my-own-pass-2026!";

test("F19-T1 an account made with a temporary password; the first sign-in asks for a password of one's own", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/admin/users");
  await page.getByRole("button", { name: "사용자 추가" }).click();
  const add = page.getByRole("dialog", { name: "사용자 추가" });
  await add.getByRole("radio", { name: "임시 비밀번호 만들기" }).click();
  await add.getByLabel("이름").fill("임시 확인");
  await add.getByLabel("이메일").fill("temp.e2e@openpms.local");
  await axe(page, "S35 사용자 추가");
  await add.getByRole("button", { name: "만들기" }).click();

  const shown = page.getByRole("dialog", { name: "임시 확인님의 임시 비밀번호" });
  const password = await shown.getByLabel("임시 비밀번호").inputValue();
  expect(password).toHaveLength(16);
  await axe(page, "S35 임시 비밀번호");
  await shown.getByRole("button", { name: "전달용 문구 복사" }).click();
  await expect(shown.getByText("전달용 문구를 복사했습니다")).toBeVisible();
  const message = await page.evaluate(() => navigator.clipboard.readText());
  expect(message).toContain("이메일: temp.e2e@openpms.local");
  expect(message).toContain(`임시 비밀번호: ${password}`);
  expect(message).toContain("/login");
  await shown.getByRole("button", { name: "확인" }).click();
  const row = page.getByRole("row").filter({ hasText: "temp.e2e@openpms.local" });
  await expect(row.getByText(/임시 비밀번호 · .+까지/)).toBeVisible();

  await logout(page);
  await page.goto("/login");
  await page.getByLabel("이메일").fill("temp.e2e@openpms.local");
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByRole("heading", { name: "새 비밀번호 정하기" })).toBeVisible();
  await axe(page, "S01 새 비밀번호 정하기");
  // the label text carries the required mark ("새 비밀번호*"), so an exact string does not match; the regex keeps it apart from "새 비밀번호 확인"
  await page.getByLabel(/^새 비밀번호\*?$/).fill(password);
  await page.getByLabel("새 비밀번호 확인").fill(password);
  await page.getByRole("button", { name: "비밀번호 저장" }).click();
  await expect(page.getByText("임시 비밀번호와 다른 비밀번호를 정하세요.")).toBeVisible();
  await page.getByLabel(/^새 비밀번호\*?$/).fill(OWN);
  await page.getByLabel("새 비밀번호 확인").fill(OWN);
  await page.getByRole("button", { name: "비밀번호 저장" }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"));

  // the temporary password is spent; the new one works
  await logout(page);
  const refused = await api<number>(page, "try { await api.auth.login(args[0], args[1]); return 200 } catch (e) { return e.status }", "temp.e2e@openpms.local", password);
  expect(refused).toBe(401);
  await login(page, "temp.e2e@openpms.local", OWN);
});

test("F19-T2 someone who never took up the invitation gets one; the administrator looks it up until it is changed", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  await api(page, "await api.admin.users.create({ name: '초대 대기', email: 'waiting.e2e@openpms.local', isAdmin: false })");
  await page.goto("/admin/users");
  const row = page.getByRole("row").filter({ hasText: "waiting.e2e@openpms.local" });
  await expect(row.getByText("초대됨", { exact: true })).toBeVisible();
  await row.getByRole("button", { name: /메뉴$/ }).click();
  await page.getByRole("menuitem", { name: "임시 비밀번호 발급" }).click();
  await page.getByRole("dialog", { name: "임시 비밀번호를 발급할까요?" }).getByRole("button", { name: "발급" }).click();
  const shown = page.getByRole("dialog", { name: "초대 대기님의 임시 비밀번호" });
  const password = await shown.getByLabel("임시 비밀번호").inputValue();
  await shown.getByRole("button", { name: "확인" }).click();
  await expect(row.getByText("활성", { exact: true })).toBeVisible();

  await row.getByRole("button", { name: /메뉴$/ }).click();
  await page.getByRole("menuitem", { name: "임시 비밀번호 보기" }).click();
  const again = page.getByRole("dialog", { name: "초대 대기님의 임시 비밀번호" });
  await expect(again.getByLabel("임시 비밀번호")).toHaveValue(password);
  await expect(again.getByText(/김서연님이 .+에 발급/)).toBeVisible();
  await again.getByRole("button", { name: "확인" }).click();

  // the person sets their own (through the API this time): the look-up is gone, issuing stays
  await logout(page);
  const token = await api<string>(page, "try { await api.auth.login(args[0], args[1]) } catch (e) { return e.current.changeToken }", "waiting.e2e@openpms.local", password);
  await api(page, "await api.auth.changeTemporaryPassword(args[0], args[1])", token, OWN);
  await logout(page);
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/admin/users");
  await row.getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 보기" })).toHaveCount(0);
});

test("F19-T3 a user manager who is not an administrator cannot handle an administrator's password", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  const users = await api<{ id: string; email: string }[]>(page, "return await api.admin.users.list()");
  const doyoon = users.find((u) => u.email === "doyoon.han@openpms.local")!.id;
  const seoyeon = users.find((u) => u.email === "seoyeon.kim@openpms.local")!.id;
  const role = await api<{ id: string }>(page, "return await api.admin.roles.create({ name: '사용자 관리자(F19)', scope: 'global', permissions: ['global.manage_users'] })");
  await api(page, "await api.admin.users.setGlobalRoles(args[0], [args[1]])", doyoon, role.id);
  await logout(page);
  await login(page, "doyoon.han@openpms.local");
  await page.goto("/admin/users");
  await page.getByRole("row").filter({ hasText: "seoyeon.kim@openpms.local" }).getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem").first()).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.getByRole("row").filter({ hasText: "haneul.lee@openpms.local" }).getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toBeVisible();
  await page.keyboard.press("Escape");
  const refused = await api<number>(page, "try { await api.admin.users.issueTemporaryPassword(args[0]); return 200 } catch (e) { return e.status }", seoyeon);
  expect(refused).toBe(403);
});
