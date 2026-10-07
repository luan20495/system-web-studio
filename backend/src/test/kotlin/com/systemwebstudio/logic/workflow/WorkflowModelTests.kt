package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PrincipalSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

class WorkflowModelTests {
    private fun issues(d: WorkflowDefinition, ceiling: WorkflowLimits = WorkflowLimits()) = WorkflowDefinitionValidator.validate(d, ceiling)
    private fun paths(d: WorkflowDefinition) = issues(d).map { it.path }

    // ---- retry policy -----------------------------------------------------------------------------------------------

    @Test fun `backoff grows exponentially and is capped`() {
        val p = RetryPolicy(maxAttempts = 10, initialBackoff = Duration.ofSeconds(1), multiplier = 2.0, maxBackoff = Duration.ofSeconds(10))
        assertEquals(listOf(1L, 2L, 4L, 8L, 10L, 10L), (1..6).map { p.backoffAfter(it).seconds })
        assertEquals(Duration.ofSeconds(1), p.backoffAfter(0))
    }

    @Test fun `a retry policy rejects nonsense`() {
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(maxAttempts = 0) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(multiplier = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(multiplier = 50.0) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(initialBackoff = Duration.ofSeconds(-1)) }
    }

    @Test fun `backoff never overflows for huge attempt numbers`() {
        val p = RetryPolicy(maxAttempts = 1000, initialBackoff = Duration.ofSeconds(1), multiplier = 10.0, maxBackoff = Duration.ofMinutes(5))
        assertEquals(Duration.ofMinutes(5), p.backoffAfter(1000))
    }

    // ---- definition validation --------------------------------------------------------------------------------------

    private fun act(id: String, next: String? = null) = WorkflowStep(id, StepKind.ACTION, actionRef = "a1", next = next)
    private fun wf(vararg steps: WorkflowStep, limits: WorkflowLimits = WorkflowLimits(), start: String? = null) =
        WorkflowDefinition("w", Fx.tenantA, Fx.appA, steps = steps.toList(), limits = limits, startStepId = start)

    @Test fun `a plain chain of actions is valid`() {
        assertTrue(issues(wf(act("a"), act("b"), act("c"))).isEmpty())
    }

    @Test fun `ids are checked`() {
        assertTrue(issues(wf(act("ok"), start = "ghost")).any { it.path == "startStepId" })
        assertTrue(issues(wf(act("a"), act("a"))).any { it.message.contains("duplicate") })
        assertTrue(issues(wf(act("bad id!"))).any { it.path == "steps[0].id" })
        assertTrue(issues(wf(act("x".repeat(65)))).any { it.path == "steps[0].id" })
        assertTrue(issues(wf(act("a"), act("../b"))).any { it.path == "steps[1].id" })
        assertTrue(issues(WorkflowDefinition("not an id", Fx.tenantA, Fx.appA, steps = listOf(act("a")))).any { it.path == "id" })
    }

