<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: UI Design System — "Redline / Kancelaria"

- **Plan**: context/changes/ui-design-system/plan.md
- **Scope**: All 4 phases (full plan review)
- **Date**: 2026-07-13
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Method

Two parallel sub-agents: one traced every planned Contract against the actual file for all 4 phases; one independently recomputed every claimed contrast ratio and compiled `globals.css` through the real Tailwind v4 pipeline to check which utility classes actually generate CSS. One claim from the safety agent (a dashboard "auth-guard removal") was verified against git history and found to be a **false positive** — it compared against `dae5e54`, a commit from the unrelated prior `app-navigation-header` feature, two commits before this plan's work even began. Discarded; not included below. The `--border` contrast claim was independently re-derived (OKLCH → linear sRGB → WCAG luminance) and confirmed within rounding of the agent's number.

Automated verification re-run clean: `pnpm lint`, `pnpm build`, `pnpm test:e2e` (13/13 passed), `git diff --stat -- frontend/e2e/` empty.

## Findings

### F1 — `--border`/`--input` fails WCAG 1.4.11 non-text contrast

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: frontend/src/app/globals.css:97-98
- **Detail**: `--border: oklch(0.85 0.01 90)` and `--input` (same value) were authored in Phase 1 without computing contrast — unlike the risk inks, which got careful WCAG treatment (see the comment at globals.css:68-71). Independently recomputed: **1.46:1** against paper (`#F7F6F2`), far under the 3:1 WCAG 1.4.11 minimum for UI-component boundaries. This token drives `Input`/`Textarea` borders, the `Button` outline variant, `Badge` outline variant, and every `border-b border-border` row/clause divider across the app (dashboard rows, clause dividers). Functionally usable but the boundaries are barely visible.
- **Fix**: Darken to `oklch(0.62 0.01 90)` (independently verified at 3.37:1, comfortable margin above 3:1) for both `--border` and `--input`.
  - Strength: Minimal shift in hue/chroma — same warm neutral, just dark enough to actually read as a boundary. One-line change, same token used everywhere so the fix is uniform.
  - Tradeoff: Slightly more visible dividers than the current very-subtle look — a deliberate aesthetic tradeoff for a real accessibility requirement.
  - Confidence: HIGH — recomputed independently via the full OKLCH→linear-RGB→WCAG-luminance pipeline, not just trusting the sub-agent's number.
  - Blind spot: Haven't re-screenshotted after the change to confirm it still reads as "quiet" per the design direction's restraint requirement.
- **Decision**: FIXED — darkened `--border`/`--input` to `oklch(0.62 0.01 90)` in globals.css.

### F2 — `border-ink/10` in app-header.tsx is a silent no-op utility

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: frontend/src/components/app-header.tsx:20
- **Detail**: `border-ink/10` references color `ink`, but `globals.css`'s `@theme inline` block never registers `--color-ink: var(--ink);` — only the raw `--ink` value exists in `:root`. Verified by compiling `globals.css` through the actual `@tailwindcss/postcss` plugin: `.border-ink` generates **zero CSS**. The header still shows *a* border today only because the universal `* { @apply border-border ... }` base rule (globals.css:119) supplies a fallback — so the header's border is accidentally correct, not intentionally correct, and one unrelated refactor away from silently losing it with no visible signal.
- **Fix**: Add `--color-ink: var(--ink);` to the `@theme inline` block in globals.css, which makes `border-ink/10` a real utility and matches the intent already written in the header's own comment ("the rule beneath it reading as a page edge").
- **Decision**: FIXED — registered `--color-ink: var(--ink);` in `@theme inline`.

### F3 — Two Phase 3 change targets never edited, decision undocumented

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/ui-design-system/plan.md, Phase 3 change #4 and change #6
- **Detail**: The plan explicitly named `frontend/src/components/ui/card.tsx` ("expected: card.tsx... sheds chrome") and `frontend/src/app/(app)/analyses/new/page.tsx` ("restyle the form and the in-flight progress panel") as Phase 3 edit targets. Git history confirms neither file has a single diff across any of the four phase commits. This was a deliberate judgment call made during implementation — both files already restyle correctly via Phase 1's token cascade (`bg-primary`, `border-border`, `CardTitle`'s `font-heading`→Fraunces all resolve correctly with zero edits) — and it was explained to the user in conversation at the time, but **never written into `plan.md` or `change.md`'s Notes**. A future reader of the plan alone would believe these files were touched when they weren't.
- **Fix**: Add a short note to `change.md`'s Notes section (or a one-line addendum under Phase 3 in `plan.md`) recording that `card.tsx` and `analyses/new/page.tsx` were deliberately left unedited because Phase 1's token cascade already carried them correctly, per the "tokens first, check before editing a primitive" rule.
- **Decision**: FIXED — addendum added to `change.md`'s Notes section.
