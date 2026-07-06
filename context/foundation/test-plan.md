# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-07-05

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. Do not promote to e2e because e2e "feels safer." For Falcon
   specifically: the classification logic belongs in backend integration
   tests with a mocked `ChatClient`; the browser is only asked to prove what
   only the browser can (the disclaimer renders, the flow submits, the redirect
   fires) — not to re-run classification.
2. **User concerns are first-class evidence.** Risks anchored in "the team is
   worried about X, and the failure would surface somewhere in <area>" carry
   the same weight as PRD lines or hot-spot data. Every interview answer in
   §2 is scored on the same axes as a PRD requirement.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from documents,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is produced
   by `/10x-research` during each rollout phase. If the plan and research
   disagree about where the failure lives, research is the ground truth.

Hot-spot scope used for likelihood weighting: `backend/src`, `frontend/src`
(docs, `context/`, and build output excluded).

**Standing caveat on likelihood evidence.** Falcon is an early MVP: the only
domain code that exists (and therefore the only code with git churn) is the
F-01 auth slice. The three highest-risk areas — LLM classification, data-level
isolation, and the entire frontend — have near-zero history *because they are
not built yet*. Their likelihood is drawn from the roadmap ("next up") and the
interview, not from churn. Do not read the absence of hot-spot evidence for
Risks #1–#4 as low likelihood.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|-------------------------|--------|------------|--------------------------------|
| 1 | An authenticated user reaches or mutates **another user's analysis** (cross-user data leak / IDOR). | High | High | PRD Access Control + isolation NFR; roadmap F-01 (the cross-user data-isolation test is *deferred* to S-01); interview Q2 |
| 2 | The classification pipeline **drops or downgrades a genuinely risky clause** given a valid model reply — a risky clause saved as LOW/unflagged, a risky clause with no linked negotiation point, or a half-empty result saved as "complete" (a false all-clear the user signs on). | High | Med-High | interview Q1; PRD US-01 acceptance criteria ("never low"), FR-005/FR-006; roadmap S-01 (the riskiest slice, unbuilt) |
| 3 | Structured-output mapping **fails on ragged model JSON** (wrong enum casing, extra/missing fields, markdown-fenced JSON, empty/unparseable input) → a silent null or half-empty save instead of a controlled explanatory state. | High | Med-High | interview Q3; roadmap S-01 Unknown (structured-output reliability against OpenRouter); tech-stack constraint (verify `.entity()` / `BeanOutputConverter` against Spring AI 2.0 GA) |
| 4 | The frontend **paste→result flow regresses or the "not legal advice" disclaimer disappears**, or empty/unparseable input shows a silent empty result instead of an explanatory state. | High (disclaimer is a named guardrail) | Med-High | interview Q4; PRD NFR (disclaimer visible wherever a result is shown) + US-01 acceptance criteria (empty → explanatory); test base = frontend has zero tests |
| 5 | A **gated endpoint silently becomes reachable without authentication** after a security-config change (default-deny erosion). | High | Medium | interview Q2; hot-spot directory `backend/src/main/java/com/morawski/dev/falcon/auth/` (6 commits/30d); PRD Access Control (permit-list is hand-maintained) |
| 6 | The **real model under-rates a genuinely dangerous clause** (non-deterministic false-negative) — a failure no mocked/deterministic test can catch. | High | Medium (unknown) | interview Q1; PRD + roadmap "riskiest assumption" (an LLM can reliably flag risky clauses) |

Order: #1 (High × High) is protect-first. #2–#4 (High × Med-High) are the core
product-value and guardrail surfaces. #5 (High × Medium) is the "already been
burned"-class regression guard. #6 is High-impact but not deterministically
testable — it is documented here so it is not silently conflated with #2; its
response is an offline eval or a documented limitation, never a CI gate.

