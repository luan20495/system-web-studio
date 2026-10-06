package com.systemwebstudio.wiring

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.data.FakeDataConnector
import com.systemwebstudio.data.datasource.AuditServiceSink
import com.systemwebstudio.data.datasource.CredentialStore
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.SecretsCryptoCredentialVault
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.runtime.SecretsCrypto
import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import com.systemwebstudio.tenancy.TenantService
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MvcResult
import java.time.Instant
import java.util.UUID

/**
 * The audit sink and the credential vault of the application context, wrapped so a test can make them fail on demand. Both wrappers delegate to the real
 * adapters (the real `audit_events` table, the real `data_credentials` rows), so "the audit write failed" means exactly that in the real transaction.
 */
class FaultAudit(private val real: DataAuditSink) : DataAuditSink {
    /** null = never fails; an empty set = every action fails; otherwise only these actions fail */
    @Volatile var failOn: Set<String>? = null
    override fun record(action: String, tenantId: UUID, dataSourceId: UUID?, details: Map<String, Any?>) {
        failOn?.let { if (it.isEmpty() || action in it) throw IllegalStateException("audit store unavailable (test)") }
        real.record(action, tenantId, dataSourceId, details)
    }
}

class FaultVault(private val real: CredentialVault) : CredentialVault by real {
    @Volatile var failDiscard = false
    override fun discard(tenantId: UUID, ref: String?) {
        if (failDiscard) throw IllegalStateException("vault unavailable (test)")
        real.discard(tenantId, ref)
    }
}

@TestConfiguration
class ManagementTestBeans {
    @Bean fun fakeDataConnector() = FakeDataConnector()
    @Bean @Primary fun faultAudit(audit: AuditService) = FaultAudit(AuditServiceSink(audit))
    @Bean @Primary fun faultVault(crypto: SecretsCrypto, store: CredentialStore) = FaultVault(SecretsCryptoCredentialVault(crypto, store))
}

