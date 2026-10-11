> **SUPERSEDED_BY:** `docs/ACTION_WORKFLOW.md` - historical document, kept for auditability (state as of 2026-10-11). It is not current guidance; the canonical description is the document named here.

# C4 — standalone real-Mac readiness (2026-10-06)

> **C0 note (2026-10-06, integration/v2):** the error-code semantics for `TIMEOUT`/`INTERRUPTED` of a *mutating* action in this document are superseded by **D-C4-17** (they become `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable=false`), and UI `onError[]` no longer runs after `IDEMPOTENCY_OUTCOME_UNKNOWN` (**D-C0-13**). Non-mutating actions are unchanged. See `docs/parallel/DECISIONS.md`.

Branch `agent/c4-workflow`. Static audit + runbook, written while C0 validates the C1 security spec. **Gradle, Testcontainers and RabbitMQ have not been run for this branch** (the Linux sandboxes available to C4 have no reachable Maven/Gradle host and no Docker daemon). Nothing below is a PASS claim. The only executed check is the ad-hoc kotlinc harness (see §6), which is not Gradle.

## 1. Toolchain the branch builds with (read from `backend/build.gradle.kts`, unchanged by C4)

| Item | Value |
|---|---|
| Kotlin / plugins | 2.2.21 (`jvm`, `plugin.spring`, `plugin.jpa`), `-Xjsr305=strict`, jvmToolchain 21 |
| Spring Boot | 4.1.1 (`spring-boot-starter-test`, Testcontainers 2.0.5 BOM) |
| Jackson 3 | BOM override `jackson-bom.version = 3.1.7` (package `tools.jackson.*`) |
| Gradle | wrapper 9.8.0, `dependencyLocking { lockAllConfigurations() }`, build cache + parallel on |
| The harness compiler | kotlinc **2.2.21** with `-jvm-target 21` — the same compiler version as the Gradle plugin |

C4 owns no build file and adds no dependency. `logic/**` imports only the JDK, the Kotlin stdlib and three Jackson 3 types: `tools.jackson.databind.JsonNode`, `…json.JsonMapper`, `…node.ObjectNode`. It imports nothing from Spring, JPA, `access`, `tenancy`, `data`, `app`, `common`, `audit`, `runtime`, `integration` or `wiring` (enforced by `ActionContractV2Tests`).

