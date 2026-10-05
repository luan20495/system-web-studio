package com.systemwebstudio.logic.action

import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One element of a [ActionRuntime.dispatch] fan-out. */
data class DispatchOutcome(val ref: ActionRef, val result: ActionResult)

/**
 * The single entry point for running actions. Expected callers (all later work): an HTTP controller for UI events,
 * a queue worker, the future workflow engine, the scheduler. They build an [ActionContext] on the server side.
 */
interface ActionRuntime {
    /** Never throws for expected failures — they come back as [ActionResult.Failed]. */
    fun execute(ctx: ActionContext, request: ActionRequest): ActionResult

    /**
     * Runs every action the AppDefinition binds to [event]. Each ref runs independently (one failure does not stop the
     * others) with idempotency key `evt:<event.id>:<ref.id>`, so re-delivering the same event is safe. Only payload
     * entries that the target action declares as inputs are forwarded.
     */
    fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome>
}

/**
 * Pipeline (order matters — cheapest and least-revealing checks first, side effects last):
 *
 *  1. request sanity (idempotency-key shape)
 *  2. tenant-scoped definition lookup  → UNKNOWN_ACTION (also for other tenants' ids and disabled actions)
 *  3. authorize                        → FORBIDDEN       (default deny; audited DENIED)
 *  4. handler for the type             → UNSUPPORTED_ACTION_TYPE
 *  5. call depth / definition valid    → LIMIT_EXCEEDED / INVALID_DEFINITION
 *  6. bind input (size, depth, types)  → LIMIT_EXCEEDED / INVALID_INPUT
 *  7. idempotency begin (CAS)          → replay / ACTION_IN_PROGRESS / IDEMPOTENCY_KEY_REUSED
 *  8. audit STARTED (fail closed)      → AUDIT_UNAVAILABLE (retryable)
 *  9. handler with timeout             → TIMEOUT / HANDLER_ERROR / handler result
 * 10. complete run state, audit terminal phase (best effort: the action already happened)
 */
