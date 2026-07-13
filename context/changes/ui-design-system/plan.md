# UI Design System — "Redline / Kancelaria" Implementation Plan

## Overview

Falcon is feature-complete (S-01…S-05) but wears the untouched Next.js/shadcn scaffold: a zero-chroma neutral theme, a font system that does not actually work, and the product's single most meaningful signal — clause risk — expressed as hardcoded Tailwind swatches living outside the design system.

This plan gives Falcon one coherent visual identity across all six screens and rebuilds the analysis result as what it actually is: **a contract someone marked up**. The design direction is locked in `context/foundation/roadmap.md` § S-06 ("Redline / Kancelaria") and is not re-opened here.

No backend change. No schema change. No new endpoint. No change to the owner-scoping invariant. No new npm dependency.

## Current State Analysis

**The font system is broken, not merely default.** `frontend/src/app/globals.css:10` reads:

```css
--font-sans: var(--font-sans);
```

That is a self-referential custom property — a CSS dependency cycle, which resolves to the guaranteed-invalid value. Verified against the built CSS: the declaration *is* emitted into `:root` and **replaces** Tailwind's default `--font-sans: ui-sans-serif, system-ui, …`, so no fallback survives. `globals.css:128-132` (`html { @apply font-sans }`) compiles to `html{font-family:var(--font-sans)}` with no fallback → invalid at computed-value time → `<html>` falls back to the **UA's initial font, which in Chrome and Firefox is the standard font: typically Times New Roman, a serif.**

`--font-heading` (`globals.css:12`) chains off the same dead variable and is broken too. Meanwhile `layout.tsx:5-8` exposes Geist as `--font-geist-sans` — a name nothing consumes. Only `--font-mono: var(--font-geist-mono)` (`globals.css:11`) is wired correctly; that asymmetry is itself the evidence this was an editing slip.

**Pre-flight check (free, do it before touching anything):** open the running app and look at the body text. It should be **serif**. If it is, the diagnosis is confirmed and the rest of Phase 1 rests on solid ground.

**Consequence:** there is no working font system to regress. Phase 1 repairs a real bug before building on it.

**The theme is stock shadcn `neutral`.** Every colour in `:root` (`globals.css:51-85`) is `oklch(L 0 0)` — literally zero chroma. The app has no hue.

**Risk lives outside the design system.** `frontend/src/lib/risk.ts:14-18`:

```ts
export const RISK_LEVEL_BADGE_CLASS: Record<RiskLevel, string> = {
  HIGH: "bg-destructive/10 text-destructive dark:bg-destructive/20",
  MEDIUM: "bg-amber-100 text-amber-800 dark:bg-amber-950/40 dark:text-amber-300",
  LOW: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300",
};
```

Stock Tailwind swatches, un-themeable, and `HIGH` overloads `--destructive` — the same ink as the **Usuń** button. A high-risk clause and a destructive action currently share a colour.

**The `.dark` block is dead code.** `globals.css:87-119` defines a full dark token set, but nothing ever applies the `.dark` class: there is no `next-themes`, no toggle, no `dark` class on `<html>`. ~30 unreachable tokens.

**The E2E suite is the safety net — and the constraint.** `frontend/e2e/` holds 8 specs, all located via `getByRole` / `getByLabel` / `getByText` (per the `CLAUDE.md` mandate). CI (`.github/workflows/ci.yml`) runs `pnpm lint` + `pnpm build` + `pnpm test:e2e` (Chromium, `retries: 0`) on any `frontend/**` push. There is **no visual-regression tooling** — repo-wide grep for `toMatchSnapshot|toHaveScreenshot|argos|percy|chromatic` returns zero matches.

### Key Discoveries

- **Broken font chain**: `globals.css:10` — `--font-sans: var(--font-sans)`. Nothing renders in Geist today.
- **The clause DOM contract is load-bearing**: `frontend/e2e/analysis-result.spec.ts:22-28` locates clauses as
  `page.getByTestId(/^clause-\d+$/).filter({ has: page.getByRole("group", { name: new RegExp(riskTypeLabel) }) })`.
  The `data-testid` sits on the **`<CardContent>`**, not the `<Card>` (`(app)/analyses/[id]/page.tsx:204`), and the `role="group"` decision cluster (`:215-217`) must remain a **descendant of the same element**. Moving the decision buttons out of that element (e.g. into a `CardFooter`) silently zeroes the `.filter()` and fails six assertions. **This is the single most likely way this slice breaks CI.**
