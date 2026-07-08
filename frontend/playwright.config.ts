import { defineConfig, devices } from "@playwright/test";

// Assumes the app is already running locally, per CLAUDE.md's local-run flow:
//   cd backend && ./mvnw spring-boot:run   (Postgres auto-starts via compose.yaml;
//                                            OPENROUTER_API_KEY must be set in the shell)
//   cd frontend && pnpm dev
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: "html",
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
