# C5 handoffs (non-C5 issues)

| ID | Owner | Severity | Blocks |
|---|---|---|---|
| H-C0-01 management API: sources/credentials/bindings coded by C3 but not integrated or verified; queries/mutations still missing (B-C0-W-03) | C0/C3 | P1 | E2E-06, 07*, 08, 09 |
| H-C0-02 V29 run stores / volatile-store 503 (B-C0-W-01) | C0/C4 | P1 | E2E-09, 12 |
| H-C0-03 APP_EDIT vs PROJECT_EDIT naming | C0 | P3 | - |
| H-C0-04 flag-off indistinguishable from not-found | C0 | P2 | - |
| H-C0-05 what C5 needs to run the real-backend suite: official backend base URL, integration-ready baseline, final runtime topology, E2E auth/session assumptions | C0 | P1 | all 14 (real-backend result) |
| H-C1-01 single OIDC redirect URI (B-C0-WEB-01) | C1 | P2 | - |
| H-C1-02 Admin platform-only (Q-1) | C1 | P3 | - |
| H-C2-01 published app has no data runtime host (B-C5-06) | C2 | P1 | E2E-08, 09 |
| H-C2-02 no AppDefinition op to declare a data slot | C2 (C0 decides) | P2 | E2E-06*, 07*, 08, 09 |
| H-C2-03 runtime-config contract: frontend support confirmed (no request) | C2 | info | - |
| H-C3-01 production connectors read-only (B-C0-W-04) | C3 | P1 | E2E-09 |
| H-C3-02 Management API doc vs controller (error table, non-atomic PATCH, filtered binding list, FORBIDDEN vs PERMISSION_DENIED, sourceRef vs bindings, flag-off shape); compile/route tests not verified | C3 | P2 | E2E-06, 07, 08 |
| H-C3-03 no approved-query / mutation management endpoint | C3 | P1 | E2E-06*, 07, 09 |
| H-C4-01 no RabbitMQ / in-memory run stores (B-C4-05/06) | C4 | P1 | E2E-12, 14 |

`*` = the query half only. C4: no new request this round.

Files: `HANDOFF_C0.md` … `HANDOFF_C4.md`. C6 retest: `docs/C5_REAL_BACKEND_E2E_RUNBOOK.md`. Audit: `API_AUDIT_F894CC6.md`.
