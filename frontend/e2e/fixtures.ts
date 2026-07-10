import { Page } from "@playwright/test";

// Shared paste->submit fixture text. The backend runs against the deterministic `e2e`
// Spring profile (see CLAUDE.md), so this contract's content is never actually classified
// by a model — the fixed MULTI_CLAUSE_JSON reply always comes back regardless of what is
// pasted here. A realistic contract is kept anyway so the paste flow itself stays honest.
export const CONTRACT_TEXT = `1. Umowa zostaje zawarta na czas okreslony 12 miesiecy i ulega automatycznemu przedluzeniu na kolejne 12 miesiecy, jesli zadna ze stron nie zlozy pisemnego wypowiedzenia najpozniej na 90 dni przed uplywem okresu obowiazywania.
2. W przypadku odstapienia od umowy przez Zleceniobiorce przed terminem, Zleceniobiorca zobowiazany jest do zaplaty kary umownej w wysokosci 50000 zl.
3. Wynagrodzenie platne jest w terminie 30 dni od dnia wystawienia faktury.`;

/**
 * Registers a fresh, timestamp-suffixed account and lands on /dashboard. Each call uses a
 * distinct email so parallel runs and re-runs never collide; the account itself leaks
 * (account deletion is not in the MVP).
 */
export async function registerFreshUser(page: Page, prefix: string) {
  const uniqueId = Date.now();
  const email = `e2e-${prefix}-${uniqueId}@example.com`;
  const password = "playwright-test-password";

  await page.goto("/register");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Hasło").fill(password);
  await page.getByRole("button", { name: "Zarejestruj się" }).click();
  await page.waitForURL("/dashboard");

  return { email, password };
}

/**
 * Drives the real paste->submit UI to create an analysis, then waits for the redirect to
 * the saved result. Against the deterministic `e2e` backend this resolves in well under a
 * second — no live-LLM wait, so no extended timeout is needed here.
 */
export async function seedAnalysis(page: Page, title: string) {
  // "Nowa analiza" appears twice on a fresh account's dashboard: the header action
  // and the empty-state CTA. Both point at the same route, so either is fine to click.
  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await page.getByLabel("Tytuł").fill(title);
  await page.getByLabel("Treść umowy").fill(CONTRACT_TEXT);
  await page.getByRole("button", { name: "Analizuj umowę" }).click();

  await page.waitForURL(/\/analyses\/\d+$/);
  return { url: page.url() };
}
