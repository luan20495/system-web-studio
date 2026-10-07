package com.systemwebstudio.wiring

import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.SchemaSnapshot
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.wiring.persistence.JdbcDataSourceSlotBindings
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import com.systemwebstudio.wiring.persistence.JdbcSourceSchemaStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * Management API contract §3.1 / §3.6 / §5 against the real stack (Spring, PostgreSQL, Redis, C1 access, the real `audit_events`): PATCH is one transaction,
 * DELETE has a lifecycle, a change that cannot be audited is not applied, TEST and LIVE are exact and separate. Failures are injected into the REAL audit sink
 * and vault (see [FaultAudit], [FaultVault]) so a rollback is observed in the database, not assumed.
 */
@Import(ManagementTestBeans::class)
class DataManagementLifecycleTests : ManagementApiTestBase() {
    private fun row(id: String) = jdbc.queryForMap("SELECT name, status, version, credential_ref, config_nonsecret::text AS config FROM data_sources WHERE id = ?", UUID.fromString(id))

    // ------------------------------------------------------------------------------------------------ PATCH is atomic

    @Test
    fun `A - a multi-field PATCH is one transaction, one new version and one audit trail`() {
        val w = admin(); val id = w.created()
        val r = w.s.patch("${w.base}/$id", """{"name":"renamed.x","config":{"host":"other.example.com"},"status":"DISABLED"}""")
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(200)
        val b = w.s.body(r)
        assertThat(b.get("name").asString()).isEqualTo("renamed.x"); assertThat(b.get("config").get("host").asString()).isEqualTo("other.example.com")
        assertThat(b.get("status").asString()).isEqualTo("DISABLED"); assertThat(b.get("version").asLong()).describedAs("three fields, ONE bump").isEqualTo(2L)
        val row = row(id)
        assertThat(row["name"]).isEqualTo("renamed.x"); assertThat(row["status"]).isEqualTo("DISABLED"); assertThat(row["version"]).isEqualTo(2L)
        assertThat(auditCount(id, "DATASOURCE_UPDATED")).isEqualTo(1L); assertThat(auditCount(id, "DATASOURCE_STATUS_CHANGED")).isEqualTo(1L)
    }

    @Test
    fun `B - expectedVersion that matches applies the change`() {
        val w = admin(); val id = w.created()
        val ok = w.s.patch("${w.base}/$id", """{"name":"renamed.b","expectedVersion":1}""")
        assertThat(status(ok)).describedAs(ok.response.contentAsString).isEqualTo(200); assertThat(w.s.body(ok).get("version").asLong()).isEqualTo(2L)
        val next = w.s.patch("${w.base}/$id", """{"status":"DISABLED","expectedVersion":2}""")
        assertThat(status(next)).isEqualTo(200); assertThat(w.s.body(next).get("version").asLong()).isEqualTo(3L)
    }

    @Test
    fun `C - expectedVersion that does not match is a 409 CONFLICT and the data source is unchanged`() {
        val w = admin(); val id = w.created(credential = mapOf("authValue" to secret))
        assertThat(status(w.s.patch("${w.base}/$id", """{"name":"renamed.c"}"""))).isEqualTo(200)             // now version 2
        val before = row(id); val audits = auditCount(id, "DATASOURCE_UPDATED", "DATASOURCE_STATUS_CHANGED")
        val stale = w.s.patch("${w.base}/$id", """{"name":"stale.write","status":"DISABLED","config":{"host":"x.example.com"},"expectedVersion":1}""")
        assertThat(status(stale)).isEqualTo(409); assertThat(code(w.s, stale)).isEqualTo("CONFLICT")
        assertThat(row(id)).isEqualTo(before)
        assertThat(auditCount(id, "DATASOURCE_UPDATED", "DATASOURCE_STATUS_CHANGED")).describedAs("a refused change leaves no audit row").isEqualTo(audits)
        assertNoSecretAnywhere(secret, stale)
    }