## 2. Real-Mac runbook (run from `backend/`)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # the toolchain is 21
open -a Docker                                         # Testcontainers needs a running daemon (PostgreSQL / RabbitMQ / MinIO base tests)
./gradlew compileKotlin     --no-daemon --rerun-tasks
./gradlew compileTestKotlin --no-daemon --rerun-tasks
./gradlew clean test        --no-daemon --rerun-tasks
```

Run them in this order and stop at the first red one. Attach the full output of the first failure; do not paste credentials or proxy URLs.

### How to classify a failure

| Class | Looks like | Owner / action |
|---|---|---|
| **C4-owned** | an error or a failing test whose file is under `logic/**` | C4 fixes it, nothing else |
| **Dependency error** | `Unresolved reference` / `Cannot access class` for `com.systemwebstudio.{access,tenancy,data,app}.*` or `com.systemwebstudio.wiring.*` | not a C4 defect: the C4 branch predates C1/C2/C3. Do not copy or recreate those classes in `logic/**` |
| **Environment** | `Could not resolve …` / 403 from the Maven or Gradle host, `Could not find a valid Docker environment`, lockfile mismatch | fix the environment; no code change |
| **Base** | a base (pre-parallel) test fails for a reason outside `logic/**` | report to C0, unchanged baseline |

**Expected for `compileKotlin` / `compileTestKotlin`:** no dependency errors at all. `logic/**` has no import of any other agent's package, so standalone it needs nothing from C1/C2/C3. If a *dependency* error does appear, the branch contains a stray import and that is a C4 bug.

**Expected for `clean test`:** the 379 logic tests (JUnit) plus the base suite. The base suite needs Docker; a Docker failure there is *Environment*, not C4.

## 3. Jackson 3 surface used (`tools.jackson.databind`, 3.1.7) — reviewed against the 3.x API, **not compiled against it**

| Call (main) | Real 3.x API | Note |
|---|---|---|
| `JsonMapper.createObjectNode()` / `createArrayNode()` | `ObjectMapper` methods, kept in 3.x | |
| `ObjectNode.put(String, String/Int/Long/Boolean)` returns `ObjectNode` | present | `put("v", s).get("v")` is used to build a `TextNode` |
| `ObjectNode.set<JsonNode>(name, node)` | `<T extends JsonNode> T set(String, JsonNode)` | |
| `JsonNode.get(String)` / `isObject` / `isArray` / `isNull` / `isNumber` / `isBoolean` | unchanged | Kotlin property syntax over `isXxx()` |
| **`isString`, `asString()`** | 3.0 renames of `isTextual` / `asText` | guarded by `isString` at every call site |
| `asBoolean(d)`, `asInt()`, `asLong(d)`, `asDouble()` | present | |
| `propertyNames()` | 3.0 rename of `fieldNames()` | |
| `JsonNode : Iterable<JsonNode>` (`.toList()`) | present | |
| `toString()` on a number node, then `BigDecimal(…)` | JSON text | used by the workflow comparison `number()` |

**Residual risk — shim-only behaviour.** The sandbox Jackson shim has no `NullNode`/`MissingNode` and parses numbers its own way. Code paths whose behaviour depends on the real node classes are: `readTree("null")` in `ActionModelTests` (real: `NullNode`, `isNull == true`); the 9007199254740992/…993 comparison in `WorkflowModelTests` (real: `LongNode`, compared through `BigDecimal(toString())`, so it should hold, but it was only run on the shim); `1.0` (real: `DoubleNode`). If Jackson 3 behaves differently, the failure will show in those tests and is C4-owned.

## 4. Where the harness can pass only because of a shim

1. **JUnit shim has one `assertEquals(Object, Object)`.** Real JUnit has typed overloads. The shim is stricter for mixed boxed types, so a pass on the shim is not a pass that depends on leniency; the open risk is a *Kotlin overload ambiguity* in `compileTestKotlin` (for example `Int` vs `Int?` arguments). No such call was found by inspection, but only the real compile settles it.
2. **No `assertNotNull(x, msg)`** in the shim (a workaround was used), so there is no shim-only pass, only an unused real overload.
3. **Reflection:** tests use `Class.permittedSubclasses` (JDK 17+); it works because Kotlin emits `PermittedSubclasses` at jvmTarget ≥ 17 (the harness uses 21). `kotlin-reflect` is on the real classpath but unused.
4. **Source scan tests** (`ActionContractV2Tests` architecture tests) locate `src/main/kotlin` through `XWEB_BACKEND_SRC` or relative paths; under Gradle the working directory is `backend/` so `src/main/kotlin` resolves. If the directory is not found the test **fails** (it asserts more than 10 sources); it cannot pass silently.
5. **Timing:** the executor/timeout tests use real threads and short deadlines; Gradle runs them sequentially by default. A slow CI box could turn a timing assertion red. That would be flakiness, not a contract change.

## 5. Contract compatibility (C0's `integration/v2` read-only, `f5f4368`)

| Contract | C4 state | Result |
|---|---|---|
| `ActionType` = exactly 9; `RUN_QUERY`, `WRITE_DATA`, `CALL_CONNECTOR_OPERATION`, `SET_VALUE` rejected, no alias map | `CanonicalActionCatalog.actionType(wire)`; tests *the canonical types are exactly the nine…*, *there is no alias map…* | OK |
| One data path `Action → ActionDataPort → adapter/resolver → DataGateway`; no handler touches data directly | `ActionDataPort` is referenced only from `logic.action`; only `ActionPorts.kt`/`ActionHandlers.kt` build `WriteRequest`/`OperationRequest` (architecture test) | OK |
| `tenantId` is server-derived | `ActionContext` is built by C0; `AppDefinitionSource.load(tenantId, appId, mode)` must return null for another tenant; workers rebuild the context from the stored run | OK (adapter is C0's) |
| Default deny | an `AccessPort`/`TenantGate`/limiter/audit that throws or returns null fails closed (tests *an access port that throws fails closed…*, *a tenant gate that throws fails closed…*, *a throwing limiter fails closed…*, *audit failure at STARTED fails closed…*) | OK |
| Derived idempotency key, 43 chars, matches `^[A-Za-z0-9_-]{8,128}$`, forwarded unchanged | `IdempotencyKeys`; `WriteRequest`/`OperationRequest` reject a missing key | OK |
| **409 `IDEMPOTENCY_OUTCOME_UNKNOWN`, 422 `MUTATION_REJECTED`** (`data-runtime.md` §4b) | **were missing in C4; added in this task** — see §7 | FIXED |
| Permission codes `APP_USE, ACTION_EXECUTE, DATA_MUTATE, WORKFLOW_EXECUTE, WORKFLOW_MANAGE` | identical strings to C1's `Permission` enum; pinned by a test | OK |
| `ActorKind` | C4 keeps its own `logic.action.ActorKind { USER, SYSTEM, APP_TOKEN, SERVICE }` (logic may not import `tenancy`); names equal to `tenancy.ActorKind`; pinned by a test | **needs a C0 decision at overlay, see below** |
| Optional `ActionDef.trigger` (D-C4-10) | `ActionDefinition.trigger?`; UI-bound top-level runs require it | OK |
| No runtime ids persisted | C4 never writes to an AppDefinition (`AppDefinitionSource` is read-only); run stores keep the *local* definition ids and the tenant/app/run ids of their own rows; only the derived idempotency key is stored | OK under that reading; tell C4 if "runtime IDs" means something else |
| Feature flags OFF | `logic/**` contains no flag, property or `@ConditionalOn…`; any flag is C0 wiring and must default to OFF | OK (nothing to turn off here) |

### The `ActorKind` overlay point (C0 decision, not changed here)
The wiring skeletons (`C1PortAdapters.kt.skel`, `RequestContexts.kt.skel`) call `ActionActor(me.userId, ActorKind.USER)` with `tenancy.ActorKind`, and `Principal(ctx.actor.kind, …)` with C4's kind. C4's enum is a different Kotlin type, so those two lines cannot compile as written. Two ways, both small:
- **A (no C4 change):** the adapters convert by name — `ActionActor(id, com.systemwebstudio.logic.action.ActorKind.valueOf(kind.name))` and `tenancy.ActorKind.valueOf(ctx.actor.kind.name)`. The names are pinned by `ActorKind keeps the four names…`.
- **B (single type):** at overlay C4 deletes its enum and imports `tenancy.ActorKind` in `ActionModel.kt`, `WorkflowEngine.kt` and `ActionFakes.kt`, and the architecture test allows exactly `com.systemwebstudio.tenancy.ActorKind`. That reverses "logic imports nothing from tenancy", so it needs a DECISIONS entry first.
C4 recommends **A**.

## 6. Harness result (kotlinc 2.2.21 + shims; **not Gradle**)

`379 / 379` pass (371 before this task + 6 mutation-code tests + 2 name-pin tests). Mutation checks for the new guards: removing the `onError` guard fails *an unknown write outcome fails the run…*; removing the retry-flag normalisation fails the handler and runtime tests. The two architecture tests fail only inside the mutation work directory, because that copy lives outside the scanned path.

## 7. Changes made in this task (C4-owned files only)

- `ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN` (409), `MUTATION_REJECTED` (422), `isNeverRetryable`, `isOutcomeUnknown`.
- `PortOutcome.Failure.toResult()` never returns `retryable = true` for those two codes, whatever an adapter says.
- `WorkflowEngine`: a step failing with `IDEMPOTENCY_OUTCOME_UNKNOWN` fails the run with that code — it is not routed to `onError`, not retried, and (as before) a failed step is never in `compensable`, so no compensation assumes "not applied". Earlier finished steps are still compensated. `MUTATION_REJECTED` is a definite failure: it may use `onError`, is never retried.
- Tests: 6 for the codes, 2 name pins.

### Still open for C0 at overlay (not C4 code)
- `ActionDataPortAdapter` must map C3 failures as in `data-runtime.md` §4b / the skeleton: `IDEMPOTENCY_OUTCOME_UNKNOWN` and every ambiguous transport error of a **write** become `Failure("IDEMPOTENCY_OUTCOME_UNKNOWN", retryable = false)`; `MUTATION_REJECTED` becomes `Failure("MUTATION_REJECTED", false)`; `IDEMPOTENCY_IN_PROGRESS` and `RATE_LIMITED` are retryable. C4 only guarantees the two codes cannot be made retryable; it does not know C3's other codes.
- UI `onError[]` chains (`ActionDefinition.onError`) still run after `IDEMPOTENCY_OUTCOME_UNKNOWN`. They are client-style follow-ups (navigate, notify) and are not a compensation; if C0 wants them suppressed too, that is a small change in `ActionRuntime` plus a DECISIONS entry (not made here).
- Migration numbers: none requested beyond the consolidated request already in `BOARD.md`; none allocated.
