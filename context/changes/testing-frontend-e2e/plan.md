# Deterministic browser E2E + disclaimer guardrail coverage — Implementation Plan

## Overview

Rollout Phase 3 of `context/foundation/test-plan.md`. Give the browser layer a
deterministic backend, retrofit the three existing Playwright specs off the live
OpenRouter model, cover Risk #4 (paste→result regression, disappearing "not legal
advice" disclaimer, silent empty result on bad input), and wire the suite into CI
as a real gate.

## Current State Analysis

Playwright landed **outside** the test rollout, as a side-effect of the S-02/S-03/S-04
slices. It works, and it is untrustworthy:

- Three specs (`frontend/e2e/{analysis-history,clause-decision,delete-analysis}.spec.ts`)
  each seed their fixture by pasting a contract and waiting up to 30 seconds for a
  **live OpenRouter classification**. Every run costs money and can fail on model
  variance.
- CI never executes them. `.github/workflows/ci.yml`'s frontend job is `pnpm lint` +
  `pnpm build`. This accident is the only reason CI is still free and deterministic.
- Risk #4 is **uncovered**. No spec asserts the disclaimer, the empty-input explanatory
  state, the 502 explanatory state, or the auth redirect. The disclaimer strings exist
  in the app and nothing guards them.
- `playwright.config.ts` has no `webServer` (assumes both servers already running),
  `retries: 0`, and `trace: "on-first-retry"` — which together mean **traces are never
  captured**.

This was not a reasoned architecture. The S-03 plan review flagged live-LLM e2e as
**CRITICAL** before it was built and recommended deterministic seeding; the finding was
marked ACCEPTED, then bypassed by a verbal mid-session decision with no recorded
rationale. Later specs copied the pattern as house style. This change adopts the prior
review's guidance rather than contradicting it.

## Desired End State

`pnpm test:e2e` runs seven specs against a backend that never touches the network,
completes in seconds rather than minutes, costs nothing, and passes deterministically.
CI runs it on any PR touching `frontend/**` or `backend/**`. The "not legal advice"
disclaimer cannot silently disappear from either render site without a red build.

Verify by: unsetting `OPENROUTER_API_KEY` entirely, booting the backend with the `e2e`
profile, and running the full suite green. If any spec needs the key, the retrofit is
incomplete.

### Key Discoveries:

- **Overriding `ChatModel` is sufficient and requires no service change.**
  `ContractAnalysisService.java:59-63` injects `ChatClient.Builder`, and Spring AI
  constructs that builder from the single `ChatModel` bean in the context. A
  `@Primary ChatModel` wins by the same mechanism the existing backend tests rely on.
- **`spring-boot.run.additional-classpath-elements=target/test-classes`** puts the stub on
  the runtime classpath, and `repackage` never adds test classes to the jar — the stub
  lives under `src/test/java` and stays out of production. **Not**
  `spring-boot.run.useTestClasspath=true`: that flag is documented for exactly this
  purpose (default `false`, since 1.3, "run in a test mode that uses stub classes"), but
  verified empirically **not** to add `target/test-classes` to the runtime classpath on
  spring-boot-maven-plugin 4.0.7 — confirmed by inspecting the forked process's classpath
  argfile directly. `additional-classpath-elements` is kebab-case; the camelCase property
  key silently no-ops.
- **`@TestConfiguration` is excluded from component scanning.** The e2e stub must be a
  plain `@Configuration`, or the profile will activate and silently do nothing.
- **Boot blocker:** `application.properties:6-10` reads `spring.ai.openai.api-key=${OPENROUTER_API_KEY}`
  with no default. The app fails at *context startup* without it — and fails **even with**
  a `@Primary` stub, because the autoconfigured `OpenAiChatModel` bean is still
  constructed and still binds that property.
- **`MULTI_CLAUSE_JSON` (`ClassificationContractTest.java:51-86`) has exactly the right
  shape**: 3 clauses with *distinct* risk types (HIGH/AUTO_RENEWAL, MEDIUM/PENALTY,
  LOW/PAYMENT) and 2 negotiation points, leaving the LOW clause unlinked.
