import { test, expect } from "@playwright/test";
import { registerFreshUser, CONTRACT_TEXT } from "./fixtures";

// Risk #4b: the "not legal advice" disclaimer must be visible wherever a result could be
// shown, and on the input page before a result exists. The two sites render different
// text in different elements — a bare <p> on /analyses/new, a role="alert" Alert on
// /analyses/[id] — so this spec asserts each with its own locator.
//
// Do NOT use getByRole('alert') on /analyses/[id]: the not-found state is also an Alert
// (variant="destructive"), so the role alone is ambiguous there — disambiguate by title
// text instead.

test("disclaimer is visible on the input page and on a saved result", async ({ page }) => {
  await registerFreshUser(page, "disclaimer");

  await page.getByRole("link", { name: "Nowa analiza" }).first().click();
  await page.waitForURL("/analyses/new");
  await expect(
    page.getByText("Falcon dostarcza analizę pomocniczą, a nie poradę prawną.")
  ).toBeVisible();

  const title = `E2E Disclaimer ${Date.now()}`;
  await page.getByLabel("Tytuł").fill(title);
  await page.getByLabel("Treść umowy").fill(CONTRACT_TEXT);
  await page.getByRole("button", { name: "Analizuj umowę" }).click();
  await page.waitForURL(/\/analyses\/\d+$/);

  await expect(page.getByText("To nie jest porada prawna")).toBeVisible();
});
