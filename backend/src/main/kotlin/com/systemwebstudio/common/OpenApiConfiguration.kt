package com.systemwebstudio.common

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration {
    @Bean
    fun openApi(@Value("\${server.servlet.session.cookie.name:STUDIO_SESSION}") cookie: String): OpenAPI = OpenAPI()
        .info(Info().title("System Web Studio API").version("v1")
            .description("Session-cookie API. Call GET /api/v1/auth/csrf first and send the token as X-XSRF-TOKEN on every POST/PATCH/PUT/DELETE. " +
                "Errors always have the shape {code, message, requestId, details}. Mutations that race return 409 REVISION_CONFLICT."))
        .components(Components().addSecuritySchemes("sessionCookie", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name(cookie)))
        .addSecurityItem(SecurityRequirement().addList("sessionCookie"))
}
