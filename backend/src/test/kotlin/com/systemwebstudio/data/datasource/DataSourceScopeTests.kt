package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.InMemoryCredentialStore
import com.systemwebstudio.data.InMemoryDataSourceRepository
import com.systemwebstudio.data.RecordingAuditSink
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import com.systemwebstudio.runtime.SecretsCrypto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/** B-C0-W-05: with [DataSourceScope.WORKSPACE] a data source is reachable only from the workspace that owns it; the default scope keeps C3's tenant-only behaviour. */
class DataSourceScopeTests {
    private val vault = SecretsCryptoCredentialVault(SecretsCrypto(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })), InMemoryCredentialStore())
    private val tenant = TestTenant()
    private val other = TestTenant()
    private val wsA = UUID.randomUUID()
    private val wsB = UUID.randomUUID()
    private val repo = InMemoryDataSourceRepository()
    private val calls = mutableListOf<String>()

    private val connector = object : DataConnector {
        override val type = "fake"
        override fun validateConfig(config: Map<String, String>) {}
        override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult { calls += "test"; return ConnectionTestResult.Ok(1) }
        override fun discovery() = object : SchemaDiscovery { override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema { calls += "discover"; return DiscoveredSchema(emptyList()) } }
        override fun executor() = object : QueryExecutor { override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult { calls += "query"; return QueryResult(emptyList(), emptyList(), false) } }
    }

    private fun service(scope: DataSourceScope) = DataSourceService(repo, vault, DataConnectorRegistry(listOf(connector)), { _, _, _ -> true }, RecordingAuditSink(), scope)
    private fun register(t: TestTenant, ws: UUID?, status: DataSourceStatus = DataSourceStatus.ACTIVE) =
        repo.save(DataSource(DataSourceRef(UUID.randomUUID(), t.tenantId, "fake", emptyMap()), "src-" + UUID.randomUUID().toString().take(6), status, workspaceId = ws))
    private fun code(block: () -> Unit): String? = try { block(); null } catch (e: ConnectorFailure) { e.code }

    @Test
    fun `same tenant and same workspace is allowed for every operation`() {
        val ds = register(tenant, wsA); val ctx = tenant.context().copy(workspaceId = wsA); val s = service(DataSourceScope.WORKSPACE)
        assertThat(s.resolve(ctx, ds.id).id).isEqualTo(ds.id)
        assertThat(s.testConnection(ctx, ds.id)).isInstanceOf(ConnectionTestResult.Ok::class.java)
        s.discoverSchema(ctx, ds.id)
        s.runQuery(ctx, ds.id, QueryRequest("q", emptyMap(), null, tenant.tenant))
        assertThat(calls).containsExactly("test", "discover", "query")
    }

    @Test
    fun `same tenant and another workspace is not found for every operation, and no connector is reached`() {
        val ds = register(tenant, wsA); val ctx = tenant.context().copy(workspaceId = wsB); val s = service(DataSourceScope.WORKSPACE)
        assertThat(code { s.resolve(ctx, ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.testConnection(ctx, ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.discoverSchema(ctx, ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.runQuery(ctx, ds.id, QueryRequest("q", emptyMap(), null, tenant.tenant)) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(calls).isEmpty()
    }

    @Test
    fun `the answer for another workspace's source is the answer for a missing one, even when that source is disabled`() {
        val off = register(tenant, wsA, DataSourceStatus.DISABLED); val ctx = tenant.context().copy(workspaceId = wsB); val s = service(DataSourceScope.WORKSPACE)
        assertThat(code { s.resolve(ctx, off.id) }).isEqualTo(FailureCodes.NOT_FOUND)                 // not DISABLED: the status of a foreign source is not revealed
        assertThat(code { s.resolve(ctx, UUID.randomUUID()) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.resolve(tenant.context().copy(workspaceId = wsA), off.id) }).isEqualTo(FailureCodes.DISABLED)
    }

    @Test
    fun `another tenant is not found, with or without the right workspace id`() {
        val ds = register(tenant, wsA); val s = service(DataSourceScope.WORKSPACE)
        assertThat(code { s.resolve(other.context().copy(workspaceId = wsA), ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.resolve(other.context().copy(workspaceId = wsB), ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
    }

    @Test
    fun `default deny: no workspace in the context, or no workspace on the source, reaches nothing`() {
        val owned = register(tenant, wsA); val tenantLevel = register(tenant, null); val s = service(DataSourceScope.WORKSPACE)
        val noWorkspace = tenant.context().copy(workspaceId = null)
        assertThat(code { s.resolve(noWorkspace, owned.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.resolve(tenant.context().copy(workspaceId = wsA), tenantLevel.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(code { s.resolve(noWorkspace, tenantLevel.id) }).isEqualTo(FailureCodes.NOT_FOUND)
    }

    @Test
    fun `the default scope is still the tenant boundary only, so C3's own behaviour is unchanged`() {
        val ds = register(tenant, wsA); val s = service(DataSourceScope.TENANT)
        assertThat(s.resolve(tenant.context(), ds.id).id).isEqualTo(ds.id)
        assertThat(s.resolve(tenant.context().copy(workspaceId = wsB), ds.id).id).isEqualTo(ds.id)
        assertThat(code { s.resolve(other.context(), ds.id) }).isEqualTo(FailureCodes.NOT_FOUND)
        val unspecified = DataSourceService(repo, vault, DataConnectorRegistry(listOf(connector)), { _, _, _ -> true }, RecordingAuditSink())
        assertThat(unspecified.resolve(tenant.context(), ds.id).id).isEqualTo(ds.id)
    }
}
