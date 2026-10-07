package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.cache.CacheKeyParts
import com.systemwebstudio.data.cache.CacheScope
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.gateway.GatewayOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Data source lifecycle: tenant-owned, config non-secret, credential write-only and by reference, versioned, audited, permission-gated. */
class DataSourceAdminTests {
    private val f = GatewayFixture()
    private val changes = mutableListOf<DataChange>()
    private val admin = DataSourceAdminService(f.repo, f.vault, DataConnectorRegistry(listOf(f.connector) + PlannedConnectors.all()), f.guard, f.limiter, f.audit, DataChangeListener { changes += it; f.notifier.onChange(it) }, f.clock)
    private val t = f.tenant(); private val ctx = f.ctx(t)
    private val secret = "sk-live-ADMIN-SECRET-123"
    private fun spec(name: String = "billing", config: Map<String, String> = mapOf("host" to "api.example.com"), cred: Map<String, String>? = mapOf("authValue" to secret)) = DataSourceSpec(name, "fake", config, cred)

    @Test fun `a created data source is tenant owned versioned and shows no credential`() {
        val v = admin.create(ctx, spec())
        assertThat(v.tenantId).isEqualTo(t.tenantId); assertThat(v.createdBy).isEqualTo(t.actorUserId); assertThat(v.version).isEqualTo(1L)
        assertThat(v.hasCredential).isTrue(); assertThat(v.connectorType).isEqualTo("fake"); assertThat(v.status).isEqualTo(DataSourceStatus.ACTIVE)
        assertThat(v.createdAt).isEqualTo(f.clock.instant())
        for (observable in listOf(v.toString(), admin.get(ctx, v.id).toString(), admin.list(ctx).toString(), f.audit.text)) assertThat(observable).doesNotContain(secret)
        val stored = f.repo.find(t.tenantId, v.id)!!
        assertThat(stored.credentialRef).isNotNull()
        assertThat(f.credentialStore.find(t.tenantId, stored.credentialRef!!)).startsWith("v1:").doesNotContain(secret)
        assertThat(DataSourceSpec("n", "fake", emptyMap(), mapOf("authValue" to secret)).toString()).doesNotContain(secret)
    }

