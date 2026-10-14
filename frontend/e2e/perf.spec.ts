// 성능 예산 (replica/fixes.md 4번): 작업 1,000건 프로젝트(backend/src/test/resources/perf/perf-1000.sql)에서 주요 화면이 다 그려질
// 때까지의 시간. 주소를 열고(새로 고침과 같음, 캐시는 데움) 화면의 마지막 요소가 보일 때까지를 5번 재서 중앙값을 예산과 비교합니다.
// 기본은 개발 서버(5174, 번들 전 코드라 느림)이고, E2E_BASE=http://localhost:8090 이면 백엔드가 내주는 빌드된 화면을 잽니다
// (npm run build 뒤 dev.sh run). 서버 쪽 시간과 SQL 수는 backend PerfBudgetIT가 따로 봅니다.
import { execSync } from "node:child_process";
import type { Page } from "@playwright/test";
import { test, expect, login, logout, resetDb } from "./support";

const BASE = process.env.E2E_BASE ?? "http://localhost:5174";
const BUILT = !/:517\d\b/.test(BASE);
const RUNS = 5;

/** Budgets for the built app (what people use); the dev server gets twice as long. */
const BUDGET = {
  list20: 1000,
  list100: 1500,
  nextPage: 500,
  gantt: 1500,
  board: 1500,
  detail: 1000,
  slowFirst: 3000,
  slowAgain: 1500,
  portal: 1000,
  serviceDeskList: 1000,
};
const budget = (k: keyof typeof BUDGET) => (BUILT ? BUDGET[k] : BUDGET[k] * 2);

let boardId = "";
let detail = 0;
const rows: string[] = [];

const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

/** Opens the address and waits until `ready` holds; one warm-up, then RUNS timings. */
async function timeOpen(page: Page, url: string, ready: () => Promise<unknown>) {
  const took: number[] = [];
  for (let i = -1; i < RUNS; i++) {
    const t0 = Date.now();
    await page.goto(url, { waitUntil: "commit" });
    await ready();
    if (i >= 0) took.push(Date.now() - t0);
  }
  return took;
}

function record(screen: string, what: string, took: number[], limit: number, extra = "") {
  const m = median(took);
  rows.push(`| ${screen} | ${what} | ${m} ms | ${Math.max(...took)} ms | ${limit} ms | ${extra} |`);
  expect(m, `${screen} · ${what}: 중앙값 ${m} ms (예산 ${limit} ms, 잰 값 ${took.join(", ")})`).toBeLessThanOrEqual(limit);
}

const count = (page: Page, selector: string, atLeast: number) =>
  page.waitForFunction(([s, n]) => document.querySelectorAll(s as string).length >= (n as number), [selector, atLeast] as const, { polling: "raf", timeout: 30_000 });

test.describe.configure({ mode: "serial" });