    @Test
    fun `D - when the second step fails the first one is rolled back too`() {
        val w = admin(); val id = w.created(); val before = row(id)
        faultAudit.failOn = setOf("DATASOURCE_STATUS_CHANGED")                                                // the name/config change audits fine, the status change cannot be audited
        val r = w.s.patch("${w.base}/$id", """{"name":"half.done","config":{"host":"half.example.com"},"status":"DISABLED"}""")
        assertThat(status(r)).isEqualTo(500); assertThat(code(w.s, r)).isIn("INTERNAL", "INTERNAL_ERROR")
        assertThat(row(id)).describedAs("no partial state: neither the name, the config nor the status").isEqualTo(before)
        assertThat(auditCount(id, "DATASOURCE_UPDATED", "DATASOURCE_STATUS_CHANGED")).describedAs("not even the audit row of the first step survived").isZero()
        faultAudit.failOn = null
        val again = w.s.patch("${w.base}/$id", """{"name":"half.done","config":{"host":"half.example.com"},"status":"DISABLED"}""")
        assertThat(status(again)).describedAs("the row is not left locked or half-written").isEqualTo(200); assertThat(w.s.body(again).get("version").asLong()).isEqualTo(2L)
    }

    @Test
    fun `E - when the audit sink fails the data source is unchanged`() {
        val w = admin(); val id = w.created(); val before = row(id)
        faultAudit.failOn = emptySet()                                                                        // every audit write fails
        for (body in listOf("""{"name":"audit.fail"}""", """{"status":"DISABLED"}""", """{"config":{"host":"a.example.com"}}""")) {
            val r = w.s.patch("${w.base}/$id", body)
            assertThat(status(r)).describedAs(body).isEqualTo(500)
            assertThat(row(id)).describedAs(body).isEqualTo(before)
        }
    }

    @Test
    fun `a PATCH validates every field before it applies any - a bad config, a taken name or a bad status changes nothing`() {
        val w = admin(); val a = w.created(); val taken = uniq("taken")
        assertThat(status(w.create(name = taken))).isEqualTo(201)
        val before = row(a)
        val badConfig = w.s.patch("${w.base}/$a", """{"name":"valid.new","status":"DISABLED","config":{"password":"x"}}""")
        assertThat(status(badConfig)).isEqualTo(400); assertThat(code(w.s, badConfig)).isEqualTo("INVALID_CONFIG")
        val dup = w.s.patch("${w.base}/$a", """{"name":"${taken.uppercase()}","status":"DISABLED"}""")
        assertThat(status(dup)).isEqualTo(409); assertThat(code(w.s, dup)).isEqualTo("CONFLICT")
        for (bad in listOf("""{"status":"DELETED","name":"valid.new"}""", """{"expectedVersion":0,"name":"valid.new"}""", """{"expectedVersion":"1","name":"valid.new"}""", """{"expectedVersion":1.5,"name":"valid.new"}""",
            """{"expectedVersion":1}""", """{"version":1,"name":"valid.new"}""", """{"tenantId":"${w.tenant}","name":"valid.new"}"""))
            assertThat(status(w.s.patch("${w.base}/$a", bad))).describedAs(bad).isEqualTo(400)
        assertThat(row(a)).isEqualTo(before)
    }

    @Test
    fun `a PATCH that changes nothing is not a new version and is not audited`() {
        val w = admin(); val id = w.created(); val audits = auditCount(id, "DATASOURCE_UPDATED", "DATASOURCE_STATUS_CHANGED")
        val r = w.s.patch("${w.base}/$id", """{"status":"ACTIVE"}""")
        assertThat(status(r)).isEqualTo(200); assertThat(w.s.body(r).get("version").asLong()).isEqualTo(1L)
        assertThat(auditCount(id, "DATASOURCE_UPDATED", "DATASOURCE_STATUS_CHANGED")).isEqualTo(audits)
    }

    // ------------------------------------------------------------------------------------------------ DELETE lifecycle

    private class Seed(val ds: UUID)