class DefaultActionRuntime(
    private val definitions: ActionDefinitionProvider,
    private val handlers: ActionHandlerRegistry,
    private val authorizer: ActionAuthorizer,
    private val runs: ActionRunStore,
    private val audit: ActionAuditPort,
    private val bindings: ActionBindingPort? = null,
    private val ceiling: ActionLimits = ActionLimits(),
    private val clock: Clock = Clock.systemUTC(),
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
) : ActionRuntime {

    private val log = System.getLogger(DefaultActionRuntime::class.java.name)

    override fun execute(ctx: ActionContext, request: ActionRequest): ActionResult {
        val started = clock.instant()
        val key = request.idempotencyKey
        if (key != null && !IDEMPOTENCY_KEY.matches(key)) {
            return reject(ctx, request, null, failed(ActionErrorCodes.IDEMPOTENCY_KEY_INVALID, "Idempotency key must be 1-128 chars of [A-Za-z0-9._:-]"))
        }

        val def = definitions.find(ctx.tenantId, request.actionId)
            ?.takeIf { it.tenantId == ctx.tenantId && it.enabled && it.id == request.actionId }
            ?: return reject(ctx, request, null, failed(ActionErrorCodes.UNKNOWN_ACTION, "Action not found"))

        when (val decision = safely { authorizer.authorize(ctx, def) }) {
            is AuthorizationDecision.Allowed -> Unit
            is AuthorizationDecision.Denied -> {
                recordBestEffort(entry(AuditPhase.DENIED, ctx, request, def, null, errorCode = ActionErrorCodes.FORBIDDEN, detail = decision.reason))
                return failed(ActionErrorCodes.FORBIDDEN, "You do not have permission to run this action")
            }
            null -> return failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Authorization could not be evaluated", retryable = true)
        }

        val handler = handlers[def.type]
            ?: return reject(ctx, request, def, failed(ActionErrorCodes.UNSUPPORTED_ACTION_TYPE, "No handler for ${def.type}"))

        val limits = def.limits.coerceAtMost(ceiling)
        if (request.callDepth < 0 || request.callDepth > limits.maxCallDepth) {
            return reject(ctx, request, def, failed(ActionErrorCodes.LIMIT_EXCEEDED, "Call depth exceeds ${limits.maxCallDepth}"))
        }
        val issues = ActionDefinitionValidator.validate(def, handler)
        if (issues.isNotEmpty()) {
            return reject(
                ctx, request, def,
                failed(ActionErrorCodes.INVALID_DEFINITION, "Action definition is invalid", details = issues.associate { it.path to it.message })
            )
        }

        val input = when (val bound = ActionInputBinder.bind(def, request.inputs, limits)) {
            is BindResult.Bound -> bound.input
            is BindResult.Rejected -> return reject(ctx, request, def, bound.failure)
        }

        if (key == null && def.idempotency == IdempotencyPolicy.REQUIRED) {
            return reject(ctx, request, def, failed(ActionErrorCodes.IDEMPOTENCY_KEY_REQUIRED, "This action requires an idempotency key"))
        }
        val effectiveKey = key?.takeIf { def.idempotency != IdempotencyPolicy.NONE }

        val runKey = effectiveKey?.let { RunKey(ctx.tenantId, def.id, it) }
        val runId = if (runKey != null) {
            when (val begin = safely { runs.begin(runKey, ActionInputBinder.fingerprint(def.id, input), clock.instant()) }) {
                is RunBegin.Started -> begin.runId
                is RunBegin.Replay -> {
                    recordBestEffort(entry(AuditPhase.REPLAYED, ctx, request, def, null))
                    return begin.result
                }
                is RunBegin.InProgress -> return failed(ActionErrorCodes.ACTION_IN_PROGRESS, "The same request is already running", retryable = true)
                RunBegin.KeyReused -> return reject(ctx, request, def, failed(ActionErrorCodes.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used with different input"))
                null -> return failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Run state is unavailable", retryable = true)
            }
        } else UUID.randomUUID().toString()

        val run = ActionRun(runId, effectiveKey, request.trigger, request.callDepth, started)

        if (safely { audit.record(entry(AuditPhase.STARTED, ctx, request, def, run)) } == null) {
            // Fail closed: no un-audited side effects. Nothing ran, so the same key may be retried.
            val failure = failed(ActionErrorCodes.AUDIT_UNAVAILABLE, "Audit trail is unavailable", retryable = true)
            if (runKey != null) safely { runs.complete(runKey, runId, failure, clock.instant()) }
            return failure
        }

        val result = runWithTimeout(handler, ctx, def, input, run, limits.timeout)

        if (runKey != null) {
            val owned = safely { runs.complete(runKey, runId, result, clock.instant()) }
            if (owned == false) log.log(System.Logger.Level.WARNING, "Run {0} lost ownership before completing (swept?)", runId)
        }
        val terminal = if (result is ActionResult.Ok) AuditPhase.SUCCEEDED else AuditPhase.FAILED
        val duration = Duration.between(started, clock.instant()).toMillis()
        recordBestEffort(
            entry(terminal, ctx, request, def, run, (result as? ActionResult.Failed), durationMillis = duration)
        )
        return result
    }

    override fun dispatch(ctx: ActionContext, event: Event): List<DispatchOutcome> {
        val refs = bindings?.let { safely { it.refsFor(ctx, event.name) } }.orEmpty().filter { it.trigger == event.name }
        return refs.map { ref ->
            val declared = definitions.find(ctx.tenantId, ref.actionId)?.inputs?.map { it.name }?.toSet().orEmpty()
            val request = ActionRequest(
                actionId = ref.actionId,
                inputs = event.payload.filterKeys { it in declared },
                idempotencyKey = "evt:${event.id}:${ref.id}".take(128),
                trigger = event.trigger()
            )
            DispatchOutcome(ref, execute(ctx, request))
        }
    }

    private fun runWithTimeout(
        handler: ActionHandler, ctx: ActionContext, def: ActionDefinition, input: ActionInput, run: ActionRun, timeout: Duration
    ): ActionResult {
        val future = try {
            executor.submit(Callable { handler.execute(ctx, def, input, run) })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            return failed(ActionErrorCodes.DEPENDENCY_UNAVAILABLE, "Action executor is saturated", retryable = true)
        }
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // The side effect may or may not have happened: repeating is only safe when a key de-duplicates it.
            failed(ActionErrorCodes.TIMEOUT, "Action timed out after ${timeout.toMillis()} ms", retryable = !def.type.mutatesState || run.idempotencyKey != null)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            failed(ActionErrorCodes.INTERRUPTED, "Action was interrupted", retryable = !def.type.mutatesState || run.idempotencyKey != null)
        } catch (e: CancellationException) {
            failed(ActionErrorCodes.INTERRUPTED, "Action was cancelled", retryable = false)
        } catch (e: ExecutionException) {
            // The message is deliberately not propagated to the caller.
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
        errorCode = errorCode, retryable = failure?.retryable, durationMillis = durationMillis, detail = detail?.take(200), at = clock.instant()
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
        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")
    }
}
