# UI Design System — "Redline / Kancelaria" — Plan Brief

> Full plan: `context/changes/ui-design-system/plan.md`
> Design brief (locked): `context/foundation/roadmap.md` § S-06

## What & Why

Falcon is feature-complete but wears the untouched Next.js/shadcn scaffold — and one of the scaffold's defects means it currently renders in **no chosen font at all**. This change gives Falcon a single, deliberate visual identity across all six screens and rebuilds the analysis result as what it actually is: *a contract someone marked up*. It also promotes clause risk — the product's whole meaning — from hardcoded Tailwind swatches into first-class theme tokens.

## Starting Point

Six working screens, styled entirely by the scaffold. Three concrete defects:

1. **The font chain is broken.** `globals.css:10` reads `--font-sans: var(--font-sans)` — a CSS dependency cycle, so it resolves to the guaranteed-invalid value. The app renders in **the UA's default serif (Times), not Geist**. (Free pre-flight check: look at the running app — the body text is serif.)
2. **The theme has zero chroma.** Every `:root` colour is `oklch(L 0 0)`.
3. **Risk lives outside the design system.** `src/lib/risk.ts:14-18` maps risk levels to `bg-amber-100` / `bg-emerald-100`, and overloads `--destructive` for HIGH — so a high-risk clause and the *delete* button currently share an ink.
4. **The browser tab shows the Next.js logo.** `src/app/favicon.ico` is the stock `create-next-app` icon, untouched since bootstrap.

A fourth fact shapes everything: an 8-spec Playwright suite guards these screens, and because `CLAUDE.md` mandates accessibility-first locators, **every assertion is `getByRole`/`getByLabel`/`getByText`** — no CSS selectors anywhere.

## Desired End State

Every screen reads as one system. The analysis result is a document with a lawyer's margin: each clause carries a `§` reference, a severity-weighted rule in risk ink, and its risk level spelled out in words — the mark sitting where a pen stroke would. The auth screen is a considered front door rather than a bare card. Risk is retunable from one file.

Verified by: **all 8 E2E specs passing with zero spec edits**, plus a manual sweep.

## Key Decisions Made

| Decision | Choice | Why | Source |
| --- | --- | --- | --- |
| Visual direction | Redline / Kancelaria — ink on paper, stamp-violet accent | The subject's own world; the mark belongs in the margin, not floating above the text | Roadmap S-06 |
| Risk in the theme | Semantic `--risk-*` tokens | Risk is the product's meaning; it belongs in the theme, not a TS string map | Plan |
| Contrast strategy | **Two-tier ink** — `-mark` (full hue, for rules) + `-ink` (darkened, for text) | Keeps the signature element vivid while the small text clears WCAG AA | Plan |
| Dark mode | Light-only — but **strip the 12 `dark:` utilities BEFORE deleting `@custom-variant dark`** | That line is not dead code: it's the only thing *suppressing* dark mode. Deleting it first would turn dark mode on, half-configured | Plan review (F1) |
| Where the violet lands | `§` marks, focus ring, wordmark, primary button | An accent with no named consumer never ships. Four is the upper bound — cut the button first if it reads as brand colour | Plan review (F2) |
| Clause DOM | **Treat the E2E DOM contract as fixed** | A green suite on untouched specs *proves* the restyle lost no meaning | Plan |
| Component strategy | Tokens first, surgical primitive edits | Largest reach per line changed; keeps the shadcn upgrade path | Plan |
| Motion | One earned moment (rules draw in), reduced-motion safe | Scattered micro-interactions are what make a design read as AI-generated | Plan |
| Auth screens | The identity statement (wordmark at display size) | The least-designed screen is currently the first one anyone sees | Plan |
| Verification | Green E2E + scripted manual sweep; no new tooling | Screenshot baselines thrash across Windows/Linux; axe needs a dep (`--frozen-lockfile`) | Plan |

## Scope

**In scope:** the token layer (palette, type, risk); the font-chain bug fix; the analysis result rebuilt as a marked-up contract; dashboard, new-analysis, header, and auth screens brought into the system; the accessibility and responsive floor.

**Out of scope:** dark mode · any new screen, route, or feature · any new npm dependency · visual-regression tooling · **any edit to `frontend/e2e/`** · backend, schema, API, or auth changes · rewriting the shadcn primitives.

## Architecture / Approach

A token layer in `globals.css` does the work; the screens consume it. Because Tailwind v4 is CSS-first (no `tailwind.config.*`), every token must be declared **twice** — the raw value in `:root`, and a `--color-*` mapping in `@theme inline` to generate the utility. (The existing `--font-sans` bug is precisely this mistake.)

Phases are ordered so the token layer's **first consumer is the signature element** — the cheapest possible test of whether the tokens are right. Building the redline first would mean hardcoding hexes and retrofitting them onto tokens later, which is how the same colour ends up defined in two places and drifting apart.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Foundation — tokens & type | Font bug fixed; three families wired; palette + two-tier risk tokens; dark mode removed **in the right order** | Two: Fraunces may lack a `latin-ext` subset (verified as the first task; IBM Plex Serif is the fallback). And **deleting `@custom-variant dark` before stripping the 12 `dark:` utilities silently activates dark styles for OS-dark-mode users** — invisible on a light-mode machine |
| 2. The margin redline | `analyses/[id]` rebuilt as a marked-up document — the signature | **The clause DOM contract.** The `data-testid` sits on `<CardContent>` and the `role="group"` decision cluster must stay *inside* it. Move it and six assertions silently fail |
| 3. The surrounding screens | Dashboard, new-analysis, header, auth-as-front-door | `getByLabel("Email"/"Hasło"/"Tytuł"/"Treść umowy")` — the fixtures call these in *every* test; break a `<Label htmlFor>` and the whole suite dies |
| 4. Quality floor & sweep | One motion moment; focus, contrast, responsive; the manual sweep | With no visual-regression tooling in the repo, this sweep is the **only** visual gate that exists |

**Prerequisites:** S-01…S-05 all `done` (they are). No new tooling, no environment setup.
**Estimated effort:** ~3-4 sessions, one per phase, each ending at a manual-confirmation gate.

## Open Risks & Assumptions

- **Fraunces' Polish diacritic coverage is unverified.** Phase 1 opens by testing it; if it fails, the display role becomes IBM Plex Serif and nothing else moves.
- **Ochre (`#A9762B`) is borderline as text at body size.** The two-tier ink strategy exists precisely to absorb this — if it fails AA, the `-ink` tier darkens and the `-mark` tier keeps the hue.
- **The margin gutter is the responsive risk.** A fixed gutter plus a `max-w-2xl` column will crush clause text at 375px.
- **Scope is the real enemy.** This slice can quietly become "rebuild the frontend." The counter-pressure: no new screens, no new deps, no spec edits.
- **The opposite failure is timidity.** Swapping the palette while leaving the card stack intact would be a repaint, not the outcome.

## Success Criteria (Summary)

- A user reading a saved analysis sees a marked-up contract — severity legible at a glance from the margin alone, and still legible with colour ignored.
- All six screens read as one designed product; no screen looks scaffolded next to another.
- All 8 E2E specs pass **with no spec file modified** — the proof that appearance changed completely while meaning changed not at all.
