package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionType
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.workflow.ApprovalSpec
import com.systemwebstudio.logic.workflow.Branch
import com.systemwebstudio.logic.workflow.CompareOp
import com.systemwebstudio.logic.workflow.Condition
import com.systemwebstudio.logic.workflow.RetryPolicy
import com.systemwebstudio.logic.workflow.StepKind
import com.systemwebstudio.logic.workflow.ValueRef
import com.systemwebstudio.logic.workflow.WorkflowDefinition
import com.systemwebstudio.logic.workflow.WorkflowLimits
import com.systemwebstudio.logic.workflow.WorkflowStep
import com.systemwebstudio.logic.workflow.WorkflowTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** The two JSON codecs of V29: what is stored is exactly what comes back, with no framework or database involved. */
class RunCodecTests {
    private val json = JsonMapper.builder().build()
    private val definitions = WorkflowDefinitionCodec(json)
    private val results = ActionResultCodec(json)
    private val tenant = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val app = UUID.fromString("00000000-0000-0000-0000-0000000000f1")

    private fun text(s: String) = json.createObjectNode().put("v", s).get("v")

    @Test
    fun `a rich workflow definition survives encode and decode unchanged`() {
        val def = WorkflowDefinition(
            id = "onboarding", tenantId = tenant, appId = app, name = "Onboarding", trigger = WorkflowTrigger.SCHEDULE, startStepId = "s1",
            limits = WorkflowLimits(maxSteps = 12, maxStepExecutions = 40, maxRetries = 3, maxDuration = Duration.ofHours(6), maxWait = Duration.ofHours(2), maxPayloadBytes = 4096, maxDepth = 2, maxStepTimeout = Duration.ofSeconds(90)),
            enabled = true, compensateOnCancel = true,
            steps = listOf(
                WorkflowStep(
                    "s1", StepKind.ACTION, actionRef = "create-order", inputs = mapOf("title" to ValueRef.Literal(text("T")), "who" to ValueRef.Input("user.id"), "prev" to ValueRef.Step("s0", "out.id")),
                    next = "gate", onError = "end", retry = RetryPolicy(3, Duration.ofSeconds(2), 3.0, Duration.ofMinutes(1)), timeout = Duration.ofSeconds(30), compensationActionRef = "cancel-order"
                ),
                WorkflowStep("gate", StepKind.BRANCH, branches = listOf(
                    Branch(Condition.AllOf(listOf(Condition.Compare(ValueRef.Input("n"), CompareOp.GT, ValueRef.Literal(text("1"))), Condition.Not(Condition.Exists(ValueRef.Step("s1")))), ), "pause"),
                    Branch(Condition.AnyOf(listOf(Condition.Exists(ValueRef.Input("a")))), "approve")
                ), defaultNext = "end"),
                WorkflowStep("pause", StepKind.WAIT, wait = Duration.ofMinutes(5), next = "approve"),
                WorkflowStep(
                    "approve", StepKind.APPROVAL, next = "end", approval = ApprovalSpec(
                        "Approve it", listOf(PrincipalSpec.User(UUID.randomUUID(), tenant), PrincipalSpec.Group("finance"), PrincipalSpec.DepartmentManager(null), PrincipalSpec.Role("approvers")),
                        requiredApprovals = 2, expiresIn = Duration.ofHours(8), allowSelfApproval = false, notifyTemplateRef = "tpl", onReject = "end", onExpire = "end"
                    )
                ),
                WorkflowStep("end", StepKind.END)
            )
        )
        assertThat(definitions.decode(definitions.encode(def))).isEqualTo(def)
    }

    @Test
    fun `a minimal definition keeps its defaults and a corrupt stored one is refused`() {
        val def = WorkflowDefinition("w", tenant, app, steps = listOf(WorkflowStep("a", StepKind.END)))
        assertThat(definitions.decode(definitions.encode(def))).isEqualTo(def)
        val broken = definitions.encode(def).replace("\"MANUAL\"", "\"NOPE\"")
        assertThat(broken).contains("NOPE")
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) { definitions.decode(broken) }
    }

    @Test
    fun `an OK result keeps its output`() {
        val r = ActionResult.Ok(json.createObjectNode().put("id", "x1").put("n", 3))
        assertThat(results.decode(results.encode(r))).isEqualTo(r)
    }

    @Test
    fun `a failure keeps its code and its retryable flag, so an unknown write outcome stays unknown and never retryable`() {
        val unknown = ActionResult.Failed(ActionErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, retryable = false, message = "write outcome unknown", details = mapOf("k" to "v"))
        val back = results.decode(results.encode(unknown)) as ActionResult.Failed
        assertThat(back).isEqualTo(unknown)
        assertThat(back.retryable).isFalse()
        val rejected = ActionResult.Failed(ActionErrorCodes.MUTATION_REJECTED, retryable = false, message = "rejected")
        assertThat(results.decode(results.encode(rejected))).isEqualTo(rejected)
        val timeout = ActionResult.Failed(ActionErrorCodes.TIMEOUT, retryable = true, message = "slow")
        assertThat((results.decode(results.encode(timeout)) as ActionResult.Failed).retryable).isTrue()
    }

    @Test
    fun `a failure without a message or details round-trips`() {
        val bare = ActionResult.Failed("X", retryable = true)
        assertThat(results.decode(results.encode(bare))).isEqualTo(bare)
    }

    @Test
    fun `a would-run answer round-trips`() {
        val w = ActionResult.WouldRun("a1", ActionType.CREATE_RECORD, DryRunLevel.NOT_EXECUTED, json.createObjectNode().put("q", "orders"), null, "test")
        assertThat(results.decode(results.encode(w))).isEqualTo(w)
    }
}
