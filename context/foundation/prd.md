---
project: "Falcon"
version: 1
status: draft
created: 2026-06-30
context_type: greenfield
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

# PRD — Falcon

## Vision & Problem Statement

People sign contracts they haven't read in full — or have read but can't tell which clauses work against them: penalty clauses, automatic renewal, no termination right, unilateral changes to terms, broad confidentiality obligations. The person feeling this is a freelancer, small-business owner, or individual signing a B2B, rental, service, or collaboration agreement without a lawyer on staff. The cost today is signing unfavorable terms unknowingly, or spending money and waiting on ad-hoc legal review before every signature.

The insight: the risky patterns in everyday contracts are recognizable and classifiable, and a per-clause risk breakdown paired with concrete, ready-to-use negotiation sentences is more actionable than a generic "have a lawyer look at it." The product's value is the domain decision it makes on every clause — a risk classification and a negotiation recommendation — not the storage of the document itself.

## User & Persona

**Primary persona — the unrepresented signer.** A freelancer, small-business owner, or individual about to sign a B2B / rental / service / collaboration contract, with no in-house or on-retainer lawyer. They reach for Falcon at the moment just before signing, when they want to quickly understand what to watch out for and how to prepare for negotiation. Their very first action is to paste a contract and get back a list of risky clauses. The value they take away: reduced risk of signing an unfavorable contract, plus a ready list of points to raise.

## Success Criteria

### Primary
- The end-to-end flow works: a logged-in user pastes a contract that contains an explicitly risky clause (e.g. automatic renewal); the analysis is saved; the clause is flagged as risky (with risk level, type, and rationale); and at least one negotiation point is generated for it.

### Secondary
- Per-user history is usable: a user returns in a later session and sees their past analyses. Nice to have, but not sufficient on its own to prove the product's value — the classification flow is.

### Guardrails
- **Privacy / isolation** — a user's pasted contract text and analyses are never visible to any other user. Contracts are sensitive data; this is a domain requirement, not just a feature.
- **Positioning** — the product visibly communicates that it provides supporting analysis, *not* legal advice. Failing to do so is a regression even if classification works.
- **Test determinism** — the primary flow must be verifiable by an automated test whose model response is fixed, so the pass/fail signal is stable in continuous integration.

## User Stories

### US-01: Signer analyzes a pasted contract

- **Given** a logged-in user who has pasted the text of a contract containing at least one risky clause
- **When** they submit it for analysis
- **Then** the contract is split into clauses, each clause is classified with a risk level (low / medium / high), a risk type, and a short rationale, at least one negotiation point is generated for the risky clause(s), and the completed analysis is saved to their history

#### Acceptance Criteria
- A clause with an obviously risky pattern (e.g. automatic renewal, penalty) is classified as medium or high risk, never low.
- Every risky clause surfaces a rationale the user can read without legal training.
- An empty or unparseable submission shows an explanatory state, not a silent empty result.
- The saved analysis is retrievable later by its owner and only its owner.

## Functional Requirements

### Authentication & access
- FR-001: User can register for an account and log in to access the application. Priority: must-have
  > Socratic: Counter-argument considered — "auth adds zero-to-one cost before any value is visible; a public demo would ship faster." Resolution: kept — contracts are sensitive data, so per-user isolation is a domain requirement, not optional polish; the login is the boundary that makes isolation real. Sign-up is open self-serve.

### Contract analysis
- FR-002: User can paste contract text and submit it for analysis. Priority: must-have
  > Socratic: Counter-argument — "text paste excludes the most common real-world input, a PDF." Resolution: kept as-is; PDF/OCR is an explicit non-goal for the MVP to avoid a hidden zero-to-one cost. Paste proves the domain rule.
- FR-003: User can give an analysis a title when creating it. Priority: must-have
- FR-004: Application can split submitted contract text into individual clauses. Priority: must-have
  > Socratic: Counter-argument — "clause boundaries are legally subtle; a naive split will mis-segment." Resolution: kept, deliberately simple — split by numbering / paragraphs and delegate all interpretation to the model. Building a legal parser is a named non-goal.
- FR-005: Application can classify each clause with a risk level (low / medium / high), a risk type (e.g. penalty, automatic renewal, no-termination, unilateral change), and a short rationale. Priority: must-have
  > Socratic: Counter-argument — "risk is contextual; a fixed three-level scale may over- or under-state it." Resolution: kept; a coarse, explainable scale with a rationale is more useful to a non-lawyer than a false-precision score.