    /** a data source with a credential, one query, one mutation and one schema snapshot */
    private fun seeded(w: W): Seed {
        val id = UUID.fromString(w.created(credential = mapOf("authValue" to secret)))
        JdbcQueryCatalog(jdbc).save(SqlQueryDefinition("q.list", w.tenant, id, "SELECT 1", emptyList(), 10))
        JdbcMutationCatalog(jdbc).save(MutationDefinition("m.create", w.tenant, id, MutationKind.CREATE, "t", listOf(QueryParamSpec("a", ParamType.STRING))))
        JdbcSourceSchemaStore(jdbc).save(SchemaSnapshot(UUID.randomUUID(), w.tenant, id, 1, Instant.now(), "fp", 1, null, false, DiscoveredSchema(emptyList())))
        return Seed(id)
    }

    private fun idem(w: W, ds: UUID, key: String, state: String, mutation: String = "m.create") = jdbc.update(
        """INSERT INTO data_idempotency (tenant_id, data_source_id, mutation_id, idem_key, fingerprint, state, lease_until, expires_at)
           VALUES (?, ?, ?, ?, 'fp', ?, now() + interval '1 hour', now() + interval '30 days')""", w.tenant, ds, mutation, key, state)

    private fun dependents(w: W, ds: UUID): Map<String, Long> = mapOf(
        "data_sources" to count("SELECT count(*) FROM data_sources WHERE id = ?", ds),
        "data_queries" to count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds),
        "data_mutations" to count("SELECT count(*) FROM data_mutations WHERE data_source_id = ?", ds),
        "source_schemas" to count("SELECT count(*) FROM source_schemas WHERE data_source_id = ?", ds),
        "data_idempotency" to count("SELECT count(*) FROM data_idempotency WHERE data_source_id = ?", ds),
        "data_credentials" to count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)
    )

    @Test
    fun `A - a source with a TEST or a LIVE binding cannot be deleted and nothing is removed`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws)
        JdbcQueryCatalog(jdbc).save(SqlQueryDefinition("q.list", p.tenant, ds.id, "SELECT 1", emptyList(), 10))
        for (mode in listOf("TEST", "LIVE")) {
            assertThat(status(put(p, mode, "erp-db", ds.id))).isEqualTo(200)
            val refused = p.admin.delete("/api/v1/workspaces/${p.sc.ws}/data-sources/${ds.id}")
            assertThat(status(refused)).describedAs(mode).isEqualTo(409); assertThat(code(p.admin, refused)).isEqualTo("CONFLICT")
            assertThat(count("SELECT count(*) FROM data_sources WHERE id = ?", ds.id)).isEqualTo(1L)
            assertThat(count("SELECT count(*) FROM data_queries WHERE data_source_id = ?", ds.id)).isEqualTo(1L)
            assertThat(status(p.admin.delete("${p.bindings}/$mode/erp-db"))).isEqualTo(204)
        }
        assertThat(status(p.admin.delete("/api/v1/workspaces/${p.sc.ws}/data-sources/${ds.id}"))).isEqualTo(204)
    }

    @Test
    fun `B and C - a RESERVED or UNKNOWN idempotency row (a write in flight or of unknown outcome) blocks the delete`() {
        for (state in listOf("RESERVED", "UNKNOWN")) {
            val w = admin(); val seed = seeded(w); val id = seed.ds.toString()
            idem(w, seed.ds, "key-$state-0001", state)
            val before = dependents(w, seed.ds); val audits = auditCount(id, "DATASOURCE_DELETED")
            val r = w.s.delete("${w.base}/$id")
            assertThat(status(r)).describedAs(state).isEqualTo(409); assertThat(code(w.s, r)).isEqualTo("CONFLICT")
            assertThat(dependents(w, seed.ds)).describedAs("$state: nothing was removed").isEqualTo(before)
            assertThat(auditCount(id, "DATASOURCE_DELETED")).isEqualTo(audits)
            // the evidence is released (the row finishes or expires), then the delete goes through
            jdbc.update("UPDATE data_idempotency SET state = 'DONE' WHERE data_source_id = ?", seed.ds)
            assertThat(status(w.s.delete("${w.base}/$id"))).describedAs("$state, after it finished").isEqualTo(204)
        }
    }

    @Test
    fun `D, E and F - finished idempotency rows do not block, and the delete removes the source, its definitions, snapshots, idempotency rows and credential`() {
        val w = admin(); val seed = seeded(w); val id = seed.ds.toString()
        idem(w, seed.ds, "key-done-00001", "DONE"); idem(w, seed.ds, "key-done-00002", "DONE", mutation = "m.other")
        val other = admin(); val otherSeed = seeded(other)                                                    // another tenant's source is not touched
        assertThat(dependents(w, seed.ds)).containsEntry("data_credentials", 1L).containsEntry("data_idempotency", 2L)
        val r = w.s.delete("${w.base}/$id")
        assertThat(status(r)).describedAs(r.response.contentAsString).isEqualTo(204)
        assertThat(dependents(w, seed.ds).filterKeys { it != "data_credentials" }.values).allMatch { it == 0L }
        assertThat(dependents(w, seed.ds)).describedAs("the credential is destroyed with its source").containsEntry("data_credentials", 0L)
        assertThat(auditCount(id, "DATASOURCE_DELETED")).isEqualTo(1L)
        assertThat(dependents(other, otherSeed.ds)).describedAs("the other tenant keeps everything")
            .containsEntry("data_sources", 1L).containsEntry("data_queries", 1L).containsEntry("data_mutations", 1L).containsEntry("source_schemas", 1L).containsEntry("data_credentials", 1L)
        assertNoSecretAnywhere(secret, r)
    }

    @Test
    fun `G - when the vault cannot discard the credential nothing is deleted`() {
        val w = admin(); val seed = seeded(w); val id = seed.ds.toString(); val before = dependents(w, seed.ds); val ref = row(id)["credential_ref"]
        faultVault.failDiscard = true
        val r = w.s.delete("${w.base}/$id")
        assertThat(status(r)).isEqualTo(500); assertThat(code(w.s, r)).isIn("INTERNAL", "INTERNAL_ERROR")
        assertThat(dependents(w, seed.ds)).describedAs("the source did not disappear while its credential is still stored").isEqualTo(before)
        assertThat(row(id)["credential_ref"]).isEqualTo(ref)
        assertThat(auditCount(id, "DATASOURCE_DELETED")).isZero()
        faultVault.failDiscard = false
        assertThat(status(w.s.delete("${w.base}/$id"))).describedAs("and the delete works once the vault does").isEqualTo(204)
        assertThat(dependents(w, seed.ds).values).allMatch { it == 0L }
    }

    @Test
    fun `H - when the delete cannot be audited nothing is deleted`() {
        val w = admin(); val seed = seeded(w); val id = seed.ds.toString(); val before = dependents(w, seed.ds)
        faultAudit.failOn = setOf("DATASOURCE_DELETED")
        val r = w.s.delete("${w.base}/$id")
        assertThat(status(r)).isEqualTo(500)
        assertThat(dependents(w, seed.ds)).isEqualTo(before)
        assertThat(auditCount(id, "DATASOURCE_DELETED")).isZero()
    }

    // ------------------------------------------------------------------------------------------------ credentials and bindings are audited in the same unit

    @Test
    fun `a credential change that cannot be audited is not applied`() {
        val w = admin(); val id = w.created(credential = mapOf("authValue" to secret)); val before = row(id)
        val cipher = jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ?", String::class.java, w.tenant)
        faultAudit.failOn = emptySet()
        val put = w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"second-$secret"}}""")
        val del = w.s.delete("${w.base}/$id/credential")
        assertThat(status(put)).isEqualTo(500); assertThat(status(del)).isEqualTo(500)
        assertThat(row(id)).describedAs("same reference, same version").isEqualTo(before)
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).describedAs("no orphaned and no lost credential").isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM data_credentials WHERE tenant_id = ?", String::class.java, w.tenant)).isEqualTo(cipher)
        faultAudit.failOn = null
        assertThat(status(w.s.put("${w.base}/$id/credential", """{"credential":{"authValue":"second-$secret"}}"""))).isEqualTo(200)
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).isEqualTo(1L)
        assertNoSecretAnywhere(secret, put, del); assertNoSecretAnywhere("second-$secret", put)
    }

    @Test
    fun `a credential whose keys do not fit the connector is refused INVALID_CREDENTIAL`() {
        val w = admin(); val config = mapOf("host" to "db.example.com", "database" to "shop")
        val id = w.created(type = "postgres", config = config)
        val bad = w.s.put("${w.base}/$id/credential", """{"credential":{"token":"$secret"}}""")
        assertThat(status(bad)).isEqualTo(400); assertThat(code(w.s, bad)).isEqualTo("INVALID_CREDENTIAL")
        val badCreate = w.create(type = "postgres", config = config, credential = mapOf("apiKey" to secret))
        assertThat(status(badCreate)).isEqualTo(400); assertThat(code(w.s, badCreate)).isEqualTo("INVALID_CREDENTIAL")
        val good = w.s.put("${w.base}/$id/credential", """{"credential":{"username":"u","password":"$secret"}}""")
        assertThat(status(good)).isEqualTo(200)
        assertThat(count("SELECT count(*) FROM data_credentials WHERE tenant_id = ?", w.tenant)).isEqualTo(1L)
        assertNoSecretAnywhere(secret, bad, badCreate, good)
    }

    @Test
    fun `a binding change that cannot be audited is not applied`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws)
        faultAudit.failOn = emptySet()
        assertThat(status(put(p, "LIVE", "erp-db", ds.id))).isEqualTo(500)
        assertThat(bindingRows(p)).isEmpty()
        faultAudit.failOn = null
        assertThat(status(put(p, "LIVE", "erp-db", ds.id))).isEqualTo(200)
        faultAudit.failOn = emptySet()
        assertThat(status(p.admin.delete("${p.bindings}/LIVE/erp-db"))).isEqualTo(500)
        assertThat(bindingRows(p).map { it["mode"] }).describedAs("the unbind was rolled back").containsExactly("LIVE")
    }

    // ------------------------------------------------------------------------------------------------ TEST / LIVE are exact and separate

    @Test
    fun `mode is exactly TEST or LIVE - any other spelling is INVALID_PARAMS and nothing is written`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws)
        for (mode in listOf("live", "test", "Test", "Live", "STAGING", "lIVE")) {
            val r = put(p, mode, "erp-db", ds.id)
            assertThat(status(r)).describedAs("PUT $mode").isEqualTo(400); assertThat(code(p.admin, r)).isEqualTo("INVALID_PARAMS")
            assertThat(status(p.admin.delete("${p.bindings}/${mode}/erp-db"))).describedAs("DELETE $mode").isEqualTo(400)
        }
        assertThat(bindingRows(p)).isEmpty()
        assertThat(status(put(p, "TEST", "erp-db", ds.id))).isEqualTo(200)
        assertThat(status(p.admin.delete("${p.bindings}/live/erp-db"))).describedAs("a lower-case delete does not remove the TEST row either").isEqualTo(400)
        assertThat(bindingRows(p).map { it["mode"] }).containsExactly("TEST")
    }

    @Test
    fun `TEST never falls back to LIVE and LIVE never falls back to TEST`() {
        val p = app(); val ds = source(p.tenant, p.sc.ws); val bindings = JdbcDataSourceSlotBindings(jdbc)
        assertThat(status(put(p, "TEST", "erp-db", ds.id))).isEqualTo(200)
        assertThat(bindings.bindings(p.tenant, p.sc.projectId, ExecutionMode.TEST)).containsEntry("erp-db", ds.id)
        assertThat(bindings.bindings(p.tenant, p.sc.projectId, ExecutionMode.LIVE)).describedAs("only TEST is bound: LIVE resolves to nothing (DATA_SOURCE_UNBOUND at run time)").isEmpty()
        assertThat(status(p.admin.delete("${p.bindings}/TEST/erp-db"))).isEqualTo(204)
        assertThat(status(put(p, "LIVE", "erp-db", ds.id))).isEqualTo(200)
        assertThat(bindings.bindings(p.tenant, p.sc.projectId, ExecutionMode.LIVE)).containsEntry("erp-db", ds.id)
        assertThat(bindings.bindings(p.tenant, p.sc.projectId, ExecutionMode.TEST)).describedAs("only LIVE is bound: TEST resolves to nothing").isEmpty()
    }
}
