---
change_id: testing-frontend-e2e
title: Deterministic browser E2E + disclaimer guardrail coverage
status: impl_reviewed
created: 2026-07-10
updated: 2026-07-10
archived_at: null
---

## Notes

Rollout Phase 3 of `context/foundation/test-plan.md`: "Frontend / browser E2E".

**Risks covered:** #4 (the frontend paste→result flow regresses, or the "not
legal advice" disclaimer disappears, or empty/unparseable input shows a silent
empty result), plus the strategy debt recorded in the test plan's §3 sequencing
note.

**Test types planned:** e2e (browser), Playwright.

### Risk response intent

- **Risk #4** — prove that the logged-in paste→submit→result flow renders
  classified clauses and negotiation points; that the disclaimer is visible
  wherever a result is shown; that empty/unparseable input reaches an
  explanatory state rather than a silent empty result; and that an
  unauthenticated visitor is redirected. Prove only what the browser owns —
  backend integration already owns classification correctness.

- **Strategy debt (precondition — retrofit first)** — the three existing specs
  (`frontend/e2e/{analysis-history,clause-decision,delete-analysis}.spec.ts`)
  seed their fixtures by driving the real paste→submit flow against the **live
  OpenRouter model**. This violates §1 principle 1 of the test plan, Risk #4's
  own response guidance ("a deterministic / mocked-LLM backend so E2E is neither
  flaky nor costly"), and `CLAUDE.md`'s rule that the real LLM is never called in
  tests. Give the browser layer a deterministic backend and re-seed those three
  specs off the live model **before** adding any new spec, so Risk #4's coverage
  is not built on top of the live-LLM seeding pattern. Then make the suite
  CI-runnable — `frontend/playwright.config.ts` currently has no `webServer`, and
  `.github/workflows/ci.yml`'s frontend job is lint + build only.

### Constraints that already hold

Role/label locators only (no CSS/XPath), never `page.waitForTimeout()`, test
independence with timestamp-suffixed unique ids and per-test cleanup.

### Open question for research

Where the deterministic seam belongs. The backend already has the right one — a
`@Primary ChatModel` bean returning fixed JSON, keeping the real `ChatClient` and
`BeanOutputConverter` in the path. Whether the browser layer reuses that via a
test Spring profile, or seeds analyses through the API instead of the paste form,
is a call-graph question the test plan deliberately does not answer.
