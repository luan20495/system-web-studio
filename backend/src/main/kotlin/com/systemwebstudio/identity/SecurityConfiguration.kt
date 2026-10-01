package com.systemwebstudio.identity

import com.systemwebstudio.common.ApiErrorWriter
import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfException
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.json.JsonMapper

@Configuration
class SecurityConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder(16, 32, 1, 65_536, 3)

    @Bean
    fun apiErrorWriter(json: JsonMapper) = ApiErrorWriter(json)

    @Bean
    fun authenticationProvider(
        userDetailsService: DatabaseUserDetailsService,
        passwordEncoder: PasswordEncoder
    ) = DaoAuthenticationProvider(userDetailsService).apply { setPasswordEncoder(passwordEncoder) }

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager =
        configuration.authenticationManager

    @Bean
    fun corsConfigurationSource(
        @Value("\${app.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") origins: List<String>
    ): CorsConfigurationSource {
        val cors = CorsConfiguration().apply {
            allowedOrigins = origins                      // never "*": credentials are allowed
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-XSRF-TOKEN", "X-Request-Id", "Idempotency-Key")
            exposedHeaders = listOf("X-Request-Id", "Retry-After")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", cors) }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authenticationProvider: DaoAuthenticationProvider,
        securityContextRepository: SecurityContextRepository,
        users: UserRepository,
        errors: ApiErrorWriter,
        @Value("\${springdoc.api-docs.enabled:false}") openApiPublic: Boolean
    ): SecurityFilterChain {
        val publicPaths = buildList {
            add("/api/v1/auth/csrf"); add("/api/v1/auth/login")
            add("/actuator/health"); add("/actuator/health/**")
            if (openApiPublic) { add("/v3/api-docs"); add("/v3/api-docs/**"); add("/swagger-ui.html"); add("/swagger-ui/**") }
        }.toTypedArray()

        http
            .authenticationProvider(authenticationProvider)
            .cors { }
            .securityContext { it.securityContextRepository(securityContextRepository) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .requestCache { it.disable() }
            .csrf { it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(*publicPaths).permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    errors.write(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required")
                }
                it.accessDeniedHandler { request, response, e ->
                    if (e is CsrfException) errors.write(request, response, HttpStatus.FORBIDDEN, "CSRF_INVALID", "Missing or invalid CSRF token")
                    else errors.write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action")
                }
            }
            .addFilterAfter(ActiveUserFilter(users, errors), SecurityContextHolderFilter::class.java)
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
        return http.build()
    }
}
