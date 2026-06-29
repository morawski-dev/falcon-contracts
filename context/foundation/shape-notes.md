---
project: "Falcon"
context_type: greenfield
created: 2026-06-29
updated: 2026-06-29
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "context type"
      decision: "greenfield — the only signal was a 2-commit, README-only git history; no code, manifests, or dependencies exist yet"
    - topic: "empty-CRUD anti-pattern"
      decision: "not triggered — real domain rule (clause risk classification + scoring + negotiation-point generation)"
    - topic: "timeline discipline"
      decision: "mvp_weeks = 3, full scope kept; user judged ~3 weeks after-hours realistic; ≤3-week gate satisfied, no scope cut, no acknowledgment block required"
    - topic: "working mode"
      decision: "after-hours only; no hard deadline"
    - topic: "access model"
      decision: "confirmed flat single-role user model; open self-serve sign-up; per-user data isolation"
    - topic: "clause parsing scope"
      decision: "simple split by numbering/paragraphs; classification intelligence delegated to the model — no custom legal parser (explicit in source)"
    - topic: "secondary success signal"
      decision: "per-user history is usable (user returns and sees past analyses)"
    - topic: "perceived latency"
      decision: "immediate acknowledgement + visible progress; full analysis result within ~15s p95"
    - topic: "data retention"
      decision: "user-initiated delete only; analyses kept until the user removes them"
    - topic: "artifact language"
      decision: "English — matches the PRD schema section names and downstream tooling; source doc was Polish"
  frs_drafted: 9
  quality_check_status: accepted
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: null
  after_hours_only: true
---

# Shape Notes — Falcon

> Facilitator's note: this file was structured from a rich seed document
> (`docs/vision.md`). All product decisions the schema needs were resolved with
> the user in an interactive round; nothing below is invented. The cross-check
> passed clean (`accepted`) — there are no outstanding Open Questions.

Seed idea (verbatim intent): *A user pastes contract text; the application
classifies clauses by risk and generates a list of negotiation points with
justifications.* Repo: `falcon-contracts`.

---

## Vision & Problem Statement

People sign contracts they haven't read in full — or have read but can't tell
which clauses work against them: penalty clauses, automatic renewal, no
termination right, unilateral changes to terms. The person feeling this is a
freelancer, small-business owner, or individual signing a B2B, rental, or
collaboration agreement without a lawyer on staff. The cost today is signing
unfavorable terms unknowingly, or burning time and money on ad-hoc legal review
before every signature.

The insight: the risky patterns in everyday contracts are recognizable and
classifiable, and a per-clause risk breakdown paired with concrete, ready-to-use
negotiation sentences is more actionable than a generic "have a lawyer look at
it." The product's value is the domain decision it makes on every clause — a
risk classification and a negotiation recommendation — not the storage of the
document itself.

## User & Persona

**Primary persona — the unrepresented signer.** A freelancer, small-business
owner, or individual about to sign a B2B / rental / collaboration contract, with
no in-house or on-retainer lawyer. They reach for Falcon at the moment just
before signing, when they want to quickly understand what to watch out for.
Their very first action is to paste a contract and get back a list of risky
clauses.

## Success Criteria

### Primary
- The end-to-end flow works: a logged-in user pastes a contract that contains an
  explicitly risky clause (e.g. automatic renewal); the analysis is saved; the
  clause is flagged as risky (with risk level, type, and rationale); and at least
  one negotiation point is generated for it.

### Secondary
- Per-user history is usable: a user returns in a later session and sees their
  past analyses. Nice to have, but not sufficient on its own to prove the
  product's value — the classification flow is.

### Guardrails
- **Privacy / isolation** — a user's pasted contract text and analyses are never
  visible to any other user. Contracts are sensitive data; this is a domain
  requirement, not just a feature.
- **Positioning** — the product visibly communicates that it provides supporting
  analysis, *not* legal advice. Failing to do so is a regression even if
  classification works.
