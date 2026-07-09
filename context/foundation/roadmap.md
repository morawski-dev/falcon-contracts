---
project: Falcon
version: 1
status: draft
created: 2026-07-04
updated: 2026-07-09
prd_version: 1
main_goal: market-feedback
top_blocker: capacity
---

# Roadmap: Falcon

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline (2026-07-04).
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

Falcon turns a pasted contract into a per-clause risk breakdown: an LLM splits the text into clauses, classifies each (risk level, risk type, plain-language rationale), and generates concrete negotiation points for the risky ones — aimed at freelancers and small-business owners about to sign a B2B / rental / service agreement without a lawyer. The product's value is the domain decision it makes on *every clause*, not the storage of the document. Its **riskiest assumption** — the one belief that, if wrong, sinks the product — is that an LLM can reliably flag genuinely risky clauses with a rationale a non-lawyer can act on. UI language and model output are Polish; "Falcon" is the codename, the artifact is `falcon-contracts`.

## North star

**S-01: Analyze & save a pasted contract** — a logged-in user pastes a contract and Falcon returns a saved, owner-scoped breakdown: each clause classified, at least one negotiation point on the risky ones, shown with the "not legal advice" disclaimer. This is the validation milestone because it *is* the PRD's Primary Success Criterion — everything else (per-clause triage, history, deletion) only matters if this classification flow works and is trusted.

