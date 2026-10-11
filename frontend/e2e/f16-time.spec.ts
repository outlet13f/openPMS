// F16 시간 기록: 내 공수 주간 입력표(키보드로 입력·저장), 작업의 시간 탭, 프로젝트 공수 보고서와 Excel, 권한 없는 사람
import { test, expect, login, logout, resetDb, axe } from "./support";

test.beforeAll(resetDb);

test.describe("F16 시간 기록", () => {
  test("F16-H1 the weekly sheet: typed with the keyboard, saved with Ctrl+S, a long day flagged", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/my/timesheet?week=2026-10-05");
    await expect(page.getByRole("heading", { name: "내 공수", level: 1 })).toBeVisible();
    const wed = page.getByLabel("#3 화면 설계 검토, 수 2026.10.07");
    await wed.click();
    await wed.fill("2:30");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.type("8");
    await expect(page.getByText("저장하지 않은 변경이 있습니다")).toBeVisible();
    await axe(page, "X10 내 공수");
    await page.keyboard.press("Control+s");
    await expect(page.getByText("이번 주 공수를 저장했습니다").first()).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("#3 화면 설계 검토, 수 2026.10.07")).toHaveValue("2.5");
    await expect(page.getByLabel("#3 화면 설계 검토, 목 2026.10.08")).toHaveValue("8");
    // Thursday now holds 10 hours (#1 2 + #3 8): past the 8-hour day, flagged in words too
    await expect(page.getByText("하루 근무 시간(8시간)을 넘었습니다").first()).toBeAttached();
    // a cell that is not a number keeps the sheet from saving
    const fri = page.getByLabel("#3 화면 설계 검토, 금 2026.10.09");
    await fri.fill("많이");
    await expect(fri).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByRole("button", { name: "저장" })).toBeDisabled();
    await page.getByRole("button", { name: "되돌리기" }).click();
    await expect(fri).toHaveValue("");
  });

  test("F16-H2 a work package's 시간 tab: log time with an activity and a note", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/projects/web-platform/work-packages/12/time");
    await expect(page.getByText("모두 6시간")).toBeVisible();
    await page.getByRole("button", { name: "시간 기록" }).click();
    const dlg = page.getByRole("dialog", { name: "시간 기록" });
    await dlg.getByLabel("날짜").fill("2026-10-09");
    await dlg.getByLabel("시간").fill("1:15");
    await dlg.getByLabel("메모").fill("디자인 QA");
    await axe(page, "S08 시간 기록 대화상자");
    await dlg.getByRole("button", { name: "저장" }).click();
    await expect(dlg).toBeHidden();
    await expect(page.getByText("모두 7.25시간")).toBeVisible();
    await expect(page.getByText("디자인 QA")).toBeVisible();
    await axe(page, "S08 시간 탭");
    // deleting the item would take its 7.25 hours along: the confirmation says so (cancelled here)
    await page.getByRole("button", { name: "더 보기" }).first().click();
    await page.getByRole("menuitem", { name: "삭제" }).click();
    const confirm = page.getByRole("dialog", { name: "작업을 삭제할까요?" });
    await expect(confirm.getByText("이 작업에 기록된 시간 7.25시간도 함께 지워지고 공수 보고서에서 빠집니다.")).toBeVisible();
    await confirm.getByRole("button", { name: "취소" }).click();
  });

  test("F16-H3 the project report: by person and by work package, and the Excel file", async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/projects/web-platform");
    await page.getByRole("link", { name: "공수 보고서" }).click();
    await page.getByLabel("시작").fill("2026-10-05");
    await page.getByLabel("끝").fill("2026-10-11");
    const table = page.getByRole("table");
    // 박준호's week is untouched by the tests above (김서연 logged more in F16-H1·H2)
    await expect(table.getByRole("row", { name: /박준호 15/ })).toBeVisible();
    await expect(table.getByRole("row", { name: /^합계/ })).toBeVisible();
    // a reversed range shows the hint and no table
    await page.getByLabel("끝").fill("2026-10-01");
    await expect(page.getByText("끝 날짜가 시작 날짜보다 빠릅니다")).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
    await page.getByLabel("끝").fill("2026-10-11");
    await page.getByRole("radio", { name: "작업별" }).click();
    await expect(table.getByRole("row", { name: /#4 결제 API 연동 12.5/ })).toBeVisible();
    await axe(page, "X11 공수 보고서");
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Excel로 내려받기" }).click();
    expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);
  });

  test("F16-N1 a viewer has no report and cannot log time", async ({ page }) => {
    await login(page, "minji.choi@openpms.local");
    await page.goto("/projects/web-platform");
    await expect(page.getByRole("link", { name: "개요" })).toBeVisible();
    await expect(page.getByRole("link", { name: "공수 보고서" })).toHaveCount(0);
    await page.goto("/my/timesheet?week=2026-10-05");
    await page.getByRole("searchbox", { name: "작업 추가" }).fill("화면 설계");
    await page.getByRole("button", { name: /#3 화면 설계 검토/ }).click();
    await page.getByLabel("#3 화면 설계 검토, 월 2026.10.05").fill("1");
    await page.getByRole("button", { name: "저장" }).click();
    await expect(page.getByText("이 작업에 시간을 기록할 권한이 없습니다.").first()).toBeVisible();
    await expect(page.getByLabel("#3 화면 설계 검토, 월 2026.10.05")).toHaveAttribute("aria-invalid", "true");
    await logout(page);
  });
});
