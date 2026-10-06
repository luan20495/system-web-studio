package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.str
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Canonical v2 contract (docs/contracts/v2/action-workflow.md + data-runtime.md §4): idempotency derivation, the single data path, the permission
 * vocabulary and trigger optionality. Pure unit tests; none of them is a Gradle run (see BASELINE.md).
 */
class ActionContractV2Tests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    private fun failure(r: ActionResult) = assertInstanceOf(ActionResult.Failed::class.java, r)
    private fun req(action: String = "m1", key: String? = "client-key-1", title: String = "t", mode: ExecutionMode = ExecutionMode.LIVE, trigger: TriggerInfo = TriggerInfo(TriggerKind.UI_EVENT), depth: Int = 0) =
        ActionRequest(action, mapOf("title" to str(title)), idempotencyKey = key, mode = mode, trigger = trigger, callDepth = depth)

    // ---- the derivation ---------------------------------------------------------------------------------------------

    @Test fun `derived key is base64url sha256, 43 chars, accepted by the C3 pattern`() {
        val k = IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "client-key-1")
        assertEquals(43, k.length)
        assertTrue(IdempotencyKeys.PORT_KEY.matches(k))
        assertFalse(k.contains("=") || k.contains("+") || k.contains("/"))
        // the documented formula, computed independently
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("${Fx.tenantA}|${Fx.appA}|${Fx.user}|m1|client-key-1".toByteArray())
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest), k)
    }

    @Test fun `derivation is stable and every canonical input changes it`() {
        val base = IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "k")
        assertEquals(base, IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "k"))
        val others = listOf(
            IdempotencyKeys.derive(Fx.tenantB, Fx.appA, Fx.user, "m1", "k"), IdempotencyKeys.derive(Fx.tenantA, Fx.appB, Fx.user, "m1", "k"),
            IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user2, "m1", "k"), IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m2", "k"),
            IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "k2")
        )
        assertEquals(others.size, others.toSet().size)
        assertFalse(base in others)
    }

    @Test fun `a component cannot smuggle the separator into the digest`() {
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1|x", "k") }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "a|b") }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "") }
    }

    @Test fun `redact never contains the key and is not reversible by length`() {
        val k = "secret-client-key-123"
        val r = IdempotencyKeys.redact(k)
        assertFalse(r.contains(k) || r.contains("secret"))
        assertEquals("-", IdempotencyKeys.redact(null))
        assertEquals(r, IdempotencyKeys.redact(k))
    }

    // ---- the port request -------------------------------------------------------------------------------------------

    @Test fun `a data request cannot be built with a raw client key`() {
        val derived = IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "k")
        WriteRequest(Fx.appA, ExecutionMode.LIVE, "q", WriteKind.CREATE, emptyMap(), derived)
        for (raw in listOf("k", "client-key-1", "evt:1:r", "has.dot.and:colon-12345678", "short", "", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "=")) {
            assertThrows(IllegalArgumentException::class.java) { WriteRequest(Fx.appA, ExecutionMode.LIVE, "q", WriteKind.CREATE, emptyMap(), raw) }
            assertThrows(IllegalArgumentException::class.java) { OperationRequest(Fx.appA, ExecutionMode.LIVE, "ds", "op", emptyMap(), raw) }
        }
    }

    @Test fun `a request prints neither its key nor its values`() {
        val derived = IdempotencyKeys.derive(Fx.tenantA, Fx.appA, Fx.user, "m1", "k")
        val text = WriteRequest(Fx.appA, ExecutionMode.LIVE, "q", WriteKind.CREATE, mapOf("ssn" to str("123-45-6789")), derived).toString()
        assertFalse(text.contains(derived)); assertFalse(text.contains("123-45-6789")); assertTrue(text.contains("ssn"))
    }

    @Test fun `the raw client key reaches no port, no audit entry and no stored record`() {
        val raw = "RAW-client-key-xyz"
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), Fx.notify(), Fx.callApi(), Fx.startWorkflow(), executor = executor)
        r.runtime.execute(Fx.ctx(), req("m1", raw))
        r.runtime.execute(Fx.ctx(), ActionRequest("n1", mapOf("name" to str("A")), idempotencyKey = raw))
        r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("a")), idempotencyKey = raw))
        r.runtime.execute(Fx.ctx(), ActionRequest("sw", mapOf("who" to str("a")), idempotencyKey = raw))
        val seen = r.data.writes.map { it.second.idempotencyKey } + r.data.operations.map { it.second.idempotencyKey } +
            r.notify.sent.map { it.idempotencyKey } + r.workflow.started.map { it.idempotencyKey }
        assertEquals(4, seen.size)
        assertTrue(seen.none { it == raw || it?.contains("RAW") == true }, "raw key leaked: $seen")
        assertEquals(Fx.dk("m1", raw), r.data.writes.single().second.idempotencyKey)
        assertEquals(Fx.dk("call", raw), r.data.operations.single().second.idempotencyKey)
        assertEquals(Fx.dk("n1", raw), r.notify.sent.single().idempotencyKey)
        assertEquals(Fx.dk("sw", raw), r.workflow.started.single().idempotencyKey)
        // audit entries have no key field at all; their text form must not carry it either
        assertTrue(r.audit.entries.none { it.toString().contains(raw) })
        // the run store is keyed by the derived key only
        assertEquals(RunStatus.SUCCEEDED, r.runs.find(RunKey(Fx.tenantA, Fx.appA, "m1", Fx.user, Fx.dk("m1", raw)))!!.status)
        assertEquals(null, r.runs.find(RunKey(Fx.tenantA, Fx.appA, "m1", Fx.user, raw)))
    }

    @Test fun `a retry reuses the same derived key and the same user gets one write`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor)
        r.runtime.execute(Fx.ctx(), req(key = "same"))
        r.runtime.execute(Fx.ctx(), req(key = "same"))
        assertEquals(1, r.data.writes.size)
        // a retry after a retryable failure goes downstream again with the very same derived key
        val r2 = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor)
        r2.data.outcome = PortOutcome.Failure("QUERY_TIMEOUT", retryable = true)
        failure(r2.runtime.execute(Fx.ctx(), req(key = "again")))
        r2.data.outcome = PortOutcome.Success(json.createObjectNode())
        assertInstanceOf(ActionResult.Ok::class.java, r2.runtime.execute(Fx.ctx(), req(key = "again")))
        assertEquals(2, r2.data.writes.size)
        assertEquals(1, r2.data.writes.map { it.second.idempotencyKey }.toSet().size)
    }

    @Test fun `two users or two tenants with the same client key get different downstream keys`() {
        val a = Fx.mutation(ActionType.CREATE_RECORD)
        val r = ActionRig.build(a, executor = executor)
        r.runtime.execute(Fx.ctx(userId = Fx.user), req(key = "shared")); r.runtime.execute(Fx.ctx(userId = Fx.user2), req(key = "shared"))
        val keys = r.data.writes.map { it.second.idempotencyKey }
        assertEquals(2, keys.size); assertNotEquals(keys[0], keys[1])
    }

    @Test fun `without a client key a run still carries a well formed but one shot key`() {
        val d = Fx.mutation(ActionType.CREATE_RECORD).copy(idempotency = IdempotencyPolicy.OPTIONAL)
        val r = ActionRig.build(d, executor = executor)
        r.runtime.execute(Fx.ctx(), req(key = null)); r.runtime.execute(Fx.ctx(), req(key = null))
        val keys = r.data.writes.map { it.second.idempotencyKey }
        assertEquals(2, keys.size); assertNotEquals(keys[0], keys[1])                   // nothing is de-duplicated, and it does not pretend to be
        assertTrue(keys.all { IdempotencyKeys.PORT_KEY.matches(it) })
        assertEquals(0, r.runs.size())                                                  // no run record without a client key
    }

    @Test fun `NONE on a mutating action is an invalid definition, so a raw key is never silently dropped`() {
        val d = Fx.mutation(ActionType.CREATE_RECORD).copy(idempotency = IdempotencyPolicy.NONE)
        val r = ActionRig.build(d, executor = executor)
        assertEquals(ActionErrorCodes.INVALID_DEFINITION, failure(r.runtime.execute(Fx.ctx(), req(key = "ignored"))).code)
        assertTrue(r.data.writes.isEmpty())
    }

    // ---- LIVE / TEST and the data port ---------------------------------------------------------------------------------

    @Test fun `the data port receives app and mode`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), Fx.callApi(), executor = executor)
        r.runtime.execute(Fx.ctx(), req())
        r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("a")), idempotencyKey = "k-call"))
        assertEquals(Fx.appA, r.data.writes.single().second.appId); assertEquals(ExecutionMode.LIVE, r.data.writes.single().second.mode)
        assertEquals(Fx.appA, r.data.operations.single().second.appId); assertEquals(ExecutionMode.LIVE, r.data.operations.single().second.mode)
    }

    @Test fun `TEST mode dry-runs with mode TEST, a derived key, and never writes`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor)
        val res = r.runtime.execute(Fx.ctx(), req(key = "raw-test-key", mode = ExecutionMode.TEST))
        val w = assertInstanceOf(ActionResult.WouldRun::class.java, res)
        assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
        assertTrue(r.data.writes.isEmpty() && r.data.operations.isEmpty())
        assertEquals(listOf("write"), r.data.dryRuns)
        assertEquals(0, r.runs.size())
    }

    @Test fun `TEST dry-run request carries mode TEST and a derived key`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<WriteRequest>()
        val data = object : ActionDataPort {
            override fun write(ctx: ActionContext, request: WriteRequest) = PortOutcome.Failure("NO", false)
            override fun callOperation(ctx: ActionContext, request: OperationRequest) = PortOutcome.Failure("NO", false)
            override fun dryRunWrite(ctx: ActionContext, request: WriteRequest): DryRunOutcome { seen += request; return DryRunOutcome.Validated(json.createObjectNode()) }
        }
        val reg = com.systemwebstudio.logic.action.handlers.DefaultActionHandlers.registry(json, ActionPorts(data))
        val rt = DefaultActionRuntime(
            FakeDefinitions(Fx.mutation(ActionType.CREATE_RECORD).copy(trigger = Fx.uiTrigger)), reg, FakeAccess(), FakeTenants(), InMemoryActionRunStore(), RecordingAudit(),
            InputResolver(json), clock = Fx.clock, executor = executor
        )
        val w = assertInstanceOf(ActionResult.WouldRun::class.java, rt.execute(Fx.ctx(), req(key = "raw-test-key", mode = ExecutionMode.TEST)))
        assertEquals(DryRunLevel.VALIDATED, w.level)
        val r = seen.single()
        assertEquals(ExecutionMode.TEST, r.mode); assertEquals(Fx.appA, r.appId)
        assertEquals(Fx.dk("m1", "raw-test-key"), r.idempotencyKey)
    }

    @Test fun `a connector without dry run is reported unsupported and success is never simulated`() {
        val r = ActionRig.build(Fx.callApi(), Fx.mutation(ActionType.UPDATE_RECORD, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true), InputSpec("title", InputType.STRING))), executor = executor)
        val w1 = assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("a")), mode = ExecutionMode.TEST)))
        val w2 = assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("1")), mode = ExecutionMode.TEST)))
        for (w in listOf(w1, w2)) {
            assertEquals(DryRunLevel.NOT_EXECUTED, w.level)
            assertTrue(w.output == null, "a result must not be invented")
            assertTrue(w.reason!!.contains("no dry-run") || w.reason!!.contains("does not support"), w.reason)
        }
        assertTrue(r.data.writes.isEmpty() && r.data.operations.isEmpty())
    }

    @Test fun `TEST mode sends no notification and starts no workflow even with a key`() {
        val r = ActionRig.build(Fx.notify(), Fx.startWorkflow(), executor = executor)
        assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("n1", mapOf("name" to str("A")), idempotencyKey = "k", mode = ExecutionMode.TEST)))
        assertInstanceOf(ActionResult.WouldRun::class.java, r.runtime.execute(Fx.ctx(), ActionRequest("sw", mapOf("who" to str("A")), idempotencyKey = "k", mode = ExecutionMode.TEST)))
        assertTrue(r.notify.sent.isEmpty() && r.workflow.started.isEmpty())
    }

    // ---- authorization (fail closed) ---------------------------------------------------------------------------------------

    @Test fun `write types need DATA_MUTATE in addition to ACTION_EXECUTE and the others do not`() {
        for (t in listOf(ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD, ActionType.SUBMIT_FORM)) {
            val r = ActionRig.build(Fx.mutation(t, inputs = listOf(InputSpec("recordId", InputType.STRING, required = true))), access = FakeAccess(denyPermissions = setOf(LogicPermissions.DATA_MUTATE)), executor = executor)
            val f = failure(r.runtime.execute(Fx.ctx(), ActionRequest("m1", mapOf("recordId" to str("1")), idempotencyKey = "k-$t")))
            assertEquals(ActionErrorCodes.FORBIDDEN, f.code, "$t"); assertTrue(r.data.writes.isEmpty())
        }
        val call = ActionRig.build(Fx.callApi(), access = FakeAccess(denyPermissions = setOf(LogicPermissions.DATA_MUTATE)), executor = executor)
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(call.runtime.execute(Fx.ctx(), ActionRequest("call", mapOf("q" to str("a")), idempotencyKey = "k"))).code)
        val other = ActionRig.build(Fx.navigate(), Fx.notify(), access = FakeAccess(denyPermissions = setOf(LogicPermissions.DATA_MUTATE)), executor = executor)
        assertInstanceOf(ActionResult.Ok::class.java, other.runtime.execute(Fx.ctx(), ActionRequest("go-home")))
        assertInstanceOf(ActionResult.Ok::class.java, other.runtime.execute(Fx.ctx(), ActionRequest("n1", mapOf("name" to str("A")), idempotencyKey = "k")))
    }

    @Test fun `a missing or throwing authorizer denies and nothing reaches a port`() {
        val deny = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(denyAll = true), executor = executor)
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(deny.runtime.execute(Fx.ctx(), req())).code)
        val boom = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(throwing = true), executor = executor)
        val f = failure(boom.runtime.execute(Fx.ctx(), req())); assertEquals(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, f.code); assertTrue(f.retryable)
        assertTrue(deny.data.writes.isEmpty() && boom.data.writes.isEmpty() && deny.runs.size() == 0 && boom.runs.size() == 0)
    }

    @Test fun `TEST mode needs the same permissions as LIVE`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), access = FakeAccess(denyPermissions = setOf(LogicPermissions.DATA_MUTATE)), executor = executor)
        assertEquals(ActionErrorCodes.FORBIDDEN, failure(r.runtime.execute(Fx.ctx(), req(mode = ExecutionMode.TEST))).code)
        assertTrue(r.data.dryRuns.isEmpty())
        assertTrue(r.access.requests.all { it.second.mode == ExecutionMode.TEST })
    }

    // ---- trigger optionality ---------------------------------------------------------------------------------------------

    @Test fun `a UI-bound run of an action without a trigger is unknown`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), bindTriggers = false, executor = executor)
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(r.runtime.execute(Fx.ctx(), req())).code)
        assertTrue(r.access.requests.isEmpty() && r.data.writes.isEmpty())
    }

    @Test fun `workflow steps, schedules and chains do not need a trigger`() {
        val d = Fx.mutation(ActionType.CREATE_RECORD)
        val r = ActionRig.build(d, bindTriggers = false, executor = executor)
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req(key = "k1", trigger = TriggerInfo(TriggerKind.WORKFLOW_STEP, "s1"))))
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req(key = "k2", trigger = TriggerInfo(TriggerKind.SCHEDULE))))
        // a chained child inherits the UI trigger kind but runs at depth + 1, so it is not "UI-bound"
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req(key = "k3", depth = 1)))
        assertEquals(3, r.data.writes.size)
    }

    @Test fun `a UI event that does not match the declared trigger is unknown`() {
        val r = ActionRig.build(Fx.mutation(ActionType.CREATE_RECORD), executor = executor)
        val wrong = TriggerInfo(TriggerKind.UI_EVENT, "other.onClick", "e1")
        assertEquals(ActionErrorCodes.UNKNOWN_ACTION, failure(r.runtime.execute(Fx.ctx(), req(trigger = wrong))).code)
        val right = TriggerInfo(TriggerKind.UI_EVENT, "btn.onClick", "e1")
        assertInstanceOf(ActionResult.Ok::class.java, r.runtime.execute(Fx.ctx(), req(trigger = right)))
    }

    // ---- names that other agents' code is compiled against (pinned so drift is a test failure, not a wiring surprise) ----------------

    @Test fun `ActorKind keeps the four names of tenancy ActorKind so the C0 adapter can convert by name`() {
        assertEquals(listOf("USER", "SYSTEM", "APP_TOKEN", "SERVICE"), ActorKind.entries.map { it.name })
    }

    @Test fun `permission codes are the five canonical codes of tenant-permission 5`() {
        assertEquals(
            listOf("APP_USE", "ACTION_EXECUTE", "DATA_MUTATE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE"),
            listOf(LogicPermissions.APP_USE, LogicPermissions.ACTION_EXECUTE, LogicPermissions.DATA_MUTATE, LogicPermissions.WORKFLOW_EXECUTE, LogicPermissions.WORKFLOW_MANAGE)
        )
    }

    // ---- the data path is the only one -------------------------------------------------------------------------------------

    private fun logicSources(): List<Path> {
        val candidates = listOfNotNull(System.getenv("XWEB_BACKEND_SRC"), "src/main/kotlin", "backend/src/main/kotlin", "../backend/src/main/kotlin")
        val root = candidates.map { Paths.get(it).resolve("com/systemwebstudio/logic") }.firstOrNull { Files.isDirectory(it) }
            ?: return emptyList()
        return Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
    }

    @Test fun `logic code imports no repository, connector, JDBC, HTTP client, framework or other agent package`() {
        val sources = logicSources()
        assertTrue(sources.size > 10, "could not locate the logic sources (set XWEB_BACKEND_SRC)")
        val forbidden = listOf(
            "java.sql.", "javax.sql.", "jakarta.persistence", "org.springframework", "org.hibernate", "com.rabbitmq", "org.springframework.amqp",
            "java.net.http", "java.net.URL", "java.net.HttpURLConnection", "java.net.Socket", "okhttp3", "org.apache.http", "io.lettuce", "redis.",
            "com.systemwebstudio.data", "com.systemwebstudio.access", "com.systemwebstudio.tenancy", "com.systemwebstudio.app.", "com.systemwebstudio.common",
            "com.systemwebstudio.audit", "com.systemwebstudio.runtime", "com.systemwebstudio.integration", "com.systemwebstudio.wiring"
        )
        val offenders = sources.flatMap { f ->
            Files.readAllLines(f).filter { it.trimStart().startsWith("import ") }.filter { line -> forbidden.any { line.contains(it) } }.map { "${f.fileName}: $it" }
        }
        assertTrue(offenders.isEmpty(), "forbidden imports in logic.*: $offenders")
    }

    @Test fun `logic sources use the Jackson 3 form of ObjectNode set and never the Jackson 2 generic one`() {
        val sources = logicSources()
        assertTrue(sources.size > 10)
        val offenders = sources.filter { f -> Files.readAllLines(f).any { Regex("""\bset<""").containsMatchIn(it) } }.map { it.fileName.toString() }
        assertTrue(offenders.isEmpty(), "set<T>(…) is Jackson 2; Jackson 3's ObjectNode.set is not generic: $offenders")
    }

    @Test fun `only the data port and the handlers' package talk to data, and handlers use nothing else for it`() {
        val sources = logicSources()
        assertTrue(sources.size > 10)
        // ActionDataPort may be referenced only by action/ (port, handlers, registry wiring) - never by workflow/approval/scheduler/notification
        val users = sources.filter { f -> Files.readAllLines(f).any { it.contains("ActionDataPort") } }.map { it.toString().substringAfter("logic/") }
        assertTrue(users.all { it.startsWith("action/") }, "ActionDataPort referenced outside logic.action: $users")
        // inside action/, only the port declaration and the handlers file build data requests
        val builders = sources.filter { f -> Files.readAllLines(f).any { it.contains("WriteRequest(") || it.contains("OperationRequest(") } }
            .map { it.fileName.toString() }.toSet()
        assertEquals(setOf("ActionPorts.kt", "ActionHandlers.kt"), builders)
    }

    @Test fun `no logic source accesses a data store or connector by name`() {
        val sources = logicSources()
        val banned = listOf("DataGateway", "JdbcTemplate", "DataSource.getConnection", "Connector", "Repository", "EntityManager")
        val offenders = sources.flatMap { f ->
            Files.readAllLines(f).filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
                .filter { line -> banned.any { line.contains(it) } }.map { "${f.fileName}: ${it.trim().take(100)}" }
        }
        assertTrue(offenders.isEmpty(), "$offenders")
    }
}
