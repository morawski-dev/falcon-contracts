---
change_id: testing-auth-boundary-regression
title: "Auth boundary regression: anonymous→401 route matrix (test-plan Phase 1)"
status: implemented
created: 2026-07-06
updated: 2026-07-06
archived_at: null
---

## Notes

Open a change folder for rollout Phase 1 of context/foundation/test-plan.md: "Auth boundary regression". Risks covered: #5 — a gated endpoint silently becomes reachable without authentication after a security-config change (default-deny erosion). Test types planned: security-slice integration (MockMvc). Risk response intent: prove that every non-permit-listed route returns 401 to an anonymous caller; the permit-list is exactly the bootstrap set (POST /api/auth/register, POST /api/auth/login, GET /api/auth/csrf); a newly added route defaults to authenticated; the entry point is 401, not a redirect. Assert behavior per route (a permit-list typo will not fail compilation). Cheapest layer: an anonymous->401 route matrix. Avoid asserting one endpoint and generalizing, or snapshotting the security config instead of exercising routes.
