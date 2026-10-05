package com.systemwebstudio.logic.action

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class ChainOn { SUCCESS, ERROR }

/** Result of an action chained through `onSuccess` / `onError` (flattened: nested chains appear in execution order). */
data class FollowUp(val actionId: String, val on: ChainOn, val result: ActionResult)

/** A run plus everything that ran because of it. [result] is the triggering action's own result. */
data class ActionExecution(val result: ActionResult, val followUps: List<FollowUp> = emptyList())

/** One element of a [ActionRuntime.dispatch] fan-out. */
data class DispatchOutcome(val ref: ActionRef, val execution: ActionExecution) {
    val result: ActionResult get() = execution.result
}

/**
 * The single entry point for running actions. Callers (all build an [ActionContext] on the server side): an HTTP controller for UI events
 * (C0/C5 wiring), the workflow engine, the scheduler.
 */
interface ActionRuntime {
    /** One action, no chaining. Never throws for expected failures — they come back as [ActionResult.Failed]. Used by workflow steps. */
    fun execute(ctx: ActionContext, request: ActionRequest): ActionResult

    /** [execute] plus the definition's `onSuccess` / `onError` chain (bounded by call depth and a per-run action budget). */
    fun run(ctx: ActionContext, request: ActionRequest): ActionExecution

    /**
     * Event → ActionRef → run. Runs every action the AppDefinition binds to (section, event type). Each ref runs independently (one failure
     * does not stop the others) with idempotency key `evt:<event.id>:<ref.id>`, so re-delivering the same event is safe. Event payload data
     * reaches an action only through that action's declared `inputMapping`.
     */
    fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome>
}

/**
 * Pipeline (order matters — cheapest and least-revealing checks first, side effects last):
 *
 *  1. idempotency-key shape
 *  2. tenant gate                       → TENANT_DISABLED
 *  3. tenant- and app-scoped definition → UNKNOWN_ACTION (also for other tenants'/apps' ids and disabled actions)
 *  3b. UI-bound runs need a declared trigger (workflow-invoked actions do not) → UNKNOWN_ACTION
 *  4. authorization (APP_USE, ACTION_EXECUTE, DATA_MUTATE for data types, declared permission, WORKFLOW_EXECUTE for START_WORKFLOW) → FORBIDDEN (default deny, audited)
 *  5. handler for the type              → UNSUPPORTED_ACTION_TYPE
 *  6. call depth / definition valid     → LIMIT_EXCEEDED / INVALID_DEFINITION
 *  7. resolve + bind input              → LIMIT_EXCEEDED / INVALID_INPUT
 *  8. TEST mode: preview (no run state, no idempotency, no side effect) → WouldRun
 *  9. idempotency begin (CAS) on the **derived** key → replay / ACTION_IN_PROGRESS / IDEMPOTENCY_KEY_REUSED
 * 10. audit STARTED (fail closed)       → AUDIT_UNAVAILABLE (retryable)
 * 11. handler with timeout              → TIMEOUT / HANDLER_ERROR / handler result
 * 12. complete run state, audit terminal phase (best effort: the action already happened)
 *
 * The runtime never trusts the UI: whatever the client sent is untrusted data, permissions are resolved by [AccessPort] from the server-side
 * context, and the downstream ports (data gateway, workflow) authorize again.
 */
