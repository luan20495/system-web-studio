package com.systemwebstudio.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Sessions live in Redis, so a Redis outage surfaces inside the security filter chain, before any controller advice runs.
 * Turn it into the standard error body with 503 (and Retry-After) instead of an opaque 500.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class DependencyFailureFilter(private val writer: ApiErrorWriter) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: Exception) {
            if (!isRedisFailure(e)) throw e
            log.error("Redis unavailable while handling {} {}", request.method, request.requestURI, e)
            writer.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "A required service is temporarily unavailable. Please retry shortly.", headers = mapOf("Retry-After" to "5"))
        }
    }

    private fun isRedisFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(10).any { it is RedisConnectionFailureException || it is QueryTimeoutException }
}
