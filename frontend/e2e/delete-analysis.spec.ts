import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Runs against the deterministic `e2e` Spring profile (see CLAUDE.md) — no live OpenRouter
// call, no API key required. Registration and seeding live in fixtures.ts.
//
// Analysis.clauses has no @OrderBy (see the plan's Key Discoveries), so this spec never
// addresses list items by index or position; every locator is scoped to the unique
// timestamped title or to the alertdialog landmark.
//
// Each run registers a fresh, timestamp-suffixed account, so re-runs and parallel runs
// never collide. Deleting the analysis is the point of this test, so unlike the other
// specs there is nothing left over to clean up for this account beyond the account
// itself (account deletion is not in the MVP).

test("deleting an analysis removes it from the dashboard and its URL becomes unreachable", async ({ page }) => {
  await registerFreshUser(page, "delete");
  const title = `E2E Usuwanie ${Date.now()}`;
  const { url: analysisUrl } = await seedAnalysis(page, title);

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