- **Test determinism** — the primary flow must be verifiable by an automated test
  whose model response is mocked, so the pass/fail signal is stable in CI.

## User Stories

### US-01: Signer analyzes a pasted contract

- **Given** a logged-in user who has pasted the text of a contract containing at
  least one risky clause
- **When** they submit it for analysis
- **Then** the contract is split into clauses, each clause is classified with a
  risk level (low / medium / high), a risk type, and a short rationale, at least
  one negotiation point is generated for the risky clause(s), and the completed
  analysis is saved to their history

#### Acceptance Criteria
- A clause with an obviously risky pattern (e.g. automatic renewal, penalty) is
  classified as medium or high risk, never low.
- Every risky clause surfaces a rationale the user can read without legal
  training.
- An empty or unparseable submission shows an explanatory state, not a silent
  empty result.
- The saved analysis is retrievable later by its owner and only its owner.

## Functional Requirements

### Authentication & access
- FR-001: User can register for an account and log in to access the application. Priority: must-have
  > Socratic: Counter-argument considered — "auth adds zero-to-one cost before
  > any value is visible; a public demo would ship faster." Resolution: kept —
  > contracts are sensitive data, so per-user isolation is a domain requirement,
  > not optional polish; the login is the boundary that makes isolation real.
  > Sign-up is open self-serve.

### Contract analysis
- FR-002: User can paste contract text and submit it for analysis. Priority: must-have
  > Socratic: Counter-argument — "text paste excludes the most common real-world
  > input, a PDF." Resolution: kept as-is; PDF/OCR is an explicit non-goal for
  > the MVP to avoid a hidden zero-to-one cost. Paste proves the domain rule.
- FR-003: Application can split submitted contract text into individual clauses. Priority: must-have
  > Socratic: Counter-argument — "clause boundaries are legally subtle; a naive
  > split will mis-segment." Resolution: kept, deliberately simple — split by
  > numbering / paragraphs and delegate all interpretation to the model. Building
  > a legal parser is a named non-goal.
- FR-004: Application can classify each clause with a risk level (low / medium / high), a risk type (e.g. penalty, automatic renewal, no-termination, unilateral change), and a short rationale. Priority: must-have
  > Socratic: Counter-argument — "risk is contextual; a fixed three-level scale
  > may over- or under-state it." Resolution: kept; a coarse, explainable scale
  > with a rationale is more useful to a non-lawyer than a false-precision score.
- FR-005: Application can generate a list of negotiation points, each linked to a clause. Priority: must-have
  > Socratic: Counter-argument — "generic negotiation advice could mislead a user
  > into a false sense of legal safety." Resolution: kept, gated by the guardrail
  > that the UI positions this as supporting analysis, not legal advice.

### Review & persistence
- FR-006: User can change the decision status of each clause (e.g. accepted / to-negotiate / rejected). Priority: must-have
  > Socratic: Counter-argument — "per-clause status is bookkeeping the user may
  > not want." Resolution: kept; the status is what turns a read-only report into
  > a working negotiation checklist — it is the user's decision surface.
- FR-007: User can save a completed analysis. Priority: must-have
  > Socratic: Counter-argument — "if analysis is cheap to re-run, saving is
  > redundant." Resolution: kept; saving is what makes history and cross-session
  > return possible, and LLM re-runs are neither free nor deterministic.
- FR-008: User can view a history of their past analyses. Priority: must-have
  > Socratic: Counter-argument — "history is a v2 nicety, not proof of value."
  > Resolution: kept; it is the confirmed Secondary success signal (not the
  > Primary). Full scope was accepted at a realistic 3-week timeline, so it stays
  > in the MVP.
- FR-009: User can delete an old analysis. Priority: must-have
  > Socratic: Counter-argument — "delete is trivial CRUD, not worth an FR."
  > Resolution: kept; for sensitive contract data, user-initiated deletion is the
  > MVP's retention mechanism (analyses are kept until the user removes them), a
  > privacy affordance rather than mere CRUD.

