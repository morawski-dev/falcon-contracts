<!-- PLAN-REVIEW-REPORT -->
# Plan Review: UI Design System — "Redline / Kancelaria"

- **Plan**: `context/changes/ui-design-system/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-13
- **Verdict**: REVISE → **SOUND** (all 5 findings fixed)
- **Findings**: 1 critical, 3 warnings, 1 observation

## Verdicts

| Dimension | Verdict (at review) | After fixes |
|-----------|---------------------|-------------|
| End-State Alignment | WARNING | PASS |
| Lean Execution | PASS | PASS |
| Architectural Fitness | FAIL | PASS |
| Blind Spots | WARNING | PASS |
| Plan Completeness | WARNING | PASS |

## Grounding

11/11 paths ✓, brief↔plan ✓, Progress↔Phase ✓ (4 phases, all Success Criteria bullets matched)

## Findings

### F1 — Deleting `@custom-variant dark` ACTIVATES dark styles

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 1, change #5
- **Detail**: The plan's safety argument was inverted. `globals.css:5` is not dead code — it is the **only** thing suppressing dark mode. Tailwind v4 wires `dark:` to `@media (prefers-color-scheme: dark)` by default; that line rebinds it to a `.dark` class that never appears. Verified: `prefers-color-scheme` appears **zero times** in the current build output, and `shadcn/dist/tailwind.css` defines no `@custom-variant dark` of its own — `globals.css:5` is the sole definition in the compiled graph. Deleting it would activate 12 surviving `dark:` utilities (`risk.ts` ×3, `button` ×4, `badge` ×3, `input` ×1, `textarea` ×1) under OS dark mode, while the `.dark` **token** block — being class-scoped — would *not* activate. Result: near-black amber badges on white paper, washed-out outline buttons. Invisible to anyone developing on a light-mode machine.
- **Fix A ⭐ Recommended**: Strip the 12 `dark:` utilities first, then delete line 5.
  - Strength: Removes the class of problem — light-only becomes true by construction, not by a suppressor nobody remembers is load-bearing.
  - Tradeoff: Touches 4 `ui/` primitives, which the plan wanted to keep "surgical". This is a legitimate reason to touch them.
  - Confidence: HIGH — the 12 sites are enumerated with line numbers; each is a deletion, not a rewrite.
  - Blind spot: shadcn's own `@variant dark` shimmer block stays in `node_modules`; harmless (unused).
- **Fix B**: Keep line 5 as a deliberate suppressor; delete only the `.dark` token block.
  - Strength: One-line diff, zero risk, primitives stay pristine.
  - Tradeoff: Leaves a booby trap — a line whose purpose is to suppress a feature we don't have, which the next cleanup removes, reintroducing this exact bug.
  - Confidence: HIGH — it works.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A. Phase 1 change #5 rewritten with a mandatory ordering (strip utilities → grep-verify zero remain → only then delete the variant + token block), plus two new automated criteria (1.5, 1.6) and a manual OS-dark-mode check (1.8).

### F2 — The stamp-violet accent has no consumer

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Implementation Approach (token table) + Phases 2–4
- **Detail**: `--stamp` (#4C3FA6) was defined in the token table and named in the Desired End State, but no phase said where it appears. `grep -ril "violet\|stamp" src/` returns nothing, so there is no existing surface it would land on by default. An accent token with no named consumer does not get used — and every phase's success criteria would still have passed, so the direction's one non-risk hue would silently never ship.
- **Fix**: Name the consumers explicitly.
- **Decision**: FIXED. A "Where `--stamp` appears" table added to Implementation Approach naming four surfaces: the `§` reference marks (Phase 2), the keyboard focus ring (Phase 4, with a 3:1 check), the wordmark and the primary action button (Phase 3). Noted that four is the upper bound for an accent, with the button as the first to cut if the violet reads as generic brand colour. New criteria 3.10 and an extended 4.5.

### F3 — Browser tab still shows the Next.js logo

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment
- **Location**: Phase 3 — omitted entirely
- **Detail**: `src/app/favicon.ico` is the stock `create-next-app` icon — verified: 25,931 bytes, MD5 `c30c7d42707a47a3f4591831641e50dc`, a 4-image ICO, mtime equal to the bootstrap date, never touched. No `icon.*`, `apple-icon.*`, or `opengraph-image.*` exists anywhere. A slice whose outcome is "one coherent visual identity on every screen" would have shipped the framework's logo as its single most-seen piece of brand surface.
- **Fix**: Add an App Router `src/app/icon.svg` (takes precedence over `favicon.ico`) carrying the mark in stamp-violet on paper; delete the stock `favicon.ico`.
- **Decision**: FIXED. Added as Phase 3 change #5 (the former #5 renumbered to #6), with criterion 3.9.

### F4 — `register/page.tsx` has two accessible-name traps

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3, change #1 (auth screens)
- **Detail**: Phase 3's contract for this file was written without the file ever being opened. Reading it surfaces two traps: (1) the submit button's accessible name is **dynamic** — `Zarejestruj się` idle, `Tworzenie konta…` while submitting (a real U+2026 ellipsis), and `fixtures.ts` clicks it by the idle name in every test; (2) the `<CardTitle>` (`:46`) is the **identical** string to the button's idle label, so `getByText` would match two nodes — the suite survives only because it scopes by role. Phase 3 adds a wordmark and tagline to this same screen, i.e. more text and more collision risk. Also noted: the error `<p>` (`:75`) has no `role="alert"` and the password hint (`:73`) is a bare sibling, not `aria-describedby` — both tempting to "fix" during a redesign, both changing the accessible-name surface.
- **Fix**: Pin the constraints in Phase 3's contract.
- **Decision**: FIXED. Phase 3 change #1 now carries a "Two accessible-name traps" block (both submit-button strings must survive; new copy must not duplicate any button's accessible name), an explicit out-of-scope note on `role="alert"` / `aria-describedby`, and the `<form>`-wraps-`CardContent`+`CardFooter` structural constraint.

### F5 — The font-bug symptom is described wrong

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis; `plan-brief.md` "Starting Point"
- **Detail**: The plan said the app "renders in the browser's default sans-serif." Verified against the built CSS: the `@theme inline` declaration *is* emitted into `:root` and **replaces** Tailwind's default `--font-sans`, so no fallback survives; `html{font-family:var(--font-sans)}` is invalid at computed-value time and falls back to the UA's **initial** font — in Chrome/Firefox the standard font, typically **Times New Roman, a serif**. Diagnosis and severity were right; the symptom was misdescribed. This matters practically: "the body text is serif" is a free, instant confirmation signal.
- **Fix**: Correct the wording and record it as a pre-flight visual check.
- **Decision**: FIXED. Current State Analysis rewritten with the verified mechanism and a "Pre-flight check (free, do it before touching anything)" note; `plan-brief.md` Starting Point corrected.

## Notes for implementation

The single most valuable output of this review is F1's **ordering constraint**. It is the kind of defect that passes every automated gate, ships, and only manifests for users whose OS is in dark mode — a population the developer is not in. Phase 1's new automated criteria (`grep -rn "dark:"` returns nothing; `prefers-color-scheme` absent from the built CSS) exist specifically to make that failure impossible to ship silently.
