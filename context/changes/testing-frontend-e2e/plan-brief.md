# Deterministic browser E2E + disclaimer guardrail coverage — Plan Brief

> Full plan: `context/changes/testing-frontend-e2e/plan.md`
> Research: `context/changes/testing-frontend-e2e/research.md`

## What & Why

Falcon's three Playwright specs seed their fixtures by driving the real paste→submit flow
against the **live OpenRouter model**. They cost money on every run, fail on model
variance, and CI never executes them. Meanwhile Risk #4 — the "not legal advice"
disclaimer silently disappearing, or bad input showing a silent empty result — is
completely uncovered. This change makes the browser layer deterministic, closes Risk #4,
and turns the suite into a real CI gate.

## Starting Point

Playwright arrived **off-plan**, as a side-effect of the S-02/S-03/S-04 slices. The S-03
plan review flagged live-LLM e2e as **CRITICAL before it was built** and recommended
deterministic seeding; the finding was marked ACCEPTED and then bypassed by a verbal
mid-session decision with no recorded rationale. Later specs copied the pattern as house
style. Today: three specs, three 30-second live-LLM waits, `pnpm lint` + `pnpm build` in
CI, and nothing anywhere asserting the disclaimer.

## Desired End State

`pnpm test:e2e` runs seven specs against a backend that never touches the network,
finishes in seconds, costs nothing, and passes deterministically. CI runs it on any PR
touching `frontend/**` or `backend/**`. The disclaimer cannot disappear from either render
site without a red build.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Where the deterministic seam goes | Not one seam — five assertions, five mechanisms | Three of Risk #4's assertions need no backend; one needs stubbing; one needs real persistence | Research |
| Is request interception possible? | Yes — every backend call is browser-issued | One client-side `fetch`; no server actions, route handlers, or proxy | Research |
| Stub location | `src/test/java` + `-Dspring-boot.run.useTestClasspath=true` | Keeps a contract-fabricating stub out of the production jar; `repackage` excludes test classes by contract | Plan |
| Fixture payload | Reuse `MULTI_CLAUSE_JSON` (3 clauses, distinct risk types) | Distinct types guarantee `clause-decision`'s locator never trips strict mode; the unlinked LOW clause makes selective linkage assertable | Plan |
| Live-LLM coverage | Dropped entirely from e2e | Model judgment belongs to Risk #6's offline eval, never a CI gate; conflating the two is the trap §2 names | Plan |
| 502 explanatory state | `page.route()` fulfils a 502 | The stub returns valid JSON and can never produce a 502; interception is viable because calls are browser-issued | Plan |
| Seeding mechanism | `e2e` Spring profile, never interception | Interception fakes the whole backend — `clause-decision` exists to prove persistence, which a stubbed seed would erase | Research |
| CI trigger | New job, path-filtered on `frontend/**` \|\| `backend/**` | Closes the §5 gate unmeetable since S-03; the existing `dorny/paths-filter` already computes both | Plan |
| Setup duplication | Extract `e2e/fixtures.ts` | The retrofit rewrites the seeding block in all three specs anyway; extraction is near-free at that moment | Plan |

## Scope

**In scope:** an `e2e` Spring profile with a stub `ChatModel`; retrofitting three specs off
the live model; a shared `e2e/fixtures.ts`; four new specs covering Risk #4; `webServer`
orchestration; a path-filtered CI job; closing test-plan §3 Phase 3.

**Out of scope:** any live-LLM spec (tagged, opt-in, or otherwise); `storageState` auth
reuse; a frontend unit-test runner; fixing the zero-clause→502 conflation (a product gap,
pinned in test-plan §7); git hooks and pre-commit (§3 Phase 4); adding `@OrderBy` to
`Analysis.clauses`.

## Architecture / Approach

`ContractAnalysisService` injects `ChatClient.Builder`, which Spring AI constructs from the
single `ChatModel` bean — so a `@Primary ChatModel` under `@Profile("e2e")` overrides the
real model with **zero service changes**, the same mechanism the backend tests already use.
The stub sits on the test classpath, reached via `useTestClasspath`, so it never ships.

Everything else follows from one distinction: **a stubbed `ChatModel` fakes one network
call; `page.route()` fakes the entire backend.** So seeding uses the profile (keeping HTTP,
security, the converter, JPA, and Postgres real) and only the 502-reaction test uses
interception.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Deterministic e2e backend | `e2e` profile serving fixed classifications, no API key needed | A stub written as `@TestConfiguration` is silently ignored — app boots, profile active, real model still answers |
| 2. Fixture module + retrofit | `fixtures.ts`; three specs cut off the live model | Assertions must not change — the retrofit proves the same things, deterministically |
| 3. Risk #4 coverage | Four new specs: auth redirect, disclaimer, empty input, 502, result page | A guardrail spec that stays green when the disclaimer is deleted is worse than no spec |
| 4. CI-runnable harness | `webServer` array + path-filtered `e2e` job | Two servers plus a service container; most likely phase to overrun |
| 5. Close the rollout phase | §6.6 cookbook, §3 status, §5 gate wired | — |

**Prerequisites:** none. Every roadmap slice is `done`; the backend mocking seam already
exists and is proven.
**Estimated effort:** ~3–4 sessions. Phases 1–2 are small and mechanical; Phase 3 is the
substance; Phase 4 carries the schedule risk.

## Open Risks & Assumptions

- **Nothing automated will exercise the real OpenRouter wire contract** once live-LLM leaves
  e2e. A breaking provider API change surfaces in production or in a manual smoke, not in
  CI. Accepted deliberately: a flaky paid gate corrodes trust in every run, while a wire
  break is loud and immediate. Revisit if OpenRouter's contract ever changes quietly.
- **`useTestClasspath` is assumed to work under Spring Boot 4.0.7.** Documented since 1.3
  and unlikely to have regressed, but Phase 1's manual verification (jar must not contain
  `E2eChatModelConfig.class`) is what actually confirms it.
- **`webServer` orchestrating a Maven-launched JVM plus Next.js in CI is unproven here.**
  Phase 4 has an explicit fallback: land the config change, pause, finish `ci.yml`
  separately rather than blocking Phase 5.
- **The stub is input-insensitive** — one fixed reply for every contract. Fine for Risk #4;
  a future spec needing two different classifications must adopt the mutable-`ChatModel`
  pattern from `ConverterRobustnessTest`.
- Local dev databases will keep accumulating `e2e-*@example.com` accounts. Account deletion
  is not in the MVP; CI discards its Postgres per run.

## Success Criteria (Summary)

- With `OPENROUTER_API_KEY` **unset**, the full seven-spec suite passes in seconds.
- Deleting the disclaimer from either render site turns the suite red; downgrading the
  fixture's HIGH clause to LOW turns it red. The guardrails can actually fail.
- CI runs the browser suite on any PR touching `frontend/**` or `backend/**`, with no API
  key and no OpenRouter egress — making test-plan §5's `browser e2e` gate genuinely wired
  for the first time.
