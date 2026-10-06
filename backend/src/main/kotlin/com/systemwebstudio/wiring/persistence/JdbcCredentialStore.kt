package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.CredentialStore
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** `data_credentials` (V28): ciphertext only (`SecretsCrypto` `v1:...`), tenant-scoped on every statement. Nothing here ever decrypts. */
class JdbcCredentialStore(private val jdbc: JdbcTemplate) : CredentialStore {
    override fun find(tenantId: UUID, ref: String): String? =
        jdbc.query("SELECT ciphertext FROM data_credentials WHERE tenant_id = ? AND ref = ?", { rs, _ -> rs.getString(1) }, tenantId, ref).firstOrNull()

    override fun put(tenantId: UUID, ref: String, ciphertext: String) {
        jdbc.update(
            """INSERT INTO data_credentials (tenant_id, ref, ciphertext) VALUES (?, ?, ?)
               ON CONFLICT (tenant_id, ref) DO UPDATE SET ciphertext = EXCLUDED.ciphertext, updated_at = CURRENT_TIMESTAMP""",
            tenantId, ref, ciphertext
        )
    }

    override fun updatedAt(tenantId: UUID, ref: String): java.time.Instant? =
        jdbc.query("SELECT updated_at FROM data_credentials WHERE tenant_id = ? AND ref = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, tenantId, ref).firstOrNull()

    override fun remove(tenantId: UUID, ref: String) {
        jdbc.update("DELETE FROM data_credentials WHERE tenant_id = ? AND ref = ?", tenantId, ref)
    }
}

/**
 * Who last set the credential of a data source, read from the append-only audit trail (`audit_events`): the latest creation that came with a credential or
 * rotation. Only the actor id is read; the audit payload never held any secret. Read-only, tenant-scoped through the resource id plus the tenant stored in
 * the payload by `AuditServiceSink`.
 */
class JdbcCredentialActorLookup(private val jdbc: JdbcTemplate) : com.systemwebstudio.data.datasource.CredentialActorLookup {
    override fun lastActor(tenantId: UUID, dataSourceId: UUID): UUID? =
        jdbc.query(
            """SELECT actor_id FROM audit_events
               WHERE resource_type = 'DATA_SOURCE' AND resource_id = ? AND new_value ->> 'tenantId' = ? AND actor_id IS NOT NULL
                 AND (action = 'DATASOURCE_CREDENTIAL_ROTATED' OR (action = 'DATASOURCE_CREATED' AND new_value ->> 'hasCredential' = 'true'))
               ORDER BY created_at DESC, id DESC LIMIT 1""",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, dataSourceId.toString(), tenantId.toString()
        ).firstOrNull()
}
