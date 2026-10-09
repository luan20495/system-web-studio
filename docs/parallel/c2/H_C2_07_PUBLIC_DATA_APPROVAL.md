# H-C2-07 — `POST /publish` enforces the persisted public-data approval

Owner C2 · baseline `integration/v2 @ 47883b0` · branch `fix/c2-final-publish-blockers` · 2026-10-09. Code: `PublishController.requirePublicDataApproval`, `ProjectFacts.bindsData`. Tests: `PublicDataApprovalTests` (11).

## Rule (server side, at publish acceptance)

Authority is **`publish_configs.public_data_approved`**, read through `PublishConfigService.get(projectId)`. The browser is not an authority: the approval is not a field of `PublishRequest`, and forged `publicDataApproved` / `acknowledgePublicData` in the JSON are ignored (and asserted not to help).

After `require(PROJECT_PUBLISH)`, the idempotency key, the rate limit, the revision check, the administrator switches and the version lookup — and **before the deployment row, the queue message, the build, the scope lease and the pointer** — with a stored policy:

| Request | Stored policy | Version snapshot (the version that will be deployed) | Result |
|---|---|---|---|
| `PRIVATE` | any | any | unaffected |
| `PUBLIC` | not `PUBLIC` | any | **409 `PUBLISH_POLICY_MISMATCH`** (a request cannot widen the authoritative policy) |
| `PUBLIC` | `PUBLIC` | no `dataBindings` | unaffected |
| `PUBLIC` | `PUBLIC`, `public_data_approved = false` | non-empty `dataBindings` | **422 `PUBLIC_DATA_NOT_APPROVED`**, `details.issues[0] = {field: acknowledgePublicData, code: PUBLIC_DATA_NOT_APPROVED, message}` (same issue shape as the publish-config API) |
| `PUBLIC` | `PUBLIC`, `public_data_approved = true` | non-empty `dataBindings` | accepted (202) |

* **"Binds data"** is the policy's own definition (`ProjectFacts.hasDataBindings`: a non-empty `dataBindings` array). It is now ONE helper, `ProjectFacts.bindsData`, used by the publish-config API (on the draft) and by publish (on the **immutable snapshot** of `versions.latest`, read through `SchemaRepository.version`). A later draft is not consulted: a draft with its bindings removed and no new version cannot dodge the approval of a version that has them (tested).
* **No stored policy** (no `publish_configs` row, or `app.publish-configs.enabled=false`, in which case the service does not exist): unchanged legacy behaviour, the request decides. This is a deliberate compatibility rule. It is not a data leak: the Public Runtime serves no query unless the same row says `public_data_approved = true` (`ReleaseSnapshotPublicQueryAllowList`), so without a stored approval a published page can show only its authored content.
* The approval can only become true through `PUT …/publish-config` / `adopt-draft` with `acknowledgePublicData = true`, by a holder of `PROJECT_PUBLISH` (unchanged). Setting the policy to anything but PUBLIC resets it to false.

## Idempotency (deliberate, tested)

The key is written before the checks (unchanged) and the whole request is one transaction, so **a refusal rolls the key back**: no reservation survives a denied request.
* denied, still unapproved, same key → denied again, deterministically (422);
* denied, approval granted, same key → a first request, accepted once (202), one deployment;
* accepted, then replayed → `Idempotent-Replay: true`, the same deployment (a replay returns what was accepted and does not re-judge it, also after the approval is withdrawn); a **new** key after the withdrawal is refused.

## Not changed
Lease, CAS, fencing, `activation_seq`, `pointer_version`, ROLLING_BACK / ROLLED_BACK, rollback, unpublish, the recovery scheduler, worker retry. A refused publish creates nothing, so release N stays active and rollback behaves exactly as before (tested). The asynchronous worker is not a second authorization layer.

## For C5 (handoff)
* `422 PUBLIC_DATA_NOT_APPROVED` and `409 PUBLISH_POLICY_MISMATCH` are the two new publish answers; both are pre-accept refusals (no deployment id).
* To approve from the Studio: `PUT /api/v1/workspaces/{w}/projects/{p}/publish-config` with `visibility: PUBLIC`, `acknowledgePublicData: true` and the current `expectedRevision` (or `POST …/adopt-draft` with `{"acknowledgePublicData": true}`), then publish. The body of `PUT` needs `requiresAuth` (an existing quirk of the request class: omitting it is `400 MALFORMED_REQUEST`).
* The local checkbox of the dialog is no longer the only guard; it should send the acknowledgement to the publish-config route, not to `/publish`.

## Open (not for C2)
The approval is a Boolean on the policy, not tied to a version: data added to an app after it was approved is covered by the existing approval. If the product wants "approval per version", that is a contract / schema change for C0 (not invented here).
