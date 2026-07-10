import { test } from "@playwright/test";

// Risk #4a: an unauthenticated visitor must never reach a protected route. proxy.ts's
// matcher covers /dashboard/:path* and /analyses/:path*; with no JSESSIONID cookie it
// issues a server-side redirect to /login before any page content renders.
//
// No fixture is used here — registering a user would defeat the point of this test.

test("unauthenticated visitor is redirected to /login from every protected route", async ({ page }) => {
  await page.goto("/dashboard");
  await page.waitForURL("**/login");

  await page.goto("/analyses/new");
  await page.waitForURL("**/login");

  await page.goto("/analyses/1");
  await page.waitForURL("**/login");
});
