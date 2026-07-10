---
date: 2026-07-10T13:56:17+02:00
researcher: Mateusz Morawski
git_commit: b665bf53d86cdb564d11a1d18b1ab22614760226
branch: main
repository: falcon-contracts
topic: "Where the deterministic seam belongs for browser E2E, and how to cover Risk #4"
tags: [research, codebase, e2e, playwright, spring-ai, chatmodel, determinism, ci]
status: complete
last_updated: 2026-07-10
last_updated_by: Mateusz Morawski
---

# Research: Deterministic browser E2E + disclaimer guardrail coverage

**Date**: 2026-07-10T13:56:17+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: `b665bf53d86cdb564d11a1d18b1ab22614760226`
**Branch**: `main`
**Repository**: `falcon-contracts`

## Research Question

Rollout Phase 3 of `context/foundation/test-plan.md`. Where does the deterministic
seam belong for the browser E2E layer — a test Spring profile, API/DB seeding,
request stubbing, or something else? And what exactly must be asserted to close
Risk #4 (paste→result regression, disappearing "not legal advice" disclaimer,
silent empty result on bad input)?

## Summary

Four findings, in descending order of how much they change the plan.

**1. The seam question is malformed as asked — there is no single seam.** Risk #4
decomposes into five assertions that need *different* amounts of backend. Three of
them (auth redirect, disclaimer on `/analyses/new`, empty-input explanatory state)
need **no backend at all** — the empty-input guard is client-side and returns before
any `fetch`. One (the 502 explanatory state) is **only** reachable via request
stubbing, because the running backend cannot be coaxed into a deterministic 502.
Only one (the result page: clauses + negotiation points + disclaimer) needs a real
saved analysis, and that is the sole assertion for which the "profile vs seeding"
debate is live. Picking one seam for everything would overpay on three tests and
make one impossible.

