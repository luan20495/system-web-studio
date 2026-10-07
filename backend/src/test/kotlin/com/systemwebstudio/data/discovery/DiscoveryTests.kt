package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.ClockedRateLimitGate
import com.systemwebstudio.data.InMemoryCredentialStore
import com.systemwebstudio.data.InMemoryDataSourceRepository
import com.systemwebstudio.data.InMemorySchemaStore
import com.systemwebstudio.data.RecordingAuditSink
import com.systemwebstudio.data.ScriptedAuthorizer
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataConnector
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SecretsCryptoCredentialVault
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.runtime.SecretsCrypto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** T9: masked samples, versioned snapshots, refresh rules, permissions, tenant isolation and the AI-safe projection. */
class DiscoveryTests {
    private fun node(v: Any?) = DataJson.toNode(v)
    private fun row(vararg kv: Pair<String, Any?>) = kv.associate { it.first to node(it.second) }
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }

    // ------------------------------------------------------------------------------------------------ masker

    @Test fun `personal secret and identifier columns are masked by name`() {
        val m = SampleMasker.maskRow(row("email" to "ada@example.com", "password_hash" to "pbkdf2\$1\$abcdef", "firstName" to "Ada", "ssn" to "123-45-6789",
            "creditCardNumber" to "4111 1111 1111 1111", "phone" to "+84 912 345 678", "api_key" to "sk-live-abcdef123456", "city" to "Hue", "balance" to 12.5, "status" to "active"))
        val text = m.toString()
        for (leak in listOf("ada@example.com", "pbkdf2", "123-45-6789", "4111 1111", "912 345", "sk-live", "abcdef123456")) assertThat(text).doesNotContain(leak)
        assertThat(m["balance"].toString()).isEqualTo("12.5")                    // harmless columns stay useful
        assertThat(m["status"].toString()).isEqualTo("\"active\"")
        assertThat(m["firstName"].toString()).doesNotContain("Ada")
    }

    @Test fun `sensitive values are caught by their shape even in an innocent column`() {
        val inputs = mapOf(
            "note" to "contact ada@example.com today", "info" to "card 4111111111111111 ok", "x" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "y" to "ghp_1234567890abcdefghijklmnopqrstuvwxyz", "z" to "call +1 415 555 0132 now", "w" to "from 192.168.4.17 yesterday", "v" to "123-45-6789", "u" to "GB82WEST12345698765432"
        )
        val out = SampleMasker.maskRow(inputs.mapValues { node(it.value) }).toString()
        for (leak in listOf("ada@example.com", "4111111111111111", "eyJhbGci", "ghp_1234", "415 555 0132", "192.168.4.17", "123-45-6789", "GB82WEST")) assertThat(out).doesNotContain(leak)
    }

    @Test fun `masking is total bounded and idempotent`() {
        val huge = "a".repeat(100_000) + " ada@example.com"
        val nested = mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("email" to "ada@example.com")))), "list" to (1..50).map { "item$it" })
        val m = SampleMasker.maskRow(row("big" to huge, "nested" to nested, "n" to null, "blob" to listOf(1, 2, 3)))
        assertThat(m.toString().length).isLessThan(3_000)
        assertThat(m.toString()).doesNotContain("ada@example.com")
        assertThat(SampleMasker.maskRow(m).toString()).isEqualTo(m.toString())     // masking masked output changes nothing
        assertThat(SampleMasker.maskRows((1..20).map { row("a" to it) })).hasSize(5)
        assertThat(SampleMasker.maskRow((1..200).associate { "c$it" to node(it) })).hasSize(60)
    }

    @Test fun `uuid style identifiers stay readable and ordinary words are untouched`() {
        val id = UUID.randomUUID().toString()
        val m = SampleMasker.maskRow(row("id" to id, "title" to "Quarterly report", "qty" to 3))
        assertThat(m["id"].toString()).contains(id)
        assertThat(m["title"].toString()).contains("Quarterly report")
    }

    // ------------------------------------------------------------------------------------------------ service

    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 9).toByte() })
    private val repo = InMemoryDataSourceRepository()
    private val vault = SecretsCryptoCredentialVault(SecretsCrypto(key), InMemoryCredentialStore())
    private val audit = RecordingAuditSink()
    private val authorizer = ScriptedAuthorizer()
    private val snapshots = InMemorySchemaStore()
    private var now = Instant.parse("2026-10-05T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }
    private var schema = DiscoveredSchema(listOf(
        DiscoveredEntity("customers", "public", EntityKind.TABLE,
            listOf(DiscoveredField("id", NormalizedType.INTEGER, false, "int4", true), DiscoveredField("email", NormalizedType.STRING, true, "text"), DiscoveredField("name", NormalizedType.STRING, true), DiscoveredField("plan", NormalizedType.STRING, true)),
            listOf("id"), emptyList(), mapOf("kind" to "table"),
            listOf(row("id" to 1, "email" to "ada@example.com", "name" to "Ada Lovelace", "plan" to "pro"), row("id" to 2, "email" to "alan@example.com", "name" to "Alan Turing", "plan" to "free")))
    ))
    private var discoverCalls = 0
    private var lastOptions: DiscoveryOptions? = null
    private val connector = object : DataConnector {
        override val type = "fake"
        override fun validateConfig(config: Map<String, String>) {}
        override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult = ConnectionTestResult.Ok(1)
        override fun discovery() = object : SchemaDiscovery {
            override fun discover(ds: DataSourceRef, cred: ResolvedCredential) = discover(ds, cred, DiscoveryOptions())
            override fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: DiscoveryOptions): DiscoveredSchema {
                discoverCalls++; lastOptions = options
                // a careless connector: it returns RAW sample rows regardless of what was asked — the service must still not store them
                return schema
            }
        }
        override fun executor() = object : QueryExecutor { override fun execute(req: com.systemwebstudio.data.query.QueryRequest, ds: DataSourceRef, cred: ResolvedCredential) = throw UnsupportedOperationException() }
    }
    private val tenantA = TestTenant()
    private val tenantB = TestTenant()
    private fun ctx(t: TestTenant) = t.context()
    private val dsService = DataSourceService(repo, vault, DataConnectorRegistry(listOf(connector)), { _, _, _ -> true }, audit)
    private val gate = ClockedRateLimitGate { clock.instant().toEpochMilli() }
    private val svc = DiscoveryService(dsService, snapshots, GatewayGuard(authorizer, audit), gate, audit, clock)
    private fun register(t: TestTenant) = repo.save(DataSource(DataSourceRef(UUID.randomUUID(), t.tenantId, "fake", mapOf("k" to "v")), "src", credentialRef = vault.store(t.tenantId, mapOf("authValue" to "pw")))).id

    @Test fun `refresh stores a versioned snapshot and with no samples requested no sample exists anywhere`() {
        val id = register(tenantA)
        val r = svc.refresh(ctx(tenantA), id)
        assertThat(r.snapshot.version).isEqualTo(1)
        assertThat(r.snapshot.discoveredAt).isEqualTo(now)
        assertThat(r.snapshot.includesSamples).isFalse()
        assertThat(r.changed).isTrue()
        assertThat(r.snapshot.schema.entities.single().sample).isEmpty()        // raw samples from the connector were dropped
        assertThat(lastOptions!!.sampleRows).isEqualTo(0)
        assertThat(snapshots.latest(tenantA.tenantId, id)!!.id).isEqualTo(r.snapshot.id)
        assertThat(audit.actions()).contains(DataAuditActions.SCHEMA_REFRESHED)
    }

    @Test fun `samples are masked before they are stored even when the connector forgot`() {
        val id = register(tenantA)
        val r = svc.refresh(ctx(tenantA), id, includeSamples = true)
        assertThat(r.snapshot.includesSamples).isTrue()
        val stored = snapshots.all.joinToString { it.schema.toString() }
        for (leak in listOf("ada@example.com", "alan@example.com", "Ada Lovelace", "Alan Turing")) assertThat(stored).doesNotContain(leak)
        assertThat(stored).contains("pro")                                       // harmless columns survive
        assertThat(lastOptions!!.sampleRows).isGreaterThan(0)
    }

    @Test fun `the ai view has no sensitive column value at all and no metadata`() {
        val id = register(tenantA)
        svc.refresh(ctx(tenantA), id, includeSamples = true)
        val view = svc.aiView(ctx(tenantA), id)!!
        val text = view.toString()
        for (leak in listOf("ada@example.com", "Ada", "Lovelace", "Alan", "turing")) assertThat(text.lowercase()).doesNotContain(leak.lowercase())
        val e = view.entities.single()
        assertThat(e.fields.first { it.name == "email" }.sensitive).isTrue()
        assertThat(e.fields.first { it.name == "plan" }.sensitive).isFalse()
        assertThat(e.sample.all { !it.containsKey("email") && !it.containsKey("name") }).isTrue()
        assertThat(e.sample.all { it.containsKey("plan") }).isTrue()
        assertThat(e.primaryKey).containsExactly("id")
    }

    @Test fun `a second refresh bumps the version reports the diff and is rate limited`() {
        val id = register(tenantA)
        svc.refresh(ctx(tenantA), id)
        assertThat(failure { svc.refresh(ctx(tenantA), id) }.code).isEqualTo(FailureCodes.REFRESH_TOO_SOON)
        assertThat(discoverCalls).isEqualTo(1)
        now = now.plusSeconds(31)
        schema = schema.copy(entities = schema.entities.map { it.copy(fields = it.fields + DiscoveredField("created", NormalizedType.TIMESTAMP, true)) } +
            DiscoveredEntity("orders", "public", EntityKind.TABLE, listOf(DiscoveredField("id", NormalizedType.INTEGER, false, null, true)), listOf("id")))
        val r = svc.refresh(ctx(tenantA), id)
        assertThat(r.snapshot.version).isEqualTo(2)
        assertThat(r.changed).isTrue()
        assertThat(r.diff!!.addedEntities).containsExactly("public.orders")
        assertThat(r.diff!!.changedEntities["public.customers"]!!.addedFields).containsExactly("created")
        now = now.plusSeconds(31)
        val same = svc.refresh(ctx(tenantA), id)
        assertThat(same.snapshot.version).isEqualTo(3)
        assertThat(same.changed).isFalse()
        assertThat(same.diff!!.isEmpty).isTrue()
        assertThat(svc.history(ctx(tenantA), id).map { it.version }).containsExactly(3, 2, 1)
    }

    @Test fun `another tenant cannot refresh or read a schema and learns nothing`() {
        val id = register(tenantA)
        svc.refresh(ctx(tenantA), id)
        assertThat(failure { svc.refresh(ctx(tenantB), id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failure { svc.latest(ctx(tenantB), id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failure { svc.history(ctx(tenantB), id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(failure { svc.aiView(ctx(tenantB), id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(snapshots.latest(tenantB.tenantId, id)).isNull()
    }

    @Test fun `discovery needs its permission and samples need a second one`() {
        val id = register(tenantA)
        authorizer.denied += GatewayOperation.SCHEMA_DISCOVER
        assertThat(failure { svc.refresh(ctx(tenantA), id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(discoverCalls).isEqualTo(0)
        authorizer.denied.clear(); authorizer.denied += GatewayOperation.SCHEMA_SAMPLE
        assertThat(failure { svc.refresh(ctx(tenantA), id, includeSamples = true) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(discoverCalls).isEqualTo(0)
        svc.refresh(ctx(tenantA), id)                                            // structure alone is still allowed
        assertThat(audit.actions()).contains(DataAuditActions.DENIED)
        authorizer.denied.clear(); authorizer.denied += GatewayOperation.DATASOURCE_READ
        assertThat(failure { svc.latest(ctx(tenantA), id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
    }

    @Test fun `an authorizer that throws denies`() {
        val id = register(tenantA)
        authorizer.failWith = IllegalStateException("db down")
        assertThat(failure { svc.refresh(ctx(tenantA), id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(discoverCalls).isEqualTo(0)
    }

    @Test fun `the fingerprint covers structure but not samples or order`() {
        val a = schema
        val reordered = a.copy(entities = a.entities.map { it.copy(fields = it.fields.reversed(), sample = emptyList()) })
        assertThat(SchemaFingerprint.of(a)).isEqualTo(SchemaFingerprint.of(reordered))
        val changed = a.copy(entities = a.entities.map { it.copy(fields = it.fields.map { f -> if (f.name == "plan") f.copy(type = NormalizedType.INTEGER) else f }) })
        assertThat(SchemaFingerprint.of(a)).isNotEqualTo(SchemaFingerprint.of(changed))
    }
}
