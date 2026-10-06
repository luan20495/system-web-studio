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
 * Metadata of the credential of one data source — and nothing else. [type] is the connector type the credential belongs to, [keys] the NAMES of the
 * entries that connector reads (from its descriptor, never from the stored material), [updatedAt]/[updatedBy] say when and by whom it was last set.
 * It is built without ever opening the stored credential, so there is no secret in this object to leak.
 */
data class CredentialInfo(val configured: Boolean, val type: String?, val keys: List<String>, val updatedAt: Instant?, val updatedBy: UUID?)

/**
 * Lifecycle of a data source: tenant-owned (workspace optional), named, typed, non-secret config, credential held by reference, status,
 * created-by and timestamps. Every method authorises first ([GatewayOperation.DATASOURCE_MANAGE] / `DATASOURCE_READ`), validates through the
 * connector, bumps [DataSource.version], audits with fixed fields only, and tells the [DataChangeListener] so cached results of the old
 * configuration disappear. The credential travels as a `Map` argument into the vault and nowhere else.
 *
 * [scope] decides which rows a call can reach: [DataSourceScope.TENANT] is the historical C3 behaviour; [DataSourceScope.WORKSPACE] (what C0's wiring
 * uses, B-C0-W-03 / D-C0-22) means tenant AND the workspace of the caller's context, default deny: a context without a workspace reaches nothing, and
 * another workspace's data source is indistinguishable from a missing one (404 either way).
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
    private val newId: () -> UUID = UUID::randomUUID,
    private val scope: DataSourceScope = DataSourceScope.TENANT,
    private val credentialActors: CredentialActorLookup = CredentialActorLookup { _, _ -> null }
) {
    fun create(ctx: GatewayContext, spec: DataSourceSpec): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, null)
        if (scope == DataSourceScope.WORKSPACE && ctx.workspaceId == null) throw notFound()            // a workspace-scoped store has no owner for a tenant-level source
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

    /** Only the arguments given change. Nothing given = nothing to do: no new version, no cache flush. */
    fun update(ctx: GatewayContext, id: UUID, name: String? = null, config: Map<String, String>? = null): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        if (name == null && config == null) return current.toView()
        val newName = name?.let { checkName(it) } ?: current.name
        if (newName != current.name && repository.list(ctx.tenantId).any { it.id != id && it.name.equals(newName, ignoreCase = true) }) throw ConnectorFailure(FailureCodes.CONFLICT, "a data source with this name already exists")
        val newConfig = config?.let { ConfigSafety.check(it).also { c -> registry.require(current.connectorType).validateConfig(c) } } ?: current.ref.configNonSecret
        val revised = repository.save(current.revised(clock.instant(), name = newName, config = newConfig))
        audit.record(DataAuditActions.UPDATED, ctx.tenantId, id, mapOf("configChanged" to (config != null), "renamed" to (newName != current.name), "version" to revised.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
        return revised.toView()
    }

    /** Replaces the credential. The old one is destroyed only after the data source points at the new one; the old secret is never read. */
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

    /** Detaches and destroys the credential. A data source without one stays registered (its connector will answer INVALID_CREDENTIAL until one is set again). */
    fun removeCredential(ctx: GatewayContext, id: UUID): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        if (current.credentialRef == null) return current.toView()
        val revised = repository.save(current.revised(clock.instant(), credentialRef = null))
        vault.discard(ctx.tenantId, current.credentialRef)
        audit.record(DataAuditActions.CREDENTIAL_REMOVED, ctx.tenantId, id, mapOf("version" to revised.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
        return revised.toView()
    }

    /** Metadata of the credential: whether there is one, for which connector, since when and by whom. Never the material (the vault is not even opened). */
    fun credentialInfo(ctx: GatewayContext, id: UUID): CredentialInfo {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, id)
        val ds = load(ctx, id)
        val ref = ds.credentialRef ?: return CredentialInfo(false, ds.connectorType, credentialKeys(ds), null, null)
        val updatedAt = runCatching { vault.updatedAt(ds.tenantId, ref) }.getOrNull() ?: ds.updatedAt
        val updatedBy = runCatching { credentialActors.lastActor(ds.tenantId, ds.id) }.getOrNull()
        return CredentialInfo(true, ds.connectorType, credentialKeys(ds), updatedAt, updatedBy)
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

    /**
     * Removes the data source: its approved queries, mutations, schema snapshots and idempotency records go with it, the credential is destroyed, cached
     * results are dropped. Refused with CONFLICT while an application binding still uses it (unbind first) — a LIVE app must never lose its source silently.
     */
    fun delete(ctx: GatewayContext, id: UUID) {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, id)
        throttle(ctx)
        val current = load(ctx, id)
        if (!repository.delete(ctx.tenantId, id)) throw notFound()
        vault.discard(ctx.tenantId, current.credentialRef)
        audit.record(DataAuditActions.DELETED, ctx.tenantId, id, mapOf("type" to current.connectorType, "version" to current.version) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, id, ChangeCause.DATASOURCE_UPDATED))
    }

    fun get(ctx: GatewayContext, id: UUID): DataSourceView {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, id)
        return load(ctx, id).toView()
    }

    /** only the data sources the caller may read; a tenant-level denial hides the list entirely */
    fun list(ctx: GatewayContext): List<DataSourceView> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, null)
        val rows = when (scope) {
            DataSourceScope.TENANT -> repository.list(ctx.tenantId)
            DataSourceScope.WORKSPACE -> ctx.workspaceId?.let { repository.listInWorkspace(ctx.tenantId, it) } ?: emptyList()
        }
        return rows.filter { it.tenantId == ctx.tenantId && (scope == DataSourceScope.TENANT || it.workspaceId == ctx.workspaceId) && guard.allowed(ctx, GatewayOperation.DATASOURCE_READ, it.id) }.map { it.toView() }
    }

    /** connector catalogue (available + planned): fixed text, no tenant data, so it needs only an authenticated tenant context */
    fun connectorCatalog(): List<ConnectorDescriptor> = registry.descriptors()

    /** the same catalogue for an HTTP caller: it still has to be someone who may read data sources of the workspace */
    fun connectorCatalog(ctx: GatewayContext): List<ConnectorDescriptor> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, null)
        return registry.descriptors()
    }

    /** Same answer for "missing", "another tenant's" and (with [DataSourceScope.WORKSPACE]) "another workspace's": nothing leaks. */
    private fun load(ctx: GatewayContext, id: UUID): DataSource {
        val ds = when (scope) {
            DataSourceScope.TENANT -> repository.find(ctx.tenantId, id)
            DataSourceScope.WORKSPACE -> repository.findInWorkspace(ctx.tenantId, ctx.workspaceId ?: throw notFound(), id)
        }
        if (ds == null || ds.tenantId != ctx.tenantId) throw notFound()
        if (scope == DataSourceScope.WORKSPACE && ds.workspaceId != ctx.workspaceId) throw notFound()      // a repository bug must not cross workspaces
        return ds
    }

    private fun credentialKeys(ds: DataSource): List<String> = registry.descriptors().firstOrNull { it.type == ds.connectorType }?.credentialKeys ?: emptyList()

    private fun notFound() = ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found")

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
