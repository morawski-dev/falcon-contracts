import { test, expect } from "@playwright/test";
import { registerFreshUser, seedAnalysis } from "./fixtures";

// Risk #5 (app-navigation-header / S-05): the persistent AppHeader is the only way back to
// the dashboard from the analyze screens, and the only affordance for starting a new
// analysis once the dashboard's own empty-state CTA no longer applies (a populated
// dashboard has none). Locate the wordmark by role, never by text — "Falcon" appears as
// plain body copy on /analyses/new and /analyses/[id], which getByText would also match.

test("clicking the wordmark from /analyses/new returns to the dashboard", async ({ page }) => {
  await registerFreshUser(page, "header-new");

  await page.getByRole("link", { name: "Nowa analiza" }).click();
  await page.waitForURL("/analyses/new");

  await page.getByRole("link", { name: "Falcon" }).click();
  await page.waitForURL("**/dashboard");
});

test("clicking the wordmark from a saved analysis returns to the dashboard", async ({ page }) => {
  await registerFreshUser(page, "header-result");
  const title = `E2E Header ${Date.now()}`;
  await seedAnalysis(page, title);

  await page.getByRole("link", { name: "Falcon" }).click();
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

test("the header's \"Moje analizy\" link returns to the dashboard from a saved analysis", async ({
  page,
}) => {
  await registerFreshUser(page, "header-moje-analizy");
  const title = `E2E Moje Analizy ${Date.now()}`;
  await seedAnalysis(page, title);

  await page.getByRole("link", { name: "Moje analizy" }).click();
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

test("the header's \"Nowa analiza\" starts a new analysis from a populated dashboard", async ({
  page,
}) => {
  // Regression guard: the dashboard's own empty-state CTA only renders when the list is
  // empty, so every other spec that clicks "Nowa analiza" does so on a fresh, empty
  // account and would not notice if the header's CTA were ever removed. This is the one
  // test that seeds an analysis first, so the dashboard is genuinely non-empty when the
  // header's link is clicked.
  await registerFreshUser(page, "header-populated");
  const title = `E2E Populated ${Date.now()}`;
  await seedAnalysis(page, title);

  await page.goto("/dashboard");
  await expect(page.getByRole("link", { name: new RegExp(title) })).toBeVisible();

  await page.getByRole("link", { name: "Nowa analiza" }).click();
  await page.waitForURL("/analyses/new");

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

test("logging out from the header ends the session", async ({ page }) => {
  await registerFreshUser(page, "header-logout");

  // Await the logout response itself, not the URL — navigating right after a mutation
  // races an in-flight request (the same class of flake fixed in commit f4295a7 for the
  // delete-analysis spec).
  await Promise.all([
    page.waitForResponse(
      (res) =>
        res.url().includes("/api/auth/logout") &&
        res.request().method() === "POST" &&
        res.ok()
    ),
    page.getByRole("button", { name: "Wyloguj" }).click(),
  ]);
  await page.waitForURL("**/login");

  await page.goto("/dashboard");
  await page.waitForURL("**/login");
});

test("the header does not appear on the login or register pages", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("link", { name: "Falcon" })).not.toBeVisible();
  await expect(page.getByRole("link", { name: "Moje analizy" })).not.toBeVisible();
  await expect(page.getByRole("button", { name: "Wyloguj" })).not.toBeVisible();

  await page.goto("/register");
  await expect(page.getByRole("link", { name: "Falcon" })).not.toBeVisible();
  await expect(page.getByRole("link", { name: "Moje analizy" })).not.toBeVisible();
  await expect(page.getByRole("button", { name: "Wyloguj" })).not.toBeVisible();
});
