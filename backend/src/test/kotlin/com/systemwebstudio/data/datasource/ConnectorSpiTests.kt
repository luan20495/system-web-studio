package com.systemwebstudio.data.datasource

import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.query.QueryRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The slots reserved for MySQL, GraphQL, CSV, Google Sheets, Odoo and Salesforce: described, listed, inert until a driver lands. */
class ConnectorSpiTests {
    private val planned = PlannedConnectors.all()
    private val ds = DataSourceRef(UUID.randomUUID(), UUID.randomUUID(), "x", emptyMap())

    @Test fun `all six slots exist with a described contract`() {
        assertThat(planned.map { it.type }).containsExactlyInAnyOrder("mysql", "graphql", "csv", "google_sheets", "odoo", "salesforce")
        for (c in planned) {
            val d = c.descriptor
            assertThat(d.status).isEqualTo(ConnectorStatus.PLANNED); assertThat(d.capabilities).contains(ConnectorCapability.DISCOVERY, ConnectorCapability.QUERY)
            assertThat(d.credentialKeys).isNotEmpty(); assertThat(d.configKeys).isNotEmpty(); assertThat(d.notes).isNotNull()
            assertThat(d.displayName).isNotEmpty()
        }
        assertThat(planned.first { it.type == "odoo" }.descriptor.capabilities).contains(ConnectorCapability.MUTATION)
        assertThat(planned.first { it.type == "mysql" }.descriptor.capabilities.contains(ConnectorCapability.MUTATION)).isFalse()
    }

    @Test fun `a planned connector never runs anything and answers with a fixed code`() {
        for (c in planned) {
            assertThat((c.test(ds, ResolvedCredential.NONE) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.NOT_IMPLEMENTED)
            for (call in listOf<() -> Unit>({ c.validateConfig(emptyMap()) }, { c.discovery().discover(ds, ResolvedCredential.NONE) }, { c.discovery().discover(ds, ResolvedCredential.NONE, DiscoveryOptions(2)) },
                { c.executor().execute(QueryRequest("q", emptyMap(), null, TenantContext(ds.tenantId, null)), ds, ResolvedCredential.NONE) })) {
                val e = try { call(); null } catch (x: ConnectorFailure) { x }
                assertThat(e!!.code).isEqualTo(FailureCodes.NOT_IMPLEMENTED); assertThat(e.safeMessage).isEqualTo("this connector is not available yet")
            }
            assertThat(c.mutator()).isNull()
        }
    }

    @Test fun `the registry lists planned types but never hands them out`() {
        val registry = DataConnectorRegistry(planned)
        assertThat(registry.types).isEmpty()
        assertThat(registry.descriptors()).hasSize(6)
        for (c in planned) {
            assertThat(registry.find(c.type)).isNull()
            val e = try { registry.require(c.type); null } catch (x: ConnectorFailure) { x }
            assertThat(e!!.code).isEqualTo(FailureCodes.NOT_IMPLEMENTED)
        }
        assertThat((try { registry.require("unknown"); null } catch (x: ConnectorFailure) { x })!!.code).isEqualTo(FailureCodes.UNSUPPORTED_TYPE)
    }

    @Test fun `a connector implementing only the required methods is a full citizen`() {
        val minimal = object : DataConnector {
            override val type = "minimal"
            override fun validateConfig(config: Map<String, String>) {}
            override fun test(ds: DataSourceRef, cred: ResolvedCredential) = ConnectionTestResult.Ok(1)
            override fun discovery() = throw UnsupportedOperationException()
            override fun executor() = throw UnsupportedOperationException()
        }
        assertThat(minimal.mutator()).isNull()
        assertThat(minimal.descriptor.status).isEqualTo(ConnectorStatus.AVAILABLE)
        val registry = DataConnectorRegistry(listOf(minimal) + planned)
        assertThat(registry.types).containsExactly("minimal")
        assertThat(registry.require("minimal")).isEqualTo(minimal)
    }

    @Test fun `duplicate connector types are refused at wiring time`() {
        val dup = listOf(planned[0], planned[0])
        val e = try { DataConnectorRegistry(dup); null } catch (x: IllegalArgumentException) { x }
        assertThat(e).isNotNull()
    }

    @Test fun `descriptors carry key names only never values`() {
        val text = planned.joinToString { it.descriptor.toString() }
        for (word in listOf("hunter2", "sk-live", "BEGIN PRIVATE KEY")) assertThat(text).doesNotContain(word)
    }
}