    @Test fun `empty workflows and unknown targets are invalid`() {
        assertEquals(listOf("steps"), issues(wf()).map { it.path })
        assertTrue(paths(wf(act("a", next = "zzz"))).contains("steps[0].next"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.ACTION, actionRef = "a1", onError = "zzz"))).contains("steps[0].onError"))
    }

    @Test fun `unreachable steps are an authoring error`() {
        val d = wf(act("a", next = "c"), act("b"), act("c"))
        assertTrue(issues(d).any { it.path == "steps.b" })
        assertTrue(issues(wf(act("a", next = "c"), act("b"), act("c"), start = "b")).any { it.path == "steps.a" })
    }

    @Test fun `limits are enforced against the platform ceiling`() {
        val tight = WorkflowLimits(maxSteps = 2, maxRetries = 1, maxDuration = Duration.ofHours(1))
        assertTrue(issues(wf(act("a"), act("b"), act("c")), tight).any { it.path == "steps" })
        assertTrue(issues(wf(limits = WorkflowLimits(maxSteps = 500), steps = arrayOf(act("a"))), tight).any { it.path == "limits.maxSteps" })
        assertTrue(issues(wf(limits = WorkflowLimits(maxDuration = Duration.ofDays(3)), steps = arrayOf(act("a"))), tight).any { it.path == "limits.maxDuration" })
        val retrying = WorkflowStep("a", StepKind.ACTION, actionRef = "a1", retry = RetryPolicy(maxAttempts = 5))
        assertTrue(issues(wf(retrying), tight).any { it.path == "steps[0].retry.maxAttempts" })
    }

    @Test fun `step timeouts must be positive and bounded`() {
        fun t(d: Duration) = issues(wf(WorkflowStep("a", StepKind.ACTION, actionRef = "a1", timeout = d))).any { it.path == "steps[0].timeout" }
        assertTrue(t(Duration.ZERO)); assertTrue(t(Duration.ofSeconds(-1))); assertTrue(t(Duration.ofHours(1)))
        assertFalse(t(Duration.ofSeconds(30)))
    }

    @Test fun `each step kind demands its own fields`() {
        assertTrue(paths(wf(WorkflowStep("a", StepKind.ACTION))).contains("steps[0].actionRef"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.ACTION, actionRef = "http://x"))).contains("steps[0].actionRef"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.WAIT))).contains("steps[0].wait"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.WAIT, wait = Duration.ofDays(60)))).contains("steps[0].wait"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.APPROVAL))).contains("steps[0].approval"))
        assertTrue(paths(wf(WorkflowStep("a", StepKind.BRANCH))).contains("steps[0].branches"))
        val badApproval = WorkflowStep("a", StepKind.APPROVAL, approval = ApprovalSpec(" ", emptyList(), 0))
        val p = paths(wf(badApproval))
        assertTrue(p.containsAll(listOf("steps[0].approval.title", "steps[0].approval.approvers", "steps[0].approval.requiredApprovals")), "got $p")
        val ok = WorkflowStep("a", StepKind.APPROVAL, approval = ApprovalSpec("t", listOf(PrincipalSpec.Role("r"))))
        assertTrue(issues(wf(ok)).isEmpty())
    }

    @Test fun `value references and conditions are validated`() {
        fun branch(c: Condition) = wf(WorkflowStep("b", StepKind.BRANCH, branches = listOf(Branch(c, "e"))), WorkflowStep("e", StepKind.END))
        val lit = ValueRef.Literal(Fx.num(1))
        assertTrue(issues(branch(Condition.Compare(ValueRef.Input("a.b"), CompareOp.EQ, lit))).isEmpty())
        assertTrue(issues(branch(Condition.Compare(ValueRef.Input("a[0]"), CompareOp.EQ, lit))).isNotEmpty())
        assertTrue(issues(branch(Condition.Compare(ValueRef.Step("ghost"), CompareOp.EQ, lit))).isNotEmpty())
        assertTrue(issues(branch(Condition.Compare(ValueRef.Step("b"), CompareOp.EQ, lit))).any { it.message.contains("own output") })
        assertTrue(issues(branch(Condition.AllOf(emptyList()))).isNotEmpty())
        var deep: Condition = Condition.Exists(ValueRef.Input("x"))
        repeat(6) { deep = Condition.Not(deep) }
        assertTrue(issues(branch(deep)).any { it.message.contains("nesting") })
        val wide = Condition.AllOf((1..25).map { Condition.Exists(ValueRef.Input("x")) })
        assertTrue(issues(branch(wide)).any { it.message.contains("nodes") })
    }

    @Test fun `step input paths must be plain`() {
        val bad = WorkflowStep("a", StepKind.ACTION, actionRef = "a1", inputs = mapOf("x" to ValueRef.Input("a..b")))
        assertTrue(paths(wf(bad)).contains("steps[0].inputs.x"))
        val tooMany = WorkflowStep("a", StepKind.ACTION, actionRef = "a1", inputs = (1..40).associate { "i$it" to (ValueRef.Literal(Fx.num(it)) as ValueRef) })
        assertTrue(paths(wf(tooMany)).contains("steps[0].inputs"))
    }

    @Test fun `limits can only be tightened by the platform ceiling`() {
        val d = WorkflowLimits(maxSteps = 100, maxDuration = Duration.ofDays(30), maxDepth = 9)
        val c = WorkflowLimits(maxSteps = 20, maxDuration = Duration.ofDays(1), maxDepth = 3)
        val m = d.coerceAtMost(c)
        assertEquals(20, m.maxSteps); assertEquals(Duration.ofDays(1), m.maxDuration); assertEquals(3, m.maxDepth)
        assertEquals(WorkflowLimits(maxSteps = 5).coerceAtMost(c).maxSteps, 5)
    }

    // ---- conditions ---------------------------------------------------------------------------------------------------

    private val input: JsonNode = Fx.json.readTree("""{"n":5,"s":"hello","t":true,"tags":["a","b"],"nested":{"x":1.0},"nothing":null}""")
    private fun resolve(r: ValueRef): JsonNode? = when (r) {
        is ValueRef.Literal -> r.value
        is ValueRef.Input -> r.path.split('.').filter { it.isNotEmpty() }.fold(input as JsonNode?) { n, p -> n?.get(p) }
        is ValueRef.Step -> null
    }
    private fun eval(l: ValueRef, op: CompareOp, r: ValueRef) = ConditionEvaluator.eval(Condition.Compare(l, op, r), ::resolve)
    private fun inp(p: String) = ValueRef.Input(p)
    private fun lit(n: Int) = ValueRef.Literal(Fx.num(n))
    private fun lit(s: String) = ValueRef.Literal(Fx.str(s))

    @Test fun `numbers compare by value not by representation`() {
        assertTrue(eval(inp("n"), CompareOp.EQ, lit(5)))
        assertTrue(eval(inp("nested.x"), CompareOp.EQ, lit(1)))          // 1.0 == 1
        assertTrue(eval(inp("n"), CompareOp.GT, lit(4))); assertTrue(eval(inp("n"), CompareOp.GTE, lit(5)))
        assertTrue(eval(inp("n"), CompareOp.LT, lit(6))); assertTrue(eval(inp("n"), CompareOp.LTE, lit(5)))
        assertFalse(eval(inp("n"), CompareOp.GT, lit(5)))
        assertTrue(eval(inp("n"), CompareOp.NE, lit(6)))
    }

    @Test fun `strings, membership and containment`() {
        assertTrue(eval(inp("s"), CompareOp.EQ, lit("hello"))); assertTrue(eval(inp("s"), CompareOp.LT, lit("world")))
        assertTrue(eval(inp("s"), CompareOp.CONTAINS, lit("ell")))
        assertTrue(eval(inp("tags"), CompareOp.CONTAINS, lit("a"))); assertFalse(eval(inp("tags"), CompareOp.CONTAINS, lit("z")))
        assertTrue(eval(inp("s"), CompareOp.IN, ValueRef.Literal(Fx.json.readTree("""["hello","bye"]"""))))
        assertFalse(eval(inp("s"), CompareOp.IN, lit("hello")))                // right side must be an array
    }

    @Test fun `mixed types never match and never throw`() {
        assertFalse(eval(inp("s"), CompareOp.GT, lit(1)))
        assertFalse(eval(inp("n"), CompareOp.EQ, lit("5")))
        assertFalse(eval(inp("t"), CompareOp.CONTAINS, lit("x")))
        assertFalse(eval(inp("n"), CompareOp.LT, inp("s")))
    }

    @Test fun `missing and null operands are false, except NE`() {
        assertFalse(eval(inp("nope"), CompareOp.EQ, lit(1))); assertFalse(eval(inp("nothing"), CompareOp.EQ, lit(1)))
        assertFalse(eval(inp("nope"), CompareOp.GT, lit(1)))
        assertTrue(eval(inp("nope"), CompareOp.NE, lit(1)))
        assertFalse(ConditionEvaluator.eval(Condition.Exists(inp("nothing")), ::resolve))
        assertTrue(ConditionEvaluator.eval(Condition.Exists(inp("n")), ::resolve))
    }

    @Test fun `AllOf AnyOf and Not combine`() {
        val t = Condition.Exists(inp("n")); val f = Condition.Exists(inp("nope"))
        assertTrue(ConditionEvaluator.eval(Condition.AllOf(listOf(t, t)), ::resolve)); assertFalse(ConditionEvaluator.eval(Condition.AllOf(listOf(t, f)), ::resolve))
        assertTrue(ConditionEvaluator.eval(Condition.AnyOf(listOf(f, t)), ::resolve)); assertFalse(ConditionEvaluator.eval(Condition.AnyOf(listOf(f, f)), ::resolve))
        assertTrue(ConditionEvaluator.eval(Condition.Not(f), ::resolve))
    }

    @Test fun `numbers beyond double precision compare exactly`() {
        val big = ValueRef.Literal(Fx.json.readTree("9007199254740992"))
        val bigger = ValueRef.Literal(Fx.json.readTree("9007199254740993"))
        assertTrue(eval(bigger, CompareOp.GT, big)); assertFalse(eval(big, CompareOp.EQ, bigger))
    }

    // ---- queue messages -----------------------------------------------------------------------------------------------

    @Test fun `job messages round trip and carry no actor or payload`() {
        val j = WorkflowJob.forStep(Fx.tenantA, UUID.randomUUID(), "step-1")
        assertEquals(j, WorkflowJob.decode(j.encode()))
        assertEquals(5, j.encode().split('|').size)
        val c = WorkflowJob.forStep(Fx.tenantA, UUID.randomUUID(), WorkflowJob.COMPENSATE)
        assertEquals(c, WorkflowJob.decode(c.encode()))
    }

    @Test fun `anything that is not exactly a v1 job is rejected`() {
        val ok = WorkflowJob.forStep(Fx.tenantA, UUID.randomUUID(), "s").encode()
        for (bad in listOf("", "garbage", ok.replace("v1", "v2"), "$ok|extra", ok.substringBeforeLast('|'), ok.replace(Fx.tenantA.toString(), "not-a-uuid"),
            ok.substringBeforeLast('|') + "|bad step", ok.substringBeforeLast('|') + "|~other", "v1|" + "x".repeat(500), ok + "\n")) {
            assertTrue(WorkflowJob.decode(bad) == null, "accepted: $bad")
        }
    }

    @Test fun `no retry ever waits less than the backoff floor`() {
        val zero = RetryPolicy(maxAttempts = 5, initialBackoff = java.time.Duration.ZERO, maxBackoff = java.time.Duration.ZERO)
        for (a in 1..6) assertEquals(RetryPolicy.MIN_BACKOFF, zero.backoffAfter(a))
        val tiny = RetryPolicy(maxAttempts = 5, initialBackoff = java.time.Duration.ofMillis(1), multiplier = 1.0)
        assertEquals(RetryPolicy.MIN_BACKOFF, tiny.backoffAfter(1))
        val normal = RetryPolicy(maxAttempts = 5, initialBackoff = java.time.Duration.ofSeconds(2), maxBackoff = java.time.Duration.ofSeconds(10))
        assertEquals(java.time.Duration.ofSeconds(2), normal.backoffAfter(1)); assertEquals(java.time.Duration.ofSeconds(4), normal.backoffAfter(2))
        assertEquals(java.time.Duration.ofSeconds(10), normal.backoffAfter(30))
    }
}
