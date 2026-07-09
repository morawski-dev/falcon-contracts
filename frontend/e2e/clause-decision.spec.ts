import { test, expect } from "@playwright/test";

// Exercises the real analysis pipeline (paste -> submit -> classify via the live
// OpenRouter LLM) to seed a clause to set a decision on. Requires a running backend
// with OPENROUTER_API_KEY set (Postgres auto-starts via backend/compose.yaml) and a
// running frontend — see CLAUDE.md's local-run flow.
//
// The number of clauses the LLM returns is non-deterministic, so this spec locates the
// auto-renewal clause's decision group by its risk-type content rather than position.
// Position is NOT reload-stable: Analysis.clauses has no @OrderBy (see the plan's Key
// Discoveries), so Hibernate does not guarantee row order is identical across separate
// GETs of the same analysis — a "first group" locator can silently point at a different
// clause after a reload. The decision group's accessible name embeds the clause's risk
// type (a persisted, content-derived value) precisely so it can be found reliably
// regardless of where the clause currently renders.
//
// Known limitation: S-04 (delete-analysis) is not built yet, so there is no UI path
// to remove the user/analysis this test creates. Each run uses a fresh,
// timestamp-suffixed account so re-runs and parallel runs never collide.

const CONTRACT_TEXT = `1. Umowa zostaje zawarta na czas okreslony 12 miesiecy i ulega automatycznemu przedluzeniu na kolejne 12 miesiecy, jesli zadna ze stron nie zlozy pisemnego wypowiedzenia najpozniej na 90 dni przed uplywem okresu obowiazywania.
2. W przypadku odstapienia od umowy przez Zleceniobiorce przed terminem, Zleceniobiorca zobowiazany jest do zaplaty kary umownej w wysokosci 50000 zl.
3. Wynagrodzenie platne jest w terminie 30 dni od dnia wystawienia faktury.`;

test("clause decision persists across a reload", async ({ page }) => {
  const uniqueId = Date.now();
  const email = `e2e-decision-${uniqueId}@example.com`;
  const password = "playwright-test-password";
  const title = `E2E Decyzja ${uniqueId}`;

  await page.goto("/register");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Hasło").fill(password);
  await page.getByRole("button", { name: "Zarejestruj się" }).click();
  await page.waitForURL("/dashboard");

  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await page.getByLabel("Tytuł").fill(title);
  await page.getByLabel("Treść umowy").fill(CONTRACT_TEXT);
  await page.getByRole("button", { name: "Analizuj umowę" }).click();

  // The real LLM call takes several seconds; wait for the redirect to the saved
  // analysis rather than a fixed delay.
  await page.waitForURL(/\/analyses\/\d+$/, { timeout: 30_000 });

  // "Automatyczne przedłużenie" is the risk type Falcon assigns the auto-renewal
  // paragraph above; it identifies the same clause regardless of render order.
  const autoRenewalDecisions = page.getByRole("group", { name: /Automatyczne przedłużenie/ });
  await expect(autoRenewalDecisions).toBeVisible();

  await autoRenewalDecisions.getByRole("button", { name: "Do negocjacji" }).click();
  await expect(
    autoRenewalDecisions.getByRole("button", { name: "Do negocjacji", pressed: true })
  ).toBeVisible();

  await page.reload();

  const autoRenewalDecisionsAfterReload = page.getByRole("group", {
    name: /Automatyczne przedłużenie/,
  });
  await expect(autoRenewalDecisionsAfterReload).toBeVisible();
  await expect(
    autoRenewalDecisionsAfterReload.getByRole("button", { name: "Do negocjacji", pressed: true })
  ).toBeVisible();
});
