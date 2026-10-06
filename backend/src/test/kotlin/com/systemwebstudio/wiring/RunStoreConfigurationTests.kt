package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.ActionRunStore
import com.systemwebstudio.logic.action.InMemoryActionRunStore
import com.systemwebstudio.logic.workflow.InMemoryWorkflowRunStore
import com.systemwebstudio.logic.workflow.WorkflowRunStore
import com.systemwebstudio.wiring.persistence.JdbcActionRunStore
import com.systemwebstudio.wiring.persistence.JdbcWorkflowRunStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.util.function.Supplier

/**
 * V29 / D-C0-23: `app.workflow.run-store` has exactly two legal values, `jdbc` (also the default when the property is absent) and `memory`. Any other value
 * defines no run store at all, so the application cannot start - there is never a silent fallback to volatile state. Needs no Docker: only the bean selection of
 * [RunStoreConfiguration] is under test (the stores are built over a DataSource that is never connected). The behaviour on each store is covered by
 * `DataRuntimeLiveApiTests` (default `jdbc`) and `AppRuntimeApiTests` (explicit `memory`).
 */
class RunStoreConfigurationTests {
    /** what `appRuntime` is: a consumer that needs both stores */
    class NeedsStores(val actions: ActionRunStore, val workflows: WorkflowRunStore)

    @Configuration
    class NeedsStoresConfig {
        @Bean fun needsStores(actions: ActionRunStore, workflows: WorkflowRunStore) = NeedsStores(actions, workflows)
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(RunStoreConfiguration::class.java, NeedsStoresConfig::class.java)
        .withBean(JdbcTemplate::class.java, Supplier { JdbcTemplate(DriverManagerDataSource("jdbc:postgresql://localhost:1/never-connected", "u", "p")) })
        .withBean(JsonMapper::class.java, Supplier { JsonMapper.builder().build() })

    private fun causes(t: Throwable?): String = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `without the property the durable jdbc stores are used`() {
        runner.withPropertyValues("app.workflow.enabled=true").run { ctx ->
            assertThat(ctx.startupFailure).isNull()
            assertThat(ctx.getBean(NeedsStores::class.java).actions).isInstanceOf(JdbcActionRunStore::class.java)
            assertThat(ctx.getBean(NeedsStores::class.java).workflows).isInstanceOf(JdbcWorkflowRunStore::class.java)
            assertThat(ctx.getBeansOfType(ActionRunStore::class.java)).hasSize(1)
            assertThat(ctx.getBeansOfType(WorkflowRunStore::class.java)).hasSize(1)
        }
    }

    @Test
    fun `jdbc explicitly selects the same durable stores`() {
        runner.withPropertyValues("app.workflow.enabled=true", "app.workflow.run-store=jdbc").run { ctx ->
            assertThat(ctx.startupFailure).isNull()
            assertThat(ctx.getBean(NeedsStores::class.java).actions).isInstanceOf(JdbcActionRunStore::class.java)
            assertThat(ctx.getBean(NeedsStores::class.java).workflows).isInstanceOf(JdbcWorkflowRunStore::class.java)
        }
    }

    @Test
    fun `memory is an explicit override and never mixes with the durable stores`() {
        runner.withPropertyValues("app.workflow.enabled=true", "app.workflow.run-store=memory").run { ctx ->
            assertThat(ctx.startupFailure).isNull()
            assertThat(ctx.getBean(NeedsStores::class.java).actions).isInstanceOf(InMemoryActionRunStore::class.java)
            assertThat(ctx.getBean(NeedsStores::class.java).workflows).isInstanceOf(InMemoryWorkflowRunStore::class.java)
            assertThat(ctx.getBeansOfType(ActionRunStore::class.java)).hasSize(1)
            assertThat(ctx.getBeansOfType(WorkflowRunStore::class.java)).hasSize(1)
        }
    }

    @Test
    fun `an unknown value makes startup fail and names the missing run store`() {
        runner.withPropertyValues("app.workflow.enabled=true", "app.workflow.run-store=bogus").run { ctx ->
            assertThat(ctx.startupFailure).describedAs("the application must not start with run-store=bogus").isNotNull()
            assertThat(causes(ctx.startupFailure)).contains("ActionRunStore")
        }
    }

    @Test
    fun `an empty or differently spelled value is not a silent fallback either`() {
        for (bad in listOf("", "volatile", "in-memory", "postgres", "none")) {
            runner.withPropertyValues("app.workflow.enabled=true", "app.workflow.run-store=$bad").run { ctx ->
                assertThat(ctx.startupFailure).describedAs("run-store='$bad'").isNotNull()
            }
        }
    }

    @Test
    fun `with the workflow runtime disabled no run store exists at all`() {
        ApplicationContextRunner().withUserConfiguration(RunStoreConfiguration::class.java)
            .withPropertyValues("app.workflow.enabled=false", "app.workflow.run-store=bogus")
            .run { ctx ->
                assertThat(ctx.startupFailure).isNull()
                assertThat(ctx.getBeansOfType(ActionRunStore::class.java)).isEmpty()
                assertThat(ctx.getBeansOfType(WorkflowRunStore::class.java)).isEmpty()
            }
    }
}
