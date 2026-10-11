# FQ-ACT-02 — UPDATE_RECORD / DELETE_RECORD and the key column (C4, 2026-10-10)

Base `integration/v2` `fad4a7b`. Fix commit on `fix/c4-fq-act-02`. No migration, no change of a shared contract value.

## 1. Defect and exact reproduction
Action input (C4 handler contract, unchanged): `recordId` = the logical identifier of the row. A table `shop.items(id uuid PRIMARY KEY, status text)` and the approved mutation
`items.update` = `{target: "shop.items key=id", params: [id STRING, status STRING]}` (the key column is `id`).

| | before the fix |
|---|---|
| action | `POST .../app-runtime/actions/update-item/execute` `{"inputs":{"recordId":"<uuid>","status":"shipped"}}` |
| parameters given to the gateway | `{recordId, status}` |
| gateway | `QueryParams.bind(def.params = [id, status], given)` → `unknown parameter` → `INVALID_PARAMS`, no SQL was generated |
| answer | HTTP 500 `{"error":{"code":"INVALID_PARAMS","message":"The change was not applied.","retryable":false}}` (nothing applied) |
| the only way to make it work | declare a parameter named `recordId` AND have a physical column called `"recordId"` (`key=recordId` → `WHERE "recordId" = ?`) |

After the fix the same request gives HTTP 200 `{"status":"OK","output":{"operation":"items.update","kind":"UPDATE","affected":1,...,"output":{"id":"…","status":"shipped"}}}` and the statement
`UPDATE "shop"."items" SET "status" = ? WHERE "id" = ?` with the bound values `[shipped, <uuid>]`.

## 2. Root cause
`recordId` was forwarded unchanged all the way to the connector, where "a parameter's NAME is its column name" (`PostgresMutationExecutor`, `PgMutationSql`). There was no mapping from the
logical identifier to the key of the mutation.

## 3. Contract reused (nothing new invented)
The approved mutation already names its key: `target := [schema.]table key=p1[,p2…] [returning=…]` (B-C0-W-04, validated by the connector when the definition is created and again at
execution). The key is therefore ALREADY approved metadata. The fix only reads it:
- `MutationExecutor.recordKey(def, ds): String?` (new SPI method, default `null`): the single key of an UPDATE / DELETE as the connector reads it. PostgreSQL: `PgMutationTarget.parse(...).keys.singleOrNull()`; invalid / several / none = `null`.
- `RecordKey.resolve(def, keyParam, given)` (new, pure) maps `recordId` → that parameter, called by `DefaultDataGateway.mutate` before `QueryParams.bind`; `DataSourceService.recordKey` asks the connector of the (tenant-scoped, already resolved) data source.

Rules: only UPDATE / DELETE; only when `recordId` is given; only when the definition does not declare its own `recordId` (every existing workaround keeps working unchanged); only when exactly one key is
reported and it is a declared parameter; `recordId` together with the key itself is refused (`INVALID_PARAMS`, ambiguous); an INTEGER key accepts a whole-number text (`"42"`). Otherwise nothing changes
and the usual `INVALID_PARAMS` (definite: nothing executed, the idempotency key is released) answers. CREATE / SUBMIT never alias.

## 4. Security
The physical column never comes from the request: it comes from the approved definition, passes `PgMutationTarget` (identifier pattern, configured schemas only) and is quoted; every value is a bound
parameter. Authorization is untouched and still happens first (`ACTION_EXECUTE`, `DATA_MUTATE` in C4; `GatewayGuard` MUTATION_EXECUTE in the gateway); tenancy and project/workspace isolation are untouched.
Idempotency is untouched (derived key; an ambiguous timeout leaves the key UNKNOWN: `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`, the statement reached the database once; no compensation of that step).

## 5. What QA / operators must configure (Journey 05 / 06 fixtures)
Define the UPDATE / DELETE mutation with the real key column: `target: "<schema>.<table> key=<keyColumn>"` and a declared parameter named `<keyColumn>` (type STRING for uuid / text keys, INTEGER for bigint). The action keeps
its input `recordId`. A table column literally named `recordId` is no longer needed (and still works). Management API document: `{"target":"shop.orders key=id","params":[{"name":"id","type":"STRING"},{"name":"status","type":"STRING","required":false}]}`.
The key column name must match `[a-z][A-Za-z0-9_]{0,39}` (the existing parameter-name rule): a column such as `OrderId` cannot be a key parameter (pre-existing limit, not changed here).

## 6. Tests (JDK 21)
- `RecordKeyGatewayTests` 14/14: the real `DefaultDataGateway` + `PostgresConnector` over a recording fake database (SQL text and bound values): update / delete, four key column names, row missing (0 rows), own `recordId` kept, INTEGER key, missing / undeclared / two keys / unsafe / unknown schema metadata (nothing executed, key released), unknown column (definite rejection), no key from the request (twice, CREATE, SUBMIT), hostile values and names, authorization / other tenant, ambiguous timeout and commit loss for UPDATE and DELETE (UNKNOWN, no second statement), duplicate key / conflict, connector key reading, pure `RecordKey`.
- `RecordKeyActionE2ETests` 7/7: HTTP → C1 → C4 → C0 adapter → gateway → real PostgreSQL 17.6, ordinary tables (`items(id uuid)`, a legacy `"recordId"` table, `numbered(no bigint)`): update / delete × exists / missing, malformed key, authorization (no ACTION_EXECUTE, no DATA_MUTATE, member without project right, other tenant, other project), key metadata, injection, duplicate key, ambiguous `pg_sleep` timeout (UNKNOWN, `retryable=false`, same key stays UNKNOWN, one statement reached the database).
- Before the fix (the gateway line reverted): `RecordKeyGatewayTests` 8/14 and `RecordKeyActionE2ETests` 5/7 fail, the update returns HTTP 500 `INVALID_PARAMS`. After: all pass.
- Regression (data.*, DataRuntimeLive, DataWritableE2E, DataManagement*, AppRuntimeApi, ActionDataPortAdapter, PublicData, logic.*): 1099 tests, 1098 pass; the one failure, `OrganizationIntegrationWiringTests` "non-structural operations never wait" (a wall-clock bound, 5.5 s vs 2.5 s on a machine at load average 30), passes alone 9/9.

## 7. Not changed, to be decided by the owners
- `INVALID_PARAMS` (a definite client/definition error: nothing executed) is answered with HTTP 500 by `RuntimeResponses` (C0). 422 would be right. Pre-existing, affects every INVALID_PARAMS.
- UPDATE / DELETE of a row that does not exist succeed with `affected: 0` (existing connector semantics; the action cannot tell "no such row" from "changed"). A "not found" answer would be a separate, deliberate contract change.
- Proposed decision D-C4-23 (C0 merges into `DECISIONS.md`): the logical `recordId` of UPDATE_RECORD / DELETE_RECORD is resolved by the data layer to the single `key=` parameter of the approved mutation.