**2. Playwright request interception is viable — every backend call is browser-issued.**
There is exactly one `fetch(` in the whole frontend
([`lib/api.ts:21`](https://github.com/morawski-dev/falcon-contracts/blob/b665bf53d86cdb564d11a1d18b1ab22614760226/frontend/src/lib/api.ts#L21)),
it runs client-side (it reads `document.cookie`), and it targets the Spring backend
cross-origin at `http://localhost:8080`. There are no server actions, no
`src/app/api/` route handlers, and no `rewrites` proxy. `page.route()` can therefore
stub any backend response. This was the fact most likely to have killed a seam
option, and it does not.

**3. A deterministic backend is a two-file change, and it does not have to ship in
the production jar.** `ContractAnalysisService` injects `ChatClient.Builder`
([`ContractAnalysisService.java:59-63`](https://github.com/morawski-dev/falcon-contracts/blob/b665bf53d86cdb564d11a1d18b1ab22614760226/backend/src/main/java/com/morawski/dev/falcon/analysis/ContractAnalysisService.java#L59-L63)),
and Spring AI builds that `Builder` from the single `ChatModel` bean in the context.
That is exactly why the existing tests override only `ChatModel` and it works. A
`@Profile("e2e") @Bean @Primary ChatModel` wins by the identical mechanism, and
`ContractAnalysisService` needs no edit. Crucially, `spring-boot:run` accepts
`-Dspring-boot.run.useTestClasspath=true`, whose documented purpose is "run your
application in a test mode that uses stub classes," and `repackage` never adds test
classes to the jar — so the stub can live under `src/test/java` and stay out of
production.

**4. Live-LLM seeding was never a reasoned decision — it overrode an explicit
CRITICAL review finding, and its rationale was never written down.** The S-03
plan-review flagged it before it was taken, recommended deterministic DB seeding,
and offered an `e2e` Spring profile as the alternative. Both were bypassed by a
verbal mid-session choice. Reopening this **adopts** prior review guidance rather
than relitigating a settled architecture.

## Detailed Findings

### The call path: browser-side, confirmed

`frontend/src/app/analyses/new/page.tsx:1` is `"use client"`. Its submit handler
(`page.tsx:28-52`) calls `createAnalysis` (`lib/analyses.ts:54-61`), which calls
`apiFetch` (`lib/api.ts:21`):

```js
const response = await fetch(`${API_BASE_URL}${path}`, {
  ...init,
  credentials: "include",
```

Three independent proofs it is browser-side: `credentials: "include"` is a browser
fetch option; `readXsrfCookie()` reads `document.cookie` (`api.ts:12-15`), which only
exists in a browser; and `API_BASE_URL` is `process.env.NEXT_PUBLIC_API_BASE_URL ??
"http://localhost:8080"` (`api.ts:1`) — the `NEXT_PUBLIC_` prefix inlines it into the
client bundle.

Confirmed absent: `src/app/api/` (no route handlers), any `"use server"` (no server
actions), and `rewrites` in `next.config.ts` (which contains only `output: "standalone"`).

**Do not be misled by `frontend/src/proxy.ts`.** Despite the name it is not a backend
proxy — it is the Next.js 16 middleware, renamed from `middleware.ts` in that release.
It only guards routes.

### Risk #4 decomposes into five assertions with different backend needs

This is the load-bearing finding. Each row names the cheapest layer that gives real
signal, per the test plan's §1 principle 1.

| # | Assertion | Backend needed? | Cheapest mechanism |
|---|-----------|-----------------|--------------------|
| 4a | Unauthenticated visitor to a protected route is redirected to `/login` | none | `page.goto` with no cookie; `proxy.ts:4-14` issues a server 307 |
| 4b | Disclaimer visible on `/analyses/new` | none | static render; `getByText(...)` |
| 4c | Empty contract text → explanatory state, not a silent empty result | **none** | client guard returns before any fetch (`new/page.tsx:32-35`) |
| 4d | Backend failure (502) → explanatory state | stub only | `page.route()` fulfilling 502 — see below |
| 4e | Result page renders clauses + negotiation points + disclaimer | real saved analysis | `e2e` Spring profile |

**On 4c** — `analyses/new/page.tsx:32-35` guards `if (!rawText.trim())`, sets the error
string `"Wklej treść umowy, aby rozpocząć analizę."` and **returns before calling
`createAnalysis`**. No HTTP request is made. A test for this needs neither backend nor
LLM. The test plan's Risk #4 row bundles "empty/unparseable input" into one phrase;
the code splits them into two entirely different paths.

**On 4d** — this is the one that forces request stubbing. All three failure modes
(converter failure, blank reply, zero-clause valid reply) funnel into
`AnalysisFailedException` (`ContractAnalysisService.java:73-83`) and map to a single
**HTTP 502** with body `{"error":"Failed to analyze contract. Please try again."}`
(`AnalysisExceptionHandler.java:13-17`). A stub `ChatModel` returning fixed valid JSON
can never produce a 502. To assert the frontend's 502 branch
(`new/page.tsx:44-45` → `"Nie udało się przeanalizować umowy. Spróbuj ponownie."`)
the test must intercept the browser's POST and fulfil a 502. Since finding 2 proves
the call is browser-issued, this is possible.

### The frontend surfaces, with locator handles

**Only one route renders results.** A grep for `clauses|negotiationPoints|riskLevel`
matched only `analyses/[id]/page.tsx`, `lib/analyses.ts`, `lib/risk.ts`. There is no
shared result component. The dashboard shows title, date, and a status badge only —
**no clauses**. So "the disclaimer must be visible wherever a result is shown"
constrains exactly one page today. That materially shrinks 4e.

**The two disclaimers are different strings in different elements** — a single shared
locator will not work:

- `analyses/new/page.tsx:103-105` — a bare `<p>`, no role:
  `"Falcon dostarcza analizę pomocniczą, a nie poradę prawną."`
- `analyses/[id]/page.tsx:162-168` — an shadcn `Alert` (hard-codes `role="alert"` at
  `components/ui/alert.tsx:28-31`) with `AlertTitle` `"To nie jest porada prawna"`.

**`getByRole('alert')` is ambiguous on `/analyses/[id]`** — the not-found state
(`[id]/page.tsx:137`) is also an `Alert` (destructive variant). Disambiguate by title
text.

**Error sink.** All four error messages on `/analyses/new` render through one
`<p className="text-sm text-destructive">` at `new/page.tsx:88` (no role). The messages
are distinct: empty input (`:32-35`), backend 400 (`:42-43`), backend 502 (`:44-45`),
unexpected (`:47`).

**Progress feedback** (`new/page.tsx:89-110`): button label flips to `"Analizowanie…"`
and disables; a panel shows `"Analizuję umowę…"`. Note the real ellipsis U+2026, not
three dots. Against a stubbed backend this may race away — prefer waiting on
`waitForURL(/\/analyses\/\d+$/)`.

**Auth redirect** (`proxy.ts:4-14`): `NextResponse.redirect(new URL("/login", ...))`
when the `JSESSIONID` cookie is absent, matching `/dashboard/:path*` and
`/analyses/:path*`. A server-side 307. Pages also carry a client-side `router.push("/login")`
fallback on a 401 (`dashboard/page.tsx:40`, `[id]/page.tsx:50`), but the middleware
fires first for an unauthenticated visitor.

**Form handles.** `getByLabel("Tytuł")`, `getByLabel("Treść umowy")`,
`getByRole('button', {name: "Analizuj umowę"})` (`new/page.tsx:68,78,108-110`).
On `/login` and `/register`, "Zaloguj się" / "Zarejestruj się" appear **both** as a
CardTitle heading and as a control — always qualify with `getByRole('button'|'link')`.

### The backend seam, precisely

The test fixtures are `@TestConfiguration` static nested classes exposing
`@Bean @Primary ChatModel` as a **real lambda** (`AnalysisFlowTest.java:294-304`;
`ClassificationContractTest.java:195-205`), or a mutable variant with a settable
`content` field for parameterized cases (`ConverterRobustnessTest.java:159-183`).
They live only on the test classpath, so `spring-boot:run` cannot see them today.

Two facts make a profile-scoped override clean:

- `ContractAnalysisService` injects `ChatClient.Builder`, and Spring AI constructs
  that `Builder` from the single `ChatModel` bean. A `@Primary ChatModel` therefore
  overrides the real model **without touching `ContractAnalysisService`** — the same
  mechanism the tests already depend on.
- There are **zero Spring profiles in the project today**. No `application-*.properties`,
  no `@Profile` annotation anywhere in `src/main/java`. This is greenfield, with no
  precedent to match and nothing to break.

**The boot blocker.** `application.properties:6-10` reads
`spring.ai.openai.api-key=${OPENROUTER_API_KEY}` — an unresolvable placeholder with no
default. The app **fails at context startup** without the env var, and it fails *even
with* a `@Primary` stub, because the autoconfigured `OpenAiChatModel` bean is still
constructed and still binds that property. Any e2e profile must supply a dummy key,
exactly as `src/test/resources/application.properties:3` does (`...api-key=test`).

**The stub need not ship.** `spring-boot:run` supports `-Dspring-boot.run.useTestClasspath=true`
(default `false`, since 1.3), documented for running "in a test mode that uses stub
classes"; `repackage` does not add test classes to the jar. So a
`@Configuration @Profile("e2e")` class under `src/test/java` is reachable by a locally-run
app and absent from production. One caveat the plan must respect: `@TestConfiguration`
is excluded from component scanning, so the e2e stub must be a plain `@Configuration`,
not a `@TestConfiguration`.

**Input-insensitivity.** A single static lambda returns the same classification for
every contract. That is fine for 4e (one happy-path result page) but means a spec that
pastes different contracts expecting different risk levels will not get them.

### What the existing specs actually depend on

No shared fixture exists — registration and seeding are copy-pasted across all three
(`analysis-history.spec.ts:18-39`, `clause-decision.spec.ts:30-49`,
`delete-analysis.spec.ts:22-43`), and `CONTRACT_TEXT` is byte-identical in all three.

Each seeds by pasting and waiting on the live model, with `waitForURL(/\/analyses\/\d+$/,
{ timeout: 30_000 })` — the three 30-second waits are the entire flakiness and cost surface.

Only **one** assertion anywhere is tied to an LLM-assigned value:
`clause-decision.spec.ts:53,63-65` locates the decision group by the accessible name
`/Automatyczne przedłużenie/`, the Polish label for the `AUTO_RENEWAL` risk type the
model must assign. Its own header (`:21-23`) documents the fragility. **A deterministic
reply pins `AUTO_RENEWAL` and this locator becomes sound** — the retrofit strengthens
the test rather than forcing a rewrite.

`analysis-history.spec.ts` and `delete-analysis.spec.ts` assert only on the
user-supplied `title` and the not-found text. They survive deterministic seeding with
**no assertion changes at all**.

Both surviving specs deliberately avoid clause count and positional locators, because
`Analysis.clauses` has no `@OrderBy` and row order is not reload-stable.

`playwright.config.ts` has no `webServer` (assumes both servers already up),
`retries: 0`, and `trace: "on-first-retry"` — which with zero retries means **traces are
never captured**.

## Code References

- `frontend/src/lib/api.ts:1,12-15,21` — the single browser `fetch`; `NEXT_PUBLIC_API_BASE_URL`; `document.cookie`
- `frontend/src/app/analyses/new/page.tsx:28-52` — submit handler; `:32-35` client-side empty guard; `:88` error sink; `:103-105` disclaimer (bare `<p>`)
- `frontend/src/app/analyses/[id]/page.tsx:162-168` — disclaimer `Alert`; `:137` not-found `Alert` (role collision)
- `frontend/src/proxy.ts:4-14` — Next.js 16 middleware; 307 to `/login`; matcher at `:13`
- `frontend/src/components/ui/alert.tsx:28-31` — `role="alert"` hard-coded
- `backend/.../analysis/ContractAnalysisService.java:59-63` — injects `ChatClient.Builder`; `:73-83` failure funnel + empty-clause guard
- `backend/.../analysis/AnalysisExceptionHandler.java:13-17` — every `AnalysisFailedException` → 502
- `backend/src/main/resources/application.properties:6-10` — `${OPENROUTER_API_KEY}` placeholder, no default (boot blocker)
- `backend/src/test/resources/application.properties:3` — dummy `api-key=test`
- `backend/.../analysis/AnalysisFlowTest.java:294-304` — `@Bean @Primary ChatModel` lambda + `FIXED_JSON` at `:54-72`
- `backend/.../analysis/ConverterRobustnessTest.java:159-183` — mutable `ChatModel` variant
- `frontend/e2e/clause-decision.spec.ts:53,63-65` — the only LLM-value-dependent assertion
- `frontend/playwright.config.ts:9-22` — no `webServer`; `retries: 0`
- `.github/workflows/ci.yml` — frontend job is `pnpm lint` + `pnpm build` only

## Architecture Insights

**The `@Primary ChatModel` seam is load-bearing and already proven.** Overriding
`ChatModel` (not `ChatClient`, not `ChatClient.Builder`) keeps the real `ChatClient`,
`BeanOutputConverter`, and `.entity()` in the path. The archive records *why*: an
unstubbed Mockito `@MockBean ChatClient` NPEs on `chatModel.getOptions().mutate()`
(`context/archive/2026-07-06-testing-classification-pipeline-isolation/plan.md:49-54`).
An e2e profile that reuses this seam inherits a pattern the team has already debugged.

**Fidelity ordering of the candidate seams**, now that browser-side is confirmed:
a stubbed `ChatModel` behind a profile keeps the HTTP layer, security, converter, JPA,
and Postgres all real, and fakes only the network call to OpenRouter. Request
interception (`page.route()`) fakes the *entire backend*, so a spec seeded that way
proves nothing about persistence — which would destroy `clause-decision`'s meaning,
since that test exists to prove a decision *persists across a reload*. Interception is
right for 4d (asserting the frontend's reaction to a 502) and wrong for seeding.

**Test scaffolding does not have to be a production liability.** The instinct to put
`@Profile("e2e")` in `src/main` is what makes people reject the profile option. The
`useTestClasspath` flag removes the tradeoff.

## Historical Context (from prior changes)

The decisive document is the S-03 plan review, which flagged this before it happened:

- `context/archive/2026-07-08-analysis-history/reviews/plan-review.md:26-43` — finding
  **F1 (CRITICAL)**: "A browser test driving paste→submit would be non-deterministic,
  cost money, ~15s — violating the PRD test-determinism guardrail and CLAUDE.md's 'the
  real LLM is never called in tests.' This is a harness-shaping decision, not deferrable
  to implementation." It offered **Fix A ⭐ Recommended** (out-of-band DB/seed-hook
  seeding) and **Fix B** (an `e2e` Spring profile with a stub `ChatModel` bean +
  `application-e2e.properties`, "highest-fidelity E2E").
- The finding was marked **ACCEPTED** with resolution deferred to implementation
  (`plan-review.md:43`).
- `context/archive/2026-07-08-analysis-history/reviews/impl-review.md:56` — the only
  trace of the actual decision: the real-LLM strategy was "discussed and approved with
  the user mid-session." **No written rationale exists anywhere.**
- It then hardened by imitation: `context/archive/2026-07-09-clause-decision-status/plan.md:278`
  instructs "Mirror `frontend/e2e/analysis-history.spec.ts` **exactly in style**," and
  `:282` concedes it "seeds through the real UI against the live LLM… a local-only gate."

Consequences already recorded in the archive:

- `context/archive/2026-07-08-analysis-history/reviews/impl-review.md:26` — the reviewer
  **declined to re-run the e2e suite** because it is "a real, paid LLM call each time,"
  and relied on the implementation session's runs instead. The suite is already too
  expensive to verify with.
- `context/archive/2026-07-10-delete-analysis/plan.md:349-353` — "**These are hygiene,
  not a gate.** … Playwright is not wired into CI and cannot be without a `webServer`
  block and a live LLM key … Any claim that 'CI covers delete' must rest on the four
  integration tests above."
- `context/archive/2026-07-06-ci-build-and-test/plan.md:30-36` — backend CI needs no
  secrets precisely because "the test profile hardcodes a dummy `spring.ai.openai.api-key=test`
  … and the LLM is mocked in-process." The e2e profile would extend this exact trick.

**Genuinely settled — do not relitigate:** the `@Primary` real-lambda `ChatModel` fixture
and the Mockito NPE reason; the `negotiation_points.clause_id` two-phase teardown (made
redundant by S-04's changeset 004 flipping the FK to `ON DELETE SET NULL`); `matchClauseId`
prefix-matching, and the rule that linkage is asserted via a non-null `clauseId`, never by
string-equality on `clauseText`.

`context/foundation/lessons.md` does not exist.

## Related Research

- `context/archive/2026-07-06-testing-classification-pipeline-isolation/` — rollout Phase 2; established the backend mocking seam this change extends to the browser
- `context/archive/2026-07-08-analysis-history/reviews/plan-review.md` — the CRITICAL finding this change finally resolves
- `context/foundation/test-plan.md` §2 Risk #4 + §3 Phase 3

## Open Questions

1. **Which fixed JSON should the e2e stub return?** `AnalysisFlowTest`'s `FIXED_JSON`
   (`:54-72`) is a single HIGH/AUTO_RENEWAL clause with one linked negotiation point;
   `ClassificationContractTest` uses a 3-clause reply. `clause-decision.spec.ts` needs a
   clause whose risk type renders as "Automatyczne przedłużenie", and its locator trips
   strict mode if two clauses share a risk type. The 3-clause reply with distinct risk
   types is the likely fit, but the exact payload must be chosen so all four specs share it.

2. **Should the e2e stub branch on prompt content?** A static reply cannot serve a test
   that needs two different classifications. Nothing in Risk #4 currently requires that.
   Prefer static until a spec demands otherwise.

3. **CI wiring cost is unestimated.** Making the suite CI-runnable needs: Postgres (service
   container or compose), a JDK + `spring-boot:run -Dspring-boot.run.useTestClasspath=true
   -Dspring-boot.run.profiles=e2e` in the background, `pnpm dev`/`pnpm start`, and a
   Playwright `webServer` array with health checks. This may deserve its own plan sub-phase,
   and it is worth deciding whether the browser suite runs on every PR or only on
   `frontend/**` changes (the existing `dorny/paths-filter` job already computes that).

4. **`trace: "on-first-retry"` with `retries: 0` captures nothing.** Once the suite is
   deterministic, `retries: 0` is correct (a retry would mask a real bug), but the trace
   setting should change to `on` or `retain-on-failure` or it is dead configuration.

5. **The zero-clause 502 conflation is a product gap, not a test gap** — pinned by
   `ConverterRobustnessTest` and documented in test-plan §7. A "clean contract" e2e scenario
   is impossible until that becomes a success state. Out of scope here; do not let a spec
   quietly assert the 502 as if it were correct product behavior.
