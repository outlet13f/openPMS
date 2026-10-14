// F20 서비스 데스크(docs/superpowers/specs/2026-10-14-itsm-design.md): 직원이 포털에서 접수하고, 처리자가 답하고,
// 답변 대기 동안 SLA가 멈추고, 요청자가 답하고 해결을 확인한다. 남의 요청은 보이지 않는다.
// 개발 데이터 V1004: 비공개 프로젝트 service-desk(처리자 박준호·이하늘, 요청자 최민지·한도윤, 관리자 김서연).
import type { Page } from "@playwright/test";
import { test, expect, login, logout, resetDb, axe, panel } from "./support";

test.describe.configure({ mode: "serial" });
test.beforeAll(resetDb);
test.afterAll(resetDb);

const MINJI = "minji.choi@openpms.local";
const DOYOON = "doyoon.han@openpms.local";
const JUNHO = "junho.park@openpms.local";
const ADMIN = "seoyeon.kim@openpms.local";
const SUBJECT = "노트북이 Wi-Fi에 안 붙습니다";
let ticket = 0;

/** The status pill in the portal ticket's header (the activity below repeats status names). */
const portalStatus = (page: Page, name: string) => page.getByRole("main").locator("h1").locator("xpath=../..").getByText(name, { exact: true });
/** My ticket's row in 내 요청. */
const myRow = (page: Page) => page.getByRole("table", { name: "내 요청" }).getByRole("row").filter({ hasText: SUBJECT });

async function setStatus(page: Page, from: string, to: string) {
  const p = panel(page);
  await p.getByRole("button", { name: new RegExp(`^상태: ${from}\\. 상태 바꾸기$`) }).click();
  await page.getByRole("menuitem", { name: to, exact: true }).click();
  await expect(p.getByRole("button", { name: new RegExp(`^상태: ${to}`) })).toBeVisible();
}

