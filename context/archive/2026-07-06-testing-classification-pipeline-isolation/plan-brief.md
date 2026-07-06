# Classification Pipeline + Isolation — Plan Brief

> Full plan: `context/changes/testing-classification-pipeline-isolation/plan.md`

## What & Why

Phase 2 of the frozen test rollout (`test-plan.md` §3): lock the three high risks around the
contract-classification pipeline — #1 cross-user data isolation (IDOR), #2 a genuinely risky
clause being dropped/downgraded/unlinked, and #3 the structured-output converter failing on
ragged model JSON. The point is a deterministic, mocked-LLM safety net so these can't silently
regress.

## Starting Point

S-01 (`analyze-and-save-contract`, archived) already shipped the pipeline **and** co-designed
feature tests: a happy-path mocked-LLM e2e (single HIGH clause + linked point), cross-user
GET→404, malformed-JSON→502, over-limit input→400, and owner-scoped persistence. So the risk
contract is *partly* covered — this phase fills the specific gaps, it does not start from zero.

## Desired End State

Three new/extended backend test areas make the §2 risk contract explicit and green, and the
test-plan cookbook documents the reusable patterns. `./mvnw test` passes; each new test asserts
structure/status — never rationale prose, never an exception type in place of the user-visible
HTTP outcome.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Scope framing | Gap-fill against S-01, don't rewrite | S-01's tests already own the happy path; only the missing assertions add signal | Plan |
| Zero-clause conflation | Document & pin as-is (characterization test + §7 note) | A distinct "no risky clauses" state is feature work beyond a frozen test phase | Plan |
| Overlap with S-01 tests | New classes alongside, no duplication | Minimal churn on working tests; add only the deltas | Plan |
| Ragged-payload breadth | Full named set at the MockMvc layer | Covers every failure mode §2 risk-#3 names, asserting the user-visible outcome | Plan |
| Risk #2 fixture | Multi-clause mixed HIGH/MEDIUM/LOW | Only a mix can prove no-drop + never-downgrade + selective linkage | Plan (decided) |
| List-isolation | Deferred to S-03 | No list endpoint / `findAllByOwnerId` exists yet | Research |

## Scope

**In scope:** multi-clause classification-contract e2e (#2); ragged-payload converter matrix + zero-clause characterization (#3); repository-level owner-scope + no-existence-leak tests (#1); test-plan cookbook/§7/status sync.

**Out of scope:** fixing the zero-clause conflation; list-isolation (no endpoint); Risk #4 browser/disclaimer (Phase 3); Risk #6 model-quality eval (Phase 5); any production code or migration.

## Architecture / Approach

Reuse S-01's proven test idioms: mock the **`ChatModel`** with a real lambda (never `ChatClient`
— an unstubbed mock NPEs), keeping the real `BeanOutputConverter` + `.entity()` in the path;
real Postgres via `@Import(TestcontainersConfiguration)` + `@SpringBootTest`; `.with(csrf())` +
`.with(user(new AppUserDetails(owner)))` for authenticated requests. The ragged matrix shares one
Spring context via a **mutable `@Bean @Primary ChatModel`** whose reply is a settable field, so a
`@ParameterizedTest` can drive per-case payloads without a context-per-case cost.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Classification contract (#2) | Multi-clause e2e: no-drop, never-downgrade, risky-only linkage | Oracle problem — must assert structure, not prose |
| 2. Converter robustness (#3) | Parameterized ragged matrix → user-visible 502 + zero-clause characterization | Fenced-JSON outcome is converter-dependent; pin observed behavior |
| 3. Isolation + doc sync (#1) | Repo owner-scope test + no-existence-leak e2e + test-plan sync | Over-mocking the repo so the owner filter is never exercised |

**Prerequisites:** S-01 pipeline present (met — archived); Docker running for Testcontainers.
**Estimated effort:** ~1-2 sessions across 3 phases (test code + one doc file, no production changes).

## Open Risks & Assumptions

- The markdown-fenced-JSON case's outcome (201 if `BeanOutputConverter` strips fences, else 502)
  is observed at implementation time; the invariant is "no silent half-save," not a fixed status.
- The zero-clause→502 conflation is *characterized*, not fixed — a follow-up feature change owns the fix.
- Testcontainers needs a working Docker daemon in the run environment.

## Success Criteria (Summary)

- A risky clause in a multi-clause reply is provably never dropped and never persisted as LOW, and carries a linked negotiation point.
- Every ragged model reply yields a controlled, user-visible outcome — never a silent half-save.
- One user can never read another's analysis, and a wrong-owner id is indistinguishable from a missing one.
