package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import java.time.Clock
import java.util.UUID

/**
 * T9: schema discovery as a service. `discover(dataSource)` runs through [DataSourceService] (tenant check, status, rate limit, vault,
 * connector, audit), the result is **masked once more**, stamped with a version and `discoveredAt`, and stored as an immutable
 * [SchemaSnapshot]. Studio can ask for a refresh at any time (cooldown [REFRESH_COOLDOWN_SECONDS] per data source, on top of the
 * connector-level rate limit); asking for samples is a separate permission because even masked rows are data.
 */
class DiscoveryService(
    private val service: DataSourceService,
    private val store: SourceSchemaStore,
    private val guard: GatewayGuard,
    private val limits: RateLimitGate,
    private val audit: DataAuditSink,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> UUID = UUID::randomUUID
) {
    data class RefreshResult(val snapshot: SchemaSnapshot, val changed: Boolean, val diff: SchemaDiff?)

    fun refresh(ctx: GatewayContext, dataSourceId: UUID, includeSamples: Boolean = false): RefreshResult {
        guard.require(ctx, GatewayOperation.SCHEMA_DISCOVER, dataSourceId)
        if (includeSamples) guard.require(ctx, GatewayOperation.SCHEMA_SAMPLE, dataSourceId)
        val ds = service.resolve(ctx, dataSourceId)
        if (!limits.allow("schema-refresh:${ctx.tenantId}:${ds.id}", 1, REFRESH_COOLDOWN_SECONDS)) throw ConnectorFailure(FailureCodes.REFRESH_TOO_SOON, "schema was refreshed moments ago; retry shortly")

        val discovered = service.discoverSchema(ctx, ds.id, DiscoveryOptions(if (includeSamples) DEFAULT_SAMPLE_ROWS else 0))
        val schema = sanitize(discovered, includeSamples)

        var previous = store.latest(ctx.tenantId, ds.id)
        var saved: SchemaSnapshot? = null
        repeat(2) { attempt ->
            if (saved != null) return@repeat
            val snapshot = SchemaSnapshot(newId(), ctx.tenantId, ds.id, (previous?.version ?: 0) + 1, clock.instant(), SchemaFingerprint.of(schema), ds.version,
                ctx.actorUserId, includeSamples, schema)
            try { store.save(snapshot); saved = snapshot }
            catch (e: ConnectorFailure) {
                if (e.code != FailureCodes.CONFLICT || attempt == 1) throw e
                previous = store.latest(ctx.tenantId, ds.id)                                  // a concurrent refresh won the version: take the next one
            }
        }
        val snapshot = saved ?: throw ConnectorFailure(FailureCodes.CONFLICT, "schema was refreshed concurrently; retry")
        val changed = previous == null || previous!!.fingerprint != snapshot.fingerprint
        val diff = previous?.let { SchemaDiffer.diff(it.schema, snapshot.schema) }
        audit.record(DataAuditActions.SCHEMA_REFRESHED, ctx.tenantId, ds.id, mapOf("version" to snapshot.version, "changed" to changed,
            "entities" to schema.entities.size, "samples" to includeSamples, "truncated" to schema.truncated) + ctx.auditFields())
        return RefreshResult(snapshot, changed, diff)
    }

    fun latest(ctx: GatewayContext, dataSourceId: UUID): SchemaSnapshot? {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, dataSourceId)
        service.resolve(ctx, dataSourceId)
        return store.latest(ctx.tenantId, dataSourceId)
    }

    fun history(ctx: GatewayContext, dataSourceId: UUID, limit: Int = 10): List<SchemaSnapshot> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, dataSourceId)
        service.resolve(ctx, dataSourceId)
        return store.history(ctx.tenantId, dataSourceId, limit.coerceIn(1, 50))
    }

    /** The projection that may be sent to an AI model (see [AiSafeSchema]); null before the first discovery. */
    fun aiView(ctx: GatewayContext, dataSourceId: UUID): AiSchemaView? = latest(ctx, dataSourceId)?.let { AiSafeSchema.of(it) }

    /** Defence in depth: re-mask whatever a connector put in `sample`, and drop samples entirely when none were asked for. */
    private fun sanitize(schema: DiscoveredSchema, includeSamples: Boolean): DiscoveredSchema =
        schema.copy(entities = schema.entities.map { e -> e.copy(sample = if (includeSamples) SampleMasker.maskRows(e.sample) else emptyList()) })

    companion object {
        const val REFRESH_COOLDOWN_SECONDS = 30L
        const val DEFAULT_SAMPLE_ROWS = 3
    }
}
