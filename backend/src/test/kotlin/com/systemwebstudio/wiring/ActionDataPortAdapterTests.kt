package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionTestSupport
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.data.gateway.GatewayMutationResponse
import com.systemwebstudio.data.gateway.GatewayQuery
import com.systemwebstudio.data.gateway.GatewayQueryResponse
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.OperationRequest
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.WriteKind
import com.systemwebstudio.logic.action.WriteRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * W-05: the data path of an action. Pure unit tests: the AppDefinition is the C2 sample document, the gateway is a recording fake.
 * The sample's `order-create` is a WRITE query on the slot `erp-db`, `orders-list` a READ query, `crm` a data source with a sourceRef.
 */
class ActionDataPortAdapterTests {
    private val erpId = UUID.fromString("22222222-3333-4444-8555-666666666666")
    private val crmId = UUID.fromString("0b8f2a3e-5c1d-4b7a-9e2f-3d4c5b6a7e80")
    private val doc = AppDefinitionTestSupport.resource("valid-v2-sample.json")

    private class Loader(private val tenant: UUID, private val app: UUID, private val doc: JsonNode, private val versionId: String?) : DefinitionLoader {
        override fun load(tenantId: UUID, projectId: UUID, mode: ExecutionMode): LoadedDefinition? =
            if (tenantId == tenant && projectId == app) LoadedDefinition(doc, if (mode == ExecutionMode.LIVE) versionId else null) else null
        override fun loadVersion(tenantId: UUID, projectId: UUID, versionId: String): LoadedDefinition? = null
    }

    private class RecordingGateway(var behaviour: (GatewayMutation) -> GatewayMutationResponse) : DataGateway {
        val contexts = mutableListOf<GatewayContext>()
        val mutations = mutableListOf<GatewayMutation>()
        override fun mutate(ctx: GatewayContext, mutation: GatewayMutation): GatewayMutationResponse {
            contexts += ctx
            mutations += mutation
            return behaviour(mutation)
        }
        override fun runQuery(ctx: GatewayContext, query: GatewayQuery): GatewayQueryResponse = throw UnsupportedOperationException()
        override fun testConnection(ctx: GatewayContext, dataSourceId: UUID): ConnectionTestResult = throw UnsupportedOperationException()
        override fun discoverSchema(ctx: GatewayContext, dataSourceId: UUID, includeSamples: Boolean): DiscoveryService.RefreshResult = throw UnsupportedOperationException()
        override fun refreshCache(ctx: GatewayContext, dataSourceId: UUID, queryId: String?) = throw UnsupportedOperationException()
    }

    private fun ok(m: GatewayMutation) = GatewayMutationResponse(m.operation, MutationKind.CREATE, 1L, false, Fx.obj("id" to Fx.str("42")))

    private fun slots(map: Map<String, UUID>) = DataSourceSlotBindings { _, _, _ -> map }

    private fun adapter(gateway: DataGateway?, bound: Boolean = true, version: String? = "11111111-1111-4111-8111-111111111111") = ActionDataPortAdapter(
        Loader(Fx.tenantA, Fx.appA, doc, version),
        RuntimeResolvers(AppDefinitionTestSupport.codec, slots(if (bound) mapOf("erp-db" to erpId) else emptyMap())),
        { gateway }
    )

    private val ctx: ActionContext get() = Fx.ctx()
    private val key = Fx.dk("create-order", "k1")

    private fun write(query: String = "order-create", mode: ExecutionMode = ExecutionMode.LIVE, app: UUID = Fx.appA) =
        WriteRequest(app, mode, query, WriteKind.CREATE, mapOf("customer" to Fx.str("ACME")), key)

    private fun failure(o: PortOutcome): PortOutcome.Failure = o as PortOutcome.Failure

    @Test
    fun `a write resolves local ids and sends the derived key unchanged to C3`() {
        val gw = RecordingGateway(this::ok)
        val outcome = adapter(gw).write(ctx, write())
        assertThat(outcome).isInstanceOf(PortOutcome.Success::class.java)
        val m = gw.mutations.single()
        assertThat(m.dataSourceId).isEqualTo(erpId)
        assertThat(m.operation).isEqualTo("orders.create")
        assertThat(m.idempotencyKey).isEqualTo(key).hasSize(43)
        assertThat(m.params.keys).containsExactly("customer")
        val g = gw.contexts.single()
        assertThat(g.tenantId).isEqualTo(Fx.tenantA)
        assertThat(g.actorUserId).isEqualTo(Fx.user)
        assertThat(g.actorKind.name).isEqualTo("USER")
        assertThat(g.projectId).isEqualTo(Fx.appA)
        assertThat(g.appVersionId).isEqualTo("11111111-1111-4111-8111-111111111111")
        assertThat(g.requestId).isEqualTo("req-1")
        // nothing runtime-specific is returned to the action besides the mutation answer
        assertThat((outcome as PortOutcome.Success).output.get("operation").asString()).isEqualTo("orders.create")
    }

    @Test
    fun `a source with its own sourceRef needs no slot binding`() {
        val gw = RecordingGateway(this::ok)
        val out = adapter(gw, bound = false).callOperation(ctx, OperationRequest(Fx.appA, ExecutionMode.LIVE, "crm", "crm.sync", mapOf("a" to Fx.str("b")), key))
        assertThat(out).isInstanceOf(PortOutcome.Success::class.java)
        assertThat(gw.mutations.single().dataSourceId).isEqualTo(crmId)
        assertThat(gw.mutations.single().operation).isEqualTo("crm.sync")
    }

