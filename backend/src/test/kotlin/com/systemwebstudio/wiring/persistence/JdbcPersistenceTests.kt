package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.CredentialStore
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.SecretsCryptoCredentialVault
import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.discovery.SchemaFingerprint
import com.systemwebstudio.data.discovery.SchemaSnapshot
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.RestQueryDefinition
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.runtime.SecretsCrypto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.util.Base64
import java.util.UUID

/** The V28 JDBC adapters against a real PostgreSQL: round trips, tenant isolation, optimistic concurrency, secrets never in a normal read. */
class JdbcPersistenceTests : DataRuntimeJdbcTestBase() {
    private val repo get() = JdbcDataSourceRepository(jdbc)
    private val credentials get() = JdbcCredentialStore(jdbc)
    private val queries get() = JdbcQueryCatalog(jdbc)
    private val mutations get() = JdbcMutationCatalog(jdbc)
    private val schemas get() = JdbcSourceSchemaStore(jdbc)
    private val crypto = SecretsCrypto(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 9).toByte() }))

    // ---------------------------------------------------------------------------------------------- data sources

    @Test
    fun `a data source round-trips and is invisible to another tenant`() {
        val tenant = newTenant(); val ws = workspaceOf(tenant); val other = newTenant()
        val ds = dataSource(tenant, ws, config = mapOf("baseUrl" to "https://api.example.com", "maxRows" to "100"), credentialRef = "ref-1")
        repo.save(ds)
        val back = repo.find(tenant, ds.id)!!
        assertThat(back.tenantId).isEqualTo(tenant)
        assertThat(back.workspaceId).isEqualTo(ws)
        assertThat(back.connectorType).isEqualTo("fake")
        assertThat(back.name).isEqualTo(ds.name)
        assertThat(back.ref.configNonSecret).isEqualTo(ds.ref.configNonSecret)
        assertThat(back.credentialRef).isEqualTo("ref-1")
        assertThat(back.status).isEqualTo(DataSourceStatus.ACTIVE)
        assertThat(back.version).isEqualTo(1L)
        assertThat(back.createdAt).isEqualTo(at)
        assertThat(repo.list(tenant).map { it.id }).containsExactly(ds.id)
        assertThat(repo.find(other, ds.id)).isNull()
        assertThat(repo.list(other)).isEmpty()
    }

    @Test
    fun `a data source without a workspace is allowed, one in another tenant's workspace is refused by the database`() {
        val tenant = newTenant(); val other = newTenant()
        repo.save(dataSource(tenant))
        val foreignWorkspace = workspaceOf(other)
        val failed = try { repo.save(dataSource(tenant, foreignWorkspace)); false } catch (e: DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
    }

    @Test
    fun `saving needs a newer version, a stale or repeated save is a conflict and changes nothing`() {
        val tenant = newTenant()
        val v1 = dataSource(tenant, name = "orders")
        repo.save(v1)
        assertThat(failureCode { repo.save(v1) }).isEqualTo(FailureCodes.CONFLICT)
        val v2 = v1.revised(at.plusSeconds(60), status = DataSourceStatus.DISABLED, config = mapOf("k" to "v2"))
        repo.save(v2)
        val back = repo.find(tenant, v1.id)!!
        assertThat(back.version).isEqualTo(2L)
        assertThat(back.status).isEqualTo(DataSourceStatus.DISABLED)
        assertThat(back.ref.configNonSecret).containsEntry("k", "v2")
        assertThat(back.createdAt).isEqualTo(at)
        assertThat(failureCode { repo.save(v1.revised(at.plusSeconds(90), name = "stale")) }).isEqualTo(FailureCodes.CONFLICT)
        assertThat(repo.find(tenant, v1.id)!!.name).isEqualTo("orders")
    }

    @Test
    fun `another tenant cannot overwrite a data source by reusing its id`() {
        val tenant = newTenant(); val intruder = newTenant()
        val mine = dataSource(tenant, name = "mine")
        repo.save(mine)
        val hijack = dataSource(intruder, name = "hijack", version = 5)
        val ref = hijack.ref.copy(id = mine.id)
        val failed = try { repo.save(com.systemwebstudio.data.datasource.DataSource(ref, "hijack", version = 5)); false } catch (e: ConnectorFailure) { true } catch (e: DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
        assertThat(repo.find(tenant, mine.id)!!.name).isEqualTo("mine")
        assertThat(repo.find(intruder, mine.id)).isNull()
    }

    @Test
    fun `a name is unique per tenant but may repeat across tenants`() {
        val a = newTenant(); val b = newTenant()
        repo.save(dataSource(a, name = "crm"))
        repo.save(dataSource(b, name = "crm"))
        assertThat(failureCode { repo.save(dataSource(a, name = "crm")) }).isEqualTo(FailureCodes.CONFLICT)
    }

    @Test
    fun `a workspace-scoped lookup finds only the source of that tenant and workspace`() {
        val tenant = newTenant(); val ws = workspaceOf(tenant); val otherWs = workspaceOf(tenant); val otherTenant = newTenant()
        val owned = dataSource(tenant, ws); val tenantLevel = dataSource(tenant)
        repo.save(owned); repo.save(tenantLevel)
        assertThat(repo.findInWorkspace(tenant, ws, owned.id)!!.id).isEqualTo(owned.id)
        assertThat(repo.findInWorkspace(tenant, otherWs, owned.id)).isNull()                   // another workspace of the same tenant
        assertThat(repo.findInWorkspace(otherTenant, ws, owned.id)).isNull()                   // another tenant
        assertThat(repo.findInWorkspace(tenant, ws, tenantLevel.id)).isNull()                  // a tenant-level source belongs to no workspace
        assertThat(repo.findInWorkspace(tenant, ws, UUID.randomUUID())).isNull()
        assertThat(repo.find(tenant, tenantLevel.id)).isNotNull()                              // the tenant-scoped lookup is unchanged
    }

    // ---------------------------------------------------------------------------------------------- credentials

    @Test
    fun `credentials are tenant-scoped ciphertext and never part of a normal data source read`() {
        val tenant = newTenant(); val other = newTenant()
        val vault = SecretsCryptoCredentialVault(crypto, credentials)
        val ref = vault.store(tenant, mapOf("token" to "sk-live-VERY-SECRET-123"))
        val ds = dataSource(tenant, credentialRef = ref)
        repo.save(ds)

        val stored = jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ? AND ref = ?", String::class.java, tenant, ref)!!
        assertThat(stored).startsWith("v1:").doesNotContain("sk-live-VERY-SECRET-123")
        assertThat(vault.open(repo.find(tenant, ds.id)!!).require("token")).isEqualTo("sk-live-VERY-SECRET-123")

        // the data_sources table has no column that could hold secret material, and the model never prints the reference
        val columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'data_sources'", String::class.java)
        assertThat(columns).noneMatch { it != "config_nonsecret" && (it.contains("secret") || it.contains("cipher") || it.contains("password") || it.contains("token")) }
        assertThat(repo.find(tenant, ds.id).toString()).doesNotContain(ref).doesNotContain("sk-live")
        assertThat(repo.list(tenant).toString()).doesNotContain("sk-live")

        // another tenant resolves nothing for this reference, whatever it guesses
        assertThat(credentials.find(other, ref)).isNull()
        assertThat(failureCode { vault.openRef(other, ref) }).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        credentials.remove(other, ref)
        assertThat(credentials.find(tenant, ref)).isEqualTo(stored)

        vault.discard(tenant, ref)
        assertThat(credentials.find(tenant, ref)).isNull()
    }

    @Test
    fun `put replaces and a value that is not ciphertext is refused`() {
        val tenant = newTenant()
        val store: CredentialStore = credentials
        store.put(tenant, "r1", "v1:AAAA")
        store.put(tenant, "r1", "v1:BBBB")
        assertThat(store.find(tenant, "r1")).isEqualTo("v1:BBBB")
        val failed = try { store.put(tenant, "r2", "plain-text-secret"); false } catch (e: DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
        assertThat(store.find(tenant, "r2")).isNull()
    }

    // ---------------------------------------------------------------------------------------------- queries and mutations

    @Test
    fun `queries and mutations round-trip, are scoped by tenant and data source, and a disabled one is not found`() {
        val tenant = newTenant(); val other = newTenant()
        val ds = dataSource(tenant); val ds2 = dataSource(tenant); repo.save(ds); repo.save(ds2)
        val sql = SqlQueryDefinition("orders.list", tenant, ds.id, "SELECT id FROM orders WHERE status = :status", listOf(QueryParamSpec("status", ParamType.STRING, false, DataJson.toNode("open"))), 200, 30, 1)
        val rest = RestQueryDefinition("orders.rest", tenant, ds.id, "/orders", rowsPointer = "/data/items", maxRows = 20)
        val mutation = MutationDefinition("orders.create", tenant, ds.id, MutationKind.CREATE, "orders", listOf(QueryParamSpec("customer", ParamType.STRING)), listOf("orders.list"), "orders")
        queries.save(sql); queries.save(rest); mutations.save(mutation)

        assertThat(queries.find(tenant, ds.id, "orders.list")).isEqualTo(sql)
        assertThat(queries.find(tenant, ds.id, "orders.rest")).isEqualTo(rest)
        assertThat(queries.list(tenant, ds.id)).containsExactlyInAnyOrder(sql, rest)
        assertThat(mutations.find(tenant, ds.id, "orders.create")).isEqualTo(mutation)
        assertThat(mutations.list(tenant, ds.id)).containsExactly(mutation)

        assertThat(queries.find(other, ds.id, "orders.list")).isNull()
        assertThat(queries.find(tenant, ds2.id, "orders.list")).isNull()
        assertThat(queries.list(tenant, ds2.id)).isEmpty()
        assertThat(mutations.find(other, ds.id, "orders.create")).isNull()
        assertThat(mutations.find(tenant, ds2.id, "orders.create")).isNull()

        assertThat(queries.disable(tenant, ds.id, "orders.list")).isTrue()
        assertThat(queries.find(tenant, ds.id, "orders.list")).isNull()
        assertThat(queries.list(tenant, ds.id)).containsExactly(rest)
        assertThat(mutations.disable(tenant, ds.id, "orders.create")).isTrue()
        assertThat(mutations.find(tenant, ds.id, "orders.create")).isNull()
        assertThat(queries.disable(other, ds.id, "orders.rest")).isFalse()
    }

    @Test
    fun `a definition is only replaced by a newer version`() {
        val tenant = newTenant(); val ds = dataSource(tenant); repo.save(ds)
        queries.save(SqlQueryDefinition("q1", tenant, ds.id, "SELECT 1", version = 1))
        assertThat(failureCode { queries.save(SqlQueryDefinition("q1", tenant, ds.id, "SELECT 2", version = 1)) }).isEqualTo(FailureCodes.CONFLICT)
        queries.save(SqlQueryDefinition("q1", tenant, ds.id, "SELECT 3", version = 2))
        assertThat((queries.find(tenant, ds.id, "q1") as SqlQueryDefinition).sql).isEqualTo("SELECT 3")
        assertThat(queries.find(tenant, ds.id, "q1")!!.version).isEqualTo(2L)
        mutations.save(MutationDefinition("m1", tenant, ds.id, MutationKind.UPDATE, "t", version = 1))
        assertThat(failureCode { mutations.save(MutationDefinition("m1", tenant, ds.id, MutationKind.DELETE, "t", version = 1)) }).isEqualTo(FailureCodes.CONFLICT)
    }

    @Test
    fun `a definition for a data source of another tenant is refused by the database`() {
        val tenant = newTenant(); val other = newTenant(); val foreign = dataSource(other); repo.save(foreign)
        val failed = try { queries.save(SqlQueryDefinition("q1", tenant, foreign.id, "SELECT 1")); false } catch (e: DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
    }

    @Test
    fun `a stored definition that no longer validates is not found and does not break the list`() {
        val tenant = newTenant(); val ds = dataSource(tenant); repo.save(ds)
        queries.save(SqlQueryDefinition("good", tenant, ds.id, "SELECT 1"))
        jdbc.update("INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) VALUES (?, ?, 'broken', 'SQL', CAST('{\"sql\":\"SELECT 1\"}' AS jsonb))", tenant, ds.id)
        jdbc.update("INSERT INTO data_queries (tenant_id, data_source_id, query_id, kind, definition) VALUES (?, ?, 'alien', 'GRAPHQL', CAST('{}' AS jsonb))", tenant, ds.id)
        jdbc.update("INSERT INTO data_mutations (tenant_id, data_source_id, mutation_id, kind, definition) VALUES (?, ?, 'weird', 'TRUNCATE', CAST('{\"target\":\"t\"}' AS jsonb))", tenant, ds.id)
        assertThat(queries.find(tenant, ds.id, "broken")).isNull()
        assertThat(queries.find(tenant, ds.id, "alien")).isNull()
        assertThat(queries.list(tenant, ds.id).map { it.id }).containsExactly("good")
        assertThat(mutations.find(tenant, ds.id, "weird")).isNull()
        assertThat(mutations.list(tenant, ds.id)).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------- schemas

    private fun snapshot(tenant: UUID, ds: UUID, version: Int, schema: DiscoveredSchema = schemaOf("orders")) =
        SchemaSnapshot(UUID.randomUUID(), tenant, ds, version, at.plusSeconds(version.toLong()), SchemaFingerprint.of(schema), 1, null, true, schema)

    private fun schemaOf(entity: String) = DiscoveredSchema(
        listOf(DiscoveredEntity(entity, "public", EntityKind.TABLE, listOf(DiscoveredField("id", NormalizedType.INTEGER, false, "int4", true), DiscoveredField("note", NormalizedType.STRING, true)),
            listOf("id"), sample = listOf(mapOf("id" to DataJson.toNode(1), "note" to DataJson.toNode("a***")))))
    )

    @Test
    fun `schema snapshots are immutable per version, newest first, tenant-scoped`() {
        val tenant = newTenant(); val other = newTenant(); val ds = dataSource(tenant); repo.save(ds)
        assertThat(schemas.latest(tenant, ds.id)).isNull()
        val v1 = snapshot(tenant, ds.id, 1); val v2 = snapshot(tenant, ds.id, 2, schemaOf("invoices"))
        schemas.save(v1); schemas.save(v2)
        assertThat(schemas.latest(tenant, ds.id)).isEqualTo(v2)
        assertThat(schemas.history(tenant, ds.id, 10)).containsExactly(v2, v1)
        assertThat(schemas.history(tenant, ds.id, 1)).containsExactly(v2)
        assertThat(schemas.latest(other, ds.id)).isNull()
        assertThat(schemas.history(other, ds.id, 10)).isEmpty()
        assertThat(failureCode { schemas.save(snapshot(tenant, ds.id, 2)) }).isEqualTo(FailureCodes.CONFLICT)
        assertThat(schemas.latest(tenant, ds.id)).isEqualTo(v2)
    }

    @Test
    fun `a snapshot for a data source of another tenant is refused by the database`() {
        val tenant = newTenant(); val other = newTenant(); val foreign = dataSource(other); repo.save(foreign)
        val failed = try { schemas.save(snapshot(tenant, foreign.id, 1)); false } catch (e: DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
    }

    // ---------------------------------------------------------------------------------------------- bindings

    @Test
    fun `TEST and LIVE bindings are separate, default-deny and tenant-scoped, and the reader never writes`() {
        val tenant = newTenant(); val ws = workspaceOf(tenant); val project = projectIn(ws, tenant); val other = newTenant()
        val live = dataSource(tenant, ws); val test = dataSource(tenant, ws); repo.save(live); repo.save(test)
        val reader = JdbcDataSourceSlotBindings(jdbc); val writer = DataSourceBindingWriter(jdbc)
        assertThat(reader.bindings(tenant, project, ExecutionMode.LIVE)).isEmpty()

        assertThat(writer.bind(tenant, ws, project, ExecutionMode.LIVE, "erp-db", live.id, null)).isTrue()
        assertThat(writer.bind(tenant, ws, project, ExecutionMode.TEST, "erp-db", test.id, null)).isTrue()
        assertThat(reader.bindings(tenant, project, ExecutionMode.LIVE)).isEqualTo(mapOf("erp-db" to live.id))
        assertThat(reader.bindings(tenant, project, ExecutionMode.TEST)).isEqualTo(mapOf("erp-db" to test.id))
        assertThat(reader.bindings(other, project, ExecutionMode.LIVE)).isEmpty()
        assertThat(reader.bindings(tenant, UUID.randomUUID(), ExecutionMode.LIVE)).isEmpty()

        // re-binding a slot replaces it; the other mode is untouched
        assertThat(writer.bind(tenant, ws, project, ExecutionMode.LIVE, "erp-db", test.id, null)).isTrue()
        assertThat(reader.bindings(tenant, project, ExecutionMode.LIVE)).isEqualTo(mapOf("erp-db" to test.id))
        assertThat(reader.bindings(tenant, project, ExecutionMode.TEST)).isEqualTo(mapOf("erp-db" to test.id))

        val before = jdbc.queryForObject("SELECT count(*) FROM data_source_bindings WHERE project_id = ?", Long::class.java, project)
        reader.bindings(tenant, project, ExecutionMode.TEST); reader.bindings(tenant, project, ExecutionMode.LIVE)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_source_bindings WHERE project_id = ?", Long::class.java, project)).isEqualTo(before)

        assertThat(writer.unbind(tenant, project, ExecutionMode.LIVE, "erp-db")).isTrue()
        assertThat(reader.bindings(tenant, project, ExecutionMode.LIVE)).isEmpty()
        assertThat(reader.bindings(tenant, project, ExecutionMode.TEST)).isNotEmpty()
    }

    @Test
    fun `the binding reader only answers for a project whose workspace owns the project and the source`() {
        val tenant = newTenant(); val ws = workspaceOf(tenant); val project = projectIn(ws, tenant)
        val ds = dataSource(tenant, ws); repo.save(ds)
        DataSourceBindingWriter(jdbc).bind(tenant, ws, project, ExecutionMode.LIVE, "erp-db", ds.id, null)
        val reader = JdbcDataSourceSlotBindings(jdbc)
        assertThat(reader.bindings(tenant, project, ExecutionMode.LIVE)).isEqualTo(mapOf("erp-db" to ds.id))
        // a forged tenant or project id finds nothing, and a binding of one project is invisible to another
        assertThat(reader.bindings(newTenant(), project, ExecutionMode.LIVE)).isEmpty()
        assertThat(reader.bindings(tenant, projectIn(ws, tenant), ExecutionMode.LIVE)).isEmpty()
        // the writer refuses a project that is not in the workspace it is told, and a tenant that does not own the workspace
        val elsewhere = workspaceOf(tenant)
        val own = dataSource(tenant, elsewhere); repo.save(own)
        assertThat(DataSourceBindingWriter(jdbc).bind(tenant, elsewhere, project, ExecutionMode.LIVE, "x", own.id, null)).isFalse()
        assertThat(DataSourceBindingWriter(jdbc).bind(newTenant(), ws, project, ExecutionMode.LIVE, "x", ds.id, null)).isFalse()
    }

    @Test
    fun `a binding cannot point at a data source of another tenant or workspace`() {
        val tenant = newTenant(); val ws = workspaceOf(tenant); val project = projectIn(ws, tenant); val other = newTenant()
        val foreign = dataSource(other, workspaceOf(other)); repo.save(foreign)
        val otherWorkspace = dataSource(tenant, workspaceOf(tenant)); repo.save(otherWorkspace)
        val writer = DataSourceBindingWriter(jdbc)
        assertThat(writer.bind(tenant, ws, project, ExecutionMode.LIVE, "erp-db", foreign.id, null)).isFalse()
        assertThat(writer.bind(tenant, ws, project, ExecutionMode.LIVE, "erp-db", otherWorkspace.id, null)).isFalse()
        assertThat(JdbcDataSourceSlotBindings(jdbc).bindings(tenant, project, ExecutionMode.LIVE)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_source_bindings WHERE project_id = ?", Long::class.java, project)).isZero()
    }
}
