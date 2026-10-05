package com.systemwebstudio.logic.workflow

import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.ActionRequest
import com.systemwebstudio.logic.action.ActionResult
import com.systemwebstudio.logic.action.ActionRuntime
import com.systemwebstudio.logic.action.ActorKind
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.DryRunLevel
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.LogicAuditRecord
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.ResourceKind
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.TenantGate
import com.systemwebstudio.logic.action.TriggerInfo
import com.systemwebstudio.logic.action.TriggerKind
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.limits.InMemoryTenantRateLimiter
import com.systemwebstudio.logic.limits.RateDecision
import com.systemwebstudio.logic.limits.RateScope
import com.systemwebstudio.logic.limits.TenantRateLimiter
import com.systemwebstudio.logic.approval.Approval
import com.systemwebstudio.logic.approval.ApprovalListener
import com.systemwebstudio.logic.approval.ApprovalRequest
import com.systemwebstudio.logic.approval.ApprovalResult
import com.systemwebstudio.logic.approval.ApprovalService
import com.systemwebstudio.logic.approval.ApprovalSource
import com.systemwebstudio.logic.approval.ApprovalStatus
import com.systemwebstudio.logic.scheduler.EnqueueOutcome
import com.systemwebstudio.logic.scheduler.ScheduleTarget
import com.systemwebstudio.logic.scheduler.ScheduledRunEnqueuer
import com.systemwebstudio.logic.scheduler.ScheduledRunRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ProcessOutcome { DONE, RETRY }

data class SweepReport(
    val timersCompleted: Int = 0, val retriesPublished: Int = 0, val approvalsReconciled: Int = 0, val republished: Int = 0,
    val timedOut: Int = 0, val compensationsResumed: Int = 0, val approvalsExpired: Int = 0
)

/**
 * The workflow engine: starts runs, executes one step per queue message, retries with backoff, waits, asks for approvals, compensates, and
 * resumes from persisted state. Everything it knows lives in [WorkflowRunStore]; the queue only carries "look at run R, step S" nudges, so a
 * lost, duplicated or reordered message cannot corrupt a run (every transition is a compare-and-set on the run row).
 *
 * Failure model, in one place:
 *  - A step failure that is retryable and has attempts left → RETRY_WAIT with exponential backoff; the sweeper publishes when it is due.
 *  - A terminal step failure → the step's `onError` target if it has one, else the run FAILS (and compensates in reverse order).
 *  - A crashed worker leaves the step RUNNING; the sweeper puts it back and re-publishes. The action's idempotency key `wf:<run>:<step>`
 *    makes re-executing a step that actually finished a replay, not a second effect.
 *  - A message that cannot be processed [maxDeliveries] times lands in the dead-letter queue; [failFromDeadLetter] then fails its run so
 *    it never hangs silently.
 *
 * Permission model: the run acts as the user who started it. Every ACTION step goes through the ActionRuntime, which re-checks the tenant
 * gate and that user's permissions at that moment.
 */