- **`POST /api/analyses` returns 201 with the full `Analysis` body**, not an id
  (`lib/analyses.ts:54-61`). The frontend never re-fetches after create. Any `page.route()`
  stub must fulfil the complete shape.
- **Every backend call is browser-issued.** One `fetch` in the whole frontend
  (`lib/api.ts:21`), client-side, cross-origin to `localhost:8080`. No server actions, no
  route handlers, no rewrites proxy. `page.route()` interception is therefore viable.
- **The two disclaimers are different strings in different elements.** A bare `<p>` on
  `/analyses/new:103-105`; an shadcn `Alert` (`role="alert"`) with title "To nie jest
  porada prawna" on `/analyses/[id]:162-168`. No single locator matches both.
- **`getByRole('alert')` is ambiguous on `/analyses/[id]`** — the not-found state
  (`[id]/page.tsx:137`) is also an `Alert`.
- **The decision group's accessible name embeds the risk-type label**
  (`[id]/page.tsx:214-218`): `` `Decyzja: klauzula ${index+1} (${RISK_TYPE_LABEL[clause.riskType]})` ``.
- **`Analysis.clauses` has no `@OrderBy`** (`Analysis.java:43-44`). Clause order is not
  reload-stable. Never use positional locators.
- Polish UI strings use a real ellipsis `…` (U+2026), not three dots.

## What We're NOT Doing

- **No live-LLM spec of any kind.** Not tagged, not opt-in, not a parallel project. Real
  model quality stays where the test plan put it: Risk #6's optional offline eval (§3
  Phase 5). Conflating a wiring test with a model-judgment test is the trap §2 Risk #6
  names explicitly.
- **No `storageState` auth reuse.** Premature for a 7-spec suite, and it would undermine
  the auth-redirect test.
- **No frontend unit-test runner.** §4 of the test plan covers frontend risks via browser
  E2E by cost × signal.
- **No fix to the zero-clause 502 conflation.** A valid reply finding no risky clauses
  still returns 502. That is a product gap pinned by `ConverterRobustnessTest` and
  documented in test-plan §7 — a feature change, not a test gap. No spec may assert the
  502 as if it were correct product behavior for a clean contract.
- **No git hooks or pre-commit.** Test-plan §3 Phase 4.
- **No `@OrderBy` on `Analysis.clauses`.** Tempting once replies are deterministic, but it
  is a production schema/JPA change to serve tests. Locators key on risk type; leave it.

## Implementation Approach

Risk #4 does not decompose into "one seam". It decomposes into five assertions needing
five different amounts of backend, and the cheapest mechanism differs per row. This is
the plan's organizing insight — forcing one seam across all of them would overpay on
three tests and make one impossible.

| # | Assertion | Backend needed | Mechanism |
|---|-----------|----------------|-----------|
| 4a | Unauthenticated visitor → `/login` | none (no session cookie) | `page.goto`; `proxy.ts` 307 |
| 4b | Disclaimer visible on both render sites | auth only | static render assertion |
| 4c | Empty contract text → explanatory state | none beyond auth | client guard returns before `fetch` |
| 4d | Backend 502 → explanatory state | **stub only** | `page.route()` fulfils 502 |
| 4e | Result page: clauses + points + linkage | real saved analysis | `e2e` profile |

Interception fakes the *entire backend*; a stubbed `ChatModel` fakes *one network call*.
That is why 4d uses `page.route()` and seeding never does — `clause-decision.spec.ts`
exists to prove a decision persists across a reload, and a route-stubbed seed would leave
no persistence to prove.

## Critical Implementation Details

**Boot ordering.** The `@Primary` stub does not prevent the autoconfigured
`OpenAiChatModel` bean from being constructed, so `spring.ai.openai.api-key` must still
resolve. `application-e2e.properties` must supply a dummy value or the app dies at
startup, before any request, with a placeholder-resolution error that looks nothing like
an AI problem. Expect to lose time here if it is skipped.

**`@TestConfiguration` vs `@Configuration`.** `@TestConfiguration` is annotated
`@TestComponent`, which the default component filter excludes. A stub written as
`@TestConfiguration` will be silently ignored under `useTestClasspath` — the app boots,
the profile is active, and the real OpenRouter model still answers. This failure is
quiet and expensive; assert on it in Phase 1's manual verification by confirming the app
serves a classification with the key unset.

