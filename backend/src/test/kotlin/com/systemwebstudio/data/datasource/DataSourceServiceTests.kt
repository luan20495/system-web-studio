package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.InMemoryCredentialStore
import com.systemwebstudio.data.InMemoryDataSourceRepository
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.RecordingAuditSink
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import com.systemwebstudio.runtime.SecretsCrypto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class DataSourceServiceTests {
    private val secret = "sk-live-TOPSECRET-9f8e7d"
    private val store = InMemoryCredentialStore()
    private val vault = SecretsCryptoCredentialVault(SecretsCrypto(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })), store)
    private val tenantA = TestTenant()
    private val tenantB = TestTenant()

    /** a connector that records the credential it was given and behaves as told */
    private class FakeConnector(var behaviour: (ResolvedCredential) -> Any = { ConnectionTestResult.Ok(1) }) : DataConnector {
        override val type = "fake"
        val seen = mutableListOf<ResolvedCredential>()
        override fun validateConfig(config: Map<String, String>) {}
        override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult { seen += cred; return behaviour(cred) as ConnectionTestResult }
        override fun discovery() = object : SchemaDiscovery { override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema { seen += cred; return behaviour(cred) as DiscoveredSchema } }
        override fun executor() = object : QueryExecutor { override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult { seen += cred; return behaviour(cred) as QueryResult } }
    }

    private val repo = InMemoryDataSourceRepository()
    private val audit = RecordingAuditSink()
    private val connector = FakeConnector()
    private var allow = true
    private fun service() = DataSourceService(repo, vault, DataConnectorRegistry(listOf(connector)), { _, _, _ -> allow }, audit)
    private fun register(t: TestTenant, status: DataSourceStatus = DataSourceStatus.ACTIVE, type: String = "fake"): DataSource {
        val id = UUID.randomUUID()
        return repo.save(DataSource(DataSourceRef(id, t.tenantId, type, mapOf("baseUrl" to "https://internal-looking.example.com/v1")), "billing", status, vault.store(t.tenantId, mapOf("authValue" to secret))))
    }
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private fun request(t: TestTenant) = QueryRequest("q", emptyMap(), null, t.tenant)

    @Test fun `another tenant cannot test, discover or query a data source - it simply does not exist for them`() {
        val ds = register(tenantA); val s = service()
        assertThat(failure { s.testConnection(tenantB.context(), ds.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failure { s.discoverSchema(tenantB.context(), ds.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failure { s.runQuery(tenantB.context(), ds.id, request(tenantB)) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(connector.seen.size).isEqualTo(0)                        // the credential was never even decrypted
        assertThat(audit.events.size).isEqualTo(0)
    }

    @Test fun `a query request may not name a different tenant than the caller`() {
        val ds = register(tenantA)
        assertThat(failure { service().runQuery(tenantA.context(), ds.id, request(tenantB)) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        assertThat(connector.seen.size).isEqualTo(0)
    }

    @Test fun `a disabled data source is not usable and an unknown type is a fixed failure`() {
        val off = register(tenantA, DataSourceStatus.DISABLED)
        assertThat(failure { service().testConnection(tenantA.context(), off.id) }.code).isEqualTo(FailureCodes.DISABLED)
        val odd = register(tenantA, type = "mongodb")
        val r = service().testConnection(tenantA.context(), odd.id) as ConnectionTestResult.Failed
        assertThat(r.code).isEqualTo(FailureCodes.UNSUPPORTED_TYPE)
        assertThat(connector.seen.size).isEqualTo(0)
    }

    @Test fun `the rate limit stops calls before any credential is decrypted`() {
        val ds = register(tenantA); allow = false
        assertThat(failure { service().testConnection(tenantA.context(), ds.id) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
        assertThat(failure { service().runQuery(tenantA.context(), ds.id, request(tenantA)) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
        assertThat(connector.seen.size).isEqualTo(0)
    }

    @Test fun `the connector gets the decrypted credential, and nothing observable carries it`() {
        val ds = register(tenantA)
        val r = service().testConnection(tenantA.context(), ds.id)
        assertThat(r).isEqualTo(ConnectionTestResult.Ok(1))
        assertThat(connector.seen.single().get("authValue")).isEqualTo(secret)
        for (observable in listOf(r.toString(), audit.text, ds.toString(), connector.seen.single().toString())) assertThat(observable).doesNotContain(secret)
        assertThat(audit.text).doesNotContain("internal-looking.example.com")          // configuration values stay out of the audit trail as well
        val e = audit.events.single()
        assertThat(e.action).isEqualTo(DataAuditActions.TESTED)
        assertThat(e.tenantId).isEqualTo(tenantA.tenantId); assertThat(e.dataSourceId).isEqualTo(ds.id)
    }

    @Test fun `connector failures reach the caller as fixed codes and are audited by code`() {
        val ds = register(tenantA)
        connector.behaviour = { throw ConnectorFailure(FailureCodes.ADDRESS_BLOCKED, "target is not a public internet address") }
        val t = service().testConnection(tenantA.context(), ds.id) as ConnectionTestResult.Failed
        assertThat(t.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        val d = failure { service().discoverSchema(tenantA.context(), ds.id) }
        assertThat(d.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        val q = failure { service().runQuery(tenantA.context(), ds.id, request(tenantA)) }
        assertThat(q.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
        assertThat(audit.events.map { it.details["code"] }).containsExactly(FailureCodes.ADDRESS_BLOCKED, FailureCodes.ADDRESS_BLOCKED, FailureCodes.ADDRESS_BLOCKED)
        assertThat(audit.events.map { it.action }).containsExactly(DataAuditActions.TESTED, DataAuditActions.DISCOVERED, DataAuditActions.QUERIED)
    }

    @Test fun `an unexpected exception is neither returned, audited nor logged with its message`() {
        val ds = register(tenantA)
        connector.behaviour = { cred -> throw IllegalStateException("boom jdbc:postgresql://10.9.8.7/db?password=${cred.get("authValue")}") }
        LogCapture().use { logs ->
            val t = service().testConnection(tenantA.context(), ds.id) as ConnectionTestResult.Failed
            assertThat(t.code).isEqualTo(FailureCodes.INTERNAL)
            val d = failure { service().discoverSchema(tenantA.context(), ds.id) }
            val q = failure { service().runQuery(tenantA.context(), ds.id, request(tenantA)) }
            for (text in listOf(t.message, d.message!!, q.message!!, d.toString(), q.toString(), audit.text, logs.text)) {
                assertThat(text).doesNotContain(secret); assertThat(text).doesNotContain("10.9.8.7")
            }
            assertThat(logs.text).contains("IllegalStateException")                      // the class is logged so the failure can be diagnosed
        }
    }

    @Test fun `a stored credential that cannot be opened fails closed`() {
        val id = UUID.randomUUID()
        store.put(tenantA.tenantId, "broken", "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        repo.save(DataSource(DataSourceRef(id, tenantA.tenantId, "fake", emptyMap()), "x", credentialRef = "broken"))
        val r = service().testConnection(tenantA.context(), id) as ConnectionTestResult.Failed
        assertThat(r.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(connector.seen.size).isEqualTo(0)
    }

    @Test fun `a data source without a credential gets the empty credential`() {
        val id = UUID.randomUUID()
        repo.save(DataSource(DataSourceRef(id, tenantA.tenantId, "fake", emptyMap()), "public-api"))
        assertThat(service().testConnection(tenantA.context(), id)).isEqualTo(ConnectionTestResult.Ok(1))
        assertThat(connector.seen.single().isEmpty).isTrue()
    }

    @Test fun `query results pass through unchanged for the gateway`() {
        val ds = register(tenantA)
        val row = mapOf("id" to DataJson.toNode(1))
        connector.behaviour = { QueryResult(emptyList(), listOf(row), false) }
        assertThat(service().runQuery(tenantA.context(), ds.id, request(tenantA)).rows.size).isEqualTo(1)
        assertThat(audit.events.single().details["ok"]).isEqualTo(true)
    }
}