- **Negative assertions scope risk text to its own clause**: `analysis-result.spec.ts:37,39` assert `.not.toBeVisible()` for `"Niskie"` inside the auto-renewal and penalty clauses. A summary legend listing all three levels, if rendered *inside* a clause element, would fail. (A legend outside every `clause-{id}` element is fine.)
- **`aria-pressed` must survive**: `clause-decision.spec.ts` uses `getByRole("button", { name: "Do negocjacji", pressed: true })` (`(app)/analyses/[id]/page.tsx:227`). Swapping the decision toggles for a Radix `ToggleGroup` (role `radio`/`checked`) breaks it.
- **`getByLabel` requires the `<Label htmlFor>` ↔ `<Input id>` association** to survive on `Email`, `Hasło`, `Tytuł`, `Treść umowy` (`fixtures.ts:16-44` depends on these in *every* test).
- **The wordmark must stay `role=link` named exactly `Falcon`** (`app-header.spec.ts:16`; its header comment explicitly forbids `getByText` because "Falcon" also appears in body copy), and **Wyloguj must stay a `<button>`**, not a link.
- **`RISK_LEVEL_BADGE_CLASS` is never asserted on.** The colour swap is free; the *label text* (`Wysokie` / `Średnie` / `Niskie`, `risk.ts:20-24`) is not.
- **No new dependency is needed**: `next/font/google` is already in use (`layout.tsx:2`). CI runs `pnpm install --frozen-lockfile` — adding any package would require a regenerated `pnpm-lock.yaml`, so **not adding one is a feature**.
- **Tailwind v4, CSS-first**: no `tailwind.config.*` exists. Tokens must be declared in **both** `:root` (the value) **and** `@theme inline` (`globals.css:7-49`, to generate the utility) or the Tailwind class will not exist.
- `frontend/public/` holds only `.gitkeep`. Both the Dockerfile (`:34`) and the CI Playwright `webServer` (`playwright.config.ts:66`) copy `public/`, so any asset dropped there is picked up automatically — but self-hosting fonts is unnecessary given `next/font/google`.

## Desired End State

A user meets one deliberate visual identity on every screen. The analysis result reads as a marked-up contract: each clause sits in a document column with a margin gutter carrying its `§` reference, a severity-weighted rule in risk ink, and the risk level spelled out in words. Risk is a first-class theme token. The auth screens are a considered front door rather than a bare card.

**Verified by:** `pnpm lint` + `pnpm build` + all 8 E2E specs passing **with zero edits to any spec file**, plus the manual sweep in Phase 4. A green suite on untouched specs is the proof that the restyle preserved every meaning.

## What We're NOT Doing

