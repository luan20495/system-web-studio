package com.systemwebstudio.logic.action

import com.systemwebstudio.logic.action.Fx.json
import com.systemwebstudio.logic.action.Fx.num
import com.systemwebstudio.logic.action.Fx.str
import com.systemwebstudio.logic.action.handlers.MutationActionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ActionInputBinderTests {
    private val limits = ActionLimits()
    private val def = Fx.navigate(inputs = listOf(
        InputSpec("name", InputType.STRING, required = true, maxLength = 5),
        InputSpec("age", InputType.NUMBER),
        InputSpec("flag", InputType.BOOLEAN),
        InputSpec("obj", InputType.OBJECT),
        InputSpec("arr", InputType.ARRAY),
        InputSpec("any")
    ))

    private fun rejected(raw: Map<String, JsonNode>, l: ActionLimits = limits) =
        assertInstanceOf(BindResult.Rejected::class.java, ActionInputBinder.bind(def, raw, l)).failure

    @Test fun `binds valid input and drops explicit nulls`() {
        val raw = mapOf("name" to str("abc"), "age" to num(3), "any" to json.readTree("null"))
        val bound = assertInstanceOf(BindResult.Bound::class.java, ActionInputBinder.bind(def, raw, limits)).input
        assertEquals(setOf("name", "age"), bound.names)
        assertEquals("abc", bound.string("name"))
    }

    @Test fun `missing required input is reported by name`() {
        val f = rejected(emptyMap())
        assertEquals(ActionErrorCodes.INVALID_INPUT, f.code)
        assertEquals("is required", f.details["name"])
        assertFalse(f.retryable)
    }

    @Test fun `undeclared input is rejected`() {
        val f = rejected(mapOf("name" to str("abc"), "sql" to str("drop table x")))
        assertEquals("not a declared input", f.details["sql"])
    }

    @Test fun `wrong json types and max length are rejected`() {
        val f = rejected(mapOf("name" to str("toolong"), "age" to str("x"), "flag" to num(1), "obj" to str("o"), "arr" to str("a")))
        assertEquals(setOf("name", "age", "flag", "obj", "arr"), f.details.keys)
    }

    @Test fun `oversized input hits the byte limit before any field is looked at`() {
        val f = rejected(mapOf("name" to str("a".repeat(2000))), ActionLimits(maxInputBytes = 1000))
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, f.code)
    }

    @Test fun `deep nesting hits the depth limit`() {
        var node: JsonNode = str("leaf")
        repeat(20) { node = json.createObjectNode().apply { set<JsonNode>("n", node) } }
        val f = rejected(mapOf("name" to str("a"), "obj" to node), ActionLimits(maxInputDepth = 5))
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, f.code)
    }

    @Test fun `fingerprint ignores key order but not values or action`() {
        val a = ActionInput(linkedMapOf("x" to str("1"), "y" to str("2")))
        val b = ActionInput(linkedMapOf("y" to str("2"), "x" to str("1")))
        val c = ActionInput(linkedMapOf("x" to str("1"), "y" to str("3")))
        assertEquals(ActionInputBinder.fingerprint("a1", a), ActionInputBinder.fingerprint("a1", b))
        assertNotEquals(ActionInputBinder.fingerprint("a1", a), ActionInputBinder.fingerprint("a1", c))
        assertNotEquals(ActionInputBinder.fingerprint("a1", a), ActionInputBinder.fingerprint("a2", a))
    }
}

class ActionDefinitionValidatorTests {
    private fun issues(d: ActionDefinition, h: ActionHandler? = null) = ActionDefinitionValidator.validate(d, h).map { it.path }

    @Test fun `a plain definition is valid`() = assertEquals(emptyList<String>(), issues(Fx.navigate()))

    @Test fun `ids must be plain references`() {
        assertEquals(listOf("id"), issues(Fx.navigate(id = "https://evil.example/x")))
        assertEquals(listOf("id"), issues(Fx.navigate(id = "")))
    }

    @Test fun `config may not carry code sql or urls at any depth`() {
        val nested = json.createObjectNode().apply { set<JsonNode>("inner", json.createObjectNode().put("Script", "alert(1)")) }
        val d = Fx.navigate().copy(config = Fx.cfg("pageId" to "home", "sql" to "select 1") + mapOf("opts" to nested))
        assertEquals(setOf("config.sql", "config.opts.inner.Script"), issues(d).toSet())
    }

