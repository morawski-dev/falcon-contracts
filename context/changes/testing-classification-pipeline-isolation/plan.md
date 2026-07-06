# Classification Pipeline + Isolation — Test Hardening Implementation Plan

## Overview

This is **Phase 2 of the frozen test rollout** (`context/foundation/test-plan.md` §3) —
"Classification pipeline + isolation" — covering the three high risks #1 (cross-user
IDOR), #2 (a risky clause dropped/downgraded/unlinked), and #3 (structured-output
converter failing on ragged model JSON).

The S-01 slice (`analyze-and-save-contract`, now archived) already shipped the pipeline
**and** a set of co-designed feature tests. So this phase does **not** write the
load-bearing e2e from scratch. It **audits S-01's coverage against the test-plan's §2
risk-response contract and fills the specific gaps**, reusing S-01's `ChatModel`-lambda
mock pattern and Testcontainers idioms, then syncs the test-plan cookbook.

## Current State Analysis

**The pipeline (all live in `backend/src/main/java/com/morawski/dev/falcon/analysis/`):**

- `ContractAnalysisService.analyze(String)` calls Spring AI 2.0 structured output:
  `chatClient.prompt().system(SYSTEM_PROMPT).user(...).call().entity(ClauseAnalysisResult.class)`
  (`ContractAnalysisService.java:65-79`). The `ChatClient` is built from an injected
  `ChatClient.Builder` (`:59-63`) — that is the mock boundary.
- A **centralized failure path exists**: a broad `catch (RuntimeException)` around `.entity()`
  wraps everything (enum/schema mismatch, non-JSON, transport error) as `AnalysisFailedException`
  (`:73-79`); a zero/empty-clause guard (`:81-83`); field-completeness + length guards
  (`:88-111`). `AnalysisExceptionHandler` maps `AnalysisFailedException → 502 BAD_GATEWAY`
  (`AnalysisExceptionHandler.java:13-17`). **LLM failure is atomic — nothing is persisted.**
- Persist flow `AnalysisService.persistAndRespond` (`AnalysisService.java:43-62`): saves
  `Analysis` + `Clause`s (`saveAndFlush`, cascade), then matches each LLM
  `NegotiationPoint.clauseText` to a saved `Clause.text` by exact-**then-prefix/contains**
  (`matchClauseId`, `:74-89`) to set a **nullable** `clauseId`, then saves the points.
- Ownership is enforced **in the query**: `AnalysisRepository.findByIdAndOwnerId(id, ownerId)`
  (`AnalysisRepository.java:9`); `getAnalysis` throws `ResponseStatusException(NOT_FOUND)` on
  empty (`AnalysisService.java:64-72`) — a wrong-owner id is **indistinguishable from a missing
  id**. Endpoints: `POST /api/analyses`, `GET /api/analyses/{id}` only — **no list, no
  update/delete** (`AnalysisController.java`).

**Existing tests (in `backend/src/test/java/com/morawski/dev/falcon/`):**

| Test | Covers | Risk |
|------|--------|------|
| `analysis/AnalysisFlowTest` | mocked-LLM happy path (single HIGH `AUTO_RENEWAL` clause + 1 linked point), cross-user GET→404, >20k input→400 | #1, #2 |
| `analysis/AnalysisFailureTest` | malformed `"not json at all"` → 502 | #3 |
| `analysis/ContractAnalysisServiceTest` | unit: transport failure, over-length clause | #3 |
| `analysis/AnalysisRepositoryTest` | owner-scoped persistence via `findByIdAndOwnerId` | #1 |
| `auth/AuthBoundaryMatrixTest` | Phase-1 anonymous→401 route matrix | #5 (prior phase) |

**The mock pattern to reuse** (`AnalysisFlowTest.java:187-197`): a `@Bean @Primary ChatModel`
implemented as a **real lambda** `prompt -> new ChatResponse(List.of(new Generation(new
AssistantMessage(json))))` — never a Mockito `@MockBean ChatClient` (an unstubbed mock NPEs on
`chatModel.getOptions().mutate()`). This keeps the real `ChatClient` + `BeanOutputConverter` +
`.entity()` in the path. Imported via `@Import({TestcontainersConfiguration.class,
XxxTest.MockChatModelConfig.class})`.

**Coverage gaps vs. the §2 risk contract (what this phase adds):**

- **Risk #2:** no test asserts the *negative* ("a HIGH never persists as LOW") or proves **no
  clause is dropped** — the single-clause fixture cannot. → a multi-clause mixed fixture.