**Abuse / security lens.** Falcon has authentication and accepts untrusted user
input (pasted contract text) over sensitive data, so the map carries abuse rows
by design: #1 (authorization/ownership — IDOR) and #5 (authentication boundary)
are the primary ones. Prompt injection (a crafted clause instructing the model
to under-rate itself) is a *mechanism* for #6 and is noted there rather than as
a separate row. Resource abuse (very large pastes or mass-submission driving LLM
cost) and leakage of contract text into logs are real but, at solo / low-QPS MVP
scale, belong to observability and config hardening — deferred (see §7), not
padded into the top risks.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|------|-----------------------------|----------------|--------------------------------------|-----------------------|-----------------------|
| #1 | User A requesting/mutating User B's analysis by id is refused (404/403, not 200); an owner-scoped list never returns another user's rows. | "logged-in ⇒ allowed" — authenticated is not authorized; a 200 auth-boundary test says nothing about ownership. | The owner-scoped query shape and where `SecurityUtils.currentUserId()` is applied; the `Analysis`→owner relationship — grounded when S-01 builds the first owned entity. | backend integration with two distinct users (the first cross-user test on the first owned entity). | testing only anon→401 and calling isolation "done"; over-mocking the repository so the owner filter is never actually exercised. |
| #2 | Given a fixed model reply containing a HIGH/MEDIUM clause: the saved analysis flags that clause not-LOW, carries a non-empty readable rationale, has ≥1 negotiation point **linked** to it, and drops no clause; retrievable by its owner only. | a green mocked test proves *wiring*, not model judgment (see #6); "flagged" must include "never silently downgraded" — assert the negative (a HIGH never persists as LOW). | The `ChatClient` mock boundary (a fixed raw JSON reply per `docs/clause-classification.md` §6); the entity graph analysis→clause→negotiation-point; the persist path — grounded in S-01. | backend integration e2e with a mocked `ChatClient` (the PRD §8 load-bearing determinism test). | the **oracle problem** — asserting rationale prose, or values lifted from the mock; string-matching the rationale text (see §7). |
| #3 | Representative ragged payloads (bad enum casing, extra/missing field, fenced JSON, empty input) yield a controlled, user-visible explanatory state — never a silent half-empty save. | do not assume `.entity()` succeeding on a clean fixture proves robustness; the failure-handling path must actually exist (co-design with S-01; the PRD mandates an explanatory state). | how S-01 invokes structured output and what it does on converter failure / empty input — grounded in S-01 research before the test is written. | backend integration, mocking at the **model-response boundary** (a raw JSON string) and letting the real converter run. | mocking the converter itself (tests nothing); asserting an exception type instead of the user-visible explanatory outcome. |
| #4 | The logged-in paste→submit→result flow renders classified clauses + negotiation points; the "not legal advice" disclaimer is visible wherever a result shows; empty/unparseable input shows an explanatory state; progress feedback appears during the wait. | do not re-test classification through the browser (backend integration owns it); prove only what the browser owns. | the result-view components and disclaimer placement; how the frontend reaches the backend (a deterministic / mocked-LLM backend so E2E is neither flaky nor costly) — grounded when the S-01 UI lands. | Playwright E2E against a running app with a deterministic backend; the disclaimer is a single text assertion. | CSS/XPath selectors, `waitForTimeout`, missing cleanup / non-unique ids (per the project's E2E rules); pixel-snapshotting instead of asserting the disclaimer text. |
| #5 | Every non-permit-listed route returns 401 to an anonymous caller; the permit-list is exactly the bootstrap set (register, login, csrf); a newly added route defaults to authenticated. | "the filter chain looks right" — assert behavior per route; a permit-list typo will not fail compilation. | the current permit-list and the 401 (not redirect) entry point; re-verify as each slice adds routes — already present in the security config. | backend security-slice MockMvc (an anonymous→401 route matrix). | asserting one endpoint and generalizing; snapshotting the security config instead of exercising routes. |
| #6 | Over a curated set of known-dangerous clauses (auto-renewal, penalty, …), the **real** model assigns not-LOW at or above a target rate; a prompt regression that lowers that rate is caught. | this is NOT the mocked e2e — a deterministic test can never prove it; conflating the two is the central trap. | whether an offline eval is in budget; the graded clause fixtures and the exact prompt under test — grounded only if/when an eval layer is opted into. | offline eval harness (run out-of-band, dated) OR a documented limitation backstopped by the disclaimer. Explicitly NOT the CI suite. | a mocked-LLM test masquerading as model-quality protection; letting eval flakiness gate CI. |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|------------|-----------------|---------------|------------|--------|---------------|
| 1 | Auth boundary regression | Lock default-deny and the permit-list contract every new endpoint inherits (buildable now against F-01). | #5 | security-slice integration | change opened | context/changes/testing-auth-boundary-regression/ |
| 2 | Classification pipeline + isolation | The load-bearing mocked-LLM e2e, converter robustness, and the first cross-user isolation test on `Analysis` (waits for S-01). | #1, #2, #3 | integration + e2e | not started | — |
| 3 | Frontend / browser E2E | Paste→result flow, the "not legal advice" disclaimer guardrail, empty-input state, and the auth redirect (waits for the S-01 UI). | #4 | e2e (browser) | not started | — |
| 4 | Quality-gate wiring | Lock the floor: per-edit hooks + pre-commit + CI (F-02) running the deterministic e2e (after the suites exist). | cross-cutting | gates | not started | — |
| 5 | Model-quality eval (optional) | Measure and regression-guard the real model on known-risky clauses; above-core. | #6 | offline eval | not started | — |

