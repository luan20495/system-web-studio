package com.systemwebstudio.data.org

import com.systemwebstudio.organization.JdbcTenantIdentityDirectory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.core.io.FileSystemResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Real PostgreSQL 17.6 with the REAL migrations of the classpath applied by Flyway (V1..V30 today). The Dynamic Organization schema is applied as "the next migration" from
 * `docs/parallel/c3/dynamic-organization-V32.pending.sql` ONLY while no `V32` migration exists on the classpath: when C0 allocates the number and the file is `git mv`-ed into
 * db/migration, Flyway applies it itself and this class stops applying the pending script (no test change needed).
 */
object OrgTestDb {
    val postgres: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }
    val json: JsonMapper = JsonMapper.builder().build()

    fun pool(size: Int, name: String = "org-test"): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password; maximumPoolSize = size; minimumIdle = minOf(2, size); poolName = name; connectionTimeout = 60_000
    })

    /** JDBC url of ANOTHER database of the container (Flyway clean / upgrade runs get their own throw-away database) */
    fun databaseUrl(database: String): String {
        val full = postgres.jdbcUrl; val base = full.substringBefore("?"); val query = full.substringAfter("?", "")
        return base.substringBeforeLast("/") + "/" + database + (if (query.isNotEmpty()) "?$query" else "")
    }

    val dataSource: HikariDataSource by lazy { pool(32, "org-test-shared") }
    val jdbc: JdbcTemplate by lazy { JdbcTemplate(dataSource) }
    val txManager: DataSourceTransactionManager by lazy { DataSourceTransactionManager(dataSource) }

    /** how the organization schema got into the database: `migration` (V32 on the classpath) or `pending-script` */
    val schemaSource: String

    init {
        val flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations("classpath:db/migration").load()
        flyway.migrate()
        val hasV32 = flyway.info().all().any { it.version?.version == "32" }
        if (hasV32) schemaSource = "migration" else {
            val pending = listOf(Path.of("..", "docs", "parallel", "c3", "dynamic-organization-V32.pending.sql"), Path.of("docs", "parallel", "c3", "dynamic-organization-V32.pending.sql")).firstOrNull { Files.exists(it) }
                ?: error("docs/parallel/c3/dynamic-organization-V32.pending.sql not found (tests run from backend/)")
            HikariDataSource(HikariConfig().apply { jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password; maximumPoolSize = 1 }).use { ds ->
                ds.connection.use { ScriptUtils.executeSqlScript(it, FileSystemResource(pending)) }
            }
            schemaSource = "pending-script"
        }
    }

    /** a set of repositories over a pool (the shared one by default); another pool lets a test control the pool size */
    class Stack(val ds: HikariDataSource, lockTimeoutMs: Int = OrgDb.DEFAULT_LOCK_TIMEOUT_MS) {
        val jdbc = JdbcTemplate(ds); val txm = DataSourceTransactionManager(ds)
        val db = OrgDb(jdbc, txm, json, lockTimeoutMs)
        val types = PostgresOrganizationUnitTypeRepository(db); val units = PostgresOrganizationUnitRepository(db); val directory = PostgresEmployeeDirectoryRepository(db)
        val memberships = PostgresEmployeeOrganizationMembershipRepository(db); val positions = PostgresPositionRepository(db); val grades = PostgresGradeRepository(db)
        val employeePositions = PostgresEmployeePositionRepository(db); val lock = PostgresTenantStructuralLock(db); val counts = PostgresOrganizationCounts(db)
        val identities = JdbcTenantIdentityDirectory(jdbc)
        fun <T> inTx(block: () -> T): T = db.tx(block)
    }

    val stack: Stack by lazy { Stack(dataSource) }

    // ---------------------------------------------------------------------------------------------------------------------------- fixtures (the REAL canonical tables)
    fun newTenant(slug: String = "t-" + UUID.randomUUID().toString().take(12), id: UUID = UUID.randomUUID(), jdbc: JdbcTemplate = this.jdbc): UUID {
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)", id, slug, "Tenant $slug"); return id
    }

    /** a user + an ACTIVE tenant member (role MEMBER): the canonical employee identity the organization tables reference */
    fun newMember(tenantId: UUID, username: String = "u" + UUID.randomUUID().toString().take(10), displayName: String? = null, email: String? = null, active: Boolean = true, jdbc: JdbcTemplate = this.jdbc): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, username, password_hash, display_name, email) VALUES (?, ?, 'x', ?, ?)", id, username, displayName, email)
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?, ?, 'MEMBER', ?)", tenantId, id, active)
        return id
    }
}
