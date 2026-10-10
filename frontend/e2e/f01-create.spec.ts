// F01 작업 패키지 만들기 (S06 → S09 → S07)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F01 작업 패키지 만들기", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F01-H1 split form: required subject, then the item opens in the panel", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await axe(page, "S06 목록");
    await page.getByRole("button", { name: "만들기" }).first().click();
    await page.getByRole("menuitem", { name: "작업" }).click();
    await page.waitForURL(/\/work-packages\/new/);
    await page.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText("제목을 입력하세요.")).toBeVisible();
    await expect(page.getByRole("textbox", { name: "제목" })).toBeFocused();
    await axe(page, "S09 새 작업");
    await page.getByRole("textbox", { name: "제목" }).fill("E2E로 만든 작업");
    await page.getByRole("button", { name: "저장" }).click();
    await page.waitForURL(/\/details\/\d+\/overview/);
    await expect(page.getByRole("link", { name: "E2E로 만든 작업" }).first()).toBeVisible();
    await axe(page, "S07 상세 패널");
  });

  test("F01-H2 inline row: type and Enter", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages");
    await page.getByRole("button", { name: "새 작업 추가" }).click();
    const input = page.getByPlaceholder("제목을 입력하고 Enter");
    await input.fill("인라인 E2E");
    await input.press("Enter");
    await expect(page.getByRole("link", { name: "인라인 E2E" }).first()).toBeVisible();
  });

  test("F01-E1 emoji, accents and a 255-character subject are kept", async ({ page }) => {
    const subject = ("🚀 Ünïcödé 한글 " + "가".repeat(300)).slice(0, 255);
    await page.goto("/projects/web-platform/work-packages/new");
    await page.getByRole("textbox", { name: "제목" }).fill(subject + "넘침");
    await page.getByRole("button", { name: "저장" }).click();
    await page.waitForURL(/\/details\/(\d+)\/overview/);
    const n = Number(/details\/(\d+)/.exec(page.url())![1]);
    const saved = await api<string>(page, "return (await api.workPackages.get(args[0])).subject", n);
    expect(saved).toBe(subject);
  });

  test("F01-E2 double click on 저장 makes one item", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages/new");
    await page.getByRole("textbox", { name: "제목" }).fill("두 번 눌러도 하나");
    await page.getByRole("button", { name: "저장" }).dblclick();
    await page.waitForURL(/\/details\/\d+\/overview/);
    const count = await api<number>(
      page,
      "const views = await api.queries.list(); const spec = views.find((v) => v.id === 'default:all' || v.id === 'default:open').spec; return (await api.workPackages.query({ ...spec, filters: [], text: '두 번 눌러도 하나' }, { pageSize: 50 })).total",
    );
    expect(count).toBe(1);
  });

  test("F01-E3 a type the project does not use is refused", async ({ page }) => {
    // 모바일 앱 does not use 기능
    const r = await api<string>(
      page,
      "const f = await api.workPackages.form('web-platform'); const feature = f.types.find((t) => t.name === '기능'); try { await api.workPackages.create('mobile-app', { typeId: feature.id, subject: 'x' }); return 'ok' } catch (e) { return e.status + ' ' + e.fieldErrors[0]?.field }",
    );
    expect(r).toBe("422 type");
  });

  test("F01-N1 a viewer sees no create button and the API says no", async ({ page }) => {
    await page.evaluate(async () => (await import("/src/api/index.ts" as string)).api.auth.logout());
    await login(page, "minji.choi@openpms.local");
    await page.goto("/projects/web-platform/work-packages");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByRole("button", { name: "만들기" })).toHaveCount(0);
    const r = await api<number>(
      page,
      "const views = await api.queries.list(); try { await api.workPackages.create('web-platform', { typeId: 'x', subject: 'x' }); return 0 } catch (e) { return e.status }",
    );
    expect(r).toBe(403);
  });
});
