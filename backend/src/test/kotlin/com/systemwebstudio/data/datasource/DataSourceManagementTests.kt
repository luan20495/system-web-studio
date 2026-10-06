package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.gateway.GatewayOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * B-C0-W-03 at unit level: the workspace-scoped management service (isolation, delete, credential metadata and removal, no secret anywhere).
 * The HTTP layer is covered by ManagementHttpTests; the real database and the real routes by DataManagementApiTests (Testcontainers).
 */
class DataSourceManagementTests {
    private val f = GatewayFixture()
    private val changes = mutableListOf<DataChange>()
    private val secret = "sk-live-MGMT-SECRET-777"
    private val author = UUID.randomUUID()

    /** the fake connector, but declaring the credential entries it reads (the metadata endpoint lists their names) */
    private val connector = object : DataConnector by f.connector {
        override val descriptor = ConnectorDescriptor("fake", "Fake", ConnectorStatus.AVAILABLE, setOf(ConnectorCapability.QUERY), credentialKeys = listOf("authValue"))
    }
    private val registry = DataConnectorRegistry(listOf(connector))

    private fun admin(scope: DataSourceScope = DataSourceScope.WORKSPACE, repo: DataSourceRepository = f.repo, vault: CredentialVault = f.vault, actors: CredentialActorLookup = CredentialActorLookup { _, _ -> author }) =
        DataSourceAdminService(repo, vault, registry, f.guard, f.limiter, f.audit, DataChangeListener { changes += it }, f.clock, scope = scope, credentialActors = actors)

    private val a = TestTenant()                                                              // tenant T, workspace A
    private val sameTenantOtherWorkspace = TestTenant(tenantId = a.tenantId, workspaceId = UUID.randomUUID())
    private val foreignTenant = TestTenant()
    private fun ctx(t: TestTenant) = f.ctx(t)
    private fun spec(name: String = "billing", cred: Map<String, String>? = mapOf("authValue" to secret)) = DataSourceSpec(name, "fake", mapOf("host" to "api.example.com"), cred)