**Fixture sharing across source roots.** `MULTI_CLAUSE_JSON` currently lives inside
`ClassificationContractTest`. Extracting it to a shared test-scope constant means that
suite must be re-run to prove nothing broke — it is a behavior-preserving move, but the
existing assertions depend on the exact payload.

## Phase 1: Deterministic e2e backend

### Overview

Make `SPRING_PROFILES_ACTIVE=e2e` serve fixed classifications with no network access and
no API key, without shipping the stub in the production jar.

### Changes Required:

#### 1. Shared fixture payload

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/ClauseAnalysisFixtures.java` (new)

**Intent**: Hoist `MULTI_CLAUSE_JSON` out of `ClassificationContractTest` so the e2e stub
and the contract test share one definition of "what a model reply looks like". A second
copy would drift.

**Contract**: A package-private final class exposing `static final String MULTI_CLAUSE_JSON`,
byte-identical to the current constant at `ClassificationContractTest.java:51-86`.
`ClassificationContractTest` references the shared constant instead of its own.

#### 2. The e2e stub bean

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/E2eChatModelConfig.java` (new)

**Intent**: Supply a `@Primary ChatModel` under the `e2e` profile that returns
`MULTI_CLAUSE_JSON` for every prompt, keeping the real `ChatClient`, `BeanOutputConverter`,
`.entity()`, JPA, and Postgres in the path.

**Contract**: A plain `@Configuration(proxyBeanMethods = false) @Profile("e2e")` class —
**not** `@TestConfiguration` — exposing `@Bean @Primary ChatModel` as a real lambda, per
the pattern at `AnalysisFlowTest.java:294-304`. Never a Mockito mock: an unstubbed mock
NPEs on `chatModel.getOptions().mutate()`.

```java
@Configuration(proxyBeanMethods = false)   // NOT @TestConfiguration — see Critical Details
@Profile("e2e")
class E2eChatModelConfig {
    @Bean @Primary
    ChatModel e2eChatModel() {
        var response = new ChatResponse(List.of(new Generation(
                new AssistantMessage(ClauseAnalysisFixtures.MULTI_CLAUSE_JSON))));
        return prompt -> response;
    }
}
```

#### 3. The e2e profile properties

**File**: `backend/src/main/resources/application-e2e.properties` (new)

**Intent**: Let the context boot with `OPENROUTER_API_KEY` unset. The autoconfigured
`OpenAiChatModel` bean still binds the key even though the stub is primary.

**Contract**: Overrides `spring.ai.openai.api-key` with a dummy literal, mirroring
`src/test/resources/application.properties:3`. Layers over the base file — no other keys
needed. This file is in `src/main/resources` (it is inert config, not code) while the stub
bean is in `src/test/java`; only the bean must stay out of the jar.

#### 4. Document the run command

**File**: `CLAUDE.md`

**Intent**: The two-flag run command is not discoverable. Record it beside the existing
local-run flow.

**Contract**: Add the e2e-profile invocation to the backend commands block:
`./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e -Dspring-boot.run.additional-classpath-elements=target/test-classes`,
noting it needs no `OPENROUTER_API_KEY`.

### Success Criteria:

#### Automated Verification:

- Backend suite still passes after the fixture extraction: `cd backend && ./mvnw test`
- `ClassificationContractTest` specifically passes: `./mvnw test -Dtest=ClassificationContractTest`
- Full build is clean: `./mvnw clean package`

#### Manual Verification:

- With `OPENROUTER_API_KEY` **unset**, `./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e -Dspring-boot.run.useTestClasspath=true` boots successfully
- Posting a contract through the running app returns 201 with 3 clauses and 2 negotiation points, with no network call to OpenRouter
- Booting **without** the `e2e` profile and without the key still fails (proves the profile, not a global default, is what changed)
- Confirm `target/*.jar` does **not** contain `E2eChatModelConfig.class`

**Implementation Note**: Pause here for manual confirmation before Phase 2. The "silently
ignored `@TestConfiguration`" trap is only caught by the manual checks above.

---

## Phase 2: Fixture module + retrofit the three existing specs

### Overview

