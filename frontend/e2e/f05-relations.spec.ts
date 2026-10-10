// F05 선후행 관계 걸기 · F07 계층(WBS) 만들기 (S08 관계 탭)
import { test, expect, login, resetDb, axe, api } from "./support";

test.beforeAll(resetDb);

test.describe("F05·F07 관계와 계층", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));

  test("F05-H1 add a predecessor from the relations tab", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages/details/12/relations");
    await page.getByRole("button", { name: "관계 추가" }).click();
    await page.getByRole("combobox", { name: "관계 추가" }).click();
    await page.getByRole("option", { name: "선행 작업" }).click();
    await page.getByRole("combobox", { name: "연결할 작업 (#번호나 제목)" }).fill("배포 자동화");
    await page.getByRole("option").filter({ hasText: "배포 자동화 스크립트 정리" }).getByRole("button").click();
    await page.getByRole("button", { name: "관계 추가" }).last().click();
    await expect(page.getByText("관계를 추가했습니다").first()).toBeVisible();
    await axe(page, "S08 관계 탭");
    const groups = await api<Record<string, { number: number }[]>>(page, "return await api.workPackages.relations(12)");
    expect(JSON.stringify(groups)).toContain('"number":13');
  });

  test("F05-E1 a cycle is refused", async ({ page }) => {
    await api(page, "await api.workPackages.addRelation(11, { type: 'precedes', toNumber: 15 })");
    const r = await api<string>(page, "try { await api.workPackages.addRelation(15, { type: 'precedes', toNumber: 11 }); return 'ok' } catch (e) { return e.status + ' ' + e.title }");
    expect(r).toMatch(/^(409|422) /);
  });

  test("F05-E2 a relation to itself is refused", async ({ page }) => {
    const r = await api<string>(page, "try { await api.workPackages.addRelation(11, { type: 'relates', toNumber: 11 }); return 'ok' } catch (e) { return String(e.status) }");
    expect(r).toBe("422");
  });

  test("F07-H1 link an existing item as a child; the new-child link prefills the parent", async ({ page }) => {
    await page.goto("/projects/web-platform/work-packages/details/12/relations");
    await page.getByRole("button", { name: "관계 추가" }).click();
    await page.getByRole("combobox", { name: "관계 추가" }).click();
    await page.getByRole("option", { name: "기존 작업을 하위로 연결" }).click();
    await page.getByRole("combobox", { name: "연결할 작업 (#번호나 제목)" }).fill("다크 모드");
    await page.getByRole("option").filter({ hasText: "다크 모드 지원" }).getByRole("button").click();
    await page.getByRole("button", { name: "관계 추가" }).last().click();
    await expect(page.getByText("관계를 추가했습니다").first()).toBeVisible();
    const parent = await api<number | null>(page, "return (await api.workPackages.get(15)).parent?.number ?? null");
    expect(parent).toBe(12);
    await page.getByRole("link", { name: "새 하위 작업 만들기" }).click();
    await expect(page).toHaveURL(/\/work-packages\/new\?.*parent=12/);
    await expect(page.getByLabel("상위 작업")).toHaveValue("#12");
  });

  test("F07-E1 an item cannot become its own descendant's child", async ({ page }) => {
    // #8 is a child of #7: making #7 a child of #8 would be a loop
    const r = await api<string>(page, "const w = await api.workPackages.get(7); try { await api.workPackages.update(7, { lockVersion: w.lockVersion, parentNumber: 8 }); return 'ok' } catch (e) { return String(e.status) }");
    expect(r).toBe("422");
  });
});
