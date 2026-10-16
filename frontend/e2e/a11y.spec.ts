// An axe scan (WCAG 2.1 A/AA) of every screen the flow specs do not already scan, signed in as an administrator.
import { test, expect, login, resetDb, axe } from "./support";

test.beforeAll(resetDb);

const SCREENS: [string, string, RegExp | string][] = [
  ["S02 홈", "/", /안녕하세요/],
  ["S05 프로젝트 개요", "/projects/web-platform", "웹 플랫폼 개편"],
  ["S08 전체 화면 상세", "/projects/web-platform/work-packages/3/activity", "화면 설계 검토"],
  ["S17 캘린더 목록", "/projects/web-platform/calendar", /캘린더/],
  ["S18 팀 플래너 목록", "/projects/web-platform/team-planner", /플래너/],
  ["S20 알림 설정", "/my/notifications", /알림/],
  ["S21 내 페이지", "/my/page", /내 페이지/],
  ["S22 검색", "/search?q=결제", /결제/],
  ["S24 프로젝트 설정", "/projects/web-platform/settings/general", /정보/],
  ["S25 작업 설정", "/projects/web-platform/settings/work-packages", /사용할 유형/],
  ["S26 버전", "/projects/web-platform/settings/versions", /버전/],
  ["S28 양식 구성", "/admin/types", /유형/],
  ["S31 역할", "/admin/roles", /역할/],
  ["S32 진행률", "/admin/progress", /진행률/],
  ["S33 달력과 날짜", "/admin/calendar", /근무/],
  ["S34 사용자 정의 필드", "/admin/custom-fields", /사용자 정의 필드/],
  ["S35 사용자", "/admin/users", /사용자/],
  ["S36 우선순위", "/admin/priorities", /우선순위/],
  ["S37 계정", "/my/account", /계정/],
  ["관리 로그인·비밀번호", "/admin/authentication", /비밀번호/],
  ["전역 Gantt", "/gantt", /Gantt/],
  ["관리 CI/CD 연동", "/admin/cicd", "사내 GitLab(개발용)"],
  ["프로젝트 설정 저장소", "/projects/web-platform/settings/repositories", "web/portal"],
  ["프로젝트 설정 환경", "/projects/web-platform/settings/environments", "개발"],
  ["개발 탭", "/projects/web-platform/work-packages/details/4/dev", "결제 API 클라이언트 추가 (#4)"],
];

test.describe("axe on every screen", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));
  for (const [name, url, marker] of SCREENS) {
    test(`a11y ${name}`, async ({ page }) => {
      await page.goto(url);
      await expect(page.getByRole("main").getByText(marker).first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      await axe(page, name);
    });
  }
});

test.describe("axe on the planning views", () => {
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));
  for (const [name, list, view] of [
    ["S17 캘린더", "/projects/web-platform/calendar", "팀 일정"],
    ["S18 팀 플래너", "/projects/web-platform/team-planner", "웹팀 주간 계획"],
  ] as const) {
    test(`a11y ${name}`, async ({ page }) => {
      await page.goto(list);
      await page.getByRole("link", { name: view }).first().click();
      await expect(page.getByRole("heading", { name: view }).first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      await axe(page, name);
    });
  }
});

test.describe("axe in the dark theme", () => {
  test.use({ colorScheme: "dark" });
  test.beforeEach(async ({ page }) => login(page, "seoyeon.kim@openpms.local"));
  for (const [name, url] of [
    ["S06 목록 (dark)", "/projects/web-platform/work-packages"],
    ["S07 상세 패널 (dark)", "/projects/web-platform/work-packages/details/3/overview"],
    ["S13 Gantt (dark)", "/projects/web-platform/gantt"],
    ["S19 알림 (dark)", "/notifications"],
    ["S02 홈 (dark)", "/"],
    ["S35 사용자 (dark)", "/admin/users"],
    ["개발 탭 (dark)", "/projects/web-platform/work-packages/details/4/dev"],
    ["관리 CI/CD 연동 (dark)", "/admin/cicd"],
    ["프로젝트 설정 환경 (dark)", "/projects/web-platform/settings/environments"],
  ] as const) {
    test(`a11y ${name}`, async ({ page }) => {
      await page.goto(url);
      // the system theme follows prefers-color-scheme: the page background must really be dark
      const bg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--pms-color-bg").trim().toLowerCase());
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(bg.slice(i, i + 2), 16));
      expect(r + g + b, `page background ${bg}`).toBeLessThan(200);
      await page.waitForLoadState("networkidle");
      await axe(page, name);
    });
  }
});

test.describe("axe in the high-contrast theme", () => {
  // the system is dark here: high contrast is a light theme chosen in the account and must win over it
  test.use({ colorScheme: "dark" });
  test.beforeEach(async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
    await page.goto("/my/account");
    await page.getByRole("radio", { name: /고대비/ }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "high_contrast");
  });
  test.afterEach(async ({ page }) => {
    await page.goto("/my/account");
    await page.getByRole("radio", { name: /시스템 설정/ }).click();
  });
  for (const [name, url] of [
    ["S06 목록 (high contrast)", "/projects/web-platform/work-packages"],
    ["S07 상세 패널 (high contrast)", "/projects/web-platform/work-packages/details/3/overview"],
    ["S13 Gantt (high contrast)", "/projects/web-platform/gantt"],
    ["S02 홈 (high contrast)", "/"],
    ["S37 계정 설정 (high contrast)", "/my/account"],
    ["개발 탭 (high contrast)", "/projects/web-platform/work-packages/details/4/dev"],
  ] as const) {
    test(`a11y ${name}`, async ({ page }) => {
      await page.goto(url);
      const bg = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--pms-color-bg").trim().toLowerCase());
      expect(bg, "a light page under a dark system setting").toBe("#ffffff");
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--pms-color-text").trim().toLowerCase())).toBe("#000000");
      await page.waitForLoadState("networkidle");
      await axe(page, name);
    });
  }
});