class WorkflowEngine(
    private val json: JsonMapper,
    private val definitions: WorkflowDefinitionProvider,
    private val actions: ActionRuntime,
    private val runs: WorkflowRunStore,
    private val queue: WorkflowQueue,
    private val access: AccessPort,
    private val tenants: TenantGate,
    private val audit: LogicAuditPort,
    private val approvals: ApprovalService? = null,
    private val ceiling: WorkflowLimits = WorkflowLimits(),
    private val clock: Clock = Clock.systemUTC(),
    /** A run untouched for this long while it should be progressing is considered lost and nudged again. */
    private val staleAfter: Duration = Duration.ofMinutes(2),
    /** Per-tenant limit on workflow starts ([RateScope.WORKFLOW_START]); scheduled fires are limited by the scheduler's own scope instead. */
    private val limiter: TenantRateLimiter = InMemoryTenantRateLimiter(clock = clock)
) : WorkflowRuntime, WorkflowStarterPort, ScheduledRunEnqueuer, ApprovalListener {

    private val log = System.getLogger(WorkflowEngine::class.java.name)

    // ───────────────────────────── start / status / cancel ─────────────────────────────

    override fun start(ctx: ActionContext, request: WorkflowStartRequest): WorkflowResult<WorkflowRunView> = start(ctx, request, countStart = true)

    private fun start(ctx: ActionContext, request: WorkflowStartRequest, countStart: Boolean): WorkflowResult<WorkflowRunView> {
        val appId = ctx.projectId ?: return fail(WorkflowErrorCodes.INVALID_INPUT, "Application context is required")
        gate(ctx)?.let { return it }
        if (!KEY.matches(request.idempotencyKey)) return fail(WorkflowErrorCodes.KEY_INVALID, "Idempotency key must be 1-128 chars of [A-Za-z0-9._:-]")
        if (request.callDepth < 0 || request.callDepth > ceiling.maxDepth) return fail(WorkflowErrorCodes.LIMIT_EXCEEDED, "Workflow nesting exceeds ${ceiling.maxDepth}")

        val found = try { java.util.Optional.ofNullable(definitions.find(ctx.tenantId, appId, request.workflowRef, request.mode)) } catch (e: Exception) {
            return fail(WorkflowErrorCodes.DEPENDENCY_UNAVAILABLE, "Workflow definitions are unavailable", retryable = true)
        }
        val def = found.orElse(null)?.takeIf { it.tenantId == ctx.tenantId && it.appId == appId && it.enabled && it.id == request.workflowRef }
            ?: return fail(WorkflowErrorCodes.UNKNOWN_WORKFLOW, "Workflow not found")

        for (check in listOf(
            AccessRequest(LogicPermissions.APP_USE, ResourceKind.APP, appId.toString(), appId, request.mode),
            AccessRequest(LogicPermissions.WORKFLOW_EXECUTE, ResourceKind.WORKFLOW, def.id, appId, request.mode)
        )) deny(ctx, check)?.let { return it }

        // After authorization (the unauthorized cannot spend the tenant's budget), before the run exists.
        if (countStart) rateLimit(ctx.tenantId, RateScope.WORKFLOW_START)?.let { return it }

        return startInternal(ctx, appId, def, request.input, request.idempotencyKey, request.mode, request.callDepth)
    }

    private fun startInternal(
        ctx: ActionContext, appId: UUID, def: WorkflowDefinition, input: JsonNode, key: String, mode: ExecutionMode, depth: Int
    ): WorkflowResult<WorkflowRunView> {
        val issues = WorkflowDefinitionValidator.validate(def, ceiling)
        if (issues.isNotEmpty()) return fail(WorkflowErrorCodes.INVALID_DEFINITION, "Workflow definition is invalid: " + issues.take(3).joinToString("; ") { "${it.path} ${it.message}" })
        val limits = def.limits.coerceAtMost(ceiling)
        if (input.toString().length > limits.maxPayloadBytes) return fail(WorkflowErrorCodes.LIMIT_EXCEEDED, "Input exceeds ${limits.maxPayloadBytes} bytes")

        val now = clock.instant()
        // Fail closed: no run exists that was not audited.
        try {
            audit.record(auditRecord("START_REQUESTED", ctx.tenantId, ctx.actor, appId, null, def.id, mode, attrs = mapOf("workflowId" to def.id)))
        } catch (e: Exception) {
            return fail(WorkflowErrorCodes.AUDIT_UNAVAILABLE, "Audit trail is unavailable", retryable = true)
        }
        val run = WorkflowRun(
            runId = UUID.randomUUID(), tenantId = ctx.tenantId, appId = appId, workflowId = def.id, definition = def, mode = mode,
            status = WorkflowRunStatus.PENDING, createdBy = ctx.actor, workspaceId = ctx.workspaceId, idempotencyKey = key,
            fingerprint = sha256("${def.id}|$mode|${canonical(input)}"), input = input, currentStepId = def.start,
            steps = mapOf(def.start to StepState(def.start, StepStatus.PENDING)), depth = depth, stepExecutions = 1, createdAt = now, updatedAt = now
        )
        return when (val created = try { runs.create(run) } catch (e: Exception) { null }) {
            null -> fail(WorkflowErrorCodes.DEPENDENCY_UNAVAILABLE, "Run store is unavailable", retryable = true)
            is CreateOutcome.KeyReused -> fail(WorkflowErrorCodes.KEY_REUSED, "Idempotency key was already used with different input")
            is CreateOutcome.Existing -> WorkflowResult.Ok(created.run.toView())
            is CreateOutcome.Created -> {
                publish(created.run.tenantId, created.run.runId, def.start)
                WorkflowResult.Ok(created.run.toView())
            }
        }
    }

    override fun status(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> {
        val run = load(ctx.tenantId, runId) ?: return fail(WorkflowErrorCodes.RUN_NOT_FOUND, "Run not found")
        if (!mayView(ctx, run)) return fail(WorkflowErrorCodes.RUN_NOT_FOUND, "Run not found")
        return WorkflowResult.Ok(run.toView())
    }

    override fun cancel(ctx: ActionContext, runId: UUID): WorkflowResult<WorkflowRunView> {
        gate(ctx)?.let { return it }
        val run0 = load(ctx.tenantId, runId) ?: return fail(WorkflowErrorCodes.RUN_NOT_FOUND, "Run not found")
        if (!mayView(ctx, run0)) return fail(WorkflowErrorCodes.RUN_NOT_FOUND, "Run not found")
        if (run0.status.terminal) return WorkflowResult.Ok(run0.toView())
        val now = clock.instant()
        val t = (try {
            mutate(ctx.tenantId, runId) { cur ->
                if (cur.status.terminal) return@mutate null
                val comp = if (cur.definition.compensateOnCancel && cur.mode == ExecutionMode.LIVE && cur.compensable.isNotEmpty()) CompensationState.IN_PROGRESS else CompensationState.NONE
                Transition(cur.copy(status = WorkflowRunStatus.CANCELLED, compensation = comp, finishedAt = now, updatedAt = now), null, comp == CompensationState.IN_PROGRESS, "CANCELLED")
            }
        } catch (e: ContendedException) {
            return fail(WorkflowErrorCodes.CONFLICT, "Concurrent update, please retry", retryable = true)
        }) ?: return WorkflowResult.Ok((load(ctx.tenantId, runId) ?: run0).toView())
        val approvalId = run0.currentStep?.approvalId
        if (approvalId != null) try { approvals?.cancel(ctx, approvalId) } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Cancelling approval failed: ${e.javaClass.simpleName}") }
        afterTransition(t)
        return WorkflowResult.Ok(t.run.toView())
    }

    // ───────────────────────────── ports implemented for other modules ─────────────────────────────

    /** [WorkflowStarterPort]: the START_WORKFLOW action. The child run's depth is the caller's + 1. */
    override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome =
        when (val r = start(ctx, WorkflowStartRequest(request.workflowRef, request.input, request.idempotencyKey, ExecutionMode.LIVE, request.callDepth + 1))) {
            is WorkflowResult.Ok -> PortOutcome.Success(json.createObjectNode().put("runId", r.value.runId.toString()).put("status", r.value.status.name))
            is WorkflowResult.Failed -> PortOutcome.Failure(r.code, r.retryable, r.message)
        }

    /** [ScheduledRunEnqueuer]: the scheduler hands over a fire; the run acts as the schedule's owner. */
    override fun enqueue(request: ScheduledRunRequest): EnqueueOutcome {
        val ctx = ActionContext(request.tenantId, ActionActor(request.actorUserId, ActorKind.USER), null, request.appId, "sched:${request.scheduleId}")
        val result = when (val t = request.target) {
            is ScheduleTarget.Workflow -> start(ctx, WorkflowStartRequest(t.workflowRef, request.input ?: json.createObjectNode(), request.idempotencyKey), countStart = false)
            is ScheduleTarget.Action -> startScheduledAction(ctx, t.actionRef, request.input, request.idempotencyKey)
        }
        return when (result) {
            is WorkflowResult.Ok -> EnqueueOutcome.Enqueued(result.value.runId.toString())
            is WorkflowResult.Failed -> EnqueueOutcome.Failed(result.code, result.retryable)
        }
    }

    /** A schedule that targets a single action runs it as a one-step run: same retries, timeouts, audit and permission re-check as any workflow. */
    private fun startScheduledAction(ctx: ActionContext, actionRef: String, input: JsonNode?, key: String): WorkflowResult<WorkflowRunView> {
        val appId = ctx.projectId ?: return fail(WorkflowErrorCodes.INVALID_INPUT, "Application context is required")
        gate(ctx)?.let { return it }
        deny(ctx, AccessRequest(LogicPermissions.APP_USE, ResourceKind.APP, appId.toString(), appId, ExecutionMode.LIVE))?.let { return it }
        val inputs = linkedMapOf<String, ValueRef>()
        if (input != null && input.isObject) input.propertyNames().forEach { inputs[it] = ValueRef.Literal(input.get(it)) }
        val def = WorkflowDefinition(
            id = "scheduled-$actionRef".take(128), tenantId = ctx.tenantId, appId = appId, trigger = WorkflowTrigger.SCHEDULE,
            steps = listOf(WorkflowStep("run", StepKind.ACTION, actionRef = actionRef, inputs = inputs, retry = RetryPolicy(maxAttempts = 3)))
        )
        return startInternal(ctx, appId, def, json.createObjectNode(), key, ExecutionMode.LIVE, 0)
    }

    override fun onFinal(approval: Approval) = resumeFromApproval(approval)

    // ───────────────────────────── worker: process one job ─────────────────────────────

    /** Executes one queue message. [ProcessOutcome.RETRY] = transient infrastructure problem, redeliver; [ProcessOutcome.DONE] = ack. */
    fun process(job: WorkflowJob): ProcessOutcome {
        val tenantOn = try { tenants.isEnabled(job.tenantId) } catch (e: Exception) { return ProcessOutcome.RETRY }
        val run0 = try { runs.get(job.tenantId, job.runId) } catch (e: Exception) { return ProcessOutcome.RETRY } ?: return ProcessOutcome.DONE

        if (run0.status.terminal) {
            if (job.stepId == WorkflowJob.COMPENSATE && run0.compensation == CompensationState.IN_PROGRESS && tenantOn) runCompensation(run0)
            return ProcessOutcome.DONE
        }
        if (!tenantOn) {
            mutate(run0.tenantId, run0.runId) { fail(it, WorkflowErrorCodes.TENANT_DISABLED, "Tenant is disabled") }?.let { afterTransition(it) }
            return ProcessOutcome.DONE
        }
        if (job.stepId == WorkflowJob.COMPENSATE || run0.currentStepId != job.stepId) return ProcessOutcome.DONE // stale message

        val def = run0.definition
        val limits = def.limits.coerceAtMost(ceiling)
        val now = clock.instant()
        if (now.isAfter(run0.createdAt.plus(limits.maxDuration))) {
            mutate(run0.tenantId, run0.runId) { fail(it, WorkflowErrorCodes.TIMEOUT, "Workflow exceeded ${limits.maxDuration}") }?.let { afterTransition(it) }
            return ProcessOutcome.DONE
        }
        val step = def.step(job.stepId) ?: run {
            mutate(run0.tenantId, run0.runId) { fail(it, WorkflowErrorCodes.INVALID_DEFINITION, "Step ${job.stepId} no longer exists") }?.let { afterTransition(it) }
            return ProcessOutcome.DONE
        }

        // 1. claim the step (compare-and-set): only one worker executes an attempt
        var attempt = 0
        val claimed = (try {
            mutate(run0.tenantId, run0.runId) { cur ->
                if (cur.status.terminal || cur.currentStepId != step.id) return@mutate null
                val st = cur.steps[step.id] ?: StepState(step.id, StepStatus.PENDING)
                val claimable = st.status == StepStatus.PENDING || (st.status == StepStatus.RETRY_WAIT && st.wakeAt?.isAfter(now) != true)
                if (!claimable) return@mutate null
                attempt = st.attempt + 1
                val running = st.copy(status = StepStatus.RUNNING, attempt = attempt, startedAt = now, wakeAt = null, errorCode = null, errorMessage = null)
                Transition(cur.withStep(running).copy(status = WorkflowRunStatus.RUNNING, updatedAt = now), null, false, null)
            }
        } catch (e: ContendedException) { return ProcessOutcome.RETRY }) ?: return ProcessOutcome.DONE
        val run = claimed.run

        // 2. execute outside any lock
        val ctx = ActionContext(run.tenantId, run.createdBy, run.workspaceId, run.appId, "wf:${run.runId}")
        val resolved = resolveInputs(run, step)
        val outcome = try { execute(ctx, run, step, resolved, attempt) } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Step ${step.id} threw ${e.javaClass.name}")
            StepOutcome.Fail(ActionErrorCodes.HANDLER_ERROR, "Step failed unexpectedly")
        }

        // 3. record the outcome (compare-and-set); a cancel/timeout that happened meanwhile wins
        val applied = try {
            mutate(run.tenantId, run.runId) { cur -> applyOutcome(cur, step, attempt, resolved, outcome, clock.instant()) }
        } catch (e: ContendedException) { return ProcessOutcome.RETRY }
        if (applied == null) {
            // The run ended (e.g. cancelled) while the step was running; undo it if the definition asks for that.
            val latest = load(run.tenantId, run.runId)
            if (latest != null && latest.status == WorkflowRunStatus.CANCELLED && outcome is StepOutcome.Advance && latest.definition.compensateOnCancel && latest.mode == ExecutionMode.LIVE) {
                compensateOne(latest, step, resolved)
            }
            return ProcessOutcome.DONE
        }
        afterTransition(applied)
        return ProcessOutcome.DONE
    }

    private sealed interface StepOutcome {
        data class Advance(val output: JsonNode, val next: String?, val simulated: Boolean = false, val level: DryRunLevel? = null) : StepOutcome
        /** [consumesAttempt] false for a rate-limit pushback: the step did not fail, it was told to wait, so it keeps its attempt budget. */
        data class Retry(val delay: Duration, val code: String, val message: String?, val consumesAttempt: Boolean = true) : StepOutcome
        data class Fail(val code: String, val message: String?) : StepOutcome
        data class Wait(val wakeAt: Instant) : StepOutcome
        data class WaitApproval(val approvalId: UUID) : StepOutcome
        data class Finish(val output: JsonNode) : StepOutcome
    }

    private fun execute(ctx: ActionContext, run: WorkflowRun, step: WorkflowStep, inputs: Map<String, JsonNode>, attempt: Int): StepOutcome {
        val test = run.mode == ExecutionMode.TEST
        return when (step.kind) {
            StepKind.END -> StepOutcome.Finish(json.createObjectNode())

            StepKind.BRANCH -> {
                val hit = step.branches.firstOrNull { ConditionEvaluator.eval(it.condition) { ref -> value(run, ref) } }?.next ?: step.defaultNext
                if (hit == null) StepOutcome.Fail(WorkflowErrorCodes.INVALID_DEFINITION, "No branch matched and no default")
                else StepOutcome.Advance(json.createObjectNode().put("branch", hit), hit)
            }

            StepKind.WAIT -> {
                val d = step.wait!!
                if (test) StepOutcome.Advance(json.createObjectNode().put("wouldWaitSeconds", d.seconds), nextOf(run, step), simulated = true, level = DryRunLevel.NOT_EXECUTED)
                else StepOutcome.Wait(clock.instant().plus(d))
            }

            StepKind.APPROVAL -> {
                val spec = step.approval!!
                if (test) {
                    StepOutcome.Advance(
                        json.createObjectNode().put("wouldRequestApproval", spec.title).put("requiredApprovals", spec.requiredApprovals), nextOf(run, step),
                        simulated = true, level = DryRunLevel.NOT_EXECUTED
                    )
                } else {
                    val svc = approvals ?: return StepOutcome.Fail(WorkflowErrorCodes.NOT_IMPLEMENTED, "Approvals are not wired")
                    val r = svc.request(
                        ctx,
                        ApprovalRequest(
                            title = spec.title, approvers = spec.approvers, requiredApprovals = spec.requiredApprovals, expiresIn = spec.expiresIn,
                            allowSelfApproval = spec.allowSelfApproval, notifyTemplateRef = spec.notifyTemplateRef,
                            source = ApprovalSource(run.runId, step.id), idempotencyKey = stepKey(run, step), appId = run.appId
                        )
                    )
                    when (r) {
                        is ApprovalResult.Ok -> StepOutcome.WaitApproval(r.value.id)
                        is ApprovalResult.Failed ->
                            if (r.retryable && attempt < step.retry.maxAttempts) StepOutcome.Retry(step.retry.backoffAfter(attempt), r.code, r.message)
                            else StepOutcome.Fail(r.code, r.message)
                    }
                }
            }

            StepKind.ACTION -> {
                val req = ActionRequest(
                    actionId = step.actionRef!!, inputs = inputs, idempotencyKey = stepKey(run, step),
                    trigger = TriggerInfo(TriggerKind.WORKFLOW_STEP, "${run.workflowId}.${step.id}", run.runId.toString()),
                    callDepth = run.depth, mode = run.mode, timeout = step.timeout
                )
                when (val res = actions.execute(ctx, req)) {
                    is ActionResult.Ok -> StepOutcome.Advance(res.output, nextOf(run, step))
                    is ActionResult.WouldRun -> StepOutcome.Advance(wouldRunOutput(res), nextOf(run, step), simulated = true, level = res.level)
                    is ActionResult.Failed ->
                        // A rate limit is pushback, not a failure: wait as long as asked (never less than the backoff floor); the run's maximum duration still bounds it.
                        if (res.code == ActionErrorCodes.RATE_LIMITED) StepOutcome.Retry(
                            maxOf(RetryPolicy.MIN_BACKOFF, Duration.ofMillis(res.details["retryAfterMillis"]?.toLongOrNull()?.coerceIn(0, 300_000) ?: 0)), res.code, res.message, consumesAttempt = false
                        )
                        else if (res.retryable && attempt < step.retry.maxAttempts) StepOutcome.Retry(step.retry.backoffAfter(attempt), res.code, res.message)
                        else StepOutcome.Fail(res.code, res.message)
                }
            }
        }
    }

    private fun wouldRunOutput(r: ActionResult.WouldRun): JsonNode {
        val o = json.createObjectNode().put("simulated", true).put("level", r.level.name).put("actionId", r.actionId).put("type", r.type.name)
        r.reason?.let { o.put("reason", it) }
        o.set<JsonNode>("plan", r.plan)
        r.output?.let { o.set<JsonNode>("output", it) }
        return o
    }

    /**
     * Idempotency key of one visit of a step. Retries and crash recovery of the *same* visit reuse the key (so a finished effect is replayed,
     * never repeated); a loop that comes back to the step gets a new key, so its second pass really runs.
     */
    private fun stepKey(run: WorkflowRun, step: WorkflowStep): String {
        val visit = run.steps[step.id]?.visit ?: 1
        return if (visit <= 1) "wf:${run.runId}:${step.id}" else "wf:${run.runId}:${step.id}:v$visit"
    }

    private fun nextOf(run: WorkflowRun, step: WorkflowStep): String? = step.next ?: run.definition.following(step)?.id

    private fun resolveInputs(run: WorkflowRun, step: WorkflowStep): Map<String, JsonNode> {
        val out = linkedMapOf<String, JsonNode>()
        for ((name, ref) in step.inputs) value(run, ref)?.takeIf { !it.isNull }?.let { out[name] = it }
        return out
    }

    private fun value(run: WorkflowRun, ref: ValueRef): JsonNode? = when (ref) {
        is ValueRef.Literal -> ref.value
        is ValueRef.Input -> descend(run.input, ref.path)
        is ValueRef.Step -> run.steps[ref.stepId]?.takeIf { it.status == StepStatus.SUCCEEDED }?.output?.let { descend(it, ref.path) }
    }

    private fun descend(start: JsonNode, path: String): JsonNode? {
        var n: JsonNode = start
        if (path.isEmpty()) return n
        for (p in path.split('.')) {
            n = when {
                n.isObject -> n.get(p) ?: return null
                n.isArray -> p.toIntOrNull()?.let { i -> if (i in 0 until n.size()) n.get(i) else null } ?: return null
                else -> return null
            }
        }
        return n
    }

    // ───────────────────────────── state transitions (pure, run inside compare-and-set) ─────────────────────────────

    /** The result of one atomic change: the stored run, the step whose job to publish (if any), whether to publish a compensation job. */
    private class Transition(val run: WorkflowRun, val publishStep: String?, val publishCompensation: Boolean, val event: String?)

    private fun applyOutcome(
        cur: WorkflowRun, step: WorkflowStep, attempt: Int, inputs: Map<String, JsonNode>, outcome: StepOutcome, now: Instant
    ): Transition? {
        if (cur.status.terminal) return null
        val st = cur.steps[step.id] ?: return null
        if (cur.currentStepId != step.id || st.status != StepStatus.RUNNING || st.attempt != attempt) return null // superseded (sweeper reset, cancel, timeout)
        val limits = cur.definition.limits.coerceAtMost(ceiling)
        val inputNode: JsonNode = json.createObjectNode().also { o -> inputs.forEach { (k, v) -> o.set<JsonNode>(k, v) } }

        return when (outcome) {
            is StepOutcome.Advance -> {
                if (outcome.output.toString().length > limits.maxPayloadBytes) return failStep(cur, step, st, inputNode, WorkflowErrorCodes.LIMIT_EXCEEDED, "Step output exceeds ${limits.maxPayloadBytes} bytes", now)
                val done = st.copy(
                    status = StepStatus.SUCCEEDED, input = inputNode, output = outcome.output, finishedAt = now,
                    simulated = outcome.simulated, dryRunLevel = outcome.level, errorCode = null, errorMessage = null
                )
                val compensable = if (step.kind == StepKind.ACTION && step.compensationActionRef != null && !outcome.simulated && cur.mode == ExecutionMode.LIVE) cur.compensable + step.id else cur.compensable
                advance(cur.withStep(done).copy(compensable = compensable), outcome.next, now)
            }
            is StepOutcome.Finish -> {
                val done = st.copy(status = StepStatus.SUCCEEDED, input = inputNode, output = outcome.output, finishedAt = now)
                advance(cur.withStep(done), null, now)
            }
            is StepOutcome.Retry -> {
                val w = st.copy(
                    status = StepStatus.RETRY_WAIT, input = inputNode, errorCode = outcome.code, errorMessage = outcome.message?.take(300), wakeAt = now.plus(outcome.delay),
                    attempt = if (outcome.consumesAttempt) st.attempt else (st.attempt - 1).coerceAtLeast(0)
                )
                Transition(cur.withStep(w).copy(status = WorkflowRunStatus.WAITING, updatedAt = now), null, false, "STEP_RETRY")
            }
            is StepOutcome.Wait -> {
                val w = st.copy(status = StepStatus.WAITING, input = inputNode, wakeAt = outcome.wakeAt)
                Transition(cur.withStep(w).copy(status = WorkflowRunStatus.WAITING, updatedAt = now), null, false, null)
            }
            is StepOutcome.WaitApproval -> {
                val w = st.copy(status = StepStatus.WAITING, input = inputNode, approvalId = outcome.approvalId, wakeAt = null)
                Transition(cur.withStep(w).copy(status = WorkflowRunStatus.WAITING, updatedAt = now), null, false, "WAITING_APPROVAL")
            }
            is StepOutcome.Fail -> failStep(cur, step, st, inputNode, outcome.code, outcome.message, now)
        }
    }

    /** A step failed for good: continue at its `onError` step if it has one, otherwise fail the run. */
    private fun failStep(cur: WorkflowRun, step: WorkflowStep, st: StepState, input: JsonNode, code: String, message: String?, now: Instant): Transition {
        val failedState = st.copy(status = StepStatus.FAILED, input = input, errorCode = code, errorMessage = message?.take(300), finishedAt = now)
        val withStep = cur.withStep(failedState)
        return if (step.onError != null) advance(withStep, step.onError, now).let { Transition(it.run, it.publishStep, it.publishCompensation, "STEP_FAILED_ROUTED") }
        else fail(withStep, code, message ?: "Step ${step.id} failed")
    }

    private fun advance(cur: WorkflowRun, next: String?, now: Instant): Transition {
        if (next == null) return Transition(cur.copy(status = WorkflowRunStatus.SUCCEEDED, currentStepId = null, finishedAt = now, updatedAt = now), null, false, "SUCCEEDED")
        val limits = cur.definition.limits.coerceAtMost(ceiling)
        if (cur.stepExecutions + 1 > limits.maxStepExecutions) return fail(cur, WorkflowErrorCodes.LIMIT_EXCEEDED, "More than ${limits.maxStepExecutions} step executions (loop?)", now)
        // entering a step always starts a fresh visit (a loop may come back to a step that already succeeded once)
        val moved = cur.copy(
            status = WorkflowRunStatus.PENDING, currentStepId = next, stepExecutions = cur.stepExecutions + 1, updatedAt = now,
            steps = cur.steps + (next to StepState(next, StepStatus.PENDING, visit = (cur.steps[next]?.visit ?: 0) + 1))
        )
        return Transition(moved, next, false, null)
    }

    private fun fail(cur: WorkflowRun, code: String, message: String, now: Instant = clock.instant()): Transition {
        val comp = if (cur.mode == ExecutionMode.LIVE && cur.compensable.isNotEmpty()) CompensationState.IN_PROGRESS else CompensationState.NONE
        return Transition(
            cur.copy(status = WorkflowRunStatus.FAILED, errorCode = code, errorMessage = message.take(300), compensation = comp, finishedAt = now, updatedAt = now),
            null, comp == CompensationState.IN_PROGRESS, "FAILED"
        )
    }

    /** Publishes / audits whatever a committed [Transition] asks for. Runs after the compare-and-set, never inside it. */
    private fun afterTransition(t: Transition) {
        t.publishStep?.let { publish(t.run.tenantId, t.run.runId, it) }
        if (t.publishCompensation) publish(t.run.tenantId, t.run.runId, WorkflowJob.COMPENSATE)
        t.event?.let { auditRun(t.run, it) }
    }

    private fun publish(tenantId: UUID, runId: UUID, stepId: String): Boolean = try {
        queue.publish(WorkflowJob.forStep(tenantId, runId, stepId)); true
    } catch (e: Exception) {
        // The state is already saved; the sweeper re-publishes runs that make no progress.
        log.log(System.Logger.Level.WARNING, "Publishing a workflow job failed: ${e.javaClass.simpleName}")
        false
    }

    // ───────────────────────────── approval resume ─────────────────────────────

    private fun resumeFromApproval(a: Approval) {
        val runId = a.source.workflowRunId ?: return
        val stepId = a.source.stepId ?: return
        if (!a.status.terminal || a.status == ApprovalStatus.CANCELLED) return
        val now = clock.instant()
        val t = try {
            mutate(a.tenantId, runId) { cur ->
                if (cur.status.terminal || cur.currentStepId != stepId) return@mutate null
                val st = cur.steps[stepId] ?: return@mutate null
                if (st.status != StepStatus.WAITING || st.approvalId != a.id) return@mutate null
                val step = cur.definition.step(stepId) ?: return@mutate null
                val spec = step.approval ?: return@mutate null
                when (a.status) {
                    ApprovalStatus.APPROVED -> {
                        val out = json.createObjectNode().put("approved", true).put("approvals", a.approvals)
                        advance(cur.withStep(st.copy(status = StepStatus.SUCCEEDED, output = out, finishedAt = now)), nextOf(cur, step), now)
                    }
                    else -> {
                        val code = if (a.status == ApprovalStatus.EXPIRED) WorkflowErrorCodes.APPROVAL_EXPIRED else WorkflowErrorCodes.APPROVAL_REJECTED
                        val route = if (a.status == ApprovalStatus.EXPIRED) spec.onExpire else spec.onReject
                        val failed = cur.withStep(st.copy(status = StepStatus.FAILED, errorCode = code, errorMessage = "Approval ${a.status.name.lowercase()}", finishedAt = now))
                        if (route != null) advance(failed, route, now) else fail(failed, code, "Approval ${a.status.name.lowercase()}", now)
                    }
                }
            }
        } catch (e: ContendedException) { return } ?: return
        afterTransition(t)
    }

    // ───────────────────────────── compensation ─────────────────────────────

    private fun runCompensation(run: WorkflowRun) {
        var current = run
        val ctx = ActionContext(run.tenantId, run.createdBy, run.workspaceId, run.appId, "wf:${run.runId}:comp")
        var allOk = true
        for (stepId in run.compensable.reversed()) {
            val st = current.steps[stepId] ?: continue
            if (st.compensated) continue
            val step = run.definition.step(stepId) ?: continue
            val ok = compensateStep(ctx, run, step, st)
            if (ok) {
                val upd = try { mutate(run.tenantId, run.runId) { cur -> cur.steps[stepId]?.let { s -> Transition(cur.withStep(s.copy(compensated = true)).copy(updatedAt = clock.instant()), null, false, null) } } } catch (e: ContendedException) { null }
                if (upd != null) current = upd.run
            } else allOk = false
        }
        try {
            mutate(run.tenantId, run.runId) { cur ->
                if (cur.compensation != CompensationState.IN_PROGRESS) null
                else Transition(cur.copy(compensation = if (allOk) CompensationState.DONE else CompensationState.PARTIAL, updatedAt = clock.instant()), null, false, if (allOk) "COMPENSATED" else "COMPENSATION_PARTIAL")
            }?.let { afterTransition(it) }
        } catch (e: ContendedException) { /* the sweeper will resume */ }
    }

    private fun compensateOne(run: WorkflowRun, step: WorkflowStep, inputs: Map<String, JsonNode>) {
        val ctx = ActionContext(run.tenantId, run.createdBy, run.workspaceId, run.appId, "wf:${run.runId}:comp")
        val st = StepState(step.id, StepStatus.SUCCEEDED, input = json.createObjectNode().also { o -> inputs.forEach { (k, v) -> o.set<JsonNode>(k, v) } })
        compensateStep(ctx, run, step, st)
    }

    private fun compensateStep(ctx: ActionContext, run: WorkflowRun, step: WorkflowStep, st: StepState): Boolean {
        val ref = step.compensationActionRef ?: return true
        val inputs = linkedMapOf<String, JsonNode>()
        st.input?.takeIf { it.isObject }?.let { o -> o.propertyNames().forEach { inputs[it] = o.get(it) } }
        val res = actions.execute(
            ctx,
            ActionRequest(
                actionId = ref, inputs = inputs, idempotencyKey = "wf:${run.runId}:${step.id}:comp",
                trigger = TriggerInfo(TriggerKind.WORKFLOW_STEP, "${run.workflowId}.${step.id}.compensate", run.runId.toString()), callDepth = run.depth, mode = ExecutionMode.LIVE
            )
        )
        return res is ActionResult.Ok
    }

    // ───────────────────────────── dead letters and sweeper ─────────────────────────────

    /** Fails the run behind a dead-lettered message so it does not hang. Returns true if the message can be acked. */
    fun failFromDeadLetter(body: String): Boolean {
        val job = WorkflowJob.decode(body) ?: run { log.log(System.Logger.Level.ERROR, "Dead-lettered message is not a valid job"); return true }
        val t = try {
            mutate(job.tenantId, job.runId) { cur -> if (cur.status.terminal) null else fail(cur, WorkflowErrorCodes.DEAD_LETTERED, "The job could not be processed and was dead-lettered") }
        } catch (e: ContendedException) { return false }
        t?.let { afterTransition(it) }
        return true
    }

    /**
     * Time-driven work, to be called every few seconds by a platform timer (C0 wiring). Safe on several nodes at once: every action here is
     * a compare-and-set or an idempotent publish. This is where waits, retry backoffs, lost messages, crashed workers, overdue runs and
     * approval decisions that missed their callback are handled — so the queue itself never needs delayed delivery.
     */
    fun sweep(limit: Int = 100): SweepReport {
        val now = clock.instant()
        var timers = 0; var retries = 0; var reconciled = 0; var republished = 0; var timedOut = 0; var comp = 0

        for (r in runs.dueTimers(now, limit)) {
            val st = r.currentStep ?: continue
            val step = r.definition.step(st.stepId) ?: continue
            if (st.status == StepStatus.RETRY_WAIT) { if (publish(r.tenantId, r.runId, st.stepId)) retries++; continue }
            val t = try {
                mutate(r.tenantId, r.runId) { cur ->
                    val s = cur.currentStep
                    if (cur.status.terminal || s == null || s.stepId != st.stepId || s.status != StepStatus.WAITING || s.approvalId != null || s.wakeAt?.isAfter(now) != false) return@mutate null
                    advance(cur.withStep(s.copy(status = StepStatus.SUCCEEDED, output = json.createObjectNode().put("waitedUntil", s.wakeAt.toString()), finishedAt = now)), nextOf(cur, step), now)
                }
            } catch (e: ContendedException) { null }
            if (t != null) { afterTransition(t); timers++ }
        }

        approvals?.let { svc ->
            for (r in runs.waitingApprovals(limit)) {
                val id = r.currentStep?.approvalId ?: continue
                val a = svc.find(r.tenantId, id) ?: continue
                if (a.status.terminal) { resumeFromApproval(a); reconciled++ }
            }
        }
        val expired = approvals?.expireDue(limit) ?: 0

        for (r in runs.stale(now.minus(staleAfter), limit)) {
            if (r.status.terminal) {
                if (r.compensation == CompensationState.IN_PROGRESS && publish(r.tenantId, r.runId, WorkflowJob.COMPENSATE)) comp++
                continue
            }
            val limits = r.definition.limits.coerceAtMost(ceiling)
            if (now.isAfter(r.createdAt.plus(limits.maxDuration))) {
                val t = try { mutate(r.tenantId, r.runId) { cur -> if (cur.status.terminal) null else fail(cur, WorkflowErrorCodes.TIMEOUT, "Workflow exceeded ${limits.maxDuration}") } } catch (e: ContendedException) { null }
                if (t != null) { afterTransition(t); timedOut++ }
                continue
            }
            val st = r.currentStep ?: continue
            when (st.status) {
                StepStatus.PENDING -> if (publish(r.tenantId, r.runId, st.stepId)) republished++
                StepStatus.RUNNING -> {
                    // the worker died mid-step: back to PENDING (attempt kept) and nudge; the action's idempotency key prevents a second effect
                    val t = try {
                        mutate(r.tenantId, r.runId) { cur ->
                            val s = cur.currentStep
                            if (cur.status.terminal || s == null || s.stepId != st.stepId || s.status != StepStatus.RUNNING || s.attempt != st.attempt) null
                            else Transition(cur.withStep(s.copy(status = StepStatus.RETRY_WAIT, wakeAt = now)).copy(status = WorkflowRunStatus.WAITING, updatedAt = now), st.stepId, false, null)
                        }
                    } catch (e: ContendedException) { null }
                    if (t != null) { afterTransition(t); republished++ }
                }
                else -> Unit // WAITING / RETRY_WAIT are handled by their timers above
            }
        }
        return SweepReport(timers, retries, reconciled, republished, timedOut, comp, expired)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private class ContendedException : RuntimeException(null, null, false, false)

    /**
     * Read-modify-write with compare-and-set. [f] returns the change to make, or null for "nothing to do" (the run moved on, the step was
     * superseded...). Returns the committed transition, or null when [f] declined or the run is gone. Throws [ContendedException] if the
     * row kept changing under us.
     */
    private fun mutate(tenantId: UUID, runId: UUID, f: (WorkflowRun) -> Transition?): Transition? {
        repeat(6) {
            val cur = runs.get(tenantId, runId) ?: return null
            val t = f(cur) ?: return null
            if (runs.compareAndSet(cur, t.run)) return Transition(t.run.copy(version = cur.version + 1), t.publishStep, t.publishCompensation, t.event)
        }
        throw ContendedException()
    }

    private fun load(tenantId: UUID, runId: UUID): WorkflowRun? = try { runs.get(tenantId, runId) } catch (e: Exception) { null }

    private fun mayView(ctx: ActionContext, run: WorkflowRun): Boolean {
        if (run.createdBy.userId == ctx.actor.userId) return true
        val d = try { access.check(ctx, AccessRequest(LogicPermissions.WORKFLOW_MANAGE, ResourceKind.WORKFLOW_RUN, run.runId.toString(), run.appId, run.mode)) } catch (e: Exception) { null }
        return d is AuthorizationDecision.Allowed
    }

    private fun rateLimit(tenantId: UUID, scope: RateScope): WorkflowResult.Failed? =
        when (val d = try { limiter.tryAcquire(tenantId, scope) } catch (e: Exception) { null }) {
            is RateDecision.Allowed -> null
            is RateDecision.Limited -> WorkflowResult.Failed(WorkflowErrorCodes.RATE_LIMITED, "Too many workflow starts for this tenant, retry in ${d.retryAfter.toMillis()} ms", true)
            null -> WorkflowResult.Failed(WorkflowErrorCodes.DEPENDENCY_UNAVAILABLE, "Rate limiter is unavailable", true)
        }

    private fun gate(ctx: ActionContext): WorkflowResult.Failed? = when (try { tenants.isEnabled(ctx.tenantId) } catch (e: Exception) { null }) {
        true -> null
        false -> WorkflowResult.Failed(WorkflowErrorCodes.TENANT_DISABLED, "Tenant is disabled")
        null -> WorkflowResult.Failed(WorkflowErrorCodes.DEPENDENCY_UNAVAILABLE, "Tenant state could not be evaluated", true)
    }

    private fun deny(ctx: ActionContext, req: AccessRequest): WorkflowResult.Failed? =
        when (val d = try { access.check(ctx, req) } catch (e: Exception) { null }) {
            is AuthorizationDecision.Allowed -> null
            is AuthorizationDecision.Denied -> {
                try { audit.record(auditRecord("DENIED", ctx.tenantId, ctx.actor, ctx.projectId, null, req.resourceId, req.mode, WorkflowErrorCodes.FORBIDDEN, mapOf("permission" to req.permission, "reason" to d.reason.take(100)))) } catch (e: Exception) { /* best effort */ }
                WorkflowResult.Failed(WorkflowErrorCodes.FORBIDDEN, "You do not have permission to run this workflow")
            }
            null -> WorkflowResult.Failed(WorkflowErrorCodes.DEPENDENCY_UNAVAILABLE, "Authorization could not be evaluated", true)
        }

    private fun auditRun(run: WorkflowRun, event: String) {
        try {
            audit.record(auditRecord(event, run.tenantId, run.createdBy, run.appId, run.runId, run.workflowId, run.mode, run.errorCode, mapOf("workflowId" to run.workflowId, "stepId" to (run.currentStepId ?: ""))))
        } catch (e: Exception) { log.log(System.Logger.Level.WARNING, "Workflow audit failed: ${e.javaClass.simpleName}") }
    }

    private fun auditRecord(
        event: String, tenantId: UUID, actor: ActionActor?, appId: UUID?, runId: UUID?, resourceId: String?, mode: ExecutionMode,
        errorCode: String? = null, attrs: Map<String, String> = emptyMap()
    ) = LogicAuditRecord(AuditDomains.WORKFLOW, event, tenantId, actor, appId, "workflow_run", resourceId ?: runId?.toString(), runId?.toString(), mode, errorCode, attrs, clock.instant())

    private fun <T> fail(code: String, message: String, retryable: Boolean = false): WorkflowResult<T> = WorkflowResult.Failed(code, message, retryable)

    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.propertyNames().sorted().joinToString(",", "{", "}") { "${it.length}:$it=${canonical(n.get(it))}" }
        n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private val KEY = Regex("^[A-Za-z0-9._:-]{1,128}$") }
}