Extract the copy-pasted setup into a shared module and cut all three specs off the live
model. No new assertions in this phase — the specs must keep proving what they proved
before, just deterministically.

### Changes Required:

#### 1. Shared e2e fixtures

**File**: `frontend/e2e/fixtures.ts` (new)

**Intent**: One place for "register a fresh user" and "seed an analysis". The seeding
block is currently duplicated verbatim at `analysis-history.spec.ts:18-39`,
`clause-decision.spec.ts:30-49`, `delete-analysis.spec.ts:22-43`.

**Contract**: Exports `registerFreshUser(page, prefix)` returning `{ email, password }`
and `seedAnalysis(page, title)` returning `{ url }`. Both preserve timestamp-suffixed
unique ids so `fullyParallel: true` stays safe. `seedAnalysis` drives the real
paste→submit UI (that is the point — it exercises the flow) and waits on
`waitForURL(/\/analyses\/\d+$/)` with a normal timeout, not 30 s.

#### 2. Retrofit the three specs

**Files**: `frontend/e2e/analysis-history.spec.ts`, `frontend/e2e/clause-decision.spec.ts`,
`frontend/e2e/delete-analysis.spec.ts`

**Intent**: Consume the fixtures; delete the live-LLM header comments, the 30-second
timeouts, and `clause-decision`'s "Known limitation" note about duplicate risk types —
the fixed reply now guarantees exactly one `AUTO_RENEWAL` clause.

**Contract**: `analysis-history` and `delete-analysis` assert only on the user-supplied
title and the not-found text; **their assertions do not change at all**.
`clause-decision`'s `getByRole("group", { name: /Automatyczne przedłużenie/ })` locator
survives unchanged and becomes sound. `CONTRACT_TEXT` moves into `fixtures.ts` (it is
now only an input to a stub that ignores it, but a realistic contract keeps the paste
flow honest).

The header comments must be **replaced, not deleted** — each should now record that the
spec runs against the `e2e` profile and requires no API key.

### Success Criteria:

#### Automated Verification:

- With the `e2e`-profile backend and `pnpm dev` running, all three specs pass: `cd frontend && pnpm test:e2e`
- Lint passes: `pnpm lint`
- No spec references a 30-second timeout: `grep -r "30_000" frontend/e2e/` returns nothing
- No spec mentions the live model: `grep -ri "openrouter\|live.*llm" frontend/e2e/` returns nothing

#### Manual Verification:

- The suite completes in seconds, not minutes
- Running the suite with `OPENROUTER_API_KEY` unset still passes end to end
- Re-running the suite twice back to back passes both times (no leaked state between runs)

**Implementation Note**: Pause for manual confirmation before Phase 3, so new specs are
never written against the old pattern.

---

## Phase 3: Risk #4 coverage

### Overview

The phase this rollout exists for. Five assertions, using the cheapest mechanism per row
from the Implementation Approach table.

### Changes Required:

#### 1. Auth redirect (4a)

**File**: `frontend/e2e/auth-redirect.spec.ts` (new)

**Intent**: Prove `proxy.ts`'s default-deny holds for the protected routes. An
unauthenticated visitor must never see an analysis.

**Contract**: With no session cookie, `page.goto` to `/dashboard`, `/analyses/new`, and a
plausible `/analyses/1` each land on `/login`. Assert via `waitForURL("**/login")`. Uses
no fixture — registering a user would defeat the test.

#### 2. Disclaimer guardrail (4b)

**File**: `frontend/e2e/disclaimer.spec.ts` (new)

**Intent**: The disclaimer is a named PRD guardrail. It must be visible wherever a result
is shown, and on the input page. Today nothing guards either string.

**Contract**: Two assertions against **different** locators, because the two sites differ:
- `/analyses/new` → `getByText("Falcon dostarcza analizę pomocniczą, a nie poradę prawną.")` (a bare `<p>`)
- `/analyses/[id]` (via `seedAnalysis`) → `getByText("To nie jest porada prawna")` (an `Alert` title)

Do **not** use `getByRole('alert')` on `/analyses/[id]` — the not-found state is also an
`Alert` (`[id]/page.tsx:137`) and the locator is ambiguous.