> **What "north star" means here:** the smallest end-to-end slice whose successful delivery would prove the core product hypothesis (that Falcon's per-clause classification is useful and trusted) — placed as early as its Prerequisites allow, because everything else is only worth building if this works. It sits behind exactly one thing: the auth gate (F-01).

## At a glance

| ID    | Change ID                | Outcome (user can …)                                            | Prerequisites | PRD refs                                        | Status   |
| ----- | ------------------------ | --------------------------------------------------------------- | ------------- | ----------------------------------------------- | -------- |
| F-01  | identity-and-isolation   | (foundation) register, log in; every analysis is owner-scoped   | —             | FR-001, Access Control                          | done     |
| S-01  | analyze-and-save-contract | paste a contract → saved, classified breakdown + negotiation points | F-01      | US-01, FR-002, FR-003, FR-004, FR-005, FR-006, FR-008 | done |
| S-02  | clause-decision-status   | mark each clause accepted / to-negotiate / rejected             | S-01          | FR-007                                          | done |
| S-03  | analysis-history         | see and reopen their past analyses                              | S-01          | FR-009                                          | done     |
| S-04  | delete-analysis          | delete one of their saved analyses                              | S-01          | FR-010                                          | proposed |
| F-02  | ci-build-and-test        | (foundation) build + tests run automatically on every push      | —             | test-determinism guardrail                      | done |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks. After S-01 lands, Streams B and C run in parallel with the tail of Stream A — the deliberate lever for a capacity-constrained solo builder.

| Stream | Theme                | Chain                          | Note                                                                    |
| ------ | -------------------- | ------------------------------ | ----------------------------------------------------------------------- |
| A      | Core value chain     | `F-01` → `S-01` → `S-02`       | The north-star path: auth gate → prove the domain rule → turn the report into a working checklist. |
| B      | History & retention  | `S-03` / `S-04`                | Both branch from `S-01` (join Stream A at `S-01`); the Secondary signal + user-driven deletion. |
| C      | Verification         | `F-02`                         | Standalone enabler; automates S-01's deterministic e2e test and guards S-02–S-04. Built alongside Stream B. |

## Baseline

What's already in place in the codebase as of 2026-07-04 (auto-researched from the actual files + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them. The theme is **"all plumbing, zero domain"** — every layer's dependency and config is wired, but not one entity, controller, or service exists yet.

- **Frontend:** present (scaffold) — Next.js 16 App Router (`frontend/src/app/{layout,page}.tsx`), React 19, Tailwind 4, shadcn/ui configured (`frontend/components.json`); starter pages only, no domain screens.
- **Backend / API:** present (scaffold) — Spring Boot 4.0.7 / Java 25 (`backend/src/main/java/com/morawski/dev/falcon/FalconApplication.java`); main class + tests only, no controllers/services/entities.
- **Data:** partial — Spring Data JPA + Postgres driver on the classpath, `backend/compose.yaml` (Postgres 18), Testcontainers wired for tests; Liquibase master changelog present but **empty** (`databaseChangeLog: []`) — zero entities, zero migrations.
- **Auth:** partial — `spring-boot-starter-security` on the classpath (Spring Security's default lock-everything), but no `User` model, no security config, no register/login, nothing owner-scoped.
- **AI integration:** partial — Spring AI 2.0.0 BOM + `spring-ai-starter-model-openai` in `pom.xml`; `application.properties` points at OpenRouter (`openai/gpt-4o`, temp 0.2, `json_object`); but no `ChatClient` usage, no `ContractAnalysisService`, no structured-output records.
- **Deploy / infra:** partial → absent — local `backend/compose.yaml` auto-starts Postgres; **no** `.github/workflows` (CI absent); AWS EC2 production is documented in `infrastructure.md` but not built (above-MVP).
- **Observability:** absent — `spring-boot-starter-actuator` only; no OpenTelemetry / Langfuse dependencies or config (above-MVP).

## Foundations

### F-01: Identity & per-user isolation

- **Outcome:** (foundation) users can register, log in, and hold a session; every `Analysis` (and its clauses and negotiation points) is scoped to its owner and unreachable by any other authenticated user — isolation enforced at the query/security layer, not just the UI.
- **Change ID:** identity-and-isolation
- **PRD refs:** FR-001, Access Control section, NFR (per-user isolation)
- **Unlocks:** S-01 (the north star requires "a logged-in user" and owner-scoped persistence); drives the privacy/isolation guardrail risk to near-zero by making ownership a query-level invariant rather than a UI nicety.
- **Prerequisites:** — (baseline already has `spring-boot-starter-security` on the classpath and an empty Liquibase changelog to add the `User` table to)
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced first because the PRD makes "logged-in user" a hard precondition and per-user isolation a domain invariant. Keep it minimal — form/session login + owner-scoping + the `User` entity's first migration — and resist roles/admin (explicit non-goals). Under-building leaves data cross-visible (a guardrail regression); over-building burns the solo capacity budget.
- **Status:** done

### F-02: CI — build & test on every push

- **Outcome:** (foundation) both apps build and their tests — including S-01's deterministic, mocked-LLM e2e — run automatically on every push, giving a stable pass/fail signal in CI.
- **Change ID:** ci-build-and-test
- **PRD refs:** NFR (test-determinism guardrail); delivery order (working flow locally → CI → production)
- **Unlocks:** the automated verification path for S-01's deterministic e2e test; regression safety for S-02 / S-03 / S-04 as they layer onto the core.
- **Prerequisites:** — (the scaffold already builds and has a passing test, so there is no code dependency)
- **Parallel with:** S-02, S-03, S-04
- **Blockers:** —
- **Unknowns:** —
- **Risk:** No hard dependency, but per the stated delivery order CI earns its keep only once S-01's deterministic e2e test exists — so build it alongside the review/history slices, not on the empty scaffold (building it earlier would be introducing infrastructure only because it's "useful later"). Keep it to build + test; production deploy and observability stay Parked (above-MVP).
- **Status:** done

## Slices

### S-01: Analyze & save a pasted contract

- **Outcome:** a logged-in user can paste contract text, give it a title, and submit it; Falcon splits it into clauses, classifies each (risk level low/medium/high, risk type, plain-language rationale), generates at least one negotiation point for the risky clause(s), and saves the owner-scoped result — shown with a visible "supporting analysis, not legal advice" disclaimer and continuous progress feedback during the wait. Empty or unparseable input shows an explanatory state, not a silent empty result.
- **Change ID:** analyze-and-save-contract
- **PRD refs:** US-01, FR-002, FR-003, FR-004, FR-005, FR-006, FR-008; NFRs (per-user isolation, "not legal advice" disclaimer, ~15s p95 with progress feedback, consistently-structured result)
- **Prerequisites:** F-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - Does Spring AI 2.0's structured-output path (`.entity()` / `BeanOutputConverter`) reliably map the model's JSON into the `ClauseAnalysisResult` records against OpenRouter / `openai/gpt-4o`? — Owner: user. Block: no (a spike inside the slice; `docs/clause-classification.md` carries a reference implementation, and a manual JSON-mapping fallback exists if the converter misbehaves).
- **Risk:** This is the irreducible core and the whole product's value — it cannot be split further without either breaking the Primary Success Criterion (which requires the result be *saved*) or slicing by technical layer (forbidden). It carries the load-bearing mocked-LLM e2e test and the riskiest tech (brand-new Spring AI 2.0 GA). Sequenced immediately after the auth gate so the riskiest assumption is exercised first (market-feedback bias).
- **Status:** done

### S-02: Work the negotiation checklist (per-clause status)

- **Outcome:** on a saved analysis, a user can set each clause's decision status (accepted / to-negotiate / rejected) and have it persist — turning the read-only breakdown into a working negotiation checklist, the user's decision surface.
- **Change ID:** clause-decision-status
- **PRD refs:** FR-007
- **Prerequisites:** S-01
- **Parallel with:** S-03, S-04, F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Small vertical add on S-01's analysis view; the load-bearing care is that status changes stay owner-scoped (reuse F-01's invariant, don't reinvent it). Low risk.
- **Status:** done

### S-03: Return to my analysis history

- **Outcome:** a returning user can see a list of their past analyses and reopen any one — strictly owner-scoped. This is the Secondary success signal (usable cross-session return).
- **Change ID:** analysis-history
- **PRD refs:** FR-009; NFR (per-user isolation)
- **Prerequisites:** S-01
- **Parallel with:** S-02, S-04, F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** The Secondary signal, not the Primary — kept because full scope was accepted at a realistic 3-week budget. The load-bearing detail is that the list query must be owner-scoped (another isolation-guardrail surface); a leak here is a guardrail regression even though the feature is "just a list".
- **Status:** done

### S-04: Delete an old analysis

- **Outcome:** a user can delete one of their saved analyses — the MVP's user-driven retention/privacy mechanism (analyses persist until the owner removes them; no automatic expiry).
- **Change ID:** delete-analysis
- **PRD refs:** FR-010; NFR (user-initiated retention, no automatic expiry)
- **Prerequisites:** S-01
- **Parallel with:** S-02, S-03, F-02
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Destructive and privacy-relevant — deletion must be owner-scoped and hard to trigger by accident. Depends only on a saved analysis (S-01); the delete action can be surfaced from both the analysis view and the history list (S-03), but neither is a hard prerequisite — kept parallelizable on purpose so the solo builder always has independent work.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                | Suggested issue title                                                    | Ready for `/10x-plan` | Notes |
| ---------- | ------------------------ | ------------------------------------------------------------------------ | --------------------- | ----- |
| F-01       | identity-and-isolation   | Add registration, login, and per-user data isolation                     | yes                   | Run `/10x-plan identity-and-isolation` — the ready gate for the north star |
| S-01       | analyze-and-save-contract | Analyze a pasted contract: classify clauses + generate negotiation points, saved | no          | The north star; blocked on F-01 |
| S-02       | clause-decision-status   | Let users mark each clause accepted / to-negotiate / rejected            | no                    | After S-01 |
| S-03       | analysis-history         | Show a user their history of past analyses                               | no                    | After S-01 |
| S-04       | delete-analysis          | Let users delete a saved analysis                                        | no                    | After S-01 |
| F-02       | ci-build-and-test        | CI: build both apps and run tests (incl. the deterministic e2e) on push  | no                    | Build alongside S-02 per the delivery order |

## Open Roadmap Questions

None. The PRD closed with zero open questions and a clean shape cross-check, and the framing interview surfaced no cross-cutting decision. The one live technical uncertainty — whether Spring AI 2.0's structured-output path behaves against OpenRouter — is scoped to S-01 as a non-blocking Unknown, because it is resolved *inside* planning that slice (with a documented reference implementation and a fallback), not by a decision that gates the roadmap.

## Parked

*Product scope (from PRD `## Non-Goals`):*

- **PDF / DOCX upload & OCR** — Why parked: text paste only for the MVP; file parsing is a hidden zero-to-one cost that would delay proving the domain rule.
- **Contract-version comparison (diff)** — Why parked: a later capability layered on top of single analyses.
- **Team sharing / collaboration** — Why parked: the MVP is single-tenant per user; isolation is a guardrail.
- **Clause rewriting / redrafting "safe" clauses** — Why parked: Falcon flags and advises; it does not redraft the contract.
- **Template library** — Why parked: out of scope for the first version.
- **PDF / Word export** — Why parked: results live in-app for the MVP.
- **Multi-language / per-jurisdiction localization** — Why parked: one language for the MVP.
- **Custom legal parser** — Why parked: clause splitting stays deliberately simple (numbering / paragraphs); all interpretation is delegated to the model.
- **Admin / moderation tooling** — Why parked: flat single-role model only; no cross-account visibility in the MVP.
- **Automatic data expiry / retention automation** — Why parked: retention is user-driven delete only (see S-04).

*Above-MVP infrastructure (from the delivery order + `infrastructure.md`):*

- **AWS production deployment (EC2 / ECS)** — Why parked: explicitly above-MVP; deploy after the core paste→classify→negotiate flow works locally. `infrastructure.md` holds the researched plan.
- **Langfuse / OpenTelemetry observability** — Why parked: explicitly above-MVP; layered on after the core works.

## Done

- **F-01: (foundation) users can register, log in, and hold a session; every `Analysis` (and its clauses and negotiation points) is scoped to its owner and unreachable by any other authenticated user — isolation enforced at the query/security layer, not just the UI.** — Archived 2026-07-06 → `context/archive/2026-07-04-identity-and-isolation/`. Lesson: —.
- **S-01: a logged-in user can paste contract text, give it a title, and submit it; Falcon splits it into clauses, classifies each (risk level low/medium/high, risk type, plain-language rationale), generates at least one negotiation point for the risky clause(s), and saves the owner-scoped result — shown with a visible "supporting analysis, not legal advice" disclaimer and continuous progress feedback during the wait. Empty or unparseable input shows an explanatory state, not a silent empty result.** — Archived 2026-07-06 → `context/archive/2026-07-06-analyze-and-save-contract/`. Lesson: —.
- **F-02: (foundation) both apps build and their tests — including S-01's deterministic, mocked-LLM e2e — run automatically on every push, giving a stable pass/fail signal in CI.** — Archived 2026-07-06 → `context/archive/2026-07-06-ci-build-and-test/`. Lesson: —.
- **S-03: a returning user can see a list of their past analyses and reopen any one — strictly owner-scoped. This is the Secondary success signal (usable cross-session return).** — Archived 2026-07-08 → `context/archive/2026-07-08-analysis-history/`. Lesson: —.
- **S-02: on a saved analysis, a user can set each clause's decision status (accepted / to-negotiate / rejected) and have it persist — turning the read-only breakdown into a working negotiation checklist, the user's decision surface.** — Archived 2026-07-09 → `context/archive/2026-07-09-clause-decision-status/`. Lesson: —.
