package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What a caller (Studio, the admin API) is allowed to specify. The credential is write-only: nothing the platform returns ever contains it. */
class DataSourceSpec(val name: String, val type: String, val config: Map<String, String>, val credential: Map<String, String>? = null) {
    override fun toString() = "DataSourceSpec(name=$name, type=$type)"
}

/** The safe projection of a [DataSource] for any API response: no credential, no credential reference — only whether one is set. */
data class DataSourceView(
    val id: UUID, val tenantId: UUID, val workspaceId: UUID?, val name: String, val connectorType: String, val config: Map<String, String>,
    val hasCredential: Boolean, val status: DataSourceStatus, val createdBy: UUID?, val createdAt: Instant, val updatedAt: Instant, val version: Long
)

internal fun DataSource.toView() = DataSourceView(id, tenantId, workspaceId, name, connectorType, ref.configNonSecret, hasCredential, status, createdBy, createdAt, updatedAt, version)

/**
 * Lifecycle of a data source: tenant-owned (workspace optional), named, typed, non-secret config, credential held by reference, status,
 * created-by and timestamps. Every method authorises first ([GatewayOperation.DATASOURCE_MANAGE] / `DATASOURCE_READ`), validates through the
 * connector, bumps [DataSource.version], audits with fixed fields only, and tells the [DataChangeListener] so cached results of the old
 * configuration disappear. The credential travels as a `Map` argument into the vault and nowhere else.
 */
class DataSourceAdminService(
    private val repository: DataSourceRepository,
    private val vault: CredentialVault,
    private val registry: DataConnectorRegistry,
    private val guard: GatewayGuard,
    private val limits: RateLimitGate,
    private val audit: DataAuditSink,
    private val listener: DataChangeListener,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> UUID = UUID::randomUUID
) {
    fun create(ctx: GatewayContext, spec: DataSourceSpec): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, null)
        throttle(ctx)
        val name = checkName(spec.name)
        val connector = registry.require(spec.type)
        val config = ConfigSafety.check(spec.config)
        connector.validateConfig(config)
        if (repository.list(ctx.tenantId).any { it.name.equals(name, ignoreCase = true) }) throw ConnectorFailure(FailureCodes.CONFLICT, "a data source with this name already exists")
        val credentialRef = spec.credential?.let { vault.store(ctx.tenantId, it) }
        val now = clock.instant()
        val ds = DataSource(DataSourceRef(newId(), ctx.tenantId, spec.type, config), name, DataSourceStatus.ACTIVE, credentialRef, ctx.workspaceId, ctx.actorUserId, now, now, 1)
        try { repository.save(ds) } catch (e: RuntimeException) { vault.discard(ctx.tenantId, credentialRef); throw e }       // no orphaned credential
        audit.record(DataAuditActions.CREATED, ds.tenantId, ds.id, mapOf("type" to ds.connectorType, "hasCredential" to ds.hasCredential) + ctx.auditFields())
        return ds.toView()
    }

    fun update(ctx: GatewayContext, id: UUID, name: String? = null, config: Map<String, String>? = null): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        val newName = name?.let { checkName(it) } ?: current.name
        if (newName != current.name && repository.list(ctx.tenantId).any { it.id != id && it.name.equals(newName, ignoreCase = true) }) throw ConnectorFailure(FailureCodes.CONFLICT, "a data source with this name already exists")
        val newConfig = config?.let { ConfigSafety.check(it).also { c -> registry.require(current.connectorType).validateConfig(c) } } ?: current.ref.configNonSecret
        val revised = repository.save(current.revised(clock.instant(), name = newName, config = newConfig))
        audit.record(DataAuditActions.UPDATED, ctx.tenantId, id, mapOf("configChanged" to (config != null), "renamed" to (newName != current.name), "version" to revised.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
        return revised.toView()
    }

    /** Replaces the credential. The old one is destroyed only after the data source points at the new one. */
    fun rotateCredential(ctx: GatewayContext, id: UUID, credential: Map<String, String>): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        val newRef = vault.store(ctx.tenantId, credential)
        val revised = try { repository.save(current.revised(clock.instant(), credentialRef = newRef)) } catch (e: RuntimeException) { vault.discard(ctx.tenantId, newRef); throw e }
        vault.discard(ctx.tenantId, current.credentialRef)
        audit.record(DataAuditActions.CREDENTIAL_ROTATED, ctx.tenantId, id, mapOf("version" to revised.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
        return revised.toView()
    }

    fun setStatus(ctx: GatewayContext, id: UUID, status: DataSourceStatus): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        if (current.status == status) return current.toView()
        val revised = repository.save(current.revised(clock.instant(), status = status))
        audit.record(DataAuditActions.STATUS_CHANGED, ctx.tenantId, id, mapOf("status" to status.name, "version" to revised.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
        return revised.toView()
    }

    fun get(ctx: GatewayContext, id: UUID): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, id)
        return load(ctx, id).toView()
    }

    /** only the data sources the caller may read; a tenant-level denial hides the list entirely */
    fun list(ctx: GatewayContext): List<DataSourceView> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, null)
        return repository.list(ctx.tenantId).filter { it.tenantId == ctx.tenantId && guard.allowed(ctx, GatewayOperation.DATASOURCE_READ, it.id) }.map { it.toView() }
    }

    /** connector catalogue (available + planned): fixed text, no tenant data, so it needs only an authenticated tenant context */
    fun connectorCatalog(): List<ConnectorDescriptor> = registry.descriptors()

    private fun load(ctx: GatewayContext, id: UUID): DataSource {
        val ds = repository.find(ctx.tenantId, id)
        if (ds == null || ds.tenantId != ctx.tenantId) throw ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found")
        return ds
    }

    private fun throttle(ctx: GatewayContext) {
        if (!limits.allow("data-source:manage:${ctx.tenantId}", 60, 60)) throw ConnectorFailure(FailureCodes.RATE_LIMITED, "too many requests; retry later")
    }

    private fun checkName(raw: String): String {
        val name = raw.trim()
        if (!NAME.matches(name)) throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "invalid data source name")
        return name
    }

    private companion object { val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$") }
}

/**
 * The configuration is "non-secret" by contract; this makes it so. Key names that denote secrets are refused, and so is any value that
 * embeds credentials (`scheme://user:pass@host`), so a secret pasted into the wrong box is rejected instead of stored in clear text.
 */
internal object ConfigSafety {
    private val KEY = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val SECRETISH = Regex("(?i)(password|passwd|secret|token|apikey|api_key|credential|privatekey|private_key)")
    private val USERINFO = Regex("(?i)[a-z][a-z0-9+.-]*://[^/\\s]*:[^/\\s]*@")

    fun check(config: Map<String, String>): Map<String, String> {
        if (config.size > 30) bad()
        for ((k, v) in config) {
            if (!KEY.matches(k) || SECRETISH.containsMatchIn(k) || v.length > 2_000 || v.any { it.code < 0x20 } || USERINFO.containsMatchIn(v)) bad()
        }
        return config.toMap()
    }

    private fun bad(): Nothing = throw ConnectorFailure(FailureCodes.INVALID_CONFIG, "configuration holds a key or value that looks like a secret; credentials go in the credential, not the configuration")
}