    @Test fun `secrets pasted into the configuration are refused and nothing is stored`() {
        for (cfg in listOf(mapOf("password" to "x"), mapOf("apiKey" to "x"), mapOf("api_key" to "x"), mapOf("authToken" to "x"), mapOf("url" to "postgres://user:hunter2@db.example.com/x"),
            mapOf("bad key!" to "x"), mapOf("k" to "a\u0000b"), (1..31).associate { "k$it" to "v" }))
            assertThat(f.failure { admin.create(ctx, spec(config = cfg)) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
        assertThat(f.repo.list(t.tenantId)).isEmpty(); assertThat(f.credentialStore.size).isEqualTo(0)
        assertThat(f.failure { admin.create(ctx, spec(name = "bad;name")) }.code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    @Test fun `names are unique per tenant but not across tenants`() {
        admin.create(ctx, spec("Billing"))
        assertThat(f.failure { admin.create(ctx, spec("billing")) }.code).isEqualTo(FailureCodes.CONFLICT)
        admin.create(f.ctx(f.tenant()), spec("billing"))
        val second = admin.create(ctx, spec("other"))
        assertThat(f.failure { admin.update(ctx, second.id, name = "BILLING") }.code).isEqualTo(FailureCodes.CONFLICT)
    }

    @Test fun `every change bumps the version drops the cache and tells subscribers`() {
        val v = admin.create(ctx, spec())
        val parts = CacheKeyParts(CacheScope(t.tenantId, v.id, "q"), 1, 1, null, 1, null, "{}", null)
        f.cache.putNow(parts, "stale", 600)
        val u = admin.update(ctx, v.id, config = mapOf("host" to "other.example.com"))
        assertThat(u.version).isEqualTo(2L); assertThat(f.cache.get(parts)).isNull()
        f.cache.putNow(parts, "stale", 600)
        val r = admin.rotateCredential(ctx, v.id, mapOf("authValue" to "new-secret-value"))
        assertThat(r.version).isEqualTo(3L); assertThat(f.cache.get(parts)).isNull()
        val d = admin.setStatus(ctx, v.id, DataSourceStatus.DISABLED)
        assertThat(d.version).isEqualTo(4L); assertThat(admin.setStatus(ctx, v.id, DataSourceStatus.DISABLED).version).isEqualTo(4L)   // no-op does not bump
        assertThat(changes.size).isEqualTo(3)
        assertThat(f.audit.actions()).contains(DataAuditActions.CREATED, DataAuditActions.UPDATED, DataAuditActions.CREDENTIAL_ROTATED, DataAuditActions.STATUS_CHANGED)
        assertThat(f.audit.text).doesNotContain(secret).doesNotContain("new-secret-value").doesNotContain("other.example.com")
    }

    @Test fun `rotation replaces the stored credential and destroys the old one`() {
        val v = admin.create(ctx, spec()); val before = f.repo.find(t.tenantId, v.id)!!.credentialRef!!
        admin.rotateCredential(ctx, v.id, mapOf("authValue" to "second-secret"))
        val after = f.repo.find(t.tenantId, v.id)!!.credentialRef!!
        assertThat(after).isNotEqualTo(before); assertThat(f.credentialStore.find(t.tenantId, before)).isNull()
        assertThat(f.vault.open(f.repo.find(t.tenantId, v.id)!!).get("authValue")).isEqualTo("second-secret")
        assertThat(f.failure { admin.rotateCredential(ctx, v.id, emptyMap()) }.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(f.vault.open(f.repo.find(t.tenantId, v.id)!!).get("authValue")).isEqualTo("second-secret")     // a failed rotation changes nothing
    }

    @Test fun `another tenant cannot read change rotate or disable it`() {
        val v = admin.create(ctx, spec()); val other = f.ctx(f.tenant())
        for (call in listOf<() -> Unit>({ admin.get(other, v.id) }, { admin.update(other, v.id, name = "x") }, { admin.rotateCredential(other, v.id, mapOf("a" to "b")) }, { admin.setStatus(other, v.id, DataSourceStatus.DISABLED) }))
            assertThat(f.failure(call).code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(admin.list(other)).isEmpty()
        assertThat(f.repo.find(t.tenantId, v.id)!!.version).isEqualTo(1L)
    }

    @Test fun `every operation needs its permission and list shows only readable sources`() {
        val a = admin.create(ctx, spec("a")); val b = admin.create(ctx, spec("b"))
        f.authorizer.denied += GatewayOperation.DATASOURCE_MANAGE
        assertThat(f.failure { admin.create(ctx, spec("c")) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.update(ctx, a.id, name = "z") }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.rotateCredential(ctx, a.id, mapOf("a" to "b")) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.setStatus(ctx, a.id, DataSourceStatus.DISABLED) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        f.authorizer.denied.clear(); f.authorizer.denied += GatewayOperation.DATASOURCE_READ
        assertThat(f.failure { admin.get(ctx, a.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { admin.list(ctx) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.repo.find(t.tenantId, a.id)!!.version).isEqualTo(1L); assertThat(b.version).isEqualTo(1L)
    }

    @Test fun `a planned connector type cannot be used and the catalogue lists it`() {
        assertThat(f.failure { admin.create(ctx, DataSourceSpec("x", "mysql", mapOf("host" to "db.example.com"))) }.code).isEqualTo(FailureCodes.NOT_IMPLEMENTED)
        assertThat(f.failure { admin.create(ctx, DataSourceSpec("x", "mongodb", emptyMap())) }.code).isEqualTo(FailureCodes.UNSUPPORTED_TYPE)
        val catalog = admin.connectorCatalog()
        assertThat(catalog.map { it.type }).containsExactlyInAnyOrder("csv", "fake", "google_sheets", "graphql", "mysql", "odoo", "salesforce")
        assertThat(catalog.filter { it.status == ConnectorStatus.PLANNED }).hasSize(6)
        assertThat(f.repo.list(t.tenantId)).isEmpty()
    }

    @Test fun `a repository failure leaves no orphaned credential and no secret in logs`() {
        val failing = object : DataSourceRepository by f.repo { override fun save(dataSource: DataSource): DataSource = throw IllegalStateException("db down: $secret") }
        val a = DataSourceAdminService(failing, f.vault, DataConnectorRegistry(listOf(f.connector)), f.guard, f.limiter, f.audit, DataChangeListener { }, f.clock)
        LogCapture().use { logs ->
            runCatching { a.create(ctx, spec()) }
            assertThat(logs.text).doesNotContain(secret)
        }
        assertThat(f.credentialStore.size).isEqualTo(0)
    }

    @Test fun `management is throttled per tenant and recovers`() {
        val v = admin.create(ctx, spec())                                             // 1st management call of the minute
        repeat(59) { i -> admin.setStatus(ctx, v.id, if (i % 2 == 0) DataSourceStatus.DISABLED else DataSourceStatus.ACTIVE) }
        assertThat(f.failure { admin.setStatus(ctx, v.id, DataSourceStatus.ACTIVE) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
        assertThat(admin.create(f.ctx(f.tenant()), spec("elsewhere")).version).isEqualTo(1L)   // another tenant has its own budget
        f.clock.advanceSeconds(61)
        admin.setStatus(ctx, v.id, DataSourceStatus.ACTIVE)
    }
}