- **Risk #3:** only `"not json"` is exercised. The plan names a fuller matrix (enum casing,
  extra/missing field, fenced JSON, empty, zero-clause) — none yet run through the real
  converter to the user-visible outcome.
- **Risk #1:** get-by-id→404 exists; missing is the explicit **no-existence-leak** assertion and
  a repository-level owner-scope test. The "list never leaks" half is **untestable** (no list
  endpoint — deferred to S-03).

## Desired End State

Three new/extended backend test areas make the §2 risk contract explicit and green, and the
test-plan cookbook (§6.4/§6.5) documents the reusable patterns. Verified by
`cd backend && ./mvnw test` passing, and by reading each new test to confirm it asserts the
*structure/classification contract* — never rationale prose, never an exception type in place of
the user-visible HTTP outcome.

### Key Discoveries:

- Mock at the `ChatModel` seam with a real lambda, not `ChatClient` (`AnalysisFlowTest.java:187-197`).
- The §6 fixture's `negotiationPoints[0].clauseText` is a **truncated prefix** of the clause
  `text` → linkage is prefix-matched and `clauseId` is nullable (`AnalysisService.java:74-89`).
- Zero-clause valid JSON currently → 502 via the empty-clause guard (`ContractAnalysisService.java:81-83`)
  — the same path as a real failure (the conflation to document).
- `@SpringBootTest` tests are **not** auto-rolled-back; teardown is explicit `deleteAll()`.
  Tests that persist `NegotiationPoint.clauseId` need the **two-phase teardown** (null the
  `clauseId` FK column, then `deleteAll()` children-first) as in `AnalysisFlowTest`.
- Boot-4 relocated packages: `@AutoConfigureMockMvc` from
  `org.springframework.boot.webmvc.test.autoconfigure` (not the old servlet path). Test config
  `src/test/resources/application.properties` sets the dummy `spring.ai.openai.api-key=test`.

## What We're NOT Doing

- **Not fixing the zero-clause conflation.** A distinct "no risky clauses found" explanatory
  success state is a *feature* change (service + controller + frontend) beyond this frozen test
  phase. We pin current behavior with a characterization test and document the tension (§7 + a
  follow-up flag).
- **Not testing list-isolation** — there is no `findAllByOwnerId` / list endpoint yet (S-03).
- **Not re-testing** what S-01 already owns (happy-path single-clause e2e, malformed→502,
  >20k→400) — we gap-fill alongside, not duplicate.
- **Not touching Risk #4 (browser/disclaimer)** — that is Phase 3 (Playwright), or **#6**
  (model-quality eval) — Phase 5.
- **No new production code, no migrations** — test code and one documentation file only.

## Implementation Approach

Gap-fill with focused new test classes that reuse S-01's fixtures/idioms and add only the
missing assertions, one class per risk delta. Each phase is independently verifiable via
`./mvnw test -Dtest=<Class>`. Phase 3 also syncs `test-plan.md`.

## Critical Implementation Details

- **Parameterized matrix needs per-case mock content.** S-01's `@Bean @Primary ChatModel`
  fixes one reply per Spring context. For the ragged matrix, introduce a **mutable**
  `@Bean @Primary ChatModel` whose reply is a settable field, autowired into the
  `@ParameterizedTest` so all cases share one (fast) context while each sets its own payload:

  ```java
  @TestConfiguration(proxyBeanMethods = false)
  static class MutableChatModelConfig {
      static final class MutableChatModel implements ChatModel {
          volatile String content = "";
          public ChatResponse call(Prompt prompt) {
              return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
          }
      }
      @Bean @Primary MutableChatModel mockChatModel() { return new MutableChatModel(); }
  }
  ```
  The test autowires `MutableChatModel`, sets `.content = <ragged payload>` before each
  `mockMvc.perform(...)`.

- **The markdown-fenced case has an uncertain-but-controlled outcome.** Spring AI's
  `BeanOutputConverter` may *strip* ```json fences and parse successfully (→ 201, clause saved)
  rather than fail (→ 502). The invariant under test is **"no silent half-save"** — the
  implementer observes the real converter's behavior and pins whichever controlled outcome it
  produces (asserting 201-with-a-persisted-clause, or 502), with a one-line comment recording
  which.

- **Prefix-match linkage.** Assert a negotiation point links to a risky clause via a non-null
  `clauseId` resolving to a HIGH/MEDIUM clause — never by string-equality on `clauseText`, and
  never on the recommendation prose.

- **Teardown ordering.** Any test persisting negotiation points with a set `clauseId` must null
  that FK column in a first committed transaction, then `deleteAll()` children before
  `userRepository.deleteAll()` (mirror `AnalysisFlowTest`). Phase 3's isolation seed avoids this
  by seeding an `Analysis` with clauses only (no linked points).

---

## Phase 1: Risk #2 — Classification-Contract Hardening

### Overview

Prove the load-bearing classification contract that the single-clause happy path cannot: no
clause dropped, no risky clause silently downgraded, every risky clause linked to a negotiation
point — with a multi-clause mixed-risk fixture.

### Changes Required:

#### 1. Multi-clause classification-contract e2e

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/ClassificationContractTest.java` (new)

