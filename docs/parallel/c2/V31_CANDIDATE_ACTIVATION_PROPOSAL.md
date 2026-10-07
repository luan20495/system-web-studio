# V31 PROPOSAL — candidate activation (LIM-1, Batch 3). For C0 review. **No migration file exists; no number is reserved or assumed.**

Raised by C2 on 2026-10-07 against `integration/v2 @ ef0d890`. Target semantics are C0's, frozen in `docs/contracts/v2/published-runtime.md` §2.2 (I1–I9, D-C0-33…35); this document only fixes the exact schema and the transaction so C0 can approve or amend it. Nothing here is implemented. V30 is immutable; everything below is a new migration that C0 numbers.

## 1. Invariants being implemented (from the contract)

I1 the served set is `{ current_deployment_id }` and only while that deployment is `RUNNING` · I2 a **candidate** is a column of the SAME scope row, written and cleared only by the lease holder in a CAS, never read by the gateway, the Data Runtime or any visitor-facing code · I3 one lease: stage candidate → verify → **one** CAS that sets `current := candidate`, clears the candidate, bumps the version, and in the same transaction `DEPLOYING → RUNNING` · I4 a failure before step 3 clears the candidate and ends `FAILED`; nothing was served · I5 a stale / fenced-out publisher can neither write the candidate nor activate · I6 rollback and unpublish clear any candidate in their own CAS · I7 a candidate whose lease expired is cleared (or resumed by the same operation) by the next acquirer · I8 the V30 status meanings are unchanged · I9 only `JdbcScopeGuard` writes the pointer and the candidate.

## 2. Schema (one migration, additive, nullable, no data rewrite)

```sql
-- sites: the candidate of the scope (all three columns NULL = no candidate)
ALTER TABLE sites ADD COLUMN candidate_deployment_id UUID REFERENCES deployments(id);   -- FK, ON DELETE NO ACTION
ALTER TABLE sites ADD COLUMN candidate_fence BIGINT;                                     -- the fencing token under which it was staged
ALTER TABLE sites ADD COLUMN candidate_since TIMESTAMPTZ;                                -- diagnostics / SiteInfo.operation

ALTER TABLE sites ADD CONSTRAINT sites_candidate_shape_check CHECK (
    (candidate_deployment_id IS NULL) = (candidate_fence IS NULL) AND (candidate_deployment_id IS NULL) = (candidate_since IS NULL));
-- a candidate only ever exists inside a PUBLISH lease, names that lease's deployment, was staged with a token that is not ahead of the counter,
-- and is never the deployment that is already active
ALTER TABLE sites ADD CONSTRAINT sites_candidate_lease_check CHECK (
    candidate_deployment_id IS NULL OR (lease_kind = 'PUBLISH' AND candidate_deployment_id = lease_deployment_id AND candidate_fence <= fence_counter));
ALTER TABLE sites ADD CONSTRAINT sites_candidate_not_active_check CHECK (candidate_deployment_id IS DISTINCT FROM current_deployment_id);

CREATE INDEX sites_candidate_idx ON sites (candidate_deployment_id) WHERE candidate_deployment_id IS NOT NULL;   -- FK lookups, recovery sweep, diagnostics
```
* **Active deployment reference:** unchanged (`sites.current_deployment_id`). No second source of truth.
* **`pointer_version`:** staging or clearing a candidate does **not** change it (the served release did not move); it is the guard of every candidate CAS (so a pointer moved by anyone invalidates the publish) and it moves by one at activation, exactly as in V30.
* **Activation sequence:** unchanged semantics. The candidate CAS applies the V30 order rule (`active_seq < :seq`, or equal for the same operation), so a stale publisher cannot even stage; activation writes `active_seq := :seq`, `active_operation_id := :op` as today.
* **Lease / fence relation:** `candidate_fence` records the token of the holder that staged it. Activation requires `candidate_fence = :fence` AND the V30 lease predicates, so a candidate staged by a holder that was later fenced out (even the same operation, which draws a new token on takeover) can never be activated by anyone but a holder that re-stages it.
* **`deployments`:** no column. (`activation_seq`, `previous_deployment_id`, the status CHECK incl. `ROLLING_BACK` stay as in V30.)
* **Existing V30 rows:** all new columns `NULL`; every constraint is true for them; `ADD COLUMN … NULL` without default is metadata only; `sites` has one row per project, so the constraint and index builds are instantaneous (if C0 prefers: add the CHECKs `NOT VALID` then `VALIDATE`).
* **Undo `U31`** (docs/parallel/c2/undo): refuses while any `candidate_deployment_id` is set or a deployment is `DEPLOYING`; otherwise drops the index, the three constraints and the three columns.

## 3. Statements (all inside `JdbcScopeGuard`; the same `REQUIRES_NEW` discipline as V30)

Stage — the candidate CAS (fails for a stale, fenced-out or overtaken publisher; 0 rows = nothing written):
```sql
UPDATE sites SET candidate_deployment_id = :d, candidate_fence = :fence, candidate_since = now()
 WHERE project_id = :p AND lease_operation_id = :op AND lease_kind = 'PUBLISH' AND lease_deployment_id = :d
   AND lease_fence = :fence AND lease_until > now() AND pointer_version = :v
   AND (active_seq < :seq OR (active_seq = :seq AND active_operation_id = :op))
```
Acquire (V30 statement, one more column): `candidate_deployment_id = CASE WHEN s.lease_operation_id = :op THEN s.candidate_deployment_id END` and the same for `candidate_fence` / `candidate_since` — the same operation resuming keeps its candidate **but only until it re-stages under its new fence**; any other acquirer clears it (I7). Release clears the three columns together with the lease.

