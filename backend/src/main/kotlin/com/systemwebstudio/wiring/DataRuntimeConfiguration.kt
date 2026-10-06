package com.systemwebstudio.wiring

import com.systemwebstudio.data.gateway.GatewayAuthorizer
import com.systemwebstudio.data.mapping.MappingCatalog
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.systemwebstudio.access.adapters.GatewayAuthorizer as C1GatewayAuthorizer

/**
 * C0 · the two ports C3's `DataGateway` asks for, implemented over C1 and C2. Present only with `app.data-platform.enabled=true` (default false).
 * There is deliberately NO `DataGateway` bean here: it needs a persistent data-source / query / credential catalog that does not exist yet (D-C0-20).
 * Until it does, R1 and every data action answer `503 DATA_RUNTIME_UNAVAILABLE`; these two beans are what the persistence change plugs into.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataRuntimeConfiguration {
    @Bean
    fun c3GatewayAuthorizer(c1: C1GatewayAuthorizer): GatewayAuthorizer = C3GatewayAuthorizerAdapter(c1)

    @Bean
    fun runtimeMappingCatalog(definitions: RuntimeAppDefinitions): MappingCatalog = RuntimeMappingCatalog(definitions)
}
