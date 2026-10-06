package com.systemwebstudio.wiring

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.logic.action.AccessPort
import com.systemwebstudio.logic.action.ActionAuditPort
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ActionDefinitionProvider
import com.systemwebstudio.logic.action.ActionPorts
import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.ActionRuntime
import com.systemwebstudio.logic.action.DefaultActionRuntime
import com.systemwebstudio.logic.action.InMemoryActionRunStore
import com.systemwebstudio.logic.action.InputResolver
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.StartWorkflowRequest
import com.systemwebstudio.logic.action.TenantGate
import com.systemwebstudio.logic.action.WorkflowStarterPort
import com.systemwebstudio.logic.action.canonical.AppDefinitionSource
import com.systemwebstudio.logic.action.canonical.CanonicalActionCatalog
import com.systemwebstudio.logic.action.handlers.DefaultActionHandlers
import com.systemwebstudio.logic.workflow.InMemoryWorkflowQueue
import com.systemwebstudio.logic.workflow.InMemoryWorkflowRunStore
import com.systemwebstudio.logic.workflow.WorkflowEngine
import com.systemwebstudio.logic.workflow.WorkflowQueue
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import com.systemwebstudio.logic.workflow.WorkflowRuntime
import com.systemwebstudio.logic.workflow.WorkflowWorker
import com.systemwebstudio.logic.workflow.canonical.CanonicalWorkflowCatalog
import com.systemwebstudio.wiring.persistence.JdbcActionRunStore
import com.systemwebstudio.wiring.persistence.JdbcWorkflowRunStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import com.systemwebstudio.access.adapters.AccessPort as C1AccessPort
import com.systemwebstudio.access.adapters.TenantGate as C1TenantGate

/** The C4 runtime as the controllers see it. [actions] and [workflows] are the guarded decorators (D-C0-20); [engine]/[worker] are for the worker only. */
class AppRuntime(
    val actions: ActionRuntime,
    val workflows: WorkflowRuntime,
    val definitions: ActionDefinitionProvider,
    val engine: WorkflowEngine,
    val worker: WorkflowWorker
)

