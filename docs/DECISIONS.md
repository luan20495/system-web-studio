# Decisions

Canonical entry point to the decision record of XWEB. It has two parts: the **standing decisions** every engineer must know (curated, each with its id), and the **full index** of the decision ledger `docs/parallel/DECISIONS.md` (generated from that file, nothing added or reworded). The ledger is the evidence; this page is the map. Architecture-level decisions that predate the ledger live in `docs/adr/` (22 ADRs, indexed in `docs/ARCHITECTURE.md` section 12).

## How decisions are made

- A decision has an id: `D-NNN` (original board), `D-Cn-NN` (proposed by role Cn), `D-C0-NN` (taken by the integrator role C0 and the release owner). Blockers are `B-*`, handoffs `H-*`, incidents `INC-*`.
- **A shared contract (`docs/contracts/**`) changes only after a ledger entry exists.** A migration number is allocated only by C0 and written in `docs/parallel/MIGRATION_LEDGER.md` before the file exists.
- Statuses used by the ledger: PROPOSED, ACCEPTED, DECIDED, SUPERSEDED (by a later id, always named in the heading).
- The ledger is partly in Vietnamese (the first phase) and partly in English; ids and headings are stable. Roles C0..C7 are logical roles, not folders (see `docs/ARCHITECTURE.md` section 13).

## Standing decisions

### Architecture and process

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-001` | Modular monolith. Do not split into microservices without an ADR with evidence. | Giữ Modular Monolith · ACCEPTED · 2026-10-05 · C0 |
| `D-007` | Only C0 allocates Flyway migration numbers; one number belongs to one task; numbers are applied in ascending order; `outOfOrder` stays off; an applied migration is never edited. | C0 cấp số Flyway migration · ACCEPTED · 2026-10-05 · C0 |
| `D-C0-48` | Shared machine: never kill by name or by port; only pids / process groups you started, or the owned-process helper (guard `tests/guards/process-safety.mjs`). | Process safety on the shared machine: owned-process helper, process-safety guard, `stop-local.sh` made ownersh |
| `D-C0-29` | Release target: V1 = local macOS, production-shaped; V2 = VPS / cloud (configuration only); V3 = scale / HA. | Release target: V1 = LOCAL MACOS (production-shaped), V2 = VPS / CLOUD, V3 = SCALE / HA · DECIDED · 2026-10-06 |
| `D-C0-49` | The public portals run an explicitly approved immutable release; process recovery is not deployment. | Explicit public deployment pinning: process recovery is not deployment · DECIDED · 2026-10-08 · C0 |
| `D-C0-50` | The public API runs an explicitly approved release; API recovery is not API deployment. | Explicit public API release pinning: API recovery is not API deployment · DECIDED · first approved API release |

### Identity, tenancy, permissions

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-C1-11` | SYSTEM_ADMIN is a platform scope; business-data access exists only behind a legacy flag that must stay off. | SYSTEM_ADMIN là platform scope; business-data bypass chỉ sau cờ · ACCEPTED (C0) · 2026-10-05 · C1 |
| `D-C1-12` | `tenant_members` is the source of truth for tenant roles; TENANT_ADMIN has no implicit access to workspace or project data. | `tenant_members` là source of truth cho tenant role · ACCEPTED (C0) · 2026-10-05 · C1 |
| `D-C1-15` | Nobody grants anything to themselves. | Không ai tự cấp quyền cho mình (đóng R-08) · ACCEPTED · 2026-10-06 · C1 |
| `D-C1-14` | Canonical permission matrix, default deny; the vocabulary is a contract. | Ma trận quyền canonical (default deny) · PROPOSED (C0 gộp vào contract) · 2026-10-06 · C1 |
| `D-C0-60` | Decision A: the gateway authorizer does not check data-source ownership; a foreign or unknown data source is the canonical 404 of C3 (no existence oracle). Also the final IAM hardening import. | C1 final IAM hardening imported; decision A: no data-source ownership check in the gateway authorizer · DECIDE |

