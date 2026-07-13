---
change_id: ui-design-system
title: Coherent UI design system — the "Redline / Kancelaria" identity
status: archived
created: 2026-07-13
updated: 2026-07-13
archived_at: 2026-07-13T11:36:22Z
---

## Notes

**Phase 3 deviation (undocumented at the time, recorded here per impl-review F3):** `frontend/src/components/ui/card.tsx` and `frontend/src/app/(app)/analyses/new/page.tsx` were named in the plan as Phase 3 edit targets but were deliberately left unedited. Both already restyle correctly via Phase 1's token cascade (`bg-primary`, `border-border`, `CardTitle`'s `font-heading` → Fraunces all resolve with zero changes to these files) — per the plan's own "tokens first, check whether a token would do it before editing a primitive" rule. This was explained in conversation during implementation but never written back into the plan; recording it now for anyone reading the plan in isolation.
