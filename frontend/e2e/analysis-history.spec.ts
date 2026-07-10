import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Runs against the deterministic `e2e` Spring profile (see CLAUDE.md) — no live OpenRouter
// call, no API key required. Registration and seeding live in fixtures.ts.
//
// Each run uses a fresh, timestamp-suffixed account so re-runs and parallel runs never
// collide. Cleanup deletes the analysis this test created (S-04); the account itself
// still leaks — account deletion is not in the MVP.

test("user's saved analyses appear on the dashboard and reopen correctly", async ({ page }) => {
  await registerFreshUser(page, "history");
  const title = `E2E Historia ${Date.now()}`;
  await seedAnalysis(page, title);
  await expect(page.getByText(title)).toBeVisible();

  await page.goto("/dashboard");
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();

  await historyRow.click();
  await page.waitForURL(/\/analyses\/\d+$/);
  await expect(page.getByText(title)).toBeVisible();

  // Cleanup: delete the analysis this test created so re-runs don't accumulate rows.
  await page.goto("/dashboard");
  await expect(historyRow).toBeVisible();
  await page.getByRole("button", { name: "Usuń" }).click();
  const confirmDialog = page.getByRole("alertdialog");
  await expect(confirmDialog).toBeVisible();
  await confirmDialog.getByRole("button", { name: "Usuń" }).click();
  await expect(historyRow).not.toBeVisible();
});
