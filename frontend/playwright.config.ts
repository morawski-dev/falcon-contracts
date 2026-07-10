import { defineConfig, devices } from "@playwright/test";

// If both servers are already running (CLAUDE.md's local-run flow, boosted with the `e2e`
// Spring profile so no OPENROUTER_API_KEY is needed), Playwright reuses them. Otherwise it
// boots both itself via the webServer array below — this is what CI relies on.
const isCI = !!process.env.CI;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: isCI,
  retries: 0,
  reporter: "html",
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000",
    // "on-first-retry" would never fire: retries is 0 by design (a deterministic suite
    // that needs a retry is reporting a real bug, not flakiness). "retain-on-failure"
    // actually captures something.
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: [
    {
      // The e2e Spring profile (Phase 1) serves fixed classifications with no network
      // call and no API key. `additional-classpath-elements`, NOT `useTestClasspath` —
      // verified not to work on this project's spring-boot-maven-plugin 4.0.7.
      command:
        process.platform === "win32"
          ? ".\\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=e2e -Dspring-boot.run.additional-classpath-elements=target/test-classes"
          : "./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e -Dspring-boot.run.additional-classpath-elements=target/test-classes",
      cwd: "../backend",
      // Returns 401 (Spring Security intercepts before the actuator payload), not 200 —
      // that is still a real HTTP response, which is all Playwright's readiness probe
      // requires. A 401 here means the server is up, not that something is wrong.
      url: "http://localhost:8080/actuator/health",
      reuseExistingServer: !isCI,
      timeout: 120_000,
      env: isCI
        ? {
            // CI has no backend/compose.yaml Postgres — spring-boot-docker-compose must
            // not try to start one on top of the GitHub Actions service container already
            // bound to :5432 (see .github/workflows/ci.yml's `e2e` job).
            SPRING_DOCKER_COMPOSE_ENABLED: "false",
            SPRING_DATASOURCE_URL: "jdbc:postgresql://localhost:5432/mydatabase",
            SPRING_DATASOURCE_USERNAME: "myuser",
            SPRING_DATASOURCE_PASSWORD: "secret",
          }
        : undefined,
    },
    {
      // In CI the frontend job's build step already ran `pnpm build`; `pnpm start` serves
      // that production build. Locally, `pnpm dev` is what a developer already has open.
      command: isCI ? "pnpm start" : "pnpm dev",
      url: "http://localhost:3000",
      reuseExistingServer: !isCI,
      timeout: 60_000,
    },
  ],
});
