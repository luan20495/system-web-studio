package com.systemwebstudio.runtime

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for project secrets, app database passwords, app tokens and connector credentials (ADR 0017). The key comes only from the
 * environment (SECRETS_MASTER_KEY, base64 of 32 bytes); without it every feature that needs secrets stays unavailable.
 * Format: "v1:" + base64(iv(12) || ciphertext || tag).
 */
@Component
class SecretsCrypto(@Value("\${app.secrets.master-key:}") key: String) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val spec: SecretKeySpec? = runCatching { Base64.getDecoder().decode(key.trim()) }.getOrNull()?.takeIf { it.size == 32 }?.let { SecretKeySpec(it, "AES") }
    private val random = SecureRandom()
    val available get() = spec != null
    init { if (key.isNotBlank() && spec == null) log.warn("SECRETS_MASTER_KEY is not base64 of 32 bytes: secrets stay unavailable") }

    fun encrypt(plain: String): String {
        val k = spec ?: error("secrets unavailable")
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return "v1:" + Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String {
        val k = spec ?: error("secrets unavailable")
        require(stored.startsWith("v1:")) { "unknown secret format" }
        val raw = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    fun token(bytes: Int = 24) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}

/**
 * Per-app database isolation (ADR 0018): a dedicated Postgres server for generated apps (never the platform database), one database and
 * one LOGIN role per app, the role owns only its database, PUBLIC may connect to nothing, roles have no CREATEDB/CREATEROLE/SUPERUSER.
 */
@Component
class AppDbProvisioner(
    @Value("\${app.runtime.appdb-url:}") private val url: String,
    @Value("\${app.runtime.appdb-admin-user:appdb_admin}") private val admin: String,
    @Value("\${app.runtime.appdb-admin-password:}") private val password: String,
    @Value("\${app.runtime.appdb-host-for-apps:appdb:5432}") val hostForApps: String
) {
    val configured get() = url.isNotBlank() && password.isNotBlank()
    private val ident = Regex("^app_[0-9a-f]{12}$")

    private fun <T> withAdmin(db: String? = null, fn: (java.sql.Statement) -> T): T {
        val u = if (db == null) url else url.substringBeforeLast('/') + "/" + db
        DriverManager.getConnection(u, admin, password).use { c -> c.createStatement().use { return fn(it) } }
    }

    /** idempotent; the names are generated (never user input) and checked; the password is hex */
    fun create(name: String, rolePassword: String) {
        require(ident.matches(name) && Regex("^[0-9a-f]{32,64}$").matches(rolePassword))
        withAdmin { st ->
            st.execute("REVOKE CONNECT ON DATABASE postgres FROM PUBLIC"); st.execute("REVOKE CONNECT ON DATABASE template1 FROM PUBLIC")
            st.execute("REVOKE CONNECT ON DATABASE ${url.substringAfterLast('/').substringBefore('?')} FROM PUBLIC")
            val exists = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '$name'").use { it.next() }
            if (!exists) st.execute("CREATE ROLE $name LOGIN PASSWORD '$rolePassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 20")
            val db = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$name'").use { it.next() }
            if (!db) st.execute("CREATE DATABASE $name OWNER $name")
            st.execute("REVOKE ALL ON DATABASE $name FROM PUBLIC")
            st.execute("GRANT CONNECT ON DATABASE $name TO $name")
        }
        // the public schema of the new database belongs to the app role only
        withAdmin(name) { st -> st.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC"); st.execute("ALTER SCHEMA public OWNER TO $name") }
    }

    /** reachability of the apps DB server with the admin credential (health probe) */
    fun ping() { withAdmin { st -> st.executeQuery("SELECT 1").use { it.next() } } }

    /** which databases a role may connect to (used by tests and the admin view to prove isolation) */
    fun connectableDatabases(role: String): List<String> = withAdmin { st ->
        require(ident.matches(role))
        st.executeQuery("SELECT datname FROM pg_database WHERE datallowconn AND has_database_privilege('$role', datname, 'CONNECT') ORDER BY 1").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) } }
    }
}
