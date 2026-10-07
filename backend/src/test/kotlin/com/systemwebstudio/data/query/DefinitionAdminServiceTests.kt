package com.systemwebstudio.data.query

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.GatewayOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The definition service on in-memory ports: the operation each call asks C1 for, scoping, versions, audit fields, cache invalidation. Rollback is proven on the real database (DataManagementLifecycleTests, DataDefinitionManagementApiTests). */
class DefinitionAdminServiceTests {
    private class MemQueries : QueryDefinitionStore {
        val rows = ConcurrentHashMap<Triple<UUID, UUID, String>, StoredQueryDefinition>()
        override fun find(tenantId: UUID, dataSourceId: UUID, queryId: String) = rows[Triple(tenantId, dataSourceId, queryId)]
        override fun list(tenantId: UUID, dataSourceId: UUID) = rows.filterKeys { it.first == tenantId && it.second == dataSourceId }.values.sortedBy { it.definition.id }
        override fun insert(def: QueryDefinition, status: DefinitionStatus) {
            val k = Triple(def.tenantId, def.dataSourceId, def.id)
            if (rows.putIfAbsent(k, StoredQueryDefinition(def, status, Instant.now(), Instant.now())) != null) throw com.systemwebstudio.data.datasource.ConnectorFailure(FailureCodes.CONFLICT, "exists")
        }
        override fun replace(def: QueryDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean {
            val k = Triple(def.tenantId, def.dataSourceId, def.id); val cur = rows[k] ?: return false
            if (cur.definition.version != expectedVersion) return false
            rows[k] = StoredQueryDefinition(def, status, cur.createdAt, Instant.now()); return true
        }
        override fun delete(tenantId: UUID, dataSourceId: UUID, queryId: String) = rows.remove(Triple(tenantId, dataSourceId, queryId)) != null
    }
    private class MemMutations : MutationDefinitionStore {
        val rows = ConcurrentHashMap<Triple<UUID, UUID, String>, StoredMutationDefinition>()
        @Volatile var unfinished = false
        override fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String) = rows[Triple(tenantId, dataSourceId, mutationId)]
        override fun list(tenantId: UUID, dataSourceId: UUID) = rows.filterKeys { it.first == tenantId && it.second == dataSourceId }.values.sortedBy { it.definition.id }
        override fun insert(def: MutationDefinition, status: DefinitionStatus) {
            if (rows.putIfAbsent(Triple(def.tenantId, def.dataSourceId, def.id), StoredMutationDefinition(def, status, Instant.now(), Instant.now())) != null) throw com.systemwebstudio.data.datasource.ConnectorFailure(FailureCodes.CONFLICT, "exists")
        }
        override fun replace(def: MutationDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean {
            val k = Triple(def.tenantId, def.dataSourceId, def.id); val cur = rows[k] ?: return false
            if (cur.definition.version != expectedVersion) return false
            rows[k] = StoredMutationDefinition(def, status, cur.createdAt, Instant.now()); return true
        }
        override fun delete(tenantId: UUID, dataSourceId: UUID, mutationId: String) = rows.remove(Triple(tenantId, dataSourceId, mutationId)) != null
        override fun hasUnfinishedWrites(tenantId: UUID, dataSourceId: UUID, mutationId: String) = unfinished
    }

    private val f = GatewayFixture()
    private val queries = MemQueries(); private val mutations = MemMutations()
    private val changes = mutableListOf<DataChange>()
    private val svc = DefinitionAdminService(f.repo, DataConnectorRegistry(listOf(f.connector)), f.guard, f.limiter, f.audit, queries, mutations, DataChangeListener { changes += it })
    private val me = TestTenant(); private val other = TestTenant()
    private fun doc(s: String) = DataJson.parse(s.toByteArray())
    private fun source(t: TestTenant = me): UUID {
        val id = UUID.randomUUID(); val now = Instant.now()
        f.repo.save(DataSource(DataSourceRef(id, t.tenantId, "fake", emptyMap()), "src-$id".take(30), workspaceId = t.workspaceId, createdAt = now, updatedAt = now)); return id
    }
    private val q = QueryDefinitionCreate("q.one", "SQL", doc("""{"sql":"SELECT 1"}"""))
    private val m = MutationDefinitionCreate("m.one", MutationKind.CREATE, doc("""{"target":"t","params":[{"name":"a","type":"STRING"}]}"""))