test.describe("성능 예산 · 작업 1,000건", () => {
  test.beforeAll(() => {
    resetDb();
    const out = execSync("bash ../backend/scripts/dev.sh perf-seed", { encoding: "utf8" }).trim().split(/\s+/);
    boardId = out[0];
    detail = Number(out[1]);
    expect(boardId).toMatch(/^[0-9a-f-]{36}$/);
  });

  test.afterAll(() => {
    console.log(`\n성능 예산 (${BUILT ? "빌드된 화면" : "개발 서버"} ${BASE}, ${RUNS}번 중앙값)\n| 화면 | 측정 | 중앙값 | 최댓값 | 예산 | 비고 |\n|---|---|---:|---:|---:|---|\n${rows.join("\n")}\n`);
    resetDb();
  });

  test.beforeEach(async ({ page }) => {
    await login(page, "seoyeon.kim@openpms.local");
  });

  test("PERF-1 목록: 기본 보기 20건, 100건, 다음 쪽", async ({ page }) => {
    const list = "/projects/perf-1000/work-packages";
    record("목록", "모두 열림 · 20건", await timeOpen(page, list, () => count(page, "tbody tr", 20)), budget("list20"));
    record("목록", "모두 열림 · 100건", await timeOpen(page, `${list}?size=100`, () => count(page, "tbody tr", 100)), budget("list100"));
    // the next page: no reload, the query and the table only
    const took: number[] = [];
    for (let i = 0; i < RUNS; i++) {
      await page.goto(`${list}?size=100`);
      await count(page, "tbody tr", 100);
      const first = await page.locator("tbody tr").first().innerText();
      const t0 = Date.now();
      await page.getByRole("button", { name: "다음 페이지" }).click();
      await page.waitForFunction((f) => document.querySelector("tbody tr")?.textContent !== f && document.querySelectorAll("tbody tr").length >= 100, first, { polling: "raf" });
      took.push(Date.now() - t0);
    }
    record("목록", "다음 쪽(100건)", took, budget("nextPage"));
  });

  test("PERF-2 Gantt: 계층 · 200건", async ({ page }) => {
    record("Gantt", "200건 · 막대와 선후행 선", await timeOpen(page, "/projects/perf-1000/gantt", () => count(page, "[data-gantt-scroll] ul > li", 200)), budget("gantt"));
    await expect(page.getByRole("group", { name: "Gantt 차트" })).toBeVisible();
  });

  test("PERF-3 보드: 상태 보드 카드 1,000장 (열마다 50장 먼저, 나머지는 더 보기)", async ({ page }) => {
    // ready: every column drawn, each with min(50, its count) cards
    const drawn = () =>
      page.waitForFunction(
        () => {
          const cols = [...document.querySelectorAll("main section[aria-label]")];
          if (cols.length < 6) return false;
          const total = cols.reduce((n, c) => n + Number(c.querySelector("h2")?.textContent?.match(/\d+$/)?.[0] ?? 0), 0);
          return total === 1000 && cols.every((c) => {
            const n = Number(c.querySelector("h2")?.textContent?.match(/\d+$/)?.[0] ?? 0);
            return c.querySelectorAll("[aria-roledescription]").length === Math.min(50, n);
          });
        },
        undefined,
        { polling: "raf", timeout: 30_000 },
      );
    const took = await timeOpen(page, `/projects/perf-1000/boards/${boardId}`, drawn);
    const heap = await page.evaluate(() => (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory?.usedJSHeapSize ?? 0);
    record("보드", "카드 1,000장 (열마다 50장)", took, budget("board"), heap ? `JS 힙 ${Math.round(heap / 1048576)} MB` : "");
    // the rest of a long column on request: 100 more each time
    const fresh = page.getByRole("region", { name: "신규" });
    await expect(fresh.locator("[aria-roledescription]")).toHaveCount(50);
    await fresh.getByRole("button", { name: /신규 열에서 카드 100장 더 보기 \(남은 카드 356장\)/ }).click();
    await expect(fresh.locator("[aria-roledescription]")).toHaveCount(150);
    await expect(fresh.getByRole("button", { name: /더 보기/ })).toBeVisible();
  });

  test("PERF-4 작업 상세: 이력 40건", async ({ page }) => {
    record("작업 상세", "전체 화면 · 이력 40건", await timeOpen(page, `/projects/perf-1000/work-packages/${detail}/activity`, () =>
      page.getByText("40번째 코멘트", { exact: false }).waitFor({ timeout: 30_000 })), budget("detail"));
  });

  test("PERF-6 서비스 데스크: 요청자의 포털 홈, SLA 열을 켠 티켓 목록", async ({ page }) => {
    // 개발 데이터 V1004: 최민지의 진행 중인 요청 2건, service-desk 티켓 6건
    await logout(page);
    await login(page, "minji.choi@openpms.local");
    record("포털 홈", "데스크와 내 요청(진행 중 2건)", await timeOpen(page, "/portal", async () => {
      await page.getByRole("link", { name: "IT 서비스 데스크에 장애 신고" }).waitFor({ timeout: 30_000 });
      await count(page, "tbody tr", 2);
    }), budget("portal"));
    await logout(page);
    await login(page, "junho.park@openpms.local");
    const spec = { v: 1, filters: [], columns: ["number", "subject", "status", "requester", "assignmentGroup", "slaDue", "slaState"], sort: [{ field: "number", dir: "asc" }],
      groupBy: null, hierarchy: false, sums: false, includeSubprojects: false };
    const list = `/projects/service-desk/work-packages?q=${Buffer.from(JSON.stringify(spec)).toString("base64url")}`;
    record("서비스 데스크 목록", "티켓 6건 · SLA 열", await timeOpen(page, list, async () => {
      await count(page, "tbody tr", 6);
      await page.locator("tbody").getByText("위반", { exact: true }).first().waitFor({ timeout: 30_000 });
    }), budget("serviceDeskList"));
  });

  test("PERF-5 느린 망(10 Mbps, 왕복 40 ms): 처음 열기와 다시 열기, 주고받은 양", async ({ browser }) => {
    test.skip(!BUILT, "개발 서버는 파일을 묶지 않아 망 조건 비교가 의미 없음");
    // a fresh browser profile: nothing cached
    const context = await browser.newContext();
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send("Network.enable");
    await cdp.send("Network.emulateNetworkConditions", { offline: false, latency: 40, downloadThroughput: (10 * 1024 * 1024) / 8, uploadThroughput: (5 * 1024 * 1024) / 8 });
    let bytes = 0;
    cdp.on("Network.loadingFinished", (e: { encodedDataLength: number }) => (bytes += e.encodedDataLength));
    await login(page, "seoyeon.kim@openpms.local");
    const open = async (url: string, ready: () => Promise<unknown>) => {
      bytes = 0;
      const t0 = Date.now();
      await page.goto(url, { waitUntil: "commit" });
      await ready();
      return { ms: Date.now() - t0, kb: Math.round(bytes / 1024) };
    };
    // sign-in loaded the app's files once; clear the cache so the first list load fetches everything again
    await cdp.send("Network.clearBrowserCache");
    const list = "/projects/perf-1000/work-packages";
    const first = await open(list, () => count(page, "tbody tr", 20));
    const again = await open(list, () => count(page, "tbody tr", 20));
    const board = await open(`/projects/perf-1000/boards/${boardId}`, () => count(page, "[aria-roledescription]", 260));
    rows.push(`| 느린 망 | 목록 처음 열기(캐시 없음) | ${first.ms} ms | | ${budget("slowFirst")} ms | 받은 양 ${first.kb} KB |`);
    rows.push(`| 느린 망 | 목록 다시 열기 | ${again.ms} ms | | ${budget("slowAgain")} ms | 받은 양 ${again.kb} KB |`);
    rows.push(`| 느린 망 | 보드 열기(카드 1,000장) | ${board.ms} ms | | ${budget("slowFirst")} ms | 받은 양 ${board.kb} KB |`);
    expect(first.ms, `처음 열기 ${first.ms} ms`).toBeLessThanOrEqual(budget("slowFirst"));
    expect(again.ms, `다시 열기 ${again.ms} ms`).toBeLessThanOrEqual(budget("slowAgain"));
    expect(board.ms, `보드 ${board.ms} ms`).toBeLessThanOrEqual(budget("slowFirst"));
    // the hashed files came from the cache the second time: far less on the wire
    expect(again.kb).toBeLessThan(first.kb / 3);
    await context.close();
  });
});
