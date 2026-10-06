package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.runtime.SecretsCrypto

/**
 * Decrypted credential material. Deliberately NOT a data class: no generated `toString`/`equals`/`copy`/`componentN`, so nothing can print
 * or destructure it by accident. It is created only inside the server, handed to a connector for the duration of a call, and never stored
 * in a [DataSourceRef], an AppDefinition, an audit payload, a log line or a response.
 */
class ResolvedCredential private constructor(private val values: Map<String, String>) {
    fun get(name: String): String? = values[name]
    fun require(name: String): String = values[name] ?: throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "credential is incomplete")
    val isEmpty get() = values.isEmpty()
    /** for [Redactor] only */
    internal fun secretValues(): Collection<String> = values.values
    override fun toString() = "ResolvedCredential(***)"
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        val NONE = ResolvedCredential(emptyMap())
        fun of(values: Map<String, String>): ResolvedCredential = if (values.isEmpty()) NONE else ResolvedCredential(values.toMap())
    }
}

/**
 * Port: where sealed credentials live — a table of their own (contract `data-connector.md`: "secret ở bảng riêng mã hóa"), never a column of
 * the data source row, so listing or exporting data sources can never carry credential material. Tenant-scoped on purpose: a reference
 * of another tenant resolves to nothing. Persistence needs a migration C0 has not issued (BOARD.md).
 */
interface CredentialStore {
    /** @return the `SecretsCrypto` ciphertext (`v1:...`) */
    fun find(tenantId: java.util.UUID, ref: String): String?
    fun put(tenantId: java.util.UUID, ref: String, ciphertext: String)
    fun remove(tenantId: java.util.UUID, ref: String)
}

/** Seals and opens credentials. The ciphertext is the only form that is ever stored. */
interface CredentialVault {
    /** @return the ciphertext to hand to [CredentialStore.put] */
    fun seal(values: Map<String, String>): String
    /** @return the decrypted credential; [ResolvedCredential.NONE] when the data source has none */
    fun open(ds: DataSource): ResolvedCredential
    /** the same for a credential that belongs to something other than a data source (a webhook secret); tenant-scoped like every lookup */
    fun openRef(tenantId: java.util.UUID, ref: String): ResolvedCredential
    /** seal + store under a fresh opaque reference; @return the reference to keep on the data source */
    fun store(tenantId: java.util.UUID, values: Map<String, String>): String
    /** drop a credential that is no longer referenced (after rotation or deletion) */
    fun discard(tenantId: java.util.UUID, ref: String?)
}

/**
 * [CredentialVault] on the platform's existing `SecretsCrypto` (AES-256-GCM, key only from the environment) — no second encryption scheme.
 * Without `SECRETS_MASTER_KEY` nothing can be sealed or opened and the failure is [FailureCodes.SECRETS_UNAVAILABLE].
 */
class SecretsCryptoCredentialVault(private val crypto: SecretsCrypto, private val store: CredentialStore) : CredentialVault {
    override fun seal(values: Map<String, String>): String {
        if (!crypto.available) throw ConnectorFailure(FailureCodes.SECRETS_UNAVAILABLE, "credentials need SECRETS_MASTER_KEY")
        if (values.isEmpty() || values.size > MAX_ENTRIES || values.any { (k, v) -> !KEY.matches(k) || v.isEmpty() || v.length > MAX_VALUE })
            throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "credential has an invalid shape")
        return crypto.encrypt(DataJson.mapper.writeValueAsString(values))
    }

    override fun store(tenantId: java.util.UUID, values: Map<String, String>): String {
        val ref = java.util.UUID.randomUUID().toString()
        store.put(tenantId, ref, seal(values))
        return ref
    }

    override fun discard(tenantId: java.util.UUID, ref: String?) { if (ref != null) store.remove(tenantId, ref) }

    override fun open(ds: DataSource): ResolvedCredential {
        val ref = ds.credentialRef ?: return ResolvedCredential.NONE
        return openRef(ds.tenantId, ref)
    }

    override fun openRef(tenantId: java.util.UUID, ref: String): ResolvedCredential {
        if (!crypto.available) throw ConnectorFailure(FailureCodes.SECRETS_UNAVAILABLE, "credentials need SECRETS_MASTER_KEY")
        val enc = store.find(tenantId, ref) ?: throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "stored credential cannot be read")
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = DataJson.mapper.readValue(crypto.decrypt(enc), Map::class.java) as Map<String, Any?>
            ResolvedCredential.of(map.mapValues { (_, v) -> v as? String ?: throw IllegalStateException() })
        } catch (e: Exception) {
            // never forward the cause: a decryption/parse error must not carry key material or plaintext into logs
            throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "stored credential cannot be read")
        }
    }

    private companion object {
        val KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")
        const val MAX_ENTRIES = 8
        const val MAX_VALUE = 4_000
    }
}
