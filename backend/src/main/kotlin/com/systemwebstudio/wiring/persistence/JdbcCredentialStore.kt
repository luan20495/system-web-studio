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

    override fun remove(tenantId: UUID, ref: String) {
        jdbc.update("DELETE FROM data_credentials WHERE tenant_id = ? AND ref = ?", tenantId, ref)
    }
}
