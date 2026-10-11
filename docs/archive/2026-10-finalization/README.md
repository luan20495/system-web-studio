# Archive 2026-10 (finalization)

Temporary working documents of the build (handoffs, proposals, audits, status boards) that a canonical document in `docs/` has replaced. They are kept for **auditability**: decisions, evidence and the history of a finding. Each carries a `SUPERSEDED_BY` banner naming the document to read instead. Nothing here is current guidance.

Not archived on purpose: the frozen contracts (`docs/contracts/**`), the decision ledger and the migration ledger (`docs/parallel/DECISIONS.md`, `docs/parallel/MIGRATION_LEDGER.md`), the undo scripts, the QA record (`docs/parallel/c6/**`) and every document that a test or a guard reads by path. The current status is `docs/PROJECT_STATUS.md`; the decision map is `docs/DECISIONS.md`.

## Moved here (17 documents, no other document referenced them)

| Original path | Now at | Superseded by |
|---|---|---|
| `docs/parallel/agents/C1_T1.md` | `docs/archive/2026-10-finalization/parallel/agents/C1_T1.md` | `docs/SECURITY_AND_PERMISSION.md` |
| `docs/parallel/agents/C2_T6.md` | `docs/archive/2026-10-finalization/parallel/agents/C2_T6.md` | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/agents/C3_T8.md` | `docs/archive/2026-10-finalization/parallel/agents/C3_T8.md` | `docs/DATA_RUNTIME.md` |
| `docs/parallel/agents/C4_PREP_T13.md` | `docs/archive/2026-10-finalization/parallel/agents/C4_PREP_T13.md` | `docs/ACTION_WORKFLOW.md` |
| `docs/parallel/agents/C5_PREP_T12.md` | `docs/archive/2026-10-finalization/parallel/agents/C5_PREP_T12.md` | `docs/ARCHITECTURE.md` |
| `docs/parallel/agents/C5_VERIFICATION.md` | `docs/archive/2026-10-finalization/parallel/agents/C5_VERIFICATION.md` | `docs/ARCHITECTURE.md` |
| `docs/parallel/audit/C2-integration-handoff.md` | `docs/archive/2026-10-finalization/parallel/audit/C2-integration-handoff.md` | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c5/HANDOFF_C1.md` | `docs/archive/2026-10-finalization/parallel/c5/HANDOFF_C1.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFF_C2.md` | `docs/archive/2026-10-finalization/parallel/c5/HANDOFF_C2.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFF_C3.md` | `docs/archive/2026-10-finalization/parallel/c5/HANDOFF_C3.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/LEDGER_STATUS_NORMALIZED.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/LEDGER_STATUS_NORMALIZED.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/R-shared-fix-clusters.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/R-shared-fix-clusters.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/R2-ledger-review.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/R2-ledger-review.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/tools/wave-briefs/COMMON.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/tools/wave-briefs/COMMON.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/tools/wave-briefs/S1-task.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/tools/wave-briefs/S1-task.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/tools/wave-briefs/S2-task.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/tools/wave-briefs/S2-task.md` | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/tools/wave-briefs/S3-task.md` | `docs/archive/2026-10-finalization/parallel/c5/audit/tools/wave-briefs/S3-task.md` | `docs/QA_FINAL.md` |

## Left in place with a banner (44 documents, other documents still link to them)

| Path | Inbound references | Superseded by |
|---|---|---|
| `docs/IMPLEMENTATION_STATUS.md` | 7 | `docs/PROJECT_STATUS.md` |
| `docs/TIEN_DO.md` | 1 | `docs/PROJECT_STATUS.md` |
| `docs/parallel/agents/C3_DATA_PLATFORM.md` | 3 | `docs/DATA_RUNTIME.md` |
| `docs/parallel/agents/C3_PUBLIC_ADDRESS_PATCH.md` | 4 | `docs/DATA_RUNTIME.md` |
| `docs/parallel/agents/C3_SCHEMA_PROPOSAL.md` | 4 | `docs/DATA_RUNTIME.md` |
| `docs/parallel/agents/C3_WIRING_PROPOSAL.md` | 5 | `docs/DATA_RUNTIME.md` |
| `docs/parallel/agents/C5_PHASE2_PORTALS.md` | 3 | `docs/ARCHITECTURE.md` |
| `docs/parallel/audit/C4-standalone-readiness.md` | 1 | `docs/ACTION_WORKFLOW.md` |
| `docs/parallel/audit/FINAL-C4-runtime-design.md` | 7 | `docs/ACTION_WORKFLOW.md` |
| `docs/parallel/audit/PREP-T12-builder-architecture.md` | 5 | `docs/ARCHITECTURE.md` |
| `docs/parallel/audit/PREP-T13-action-runtime-design.md` | 2 | `docs/ACTION_WORKFLOW.md` |
| `docs/parallel/c0/HANDOFFS_2026-10-06.md` | 1 | `docs/PROJECT_STATUS.md` |
| `docs/parallel/c0/HANDOFFS_2026-10-07.md` | 2 | `docs/PROJECT_STATUS.md` |
| `docs/parallel/c2/BATCH2_HANDOFF.md` | 5 | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c2/DECISION_REQUEST_PUBLISHED_RUNTIME.md` | 1 | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c2/HANDOFF_C3_PUBLIC_QUERY.md` | 2 | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c2/HANDOFF_C5_PAGE_SCHEMA_DATA.md` | 5 | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c2/V31_CANDIDATE_ACTIVATION_PROPOSAL.md` | 4 | `docs/PUBLISH_RUNTIME.md` |
| `docs/parallel/c5/C5_NEXT_SESSION_HANDOFF.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFFS_PAGE_SCHEMA_DATA.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFFS_PORTALS.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFF_C0.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFF_C4.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/HANDOFF_INDEX.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/FINAL_EVIDENCE_2026-10-10.md` | 6 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/FINAL_HARDENING_2026-10-10.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/H-C1-04-frontend.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/HANDOFFS_FRONTEND_AUDIT.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/IAM_PERMISSION_INVENTORY.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/M-053-bundle-splitting.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/M-068-button-convergence.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/PL01_ORG_AD01_C2_2026-10-10.md` | 4 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/R-architecture.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/R2-security.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S1-data-binding-proposal.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S1-studio.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S2-people-screens.md` | 6 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S2-platform-admin.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S3-design-system.md` | 2 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S3-text-and-terminology.md` | 3 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S4-baselines.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S4-cross-browser.md` | 6 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S4-matrix-baseline.md` | 1 | `docs/QA_FINAL.md` |
| `docs/parallel/c5/audit/S4-performance-tooling.md` | 2 | `docs/QA_FINAL.md` |