    @Test fun `each call asks C1 for exactly the operation of the contract`() {
        val ds = source(); val c = f.ctx(me)
        f.authorizer.calls.clear()
        svc.listQueries(c, ds); svc.listMutations(c, ds)
        assertThat(f.authorizer.calls.map { it.first }).containsOnly(GatewayOperation.DATASOURCE_READ)
        f.authorizer.calls.clear()
        svc.createQuery(c, ds, q); svc.getQuery(c, ds, "q.one"); svc.updateQuery(c, ds, "q.one", DefinitionPatch(status = DefinitionStatus.DISABLED)); svc.deleteQuery(c, ds, "q.one")
        svc.createMutation(c, ds, m); svc.getMutation(c, ds, "m.one"); svc.updateMutation(c, ds, "m.one", DefinitionPatch(status = DefinitionStatus.DISABLED)); svc.deleteMutation(c, ds, "m.one")
        assertThat(f.authorizer.calls.map { it.first }).describedAs("no QUERY_MANAGE / MUTATION_MANAGE exists").containsOnly(GatewayOperation.DATASOURCE_MANAGE)
    }

    @Test fun `a denied caller learns nothing and changes nothing`() {
        val ds = source(); val c = f.ctx(me)
        f.authorizer.denied += GatewayOperation.DATASOURCE_MANAGE
        for (call in listOf<() -> Unit>({ svc.createQuery(c, ds, q) }, { svc.getQuery(c, ds, "x") }, { svc.createMutation(c, ds, m) }, { svc.deleteQuery(c, ds, "x") }, { svc.updateMutation(c, ds, "x", DefinitionPatch(status = DefinitionStatus.DISABLED)) }))
            assertThat(f.failure(call).code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(queries.rows).isEmpty(); assertThat(mutations.rows).isEmpty(); assertThat(f.audit.actions().filter { it.startsWith("DATA_QUERY") || it.startsWith("DATA_MUTATION") }).isEmpty()
    }

    @Test fun `versions grow by one per change, status and definition changes are audited by id, version, status and hash`() {
        val ds = source(); val c = f.ctx(me)
        val created = svc.createQuery(c, ds, q); assertThat(created.definition.version).isEqualTo(1L)
        val v2 = svc.updateQuery(c, ds, "q.one", DefinitionPatch(status = DefinitionStatus.DISABLED)); assertThat(v2.definition.version).isEqualTo(2L); assertThat(v2.status).isEqualTo(DefinitionStatus.DISABLED)
        val v3 = svc.updateQuery(c, ds, "q.one", DefinitionPatch(doc("""{"sql":"SELECT 2"}"""), expectedVersion = 2)); assertThat(v3.definition.version).isEqualTo(3L)
        assertThat((v3.definition as SqlQueryDefinition).sql).isEqualTo("SELECT 2"); assertThat(v3.status).describedAs("status is kept when only the document changes").isEqualTo(DefinitionStatus.DISABLED)
        assertThat(f.failure { svc.updateQuery(c, ds, "q.one", DefinitionPatch(status = DefinitionStatus.ACTIVE, expectedVersion = 1)) }.code).isEqualTo(FailureCodes.CONFLICT)
        assertThat(queries.find(me.tenantId, ds, "q.one")!!.definition.version).isEqualTo(3L)
        val events = f.audit.events.filter { it.action == "DATA_QUERY_DEFINITION_CHANGED" }
        assertThat(events.map { it.details["change"] }).containsExactly("create", "update", "update")
        assertThat(events.map { it.details["version"] }).containsExactly(1L, 2L, 3L)
        assertThat(events.last().details["hash"] as String).matches("[0-9a-f]{64}"); assertThat(events.joinToString { it.details.toString() }).doesNotContain("SELECT")
        assertThat(changes).describedAs("each change flushes the cache of the source").hasSize(3)
    }

    @Test fun `ids are unique per data source, ids are validated, and the same id on another source is fine`() {
        val ds = source(); val ds2 = source(); val c = f.ctx(me)
        svc.createQuery(c, ds, q)
        assertThat(f.failure { svc.createQuery(c, ds, q) }.code).isEqualTo(FailureCodes.CONFLICT)
        svc.createQuery(c, ds2, q)
        assertThat(f.failure { svc.createQuery(c, ds, QueryDefinitionCreate("bad id", "SQL", doc("""{"sql":"SELECT 1"}"""))) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.failure { svc.getQuery(c, ds, "../x") }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(svc.listQueries(c, ds)).hasSize(1)
    }

    @Test fun `another workspace or tenant reaches nothing and cannot tell the data source exists`() {
        val ds = source(); val sibling = TestTenant(tenantId = me.tenantId, workspaceId = UUID.randomUUID())
        svc.createQuery(f.ctx(me), ds, q); svc.createMutation(f.ctx(me), ds, m)
        val missing = f.failure { svc.listQueries(f.ctx(me), UUID.randomUUID()) }
        for (foreign in listOf(sibling, other)) {
            val c = f.ctx(foreign)
            val calls = listOf<() -> Unit>({ svc.listQueries(c, ds) }, { svc.getQuery(c, ds, "q.one") }, { svc.createQuery(c, ds, q.let { QueryDefinitionCreate("n.q", it.kind, it.definition) }) },
                { svc.updateQuery(c, ds, "q.one", DefinitionPatch(status = DefinitionStatus.DISABLED)) }, { svc.deleteQuery(c, ds, "q.one") }, { svc.listMutations(c, ds) }, { svc.getMutation(c, ds, "m.one") },
                { svc.createMutation(c, ds, MutationDefinitionCreate("n.m", m.kind, m.definition)) }, { svc.updateMutation(c, ds, "m.one", DefinitionPatch(status = DefinitionStatus.DISABLED)) }, { svc.deleteMutation(c, ds, "m.one") })
            for (call in calls) { val e = f.failure(call); assertThat(e.code).isEqualTo(FailureCodes.NOT_FOUND); assertThat(e.message).isEqualTo(missing.message) }
        }
        assertThat(queries.rows).hasSize(1); assertThat(mutations.rows).hasSize(1)
        assertThat(f.failure { svc.listQueries(f.ctx(TestTenant(tenantId = me.tenantId, workspaceId = null)), ds) }.code).describedAs("no workspace, no data source").isEqualTo(FailureCodes.NOT_FOUND)
    }

    @Test fun `a mutation with an unfinished write cannot be deleted`() {
        val ds = source(); val c = f.ctx(me)
        svc.createMutation(c, ds, m)
        mutations.unfinished = true
        assertThat(f.failure { svc.deleteMutation(c, ds, "m.one") }.code).isEqualTo(FailureCodes.CONFLICT); assertThat(mutations.rows).hasSize(1)
        mutations.unfinished = false
        svc.deleteMutation(c, ds, "m.one"); assertThat(mutations.rows).isEmpty()
    }

    @Test fun `the connector decides whether it takes a mutation - a read-only one refuses with READ_ONLY_VIOLATION`() {
        val ds = source(); val c = f.ctx(me)
        f.connector.readOnly = true
        try { assertThat(f.failure { svc.createMutation(c, ds, m) }.code).isEqualTo(FailureCodes.READ_ONLY_VIOLATION) } finally { f.connector.readOnly = false }
        assertThat(mutations.rows).isEmpty(); assertThat(f.audit.actions()).doesNotContain("DATA_MUTATION_DEFINITION_CHANGED")
    }
}