    @Test fun `a data source belongs to the workspace of its creator and nobody else's`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        assertThat(v.workspaceId).isEqualTo(a.workspaceId)
        assertThat(svc.list(ctx(a)).map { it.id }).containsExactly(v.id)
        assertThat(svc.get(ctx(a), v.id).id).isEqualTo(v.id)
        val missing = f.failure { svc.get(ctx(a), UUID.randomUUID()) }
        for (other in listOf(sameTenantOtherWorkspace, foreignTenant)) {
            assertThat(svc.list(ctx(other))).isEmpty()
            val calls = listOf<() -> Unit>(
                { svc.get(ctx(other), v.id) }, { svc.update(ctx(other), v.id, name = "x") }, { svc.rotateCredential(ctx(other), v.id, mapOf("authValue" to "x")) },
                { svc.removeCredential(ctx(other), v.id) }, { svc.credentialInfo(ctx(other), v.id) }, { svc.setStatus(ctx(other), v.id, DataSourceStatus.DISABLED) }, { svc.delete(ctx(other), v.id) })
            for (call in calls) {
                val e = f.failure(call)
                assertThat(e.code).isEqualTo(FailureCodes.NOT_FOUND)
                assertThat(e.message).isEqualTo(missing.message)                                // a foreign id answers exactly like a missing one
            }
        }
        val stored = f.repo.find(a.tenantId, v.id)!!
        assertThat(stored.version).isEqualTo(1L); assertThat(stored.credentialRef).isNotNull(); assertThat(stored.status).isEqualTo(DataSourceStatus.ACTIVE)
        assertThat(f.credentialStore.size).isEqualTo(1)
    }

    @Test fun `a context without a workspace reaches nothing in workspace scope`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        val noWorkspace = TestTenant(tenantId = a.tenantId, workspaceId = null)
        assertThat(f.failure { svc.create(ctx(noWorkspace), spec("other")) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { svc.get(ctx(noWorkspace), v.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { svc.delete(ctx(noWorkspace), v.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(svc.list(ctx(noWorkspace))).isEmpty()
        assertThat(f.repo.list(a.tenantId)).hasSize(1)
    }

    @Test fun `the historical tenant scope is unchanged`() {
        val svc = admin(DataSourceScope.TENANT); val v = svc.create(ctx(a), spec())
        assertThat(svc.get(ctx(sameTenantOtherWorkspace), v.id).id).isEqualTo(v.id)
        assertThat(f.failure { svc.get(ctx(foreignTenant), v.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
    }

    @Test fun `delete removes the source and destroys its credential and tells subscribers`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        svc.delete(ctx(a), v.id)
        assertThat(f.repo.find(a.tenantId, v.id)).isNull(); assertThat(f.credentialStore.size).isEqualTo(0)
        assertThat(f.failure { svc.get(ctx(a), v.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { svc.delete(ctx(a), v.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)           // a second delete is a plain 404
        assertThat(changes.map { it.cause }).contains(ChangeCause.DATASOURCE_UPDATED)
        assertThat(f.audit.actions()).contains(DataAuditActions.DELETED)
        assertThat(f.audit.text).doesNotContain(secret)
        assertThat(svc.create(ctx(a), spec()).version).isEqualTo(1L)                                         // the name is free again
    }

    @Test fun `a refused delete changes nothing and keeps the credential`() {
        val refusing = object : DataSourceRepository by f.repo { override fun delete(tenantId: UUID, id: UUID): Boolean = throw ConnectorFailure(FailureCodes.CONFLICT, "bound") }
        val svc = admin(repo = refusing); val v = svc.create(ctx(a), spec())
        assertThat(f.failure { svc.delete(ctx(a), v.id) }.code).isEqualTo(FailureCodes.CONFLICT)
        assertThat(f.repo.find(a.tenantId, v.id)).isNotNull(); assertThat(f.credentialStore.size).isEqualTo(1)
        assertThat(f.vault.open(f.repo.find(a.tenantId, v.id)!!).get("authValue")).isEqualTo(secret)
        assertThat(f.audit.actions()).doesNotContain(DataAuditActions.DELETED)
    }

    @Test fun `delete needs the manage permission`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        f.authorizer.denied += GatewayOperation.DATASOURCE_MANAGE
        assertThat(f.failure { svc.delete(ctx(a), v.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { svc.removeCredential(ctx(a), v.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.repo.find(a.tenantId, v.id)).isNotNull(); assertThat(f.credentialStore.size).isEqualTo(1)
    }

    @Test fun `credential metadata has configured type names and times but never a value or a reference`() {
        val vault = object : CredentialVault by f.vault { override fun open(ds: DataSource): ResolvedCredential = throw AssertionError("metadata must not open the vault") }
        val svc = admin(vault = vault); val v = svc.create(ctx(a), spec())
        val info = svc.credentialInfo(ctx(a), v.id)
        assertThat(info.configured).isTrue(); assertThat(info.type).isEqualTo("fake"); assertThat(info.keys).containsExactly("authValue")
        assertThat(info.updatedAt).isEqualTo(f.clock.instant()); assertThat(info.updatedBy).isEqualTo(author)
        val ref = f.repo.find(a.tenantId, v.id)!!.credentialRef!!
        for (observable in listOf(info.toString(), v.toString())) assertThat(observable).doesNotContain(secret).doesNotContain(ref)
        f.clock.advanceSeconds(120)
        svc.rotateCredential(ctx(a), v.id, mapOf("authValue" to "second-secret"))
        assertThat(svc.credentialInfo(ctx(a), v.id).updatedAt).isEqualTo(f.clock.instant())                  // rotation moves updatedAt
    }

    @Test fun `credential metadata needs only the read permission and an unknown author is null`() {
        val svc = admin(actors = CredentialActorLookup { _, _ -> null }); val v = svc.create(ctx(a), spec())
        f.authorizer.denied += GatewayOperation.DATASOURCE_MANAGE
        assertThat(svc.credentialInfo(ctx(a), v.id).updatedBy).isNull()
        f.authorizer.denied.clear(); f.authorizer.denied += GatewayOperation.DATASOURCE_READ
        assertThat(f.failure { svc.credentialInfo(ctx(a), v.id) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        val broken = admin(actors = CredentialActorLookup { _, _ -> throw IllegalStateException("audit store down") })
        f.authorizer.denied.clear()
        assertThat(broken.credentialInfo(ctx(a), v.id).updatedBy).isNull()                                   // metadata never fails because of the author lookup
    }

    @Test fun `a source without a credential reports not configured`() {
        val svc = admin(); val v = svc.create(ctx(a), spec(cred = null))
        val info = svc.credentialInfo(ctx(a), v.id)
        assertThat(info.configured).isFalse(); assertThat(info.updatedAt).isNull(); assertThat(info.updatedBy).isNull(); assertThat(info.keys).containsExactly("authValue")
    }

    @Test fun `removing the credential destroys it keeps the source and is repeatable`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        val removed = svc.removeCredential(ctx(a), v.id)
        assertThat(removed.hasCredential).isFalse(); assertThat(removed.version).isEqualTo(2L)
        assertThat(f.credentialStore.size).isEqualTo(0); assertThat(svc.credentialInfo(ctx(a), v.id).configured).isFalse()
        assertThat(svc.removeCredential(ctx(a), v.id).version).isEqualTo(2L)                                 // nothing to remove: no new version
        assertThat(f.audit.actions().count { it == DataAuditActions.CREDENTIAL_REMOVED }).isEqualTo(1)
        assertThat(f.audit.text).doesNotContain(secret)
        val set = svc.rotateCredential(ctx(a), v.id, mapOf("authValue" to "again"))                          // configure again
        assertThat(set.hasCredential).isTrue(); assertThat(set.version).isEqualTo(3L)
    }

    @Test fun `update with nothing to change is a no op`() {
        val svc = admin(); val v = svc.create(ctx(a), spec())
        assertThat(svc.update(ctx(a), v.id).version).isEqualTo(1L)
        assertThat(changes).isEmpty()
        assertThat(f.audit.actions()).doesNotContain(DataAuditActions.UPDATED)
    }

    @Test fun `no management path writes the secret to a log or an audit event or an exception`() {
        LogCapture().use { logs ->
            val svc = admin(); val v = svc.create(ctx(a), spec())
            svc.rotateCredential(ctx(a), v.id, mapOf("authValue" to secret + "-2"))
            svc.credentialInfo(ctx(a), v.id); svc.removeCredential(ctx(a), v.id); svc.delete(ctx(a), v.id)
            val failures = listOf(f.failure { svc.rotateCredential(ctx(a), v.id, mapOf("authValue" to secret)) }, f.failure { svc.create(ctx(a), spec(name = "bad;name")) })
            for (e in failures) assertThat(e.toString() + e.message).doesNotContain(secret)
            assertThat(logs.text).doesNotContain(secret)
        }
        assertThat(f.audit.text).doesNotContain(secret)
    }
}