**Intent**: A MockMvc e2e that POSTs a contract as an authenticated owner with the `ChatModel`
mocked to return a **multi-clause mixed-risk** fixture, then asserts the full persist contract on
the response (and via a follow-up owner GET). Reuses the `MockChatModelConfig` lambda shape and
`persistUser` / `.with(csrf()).with(user(new AppUserDetails(owner)))` idioms.

**Contract**: Fixture = a valid JSON reply with three clauses and two points:

```json
{
  "clauses": [
    { "text": "Umowa ulega automatycznemu przedłużeniu o kolejne 12 miesięcy...", "riskLevel": "HIGH",   "riskType": "AUTO_RENEWAL", "rationale": "..." },
    { "text": "W razie opóźnienia płatności naliczana jest kara 2% za każdy dzień...", "riskLevel": "MEDIUM", "riskType": "PENALTY",      "rationale": "..." },
    { "text": "Płatność następuje przelewem w terminie 14 dni od wystawienia faktury.", "riskLevel": "LOW", "riskType": "PAYMENT",       "rationale": "..." }
  ],
  "negotiationPoints": [
    { "clauseText": "Umowa ulega automatycznemu przedłużeniu...", "recommendation": "...", "priority": "HIGH" },
    { "clauseText": "W razie opóźnienia płatności...",           "recommendation": "...", "priority": "MEDIUM" }
  ]
}
```

Assertions on the saved analysis (POST 201 body + owner GET 200 body):
- **No clause dropped**: persisted clause count == 3.
- **Never downgraded (assert the negative)**: the `AUTO_RENEWAL` clause persists `riskLevel == HIGH`
  (and `!= LOW`); the `PENALTY` clause `== MEDIUM`; the `PAYMENT` clause `== LOW`.
- **Linkage**: exactly 2 negotiation points; each has a non-null `clauseId` resolving to a
  HIGH-or-MEDIUM clause; the LOW clause is referenced by none.
- **Rationale readable, not pinned**: each clause `rationale` has text (`StringUtils.hasText`) —
  **no assertion on the prose** (oracle-problem guard).
- Two-phase teardown (points carry `clauseId`).

### Success Criteria:

#### Automated Verification:

- New test passes: `cd backend && ./mvnw test -Dtest=ClassificationContractTest`
- Full backend suite still green: `cd backend && ./mvnw test`

#### Manual Verification:

- The test asserts no-drop (count), never-downgrade (HIGH stays HIGH), risky-only linkage by
  `clauseId`, and rationale `hasText` — with **no** assertion on rationale/recommendation prose.
- The mock is the `ChatModel` lambda (real converter in path), not a Mockito `ChatClient`.

**Implementation Note**: After automated verification passes, pause for human confirmation that
the assertions faithfully encode the §2 risk-#2 contract before proceeding.

---

## Phase 2: Risk #3 — Converter-Robustness Matrix

### Overview

Drive the full ragged-payload set through the **real** converter to its controlled, user-visible
outcome, and pin today's zero-clause→502 behavior as a characterization test.

### Changes Required:

#### 1. Parameterized ragged-payload matrix

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/ConverterRobustnessTest.java` (new)

**Intent**: A MockMvc `@ParameterizedTest` over the named ragged payloads, each set on a mutable
`ChatModel` bean, asserting the controlled HTTP outcome the pipeline actually produces — proving
the failure-handling path exists and is user-visible, not a silent half-save.

**Contract**: `@ParameterizedTest @MethodSource` supplying `(label, rawJsonPayload)` pairs for:
`bad enum casing` (`"riskLevel":"high"`), `extra unexpected field`, `missing required field`
(no `riskLevel`), `markdown-fenced JSON` (```json … ```), `empty/blank content` (`""`),
`zero-clause valid` (`"clauses":[]`). Each case authenticates and POSTs `/api/analyses` with the
mutable mock set to the payload; the real `BeanOutputConverter` + service run.
- Failure cases (enum casing, missing field, empty, zero-clause) → **502** with the generic
  `{"error":"Failed to analyze contract. Please try again."}` body; assert the **status**, not
  the exception type.
- Extra-field case → assert the observed controlled outcome (Jackson typically ignores unknown
  fields → 201; pin whichever the converter yields).
- Fenced-JSON case → assert the observed controlled outcome per Critical Implementation Details
  (201-with-clause if fences stripped, else 502), with a comment recording which.
- Uses the `MutableChatModelConfig` from Critical Implementation Details.

#### 2. Zero-clause characterization + cross-reference

**File**: same test (a named case) + a code comment

**Intent**: The `zero-clause valid` case explicitly documents that a *valid* reply finding no
risky clauses is currently folded into the 502 error path (a false-all-clear conflated with a
model failure). The comment cross-references the test-plan §7 note added in Phase 3 and flags a
distinct explanatory state as a follow-up feature change.

**Contract**: assert `"clauses":[]` (with valid JSON envelope) → 502 today; comment: "characterizes
current behavior — see test-plan §7 (zero-clause conflation); fixing is a follow-up feature."

### Success Criteria:

#### Automated Verification:

- New matrix passes: `cd backend && ./mvnw test -Dtest=ConverterRobustnessTest`
- Full backend suite still green: `cd backend && ./mvnw test`

#### Manual Verification:

- Every case asserts the **user-visible HTTP outcome**, real converter in path (mock at the raw-JSON
  boundary — the converter itself is never mocked).
- The fenced-JSON case pins the actually-observed controlled outcome; the zero-clause case pins
  502 with the §7 cross-reference comment.

**Implementation Note**: After automated verification passes, pause for human confirmation that
the matrix covers the §2 risk-#3 named set and asserts outcomes (not exception types).

---

## Phase 3: Risk #1 — Isolation Completeness + Test-Plan Sync

### Overview

Add the repository-level owner-scope test (filter actually exercised) and the explicit
no-existence-leak assertion, then sync the test-plan cookbook, negative-space, and status.

### Changes Required:

#### 1. Repository-level owner-scope test

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisRepositoryTest.java` (extend)

