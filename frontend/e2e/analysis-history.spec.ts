import { test, expect } from "@playwright/test";

// Exercises the real analysis pipeline (paste -> submit -> classify via the live
// OpenRouter LLM) to seed data for the dashboard history list, per the chosen
// seeding strategy for S-03 (see context/changes/analysis-history/plan.md, Phase 3).
// Requires a running backend with OPENROUTER_API_KEY set (Postgres auto-starts via
// backend/compose.yaml) and a running frontend — see CLAUDE.md's local-run flow.
//
// Known limitation: S-04 (delete-analysis) is not built yet, so there is no UI path
// to remove the user/analysis this test creates. Each run uses a fresh,
// timestamp-suffixed account so re-runs and parallel runs never collide.

const CONTRACT_TEXT = `1. Umowa zostaje zawarta na czas okreslony 12 miesiecy i ulega automatycznemu przedluzeniu na kolejne 12 miesiecy, jesli zadna ze stron nie zlozy pisemnego wypowiedzenia najpozniej na 90 dni przed uplywem okresu obowiazywania.
2. W przypadku odstapienia od umowy przez Zleceniobiorce przed terminem, Zleceniobiorca zobowiazany jest do zaplaty kary umownej w wysokosci 50000 zl.
3. Wynagrodzenie platne jest w terminie 30 dni od dnia wystawienia faktury.`;

test("user's saved analyses appear on the dashboard and reopen correctly", async ({ page }) => {
  const uniqueId = Date.now();
  const email = `e2e-history-${uniqueId}@example.com`;
  const password = "playwright-test-password";
  const title = `E2E Historia ${uniqueId}`;

  await page.goto("/register");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Hasło").fill(password);
  await page.getByRole("button", { name: "Zarejestruj się" }).click();
  await page.waitForURL("/dashboard");

  // "Nowa analiza" appears twice on a fresh account's dashboard: the header action
  // and the empty-state CTA. Both point at the same route, so either is fine to click.
  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await page.getByLabel("Tytuł").fill(title);
  await page.getByLabel("Treść umowy").fill(CONTRACT_TEXT);
  await page.getByRole("button", { name: "Analizuj umowę" }).click();

  // The real LLM call takes several seconds; wait for the redirect to the saved
  // analysis rather than a fixed delay.
  await page.waitForURL(/\/analyses\/\d+$/, { timeout: 30_000 });
  await expect(page.getByText(title)).toBeVisible();

  await page.goto("/dashboard");
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();

  await historyRow.click();
  await page.waitForURL(/\/analyses\/\d+$/);
  await expect(page.getByText(title)).toBeVisible();
});