- FR-006: Application can generate a list of negotiation points, each linked to a clause. Priority: must-have
  > Socratic: Counter-argument — "generic negotiation advice could mislead a user into a false sense of legal safety." Resolution: kept, gated by the guardrail that the interface positions this as supporting analysis, not legal advice.

### Review & persistence
- FR-007: User can change the decision status of each clause (e.g. accepted / to-negotiate / rejected). Priority: must-have
  > Socratic: Counter-argument — "per-clause status is bookkeeping the user may not want." Resolution: kept; the status is what turns a read-only report into a working negotiation checklist — it is the user's decision surface.
- FR-008: User can save a completed analysis. Priority: must-have
  > Socratic: Counter-argument — "if analysis is cheap to re-run, saving is redundant." Resolution: kept; saving is what makes history and cross-session return possible, and model re-runs are neither free nor deterministic.
- FR-009: User can view a history of their past analyses. Priority: must-have
  > Socratic: Counter-argument — "history is a v2 nicety, not proof of value." Resolution: kept; it is the confirmed Secondary success signal (not the Primary). Full scope was accepted at a realistic 3-week timeline, so it stays in the MVP.
- FR-010: User can delete an old analysis. Priority: must-have
  > Socratic: Counter-argument — "delete is trivial CRUD, not worth an FR." Resolution: kept; for sensitive contract data, user-initiated deletion is the MVP's retention mechanism (analyses are kept until the user removes them), a privacy affordance rather than mere CRUD.

## Non-Functional Requirements

- A user's pasted contract text and resulting analyses are accessible only to that user; no other authenticated user can reach them.
- A user's saved analyses persist until that user deletes them; deletion is user-initiated and removes the analysis (no automatic expiry in the MVP).
- The interface visibly and unambiguously communicates that Falcon provides supporting analysis, not legal advice, wherever an analysis result is shown.
- On submitting an analysis, the user receives immediate acknowledgement and continuous visible feedback during the wait, with the full result delivered within roughly 15 seconds at the 95th percentile.
- Every analysis produces a consistently structured result (each clause carries a risk level, a risk type, and a rationale; risky clauses carry negotiation points), so results are predictable to the user and verifiable by an automated test.

## Business Logic

Falcon classifies each clause of a pasted contract by risk level and risk type, and generates negotiation recommendations with justifications.

The rule consumes one user-facing input: the raw text of a contract the user pastes. Its output is, for each clause, a risk classification (a level, a type, and a short rationale) and — for clauses that carry risk — one or more negotiation points the user can raise before signing. The user encounters this as an annotated, reviewable breakdown of their own contract: every clause is shown with its risk assessment, and the risky ones come with concrete sentences to negotiate. The user then works through the breakdown, marking each clause's status, and saves the result.

Without this per-clause decision the product would be empty storage; the classification and recommendation are what make it worth using rather than a spreadsheet.

## Access Control

Multi-user with login and **open self-serve sign-up** — anyone can register and create an account. The user model is **flat and single-role**: every user is equal, and each user can see only their own analyses (and the clauses and negotiation points within them). There are no admin or guest tiers in the MVP. The domain justification is intrinsic: contracts are sensitive data, so per-user isolation is a property of the domain, not a bolted-on requirement. An unauthenticated visitor hitting a gated route is sent to sign in.

## Non-Goals

- **No PDF/DOCX upload or OCR** — text paste only for the MVP; file parsing is a hidden zero-to-one cost that would delay proving the domain rule.
- **No contract-version comparison (diff)** — a later capability layered on top of single analyses.
- **No team sharing or collaboration** — the MVP is single-tenant per user; isolation is a guardrail.
- **No generation of corrected / rewritten "safe" clauses** — Falcon flags and advises; it does not redraft the contract.
- **No template library** — out of scope for the first version.
- **No report export to PDF / Word** — results live in-app for the MVP.
- **No multi-language support or per-jurisdiction legal localization** — one language for the MVP.
- **No custom legal parser** — clause splitting stays deliberately simple (numbering / paragraphs); all interpretation is delegated to the model. Building a legal parser is a hidden high zero-to-one cost. *(Functional non-goal / buy-vs-build decision, not a technology avoid.)*
- **No admin/moderation tooling** — flat single-role model only; no cross-account visibility in the MVP.
- **No automatic data expiry / retention automation** — retention is user-driven delete only for the MVP.

## Open Questions

None outstanding. All product decisions surfaced during shaping were resolved with the user in the closing round of `/10x-shape` (timeline, secondary success signal, perceived latency, role model, sign-up flow, data retention, working mode), and the quality cross-check passed as `accepted`. This PRD inherits a clean slate.
