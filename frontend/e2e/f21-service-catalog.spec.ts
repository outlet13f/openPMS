// F21 서비스 카탈로그와 승인(docs/superpowers/specs/2026-10-15-itsm-phase2-design.md): 요청자가 포털 카탈로그에서 항목을 요청하고,
// 요청자의 관리자와 그룹 책임자가 차례로 승인하면 접수되고, 반려하면 사유가 요청자에게 보인다. 남의 승인은 404, 숨긴 항목은 포털에서 사라진다.
// 개발 데이터 V1005: 최민지·한도윤의 관리자 alex.kim, 1선 지원 그룹 책임자 박준호, 항목 노트북 신청(관리자 → 그룹 책임자)·VPN 계정(관리자)·공유 폴더 권한(승인 없음).
import type { Page } from "@playwright/test";
import { test, expect, login, resetDb, axe, panel } from "./support";

test.describe.configure({ mode: "serial" });
test.beforeAll(resetDb);
test.afterAll(resetDb);

const MINJI = "minji.choi@openpms.local";
const DOYOON = "doyoon.han@openpms.local";
const JUNHO = "junho.park@openpms.local";
const ALEX = "alex.kim@openpms.local";
const ADMIN = "seoyeon.kim@openpms.local";
const SUBJECT = "개발용 노트북 신청(F21)";
const VPN_SUBJECT = "재택 근무용 VPN(F21)";
const REASON = "보안 교육 미이수";
const FOLDER = "b187cc86-8e46-5157-981b-2f0c6f66b365";
let ticket = 0;
let approvalPath = "";

/** The status pill in the portal ticket's header (the activity below repeats status names). */
const portalStatus = (page: Page, name: string) => page.getByRole("main").locator("h1").locator("xpath=../..").getByText(name, { exact: true });
/** 승인 진행 table of the requester's ticket. */
const steps = (page: Page) => page.getByRole("table", { name: "승인 단계와 결정" });
/** An item's card in the portal catalog (the 내 요청 list below may carry a ticket of the same name). */
const itemCard = (page: Page, name: string) => page.locator('a[href^="/portal/items/"]').filter({ hasText: name });
/** A row of 내 승인함. */
const inboxRow = (page: Page, subject: string) => page.getByRole("table", { name: "승인할 요청" }).getByRole("row").filter({ hasText: subject });
const inboxLink = (page: Page) => page.getByRole("navigation").getByRole("link", { name: /^내 승인함/ }).first();

async function openInbox(page: Page) {
  await inboxLink(page).click();
  await expect(page).toHaveURL(/\/portal\/approvals$/);
  await expect(page.getByRole("heading", { name: "내 승인함", level: 1 })).toBeVisible();
}

async function request(page: Page, itemName: string, subject: string) {
  await page.goto("/portal");
  await itemCard(page, itemName).click();
  await expect(page.getByRole("heading", { name: `${itemName} 요청` })).toBeVisible();
  await page.getByRole("textbox", { name: /^제목/ }).fill(subject);
}

