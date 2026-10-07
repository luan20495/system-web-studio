package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Small helpers shared by the C3 JDBC adapters (V28). Plain `JdbcTemplate`, same style as the other repositories of the platform:
 * every statement carries `tenant_id`, so another tenant's row is simply not found.
 */
internal object JdbcSupport {
    fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
    fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
    fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
    fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
    fun ResultSet.longOrNull(column: String): Long? { val v = getLong(column); return if (wasNull()) null else v }
    fun ts(instant: Instant): Timestamp = Timestamp.from(instant)

    fun conflict(message: String) = ConnectorFailure(FailureCodes.CONFLICT, message)
}