/**
 * The run state of C4 (V29, D-C0-23), selected by `app.workflow.run-store`: `jdbc` (default when the property is absent) = PostgreSQL, `memory` = explicit volatile
 * dev / test override. **Any other value defines no store at all**, so every consumer of [ActionRunStore] / [WorkflowRunStore] fails and the application does not
 * start - there is deliberately no fallback. Kept in its own class so that this rule can be tested without the rest of the runtime.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class RunStoreConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.workflow", name = ["run-store"], havingValue = "jdbc", matchIfMissing = true)
    fun durableActionRunStore(jdbc: JdbcTemplate, json: JsonMapper): ActionRunStore = JdbcActionRunStore(jdbc, json)

    @Bean
    @ConditionalOnProperty(prefix = "app.workflow", name = ["run-store"], havingValue = "jdbc", matchIfMissing = true)
    fun durableWorkflowRunStore(jdbc: JdbcTemplate, json: JsonMapper): WorkflowRunStore = JdbcWorkflowRunStore(jdbc, json)

    @Bean
    @ConditionalOnProperty(prefix = "app.workflow", name = ["run-store"], havingValue = "memory")
    fun volatileActionRunStore(): ActionRunStore = InMemoryActionRunStore()

    @Bean
    @ConditionalOnProperty(prefix = "app.workflow", name = ["run-store"], havingValue = "memory")
    fun volatileWorkflowRunStore(): WorkflowRunStore = InMemoryWorkflowRunStore()
}

/**
 * C0 · wires C4's runtime to C1 (access, tenant gate), C2 (AppDefinition, resolver) and C3 (data gateway, when it exists). Everything is behind
 * `app.workflow.enabled` (default false): with the flag off none of these beans exists and no route is mounted.
 *
 * Run state (V29, D-C0-23): `app.workflow.run-store` selects the `ActionRunStore` / `WorkflowRunStore` - `jdbc` (the default: durable, restart-safe, the only
 * choice for production) or `memory` (explicit dev / test choice, single node, lost on restart; LIVE changes are then refused with `RUNTIME_STORES_VOLATILE`
 * unless `app.workflow.allow-volatile-stores=true`). Any other value leaves the beans undefined and the application does not start: there is no silent fallback to
 * volatile stores. The `WorkflowQueue` is still in memory until the RabbitMQ phase; a lost job is not lost work, the sweeper re-publishes every run whose
 * job vanished (a PENDING / stale run), so a restart only delays - it never drops - a run.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class AppRuntimeConfiguration {

    @Bean
    fun runtimeAppDefinitionSource(definitions: RuntimeAppDefinitions): AppDefinitionSource = RuntimeAppDefinitionSource(definitions)

    @Bean
    fun c4AccessPort(c1: C1AccessPort): AccessPort = C4AccessPortAdapter(c1)

    @Bean
    fun c4TenantGate(c1: C1TenantGate): TenantGate = C4TenantGateAdapter(c1)

    @Bean
    fun actionAuditPort(audit: AuditService): ActionAuditPort = ActionAuditAdapter(audit)

    @Bean
    fun logicAuditPort(audit: AuditService): LogicAuditPort = LogicAuditAdapter(audit)

    @Bean
    fun workflowQueue(): WorkflowQueue = InMemoryWorkflowQueue()

    @Bean
    fun appRuntime(
        json: JsonMapper,
        source: AppDefinitionSource,
        definitions: RuntimeAppDefinitions,
        resolvers: RuntimeResolvers,
        access: AccessPort,
        tenants: TenantGate,
        actionAudit: ActionAuditPort,
        logicAudit: LogicAuditPort,
        actionRuns: ActionRunStore,
        workflowRuns: WorkflowRunStore,
        queue: WorkflowQueue,
        gateways: ObjectProvider<DataGateway>,
        @Value("\${app.workflow.allow-volatile-stores:false}") allowVolatile: Boolean,
        @Value("\${app.workflow.run-store:jdbc}") runStore: String,
        @Value("\${app.workflow.stale-after:PT2M}") staleAfter: String,
        @Value("\${app.workflow.worker-id:}") workerIdProperty: String
    ): AppRuntime {
        require(runStore == "jdbc" || runStore == "memory") { "app.workflow.run-store must be 'jdbc' or 'memory'" }
        val durable = runStore == "jdbc"
        val leaseAfter = Duration.parse(staleAfter)
        require(!leaseAfter.isNegative && leaseAfter >= Duration.ofSeconds(30)) { "app.workflow.stale-after must be at least 30 seconds" }
        // identity of this node in workflow_runs.lease_owner (VARCHAR(64)): one per process, diagnostics and ownership checks only (C4 H-3)
        val workerId = workerIdProperty.ifBlank { "node-" + java.util.UUID.randomUUID().toString().take(8) }
        require(workerId.length <= 64) { "app.workflow.worker-id must be at most 64 characters" }
        val catalog = CanonicalActionCatalog(source)
        // START_WORKFLOW reaches the engine, which is built after the action runtime: a forwarding port closes the loop.
        var engineRef: WorkflowEngine? = null
        val starter = object : WorkflowStarterPort {
            override fun start(ctx: ActionContext, request: StartWorkflowRequest): PortOutcome =
                engineRef?.start(ctx, request) ?: PortOutcome.Failure("DEPENDENCY_UNAVAILABLE", false, "The workflow runtime is not available.")
        }
        val dataPort = ActionDataPortAdapter(definitions, resolvers) { gateways.getIfAvailable() }
        val runtime = DefaultActionRuntime(
            definitions = catalog,
            handlers = DefaultActionHandlers.registry(json, ActionPorts(data = dataPort, notify = null, workflow = starter)),
            access = access,
            tenants = tenants,
            runs = actionRuns,
            audit = actionAudit,
            resolver = InputResolver(json),
            bindings = catalog
        )
        val engine = WorkflowEngine(json, CanonicalWorkflowCatalog(source), runtime, workflowRuns, queue, access, tenants, logicAudit, staleAfter = leaseAfter, workerId = workerId)
        engineRef = engine
        return AppRuntime(
            actions = VolatileActionGuard(runtime, catalog, access, allowVolatile || durable),
            workflows = VolatileWorkflowGuard(engine, access, allowVolatile || durable),
            definitions = catalog,
            engine = engine,
            worker = WorkflowWorker(engine, queue)
        )
    }
}

/** Drains the in-memory queue and runs the sweeper. One thread, bounded work per tick; failures are logged without detail and never stop the schedule. */
@Component
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class WorkflowWorkerRunner(private val runtime: AppRuntime) {
    private val log = System.getLogger(WorkflowWorkerRunner::class.java.name)

    @Scheduled(fixedDelayString = "\${app.workflow.worker-delay-ms:500}")
    fun tick() {
        try {
            var processed = 0
            while (processed < MAX_PER_TICK && runtime.worker.runOnce()) processed++
            runtime.engine.sweep()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Workflow worker tick failed: ${e.javaClass.name}")
        }
    }

    companion object { const val MAX_PER_TICK = 50 }
}

/**
 * Recovery of action runs (V29). A worker that dies between `begin` and `complete` leaves a RUNNING row, which would answer `ACTION_IN_PROGRESS` for ever. The sweep
 * fails a RUNNING run untouched for [staleAfter] (the `ActionRunStore.sweepStale` contract, C4 A-1, D-C0-25): a MUTATING action's run may already have written, so it becomes
 * `IDEMPOTENCY_OUTCOME_UNKNOWN`, `retryable = false` (replayed as it is: no automatic retry, no `onError` chain, the workflow step is not retried); a non-mutating action's run
 * becomes a retryable `TIMEOUT`. [staleAfter] must be longer than the longest action timeout (default PT10M); it is the lease of a RUNNING action run: a run that has
 * not finished within it is treated as abandoned.
 */
@Component
@ConditionalOnProperty(prefix = "app.workflow", name = ["enabled"], havingValue = "true")
class ActionRunRecovery(
    private val actionRuns: ActionRunStore,
    @Value("\${app.workflow.action-run-stale-after:PT10M}") staleAfter: String,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = System.getLogger(ActionRunRecovery::class.java.name)
    private val lease: Duration = Duration.parse(staleAfter).also { require(it >= Duration.ofMinutes(1)) { "app.workflow.action-run-stale-after must be at least 1 minute" } }

    @Scheduled(fixedDelayString = "\${app.workflow.action-run-sweep-delay-ms:30000}")
    fun tick() {
        try {
            val now = clock.instant()
            val n = actionRuns.sweepStale(now.minus(lease), now)
            if (n > 0) log.log(System.Logger.Level.WARNING, "Action run recovery: $n abandoned run(s) failed (mutating: IDEMPOTENCY_OUTCOME_UNKNOWN, otherwise retryable TIMEOUT)")
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Action run recovery failed: ${e.javaClass.name}")
        }
    }
}