    @Test fun `duplicate and malformed input names are reported`() {
        val d = Fx.navigate(inputs = listOf(InputSpec("a"), InputSpec("a"), InputSpec("bad name")))
        assertTrue(issues(d).containsAll(listOf("inputs[1].name", "inputs[2].name")))
    }

    @Test fun `maxLength only on strings`() {
        assertEquals(listOf("inputs[0].maxLength"), issues(Fx.navigate(inputs = listOf(InputSpec("n", InputType.NUMBER, maxLength = 3)))))
    }

    @Test fun `handler specific rules are applied and a mismatching handler is reported`() {
        val data = FakeDataPort()
        val missing = Fx.mutation(ActionType.UPDATE_RECORD, inputs = emptyList())
        assertEquals(listOf("inputs.recordId"), issues(missing, MutationActionHandler(ActionType.UPDATE_RECORD, data)))
        assertEquals(listOf("type"), issues(Fx.navigate(), MutationActionHandler(ActionType.CREATE_RECORD, data)))
    }
}

class ActionRunStoreTests {
    private val store = InMemoryActionRunStore()
    private val key = RunKey(Fx.tenantA, "a1", "k1")

    @Test fun `first begin starts, same input while running is in progress, different input is key reuse`() {
        val s = assertInstanceOf(RunBegin.Started::class.java, store.begin(key, "f1", Fx.now))
        assertEquals(1, s.attempt)
        assertInstanceOf(RunBegin.InProgress::class.java, store.begin(key, "f1", Fx.now))
        assertEquals(RunBegin.KeyReused, store.begin(key, "other", Fx.now))
    }

    @Test fun `success is replayed`() {
        val s = store.begin(key, "f1", Fx.now) as RunBegin.Started
        val ok = ActionResult.Ok(str("done"))
        assertTrue(store.complete(key, s.runId, ok, Fx.now))
        assertEquals(ok, (store.begin(key, "f1", Fx.now) as RunBegin.Replay).result)
    }

    @Test fun `retryable failure restarts with a new attempt, non retryable failure is replayed`() {
        val s1 = store.begin(key, "f1", Fx.now) as RunBegin.Started
        store.complete(key, s1.runId, ActionResult.Failed("DEPENDENCY_UNAVAILABLE", true, "x"), Fx.now)
        val s2 = assertInstanceOf(RunBegin.Started::class.java, store.begin(key, "f1", Fx.now))
        assertEquals(2, s2.attempt)
        assertNotEquals(s1.runId, s2.runId)

        val bad = ActionResult.Failed("INVALID_INPUT", false, "x")
        store.complete(key, s2.runId, bad, Fx.now)
        assertEquals(bad, (store.begin(key, "f1", Fx.now) as RunBegin.Replay).result)
    }

    @Test fun `a stale owner cannot complete a run it lost`() {
        val s1 = store.begin(key, "f1", Fx.now) as RunBegin.Started
        assertEquals(1, store.sweepStale(Fx.now.plusSeconds(60), Fx.now.plusSeconds(61)))
        assertFalse(store.complete(key, s1.runId, ActionResult.Ok(str("late")), Fx.now))
        assertEquals(RunStatus.FAILED, store.find(key)!!.status)
    }

    @Test fun `keys are scoped by tenant and action`() {
        store.begin(key, "f1", Fx.now)
        assertInstanceOf(RunBegin.Started::class.java, store.begin(key.copy(tenantId = Fx.tenantB), "f1", Fx.now))
        assertInstanceOf(RunBegin.Started::class.java, store.begin(key.copy(actionId = "a2"), "f1", Fx.now))
    }

    @Test fun `concurrent begins on one key yield exactly one Started`() {
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val tasks = (1..8).map { Callable { gate.await(); store.begin(key, "f1", Fx.now) } }
        val futures = tasks.map { pool.submit(it) }
        gate.countDown()
        val results = futures.map { it.get() }
        pool.shutdownNow()
        assertEquals(1, results.count { it is RunBegin.Started })
        assertEquals(7, results.count { it is RunBegin.InProgress })
    }

    @Test fun `limits are only ever lowered by a definition`() {
        val ceiling = ActionLimits(timeout = Duration.ofSeconds(10), maxInputBytes = 100, maxInputDepth = 3, maxCallDepth = 2)
        val wild = ActionLimits(timeout = Duration.ofHours(1), maxInputBytes = 1_000_000, maxInputDepth = 99, maxCallDepth = 99)
        assertEquals(ceiling, wild.coerceAtMost(ceiling))
        assertEquals(Duration.ofSeconds(1), ActionLimits(timeout = Duration.ofSeconds(1)).coerceAtMost(ceiling).timeout)
    }
}
