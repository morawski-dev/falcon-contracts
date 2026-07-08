<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Analysis History (S-03)

- **Plan**: `context/changes/analysis-history/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-08
- **Verdict**: REVISE → SOUND after triage (F2, F3 fixed; F1 accepted as a known implementation-time risk)
- **Findings**: 2 critical, 1 warning

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | FAIL → WARNING after triage (F3 fixed; F1 accepted) |
| Plan Completeness | WARNING → PASS after triage (F2 fixed) |

## Grounding

15/15 paths ✓, symbols ✓ (controller mapping, entity fields/enum, security+CORS+CSRF, both test extension points all verified by sub-agent), brief↔plan ✓, contract-surfaces.md absent (check skipped), lessons.md absent. Progress↔Phase mechanical: initial phase-block checkbox violation (F2) — now fixed.

## Findings

### F1 — Phase 3 E2E would call the real, paid, non-deterministic LLM

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 3 — "History E2E test" (seeding strategy)
- **Detail**: The plan's E2E "creates an analysis through the app (paste → submit) or a seeded fixture, decided during implementation." Verification is definitive: there is NO mechanism to run the *application* (as opposed to `@SpringBootTest`) with a mocked LLM. The runtime create flow (`AnalysisService.createAnalysis` → `ContractAnalysisService.analyze` → `chatClient...call().entity()`) hits OpenRouter live; main `application.properties` needs `${OPENROUTER_API_KEY}`, model `openai/gpt-4o`. The only `ChatModel` stub (`AnalysisFlowTest.MockChatModelConfig`) lives inside the test context and is never wired into the runnable app. There are no `application-*.properties` profiles. A browser test driving paste→submit would be non-deterministic, cost money, ~15s — violating the PRD test-determinism guardrail and CLAUDE.md's "the real LLM is never called in tests." This is a harness-shaping decision, not deferrable to implementation.
- **Fix A ⭐ Recommended**: Seed analyses out-of-band (DB/seed hook); Playwright tests only list + reopen, never the create→LLM path.
  - Strength: Matches the slice's scope ("list + reopen", not "create"); no LLM in the browser path; deterministic and free; the create→classify flow is already deterministically covered by `AnalysisFlowTest`.
  - Tradeoff: Browser test doesn't exercise create→classify.
  - Confidence: HIGH — create path is backend-tested; S-03's value is the list.
  - Blind spot: Needs a clean seeding hook (SQL seed or a Playwright global-setup that inserts rows directly — not via the LLM-backed POST).
- **Fix B**: Add an `e2e` Spring profile with a stub `ChatModel` bean + `application-e2e.properties`.
  - Strength: Browser drives the real paste→submit→list→reopen flow deterministically (fixed JSON like `FIXED_JSON`); highest-fidelity E2E.
  - Tradeoff: Net-new production-adjacent wiring; the repo's first Playwright test would carry it.
  - Confidence: MED — standard Spring profile work but net-new infra.
  - Blind spot: A `@Profile("e2e")` bean in main source must be provably inert in prod.
- **Decision**: ACCEPTED — user will resolve the seeding/LLM-determinism approach during Phase 3 implementation. (Plan-brief already flags Playwright as the largest unknown.)

### F2 — Phase-block Success Criteria used `[ ]` checkboxes (progress-format contract break)

- **Severity**: ❌ CRITICAL (per progress-format mechanical contract)
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phases 1–3, `#### Automated/Manual Verification:` blocks
- **Detail**: 17 Success-Criteria bullets inside the Phase blocks were written as `- [ ]` checkboxes. The progress-format contract requires Phase blocks to carry plain `- ` bullets, with checkbox state owned solely by the `## Progress` section (which was already well-formed and matched all phases). Duplicate checkboxes outside Progress can confuse `/10x-implement`'s state parser (34 items seen instead of 17).
- **Fix**: Convert the phase-block `- [ ]` Success-Criteria bullets to plain `- `; leave `## Progress` untouched.
- **Decision**: FIXED — converted all 17 phase-block bullets (checkbox count dropped from 35 → 18, i.e. 17 Progress items + the convention line).

### F3 — `spring-boot:run` as an automated verification won't terminate

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 — Automated Verification 1.1
- **Detail**: "Migration applies cleanly on boot: `./mvnw spring-boot:run`" is not a terminating check — it launches a long-running server (and without `OPENROUTER_API_KEY` the context may not even start). The migration is already exercised automatically by any `@SpringBootTest`, which runs Liquibase against Testcontainers Postgres.
- **Fix**: Replace 1.1 with "Migration 003 applies via the test context: `cd backend && ./mvnw test` boots Spring against Testcontainers Postgres and runs Liquibase."
- **Decision**: FIXED — updated item 1.1 in both the Phase 1 block and the Progress section.