**Intent**: Add method(s) proving the owner filter at the query layer against real Postgres —
`findByIdAndOwnerId` returns empty for the wrong owner and present for the right owner — so the
filter is genuinely exercised, not mocked away (the §2 risk-#1 anti-pattern).

**Contract**: persist two users A and B and one `Analysis` owned by A; assert
`findByIdAndOwnerId(analysisId, B.id)` is empty and `findByIdAndOwnerId(analysisId, A.id)` is
present. Reuse the existing class's Testcontainers setup and `deleteAll()` teardown.

#### 2. No-existence-leak e2e

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java` (new)

**Intent**: A MockMvc test seeding an `Analysis` for user A directly via the repository (no LLM
mock needed), then asserting user B's GET of A's **real** id and user B's GET of a **truly-missing**
id return byte-identical 404s — proving ownership does not leak existence.

**Contract**: seed `new Analysis(A.id, …)` with clauses only (no linked points → simple teardown);
`GET /api/analyses/{realIdOwnedByA}` as B → 404; `GET /api/analyses/{missingId}` as B → 404;
assert both responses have identical status and body (no distinguishing signal). Document in a
comment that list-isolation is deferred to S-03 (no list endpoint).

#### 3. Test-plan cookbook + status sync

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "TBD" cookbook stubs with the real patterns this phase established, record
the zero-clause conflation in negative-space, advance the rollout status, and append the
per-phase note.

**Contract**:
- §6.4 "Adding a cross-user isolation test" — replace TBD with: seed one user's owned entity,
  assert the other user's GET → 404 identical to a missing id (no existence leak); exercise
  `findByIdAndOwnerId` against real Postgres (don't over-mock the repo).
- §6.5 "Adding a mocked-LLM classification test" — replace TBD with: `@Bean @Primary ChatModel`
  real lambda (never mock `ChatClient`); fixed/mutable JSON at the model-response boundary; assert
  the structure/classification contract (not-LOW, linked point, no drop, persisted) — never prose.
- §7 — add a bullet: zero-clause valid replies are currently conflated with LLM failure (both
  502); pinned by a characterization test; a distinct "no risky clauses found" state is a
  follow-up feature, not a test gap.
- §3 Phase-2 row — advance Status per the vocabulary as the phase lands.
- §6 per-rollout-phase notes — append a 2-3 line note (shared mutable-`ChatModel` fixture location;
  prefix-match linkage; two-phase teardown for `clauseId`).

### Success Criteria:

#### Automated Verification:

- Repo isolation test passes: `cd backend && ./mvnw test -Dtest=AnalysisRepositoryTest`
- E2e no-leak test passes: `cd backend && ./mvnw test -Dtest=AnalysisIsolationTest`
- Full backend suite green: `cd backend && ./mvnw test`

#### Manual Verification:

- `findByIdAndOwnerId` is exercised against real Postgres; wrong-owner → empty, right-owner → present.
- Cross-user GET and missing-id GET are indistinguishable 404s.
- `test-plan.md` §6.4/§6.5 filled, §7 zero-clause note added, §3 Phase-2 status advanced, per-phase
  note appended.

**Implementation Note**: After automated verification passes, pause for human confirmation that the
isolation assertions and the test-plan edits are correct before closing the phase.

---

## Testing Strategy

### Unit Tests:

- Existing `ContractAnalysisServiceTest` (unit, no Spring context) remains the cheap layer for
  service-internal failure branches; this phase does not duplicate it.

### Integration Tests:

- Phase 1 `ClassificationContractTest` (MockMvc + Testcontainers + `ChatModel` lambda): the
  multi-clause classification contract.
- Phase 2 `ConverterRobustnessTest` (MockMvc + mutable `ChatModel`): the ragged-payload matrix to
  user-visible outcomes.
- Phase 3 `AnalysisRepositoryTest` (extended) + `AnalysisIsolationTest` (MockMvc): owner-scope
  query + no-existence-leak.

### Manual Testing Steps:

1. Read each new test and confirm assertions target structure/status, never prose or exception type.
2. Confirm the `ChatModel` (not `ChatClient`) is mocked, so the real converter runs.
3. Confirm the fenced-JSON and zero-clause outcomes match observed behavior and carry the
   documenting comments.

## Performance Considerations

Each MockMvc case boots the Spring context; the parameterized matrix deliberately shares one
context via the mutable `ChatModel` bean to avoid a context-per-case cost. Full-suite runtime
grows by a handful of context-bound cases — acceptable for the risk coverage gained.

## Migration Notes

None — no schema or production-code changes. Test code plus one documentation file only.

## References

- Test strategy: `context/foundation/test-plan.md` (§2 risk map + Risk Response Guidance; §3 Phase 2; §6.4/§6.5 cookbook; §7 negative-space)
- S-01 build (archived): `context/archive/2026-07-06-analyze-and-save-contract/{plan.md,research.md}`
- Authoritative acceptance criteria: `context/foundation/prd.md` (US-01 lines 51-55; FR-005 line 69; FR-006 line 71)
- Fixture source: `docs/clause-classification.md` §6
- Mock pattern to mirror: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:187-197`
- Pipeline: `backend/src/main/java/com/morawski/dev/falcon/analysis/{ContractAnalysisService,AnalysisService,AnalysisRepository,AnalysisExceptionHandler}.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Risk #2 — Classification-Contract Hardening

#### Automated

- [x] 1.1 New test passes: `./mvnw test -Dtest=ClassificationContractTest` — b855927
- [x] 1.2 Full backend suite still green: `./mvnw test` — b855927

#### Manual

- [x] 1.3 Test asserts no-drop, never-downgrade, risky-only linkage by clauseId, rationale hasText — no prose assertion — b855927
- [x] 1.4 Mock is the ChatModel lambda (real converter in path), not a Mockito ChatClient — b855927

### Phase 2: Risk #3 — Converter-Robustness Matrix

#### Automated

- [x] 2.1 New matrix passes: `./mvnw test -Dtest=ConverterRobustnessTest` — 4b37b0f
- [x] 2.2 Full backend suite still green: `./mvnw test` — 4b37b0f

#### Manual

- [x] 2.3 Every case asserts the user-visible HTTP outcome, real converter in path (converter never mocked) — 4b37b0f
- [x] 2.4 Fenced-JSON case pins the observed controlled outcome; zero-clause case pins 502 with the §7 cross-reference comment — 4b37b0f

### Phase 3: Risk #1 — Isolation Completeness + Test-Plan Sync

#### Automated

- [x] 3.1 Repo isolation test passes: `./mvnw test -Dtest=AnalysisRepositoryTest`
- [x] 3.2 E2e no-leak test passes: `./mvnw test -Dtest=AnalysisIsolationTest`
- [x] 3.3 Full backend suite green: `./mvnw test`

#### Manual

- [x] 3.4 findByIdAndOwnerId exercised against real Postgres; wrong-owner empty, right-owner present
- [x] 3.5 Cross-user GET and missing-id GET are indistinguishable 404s
- [x] 3.6 test-plan.md §6.4/§6.5 filled, §7 zero-clause note added, §3 Phase-2 status advanced, per-phase note appended
