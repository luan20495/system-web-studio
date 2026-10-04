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

    /**
     * Published sites (ADR 0009), reached only through the sites gateway on their own origin: anonymous or a site session handled by
     * SiteServingController, never the Studio session; GET only; no CSRF (nothing changes state); headers set per response.
     */
    @Bean
    @org.springframework.core.annotation.Order(1)
    fun sitesFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/sites/**")
            // the Studio's CORS policy does not apply here: sandboxed apps (origin "null") load their own assets, answered by the controller
            .cors { it.disable() }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .securityContext { it.disable() }
            .headers { it.disable() }
            // the only POST: anonymous website form submissions (no cookie is used, so there is nothing to forge; Origin is checked)
            .authorizeHttpRequests { it.requestMatchers(HttpMethod.GET, "/sites/**").permitAll().requestMatchers(HttpMethod.HEAD, "/sites/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/sites/*/_forms/*").permitAll().anyRequest().denyAll() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
        return http.build()
    }

    /** Build runner endpoints (loopback process with its own token, checked in BuildRunnerController). Never proxied by the UI or gateway. */
    @Bean
    @org.springframework.core.annotation.Order(2)
    fun internalFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/internal/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .securityContext { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
        return http.build()
    }

    /** SCIM 2.0 (stage I): stateless, no session, no CSRF (no cookies are used); the controller checks the bearer token and the enable flag. */
    @Bean
    @org.springframework.core.annotation.Order(2)
    fun scimFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.securityMatcher("/scim/v2/**")
            .cors { it.disable() }.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }.securityContext { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .formLogin { it.disable() }.httpBasic { it.disable() }
        return http.build()
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authenticationProvider: DaoAuthenticationProvider,
        securityContextRepository: SecurityContextRepository,
        users: UserRepository,
        errors: ApiErrorWriter,
        @Value("\${springdoc.api-docs.enabled:false}") openApiPublic: Boolean,
        @Value("\${server.servlet.session.cookie.secure:true}") cookieSecure: Boolean,
        @Value("\${app.oidc.enabled:false}") oidcEnabled: Boolean,
        @Value("\${app.metrics.token:}") metricsToken: String,
        oidcSuccess: org.springframework.beans.factory.ObjectProvider<com.systemwebstudio.identity.oidc.OidcLoginSuccessHandler>,
        oidcFailure: org.springframework.beans.factory.ObjectProvider<com.systemwebstudio.identity.oidc.OidcLoginFailureHandler>,
        registrations: org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository>,
        @Value("\${app.saml.enabled:false}") samlEnabled: Boolean,
        @Value("\${app.saml.idp-hint:}") samlHint: String
    ): SecurityFilterChain {
        val publicPaths = buildList {
            add("/api/v1/auth/csrf"); add("/api/v1/auth/login"); add("/api/v1/auth/config"); add("/api/v1/auth/register")
            if (oidcEnabled) { add("/oauth2/**"); add("/login/oauth2/**") }
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
            .headers { h ->
                // API responses are JSON: nothing may be framed, scripted or embedded. (Swagger UI, local profile only, needs scripts.)
                if (!openApiPublic) h.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'") }
                h.referrerPolicy { it.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                h.permissionsPolicyHeader { it.policy("camera=(), microphone=(), geolocation=(), payment=()") }
                h.httpStrictTransportSecurity { it.includeSubDomains(true).maxAgeInSeconds(63_072_000) }   // only emitted on requests the container sees as HTTPS
            }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(*publicPaths).permitAll()
                    .requestMatchers("/actuator/prometheus").hasRole("METRICS")
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
            .addFilterAfter(MetricsTokenFilter(metricsToken), ActiveUserFilter::class.java)
            .also { if (oidcEnabled) it.oauth2Login { o ->
                o.successHandler(oidcSuccess.getObject()).failureHandler(oidcFailure.getObject())
                // "?idp=saml" sends the user straight to the SAML IdP brokered by the OIDC provider (Keycloak kc_idp_hint); only the configured alias
                if (samlEnabled && samlHint.isNotBlank()) o.authorizationEndpoint { a ->
                    val resolver = org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver(registrations.getObject(), "/oauth2/authorization")
                    resolver.setAuthorizationRequestCustomizer { b ->
                        val req = (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() as? org.springframework.web.context.request.ServletRequestAttributes)?.request
                        if (req?.getParameter("idp") == "saml") b.additionalParameters { it["kc_idp_hint"] = samlHint }
                    }
                    a.authorizationRequestResolver(resolver)
                }
            } }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
        return http.build()
    }
}
