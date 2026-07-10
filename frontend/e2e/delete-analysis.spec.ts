import { test, expect } from "@playwright/test";

// Exercises the real analysis pipeline (paste -> submit -> classify via the live
// OpenRouter LLM) to seed an analysis to delete. Requires a running backend with
// OPENROUTER_API_KEY set (Postgres auto-starts via backend/compose.yaml) and a running
// frontend — see CLAUDE.md's local-run flow.
//
// Analysis.clauses has no @OrderBy (see the plan's Key Discoveries), so this spec never
// addresses list items by index or position; every locator is scoped to the unique
// timestamped title or to the alertdialog landmark.
//
// Each run registers a fresh, timestamp-suffixed account, so re-runs and parallel runs
// never collide. Deleting the analysis is the point of this test, so unlike the other
// specs there is nothing left over to clean up for this account beyond the account
// itself (account deletion is not in the MVP).

const CONTRACT_TEXT = `1. Umowa zostaje zawarta na czas okreslony 12 miesiecy i ulega automatycznemu przedluzeniu na kolejne 12 miesiecy, jesli zadna ze stron nie zlozy pisemnego wypowiedzenia najpozniej na 90 dni przed uplywem okresu obowiazywania.
2. W przypadku odstapienia od umowy przez Zleceniobiorce przed terminem, Zleceniobiorca zobowiazany jest do zaplaty kary umownej w wysokosci 50000 zl.
3. Wynagrodzenie platne jest w terminie 30 dni od dnia wystawienia faktury.`;

test("deleting an analysis removes it from the dashboard and its URL becomes unreachable", async ({ page }) => {
  const uniqueId = Date.now();
  const email = `e2e-delete-${uniqueId}@example.com`;
  const password = "playwright-test-password";
  const title = `E2E Usuwanie ${uniqueId}`;

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
  const analysisUrl = page.url();

  await page.goto("/dashboard");
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();

  // The dashboard's fresh account has exactly one analysis, so the trigger button is
  // unambiguous by role + name alone before the confirmation dialog opens.
  await page.getByRole("button", { name: "Usuń" }).click();

  const confirmDialog = page.getByRole("alertdialog");
  await expect(confirmDialog).toBeVisible();
  // Once the dialog is open, a second "Usuń" button exists (the confirm action) —
  // scope to the alertdialog landmark rather than relying on render order or position.
  await confirmDialog.getByRole("button", { name: "Usuń" }).click();

  await expect(historyRow).not.toBeVisible();

  await page.goto(analysisUrl);
  await expect(page.getByText("Nie znaleziono analizy")).toBeVisible();
});
