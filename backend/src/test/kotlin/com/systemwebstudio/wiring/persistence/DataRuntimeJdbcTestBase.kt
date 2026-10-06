package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.support.IntegrationTestBase
import java.time.Instant
import java.util.UUID

/** Shared helpers of the V28 adapter tests: fresh tenants (so no test can see another's rows), workspaces, projects and registered data sources. */
abstract class DataRuntimeJdbcTestBase : IntegrationTestBase() {
    protected val at: Instant = Instant.parse("2026-10-05T10:00:00Z")

    protected fun newTenant(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", id, "t-" + id.toString().take(8))
        return id
    }

    protected fun workspaceOf(tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, ?)", id, "w-" + id.toString().take(8), tenant)
        return id
    }

    protected fun projectIn(ws: UUID, tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'p', ?, ?)", id, ws, fx.user().id, tenant)
        return id
    }

    protected fun dataSource(
        tenant: UUID, ws: UUID? = null, type: String = "fake", name: String = "ds-" + UUID.randomUUID().toString().take(8),
        config: Map<String, String> = emptyMap(), credentialRef: String? = null, version: Long = 1
    ) = DataSource(DataSourceRef(UUID.randomUUID(), tenant, type, config), name, credentialRef = credentialRef, workspaceId = ws, createdAt = at, updatedAt = at, version = version)

    /** the code of the [ConnectorFailure] [block] throws, or null when it does not throw one */
    protected fun failureCode(block: () -> Unit): String? = try { block(); null } catch (e: ConnectorFailure) { e.code }
}