    @Test
    fun `a READ query is refused for a write before anything is sent`() {
        val gw = RecordingGateway(this::ok)
        val f = failure(adapter(gw).write(ctx, write("orders-list")))
        assertThat(f.code).isEqualTo("WRONG_MODE")
        assertThat(f.retryable).isFalse()
        assertThat(gw.mutations).isEmpty()
    }

    @Test
    fun `an unbound data source slot is a definite refusal not a crash`() {
        val gw = RecordingGateway(this::ok)
        val f = failure(adapter(gw, bound = false).write(ctx, write()))
        assertThat(f.code).isEqualTo("DATA_SOURCE_UNBOUND")
        assertThat(f.retryable).isFalse()
        assertThat(gw.mutations).isEmpty()
    }

    @Test
    fun `an unknown query is a definite refusal`() {
        val f = failure(adapter(RecordingGateway(this::ok)).write(ctx, write("nope")))
        assertThat(f.code).isEqualTo("UNKNOWN_REFERENCE")
        assertThat(f.retryable).isFalse()
    }

    @Test
    fun `without a data gateway the answer is DATA_RUNTIME_UNAVAILABLE and not retryable`() {
        val f = failure(adapter(null).write(ctx, write()))
        assertThat(f.code).isEqualTo("DATA_RUNTIME_UNAVAILABLE")
        assertThat(f.retryable).isFalse()
    }

    @Test
    fun `another tenant sees the application as missing`() {
        val gw = RecordingGateway(this::ok)
        val f = failure(adapter(gw).write(Fx.ctx(tenant = Fx.tenantB), write()))
        assertThat(f.code).isEqualTo("APP_NOT_FOUND")
        assertThat(gw.mutations).isEmpty()
    }

    @Test
    fun `a request for another application than the context is refused`() {
        val gw = RecordingGateway(this::ok)
        val f = failure(adapter(gw).write(ctx, write(app = Fx.appB)))
        assertThat(f.code).isEqualTo("APP_MISMATCH")
        assertThat(gw.mutations).isEmpty()
    }

    @Test
    fun `TEST mode never reaches the data source`() {
        val gw = RecordingGateway(this::ok)
        val f = failure(adapter(gw).write(ctx, write(mode = ExecutionMode.TEST)))
        assertThat(f.code).isEqualTo("INVALID_MODE")
        assertThat(gw.mutations).isEmpty()
    }

    @Test
    fun `the dry run is not emulated`() {
        val a = adapter(RecordingGateway(this::ok))
        assertThat(a.dryRunWrite(ctx, write())).isEqualTo(com.systemwebstudio.logic.action.DryRunOutcome.Unsupported)
    }

    // --- C3 -> C4 mapping through the adapter ---------------------------------------------------------------------------------------------------------

    private fun failing(t: Throwable): PortOutcome.Failure = failure(adapter(RecordingGateway { throw t }).write(ctx, write()))

    @Test
    fun `an ambiguous write failure is IDEMPOTENCY_OUTCOME_UNKNOWN and not retryable`() {
        for (t in listOf<Throwable>(
            ConnectorFailure(FailureCodes.TIMEOUT, "slow"), ConnectorFailure(FailureCodes.CONNECT_FAILED, "x"), ConnectorFailure(FailureCodes.UPSTREAM_STATUS, "502"),
            java.net.SocketTimeoutException("t"), RuntimeException("sql: INSERT INTO secrets")
        )) {
            val f = failing(t)
            assertThat(f.code).describedAs(t.toString()).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN")
            assertThat(f.retryable).isFalse()
            assertThat(f.message).doesNotContain("INSERT").doesNotContain("secrets")
        }
    }

    @Test
    fun `definite and busy failures keep their code`() {
        assertThat(failing(ConnectorFailure(FailureCodes.MUTATION_REJECTED, "no")).let { it.code to it.retryable }).isEqualTo("MUTATION_REJECTED" to false)
        assertThat(failing(ConnectorFailure(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, "?")).let { it.code to it.retryable }).isEqualTo("IDEMPOTENCY_OUTCOME_UNKNOWN" to false)
        assertThat(failing(ConnectorFailure(FailureCodes.IDEMPOTENCY_IN_PROGRESS, "busy")).let { it.code to it.retryable }).isEqualTo("IDEMPOTENCY_IN_PROGRESS" to true)
        assertThat(failing(ConnectorFailure(FailureCodes.RATE_LIMITED, "slow down")).let { it.code to it.retryable }).isEqualTo("RATE_LIMITED" to true)
        assertThat(failing(ConnectorFailure(FailureCodes.IDEMPOTENCY_CONFLICT, "other input")).let { it.code to it.retryable }).isEqualTo("IDEMPOTENCY_CONFLICT" to false)
        assertThat(failing(ConnectorFailure(FailureCodes.PERMISSION_DENIED, "no")).let { it.code to it.retryable }).isEqualTo("PERMISSION_DENIED" to false)
    }

    @Test
    fun `a failure result can never be made retryable for the two terminal codes`() {
        // PortOutcome.toResult is C4's last line of defence; the adapter must also never ask for it
        val unknown = failing(ConnectorFailure(FailureCodes.TIMEOUT, "t")).toResult()
        assertThat((unknown as com.systemwebstudio.logic.action.ActionResult.Failed).retryable).isFalse()
    }
}
