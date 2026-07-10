import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Risk #4e (the core of Risk #4): proves the result view renders the full breakdown from
// the e2e-profile backend's fixed reply (ClauseAnalysisFixtures.MULTI_CLAUSE_JSON) — no
// clause dropped, no risky clause silently downgraded, and negotiation points linked to
// exactly the risky clauses (selective linkage), never asserted by string-equality on
// recommendation prose or by clause position.
//
// Each clause's card is scoped via data-testid="clause-{id}" (added to page.tsx — see the
// plan's Phase 3 note) combined with its risk-type-labeled decision group, since the card
// itself carries no ARIA role that role/label/text locators alone could scope by.

test("saved analysis renders all clauses, never downgrades risk, and links negotiation points selectively", async ({
  page,
}) => {
  await registerFreshUser(page, "result");
  const title = `E2E Wynik ${Date.now()}`;
  await seedAnalysis(page, title);

  const clauseCard = (riskTypeLabel: string) =>
    page
      .getByTestId(/^clause-\d+$/)
      .filter({ has: page.getByRole("group", { name: new RegExp(riskTypeLabel) }) });

  const autoRenewalCard = clauseCard("Automatyczne przedłużenie");
  const penaltyCard = clauseCard("Kara umowna");
  const paymentCard = clauseCard("Warunki płatności");

  // All 3 clauses render — no drop.
  await expect(autoRenewalCard).toBeVisible();
  await expect(penaltyCard).toBeVisible();
  await expect(paymentCard).toBeVisible();

  // Never silently downgraded — assert the negative, not just the positive.
  await expect(autoRenewalCard.getByText("Wysokie")).toBeVisible();
  await expect(autoRenewalCard.getByText("Niskie")).not.toBeVisible();
  await expect(penaltyCard.getByText("Średnie")).toBeVisible();
  await expect(penaltyCard.getByText("Niskie")).not.toBeVisible();
  await expect(paymentCard.getByText("Niskie")).toBeVisible();

  // Selective linkage: the two risky clauses each show their own negotiation point; the
  // LOW/PAYMENT clause shows none.
  await expect(
    autoRenewalCard.getByText(
      "Skrócić okres wypowiedzenia do 30 dni lub usunąć automatyczne przedłużenie."
    )
  ).toBeVisible();
  await expect(
    penaltyCard.getByText("Obniżyć karę umowną lub wprowadzić łączny limit.")
  ).toBeVisible();
  await expect(paymentCard.getByText("Punkt do negocjacji")).not.toBeVisible();

  // No unlinked points in this fixture — the "Pozostałe uwagi" section never renders.
  await expect(page.getByText("Pozostałe uwagi")).not.toBeVisible();

  // Cleanup: delete the analysis this test created.
  await page.goto("/dashboard");
  const historyRow = page.getByRole("link", { name: new RegExp(title) });
  await expect(historyRow).toBeVisible();
  await page.getByRole("button", { name: "Usuń" }).click();
  const confirmDialog = page.getByRole("alertdialog");
  await expect(confirmDialog).toBeVisible();
  await confirmDialog.getByRole("button", { name: "Usuń" }).click();
  await expect(historyRow).not.toBeVisible();
});
