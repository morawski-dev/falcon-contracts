---
project: Falcon
version: 1
status: draft
created: 2026-07-04
updated: 2026-07-13
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
| S-04  | delete-analysis          | delete one of their saved analyses                              | S-01          | FR-010                                          | done |
| F-02  | ci-build-and-test        | (foundation) build + tests run automatically on every push      | —             | test-determinism guardrail                      | done |
| S-05  | app-navigation-header    | return to their dashboard from any authenticated screen         | S-01, S-03    | — (navigation gap; supports US-01, FR-009)      | done |
| S-06  | ui-design-system         | read the risk report as a marked-up contract, in one coherent visual identity | S-01, S-02, S-03, S-04, S-05 | — (quality/trust gap; serves US-01, FR-004, FR-006, NFR a11y) | done |
| S-07  | analysis-closing-action  | finish reading a report and be shown the way back to their analyses | S-05, S-06    | — (affordance gap; serves US-01, FR-009)        | done     |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks. After S-01 lands, Streams B and C run in parallel with the tail of Stream A — the deliberate lever for a capacity-constrained solo builder.

| Stream | Theme                | Chain                          | Note                                                                    |
| ------ | -------------------- | ------------------------------ | ----------------------------------------------------------------------- |
| A      | Core value chain     | `F-01` → `S-01` → `S-02`       | The north-star path: auth gate → prove the domain rule → turn the report into a working checklist. |
| B      | History & retention  | `S-03` / `S-04`                | Both branch from `S-01` (join Stream A at `S-01`); the Secondary signal + user-driven deletion. |
| C      | Verification         | `F-02`                         | Standalone enabler; automates S-01's deterministic e2e test and guards S-02–S-04. Built alongside Stream B. |
| D      | Navigation & shell   | `S-05` → `S-07`                | Joins after Streams A and B land — the screens must exist before the shell that connects them. `S-05` made the way back *possible* (a persistent header); `S-07` makes it *findable* at the moment the user actually needs it. |
| E      | Identity & craft     | `S-06`                         | Runs last by construction: a design system is only coherent across screens that all exist. Converts a feature-complete MVP into one that looks like a product a user would trust with a contract. |

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
- **Status:** done

### S-05: Return to the dashboard from anywhere

- **Outcome:** from any authenticated screen — the new-analysis form (`/analyses/new`) and the analysis result (`/analyses/[id]`) — a logged-in user can navigate back to their dashboard via a **persistent app header**: the "Falcon" wordmark links to `/dashboard`, and logout is consolidated into that header. The header is present on every authenticated screen and absent from login/register. Purely client-side navigation — no new endpoint, no schema change, no change to the owner-scoping invariant.
- **Change ID:** app-navigation-header
- **PRD refs:** none direct — a navigation/usability gap found during rollout, not an unbuilt requirement. It serves the unstated movement assumption behind US-01 (paste → read the result → carry on) and FR-009 (history is only *reachable* if something links back to it).
- **Prerequisites:** S-01 (the analyze screens must exist), S-03 (the dashboard hub must exist) — both `done`, so this is immediately ready for `/10x-plan`.
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Thin and frontend-only. Today there is no authenticated shell — only the root `app/layout.tsx` — so the header cannot simply go there or it would leak onto `(auth)/login` and `(auth)/register`; the slice must introduce an authenticated route-group layout (e.g. `(app)/layout.tsx`) covering `dashboard` + `analyses/*` without regressing the existing per-screen layouts. The dashboard's current "Falcon" + logout card becomes redundant and should shed that chrome rather than grow a second logout. Keep the header minimal (wordmark → dashboard, logout); resist a full nav menu, breadcrumbs, or a sidebar (YAGNI). Do not touch the backend.
- **Status:** done

### S-06: Read the report as a marked-up contract

