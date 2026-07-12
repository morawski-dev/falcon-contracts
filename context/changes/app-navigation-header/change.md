---
change_id: app-navigation-header
title: App navigation header
status: impl_reviewed
created: 2026-07-12
updated: 2026-07-13
archived_at: null
---

## Notes

**Regression found and fixed after Phase 3 landed:** deleting all five files from `frontend/public/` (Phase 3, change 3) left the directory completely empty, and since git doesn't track empty directories, `frontend/public/` vanished from the working tree entirely. This broke `frontend/Dockerfile`'s `COPY --from=build /app/public ./public` step (line 34) — the `build` stage never produced a `/app/public` to copy from. Surfaced when the user ran `docker-compose up -d` and hit `"/app/public": not found`. Fixed by adding `frontend/public/.gitkeep` (the standard placeholder for an intentionally-empty tracked directory) — verified with `docker compose build frontend`, which now succeeds. Folded into Phase 4's commit since Phase 3 was already landed.

**Known environment issue (unrelated to this change):** during Phase 1 and Phase 2 implementation, `analysis-result.spec.ts` intermittently failed with content that traces exactly to `frontend/e2e/fixtures.ts`'s `CONTRACT_TEXT` (verbatim ASCII text, real numbers) instead of the deterministic `e2e`-profile fixture (`ClauseAnalysisFixtures.MULTI_CLAUSE_JSON`) — meaning a non-`e2e`-profile backend was bound to port 8080 (verified via `Get-NetTCPConnection`; the port is WSL2/Docker-forwarded, so the actual owning process isn't visible from Windows). Confirmed unrelated to the plan: the identical failure reproduced at clean HEAD before any Phase 1 work started, and the suite passed 8/8 cleanly once the port was genuinely free (commit `dae5e54`'s verification run). Recurred once more after Phase 2's edits with the same signature. Accepted as a pre-existing local-environment flake, not blocking phase progress on it further.
