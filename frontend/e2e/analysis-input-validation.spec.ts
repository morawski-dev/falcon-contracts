import { test, expect } from "@playwright/test";
import { registerFreshUser, CONTRACT_TEXT } from "./fixtures";

// Risk #4c/4d: empty/unparseable input must reach an explanatory state, never a silent
// empty result. The two failure modes are genuinely different code paths and therefore
// different tests:
//
// - Empty contract text is caught client-side (analyses/new/page.tsx's `!rawText.trim()`
//   guard) and returns before any fetch — proven here by asserting no POST is issued.
//   The textarea carries a native `required` attribute, which the browser enforces on
//   submit BEFORE React's handler ever runs — a genuinely empty field never reaches the
//   JS guard at all, it just shows the browser's own validation bubble. Whitespace-only
//   text satisfies `required` (the browser doesn't trim) while still failing `.trim()`,
//   so that is what actually exercises this code path.
// - A backend failure (502) can never come from the e2e-profile stub, which always
//   returns valid JSON — so this test drives it via request interception, which is
//   viable because every backend call is browser-issued (see the change's research).
//   The route only intercepts POST /api/analyses; GET requests pass through untouched
//   so the dashboard list is never swallowed.

test("empty contract text shows an explanatory state without contacting the server", async ({ page }) => {
  await registerFreshUser(page, "empty-input");

  let posted = false;
  await page.route("**/api/analyses", async (route) => {
    if (route.request().method() === "POST") {
      posted = true;
    }
    await route.continue();
  });

  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await page.getByLabel("Tytuł").fill("Pusta umowa");
  // Whitespace-only: satisfies the native `required` attribute so the browser lets the
  // submit through, while still failing the JS guard's `!rawText.trim()` check.
  await page.getByLabel("Treść umowy").fill("   ");
  await page.getByRole("button", { name: "Analizuj umowę" }).click();

  await expect(page.getByText("Wklej treść umowy, aby rozpocząć analizę.")).toBeVisible();
  expect(page.url()).toContain("/analyses/new");
  expect(posted).toBe(false);
});

test("a backend failure shows an explanatory state, not a silent empty result", async ({ page }) => {
  await registerFreshUser(page, "backend-failure");

  await page.route("**/api/analyses", async (route) => {
    if (route.request().method() === "POST") {
      await route.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({ error: "Failed to analyze contract. Please try again." }),
      });
      return;
    }
    await route.continue();
  });

  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await page.getByLabel("Tytuł").fill("Umowa z bledem");
  await page.getByLabel("Treść umowy").fill(CONTRACT_TEXT);
  await page.getByRole("button", { name: "Analizuj umowę" }).click();

  await expect(
    page.getByText("Nie udało się przeanalizować umowy. Spróbuj ponownie.")
  ).toBeVisible();
});