**Status vocabulary** (fixed — parser literals):

| Value | Meaning |
|-------|---------|
| `not started` | No change folder for this rollout phase yet. |
| `change opened` | `context/changes/<id>/` exists with `change.md`; research not done. |
| `researched` | `research.md` exists in the change folder. |
| `planned` | `plan.md` exists with a `## Progress` section. |
| `implementing` | Progress section has at least one `[x]` and at least one `[ ]`. |
| `complete` | Progress section is fully `[x]`. |

Sequencing note: only Phase 1 is buildable today. Phases 2–3 are gated on the
S-01 (`analyze-and-save-contract`) roadmap slice being implemented; Phase 4 is
gated on the suites from Phases 1–3 existing; Phase 5 is optional / above-core.
The rollout ships Phase 1 immediately, then blocks at Phase 2 until S-01 lands —
that is the honest sequencing, not a defect.

## 4. Stack

The classic test base for this project. AI-native tools (if any) carry a
`checked:` date so future readers can see which lines need re-verification.

| Layer | Tool | Version | Notes |
|-------|------|---------|-------|
| unit + integration (backend) | JUnit 5 + Spring Boot Test | managed by Spring Boot 4.0.7 | run with `./mvnw test`; the existing base. |
| DB integration (backend) | Testcontainers (Postgres 18) | BOM-managed | via `TestcontainersConfiguration` (`@ServiceConnection`); integration tests do not use the Compose DB. |
| HTTP / controller (backend) | MockMvc + `spring-security-test` | managed by Spring Boot 4.0.7 | deterministic auth-flow tests already use `.with(csrf())` and the `user(...)` post-processor. |
| LLM mocking (backend) | Mockito (mock `ChatClient`) | bundled in `spring-boot-starter-test` | none wired yet — see §3 Phase 2. Mock at the model-response boundary; never call OpenRouter in tests. |
| e2e (browser) | Playwright | not installed | none yet — see §3 Phase 3. Follow the project's E2E rules (role/label locators, no `waitForTimeout`, test independence). |
| unit (frontend) | — | — | none; frontend risks are covered by browser E2E (§3 Phase 3), not a JS unit runner, per cost × signal. |
| model-quality eval | offline harness (direct OpenRouter call) | n/a | none; optional — see §3 Phase 5. Runs out-of-band, never in the CI suite. |

**Stack grounding tools (current session):**
- Docs: Context7 available — can ground Spring AI 2.0 structured output (`.entity()` / `BeanOutputConverter`), Spring Security 7, Next.js 16, and Playwright APIs before the phases that need them; checked: 2026-07-05.
- Search: Exa.ai not available in current session; general WebSearch is available as a fallback for current-status checks; checked: 2026-07-05.
- Runtime/browser: Playwright MCP, chrome-devtools MCP, and claude-in-chrome available — candidate drivers for the §3 Phase 3 browser E2E layer; checked: 2026-07-05.
- Provider/platform: Linear available (a "Falcon" backlog exists) for tracking; GitHub via the `gh` CLI for the §3 Phase 4 CI gate (F-02); checked: 2026-07-05.

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required for §3 Phase N" means the gate is enforced once that rollout phase
lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|------|-------|-----------|---------|
| lint (frontend eslint) + compile (backend) | local + CI | required | syntactic / type drift |
| unit + integration (backend) | local + CI | required after §3 Phase 1 | logic regressions (auth boundary, then classification) |
| deterministic mocked-LLM e2e | CI on PR | required after §3 Phase 2 | broken critical classification path; non-deterministic CI |
| browser e2e on the paste→result flow | CI on PR | required after §3 Phase 3 | broken critical user path; dropped disclaimer guardrail |
| per-edit hook (lint/format + scoped risk tests) | local (agent loop) | recommended after §3 Phase 4 | regressions at edit time on risk-area files |
| pre-commit (staged lint + scoped risk tests) | local | recommended after §3 Phase 4 | what slipped past per-edit; manual edits |
| model-quality eval | out-of-band | optional (§3 Phase 5) | prompt regressions that lower real-model risk detection |

