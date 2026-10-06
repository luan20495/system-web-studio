package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.workflow.canonical.CanonicalWorkflowReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The shape of a workflow is data, never code: a closed set of step kinds, value sources, comparison operators and approver kinds. These tests
 * pin that set (adding a `SCRIPT` step or an `EVAL` operator must be a deliberate, reviewed change) and exercise a step timeout end to end.
 */
class WorkflowShapeTests {
    private val executor = Executors.newCachedThreadPool()
    @AfterEach fun stop() { executor.shutdownNow() }

    @Test fun `the step kinds, value sources, operators and approver kinds are a closed set with no scripting`() {
        assertEquals(setOf("ACTION", "WAIT", "APPROVAL", "BRANCH", "END"), StepKind.entries.map { it.name }.toSet())
        assertEquals(setOf("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS"), CompareOp.entries.map { it.name }.toSet())
        assertEquals(setOf("Input", "Step", "Literal"), ValueRef::class.java.permittedSubclasses.map { it.simpleName }.toSet())
        assertEquals(setOf("User", "Group", "DepartmentManager", "Role"), PrincipalSpec::class.java.permittedSubclasses.map { it.simpleName }.toSet())
        val fields = WorkflowStep::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { f -> listOf("script", "code", "expr", "eval", "js", "lambda", "shell", "command").any { f.contains(it) } }, "WorkflowStep fields: $fields")
    }

    private fun parse(s: String) = CanonicalWorkflowReader.parse(Fx.tenantA, Fx.appA, Fx.json.readTree(s))

    @Test fun `a script-like step kind, operator or value source is rejected and the workflow is not offered`() {
        val p = parse("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[
            {"id":"s1","steps":[{"id":"a","kind":"SCRIPT","code":"process.exit()"}]},
            {"id":"s2","steps":[{"id":"a","kind":"JS","actionRef":"go"}]},
            {"id":"s3","steps":[{"id":"a","kind":"BRANCH","branches":[{"condition":{"op":"EVAL","left":{"from":"LITERAL","value":1},"right":{"from":"LITERAL","value":1}},"next":"a"}]}]},
            {"id":"s4","steps":[{"id":"a","kind":"ACTION","actionRef":"go","inputs":{"x":{"from":"EXPRESSION","expr":"1+1"}}}]}]}""")
        assertTrue(p.workflows.isEmpty(), "offered: ${p.workflows.keys}")
        assertEquals(setOf("s1", "s2", "s3", "s4"), p.issues.keys)
    }

    @Test fun `a script field next to a valid step is not carried into the parsed step`() {
        val p = parse("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[
            {"id":"ok","steps":[{"id":"a","kind":"ACTION","actionRef":"go","script":"DROP TABLE users","onSuccess":"() => 1"}]}]}""")
        val step = p.workflows["ok"]?.step("a")
        if (step != null) assertFalse(step.toString().contains("DROP TABLE") || step.toString().contains("=>"), "step: $step")
    }

    // ---- step timeout end to end -------------------------------------------------------------------------------------------------

    private val actions = listOf("w1").map { Fx.write(it) }

    @Test fun `a step timeout fails a slow action, and with no attempts left the run ends FAILED with TIMEOUT`() {
        val r = WorkflowRig(listOf(wf("slow", act("a", "w1", timeout = Duration.ofMillis(150), retry = RetryPolicy(maxAttempts = 1)))), actions, executor)
        r.data.onWrite = { Thread.sleep(800) }
        val id = r.startOk("slow"); r.drain()
        assertEquals(WorkflowRunStatus.FAILED, r.view(id).status)
        assertEquals("TIMEOUT", r.view(id).errorCode)
    }

    @Test fun `a timed out step is retried with backoff and the run can still succeed`() {
        val r = WorkflowRig(listOf(wf("slow", act("a", "w1", timeout = Duration.ofMillis(150), retry = RetryPolicy(maxAttempts = 3)))), actions, executor)
        var slow = true
        r.data.onWrite = { if (slow) Thread.sleep(800) }
        val id = r.startOk("slow"); r.drain()
        assertEquals(WorkflowRunStatus.WAITING, r.view(id).status)                     // RETRY_WAIT, not failed
        assertEquals(StepStatus.RETRY_WAIT, r.step(id, "a")!!.status)
        slow = false
        r.advance(Duration.ofMinutes(1)); r.engine.sweep(); r.drain()
        assertEquals(WorkflowRunStatus.SUCCEEDED, r.view(id).status)
    }

    // ---- conditions are read element by element (Jackson 3's JsonNode.map is a member that would shadow Iterable.map) ----------------

    /** Parses a one-branch workflow whose condition is [condition]; null when the workflow is not offered (invalid condition). */
    private fun cond(condition: String): Condition? =
        parse("""{"actions":[{"id":"go","type":"NAVIGATE","pageRef":"home"}],"workflows":[{"id":"w","steps":[
            {"id":"br","kind":"BRANCH","branches":[{"condition":$condition,"next":"fin"}],"defaultNext":"fin"},{"id":"fin","kind":"END"}]}]}""")
            .workflows["w"]?.step("br")?.branches?.single()?.condition
    private val eq = """{"op":"EQ","left":{"from":"LITERAL","value":1},"right":{"from":"LITERAL","value":1}}"""

    @Test fun `all and any read every element in order`() {
        val all = cond("""{"all":[$eq,{"exists":{"from":"INPUT","path":"a"}}]}""") as Condition.AllOf
        assertEquals(2, all.items.size); assertTrue(all.items[0] is Condition.Compare); assertTrue(all.items[1] is Condition.Exists)
        val any = cond("""{"any":[{"not":$eq},$eq,$eq]}""") as Condition.AnyOf
        assertEquals(3, any.items.size); assertTrue(any.items[0] is Condition.Not)
    }

    @Test fun `one invalid element anywhere makes the whole condition invalid and the workflow is not offered`() {
        val bad = """{"op":"NOPE","left":{"from":"LITERAL","value":1},"right":{"from":"LITERAL","value":1}}"""
        assertEquals(null, cond("""{"all":[$eq,{"op":"EVAL"}]}"""))
        assertEquals(null, cond("""{"any":[{"all":[$eq,$bad]},$eq]}"""))
        assertEquals(null, cond("""{"all":[$eq,"not-an-object"]}"""))
        assertEquals(null, cond("""{"all":"not-an-array"}"""))
    }

    @Test fun `an empty all array is read but rejected by the validator`() {
        val p = parse("""{"actions":[],"workflows":[{"id":"w","steps":[{"id":"br","kind":"BRANCH","branches":[{"condition":{"all":[]},"next":"fin"}],"defaultNext":"fin"},{"id":"fin","kind":"END"}]}]}""")
        assertTrue(p.workflows.isEmpty()); assertEquals(setOf("w"), p.issues.keys)
    }
}