class DefaultActionRuntime(
    private val definitions: ActionDefinitionProvider,
    private val handlers: ActionHandlerRegistry,
    private val access: AccessPort,
    private val tenants: TenantGate,
    private val runs: ActionRunStore,
    private val audit: ActionAuditPort,
    private val resolver: InputResolver,
    private val bindings: ActionBindingPort? = null,
    private val ceiling: ActionLimits = ActionLimits(),
    private val clock: Clock = Clock.systemUTC(),
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
    /** Max number of actions started by `onSuccess`/`onError` chaining per [run]. */
    private val maxChainActions: Int = 16
) : ActionRuntime {

    private val log = System.getLogger(DefaultActionRuntime::class.java.name)

    private class Outcome(val result: ActionResult, val def: ActionDefinition?)

    override fun execute(ctx: ActionContext, request: ActionRequest): ActionResult = executeInternal(ctx, request).result

    override fun run(ctx: ActionContext, request: ActionRequest): ActionExecution = runInternal(ctx, request, intArrayOf(maxChainActions))

    private fun runInternal(ctx: ActionContext, request: ActionRequest, budget: IntArray): ActionExecution {
        val outcome = executeInternal(ctx, request)
        val def = outcome.def
        val result = outcome.result
        // Chains only run after a real (LIVE) run of an authorized action; a TEST run lists the chain in its plan instead.
        if (def == null || request.mode == ExecutionMode.TEST) return ActionExecution(result)
        val (ids, on) = when {
            result is ActionResult.Ok -> def.onSuccess to ChainOn.SUCCESS
            result is ActionResult.Failed && result.code !in NO_CHAIN_CODES -> def.onError to ChainOn.ERROR
            else -> return ActionExecution(result)
        }
        val followUps = mutableListOf<FollowUp>()
        for (id in ids) {
            if (budget[0] <= 0) {
                followUps += FollowUp(id, on, failed(ActionErrorCodes.LIMIT_EXCEEDED, "Chain budget of $maxChainActions actions exhausted"))
                continue
            }
            budget[0]--
            val child = ActionRequest(
                actionId = id,
                idempotencyKey = request.idempotencyKey?.let { derivedKey(it, "${on.name.first().lowercaseChar()}:$id") },
                trigger = request.trigger,
                callDepth = request.callDepth + 1,
                mode = request.mode,
                payload = request.payload,
                previousResult = (result as? ActionResult.Ok)?.output
            )
            val sub = runInternal(ctx, child, budget)
            followUps += FollowUp(id, on, sub.result)
            followUps += sub.followUps
        }
        return ActionExecution(result, followUps)
    }

    private fun executeInternal(ctx: ActionContext, request: ActionRequest): Outcome {
        val started = clock.instant()
        val mode = request.mode
        val key = request.idempotencyKey
        if (key != null && !IdempotencyKeys.isValidClientKey(key)) {
            return Outcome(reject(ctx, request, null, failed(ActionErrorCodes.IDEMPOTENCY_KEY_INVALID, "Idempotency key must be 1-128 chars of [A-Za-z0-9._:-]")), null)
        }

        when (safely { tenants.isEnabled(ctx.tenantId) }) {
            true -> Unit
            false -> return Outcome(reject(ctx, request, null, failed(ActionErrorCodes.TENANT_DISABLED, "Tenant is disabled")), null)
            null -> return Outcome(failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Tenant state could not be evaluated", retryable = true), null)
        }

        val appId = ctx.projectId
            ?: return Outcome(reject(ctx, request, null, failed(ActionErrorCodes.INVALID_INPUT, "Application context is required")), null)

        val lookup = safely { Optional.ofNullable(definitions.find(ctx.tenantId, appId, request.actionId, mode)) }
            ?: return Outcome(failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Action definitions are unavailable", retryable = true), null)
        val def = lookup.orElse(null)
            ?.takeIf { it.tenantId == ctx.tenantId && it.enabled && it.id == request.actionId && (it.appId == null || it.appId == appId) }
            ?: return Outcome(reject(ctx, request, null, failed(ActionErrorCodes.UNKNOWN_ACTION, "Action not found")), null)

        // UI-bound execution needs a declared trigger; actions only a workflow step or a chain invokes have none (D-C4-10).
        if (request.trigger.kind == TriggerKind.UI_EVENT && request.callDepth == 0 && def.trigger == null) {
            return Outcome(reject(ctx, request, def, failed(ActionErrorCodes.UNKNOWN_ACTION, "Action not found")), null)
        }
        if (request.trigger.kind == TriggerKind.UI_EVENT && request.trigger.eventName != null && def.trigger != null &&
            request.trigger.eventName != "${def.trigger.sectionId}.${def.trigger.event.wire}" && request.callDepth == 0
        ) {
            return Outcome(reject(ctx, request, def, failed(ActionErrorCodes.UNKNOWN_ACTION, "Action not found")), null)
        }

        for (check in accessChecks(def, appId, mode)) {
            when (val decision = safely { access.check(ctx, check) }) {
                is AuthorizationDecision.Allowed -> Unit
                is AuthorizationDecision.Denied -> {
                    recordBestEffort(entry(AuditPhase.DENIED, ctx, request, def, null, errorCode = ActionErrorCodes.FORBIDDEN, detail = "${check.permission}: ${decision.reason}"))
                    return Outcome(failed(ActionErrorCodes.FORBIDDEN, "You do not have permission to run this action"), null)
                }
                null -> return Outcome(failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Authorization could not be evaluated", retryable = true), null)
            }
        }

        val handler = handlers[def.type]
            ?: return Outcome(reject(ctx, request, def, failed(ActionErrorCodes.UNSUPPORTED_ACTION_TYPE, "No handler for ${def.type}")), def)

        val limits = def.limits.coerceAtMost(ceiling)
        if (request.callDepth < 0 || request.callDepth > limits.maxCallDepth) {
            return Outcome(reject(ctx, request, def, failed(ActionErrorCodes.LIMIT_EXCEEDED, "Call depth exceeds ${limits.maxCallDepth}")), def)
        }
        val issues = ActionDefinitionValidator.validate(def, handler)
        if (issues.isNotEmpty()) {
            return Outcome(
                reject(ctx, request, def, failed(ActionErrorCodes.INVALID_DEFINITION, "Action definition is invalid", details = issues.associate { it.path to it.message })),
                def
            )
        }

        val resolved = when (val r = resolver.resolve(def, request, ctx, started)) {
            is ResolveResult.Resolved -> r.values
            is ResolveResult.Rejected -> return Outcome(reject(ctx, request, def, r.failure), def)
        }
        val input = when (val bound = ActionInputBinder.bind(def, resolved, limits)) {
            is BindResult.Bound -> bound.input
            is BindResult.Rejected -> return Outcome(reject(ctx, request, def, bound.failure), def)
        }
        val timeout = request.timeout?.takeIf { !it.isNegative && !it.isZero && it < limits.timeout } ?: limits.timeout

        if (mode == ExecutionMode.TEST) {
            val testRunId = UUID.randomUUID().toString()
            // A dry-run still carries a derived key (never the raw one) so the data port's request is well-formed; it is not stored and reserves nothing.
            val testKey = if (def.type.mutatesState) IdempotencyKeys.derive(ctx.tenantId, appId, ctx.actor.userId, def.id, key ?: "test:$testRunId") else null
            val run = ActionRun(testRunId, testKey, request.trigger, request.callDepth, started, ExecutionMode.TEST, appId)
            val result = runWithTimeout(def, run, timeout, dedupes = false) { handler.preview(ctx, def, input, run) }
            recordBestEffort(
                entry(AuditPhase.PREVIEWED, ctx, request, def, run, result as? ActionResult.Failed, durationMillis = Duration.between(started, clock.instant()).toMillis())
            )
            return Outcome(result, def)
        }

        if (key == null && def.idempotency == IdempotencyPolicy.REQUIRED) {
            return Outcome(reject(ctx, request, def, failed(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, "This action requires an idempotency key")), def)
        }
        // Only the derived key is stored, audited or forwarded. Without a client key (OPTIONAL/NONE) the run gets a one-shot key: unique, so it
        // de-duplicates nothing, but every mutating call downstream still carries a well-formed derived key.
        val derived = key?.let { IdempotencyKeys.derive(ctx.tenantId, appId, ctx.actor.userId, def.id, it) }
        val effectiveKey = derived?.takeIf { def.idempotency != IdempotencyPolicy.NONE }

        val runKey = effectiveKey?.let { RunKey(ctx.tenantId, appId, def.id, ctx.actor.userId, it) }
        val runId = if (runKey != null) {
            when (val begin = safely { runs.begin(runKey, ActionInputBinder.fingerprint(def.id, input), clock.instant()) }) {
                is RunBegin.Started -> begin.runId
                is RunBegin.Replay -> {
                    recordBestEffort(entry(AuditPhase.REPLAYED, ctx, request, def, null))
                    return Outcome(begin.result, def)
                }
                is RunBegin.InProgress -> return Outcome(failed(ActionErrorCodes.ACTION_IN_PROGRESS, "The same request is already running", retryable = true), def)
                RunBegin.KeyReused -> return Outcome(
                    reject(ctx, request, def, failed(ActionErrorCodes.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used with different input")), def
                )
                null -> return Outcome(failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Run state is unavailable", retryable = true), def)
            }
        } else UUID.randomUUID().toString()

        val callKey = effectiveKey ?: if (def.type.mutatesState) IdempotencyKeys.forFreshRun(ctx.tenantId, appId, ctx.actor.userId, def.id, runId) else null
        val run = ActionRun(runId, callKey, request.trigger, request.callDepth, started, ExecutionMode.LIVE, appId)

        if (safely { audit.record(entry(AuditPhase.STARTED, ctx, request, def, run)) } == null) {
            // Fail closed: no un-audited side effects. Nothing ran, so the same key may be retried.
            val failure = failed(ActionErrorCodes.AUDIT_UNAVAILABLE, "Audit trail is unavailable", retryable = true)
            if (runKey != null) safely { runs.complete(runKey, runId, failure, clock.instant()) }
            return Outcome(failure, def)
        }

        val result = runWithTimeout(def, run, timeout, dedupes = runKey != null) { handler.execute(ctx, def, input, run) }

        if (runKey != null) {
            val owned = safely { runs.complete(runKey, runId, result, clock.instant()) }
            if (owned == false) log.log(System.Logger.Level.WARNING, "Run {0} lost ownership before completing (swept?)", runId)
        }
        val terminal = if (result is ActionResult.Ok) AuditPhase.SUCCEEDED else AuditPhase.FAILED
        recordBestEffort(
            entry(terminal, ctx, request, def, run, (result as? ActionResult.Failed), durationMillis = Duration.between(started, clock.instant()).toMillis())
        )
        return Outcome(result, def)
    }

    override fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome> {
        val appId = ctx.projectId ?: return emptyList()
        val refs = bindings
            ?.let { b -> safely { Optional.of(b.refsFor(ctx.tenantId, appId, event.sectionId, event.type, event.mode)) } }
            ?.get().orEmpty()
            .filter { it.sectionId == event.sectionId && it.event == event.type }
        return refs.map { ref ->
            val request = ActionRequest(
                actionId = ref.actionId,
                idempotencyKey = derivedKey("evt:${safeSegment(event.id)}", safeSegment(ref.id)),
                trigger = event.trigger(),
                payload = event.payload,
                mode = event.mode
            )
            DispatchOutcome(ref, run(ctx, request))
        }
    }

    private fun accessChecks(def: ActionDefinition, appId: UUID, mode: ExecutionMode): List<AccessRequest> = buildList {
        add(AccessRequest(LogicPermissions.APP_USE, ResourceKind.APP, appId.toString(), appId, mode))
        add(AccessRequest(LogicPermissions.ACTION_EXECUTE, ResourceKind.ACTION, def.id, appId, mode))
        if (def.type in DATA_MUTATING) add(AccessRequest(LogicPermissions.DATA_MUTATE, ResourceKind.ACTION, def.id, appId, mode))
        def.requiredPermission?.let { add(AccessRequest(it, ResourceKind.ACTION, def.id, appId, mode)) }
        if (def.type == ActionType.START_WORKFLOW) {
            def.configString("workflowRef")?.let { add(AccessRequest(LogicPermissions.WORKFLOW_EXECUTE, ResourceKind.WORKFLOW, it, appId, mode)) }
        }
    }

    /** [dedupes]: a client key reserved this run, so repeating it cannot create a second effect (a one-shot fresh-run key does not count). */
    private fun runWithTimeout(def: ActionDefinition, run: ActionRun, timeout: Duration, dedupes: Boolean, block: () -> ActionResult): ActionResult {
        val future = try {
            executor.submit(Callable { block() })
        } catch (e: RejectedExecutionException) {
            return failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Action executor is saturated", retryable = true)
        }
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // The side effect may or may not have happened: repeating is only safe when a key de-duplicates it.
            failed(ActionErrorCodes.TIMEOUT, "Action timed out after ${timeout.toMillis()} ms", retryable = !def.type.mutatesState || dedupes)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            failed(ActionErrorCodes.INTERRUPTED, "Action was interrupted", retryable = !def.type.mutatesState || dedupes)
        } catch (e: CancellationException) {
            failed(ActionErrorCodes.INTERRUPTED, "Action was cancelled", retryable = false)
        } catch (e: ExecutionException) {
            // Only the exception class is logged: the message/stack may embed connection strings or credentials from a downstream.
            log.log(System.Logger.Level.ERROR, "Handler for ${def.type} failed (run ${run.runId}): ${e.cause?.javaClass?.name}")
            failed(ActionErrorCodes.HANDLER_ERROR, "Action failed unexpectedly")
        }
    }

    private fun reject(ctx: ActionContext, request: ActionRequest, def: ActionDefinition?, failure: ActionResult.Failed): ActionResult.Failed {
        recordBestEffort(entry(AuditPhase.REJECTED, ctx, request, def, null, failure))
        return failure
    }

    private fun entry(
        phase: AuditPhase, ctx: ActionContext, request: ActionRequest, def: ActionDefinition?, run: ActionRun?,
        failure: ActionResult.Failed? = null, errorCode: String? = failure?.code, durationMillis: Long? = null, detail: String? = null
    ) = ActionAuditEntry(
        phase = phase, tenantId = ctx.tenantId, actor = ctx.actor, workspaceId = ctx.workspaceId, projectId = ctx.projectId,
        actionId = request.actionId, actionType = def?.type, runId = run?.runId, trigger = request.trigger, requestId = ctx.requestId,
        errorCode = errorCode, retryable = failure?.retryable, durationMillis = durationMillis, detail = detail?.take(200),
        mode = request.mode, at = clock.instant()
    )

    private fun recordBestEffort(entry: ActionAuditEntry) {
        if (safely { audit.record(entry) } == null) log.log(System.Logger.Level.WARNING, "Audit of {0} for action {1} failed", entry.phase, entry.actionId)
    }

    /** Runs a port call; a thrown exception becomes null so the caller can map it to a typed failure. */
    private fun <T : Any> safely(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        log.log(System.Logger.Level.WARNING, "Port call failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private val IDEMPOTENCY_KEY = IdempotencyKeys.CLIENT_KEY
        /** Types that go through the data path and therefore need DATA_MUTATE (`tenant-permission.md` §5). */
        private val DATA_MUTATING = setOf(ActionType.SUBMIT_FORM, ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD, ActionType.CALL_API)
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._-]{1,64}$")

        /** Failures that mean "the action did not get to run" — they must not trigger `onError` actions. */
        private val NO_CHAIN_CODES = setOf(
            ActionErrorCodes.UNKNOWN_ACTION, ActionErrorCodes.UNSUPPORTED_ACTION_TYPE, ActionErrorCodes.FORBIDDEN, ActionErrorCodes.TENANT_DISABLED,
            ActionErrorCodes.INVALID_DEFINITION, ActionErrorCodes.LIMIT_EXCEEDED, ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED,
            ActionErrorCodes.IDEMPOTENCY_KEY_INVALID, ActionErrorCodes.IDEMPOTENCY_KEY_REUSED, ActionErrorCodes.ACTION_IN_PROGRESS,
            ActionErrorCodes.DEPENDENCY_UNAVAILABLE, ActionErrorCodes.AUDIT_UNAVAILABLE
        )

        /** A client-supplied id becomes part of an idempotency key: keep it if it is plain, otherwise replace it by a digest. */
        internal fun safeSegment(s: String): String = if (SAFE_SEGMENT.matches(s)) s else sha256Hex(s).take(32)

        /** `parent` + ":" + `suffix`, shortened with a digest (never truncated, which could collide) when it would exceed 128 chars. */
        internal fun derivedKey(parent: String, suffix: String): String {
            val full = "$parent:$suffix"
            return if (full.length <= 128 && IDEMPOTENCY_KEY.matches(full)) full else full.take(95).filter { it.isLetterOrDigit() || it in "._:-" } + "_" + sha256Hex(full).take(32)
        }

        private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