/** Shared fixtures of the Management API integration tests: real PostgreSQL, real Redis, real C1 access checks, real audit; one tenant per administrator. */
@TestPropertySource(
    properties = [
        "app.workflow.enabled=true", "app.data-platform.enabled=true", "app.workflow.allow-volatile-stores=true", "app.workflow.worker-delay-ms=3600000",
        "app.secrets.master-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    ]
)
abstract class ManagementApiTestBase : IntegrationTestBase() {
    @Autowired lateinit var connector: FakeDataConnector
    @Autowired lateinit var repository: DataSourceRepository
    @Autowired lateinit var tenants: TenantService
    @Autowired lateinit var faultAudit: FaultAudit
    @Autowired lateinit var faultVault: FaultVault

    protected val secret = "pw-MGMT-SECRET-5b1f"
    protected fun uniq(p: String) = p + "-" + UUID.randomUUID().toString().take(8)

    @org.junit.jupiter.api.BeforeEach fun resetFaults() {
        faultAudit.failOn = null; faultVault.failDiscard = false; connector.credentialsSeen.clear()
        connector.readOnly = false; connector.discovered = com.systemwebstudio.data.discovery.DiscoveredSchema(emptyList()); connector.discoveredSample = emptyList(); connector.discoveries.set(0)
    }
    @org.junit.jupiter.api.AfterEach fun clearFaults() { faultAudit.failOn = null; faultVault.failDiscard = false }

    /** an administrator of one workspace; the workspace lives in its own tenant, so the per-tenant management rate limit of one test never touches another */
    protected class W(val tenant: UUID, val ws: UUID, val user: UserEntity, val s: ApiSession) { val base = "/api/v1/workspaces/$ws/data-sources" }

    protected fun newTenant(): UUID = tenants.create(uniq("dsm"), "T").id
    protected fun workspaceIn(tenant: UUID): UUID = fx.workspace().also { jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", tenant, it) }
    protected fun member(ws: UUID, role: String): Pair<UserEntity, ApiSession> = fx.user("dsm").let { u -> fx.member(ws, u, role); u to sessionFor(u.username) }
    protected fun admin(tenant: UUID = newTenant(), ws: UUID = workspaceIn(tenant)): W = member(ws, "WORKSPACE_ADMIN").let { (u, s) -> W(tenant, ws, u, s) }

    protected fun W.create(name: String = uniq("src"), type: String = "fake", config: Map<String, Any> = mapOf("host" to "api.example.com"), credential: Map<String, String>? = null): MvcResult =
        s.post(base, json.writeValueAsString(buildMap<String, Any> { put("name", name); put("type", type); put("config", config); if (credential != null) put("credential", credential) }))
    protected fun W.created(credential: Map<String, String>? = null, type: String = "fake", config: Map<String, Any> = mapOf("host" to "api.example.com")): String =
        s.body(create(credential = credential, type = type, config = config).also { assertThat(it.response.status).describedAs(it.response.contentAsString).isEqualTo(201) }).get("id").asString()
    /** a PostgreSQL data source that may write (no connection is made by the definition routes: the connector only checks the shape) */
    protected fun W.pg(writable: Boolean = true) = created(type = "postgres", config = mapOf("host" to "db.example.com", "database" to "shop", "schemas" to "shop", "writable" to writable))
    protected fun status(r: MvcResult) = r.response.status
    protected fun code(s: ApiSession, r: MvcResult) = s.body(r).get("code")?.asString()
    protected fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    protected fun auditCount(resourceId: String, vararg actions: String): Long =
        count("SELECT count(*) FROM audit_events WHERE resource_id = ? AND action IN (${actions.joinToString(", ") { "?" }})", resourceId, *actions)

    /** after a request that carried [secretValue]: it is in no response, no audit row, no credential row (ciphertext only), no data source row, no idempotency row */
    protected fun assertNoSecretAnywhere(secretValue: String, vararg responses: MvcResult) {
        for (r in responses) assertThat(r.response.contentAsString).doesNotContain(secretValue)
        assertThat(count("SELECT count(*) FROM audit_events WHERE new_value::text LIKE ? OR old_value::text LIKE ?", "%$secretValue%", "%$secretValue%")).describedAs("audit events").isZero()
        assertThat(count("SELECT count(*) FROM data_credentials WHERE ciphertext LIKE ?", "%$secretValue%")).describedAs("credential rows hold ciphertext only").isZero()
        for (table in listOf("data_sources", "data_idempotency", "data_queries", "data_mutations", "source_schemas", "data_source_bindings"))
            assertThat(count("SELECT count(*) FROM $table t WHERE t::text LIKE ?", "%$secretValue%")).describedAs(table).isZero()
    }

    // ------------------------------------------------------------------------------------------------ an application (bindings)

    protected class P(val sc: Scenario, val tenant: UUID, val admin: ApiSession, val adminUser: UserEntity) { val bindings = "${sc.base}/data-bindings" }

    /** one application (default tenant) with a WORKSPACE_ADMIN; data sources are written through the repository here (the API itself is covered elsewhere) */
    protected fun app(): P {
        val sc = scenario()
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        val adminUser = fx.user("wsadmin").also { fx.member(sc.ws, it, "WORKSPACE_ADMIN") }
        return P(sc, tenant, sessionFor(adminUser.username), adminUser)
    }

    protected fun source(tenant: UUID, ws: UUID?): DataSource {
        val now = Instant.now()
        return DataSource(DataSourceRef(UUID.randomUUID(), tenant, "fake", emptyMap()), uniq("src"), workspaceId = ws, createdAt = now, updatedAt = now).also { repository.save(it) }
    }
    protected fun bindingRows(p: P) = jdbc.queryForList("SELECT mode, slot_id, data_source_id FROM data_source_bindings WHERE project_id = ? ORDER BY mode, slot_id", p.sc.projectId)
    protected fun put(p: P, mode: String, slot: String, ds: UUID) = p.admin.put("${p.bindings}/$mode/$slot", """{"dataSourceId":"$ds"}""")
}