test.describe("F21 서비스 카탈로그와 승인", () => {
  test("F21-T1 직원이 카탈로그에서 노트북을 신청하고 승인 진행을 본다", async ({ page }) => {
    await login(page, MINJI);
    await request(page, "노트북 신청", SUBJECT);
    await expect(page.getByText("이 요청은 승인을 거쳐 처리됩니다: 관리자 → 그룹 책임자")).toBeVisible();
    // the item's required form field is missing: the request stays on the form
    await page.getByRole("button", { name: "요청", exact: true }).click();
    await expect(page.getByText("값을 입력하세요.")).toBeVisible();
    await expect(page).toHaveURL(/\/portal\/items\//);
    await page.getByRole("combobox", { name: /노트북 기종/ }).click();
    await page.getByRole("option", { name: "16인치 개발용" }).click();
    await page.getByRole("button", { name: "요청", exact: true }).click();
    await expect(page.getByText("요청이 접수되었습니다.", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/portal\/tickets\/\d+$/);
    ticket = Number(new URL(page.url()).pathname.split("/").pop());
    await expect(page.getByRole("heading", { name: `#${ticket} ${SUBJECT}` })).toBeVisible();
    await expect(portalStatus(page, "승인 대기")).toBeVisible();
    const rows = steps(page).getByRole("row");
    await expect(rows.filter({ hasText: "1단계" })).toContainText("Alex Kim");
    await expect(rows.filter({ hasText: "1단계" })).toContainText("승인 대기 중");
    await expect(rows.filter({ hasText: "2단계" })).toContainText("박준호");
    await expect(rows.filter({ hasText: "2단계" })).toContainText("대기");
    await axe(page, "포털 요청 상세(승인 진행)");

    // alex.kim's inbox link, for the next test (the approver reads it from his own screen)
    await page.context().clearCookies();
    await login(page, ALEX);
    await openInbox(page);
    approvalPath = (await inboxRow(page, SUBJECT).getByRole("link", { name: SUBJECT }).getAttribute("href")) ?? "";
    expect(approvalPath).toMatch(/^\/portal\/approvals\/[0-9a-f-]{36}$/);
  });

  test("F21-T2 다른 직원은 그 승인 요청을 열 수 없다", async ({ page }) => {
    expect(approvalPath).not.toBe("");
    await login(page, DOYOON);
    await page.goto(approvalPath);
    await expect(page.getByText("승인 요청을 찾을 수 없습니다.").first()).toBeVisible();
    await expect(page.getByText(SUBJECT)).toHaveCount(0);
  });

  test("F21-T3 관리자 alex.kim이 승인함에서 승인한다", async ({ page }) => {
    await login(page, ALEX);
    await page.goto("/portal");
    // 개발 데이터의 노트북 신청 1건과 이번 요청: 전역 메뉴 배지
    await expect(inboxLink(page)).toContainText("2");
    await openInbox(page);
    await inboxRow(page, SUBJECT).getByRole("link", { name: SUBJECT }).click();
    await expect(page).toHaveURL(new RegExp(`${approvalPath}$`));
    await expect(page.getByRole("heading", { name: `승인 요청 #${ticket} ${SUBJECT}` })).toBeVisible();
    await expect(page.getByText("16인치 개발용")).toBeVisible();
    await axe(page, "승인 상세");
    await page.getByRole("textbox", { name: "의견" }).fill("승인합니다");
    await page.getByRole("button", { name: "승인", exact: true }).click();
    await expect(page.getByText("승인했습니다.", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/portal\/approvals$/);
    await expect(inboxRow(page, SUBJECT)).toHaveCount(0);
    await expect(inboxLink(page)).toContainText("1");
    // 한 번 처리한 승인 상세는 다시 열 수 없다
    await page.goto(approvalPath);
    await expect(page.getByText("승인 요청을 찾을 수 없습니다.").first()).toBeVisible();
  });

  test("F21-T4 그룹 책임자 박준호가 이어서 승인하면 접수된다", async ({ page }) => {
    await login(page, JUNHO);
    await page.goto("/portal/approvals");
    await expect(inboxRow(page, SUBJECT)).toBeVisible();
    await inboxRow(page, SUBJECT).getByRole("link", { name: SUBJECT }).click();
    await page.getByRole("button", { name: "승인", exact: true }).click();
    await expect(page.getByText("승인했습니다.", { exact: true })).toBeVisible();
    await expect(inboxRow(page, SUBJECT)).toHaveCount(0);

    // 처리자 목록에서는 접수, 서비스 탭의 승인 패널에는 두 단계 모두 승인함
    await page.goto("/projects/service-desk/work-packages");
    const row = page.getByRole("row").filter({ hasText: SUBJECT });
    await expect(row).toBeVisible();
    await expect(row.getByText("접수", { exact: true })).toBeVisible();
    await page.goto(`/projects/service-desk/work-packages/details/${ticket}/service`);
    const approvals = panel(page).getByRole("region", { name: "승인" });
    await expect(approvals.getByRole("row").filter({ hasText: "1단계" }).getByText("승인함", { exact: true })).toBeVisible();
    await expect(approvals.getByRole("row").filter({ hasText: "2단계" }).getByText("승인함", { exact: true })).toBeVisible();
    await expect(approvals.getByText("승인합니다")).toBeVisible();
  });

  test("F21-T5 반려하면 요청자가 사유를 본다", async ({ page }) => {
    await login(page, DOYOON);
    await request(page, "VPN 계정", VPN_SUBJECT);
    await page.getByRole("button", { name: "요청", exact: true }).click();
    await expect(page.getByText("요청이 접수되었습니다.", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/portal\/tickets\/\d+$/);
    const vpn = Number(new URL(page.url()).pathname.split("/").pop());
    await expect(portalStatus(page, "승인 대기")).toBeVisible();

    await page.context().clearCookies();
    await login(page, ALEX);
    await openInbox(page);
    await inboxRow(page, VPN_SUBJECT).getByRole("link", { name: VPN_SUBJECT }).click();
    await page.getByRole("button", { name: "반려", exact: true }).click();
    const dialog = page.getByRole("dialog");
    // the reason is required
    await dialog.getByRole("button", { name: "반려", exact: true }).click();
    await expect(dialog.getByText("반려 사유를 입력하세요.")).toBeVisible();
    await dialog.getByRole("textbox", { name: /반려 사유/ }).fill(REASON);
    await dialog.getByRole("button", { name: "반려", exact: true }).click();
    await expect(page.getByText("반려했습니다.", { exact: true })).toBeVisible();
    await expect(inboxRow(page, VPN_SUBJECT)).toHaveCount(0);

    await page.context().clearCookies();
    await login(page, DOYOON);
    await page.goto(`/portal/tickets/${vpn}`);
    await expect(portalStatus(page, "반려")).toBeVisible();
    await expect(page.getByText(REASON)).toBeVisible();
    await expect(steps(page).getByRole("row").filter({ hasText: "1단계" })).toContainText("반려함");
  });

  test("F21-T6 항목을 포털에서 숨기면 요청자 카탈로그에서 사라진다", async ({ page }) => {
    await login(page, MINJI);
    await page.goto("/portal");
    await expect(itemCard(page, "공유 폴더 권한")).toBeVisible();

    await page.context().clearCookies();
    await login(page, ADMIN);
    await page.goto("/projects/service-desk/settings/service-desk");
    await expect(page.getByRole("heading", { name: "카탈로그", level: 2 })).toBeVisible();
    await page.getByRole("button", { name: "공유 폴더 권한 메뉴" }).click();
    await page.getByRole("menuitem", { name: "수정", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("checkbox", { name: "포털에 보임" }).uncheck();
    await dialog.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("카탈로그를 저장했습니다", { exact: true })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: "공유 폴더 권한" }).getByText("숨김", { exact: true })).toBeVisible();
    await axe(page, "카탈로그 설정");

    await page.context().clearCookies();
    await login(page, MINJI);
    await page.goto("/portal");
    await expect(itemCard(page, "노트북 신청")).toBeVisible();
    await expect(itemCard(page, "공유 폴더 권한")).toHaveCount(0);
    // a hidden item's request form is not found either
    await page.goto(`/portal/items/${FOLDER}`);
    await expect(page.getByText("서비스 항목을 찾을 수 없습니다.").first()).toBeVisible();
  });
});
