package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.datasource.ConnectorDescriptor
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.CredentialInfo
import com.systemwebstudio.data.datasource.DataSourceSpec
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.DataSourceView
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode
import java.util.UUID

/*
 * Framework-agnostic HTTP layer of the Data Source Management API (BLOCKERS B-C0-W-03): strict request parsers and JSON response builders, in the
 * same style as [GatewayRequests] / [GatewayResponses]. The Spring controllers that call them live in C0's wiring (`wiring/DataManagementControllers.kt`).
 *
 * Request rules: the body must be an object, an unknown key is refused (so a body that carries `tenantId`, `workspaceId`, `credentialRef`, `status` where it is
 * not accepted ... fails instead of being ignored), values have fixed types, and no error message ever contains a value from the body.
 * Response rules: only the safe projections ([DataSourceView], [CredentialInfo]): there is no field that can hold a secret or the reference to one.
 */
object ManagementRequests {
    private val CREATE_KEYS = setOf("name", "type", "config", "credential")
    private val UPDATE_KEYS = setOf("name", "config", "status")
    private val CREDENTIAL_KEYS = setOf("credential")
    private val BINDING_KEYS = setOf("dataSourceId")
    private val SLOT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** the accepted fields of a PATCH; at least one is present */
    class DataSourceChange(val name: String?, val config: Map<String, String>?, val status: DataSourceStatus?)

    fun create(n: JsonNode?): DataSourceSpec {
        strict(n, CREATE_KEYS)
        val node = n!!
        return DataSourceSpec(text(node, "name"), text(node, "type"), node.get("config")?.takeUnless { it.isNull }?.let { stringMap(it, "config", 30) } ?: emptyMap(),
            node.get("credential")?.takeUnless { it.isNull }?.let { credentialMap(it) })
    }

    fun update(n: JsonNode?): DataSourceChange {
        strict(n, UPDATE_KEYS)
        val node = n!!
        val name = node.get("name")?.let { if (it.isNull) throw bad("name must be text") else textOf(it, "name") }
        val config = node.get("config")?.let { if (it.isNull) throw bad("config must be an object") else stringMap(it, "config", 30) }
        val status = node.get("status")?.let {
            when (textOf(it, "status")) { "ACTIVE" -> DataSourceStatus.ACTIVE; "DISABLED" -> DataSourceStatus.DISABLED; else -> throw bad("status must be ACTIVE or DISABLED") }
        }
        if (name == null && config == null && status == null) throw bad("nothing to change")
        return DataSourceChange(name, config, status)
    }

    /** `{"credential": {...}}` — write-only: it is the one place a secret enters, and it goes straight into the vault */
    fun credential(n: JsonNode?): Map<String, String> {
        strict(n, CREDENTIAL_KEYS)
        return credentialMap(n!!.get("credential") ?: throw bad("credential is required"))
    }

    fun bindingTarget(n: JsonNode?): UUID {
        strict(n, BINDING_KEYS)
        return try { UUID.fromString(text(n!!, "dataSourceId")) } catch (e: IllegalArgumentException) { throw bad("dataSourceId is not a valid id") }
    }

    fun id(raw: String): UUID = try { UUID.fromString(raw) } catch (e: IllegalArgumentException) { throw ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found") }

    fun slot(raw: String): String = raw.takeIf { SLOT.matches(it) } ?: throw bad("invalid slot id")

    private fun credentialMap(n: JsonNode): Map<String, String> {
        if (!n.isObject) throw bad("credential must be an object")
        val keys = DataJson.keys(n)
        if (keys.isEmpty() || keys.size > 8) throw bad("credential has an invalid shape")
        return keys.associateWith { k -> n.get(k).let { v -> if (!DataJson.isText(v)) throw bad("credential values must be text"); DataJson.text(v) } }
    }

    /** config values are non-secret scalars; text stays text, numbers and booleans are written as their plain text */
    private fun stringMap(n: JsonNode, what: String, max: Int): Map<String, String> {
        if (!n.isObject) throw bad("$what must be an object")
        val keys = DataJson.keys(n)
        if (keys.size > max) throw bad("$what is too large")
        return keys.associateWith { k ->
            val v = n.get(k)
            when {
                DataJson.isText(v) -> DataJson.text(v)
                v.isIntegralNumber || v.isBoolean -> v.toString()
                else -> throw bad("$what values must be text, whole numbers or booleans")
            }
        }
    }

    private fun strict(n: JsonNode?, allowed: Set<String>) {
        if (n == null || !n.isObject) throw bad("body must be an object")
        if (DataJson.keys(n).any { it !in allowed }) throw bad("body has a field that is not accepted")
    }

    private fun text(n: JsonNode, key: String): String = textOf(n.get(key) ?: throw bad("$key is required"), key)
    private fun textOf(v: JsonNode, key: String): String { if (!DataJson.isText(v)) throw bad("$key must be text"); return DataJson.text(v).also { if (it.length > 2_000) throw bad("$key is too long") } }
    private fun bad(msg: String) = ConnectorFailure(FailureCodes.INVALID_PARAMS, msg)
}

object ManagementResponses {
    private fun fields(v: DataSourceView) = linkedMapOf<String, Any?>(
        "id" to v.id.toString(), "workspaceId" to v.workspaceId?.toString(), "name" to v.name, "type" to v.connectorType, "config" to v.config,
        "hasCredential" to v.hasCredential, "status" to v.status.name, "version" to v.version,
        "createdBy" to v.createdBy?.toString(), "createdAt" to v.createdAt.toString(), "updatedAt" to v.updatedAt.toString()
    )

    fun dataSource(v: DataSourceView): JsonNode = DataJson.toNode(fields(v))

    fun dataSources(list: List<DataSourceView>): JsonNode = DataJson.toNode(linkedMapOf("items" to list.map { fields(it) }))

    /** configured / type / keys (names only) / updatedAt / updatedBy — never a value, never the reference */
    fun credential(i: CredentialInfo): JsonNode = DataJson.toNode(linkedMapOf(
        "configured" to i.configured, "type" to i.type, "keys" to i.keys, "updatedAt" to i.updatedAt?.toString(), "updatedBy" to i.updatedBy?.toString()
    ))

    fun connectors(list: List<ConnectorDescriptor>): JsonNode = DataJson.toNode(linkedMapOf("items" to list.map { d ->
        linkedMapOf(
            "type" to d.type, "displayName" to d.displayName, "status" to d.status.name, "capabilities" to d.capabilities.map { it.name }.sorted(),
            "configKeys" to d.configKeys.map { linkedMapOf("name" to it.name, "required" to it.required, "description" to it.description) },
            "credentialKeys" to d.credentialKeys, "notes" to d.notes
        )
    }))

    fun binding(mode: String, slotId: String, dataSourceId: UUID, updatedAt: java.time.Instant?): JsonNode =
        DataJson.toNode(linkedMapOf("mode" to mode, "slotId" to slotId, "dataSourceId" to dataSourceId.toString(), "updatedAt" to updatedAt?.toString()))
}
