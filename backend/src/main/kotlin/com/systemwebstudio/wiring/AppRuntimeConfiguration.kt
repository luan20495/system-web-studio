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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
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
 * C0 · wires C4's runtime to C1 (access, tenant gate), C2 (AppDefinition, resolver) and C3 (data gateway, when it exists). Everything is behind
 * `app.workflow.enabled` (default false): with the flag off none of these beans exists and no route is mounted. State is in memory (D-C0-20).
 * `ActionRunStore`/`WorkflowRunStore`/`WorkflowQueue` are the seams a persistent implementation replaces after a reviewed migration.
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
    fun actionRunStore(): ActionRunStore = InMemoryActionRunStore()

    @Bean
    fun workflowRunStore(): WorkflowRunStore = InMemoryWorkflowRunStore()

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
        @Value("\${app.workflow.allow-volatile-stores:false}") allowVolatile: Boolean
    ): AppRuntime {
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
        val engine = WorkflowEngine(json, CanonicalWorkflowCatalog(source), runtime, workflowRuns, queue, access, tenants, logicAudit)
        engineRef = engine
        return AppRuntime(
            actions = VolatileActionGuard(runtime, catalog, access, allowVolatile),
            workflows = VolatileWorkflowGuard(engine, access, allowVolatile),
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
