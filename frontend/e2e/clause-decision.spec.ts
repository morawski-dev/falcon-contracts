import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Runs against the deterministic `e2e` Spring profile (see CLAUDE.md) — no live OpenRouter
// call, no API key required. Registration and seeding live in fixtures.ts.
//
// This spec locates the auto-renewal clause's decision group by its risk-type content
// rather than position. Position is NOT reload-stable: Analysis.clauses has no @OrderBy
// (see the plan's Key Discoveries), so Hibernate does not guarantee row order is
// identical across separate GETs of the same analysis — a "first group" locator can
// silently point at a different clause after a reload. The decision group's accessible
// name embeds the clause's risk type (a persisted, content-derived value) precisely so
// it can be found reliably regardless of where the clause currently renders. The e2e
// fixture (ClauseAnalysisFixtures.MULTI_CLAUSE_JSON) assigns a distinct risk type to
// each of its 3 clauses, so this locator never risks matching more than one group.
//
// Each run uses a fresh, timestamp-suffixed account so re-runs and parallel runs never
// collide. Cleanup deletes the analysis this test created (S-04); the account itself
// still leaks — account deletion is not in the MVP.

test("clause decision persists across a reload", async ({ page }) => {
  await registerFreshUser(page, "decision");
  const title = `E2E Decyzja ${Date.now()}`;
  await seedAnalysis(page, title);

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

  // Cleanup: delete the analysis this test created so re-runs don't accumulate rows.
  await page.goto("/dashboard");
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();
  await page.getByRole("button", { name: "Usuń" }).click();
  const confirmDialog = page.getByRole("alertdialog");
  await expect(confirmDialog).toBeVisible();
  await confirmDialog.getByRole("button", { name: "Usuń" }).click();
  await expect(historyRow).not.toBeVisible();
});
