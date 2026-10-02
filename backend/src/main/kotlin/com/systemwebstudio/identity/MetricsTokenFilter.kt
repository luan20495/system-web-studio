package com.systemwebstudio.identity

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Lets a Prometheus scraper read /actuator/prometheus with `Authorization: Bearer <METRICS_TOKEN>` and nothing else.
 * Without a configured token the endpoint stays unreachable for everyone (no user session can read metrics).
 */
class MetricsTokenFilter(private val token: String) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI != "/actuator/prometheus" || token.length < 16

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val presented = request.getHeader("Authorization")?.removePrefix("Bearer ")?.takeIf { request.getHeader("Authorization")!!.startsWith("Bearer ") }
        if (presented != null && MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) {
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = UsernamePasswordAuthenticationToken.authenticated("metrics-scraper", null, listOf(SimpleGrantedAuthority("ROLE_METRICS")))
            SecurityContextHolder.setContext(context)
        }
        chain.doFilter(request, response)
    }
}
