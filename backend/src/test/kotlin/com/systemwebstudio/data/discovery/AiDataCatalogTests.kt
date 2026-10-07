package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `AiDataCatalog`: what an AI planner may be told about the data — masked structure and approved operations, per caller, per tenant, and nothing else. */
class AiDataCatalogTests {
    private val f = GatewayFixture()
    private val provider = DefaultAiDataCatalogProvider(f.repo, f.queries, f.mutations, f.snapshots, f.guard, f.audit, f.clock)
    private fun node(v: Any?) = DataJson.toNode(v)
    private fun row(vararg kv: Pair<String, Any?>) = kv.associate { it.first to node(it.second) }

    /** a customers table whose stored snapshot even contains RAW personal samples (as if some connector had failed to mask them) */
    private fun snapshot(t: TestTenant, ds: DataSource, version: Int = 1) = SchemaSnapshot(
        UUID.randomUUID(), t.tenantId, ds.id, version, Instant.parse("2026-10-05T09:00:00Z"), "fp", ds.version, null, true,
        DiscoveredSchema(listOf(DiscoveredEntity("customers", "shop", EntityKind.TABLE, listOf(
            DiscoveredField("id", NormalizedType.INTEGER, false, "int8", true), DiscoveredField("email", NormalizedType.STRING, true, "text"),
            DiscoveredField("ssn", NormalizedType.STRING, true, "text"), DiscoveredField("password_hash", NormalizedType.STRING, true, "text"),
            DiscoveredField("first_name", NormalizedType.STRING, true, "text"), DiscoveredField("status", NormalizedType.STRING, false, "text")),
            primaryKey = listOf("id"), relations = listOf(DiscoveredRelation("fk_orders", listOf("id"), "orders", "shop", listOf("customer_id"))),
            metadata = mapOf("source" to "postgres", "internal" to "host=db.internal-looking.example.com"),
            sample = (1..5).map { row("id" to it, "email" to "ada$it@example.com", "ssn" to "123-45-678$it", "password_hash" to "pbkdf2\$HASH$it", "first_name" to "Ada$it", "status" to "active") })),
            warnings = listOf("sample skipped for 1 entities (not readable)")))

    private class World(val t: TestTenant, val ds: DataSource, val ctx: GatewayContext)
    private fun world(): World {
        val t = f.tenant(); val ds = f.register(t)
        f.query(t, ds, "customers", params = listOf(QueryParamSpec("status", ParamType.STRING, false, node("SECRET-DEFAULT"))))
        f.mutation(t, ds)
        f.snapshots.save(snapshot(t, ds))
        return World(t, ds, f.ctx(t))
    }

    @Test fun `the catalog lists structure and approved operations and nothing that can reach the source`() {
        val w = world()
        val c = provider.catalog(w.ctx)
        val ds = c.dataSources.single()
        assertThat(ds.id).isEqualTo(w.ds.id); assertThat(ds.type).isEqualTo("fake")
        assertThat(ds.queries.map { it.id }).containsExactly("customers")
        assertThat(ds.queries.single().params.map { it.name }).containsExactly("status")
        assertThat(ds.mutations.map { it.id }).containsExactly("create-customer")
        assertThat(ds.schema!!.entities.single().fields.map { it.name }).contains("id", "status")

        val json = c.toJson().toString()
        for (forbidden in listOf(f.secret, "db.internal-looking.example.com", w.ds.credentialRef!!, "SELECT 1", "SECRET-DEFAULT", "host=", "sample skipped", "internal", "\"customers\",\"target\"", "authValue"))
            assertThat(json).doesNotContain(forbidden)
        assertThat(json).doesNotContain("configNonSecret").doesNotContain("credential").doesNotContain("\"sql\"").doesNotContain("\"target\"").doesNotContain("\"default\"")
        assertThat(c.toString()).doesNotContain(f.secret)
    }

    @Test fun `no sample value is included unless asked for`() {
        val w = world()
        val c = provider.catalog(w.ctx)
        assertThat(c.dataSources.single().schema!!.entities.single().sample).isEmpty()
        val json = c.toJson().toString()
        for (raw in listOf("ada1@example.com", "123-45-678", "pbkdf2", "HASH1", "Ada1")) assertThat(json).doesNotContain(raw)
    }

    @Test fun `masked samples, when asked for, carry no raw personal data and no sensitive column at all`() {
        val w = world()
        val c = provider.catalog(w.ctx, AiCatalogOptions(includeMaskedSamples = true))
        val sample = c.dataSources.single().schema!!.entities.single().sample
        assertThat(sample.size <= AiCatalogOptions.MAX_SAMPLE_ROWS).isTrue()
        assertThat(sample).isNotEmpty()
        for (r in sample) {
            assertThat(r.keys.toSet()).isEqualTo(setOf("id", "status"))                              // email, ssn, password_hash, first_name removed outright
            assertThat(DataJson.text(r["status"]!!)).isEqualTo("active")                             // harmless values stay useful
        }
        val json = c.toJson().toString()
        for (raw in listOf("ada1@example.com", "ada2@example.com", "123-45-678", "pbkdf2", "HASH", "Ada1")) assertThat(json).doesNotContain(raw)
        // the structure still says WHICH columns are sensitive, so the planner can avoid binding to them
        val fields = c.dataSources.single().schema!!.entities.single().fields.associateBy { it.name }
        assertThat(fields["email"]!!.sensitive).isTrue(); assertThat(fields["ssn"]!!.sensitive).isTrue(); assertThat(fields["password_hash"]!!.sensitive).isTrue()
        assertThat(fields["status"]!!.sensitive).isFalse(); assertThat(fields["id"]!!.sensitive).isFalse()
    }