## Non-Functional Requirements

- A user's pasted contract text and resulting analyses are accessible only to
  that user; no other authenticated user can reach them.
- A user's saved analyses persist until that user deletes them; deletion is
  user-initiated and removes the analysis (no automatic expiry in the MVP).
- The interface visibly and unambiguously communicates that Falcon provides
  supporting analysis, not legal advice, wherever an analysis result is shown.
- On submitting an analysis, the user receives immediate acknowledgement and
  continuous visible progress during the wait, with the full result delivered
  within roughly 15 seconds at the 95th percentile.
- Every analysis produces a consistently structured result (each clause carries a
  risk level, a risk type, and a rationale; risky clauses carry negotiation
  points), so results are predictable to the user and verifiable by an automated
  test.

## Business Logic

Falcon classifies each clause of a pasted contract by risk level and risk type,
and generates negotiation recommendations with justifications.

The rule consumes one user-facing input: the raw text of a contract the user
pastes. Its output is, for each clause, a risk classification (a level, a type,
and a short rationale) and — for clauses that carry risk — one or more negotiation
points the user can raise before signing. The user encounters this as an
annotated, reviewable breakdown of their own contract: every clause is shown with
its risk assessment, and the risky ones come with concrete sentences to
negotiate. The user then works through the breakdown, marking each clause's
status, and saves the result.

*Empty-CRUD check: not triggered. The application makes a real domain decision on
every clause (classify → score → recommend); it does not merely store records the
user could keep in a spreadsheet.*

## Access Control

Multi-user with login and **open self-serve sign-up** — anyone can register and
create an account. The user model is **flat and single-role**: every user is
equal, and each user can see only their own analyses (and the clauses and
negotiation points within them). There are no admin or guest tiers in the MVP.
The domain justification is intrinsic: contracts are sensitive data, so per-user
isolation is a property of the domain, not a bolted-on requirement. An
unauthenticated visitor hitting a gated route is sent to sign in.

## Non-Goals

- **No PDF upload or OCR** — text paste only for the MVP; PDF/OCR is a hidden
  zero-to-one cost that would delay proving the domain rule.
- **No contract-version comparison** — a v2 capability layered on top of single
  analyses.
- **No team sharing or collaboration** — the MVP is single-tenant per user;
  isolation is a guardrail.
- **No generation of corrected / rewritten clauses** — Falcon flags and advises;
  it does not redraft the contract.
- **No template library** — out of scope for the first version.
- **No Word / document export** — results live in-app for the MVP.
- **No multi-language support** — one language for the MVP.
- **No custom legal parser** — clause splitting stays deliberately simple
  (numbering / paragraphs); all interpretation is delegated to the model. Building
  a legal parser is a hidden high zero-to-one cost. *(Functional non-goal /
  buy-vs-build decision, not a technology avoid.)*
- **No admin/moderation tooling** — flat single-role model only; no cross-account
  visibility in the MVP.
- **No automatic data expiry / retention automation** — retention is user-driven
  delete only for the MVP.

## Open Questions

None outstanding. All product decisions surfaced during shaping were resolved
with the user in the closing round (timeline, secondary success signal, perceived
latency, role model, sign-up flow, data retention, working mode). Downstream
`/10x-prd` therefore inherits a clean slate.

---

## Quality cross-check (soft gate)

Run against the captured content per the skill's Step 7. Status: **accepted** —
all greenfield elements present, no open gaps.

```
═══════════════════════════════════════════════════════════
  QUALITY CROSS-CHECK
═══════════════════════════════════════════════════════════

  Access Control:      present — open self-serve sign-up; flat single-role;
                       per-user isolation
  Business Logic:      present — one-sentence rule captured; empty-CRUD NOT
                       triggered
  Project artifacts:   present — shape-notes.md with valid checkpoint
  Timeline-cost ack:   present — mvp_weeks = 3 (≤ 3), realistic full scope
                       accepted deliberately
  Non-Goals:           present — 10 explicit scope avoids
  Preserved behavior:  n/a (greenfield)

═══════════════════════════════════════════════════════════
```