#### 3. Empty-input explanatory state (4c)

**File**: `frontend/e2e/analysis-input-validation.spec.ts` (new)

**Intent**: Prove empty input yields an explanatory state, not a silent empty result. This
guard is **client-side** (`new/page.tsx:32-35`) and returns before any `fetch`.

**Contract**: Logged in, submit `/analyses/new` with an empty contract textarea; assert
`getByText("Wklej treść umowy, aby rozpocząć analizę.")` is visible and the URL has not
changed. Assert **no** POST was issued — this is what makes it a client-guard test rather
than a coincidence.

#### 4. Backend-failure explanatory state (4d)

**File**: `frontend/e2e/analysis-input-validation.spec.ts` (same file, second test)

**Intent**: Prove the frontend's 502 branch (`new/page.tsx:44-45`) renders an explanatory
state. The `e2e` stub returns valid JSON and can never produce a 502, so this must be
driven by interception — viable because every backend call is browser-issued.

**Contract**: `page.route("**/api/analyses", …)` fulfilling `502` with
`{"error":"Failed to analyze contract. Please try again."}` — the exact body from
`AnalysisExceptionHandler.java:13-17`. Assert `getByText("Nie udało się przeanalizować umowy. Spróbuj ponownie.")`.
Route only the POST; do not blanket-stub the API.

The route must be scoped so it does not swallow `GET /api/analyses` (the dashboard list).

#### 5. Paste→result page (4e)

**File**: `frontend/e2e/analysis-result.spec.ts` (new)