### Data

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-C3-17` | PostgreSQL data sources: TLS verify-full against the JVM trust store and a per-session preflight; a tenant cannot supply a CA. | PostgreSQL: TLS verify-full và preflight mỗi session · PROPOSED · 2026-10-06 · C3 |
| `D-C3-18` | SqlGuard fails closed: single read statement by construction. | SqlGuard fail-closed · PROPOSED · 2026-10-06 · C3 |
| `D-C0-18` | There is no browser-callable data mutation route; writes happen only through actions. | No browser-callable data mutation route; writes happen only through actions · ACCEPTED · 2026-10-06 · C0 |
| `D-C0-22` | A data source belongs to a workspace at run time. | Data sources are owned by a workspace at run time (B-C0-W-05) · ACCEPTED · 2026-10-06 · C0 (owner instruction) |
| `D-C0-31` | PostgreSQL targets are allow-listed by exact host:port; the platform and apps databases are always denied. | PostgreSQL data-source target policy by host:port, wired to configuration (B-C0-W-06) · DECIDED · 2026-10-07 · |

### Actions, workflows, approvals

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-C4-17` | An executor-level timeout or interruption of a mutating action is an unknown outcome (`IDEMPOTENCY_OUTCOME_UNKNOWN`, not retryable, not routed, not compensated). | executor-level TIMEOUT / INTERRUPTED of a MUTATING action is an unknown outcome (closes F-1) · ACCEPTED (C0) · |
| `D-C4-11` | Idempotency keys are derived; the raw key never leaves the action runtime. | Idempotency key dẫn xuất; key thô không rời ActionRuntime · ACCEPTED (C0, imported 2026-10-06) · 2026-10-06 ·  |
| `D-C0-32` | One WorkflowQueue bean selected by `app.workflow.queue`; RabbitMQ is the only production queue. | C4 queue (H-5) and C2 V30 imported; one WorkflowQueue bean; test heap · DECIDED · 2026-10-07 · C0 |
| `D-C0-61` | V33 `approvals`; approval decision route; the permission is the existing WORKFLOW_MANAGE (no APPROVAL_DECIDE in this release); run steps carry a nullable `approvalId`. | V33 `approvals` allocated; workflow approval runtime (FQ-WF-01 / FQ-WF-02) contract · DECIDED · 2026-10-11 · C |