Activate — **one statement**, then the status in the same transaction:
```sql
UPDATE sites SET current_deployment_id = candidate_deployment_id, candidate_deployment_id = NULL, candidate_fence = NULL, candidate_since = NULL,
       pointer_version = pointer_version + 1, active_seq = :seq, active_operation_id = :op, updated_at = now()
 WHERE project_id = :p AND lease_operation_id = :op AND lease_fence = :fence AND lease_until > now() AND pointer_version = :v
   AND candidate_deployment_id = :d AND candidate_fence = :fence
   AND (active_seq < :seq OR (active_seq = :seq AND active_operation_id = :op));
-- same transaction (PointerFence.withNextCommit, already in V30): 
UPDATE deployments SET status = 'RUNNING', url = :url, updated_at = now(), finished_at = now() WHERE id = :d AND status = 'DEPLOYING';   -- must be 1 row, else the transaction rolls back
UPDATE projects SET site_visibility = (SELECT visibility FROM deployments WHERE id = :d) WHERE id = :p;                                  -- moves into the activation (today the processor does it afterwards)
```
Clear (failure before activation, rollback, unpublish): `SET candidate_* = NULL` under the same lease predicates; rollback and unpublish fold it into their pointer CAS (I6).

## 4. Flow, failures, and what is served when

`acquire lease → stage candidate (CAS) → verify the artifact (record, checksum, sizes, bytes) → provider stage → server runtime healthy (server apps; moved before activation, as in V30) → probe the CANDIDATE → activate (one transaction) → release`.
| failure | result |
|---|---|
| candidate verification / runtime / probe fails | clear candidate (CAS), undo the server runtime if it moved, `DEPLOYING → FAILED`; **the previous release was never touched and nothing was served** (no `ROLLING_BACK` is needed for this path; the status stays in the CHECK for V30 rows and for any future provider that serves earlier) |
| fenced out / stale / CAS conflict at stage or activation | refused, nothing written; `FAILED / STALE_PUBLISH` (or silent `Lost` when the same operation resumes) |
| crash between stage and activation | the lease expires; the next acquirer clears the candidate (other operation) or the same operation re-stages under a new fence and continues |
| activation statement succeeds but the status update matches 0 rows | the whole transaction rolls back: the pointer did not move |
The candidate is never visible to `SiteService.live`, the gateway or the Data Runtime; `live()` serves `current_deployment_id` only when its deployment is `RUNNING` (I1) — which is true from the instant of activation, so visitors never see `DEPLOYING`.

## 5. Probing the candidate (open point for C0)
HTTP probing a candidate needs a read path for it that visitors do not have. Two options: **(a)** in-process only (artifact bytes + provider checks + runtime health) — no new route, weaker than a real HTTP probe; **(b)** an internal route `/sites/_candidate/{deploymentId}/…` that only accepts a short-lived HMAC token minted and consumed by C2 itself (never handed to a browser), reading `candidate_deployment_id`. I2 says the candidate column is never read by visitor-facing code; (b) is not visitor-facing but is reachable on the same listener, so C0 must say whether it accepts it. C2 recommends (a) until a public host exists to probe, then (b).

## 6. Rollout / compatibility (zero downtime)
1. Apply V31 (additive, nullable): the running V30 code ignores the columns.
2. Deploy the new code. During the switch-over window an old node may still point the site at a `DEPLOYING` deployment (V30 behaviour). Quiesce publishes while rolling (they last seconds), or accept that the recovery sweeper finishes them (`DEPLOYING → RUNNING` under the new code); a site whose pointer names a `DEPLOYING` deployment is **not** served by the new code until it is `RUNNING` — this is the intended I1 and the only visible effect, bounded by the publish time.
3. Rollback of the code: V31 can stay; the old code ignores the columns. `U31` only when no candidate is set.
4. Existing `ROLLING_BACK` / `ROLLED_BACK` / `FAILED` rows keep their meaning; `ROLLBACK_FAILED` / `ROLLBACK_OFFLINE` stay events; rollback and unpublish behave as in V30 plus the candidate clear.

## 7. Tests C2 will deliver with it (real PostgreSQL)
candidate never served at any time (poll `live()` through a publish); stage refused for stale / fenced-out / wrong-version writers; activation is one transaction (pointer + `RUNNING` + visibility, all or nothing; forced failure of the status update leaves the pointer); candidate staged by a fenced-out holder cannot be activated by another; takeover clears it (other operation) and the same operation re-stages; verification failure leaves the active release untouched and ends `FAILED` without `ROLLING_BACK`; rollback / unpublish clear a candidate; schema test (constraints accept the valid shapes and reject the invalid ones, V30 rows pass) and the undo-refusal test; source scan that only `JdbcScopeGuard` writes `candidate_*`.

## 8. Questions for C0
(1) Column set: is `candidate_fence` + `candidate_since` acceptable, or the bare `candidate_deployment_id` of §2.2? (2) §5 (a) vs (b). (3) Confirm `ROLLING_BACK` stays in the status CHECK even though the static path no longer enters it. (4) Number and import order after the C2 Mac sign-off of V30; C2 writes the migration, undo and tests only after the number is given.