No gaps to carry forward. `/10x-prd` will find no warnings to mirror into its
`## Open Questions`.

---

## Forward: tech-stack

*Not part of the PRD schema. Captured verbatim from `docs/vision.md` because the
user volunteered a full stack; the PRD stays stack-agnostic and the tech-stack
selection step downstream picks this up. Preserved so nothing is lost.*

**Backend (Java)**
- Language: Java 25 LTS (records, pattern matching, virtual threads, scoped
  values, instance main methods)
- Build: Maven 3.9+ (Spring AI BOMs favor Maven; Gradle also works)
- Framework: Spring Boot 4.0.7 (GA; requires Java 17+, officially supported up to
  Java 26, on Spring Framework 7.0.x — so Java 25 is a first-class target, not
  just "forward-compatible" as it was on Boot 3.5.x). Latest 4.x line is 4.1.0;
  4.0.7 chosen as a stable patch in the more-settled 4.0.x line.
- AI: Spring AI BOM 2.0.0 (GA — confirmed on Maven Central 2026-07-03; the 2.0.x
  line officially supports Spring Boot 4.0.x and 4.1.x). Migration note: Spring AI
  2.0 removes `FunctionCallback` (→ `ToolCallback`) and requires MCP Java SDK
  1.0.x — likely irrelevant here since Falcon uses structured output, not
  tool-calling. Verify the `BeanOutputConverter` / `.entity()` structured-output
  pattern against 2.0 docs before relying on it.
- AI starter: `spring-ai-starter-model-openai` (OpenRouter is OpenAI-compatible;
  use the `openai` starter with `base-url=https://openrouter.ai/api/v1`)
- Observability: `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp` (OTel BOM 2.17.0),
  `opentelemetry-spring-boot-starter`, `spring-boot-starter-actuator` — traces to
  Langfuse
- Data: PostgreSQL (primary DB), Spring Data JPA + Hibernate (ORM),
  Spring Security (authN/authZ), Liquibase (schema migrations)

**Frontend**
- Next.js (React), TypeScript, Tailwind CSS, Shadcn/ui

**AI integration**
- Spring AI; OpenRouter (LLM access — GPT-4o, Gemini, Claude, …); Langfuse (LLM
  call observability)

**Infrastructure**
- Docker Compose (local dev), GitHub Actions (CI/CD), AWS (production)

**Technical tips carried from source**
- Force structured LLM output from the start — model returns JSON on a fixed
  schema (`clauses: [{text, riskLevel, riskType, rationale}]`); Spring AI's
  `BeanOutputConverter` maps to Java objects so no manual text parsing, which also
  eases testing.
- Keep clause-splitting simple (split by numbering / paragraphs); leave all
  "intelligence" to the model. Do not build a legal parser.
- UI disclaimer: present Falcon as a supporting tool, not legal advice (fits the
  domain and demos well).

## Forward: technical-roadmap

*Not part of the PRD schema. Sequencing and downstream concerns captured so a
later chain step can pick them up.*

- **Deployment sequencing** — start local on Docker Compose + Postgres; then a CI
  pipeline; then AWS production. Production deployment and full observability
  (Langfuse + OTel) are explicitly *beyond minimum* — layered on after the core
  works locally.
- **CI/CD** — GitHub Actions: build (Maven backend + Next.js frontend) → tests →
  (optional) build Docker images.
- **Testing strategy** — an e2e test of the primary flow with a **mocked LLM
  response** for determinism (a logged-in user pastes a contract with an
  obviously risky clause → analysis saved → clause flagged risky → negotiation
  point present).
- **Companion artifacts to produce** — a clause-classification prompt
  (system + user prompt as agent context) and a structured-output schema spec
  (the LLM response record: list of clauses with risk / type / rationale fields).