    @Test fun `what the caller may not do is not listed`() {
        val w = world()
        f.authorizer.denied += GatewayOperation.MUTATION_EXECUTE
        var ds = provider.catalog(w.ctx).dataSources.single()
        assertThat(ds.mutations).isEmpty(); assertThat(ds.queries).isNotEmpty(); assertThat(ds.schema).isNotNull()
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE
        ds = provider.catalog(w.ctx).dataSources.single()
        assertThat(ds.queries).isEmpty(); assertThat(ds.schema).isNotNull()
        f.authorizer.denied += GatewayOperation.DATASOURCE_READ
        assertThat(provider.catalog(w.ctx).dataSources).isEmpty()                                  // not even its existence
        assertThat(f.audit.actions().filter { it == DataAuditActions.DENIED }).isEmpty()           // filtering is silent: nothing to enumerate through the audit trail either
    }

    @Test fun `an authorizer that fails yields an empty catalog, never a full one`() {
        val w = world()
        f.authorizer.failWith = IllegalStateException("access service down")
        assertThat(provider.catalog(w.ctx).dataSources).isEmpty()
    }

    @Test fun `another tenant sees nothing of this one`() {
        val a = world(); val b = world()
        val forB = provider.catalog(b.ctx)
        assertThat(forB.tenantId).isEqualTo(b.t.tenantId)
        assertThat(forB.dataSources.map { it.id }).containsExactly(b.ds.id)
        assertThat(forB.toJson().toString()).doesNotContain(a.ds.id.toString())
        f.authorizer.allowedTenants = setOf(a.t.tenantId)                                          // B is not even allowed
        assertThat(provider.catalog(b.ctx).dataSources).isEmpty()
        assertThat(provider.catalog(a.ctx).dataSources.map { it.id }).containsExactly(a.ds.id)
    }

    @Test fun `a snapshot or operation recorded for another data source is not attributed to this one`() {
        val a = world()
        val other = f.register(a.t)                                                                // a second data source of the same tenant, nothing registered on it
        val c = provider.catalog(a.ctx)
        val bare = c.dataSources.single { it.id == other.id }
        assertThat(bare.schema).isNull(); assertThat(bare.queries).isEmpty(); assertThat(bare.mutations).isEmpty()
        assertThat(c.dataSources.single { it.id == a.ds.id }.queries.map { it.id }).containsExactly("customers")
    }

    @Test fun `disabled data sources are not offered to a planner`() {
        val w = world()
        f.repo.save(w.ds.revised(f.clock.instant(), status = DataSourceStatus.DISABLED))
        assertThat(provider.catalog(w.ctx).dataSources).isEmpty()
    }

    @Test fun `the catalog is bounded and ordered deterministically`() {
        val t = f.tenant(); val ctx = f.ctx(t)
        repeat(DefaultAiDataCatalogProvider.MAX_DATA_SOURCES + 5) { f.register(t) }
        val ids = provider.catalog(ctx).dataSources.map { it.id.toString() }
        assertThat(ids.size).isEqualTo(DefaultAiDataCatalogProvider.MAX_DATA_SOURCES)
        assertThat(ids).isEqualTo(ids.sorted())
        assertThat(provider.catalog(ctx).dataSources.map { it.id.toString() }).isEqualTo(ids)
    }

    @Test fun `building a catalog is audited by counts only`() {
        val w = world()
        provider.catalog(w.ctx, AiCatalogOptions(includeMaskedSamples = true))
        val e = f.audit.events.single { it.action == DataAuditActions.AI_CATALOG_BUILT }
        assertThat(e.tenantId).isEqualTo(w.t.tenantId)
        assertThat(e.details["dataSources"]).isEqualTo(1)
        assertThat(e.details.values.map { it.toString() }.none { it.contains("customers") || it.contains("example.com") }).isTrue()
    }

    @Test fun `the AI view of a schema is what the catalog is built from`() {
        val w = world()
        val direct = AiSafeSchema.of(snapshot(w.t, w.ds))
        val viaCatalog = provider.catalog(w.ctx).dataSources.single().schema!!
        assertThat(viaCatalog.entities.map { e -> e.fields }).isEqualTo(direct.entities.map { e -> e.fields })
        assertThat(viaCatalog.entities.map { e -> e.relations }).isEqualTo(direct.entities.map { e -> e.relations })
        assertThat(SqlQueryDefinition("x", w.t.tenantId, w.ds.id, "SELECT 1").id).isEqualTo("x")
    }
}