/** Pulls messages from the queue and hands them to the engine. Run one or more of these per node (virtual threads / a Spring listener). */
class WorkflowWorker(private val engine: WorkflowEngine, private val queue: WorkflowQueue) {
    private val log = System.getLogger(WorkflowWorker::class.java.name)

    /** Processes at most one message. @return false when the queue was empty. */
    fun runOnce(): Boolean {
        val lease = queue.poll() ?: return false
        val job = WorkflowJob.decode(lease.body)
        if (job == null) {
            log.log(System.Logger.Level.ERROR, "Rejecting a malformed workflow message to the dead-letter queue")
            queue.nack(lease, requeue = false)
            return true
        }
        val outcome = try { engine.process(job) } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Unexpected failure while processing a job: ${e.javaClass.name}")
            ProcessOutcome.RETRY
        }
        if (outcome == ProcessOutcome.DONE) queue.ack(lease) else queue.nack(lease, requeue = true)
        return true
    }

    /** Drains the queue (handy for tests and for batch nodes). Bounded so a message loop cannot spin forever. */
    fun runUntilIdle(maxMessages: Int = 10_000): Int {
        var n = 0
        while (n < maxMessages && runOnce()) n++
        return n
    }

    /** Dead-letter consumer: fails the runs of messages that could not be processed. */
    fun drainDeadLetters(max: Int = 1000): Int {
        var n = 0
        while (n < max) {
            val lease = queue.pollDeadLetter() ?: break
            if (engine.failFromDeadLetter(lease.body)) queue.ackDeadLetter(lease)
            n++
        }
        return n
    }
}
