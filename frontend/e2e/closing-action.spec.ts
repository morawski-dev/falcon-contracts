import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Risk (analysis-closing-action / S-07): the report column simply ends after the last
// clause. The header's persistent wordmark is the only existing way back
// (app-header.spec.ts), but it reads as branding, not navigation — worst right after
// creation, when the user arrives by a redirect with no mental model of where they are.
// This spec locks in the report's own closing link as an independent affordance, labelled
// distinctly from the header's "Moje analizy" so the two never collide under getByRole's
// substring matching.

test("the closing link at the end of the report returns to the dashboard", async ({ page }) => {
  await registerFreshUser(page, "closing-action");
  const title = `E2E Closing Action ${Date.now()}`;
  await seedAnalysis(page, title);

  const closingLink = page.getByRole("link", { name: "Wróć do moich analiz" });
  await expect(closingLink).toBeVisible();
  await closingLink.click();
  await page.waitForURL("**/dashboard");

  // Cleanup: delete the analysis this test created.
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();
  await page.getByRole("button", { name: "Usuń" }).click();
  const confirmDialog = page.getByRole("alertdialog");
  await expect(confirmDialog).toBeVisible();
  await confirmDialog.getByRole("button", { name: "Usuń" }).click();
  await expect(historyRow).not.toBeVisible();
});
