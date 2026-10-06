package com.systemwebstudio.data.datasource

import java.time.Instant
import java.util.UUID

object DataSourceTypes {
    const val REST = "rest"
    const val POSTGRES = "postgres"
    const val CSV = "csv"
    // planned (SPI only, see ConnectorSpi.kt): mysql, graphql, google_sheets, odoo, salesforce
}

enum class DataSourceStatus { ACTIVE, DISABLED }

/**
 * What a connector may see of a data source: identity, tenant, type and **non-secret** configuration. Secrets never live here
 * (contract `data-connector.md`: `DataSourceRef = {id, tenantId, type, configNonSecret}`); they arrive separately as a [ResolvedCredential].
 */
data class DataSourceRef(val id: UUID, val tenantId: UUID, val type: String, val configNonSecret: Map<String, String>)

/**
 * A registered data source as the persistence port stores it: tenant-owned, workspace optional, a name, the connector type, the non-secret
 * configuration (inside [ref]), a **reference** to the credential ([credentialRef], the material itself lives in the separate, encrypted
 * `CredentialStore`), a status, who created it and when.
 *
 * [version] grows by one on every change of configuration, credential or status; it is part of every cache key, so a change makes older
 * cached results unreachable even before the explicit invalidation lands.
 * `toString` leaves out the credential reference so a data source can be logged safely.
 */
class DataSource(
    val ref: DataSourceRef,
    val name: String,
    val status: DataSourceStatus = DataSourceStatus.ACTIVE,
    val credentialRef: String? = null,
    val workspaceId: UUID? = null,
    val createdBy: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val version: Long = 1
) {
    val id get() = ref.id
    val tenantId get() = ref.tenantId
    val connectorType get() = ref.type
    val hasCredential get() = credentialRef != null

    /** a new revision (version + 1); only the arguments given change */
    fun revised(
        now: Instant, name: String = this.name, status: DataSourceStatus = this.status, config: Map<String, String> = ref.configNonSecret,
        credentialRef: String? = this.credentialRef
    ) = DataSource(ref.copy(configNonSecret = config), name, status, credentialRef, workspaceId, createdBy, createdAt, now, version + 1)

    override fun toString() = "DataSource(id=${ref.id}, tenant=${ref.tenantId}, type=${ref.type}, name=$name, status=$status, hasCredential=$hasCredential, v=$version)"
}