- **No new screens, routes, states, or features.** Not a landing page. Not a settings screen.
- **No dark mode.** The `.dark` block is deleted, not tuned. "Ink on paper" is a light-mode thesis.
- **No new npm dependency.** No `next-themes`, no `@axe-core/playwright`, no font package.
- **No visual-regression tooling.** No `toHaveScreenshot` baselines (font rendering differs between Windows and CI's Linux container; baselines would thrash).
- **No E2E spec edits.** If a spec needs changing, the redesign is wrong — revisit the markup, not the test.
- **No backend, schema, API, or auth changes.**
- **No rewrite of the shadcn primitives.** Tokens do the work; primitives are edited surgically, only where the direction demands it.
- **No motion layer.** One earned moment, nothing scattered.

## Implementation Approach

Four phases, ordered so the token layer's first consumer is the signature element — the cheapest possible test of whether the tokens are right.

1. **Foundation** re-inks every screen with **zero structural change**, so a green E2E run isolates the token swap from any markup risk.
2. **The redline** rebuilds the one screen that carries the thesis, honouring the DOM contract by construction.
3. **The surrounding screens** are brought into the system and kept deliberately quiet, so the redline remains the single bold thing.
4. **The quality floor** — motion, focus, contrast, responsive — plus the manual sweep that is, given no visual-regression tooling, the *only* visual gate that exists.

### The token system (authored in Phase 1, consumed by 2–4)

| Role | Token | Value |
| --- | --- | --- |
| Surface | `--paper` | `#F7F6F2` |
| Text | `--ink` | `#14161C` |
| Accent | `--stamp` | `#4C3FA6` |
| Risk LOW — rule | `--risk-low-mark` | `#5C6B4A` (moss) |
| Risk LOW — text | `--risk-low-ink` | darkened to clear AA 4.5:1 on paper |
| Risk MEDIUM — rule | `--risk-medium-mark` | `#A9762B` (ochre) |
| Risk MEDIUM — text | `--risk-medium-ink` | darkened to clear AA 4.5:1 on paper |
| Risk HIGH — rule | `--risk-high-mark` | `#8E2C25` (oxblood) |
| Risk HIGH — text | `--risk-high-ink` | darkened to clear AA 4.5:1 on paper |

**Two-tier rationale:** the `-mark` tier carries the full-chroma hue and is used only where it is *not* text (the margin rule, surface tints), so the at-a-glance severity read stays vivid. The `-ink` tier is darkened to clear WCAG AA and is used for the risk-level label. Nobody perceives the darkened label as a different colour, and the signature element keeps its hue. **Do not mix the tiers.**

**Where `--stamp` appears (an accent with no named consumer never ships):**

| Surface | Phase | Note |
| --- | --- | --- |
| The `§` reference marks in the gutter | 2 | The violet becomes the ink of the annotation apparatus itself — the closest thing to the "official stamp" idea |
| The keyboard focus ring | 4 | Must clear 3:1 non-text contrast against paper — verify |
| The wordmark | 3 | Header + auth screens, in the display face |
| The primary action button | 3 | **The first to pull back** if the violet starts reading as generic SaaS purple rather than stamp ink — that's a judgment call to make with the thing on screen, not now |

Four consumers is the upper bound for an accent. If it reads as brand colour rather than ink, cut the button first, then the wordmark.

| Role | Family | Used for |
| --- | --- | --- |
| Display | Fraunces | Wordmark, page titles — **only** |
| Body / UI | IBM Plex Sans | Everything else |
| Marks | IBM Plex Mono | `§` references, risk-type labels, metadata |

## Critical Implementation Details

**The clause DOM contract (Phase 2).** The element carrying `data-testid="clause-{id}"` must remain the common ancestor of: the risk-level text, the rationale, the negotiation point, and the `role="group"` decision cluster with its `aria-pressed` buttons. The margin gutter must therefore be a **grid/flex child inside that element**, not a sibling wrapper around it. This constrains the markup, not the visual — a two-column grid inside the testid element produces the gutter exactly as designed.

**Tailwind v4 token declaration.** Every new token must be added in two places in `globals.css`: the raw value in `:root`, and a `--color-*` / `--font-*` mapping inside `@theme inline` (`:7-49`). Declaring only the former yields a variable that works in arbitrary values (`bg-[var(--x)]`) but generates no utility class; declaring only the latter yields an unresolvable reference. The existing `--font-sans` bug is exactly this class of mistake — do not reproduce it.

**Font subsetting.** `next/font/google` must be called with `subsets: ["latin", "latin-ext"]` for Polish diacritics (ą ę ł ń ó ś ź ż). This is verified as the first task in Phase 1 — if Fraunces lacks `latin-ext`, the display role falls back per the plan's contingency without disturbing the rest of the system.

## Phase 1: Foundation — tokens & type

### Overview

Repair the broken font chain, wire the three families, author the palette and risk tokens, delete the dead `.dark` block, and point `risk.ts` at the new tokens. Every screen is visibly re-inked. **No markup structure changes.** A green E2E run at the end of this phase proves the token layer alone lost no meaning.

### Changes Required

#### 1. Verify Polish diacritic coverage (do this first)

**File**: none — a spike.

**Intent**: Confirm Fraunces, IBM Plex Sans, and IBM Plex Mono all ship a `latin-ext` subset via `next/font/google`, before any of them is wired in. This is the one open Unknown from the roadmap slice.

**Contract**: Each family must render `ą ę ł ń ó ś ź ż` without falling back. If Fraunces lacks `latin-ext`, substitute the display role with **IBM Plex Serif** (keeping the superfamily coherent) and record the substitution in `change.md` Notes — the rest of the system is unaffected.

#### 2. Fonts

**File**: `frontend/src/app/layout.tsx`

**Intent**: Replace the Geist/Geist_Mono pair with the three chosen families, each exposing a CSS variable consumed by `globals.css`. Geist is removed entirely — nothing depends on it (the `font-sans` chain never resolved, and `--font-geist-mono` is only referenced by the broken chain in `globals.css:11`).

**Contract**: `next/font/google` imports for `Fraunces`, `IBM_Plex_Sans`, `IBM_Plex_Mono`, each with `subsets: ["latin", "latin-ext"]` and a `variable` (`--font-display`, `--font-body`, `--font-mono-mark`). The variables are applied to `<html>` alongside the existing `h-full antialiased`. `lang="pl"` and the `<body className="min-h-full flex flex-col">` are unchanged.

#### 3. Font token chain — the bug fix

**File**: `frontend/src/app/globals.css`

**Intent**: Fix the self-referential `--font-sans` and route the three families through `@theme inline` so Tailwind generates working `font-sans` / `font-display` / `font-mono` utilities. This is a genuine defect fix, independent of the redesign.

**Contract**: In `@theme inline`, `--font-sans` maps to `var(--font-body)`, `--font-display` to `var(--font-display)`, `--font-mono` to `var(--font-mono-mark)`. Delete the self-referential line (`:10`). The `@layer base` rule applying `font-sans` to `html` (`:129`) then resolves for the first time.

**Implementation-time correction:** `--font-heading` is **not** dead code — it's an active Tailwind utility (`font-heading`) consumed by `ui/card.tsx` (`CardTitle`) and `ui/alert-dialog.tsx` (`AlertDialogTitle`), both of which are page/dialog titles. Repoint it at `var(--font-display)` instead of deleting it — a CSS-token-only fix, no markup touched, so it stays within Phase 1's "zero structural change" rule, and it means every existing title picks up Fraunces for free.

#### 4. Palette & risk tokens

**File**: `frontend/src/app/globals.css`

**Intent**: Replace the zero-chroma neutral `:root` set with the paper/ink/stamp direction, and add the six two-tier risk tokens. Map the shadcn semantic tokens (`--background`, `--foreground`, `--primary`, `--border`, …) onto the new palette so the ten `ui/` primitives restyle themselves without being touched.

**Contract**: `:root` carries the raw values (see the token table above) plus the derived shadcn semantics; `@theme inline` gains `--color-risk-low-ink`, `--color-risk-low-mark`, and the four siblings, so `text-risk-high-ink` / `bg-risk-high-mark` become real utilities. `--destructive` stays a distinct ink from `--risk-high-*` — the Usuń button and a high-risk clause must not share a colour. `--radius` is retuned to the direction (documents have crisp edges; the current `0.625rem` is a scaffold default). `--header-height: 3.5rem` (`:76`) is preserved — `app-header.tsx:20` and the `scroll-padding-top` rule both consume it.

#### 5. Remove dark mode — in this exact order

**Files**: `frontend/src/lib/risk.ts`, `frontend/src/components/ui/{button,badge,input,textarea}.tsx`, then `frontend/src/app/globals.css`

**Intent**: Make light-only true *by construction*. **`globals.css:5` is not dead code — it is the only thing suppressing dark mode**, and the ordering below is load-bearing.

In Tailwind v4, `dark:` is wired to `@media (prefers-color-scheme: dark)` by default. `@custom-variant dark (&:is(.dark *))` **rebinds it to a class that never appears**, which is what keeps every `dark:` utility permanently switched off. (Verified: `prefers-color-scheme` appears **zero times** in the current build output, and `shadcn/dist/tailwind.css` defines no `@custom-variant dark` of its own — `globals.css:5` is the sole definition in the whole compiled graph.)

Deleting line 5 *first* would therefore **turn dark mode on, half-configured**: the 12 surviving `dark:` utilities would activate under OS dark mode while the `.dark` **token** block — being class-scoped — would not. An OS-dark-mode user would get near-black amber badges on white paper and washed-out outline buttons. Nobody developing on a light-mode machine would ever see it.

**Contract** — do these in order:

1. **Delete all 12 `dark:` utilities** from source (each is a deletion, not a rewrite):

   | File | Count | Lines |
   | --- | --- | --- |
   | `src/lib/risk.ts` | 3 | 15, 16, 17 |
   | `src/components/ui/button.tsx` | 4 | 8, 14, 18, 20 |
   | `src/components/ui/badge.tsx` | 3 | 8, 16, 20 |
   | `src/components/ui/input.tsx` | 1 | 11 |
   | `src/components/ui/textarea.tsx` | 1 | 10 |

   (This is the legitimate reason to touch four `ui/` primitives despite the "tokens first, surgical edits" rule — see Phase 3, change #5.)

2. **Verify zero remain**: `grep -rn "dark:" frontend/src/` returns nothing.

3. **Only then** delete `globals.css:5` (`@custom-variant dark`) and `globals.css:87-119` (the `.dark` block).

After this, no `dark:` utility exists anywhere, so the variant's definition is genuinely irrelevant and light-only holds without a suppressor that a future cleanup could "helpfully" remove.

#### 6. Risk map → tokens

**File**: `frontend/src/lib/risk.ts`

**Intent**: Repoint `RISK_LEVEL_BADGE_CLASS` at the new semantic tokens and drop the now-meaningless `dark:` variants. The label maps (`RISK_LEVEL_LABEL`, `RISK_TYPE_LABEL`) are **untouched** — the E2E suite asserts on their exact strings.

**Contract**: The exported record keys and the `RiskLevel` / `RiskType` types are unchanged, so no call site breaks. Values reference the risk tokens. Add a second export for the margin rule's ink/weight, consumed by Phase 2 — the mark tier and the text tier are separate lookups so they cannot be mixed up by accident.

### Success Criteria

#### Automated Verification

- Lint passes: `cd frontend && pnpm lint`
- Production build succeeds: `cd frontend && pnpm build`
- All 8 E2E specs pass **with no spec file modified**: `cd frontend && pnpm test:e2e`
- `git diff --stat frontend/e2e/` is empty
- **No `dark:` utility survives**: `grep -rn "dark:" frontend/src/` returns nothing
- **Dark mode is not reachable**: `prefers-color-scheme` appears zero times in the built **CSS** — `find frontend/.next/static -name "*.css" -exec grep -l "prefers-color-scheme" {} \;` returns nothing, after `pnpm build`. (Scoped to `*.css`, not all of `.next/static/`: an unscoped grep also matches Next.js's own built-in error-overlay boilerplate — `--next-error-*` variables, unrelated to our Tailwind `dark:` variant — which is framework code we don't control and would always false-positive.)

#### Manual Verification

- Polish diacritics (ą ę ł ń ó ś ź ż) render correctly in all three families — no fallback glyphs
- **With the OS set to dark mode**, the app still renders as light paper — no dark badges, no washed-out buttons
- Every screen is visibly re-inked (paper background, new type) with no layout breakage
- The Usuń button and a HIGH-risk clause are visibly different colours
- Risk-level label text clears AA contrast against paper (spot-check HIGH / MEDIUM / LOW)

**Implementation Note**: Pause here for manual confirmation before proceeding to Phase 2.

---

## Phase 2: The margin redline

### Overview

Rebuild `analyses/[id]` as a document with a margin. This is the signature element and the one place boldness is spent. The DOM contract from Key Discoveries is honoured by construction, so the E2E suite stays green and untouched.

### Changes Required

#### 1. The clause as a marked-up paragraph

**File**: `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: Replace the per-clause `<Card>` + floating `<Badge>` with a document column: a two-column layout inside the clause element, where the left gutter carries the `§` reference (mono), a severity-weighted rule in risk ink (`--risk-*-mark`), and the risk level spelled out in words (`--risk-*-ink`); the right column carries the clause text, the rationale, the decision controls, and the negotiation point as a margin annotation.

**Contract**: **The element carrying `data-testid="clause-{id}"` must remain the common ancestor of the risk-level text, the rationale, the negotiation point, and the `role="group"` decision cluster.** The gutter is a grid child *inside* that element. Preserve verbatim:
- `data-testid={`clause-${clause.id}`}`
- `role="group"` with `aria-label={`Decyzja: klauzula ${index + 1} (${RISK_TYPE_LABEL[clause.riskType]})`}`
- the three decision controls as `<button>` with `aria-pressed` (not a Radix `ToggleGroup`)
- the literal risk-level text from `RISK_LEVEL_LABEL`, scoped inside its own clause element
- the literal string `"Punkt do negocjacji"`
- the `RISK_TYPE_LABEL` string inside the group's accessible name

Severity is encoded **redundantly** — rule weight *and* ink *and* the level in words. Never colour alone.

#### 2. Clause numbering

**File**: `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: Render the clause's ordinal as a `§` reference in the gutter. Numbering is earned here — contract clauses genuinely are an ordered sequence, and the reference is how a user points at one when they call the other side.

**Contract**: Derived from the existing `index` already in scope at `:202` (used today only for the group's `aria-label`). No data-model change; no new field on `Clause`.

#### 3. Page chrome and the disclaimer

**File**: `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: Restyle the title header and the "not legal advice" disclaimer into the document treatment. The disclaimer must remain unmissable — the roadmap slice is explicit that it may not be decoratively softened.

**Contract**: The disclaimer keeps its `role="alert"` Alert and the literal title `"To nie jest porada prawna"` (`disclaimer.spec.ts:28`). The not-found state keeps `"Nie znaleziono analizy"` and its destructive Alert (`delete-analysis.spec.ts`). The delete trigger keeps its accessible name `Usuń` and its `alertdialog`. The title renders in the display face.

#### 4. Loading skeletons

**File**: `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: Reshape the skeletons to match the new document layout — a skeleton that mimics a card stack while the page renders a redlined document is a perceived-performance regression.

**Contract**: Uses the existing `Skeleton` primitive; no new component.

### Success Criteria

#### Automated Verification

- Lint passes: `cd frontend && pnpm lint`
- Production build succeeds: `cd frontend && pnpm build`
- All 8 E2E specs pass **with no spec file modified**: `cd frontend && pnpm test:e2e`
- `git diff --stat frontend/e2e/` is empty
- `analysis-result.spec.ts` and `clause-decision.spec.ts` pass specifically — these are the two that pin the clause DOM

#### Manual Verification

- The result reads as a marked-up contract, not a feed of cards
- Severity is legible at a glance from the margin rule alone, and still legible with colour ignored (rule weight + the level in words)
- The disclaimer is unmissable
- Decision buttons still visibly reflect their pressed state

**Implementation Note**: Pause here for manual confirmation before proceeding to Phase 3.

---

## Phase 3: The surrounding screens

### Overview

Bring the dashboard, new-analysis form, header, and auth screens into the system. Everything here stays deliberately quiet — the redline is the one memorable thing, and these screens must not compete with it.

### Changes Required

#### 1. Auth screens as the identity statement

**Files**: `frontend/src/app/(auth)/login/page.tsx`, `frontend/src/app/(auth)/register/page.tsx`

**Intent**: Give the first screen anyone sees the full paper treatment: the Fraunces wordmark at display size and one line of plain-Polish copy saying what Falcon does, above the form. A considered front door — **not** a landing page, not a hero, no features, no testimonials.

**Contract**: The `<Label htmlFor>` ↔ `<Input id>` associations for `Email` and `Hasło` must survive verbatim — `fixtures.ts:16-28` calls `getByLabel("Email")` / `getByLabel("Hasło")` in **every single test**, so breaking these breaks the entire suite. The login↔register cross-links are preserved.

**Two accessible-name traps on `register/page.tsx` — read before writing copy:**

1. **The submit button's accessible name is dynamic.** `register/page.tsx:78-80` renders `Zarejestruj się` when idle and `Tworzenie konta…` while submitting (a real U+2026 ellipsis, not three dots). Both strings must survive. Same pattern on login (`Zaloguj się` / `Logowanie…`) and on the new-analysis form (`Analizuj umowę` / `Analizowanie…`).
2. **`Zarejestruj się` is already ambiguous.** The `<CardTitle>` at `register/page.tsx:46` is the *identical* string to the button's idle label — two nodes with that text. The suite survives today only because it scopes by role. **This phase adds a wordmark and a tagline to the same screen: the new copy must not duplicate any button's accessible name**, or a third collision appears.

**Explicitly out of scope on these screens** (both are tempting during a redesign and both change the accessible-name surface): do **not** add `role="alert"` to the error `<p>` (`register/page.tsx:75`), and do **not** wire the `Co najmniej 8 znaków.` hint (`:73`) via `aria-describedby`. Both are real a11y improvements and both belong to a different slice.

Also structural: the `<form>` wraps `CardContent` + `CardFooter` and sits *inside* `<Card>` after `<CardHeader>` (`:49`). If the Card is flattened, the form must still wrap both, or the footer's submit button detaches from the form.

#### 2. Header

**File**: `frontend/src/components/app-header.tsx`

**Intent**: Restyle the header into the document system — the wordmark in the display face, the rule beneath it reading as a page edge rather than a generic border.

**Contract**: The wordmark stays `role=link` with the accessible name **exactly** `Falcon` (`app-header.spec.ts:16`). **Wyloguj stays a `<button>`**, not a link (`:80,91,95`). `Nowa analiza` stays a link with that exact name — `fixtures.ts:36` clicks it in every seeded test. `h-[var(--header-height)]` is preserved.

#### 3. Dashboard

**File**: `frontend/src/app/(app)/dashboard/page.tsx`

**Intent**: Restyle the history list into the system — rows as document entries rather than cards. Rewrite the empty state as an invitation to act (it currently reads as a passive statement of absence).

**Contract**: Each row stays a `role=link` whose accessible name contains the analysis title (`analysis-history.spec.ts:23`, `delete-analysis.spec.ts`). The delete trigger keeps `aria-label={`Usuń analizę: ${analysis.title}`}` — which is what regex-matches `{ name: "Usuń" }` — and its `alertdialog`. The `pl-PL` date formatting is unchanged.

#### 4. New-analysis form

**File**: `frontend/src/app/(app)/analyses/new/page.tsx`

**Intent**: Restyle the form and the in-flight progress panel into the system. The progress panel serves the ~15s LLM wait (an NFR) and keeps its existing indeterminate animation.

**Contract**: `getByLabel("Tytuł")` and `getByLabel("Treść umowy")` must survive — `fixtures.ts:39-40` depends on both. The textarea keeps its native `required` attribute (`analysis-input-validation.spec.ts:12-14`). The submit button keeps the name `Analizuj umowę`. The exact error strings `"Wklej treść umowy, aby rozpocząć analizę."` and `"Nie udało się przeanalizować umowy. Spróbuj ponownie."` are preserved verbatim, as is the disclaimer line `"Falcon dostarcza analizę pomocniczą, a nie poradę prawną."` — note this renders as a **bare `<p>`**, deliberately distinct from the `role="alert"` Alert on the result page (`disclaimer.spec.ts:4-11` explains why).

#### 5. The favicon — the most-seen brand surface, currently the framework's

**Files**: `frontend/src/app/icon.svg` (new), `frontend/src/app/favicon.ico` (delete)

**Intent**: `src/app/favicon.ico` is the **stock `create-next-app` icon** — verified: 25,931 bytes, a 4-image ICO, mtime equal to the bootstrap date, never touched. Every browser tab currently shows the Next.js "N" beside the word "Falcon". A slice whose outcome is "one coherent visual identity on every screen" cannot ship the framework's logo as its most-seen piece of brand.

**Contract**: Add `src/app/icon.svg` (the App Router convention — it takes precedence over `favicon.ico`), carrying the wordmark's mark in stamp-violet on paper. Delete the stock `favicon.ico`. No `public/` asset is needed and no config change: `metadata.title` in `layout.tsx:16` already reads `Falcon`.

#### 6. Surgical primitive edits

**Files**: `frontend/src/components/ui/*.tsx` (only as needed)

**Intent**: Edit a primitive only where the token layer cannot carry the direction. Expected: `badge.tsx` (its pill shape fights the margin-mark treatment) and `card.tsx` (sheds chrome). Everything else should restyle for free from Phase 1's tokens — if a primitive needs editing, first check whether a token would do it.

**Contract**: Keep each component's exported API and `cva` variant names intact; call sites across six screens depend on them.

### Success Criteria

#### Automated Verification

- Lint passes: `cd frontend && pnpm lint`
- Production build succeeds: `cd frontend && pnpm build`
- All 8 E2E specs pass **with no spec file modified**: `cd frontend && pnpm test:e2e`
- `git diff --stat frontend/e2e/` is empty

#### Manual Verification

- All six screens read as one system — no screen looks scaffolded next to another
- The auth screen is a credible first impression for a tool you'd trust with a contract
- The surrounding screens stay quiet; the redline is still the one thing you remember
- Register → new analysis → result → dashboard → delete runs end to end by hand without a visual break
- The browser tab shows Falcon's mark, not the Next.js logo
- The stamp-violet reads as ink, not as generic brand colour — if it doesn't, pull it from the primary button first

**Implementation Note**: Pause here for manual confirmation before proceeding to Phase 4.

---

## Phase 4: Quality floor & the manual sweep

### Overview

The one earned motion moment, then the accessibility and responsive floor. Given there is **no visual-regression tooling in the repo**, this manual sweep is the only visual gate that exists — it is not a formality.

### Changes Required

#### 1. The one motion moment

**Files**: `frontend/src/app/globals.css`, `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: As the report first renders, the margin rules draw in — as if the marks were being made. One orchestrated moment on the page that matters; nothing scattered elsewhere.

**Contract**: Decorative only — it must never delay reading the content, and the clause text must be present and readable from the first frame. Guarded by `@media (prefers-reduced-motion: reduce)`, under which the rules appear instantly at full length. The existing `indeterminate-progress` keyframe (`globals.css:135-142`) is retained for the analysis wait.

#### 2. Focus visibility

**Files**: `frontend/src/app/globals.css` and any call site with a custom focus ring

**Intent**: Ensure the keyboard focus ring is clearly visible against paper on every interactive element. The current base rule (`globals.css:123`, `outline-ring/50`) was tuned for the neutral theme and needs re-verifying against the new palette.

**Contract**: Every interactive element — links, buttons, inputs, decision toggles, dialog controls — shows a visible focus indicator meeting the 3:1 non-text contrast minimum.

#### 3. Responsive

**Files**: the six page/component files

**Intent**: Verify and fix the layout down to mobile. The margin gutter is the risk: a fixed-width gutter plus a `max-w-2xl` column will crush the clause text on a narrow viewport.

**Contract**: The gutter collapses gracefully on small screens (the rule and `§` reference remain, the layout does not overflow horizontally). No horizontal page scroll at 375px.

### Success Criteria

#### Automated Verification

- Lint passes: `cd frontend && pnpm lint`
- Production build succeeds: `cd frontend && pnpm build`
- All 8 E2E specs pass **with no spec file modified**: `cd frontend && pnpm test:e2e`
- `git diff --stat frontend/e2e/` is empty

#### Manual Verification

- **Contrast**: every risk-level label clears WCAG AA (4.5:1) against paper; the margin rules clear 3:1 as non-text; **the stamp-violet focus ring clears 3:1 against paper**
- **Colour-blind check**: severity remains readable with colour ignored (rule weight + the level spelled out)
- **Keyboard**: full flow (register → analyse → decide → delete) navigable by keyboard alone with a visible focus ring throughout
- **Reduced motion**: with `prefers-reduced-motion: reduce`, the rule reveal does not animate and nothing is hidden
- **Responsive**: all six screens at 375px and 1440px — no horizontal scroll, no crushed text, gutter intact
- **The whole-app sweep**: all six screens viewed back to back read as one designed product

**Implementation Note**: This is the final phase. Confirm the full manual sweep before closing the change.

---

## Testing Strategy

### The regression gate (automated)

The existing 8 E2E specs are the semantic regression gate. **They must pass without a single spec file being edited.** Because every locator is `getByRole` / `getByLabel` / `getByText`, a green run proves the restyle preserved every accessible name, role, and literal string the product depends on — i.e. that no *meaning* was lost while the *appearance* changed entirely.

If a spec fails, the default assumption is that **the markup is wrong, not the test.** Investigate the DOM contract before touching `frontend/e2e/`.

Highest-risk specs, to run first after any Phase 2 change:
- `analysis-result.spec.ts` — pins the `testid` + `role="group"` containment
- `clause-decision.spec.ts` — pins `aria-pressed`
- `disclaimer.spec.ts` — pins both disclaimer surfaces and their differing element types

### Manual testing steps

1. Register a fresh user → confirm the auth screen reads as a considered front door.
2. Paste a contract with an obviously risky clause → watch the progress panel through the wait.
3. On the result: confirm the margin redline reads as a markup, severity is legible at a glance, the disclaimer is unmissable.
4. Set a decision on each clause → confirm pressed state is visible and persists on reload.
5. Return to the dashboard via the wordmark → confirm the history list is in-system.
6. Delete an analysis → confirm the dialog is in-system.
7. Repeat steps 2–6 at 375px width.
8. Repeat step 3 with keyboard only, and again with `prefers-reduced-motion: reduce`.

### Not tested automatically (accepted)

Visual regression and programmatic contrast checking. Both were considered and deferred: screenshot baselines would thrash across the Windows/Linux font-rendering boundary, and an axe gate needs a new dependency (breaking `--frozen-lockfile`) — worth doing as its own slice, not smuggled into this one.

## Performance Considerations

Three Google font families instead of one is the only meaningful budget change. `next/font/google` self-hosts and inlines them at build time (no runtime request to Google, no layout shift), but each family still ships bytes. Keep the weight/style axes requested to what the design actually uses — Fraunces is variable and easy to over-request. Fraunces is used on the wordmark and page titles **only**, so a single weight range is sufficient.

## Migration Notes

Not applicable — no persisted data, schema, or API surface changes. The change is confined to `frontend/src/`.

Rollback is a clean `git revert` of the phase commits; nothing outside `frontend/src/` is touched, and no dependency is added, so there is no lockfile or database state to unwind.

## References

- Roadmap slice (the locked design brief): `context/foundation/roadmap.md` § S-06
- Change identity: `context/changes/ui-design-system/change.md`
- The font bug: `frontend/src/app/globals.css:10`
- The clause DOM contract: `frontend/e2e/analysis-result.spec.ts:22-28`, `frontend/src/app/(app)/analyses/[id]/page.tsx:204,215-217,227`
- Risk map to be repointed: `frontend/src/lib/risk.ts:14-18`
- CI: `.github/workflows/ci.yml` (lint + build + E2E on `frontend/**`)
- Prior frontend slice (established the `(app)` route group + header): `context/archive/2026-07-12-app-navigation-header/`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Foundation — tokens & type

#### Automated

- [x] 1.1 Lint passes: `cd frontend && pnpm lint`
- [x] 1.2 Production build succeeds: `cd frontend && pnpm build`
- [x] 1.3 All 8 E2E specs pass with no spec file modified: `cd frontend && pnpm test:e2e`
- [x] 1.4 `git diff --stat frontend/e2e/` is empty
- [x] 1.5 No `dark:` utility survives: `grep -rn "dark:" frontend/src/` returns nothing
- [x] 1.6 Dark mode is not reachable: `prefers-color-scheme` appears zero times in the built CSS

#### Manual

- [ ] 1.7 Polish diacritics render correctly in all three families — no fallback glyphs
- [ ] 1.8 With the OS set to dark mode, the app still renders as light paper
- [ ] 1.9 Every screen is visibly re-inked with no layout breakage
- [ ] 1.10 The Usuń button and a HIGH-risk clause are visibly different colours
- [ ] 1.11 Risk-level label text clears AA contrast against paper

### Phase 2: The margin redline

#### Automated

- [ ] 2.1 Lint passes: `cd frontend && pnpm lint`
- [ ] 2.2 Production build succeeds: `cd frontend && pnpm build`
- [ ] 2.3 All 8 E2E specs pass with no spec file modified: `cd frontend && pnpm test:e2e`
- [ ] 2.4 `git diff --stat frontend/e2e/` is empty
- [ ] 2.5 `analysis-result.spec.ts` and `clause-decision.spec.ts` pass specifically

#### Manual

- [ ] 2.6 The result reads as a marked-up contract, not a feed of cards
- [ ] 2.7 Severity is legible at a glance, and still legible with colour ignored
- [ ] 2.8 The disclaimer is unmissable
- [ ] 2.9 Decision buttons still visibly reflect their pressed state

### Phase 3: The surrounding screens

#### Automated

- [ ] 3.1 Lint passes: `cd frontend && pnpm lint`
- [ ] 3.2 Production build succeeds: `cd frontend && pnpm build`
- [ ] 3.3 All 8 E2E specs pass with no spec file modified: `cd frontend && pnpm test:e2e`
- [ ] 3.4 `git diff --stat frontend/e2e/` is empty

#### Manual

- [ ] 3.5 All six screens read as one system
- [ ] 3.6 The auth screen is a credible first impression
- [ ] 3.7 The surrounding screens stay quiet; the redline is still the memorable thing
- [ ] 3.8 Full flow runs end to end by hand without a visual break
- [ ] 3.9 The browser tab shows Falcon's mark, not the Next.js logo
- [ ] 3.10 The stamp-violet reads as ink, not as generic brand colour

### Phase 4: Quality floor & the manual sweep

#### Automated

- [ ] 4.1 Lint passes: `cd frontend && pnpm lint`
- [ ] 4.2 Production build succeeds: `cd frontend && pnpm build`
- [ ] 4.3 All 8 E2E specs pass with no spec file modified: `cd frontend && pnpm test:e2e`
- [ ] 4.4 `git diff --stat frontend/e2e/` is empty

#### Manual

- [ ] 4.5 Contrast: risk labels clear AA 4.5:1; margin rules and the stamp-violet focus ring clear 3:1
- [ ] 4.6 Colour-blind check: severity readable with colour ignored
- [ ] 4.7 Keyboard: full flow navigable with a visible focus ring throughout
- [ ] 4.8 Reduced motion: rule reveal does not animate; nothing is hidden
- [ ] 4.9 Responsive: all six screens at 375px and 1440px — no horizontal scroll
- [ ] 4.10 Whole-app sweep: all six screens read as one designed product
