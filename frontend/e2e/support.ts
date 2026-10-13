// Shared by every spec: console errors and 5xx answers fail the test, an axe scan per screen, sign-in helpers and a
// database reset. Dev seed accounts only (password "openpms!" exists in the dev profile and nowhere else).
import { execSync } from "node:child_process";
import { test as base, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

export { expect };

const DEV_PASSWORD = "openpms!";

/** Back to the seed restore point (the API reconnects by itself). */
export function resetDb() {
  execSync("bash ../backend/scripts/dev.sh restore-db", { stdio: "ignore" });
}

export const test = base.extend<{ problems: string[] }>({
  problems: [
    async ({ page }, use) => {
      const problems: string[] = [];
      // a 4xx the test provokes on purpose shows up as a resource error in Chrome's console: that one is expected
      page.on("console", (m) => {
        if (m.type() === "error" && !/status of 4\d\d/.test(m.text())) problems.push(`console: ${m.text()}`);
      });
      page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));
      page.on("response", (r) => {
        if (r.status() >= 500) problems.push(`${r.status()} ${r.request().method()} ${r.url()}`);
      });
      await use(problems);
      expect(problems, "console errors and 5xx answers").toEqual([]);
    },
    { auto: true },
  ],
});

export async function login(page: Page, email: string, password = DEV_PASSWORD) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"));
}

export async function logout(page: Page) {
  await page.evaluate(async () => {
    const { api } = await import("/src/api/index.ts" as string);
    await api.auth.logout();
  });
}

/** Calls the app's own API client in the page (same session, same CSRF handling). */
export async function api<T>(page: Page, fn: string, ...args: unknown[]): Promise<T> {
  return page.evaluate(
    async ([body, a]) => {
      const { api: client } = await import("/src/api/index.ts" as string);
      // eslint-disable-next-line @typescript-eslint/no-implied-eval
      return new Function("api", "args", `return (async () => { ${body} })()`)(client, a);
    },
    [fn, args] as const,
  );
}

/** WCAG 2.1 A/AA rules on what is on screen now. */
export async function axe(page: Page, screen: string) {
  // a pointer resting on a control starts its hover transition, and a closing menu fades out: scanned halfway, the
  // in-between colours fail contrast (seen on S06: a column header at 3.74:1 mid-hover). Scan the settled screen.
  await page.mouse.move(0, 0);
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== "running"));
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  // ids such as #radix-_r_s_ say little: print what the element is and why (colours and ratio for contrast)
  for (const v of r.violations)
    for (const n of v.nodes.slice(0, 3)) console.log(`axe ${screen} ${v.id}: ${n.html.slice(0, 160)} — ${n.failureSummary?.replace(/\s+/g, " ").slice(0, 240)}`);
  expect(
    r.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).slice(0, 3).join(" | ")}`),
    `axe on ${screen}`,
  ).toEqual([]);
}

export const panel = (page: Page) => page.getByRole("complementary", { name: "상세 패널" });