test.describe("F20 서비스 데스크와 포털", () => {
  test("F20-T1 직원이 포털에서 장애를 신고한다", async ({ page }) => {
    await login(page, MINJI);
    await page.getByRole("navigation").getByRole("link", { name: "서비스 요청", exact: true }).click();
    await expect(page.getByRole("heading", { name: "무엇을 도와드릴까요?" })).toBeVisible();
    await page.getByRole("link", { name: "IT 서비스 데스크에 장애 신고" }).click();
    await page.getByRole("textbox", { name: /^제목/ }).fill(SUBJECT);
    await page.getByRole("group", { name: "급한 정도" }).getByRole("radio", { name: "지금 일을 못 함" }).check();
    await page.getByRole("button", { name: "접수", exact: true }).click();
    // the toast, not its copy in the screen-reader live region ("알림 요청이 접수되었습니다.")
    await expect(page.getByText("요청이 접수되었습니다.", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(/\/portal\/tickets\/\d+$/);
    ticket = Number(new URL(page.url()).pathname.split("/").pop());
    await expect(page.getByRole("heading", { name: `#${ticket} ${SUBJECT}` })).toBeVisible();
    await expect(portalStatus(page, "접수")).toBeVisible();
    await axe(page, "포털 요청 상세");
  });

  test("F20-T2 다른 직원은 그 요청을 볼 수 없다", async ({ page }) => {
    expect(ticket).toBeGreaterThan(0);
    await login(page, DOYOON);
    await page.goto(`/portal/tickets/${ticket}`);
    await expect(page.getByText("요청을 찾을 수 없습니다.")).toBeVisible();
    await expect(page.getByText(SUBJECT)).toHaveCount(0);
    // not in his own 내 요청 (전체), and the service desk's work package list is not his to open
    await page.goto("/portal");
    await page.getByRole("tab", { name: "전체" }).click();
    await expect(page.getByRole("heading", { name: "내 요청" })).toBeVisible();
    await expect(page.getByText(SUBJECT)).toHaveCount(0);
    await page.goto("/projects/service-desk/work-packages");
    await expect(page.getByRole("main")).toBeVisible();
    await expect(page.getByText(SUBJECT)).toHaveCount(0);
  });

  test("F20-T3 처리자가 SLA를 보고 답한 뒤 답변 대기로 둔다", async ({ page }) => {
    await login(page, JUNHO);
    await page.goto(`/projects/service-desk/work-packages/details/${ticket}/service`);
    const p = panel(page);
    const sla = p.getByRole("region", { name: "SLA" });
    await expect(sla.getByText("첫 응답", { exact: true })).toBeVisible();
    await expect(sla.getByText(/남음$/).first()).toBeVisible();

    await p.getByRole("tab", { name: "활동" }).click();
    await p.getByLabel("코멘트", { exact: true }).fill("공유기 재시작 후 다시 붙어 보시겠어요?");
    await p.getByRole("button", { name: "코멘트 남기기" }).click();
    await expect(p.getByText("공유기 재시작 후 다시 붙어 보시겠어요?")).toBeVisible();
    await setStatus(page, "접수", "답변 대기");

    await p.getByRole("tab", { name: "서비스" }).click();
    await expect(sla.getByText("멈춤(답변 대기)")).toBeVisible();
    await expect(sla.getByText("응답함", { exact: true })).toBeVisible();
  });

  test("F20-T4 요청자에게 새 답변이 보이고, 답하면 다시 처리 중이 된다", async ({ page }) => {
    await login(page, MINJI);
    await page.goto("/portal");
    await expect(myRow(page).getByText("새 답변")).toBeVisible();
    await myRow(page).getByRole("link", { name: SUBJECT }).click();
    await expect(page).toHaveURL(new RegExp(`/portal/tickets/${ticket}$`));
    await expect(page.getByText("공유기 재시작 후 다시 붙어 보시겠어요?")).toBeVisible();
    await expect(portalStatus(page, "답변 대기")).toBeVisible();
    await page.getByRole("textbox", { name: "답변 쓰기" }).fill("재시작해도 안 됩니다");
    await page.getByRole("button", { name: "보내기" }).click();
    await expect(page.getByText("답변을 보냈습니다.", { exact: true })).toBeVisible();
    await expect(portalStatus(page, "처리 중")).toBeVisible();
    await page.goto("/portal");
    await expect(myRow(page)).toBeVisible();
    await expect(myRow(page).getByText("새 답변")).toHaveCount(0);
    await axe(page, "포털 홈");
  });

  test("F20-T5 처리자가 해결하고 요청자가 해결을 확인한다", async ({ page }) => {
    await login(page, JUNHO);
    await page.goto(`/projects/service-desk/work-packages/details/${ticket}/activity`);
    await setStatus(page, "처리 중", "해결");
    await logout(page);

    await login(page, MINJI);
    await page.goto(`/portal/tickets/${ticket}`);
    await expect(portalStatus(page, "해결")).toBeVisible();
    await expect(page.getByRole("button", { name: "다시 열기" })).toBeVisible();
    await page.getByRole("button", { name: "해결 확인" }).click();
    await expect(portalStatus(page, "종료")).toBeVisible();
    await expect(page.getByRole("button", { name: "다시 열기" })).toHaveCount(0);
    await expect(page.getByText("닫힌 요청에는 답변을 쓸 수 없습니다.")).toBeVisible();
  });

  test("F20-T6 관리자가 SLA 정책을 바꾸면 저장되어 남는다", async ({ page }) => {
    await login(page, ADMIN);
    await page.goto("/projects/service-desk/settings/service-desk");
    await expect(page.getByRole("heading", { name: "SLA 정책" })).toBeVisible();
    // the policy rows are Selects: find the 장애 · 보통 row by what its type and priority pickers show
    const policyRow = () =>
      page
        .getByRole("row")
        .filter({ has: page.getByRole("combobox", { name: /번째 정책 유형$/ }).filter({ hasText: "장애" }) })
        .filter({ has: page.getByRole("combobox", { name: /번째 정책 우선순위$/ }).filter({ hasText: "보통" }) });
    await expect(policyRow()).toHaveCount(1);
    const resolve = policyRow().getByRole("spinbutton", { name: /해결\(분\)$/ });
    await expect(resolve).toHaveValue("480");
    await resolve.fill("600");
    await page.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("서비스 데스크 설정을 저장했습니다", { exact: true })).toBeVisible();
    await page.reload();
    await expect(policyRow().getByRole("spinbutton", { name: /해결\(분\)$/ })).toHaveValue("600");
    await axe(page, "서비스 데스크 설정");
    // 바뀐 정책이 새 티켓의 기한에 쓰이는지는 SlaServiceIT·ServiceDeskSettingsIT가 확인한다
  });
});