- **Outcome:** a user meets one coherent, deliberate visual identity on every screen they touch — login, register, dashboard, new-analysis, analysis result — instead of the untouched scaffold theme. The centrepiece is the **analysis result**: it stops being a stack of generic cards and becomes what it actually is, *a contract someone marked up*. Each clause sits in a document column with a **margin redline** — a rule in the left gutter whose ink and weight encode severity, next to the clause reference and the risk level spelled out in words. Risk stops being three hardcoded Tailwind swatches in a TypeScript map and becomes a **first-class semantic token** in the theme. No backend change, no schema change, no new endpoint, no change to the owner-scoping invariant.
- **Change ID:** ui-design-system
- **PRD refs:** none direct — a quality/trust gap, not an unbuilt requirement. It serves US-01 (the result must be *readable* to be actionable), FR-004 / FR-006 (risk level and rationale are the product's meaning — they deserve the strongest visual treatment, not a stock badge), and the accessibility/NFR floor. The "not legal advice" disclaimer (NFR) must remain unmissable under the new styling, not decoratively softened.
- **Prerequisites:** S-01, S-02, S-03, S-04, S-05 — all `done`. A design system is only coherent across screens that all exist; sequencing this before the screens would mean designing against imagined content and restyling every slice as it landed.
- **Parallel with:** —
- **Blockers:** —
- **Design direction (locked 2026-07-13, via `frontend-design`):** *Redline / Kancelaria* — Falcon as the marked-up contract itself.
  - **Palette:** paper `#F7F6F2`, ink `#14161C`, stamp-violet `#4C3FA6` (the violet of a Polish official stamp — the accent, used sparingly). Risk inks are desaturated, as if bled into paper: moss `#5C6B4A` (niskie), ochre `#A9762B` (średnie), oxblood `#8E2C25` (wysokie). Deliberately *not* a saturated traffic light.
  - **Type:** Fraunces for display (wordmark and page titles **only** — restraint is the point), IBM Plex Sans for body/UI, IBM Plex Mono for clause references and risk-type labels. Legal citation is tabular by nature; the mono is structural, not decorative.
  - **Signature:** the margin redline. Severity is encoded **redundantly** — rule weight *and* ink *and* the level spelled out in words — never by color alone.
  - **Structure:** clause numbering (`§1`, `§2` …) is earned, not decoration: contract clauses genuinely *are* an ordered sequence, and the reference is how a user points at one when they call the other side.
- **Unknowns:**
  - Do Fraunces and IBM Plex Sans/Mono ship a `latin-ext` subset covering Polish diacritics (ą ę ł ń ó ś ź ż) via `next/font/google`? — Owner: user. Block: no (verified during planning; IBM Plex is known-strong here, and if Fraunces falls short the display role swaps to a Plex Serif or Bricolage Grotesque without disturbing the rest of the system).
  - Do the risk inks clear WCAG AA contrast against paper at body size? — Owner: user. Block: no (tune lightness within the hue; redundant text/weight encoding means a failure degrades legibility, not correctness).
- **Risk:** **This is a cross-cutting slice, and that is a deliberate exception — read the justification.** The roadmap forbids slicing by technical layer, and at a glance "restyle the app" looks like exactly that. It isn't: the outcome is end-to-end and user-visible on every screen, and it removes no layer from any feature. It is a whole-product quality change that is only *possible* now that all five feature slices exist. Sequenced last for that reason.

  The load-bearing constraint is the **E2E suite** (`frontend/e2e/`, 8 specs). Because `CLAUDE.md` mandates accessibility-first locators, every assertion is `getByRole` / `getByLabel` / `getByText` — so a restyle is survivable *provided the accessible name surface is preserved*. Concretely, the redesign must keep: the risk level as literal on-screen text (`Wysokie` / `Średnie` / `Niskie` — do **not** abbreviate to `WYS.` in the margin, which is also the WCAG requirement); the `Punkt do negocjacji` label; the clause `data-testid="clause-{id}"` hooks; the decision controls as `button`s carrying `aria-pressed` inside a `role="group"` whose accessible name contains the risk-type label; and the `Falcon` wordmark as a link. Redesigning *through* these constraints rather than around them is the job — a green E2E run is the slice's own verification that no meaning was lost in the restyle.

  The real failure mode is scope: this slice can quietly become "rebuild the frontend." It must not add screens, states, or features. The other is timidity — swapping the palette while leaving the card stack intact would be a repaint, not the outcome. Spend the boldness in exactly one place (the margin redline) and keep everything around it quiet.
- **Status:** done

### S-07: Finish the report and be shown the way back

- **Outcome:** a user who has read to the end of an analysis (`/analyses/[id]`) is offered an explicit, labelled way onward — a closing action at the foot of the report that returns them to their analyses on the dashboard, rather than leaving the clause column ending in nothing. The same explicitness is applied to the app header, so the route back reads as *"moje analizy"* and not as *"a logo that happens to be clickable"*. Frontend-only: no new endpoint, no schema change, no change to the owner-scoping invariant.
- **Change ID:** analysis-closing-action
- **PRD refs:** none direct — an affordance gap found in use, not an unbuilt requirement. It serves the movement assumption behind US-01 (paste → read the result → carry on) and FR-009 (a history that exists but is not *findable from where the user is standing* does not deliver its outcome).
- **Prerequisites:** S-05 (the persistent header and the `(app)` route group exist and are the thing being made legible), S-06 (the visual system the closing action must be expressed in) — both `done`, so this is immediately ready for `/10x-plan`.
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** **Read the diagnosis before planning — the naive reading of this slice is wrong.** The way back is *not* missing. `S-05` shipped a sticky header (`frontend/src/components/app-header.tsx`) whose "Falcon" wordmark already links to `/dashboard` and never scrolls out of view. So the failure is not a missing link, and the fix is **not** a second link to the dashboard bolted next to the first one — that would add clutter and leave the original problem intact.

  The actual failure is **affordance**, in two places. First, a wordmark reads as branding; users do not scan a logo for "back to my analyses", they scan for a *label that names the destination*. Second, the report is a long single column (`frontend/src/app/(app)/analyses/[id]/page.tsx`) that simply stops after the last clause — at the exact moment the user has finished the task and is looking for "what now", the page offers nothing, and the only exit is a upward eye-movement to a logo. Right after creating an analysis this is at its worst, because the user arrived by a redirect and has no mental model of where they are in the app.

  So the slice's job is to make the existing route back *legible*: a closing action where the reading ends, and a header link that says what it does. Keep it minimal — resist breadcrumbs, a sidebar, a nav menu, or a "back" that abuses browser history (`router.back()` would send the post-creation user to the *form*, not the dashboard — a real trap here, since the redirect means there is no meaningful history entry). Prefer a plain `next/link` to `/dashboard`, styled inside S-06's system rather than against it.

  E2E constraint (same as S-06): `CLAUDE.md` mandates accessibility-first locators, so the existing suite (`frontend/e2e/`) asserts on accessible names. Adding a *second* element whose accessible name matches an existing one (e.g. two things reachable as `Falcon`, or two links named the same) will make a `getByRole` locator strict-mode-ambiguous and break passing specs. Name the new action distinctly, and add its own spec asserting the end-of-report route back — a green suite is the slice's verification.
- **Status:** done

## Backlog Handoff

| Roadmap ID | Change ID                | Suggested issue title                                                    | Ready for `/10x-plan` | Notes |
| ---------- | ------------------------ | ------------------------------------------------------------------------ | --------------------- | ----- |
| F-01       | identity-and-isolation   | Add registration, login, and per-user data isolation                     | yes                   | Run `/10x-plan identity-and-isolation` — the ready gate for the north star |
| S-01       | analyze-and-save-contract | Analyze a pasted contract: classify clauses + generate negotiation points, saved | no          | The north star; blocked on F-01 |
| S-02       | clause-decision-status   | Let users mark each clause accepted / to-negotiate / rejected            | no                    | After S-01 |
| S-03       | analysis-history         | Show a user their history of past analyses                               | no                    | After S-01 |
| S-04       | delete-analysis          | Let users delete a saved analysis                                        | no                    | After S-01 |
| F-02       | ci-build-and-test        | CI: build both apps and run tests (incl. the deterministic e2e) on push  | no                    | Build alongside S-02 per the delivery order |
| S-05       | app-navigation-header    | Let users return to the dashboard from any authenticated screen          | yes                   | Run `/10x-plan app-navigation-header` — prerequisites (S-01, S-03) are done |
| S-06       | ui-design-system         | Give Falcon one coherent visual identity; render the report as a marked-up contract | yes       | Run `/10x-plan ui-design-system` — all prerequisites done. Design direction is locked in the slice; plan against it, don't re-open it. |
| S-07       | analysis-closing-action  | Close the loop at the end of an analysis: an explicit way back to my analyses | yes                   | Run `/10x-plan analysis-closing-action` — prerequisites (S-05, S-06) are done. The link back already exists; the slice is about affordance, not a new route — read the Risk note before planning. |

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
- **S-04: a user can delete one of their saved analyses — the MVP's user-driven retention/privacy mechanism (analyses persist until the owner removes them; no automatic expiry).** — Archived 2026-07-10 → `context/archive/2026-07-10-delete-analysis/`. Lesson: —.
- **S-05: from any authenticated screen — the new-analysis form (`/analyses/new`) and the analysis result (`/analyses/[id]`) — a logged-in user can navigate back to their dashboard via a **persistent app header**: the "Falcon" wordmark links to `/dashboard`, and logout is consolidated into that header. The header is present on every authenticated screen and absent from login/register. Purely client-side navigation — no new endpoint, no schema change, no change to the owner-scoping invariant.** — Archived 2026-07-12 → `context/archive/2026-07-12-app-navigation-header/`. Lesson: —.
- **S-06: a user meets one coherent, deliberate visual identity on every screen they touch — login, register, dashboard, new-analysis, analysis result — instead of the untouched scaffold theme. The centrepiece is the **analysis result**: it stops being a stack of generic cards and becomes what it actually is, *a contract someone marked up*. Each clause sits in a document column with a **margin redline** — a rule in the left gutter whose ink and weight encode severity, next to the clause reference and the risk level spelled out in words. Risk stops being three hardcoded Tailwind swatches in a TypeScript map and becomes a **first-class semantic token** in the theme. No backend change, no schema change, no new endpoint, no change to the owner-scoping invariant.** — Archived 2026-07-13 → `context/archive/2026-07-13-ui-design-system/`. Lesson: —.
- **S-07: a user who has read to the end of an analysis (`/analyses/[id]`) is offered an explicit, labelled way onward — a closing action at the foot of the report that returns them to their analyses on the dashboard, rather than leaving the clause column ending in nothing. The same explicitness is applied to the app header, so the route back reads as *"moje analizy"* and not as *"a logo that happens to be clickable"*. Frontend-only: no new endpoint, no schema change, no change to the owner-scoping invariant.** — Archived 2026-07-13 → `context/archive/2026-07-13-analysis-closing-action/`. Lesson: —.