### Publish and public runtime

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-C0-26` | C2 owns the publish pipeline paths (limited delegation). | C2 owns the publish pipeline paths (limited delegation) · DECIDED · 2026-10-06 · C0 (decision by C7) |
| `D-C0-33` | Lifecycle goes through the release scope; LIVE means the active release. | Lifecycle goes through the release scope; LIVE = the active release · DECIDED · 2026-10-07 · C0 |
| `D-C0-35` | Public runtime V1: a read-only LIVE query through the same-origin sites gateway. | Public Runtime frozen (V1: read-only LIVE query through the same-origin sites gateway); gap map; live V1 stack |
| `D-C0-36` | PUBLIC_SITE actor and the release allow-list; the route is behind a flag. | C1 PUBLIC_SITE imported; Public Runtime route, release allow-list and rate limit implemented (flag off) · DECI |

### Organization and migrations

| Id | What it fixes | Ledger heading |
|---|---|---|
| `D-C0-52` | V31 is a permanent void gap; V32 is the dynamic organization; organization data is never authorization. | C3 Dynamic Organization persistence (V32) imported; V31 declared a permanent VOID gap; structural lock extende |
| `D-C0-44` | Contract-independent frontend guards and one frontend gate command. | Contract-independent guards for the Dynamic Organization work, one frontend gate command, V31 / V32 reservatio |

## Superseded decisions (do not follow)

- D-006 — Migration không liên tục · SUPERSEDED bởi D-007 · 2026-10-05 · C0
- D-C3-00a (was D-008; renamed by C0 at import: the id collides with the C2 branch) — DataConnector foundation: bổ sung contract và chính sách bảo mật · SUPERSEDED bởi D-C3-01…03 (giữ làm lịch sử) · 202

## Full index of the ledger

110 decisions in `docs/parallel/DECISIONS.md`, in file order (the order they were taken). Search the ledger by id.

| Id | Title | Status | Date | Role |
|---|---|---|---|---|
| `D-001` | Giữ Modular Monolith | ACCEPTED | 2026-10-05 | C0 |
| `D-002` | Gán mặc định cho module legacy không có owner | PROPOSED | 2026-10-05 | C0 |
| `D-003` | SSRF guard & connector hiện có nằm trong `runtime/Gateway.kt` | PROPOSED | 2026-10-05 | C0 |
| `D-004` | Frontend ở root repo, không có `frontend/` | ACCEPTED | 2026-10-05 | C0 |
| `D-005` | API client frontend dùng chung | PROPOSED | 2026-10-05 | C0 |
| `D-006` | Migration không liên tục | SUPERSEDED bởi D-007 | 2026-10-05 | C0 |
| `D-007` | C0 cấp số Flyway migration | ACCEPTED | 2026-10-05 | C0 |
| `D-C0-10` | Web-only V2 preparation applied on integration/v2 (uncompiled) | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-11` | Wiring skeleton is non-compiled until the agent code is imported | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-12` | Frontend monorepo, three deployments (ADR 0022) | ACCEPTED | 2026-10-06 | C0 |
| `D-C1-11` | SYSTEM_ADMIN là platform scope; business-data bypass chỉ sau cờ | ACCEPTED (C0) | 2026-10-05 | C1 |
| `D-C1-12` | `tenant_members` là source of truth cho tenant role | ACCEPTED (C0) | 2026-10-05 | C1 |
| `D-C1-13` | Compatibility V26 có kế hoạch gỡ | ACCEPTED | 2026-10-06 | C1 |
| `D-C1-14` | Ma trận quyền canonical (default deny) | PROPOSED (C0 gộp vào contract) | 2026-10-06 | C1 |
| `D-C1-15` | Không ai tự cấp quyền cho mình (đóng R-08) | ACCEPTED | 2026-10-06 | C1 |
| `D-C1-16` | Adapter C1 là lõi policy thuần, C0 nối bằng `wiring.*Adapter` | ACCEPTED | 2026-10-06 | C1 |
| `D-C3-00a` | DataConnector foundation: bổ sung contract và chính sách bảo mật | SUPERSEDED bởi D-C3-01…03 (giữ làm lịch  | 2026-10-05 | C3 |
| `D-C3-01` | DataConnector: bổ sung contract (thay D-008) | PROPOSED | 2026-10-05 | C3 |
| `D-C3-02` | Credential tách riêng + DataSource.version trong cache key | PROPOSED | 2026-10-05 | C3 |
| `D-C3-03` | Chính sách bảo mật connector | PROPOSED | 2026-10-05 | C3 |
| `D-C3-04` | Mapping/ViewModel contract + Transform DSL + Expression sandbox | PROPOSED | 2026-10-05 | C3 |
| `D-C3-05` | DataGateway | PROPOSED | 2026-10-05 | C3 |
| `D-C3-06` | Cache | PROPOSED | 2026-10-05 | C3 |
| `D-C3-07` | Mutation scope + idempotency | PROPOSED | 2026-10-05 | C3 |
| `D-C3-08` | Sync V1 | PROPOSED | 2026-10-05 | C3 |
| `D-C3-09` | Webhook ingress | PROPOSED | 2026-10-05 | C3 |
| `D-C3-10` | Realtime qua SSE + vị trí package | PROPOSED | 2026-10-05 | C3 |
| `D-C3-11` | Đối soát C2/C4 | PROPOSED | 2026-10-05 | C3 |
| `D-C3-12` | Type canonical của C1 + `GatewayContext` | PROPOSED | 2026-10-06 | C3 |
| `D-C3-13` | Mapping: `fields[].transforms[]` canonical | PROPOSED | 2026-10-06 | C3 |
| `D-C3-14` | Idempotency: trạng thái và mã lỗi mới | PROPOSED | 2026-10-06 | C3 |
| `D-C3-15` | Cache: ticket protocol chống ghi đè kết quả cũ | PROPOSED | 2026-10-06 | C3 |
| `D-C3-16` | Webhook: path, chữ ký v2, replay | PROPOSED | 2026-10-06 | C3 |
| `D-C3-17` | PostgreSQL: TLS verify-full và preflight mỗi session | PROPOSED | 2026-10-06 | C3 |
| `D-C3-18` | SqlGuard fail-closed | PROPOSED | 2026-10-06 | C3 |
| `D-C3-19` | `AiDataCatalog` từ `AiSafeSchema` | PROPOSED | 2026-10-06 | C3 |
| `D-C3-20` | Address security: bỏ `SupplementaryRanges` | PROPOSED | 2026-10-06 | C3 |
| `D-C4-01` | Danh sách ActionType chốt (đóng kín) | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-02` | ActionContext của C4 + port thay cho AccessContext/ActionAuthorizer | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-03` | Định nghĩa action/workflow đọc từ AppDefinition canonical | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-04` | Mô hình thực thi workflow | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-05` | Phạm vi idempotency | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-06` | Ngữ nghĩa TEST mode | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-07` | Ngữ nghĩa Approval | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-08` | Scheduler chỉ enqueue | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-09` | Notification là port, không vendor | ACCEPTED (C0, imported 2026-10-06) | 2026-10-05 | C4 |
| `D-C4-10` | `ActionDef.trigger` là tùy chọn trên định nghĩa, bắt buộc chỉ với action gắn UI | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-11` | Idempotency key dẫn xuất; key thô không rời ActionRuntime | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-12` | Một cửa dữ liệu: ActionRuntime → ActionDataPort → adapter C0 → DataGateway | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-13` | Cách ly poison/outage và sweeper công bằng | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-14` | Rate limit theo tenant | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-15` | Định danh thực thi của schedule và đăng ký idempotent | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-16` | Retention cho dữ liệu chạy | ACCEPTED (C0, imported 2026-10-06) | 2026-10-06 | C4 |
| `D-C4-17` | executor-level TIMEOUT / INTERRUPTED of a MUTATING action is an unknown outcome (closes F-1) | ACCEPTED (C0) | 2026-10-06 | C4 |
| `D-C0-13` | an unknown-outcome write does not run the UI `onError` chain | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-14` | C4 keeps its own `logic.action.ActorKind`; adapters convert by name | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-15` | C3 failures map to C4 `PortOutcome.Failure` in the `ActionDataPortAdapter` (W-05) | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-16` | C4 official import: scope and evidence | ACCEPTED | 2026-10-06 | C0 |
| `D-C5-01` | Builder tách Edit và Test; Test không ghi AppDefinition | PROPOSED | 2026-10-05 | C5 |
| `D-C5-02` | Đường dẫn API Data/Action/Workflow theo project; tenant do server suy ra | PROPOSED | 2026-10-05 | C5 |
| `D-C5-03` | Dữ liệu trên site xuất bản: chụp lúc publish, không gọi lúc chạy | PROPOSED | 2026-10-05 | C5 |
| `D-C5-04` | Frontend thành monorepo npm workspaces: 3 app + packages dùng chung | PROPOSED | 2026-10-05 | C5 |
| `D-C5-05` | Quyền vào portal (tạm) bám `me.systemAdmin`; ẩn UI không thay thế kiểm quyền server | PROPOSED | 2026-10-05 | C5 |
| `D-C5-06` | Link portal theo cấu hình; dev server bind loopback | PROPOSED | 2026-10-06 | C5 |
| `D-C0-17` | Runtime API route family: project-scoped `/app-runtime`; no competing family | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-18` | No browser-callable data mutation route; writes happen only through actions | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-19` | Admin portal stays platform-only (Q-1) | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-20` | Persistence gap: LIVE data path answers 503; C4 in-memory stores need an explicit acknowledgement | ACCEPTED | 2026-10-06 | C0 |
| `D-C0-21` | Persistence for real LIVE E2E: V28 data runtime, V29 run stores; D1–D7 | ACCEPTED | 2026-10-06 | C0 (owner decisions) |
| `D-C0-22` | Data sources are owned by a workspace at run time (B-C0-W-05) | ACCEPTED | 2026-10-06 | C0 (owner instructio |
| `D-C0-23` | V29 durable action / workflow run state; restart-safety rules | PROPOSED (implemented on `wire/v29-run-p | 2026-10-06 | C0 |
| `D-C0-25` | V29 is completed in place with the C4 contract (A-1 `mutating`, H-3 lease); no V30 is allocated | DECIDED (implemented on `wire/v29-run-pe | 2026-10-06 | C0 |
| `D-C0-26` | C2 owns the publish pipeline paths (limited delegation) | DECIDED | 2026-10-06 | C0 (decision by C7) |
| `D-C0-27` | V29 closed on `integration/v2`; V30 reserved for C2; handoffs C2 / C3 / C5 | DECIDED | 2026-10-06 | C0 |
| `D-C0-28` | Management API v2 contract frozen (data sources, credentials, schema, query / mutation definitions, TEST/LIVE bindings); NOT integrated | DECIDED | 2026-10-06 | C0 |
| `D-C0-29` | Release target: V1 = LOCAL MACOS (production-shaped), V2 = VPS / CLOUD, V3 = SCALE / HA | DECIDED | 2026-10-06 | C0 (owner instructio |
| `D-C0-30` | C3 Management API imported into `integration/v2`; Management API READY_TO_CONSUME | DECIDED | 2026-10-06 | C0 |
| `D-C0-31` | PostgreSQL data-source target policy by host:port, wired to configuration (B-C0-W-06) | DECIDED | 2026-10-07 | C0 |
| `D-C0-32` | C4 queue (H-5) and C2 V30 imported; one WorkflowQueue bean; test heap | DECIDED | 2026-10-07 | C0 |
| `D-C0-33` | Lifecycle goes through the release scope; LIVE = the active release | DECIDED | 2026-10-07 | C0 |
| `D-C0-34` | Config hygiene (L-2), apiBase, portals (L-1), TLS data target (L-6) | DECIDED | 2026-10-07 | C0 |
| `D-C0-35` | Public Runtime frozen (V1: read-only LIVE query through the same-origin sites gateway); gap map; live V1 stack smoke | DECIDED | 2026-10-07 | C0 |
| `D-C0-36` | C1 PUBLIC_SITE imported; Public Runtime route, release allow-list and rate limit implemented (flag off) | DECIDED | 2026-10-07 | C0 |
| `D-C0-37` | C2 V1 work reviewed and imported (no duplicate of C0 / C1 work); B-C0-W-07 closed; PAGE_SCHEMA runtime; V31 approved with conditions; first  | DECIDED | 2026-10-07 | C0 |
| `D-C0-38` | Global ingress OPTION A: the existing public stack (`hblpub`) upgraded to V1; trusted proxy measured; https at the gateway; global smoke | DECIDED | 2026-10-07 | C0 |
| `D-C0-39` | Public 3-portal ingress: Platform / Admin / Studio on their own hostnames, same-origin API, portal gateway | DECIDED | 2026-10-07 | C0 |
| `D-C1-13A` | Provisioning hierarchy: platform / tenant / workspace are three scopes; tenant-owned workspace creation uses the tenant route | ACCEPTED | 2026-10-07 | C1 (recorded by C0) |
| `D-C0-40` | P0 integration, step 1: C1 `2356d64` imported and gated GREEN; C5 import HELD (its provisioning wiring is not committed, and the legacy work | DECIDED | 2026-10-07 | C0 |
| `D-C0-41` | P0 integration, step 2: C5 `a3f9a4e` imported; RC baseline built and verified; USER01 stays BLOCKED on C1 H-C1-04 | DECIDED | 2026-10-07 | C0 |
| `D-C0-42` | C5 `4ef8439` imported (redesigned create-company dialog, H-C0-13); portals rebuilt clean and restarted; UI verified in a real browser | DECIDED | 2026-10-08 | C0 |
| `D-C0-43` | C5 `22e07cd` imported: Dynamic Organization UI prework (frontend only, every mutation NOT_READY until C1 H-C1-17); organization architecture | DECIDED | 2026-10-08 | C0 |
| `D-C0-44` | Contract-independent guards for the Dynamic Organization work, one frontend gate command, V31 / V32 reservations enforced | DECIDED | 2026-10-08 | C0 |
| `D-C0-45` | Portal build / restart reliability: source-aware `up`, fingerprint-named dist directories, atomic swap, foreign-process protection | DECIDED | 2026-10-08 | C0 |
| `D-C0-46` | C0 guards (D-C0-44) and portal reliability (D-C0-45) imported into integration; `ORG_MANAGE` stays a TEMPORARY allow-listed finding | DECIDED | 2026-10-08 | C0 |
| `D-C0-47` | C5 `5cc230e` imported: UI hardening (initials, accessibility, responsive menu, Lucide glyphs, table containment, builder toolbar, search deb | DECIDED | 2026-10-08 | C0 |
| `D-C0-48` | Process safety on the shared machine: owned-process helper, process-safety guard, `stop-local.sh` made ownership-safe | DECIDED | 2026-10-08 | C0 |
| `D-C0-49` | Explicit public deployment pinning: process recovery is not deployment | DECIDED | 2026-10-08 | C0 |
| `D-C0-50` | Explicit public API release pinning: API recovery is not API deployment | DECIDED | first approv | C0 |
| `D-C0-51` | C1 final IAM / tenant / account lifecycle / canonical permissions / organization contract imported; organization permissions frozen; ORG_MAN | DECIDED | 2026-10-09 | C0 |
| `D-C0-52` | C3 Dynamic Organization persistence (V32) imported; V31 declared a permanent VOID gap; structural lock extended; counts / offset / ORG_STRUC | DECIDED | 2026-10-09 | C0 |
| `D-C0-53` | Unified IAM + C2 + C3 + C5 integration gate | DECIDED | 2026-10-10 | C0 |
| `D-C0-54` | C1 M-052 multi-tenant `/auth/me` (`tenants[].permissions`) imported | DECIDED | 2026-10-10 | C0 |
| `D-C0-55` | C5 final (`agent/c5-web @ 09f27e5`) imported officially; isolated stack tooling reconciled | DECIDED | 2026-10-10 | C0 |
| `D-C0-56` | Public portals redeployed from the final integration SHA; final isolated flag-ON stack; real flows | DECIDED | 2026-10-10 | C0 |
| `D-C0-57` | C5 flow fixes imported; ORG01 31/31 and PD02 PASS; public API: DEFER (RC decision) | DECIDED | 2026-10-10 | C0 |
| `D-C0-58` | RC candidate 1: C4 runtime authorization + FQ-ACT-02, C2 publish-authorization tests, C5 FQ-PERF-01 imported; heavy gates in the quiet windo | DECIDED | 2026-10-10 | C0 |
| `D-C0-59` | RC candidate 1 stack `c0rc` from `2f5a86c4e0f9f9b2a158d6def2948d9547a6c8e0`; data-target trust store proven; handoff to C6 | DECIDED | 2026-10-10 | C0 |
| `D-C0-60` | C1 final IAM hardening imported; decision A: no data-source ownership check in the gateway authorizer | DECIDED | 2026-10-11 | C0 (final consolidat |
| `D-C0-61` | V33 `approvals` allocated; workflow approval runtime (FQ-WF-01 / FQ-WF-02) contract | DECIDED | 2026-10-11 | C0 |
| `D-C0-62` | Final consolidation: one source of truth, canonical documentation, golden company acceptance, operating-model transition | DECIDED | 2026-10-11 | C0 (final consolidat |
