// End-to-end tests (replica/test-plan.md): the real API behind the dev server, on the seed database.
// Needs: backend/scripts/dev.sh run (API on 127.0.0.1:8090) and `npm run dev:api` (5174). Each spec file starts from the
// restore point (dev.sh restore-db), so files run one after another.
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE ?? "http://localhost:5174",
    channel: "chrome",
    headless: true,
    viewport: { width: 1424, height: 900 },
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
    trace: "retain-on-failure",
  },
});