**Also required — discovered during implementation**: `frontend/src/app/analyses/[id]/page.tsx`
needs one attribute added: `data-testid={\`clause-${clause.id}\`}` on each clause's
`CardContent`. Reason: the decision group (`role="group"`, risk-type-labeled) and the
negotiation-point block (`"Punkt do negocjacji"`) are **siblings** under a bare, roleless
`<Card>`/`<CardContent>` div (confirmed via `card.tsx` — plain `<div data-slot="card">`,
no ARIA role). With no accessible landmark scoping a clause's card as a unit, only
*aggregate* facts are provable via role/label/text alone ("2 negotiation-point blocks
exist somewhere") — not *which clause* each one renders under, so a linkage-swap
regression (AUTO_RENEWAL's card showing PENALTY's point) would slip past. This is exactly
the case CLAUDE.md's locator rule carves out `getByTestId` for ("only when accessibility
attributes are ambiguous"). Confirmed with the user before making this production-code
addition.

**Intent**: The core of Risk #4 — prove the result view renders a real breakdown, not an
empty shell. This is the only assertion needing a genuinely saved analysis.

**Contract**: Via `seedAnalysis`, land on `/analyses/[id]` and assert against the known
fixture: all three clauses render; the HIGH/AUTO_RENEWAL and MEDIUM/PENALTY clauses each
show a negotiation point; the LOW/PAYMENT clause shows **none** (selective linkage — the
fixture's unlinked clause is what makes this assertable). Assert risk-level badges by
their Polish labels ("Wysokie", "Średnie", "Niskie").

Locate clauses by risk-type accessible name, never by index — `Analysis.clauses` has no
`@OrderBy` and order is not reload-stable.

Assert the negative too: a HIGH clause never renders as "Niskie". A test that only checks
the happy label passes against a downgrade bug.

### Success Criteria:

#### Automated Verification:

- All seven specs pass: `cd frontend && pnpm test:e2e`
- Lint passes: `pnpm lint`
- Suite passes with `OPENROUTER_API_KEY` unset
- No CSS/XPath selectors introduced: `grep -rE "locator\(['\"][.#]" frontend/e2e/` returns nothing
- No `waitForTimeout`: `grep -r "waitForTimeout" frontend/e2e/` returns nothing

#### Manual Verification:

- Deleting the disclaimer from `analyses/[id]/page.tsx` turns `disclaimer.spec.ts` red (the guardrail actually guards)
- Deleting the disclaimer from `analyses/new/page.tsx` also turns it red (both sites, not just one)
- Changing the fixture's AUTO_RENEWAL clause to `riskLevel: "LOW"` turns `analysis-result.spec.ts` red (the never-downgraded assertion has teeth)

**Implementation Note**: The three manual checks are mutation tests. A spec that stays
green when the disclaimer is deleted is worse than no spec — it certifies a guardrail it
does not check. Pause and run them.

---

## Phase 4: CI-runnable harness

### Overview

Wire the now-deterministic suite into CI, closing the §5 gate that has been unmeetable
since S-03.

### Changes Required:

#### 1. Playwright config

**File**: `frontend/playwright.config.ts`

**Intent**: Let Playwright own server lifecycle so CI (and a fresh clone) can run the
suite with one command. Fix the dead trace setting.

**Contract**: Add a `webServer` array — the backend (`./mvnw spring-boot:run` with the `e2e`
profile and `-Dspring-boot.run.additional-classpath-elements=target/test-classes` — **not**
`useTestClasspath`, per Phase 1's Key Discoveries, `cwd: ../backend`, `url` on the actuator
health endpoint) and the frontend (`pnpm dev` locally, the built app in CI), both with
`reuseExistingServer: !process.env.CI`. Change `trace` to
`"retain-on-failure"`: with `retries: 0`, `"on-first-retry"` captures nothing. Keep
`retries: 0` — a deterministic suite that needs a retry is reporting a real bug.

#### 2. CI job

**File**: `.github/workflows/ci.yml`

**Intent**: Run the browser suite on any PR that could break it, without taxing
docs-only commits.

**Contract**: A new `e2e` job, `needs: changes`, guarded by
`if: needs.changes.outputs.frontend == 'true' || needs.changes.outputs.backend == 'true'`
(both filters already exist). A `postgres:18` service container matching `compose.yaml`;
`setup-java` (temurin 25) and `setup-node` + corepack, mirroring the existing jobs' cache
config. No `OPENROUTER_API_KEY` secret — the whole point. Upload the Playwright report as
an artifact on failure.

The backend must reach Postgres via the service container rather than
`spring-boot-docker-compose`; set the datasource URL through environment variables in the
job. Do not add Docker Compose to CI.

### Success Criteria:

#### Automated Verification:

- `pnpm test:e2e` from a clean shell with **no servers running** boots both and passes
- `pnpm test:e2e` with servers already running reuses them (does not double-boot)
- The workflow parses: `gh workflow view ci.yml` (or `act -n` if available)
- A pushed branch touching `frontend/**` triggers the `e2e` job and it passes
- A branch touching only `context/**` does **not** trigger the `e2e` job

#### Manual Verification:

- CI run contains no `OPENROUTER_API_KEY` and no OpenRouter network egress
- A deliberately broken assertion produces a red build with a downloadable trace artifact
- `e2e` job wall-clock is acceptable (target: under ~5 minutes)

**Implementation Note**: This is the phase most likely to overrun. If the two-server
`webServer` orchestration fights CI, land the config change (item 1) and pause — a
locally-runnable suite is still a real improvement, and the ci.yml job can be finished
without blocking Phase 5.

---

## Phase 5: Close the rollout phase

### Overview

Update the test plan so the next reader inherits the pattern rather than rediscovering it.

### Changes Required:

#### 1. Cookbook pattern

**File**: `context/foundation/test-plan.md` §6.6

**Intent**: §6.6 currently reads "TBD — see §3 Phase 3". Replace with the real pattern.

**Contract**: Record: location `frontend/e2e/`; the `e2e`-profile backend and its run
command; `fixtures.ts` as the seeding entry point; the rule that seeding uses the profile
and error-state tests use `page.route()`; the `getByRole('alert')` ambiguity on
`/analyses/[id]`; the no-`@OrderBy` positional-locator hazard; and the U+2026 ellipsis.

#### 2. Status and gates

**File**: `context/foundation/test-plan.md` §3, §4, §5

**Intent**: Reflect what now exists.

**Contract**: §3 Phase 3 Status → `complete`, Change folder → the archive path. §4's
Playwright row drops the "seeds via the live LLM / not run by CI" caveat. §5's
`browser e2e on the paste→result flow` gate: `Wired?` → yes. The §3 strategy-debt note
becomes past tense.

#### 3. Per-rollout-phase note

**File**: `context/foundation/test-plan.md` §6

**Intent**: Capture what this phase taught, per the section's convention.

**Contract**: 2–3 lines on the `@TestConfiguration`-is-not-component-scanned trap, the
api-key placeholder boot blocker surviving a `@Primary` override, and the
interception-vs-profile boundary (route-stubbing seeds would destroy `clause-decision`'s
persistence proof).

### Success Criteria:

#### Automated Verification:

- No `TBD` remains in §6.6: `grep -n "TBD" context/foundation/test-plan.md` shows no §6.6 hit
- Full backend suite and e2e suite both green

#### Manual Verification:

- A fresh agent session asked "how do I add a browser test for a new page?" names `fixtures.ts`, the `e2e` profile, and the role/label locator rule
- §3 Phase 4's goal still reads correctly now that Phase 3 delivered the CI job

---

## Testing Strategy

### Unit Tests:

None added. Frontend has no unit runner by deliberate choice (test-plan §4), and the
backend units in scope are already covered.

### Integration Tests:

Backend integration is unchanged. `ClassificationContractTest` must be re-run after the
fixture extraction — it is a behavior-preserving move whose assertions depend on the exact
payload.

### End-to-end Tests:

Seven specs, all deterministic, all free, all CI-gated after Phase 4:

| Spec | Risk | Backend |
|------|------|---------|
| `auth-redirect.spec.ts` | 4a | none |
| `disclaimer.spec.ts` | 4b | `e2e` profile (seed) |
| `analysis-input-validation.spec.ts` | 4c, 4d | none / `page.route()` |
| `analysis-result.spec.ts` | 4e | `e2e` profile |
| `analysis-history.spec.ts` | S-03 regression | `e2e` profile |
| `clause-decision.spec.ts` | S-02 regression | `e2e` profile |
| `delete-analysis.spec.ts` | S-04 regression | `e2e` profile |

**These become a real gate**, reversing the S-04 plan's "these are hygiene, not a gate".

### Manual Testing Steps:

1. Unset `OPENROUTER_API_KEY`. Boot the backend with the `e2e` profile. Run `pnpm test:e2e`. All seven pass.
2. Delete the disclaimer from `analyses/[id]/page.tsx`. Re-run. `disclaimer.spec.ts` fails.
3. Delete the disclaimer from `analyses/new/page.tsx`. Re-run. `disclaimer.spec.ts` fails.
4. Set the fixture's AUTO_RENEWAL clause to `riskLevel: "LOW"`. Re-run. `analysis-result.spec.ts` fails.
5. Restore all three. Re-run. All seven pass.
6. Boot **without** the `e2e` profile and without the key. The backend fails to start.

Steps 2–4 are mutation tests: they prove the guardrail specs can fail. Skipping them
leaves you with specs that certify a guardrail they never check.

## Performance Considerations

The suite's wall-clock today is dominated by three 30-second live-LLM waits. Removing them
should take the run from minutes to seconds. The new CI cost is server boot, not test
execution: a JVM start plus a Next.js build. Target under ~5 minutes for the `e2e` job;
path-filtering keeps it off docs-only commits.

`fullyParallel: true` stays. Every fixture keeps timestamp-suffixed unique ids, so
parallel workers do not collide.

## Migration Notes

Existing e2e runs leak user accounts (account deletion is not in the MVP). The retrofit
does not change this; the specs already delete the analyses they create. A local dev
database will keep accumulating `e2e-*@example.com` users. In CI the Postgres service
container is discarded per run, so this is a local-only annoyance and out of scope.

## References

- Research: `context/changes/testing-frontend-e2e/research.md`
- Test plan: `context/foundation/test-plan.md` §2 Risk #4, §3 Phase 3
- The finding this change finally resolves: `context/archive/2026-07-08-analysis-history/reviews/plan-review.md:26-43`
- Mocking-seam rationale (Mockito NPE): `context/archive/2026-07-06-testing-classification-pipeline-isolation/plan.md:49-54`
- CI's no-secrets precedent: `context/archive/2026-07-06-ci-build-and-test/plan.md:30-36`
- Stub pattern to mirror: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:294-304`
- `useTestClasspath` docs: https://docs.spring.io/spring-boot/maven-plugin/run.html

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Deterministic e2e backend

#### Automated

- [x] 1.1 Backend suite still passes after the fixture extraction — e110b0d
- [x] 1.2 ClassificationContractTest specifically passes — e110b0d
- [x] 1.3 Full build is clean — e110b0d

#### Manual

- [x] 1.4 Backend boots with the e2e profile and no OPENROUTER_API_KEY — e110b0d
- [x] 1.5 Running app returns 201 with 3 clauses and 2 negotiation points, no network call — e110b0d
- [x] 1.6 Booting without the e2e profile and without the key still fails — e110b0d (did NOT hold as stated; see plan note below — non-blocking, documented not enforced)
- [x] 1.7 Confirm the packaged jar does not contain E2eChatModelConfig.class — e110b0d

### Phase 2: Fixture module + retrofit the three existing specs

#### Automated

- [x] 2.1 All three existing specs pass against the e2e-profile backend — 30789fe
- [x] 2.2 Lint passes — 30789fe
- [x] 2.3 No spec references a 30-second timeout — 30789fe
- [x] 2.4 No spec mentions the live model (grep matches only negation phrasing declaring independence from it — see plan note) — 30789fe

#### Manual

- [x] 2.5 The suite completes in seconds, not minutes — 30789fe
- [x] 2.6 Suite passes with OPENROUTER_API_KEY unset — 30789fe
- [x] 2.7 Two back-to-back runs both pass (no leaked state) — 30789fe

### Phase 3: Risk #4 coverage

#### Automated

- [x] 3.1 All seven specs pass — 926c4e9
- [x] 3.2 Lint passes — 926c4e9
- [x] 3.3 Suite passes with OPENROUTER_API_KEY unset — 926c4e9
- [x] 3.4 No CSS/XPath selectors introduced — 926c4e9
- [x] 3.5 No waitForTimeout introduced — 926c4e9

#### Manual

- [x] 3.6 Deleting the disclaimer from analyses/[id]/page.tsx turns disclaimer.spec.ts red — 926c4e9
- [x] 3.7 Deleting the disclaimer from analyses/new/page.tsx also turns it red — 926c4e9
- [x] 3.8 Downgrading the fixture's AUTO_RENEWAL clause to LOW turns analysis-result.spec.ts red — 926c4e9

### Phase 4: CI-runnable harness

#### Automated

- [x] 4.1 pnpm test:e2e from a clean shell boots both servers and passes — ebdd4d2
- [x] 4.2 pnpm test:e2e reuses already-running servers — ebdd4d2
- [x] 4.3 The workflow parses — ebdd4d2
- [x] 4.4 A branch touching frontend/** triggers the e2e job and it passes (live: run 29102214919, e2e job succeeded in 102s) — ebdd4d2
- [x] 4.5 A branch touching only context/** does not trigger the e2e job (not directly tested — inferred from the identical, already-proven path-filter mechanism shared with the `backend`/`frontend` jobs; accepted without a dedicated push per user direction) — ebdd4d2

#### Manual

- [x] 4.6 CI run contains no OPENROUTER_API_KEY and no OpenRouter egress (static: grep confirms no reference in ci.yml; the run succeeded using only the deterministic e2e-profile backend) — ebdd4d2
- [x] 4.7 A broken assertion produces a red build with a downloadable trace artifact (live: run 29102544025 — "Run e2e suite" failed, "Upload Playwright report" ran and succeeded, playwright-report artifact confirmed present, 1.1MB; reverted in run 29102730314, green again) — ebdd4d2
- [x] 4.8 e2e job wall-clock is under ~5 minutes (live: 102s) — ebdd4d2

### Phase 5: Close the rollout phase

#### Automated

- [x] 5.1 No TBD remains in test-plan §6.6
- [x] 5.2 Full backend suite and e2e suite both green (backend: 59/59 local, just now; e2e: CI runs 29102214919 + 29102730314, both green on main's current code)

#### Manual

- [x] 5.3 A fresh agent session names fixtures.ts, the e2e profile, and the locator rule (verified via a genuinely fresh Agent reading only test-plan.md + CLAUDE.md — correctly named all three, including the useTestClasspath trap)
- [x] 5.4 §3 Phase 4's goal still reads correctly
