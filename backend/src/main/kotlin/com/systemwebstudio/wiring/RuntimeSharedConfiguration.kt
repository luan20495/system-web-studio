package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDefinitionCodec
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * C0 · the one bean both runtime families need (queries need the data flag, actions the workflow flag): present when EITHER flag is on, absent when both
 * are off (the default). [DataSourceSlotBindings] is optional; without it nothing is bound (D-C0-20).
 */
@Configuration
@ConditionalOnExpression("\${app.workflow.enabled:false} or \${app.data-platform.enabled:false}")
class RuntimeSharedConfiguration {
    @Bean
    fun runtimeResolvers(codec: AppDefinitionCodec, slots: ObjectProvider<DataSourceSlotBindings>): RuntimeResolvers =
        RuntimeResolvers(codec, slots.getIfAvailable { NoSlotBindings })
}
