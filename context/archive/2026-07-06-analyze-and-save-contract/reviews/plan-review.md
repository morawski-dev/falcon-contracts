<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Analyze & Save a Pasted Contract (S-01)

- **Plan**: `context/changes/analyze-and-save-contract/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-06
- **Verdict**: REVISE → SOUND (all findings fixed)
- **Findings**: 0 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING → PASS (F1, F2 fixed) |
| Plan Completeness | WARNING → PASS (F3 fixed) |

## Grounding
9/9 referenced paths ✓. Global `DataIntegrityViolationException` handler confirmed by grep (`AuthExceptionHandler.java:17` `@RestControllerAdvice`, `:30` `@ExceptionHandler(DataIntegrityViolationException.class)` → 409 "Email already in use"). `docs/reference/contract-surfaces.md` absent — surface check skipped. brief↔plan consistent. Progress↔Phase mechanically consistent (4 phases, every success-criteria bullet mapped). Note: the verification sub-agent failed (0 tool calls — third such failure this session); the key blast-radius claim was confirmed directly via grep, and the Spring AI `.entity()`/mock claims were already jar-verified in `research.md`.

## Findings

### F1 — Malformed LLM output would return "Email already in use"

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 (ContractAnalysisService) + Phase 3 (persistence); interacts with AuthExceptionHandler.java:17,30
- **Detail**: `.entity()` maps JSON into records that permit null fields; a clause with a null/blank `rationale`/`text` becomes a `@Column(nullable=false)` entity that throws `DataIntegrityViolationException` on save, which F-01's GLOBAL `AuthExceptionHandler` maps to 409 {"error":"Email already in use"} — a nonsensical, auth-specific message. Phase-2's guard only checked the empty clause *list*, not empty *fields*.
- **Fix**: Validate LLM record completeness in `ContractAnalysisService.analyze()` (null/blank text/rationale/riskLevel/riskType/recommendation → `AnalysisFailedException` → 502) so malformed output never reaches a DB constraint.
  - Strength: Keeps all LLM failures on the one controlled 502 path; treats external LLM output as untrusted input.
  - Tradeoff: A few lines of field validation in the mapping step.
  - Confidence: HIGH — global handler + message grep-confirmed.
  - Blind spot: None significant.
- **Decision**: FIXED — added `validateFieldsPresent` to Phase 2 §3 contract + snippet, a Critical Implementation Details bullet (the global-handler rationale), and extended failure criterion + Progress 2.3.

### F2 — @Transactional spans the ~15s LLM call

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 3 — AnalysisService (`@Transactional on createAnalysis`)
- **Detail**: `createAnalysis` was marked `@Transactional` and called `analyze()` (the ~15s OpenRouter round-trip) inside it — holding a Hikari connection + open transaction for the entire wait. Survivable at MVP scale but a known pool-exhaustion anti-pattern.
- **Fix**: Run `analyze()` outside any transaction; wrap only the persist in a short `@Transactional` method so atomicity is preserved without holding a connection across the LLM call.
  - Strength: Removes the 15s connection hold; keeps the persist atomic; the LLM failure already throws before persist.
  - Tradeoff: Splits the service into `analyze()` + a `@Transactional` persist step.
  - Confidence: HIGH — the plan explicitly annotated the LLM-calling method `@Transactional`.
  - Blind spot: None significant.
- **Decision**: FIXED — Phase 3 §2 contract now specifies the LLM call runs outside the transaction, with a short `@Transactional` persist method (+ a Critical Implementation Details bullet).

### F3 — The 502-failure test needs a mock the happy-path @Bean can't provide

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 — success criterion 3.3 ("LLM failure → 502")
- **Detail**: The load-bearing e2e uses a `@Bean @Primary ChatModel` returning good fixed JSON; the 502 assertion can't be induced with that bean and needs a distinct malformed/throwing mock. The plan didn't say how.
- **Fix**: Note that the 502 case uses a separate test config / `@MockitoBean ChatModel` override returning malformed content, kept apart from the happy-path e2e bean.
- **Decision**: FIXED — added the note to the Testing Strategy `AnalysisFlowTest` bullet.