## 6. Cookbook Patterns

How to add new tests in this project. Each sub-section is filled in once the
relevant rollout phase ships; before that, it reads "TBD — see §3 Phase N."

### 6.1 Adding a backend unit test

- **Location**: alongside the unit under test, under `backend/src/test/java/com/morawski/dev/falcon/<area>/`.
- **Naming**: `<Unit>Test.java`.
- **Reference test**: `backend/src/test/java/com/morawski/dev/falcon/auth/SecurityUtilsTest.java` (drives `SecurityContextHolder` directly, asserts the primitive in isolation).
- **Run locally**: `cd backend && ./mvnw test -Dtest=<ClassName>`.

### 6.2 Adding a backend integration test

- **Location**: `backend/src/test/java/com/morawski/dev/falcon/<area>/`.
- **Mocking policy**: mock only at the external edge — mock `ChatClient` for the LLM; use the real Postgres via `@SpringBootTest @Import(TestcontainersConfiguration.class)` (a bare `@DataJpaTest` fails — there is no embedded DB on the classpath). `@SpringBootTest` tests are not auto-rolled-back, so clean up test data explicitly. Never mock internal services.
- **Reference tests**: `UserRepositoryTest.java` (Testcontainers persistence), `auth/AuthFlowTest.java` (MockMvc full flow with `.with(csrf())`).
- **Run locally**: `cd backend && ./mvnw test -Dtest=<ClassName>` (or `./mvnw clean package` for the full suite).

### 6.3 Adding an auth-boundary / default-deny test

- TBD — see §3 Phase 1. Pattern: an anonymous-caller → 401 route matrix plus a guard that the permit-list is exactly the bootstrap set, so a route wrongly opened to `permitAll` fails a test rather than shipping.

### 6.4 Adding a cross-user isolation test

- TBD — see §3 Phase 2. Pattern: two distinct users; assert User A cannot read or mutate User B's owned entity (404/403, not 200), and owner-scoped lists never leak another user's rows.

### 6.5 Adding a mocked-LLM classification test

- TBD — see §3 Phase 2. Pattern: stub `ChatClient` with a fixed raw JSON reply; assert the *structure and classification contract* (a risky clause is not-LOW, has a linked non-empty negotiation point, no clause dropped, result persisted) — never the rationale prose (see §7).

### 6.6 Adding a browser E2E test

- TBD — see §3 Phase 3. Pattern: drive the logged-in paste→result flow against a deterministic backend; assert the disclaimer is visible and the empty-input explanatory state renders; role/label locators only, wait on state not time, unique ids + cleanup per test.

**Per-rollout-phase notes.** (Empty on first write. After each phase lands, `/10x-implement` appends a 2–3 line note here capturing anything surprising the phase taught — a shared fixture location, a converter quirk, an isolation-query gotcha.)

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **LLM rationale wording** — never asserted; it is non-deterministic and low-value to pin. Assert the classification/structure contract instead. Re-evaluate only if the product starts surfacing canned/templated rationales. (Source: Phase 2 interview Q5.)
- **Model judgment quality as a CI gate** — Risk #6 is never allowed to block CI. It lives in the optional offline eval (§3 Phase 5) or as a documented limitation backstopped by the disclaimer; a deterministic test can only ever prove the wiring, not the judgment. (Source: Phase 2 interview Q1 + challenger pass.)
- **Resource abuse and log-leakage of contract text** — huge pastes / mass-submission cost and sensitive text in logs are deferred to observability and config hardening at this solo / low-QPS MVP scale, not covered by unit/integration tests now. Re-evaluate if usage scales or a real cost/PII incident occurs. (Source: abuse-lens review.)
- **Vendored UI primitives (shadcn/ui: button, input, card, label)** — the library is the test; Falcon tests its own composition (via §3 Phase 3 E2E), not the primitives. (Source: cost × signal.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-07-05
- Stack versions last verified: 2026-07-05
- AI-native tool references last verified: 2026-07-05

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
