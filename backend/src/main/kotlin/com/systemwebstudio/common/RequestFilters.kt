package com.systemwebstudio.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import com.systemwebstudio.identity.StudioUserDetails

/** Rejects oversized JSON bodies early. Uploads never pass through the API (presigned PUT to object storage). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class RequestSizeFilter(private val writer: ApiErrorWriter, @Value("\${app.limits.max-request-bytes:1048576}") private val max: Long) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > max) {
            writer.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Request body must be at most $max bytes", mapOf("maxBytes" to max))
            return
        }
        chain.doFilter(request, response)
    }
}

/**
 * One operational log line per request: method, route *pattern* (never the raw URL with ids or query string),
 * status, duration, user id and request id (MDC). Bodies, headers, cookies and parameters are never logged.
 * The audit log is a separate, durable record (audit_events).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class AccessLogFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger("access")

    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/actuator/health")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val started = System.nanoTime()
        var failure: Throwable? = null
        try {
            chain.doFilter(request, response)
        } catch (e: Throwable) {
            failure = e; throw e
        } finally {
            val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: "unmatched"
            val user = (SecurityContextHolder.getContext().authentication?.principal as? StudioUserDetails)?.userId
            val status = if (failure != null) 500 else response.status
            log.info("http_request method={} route={} status={} duration_ms={} user={}", request.method, route, status, (System.nanoTime() - started) / 1_000_000, user ?: "-")
        }
    }
}

/** The CSRF cookie must be SameSite=Lax (and Secure when the deployment is https), whichever way the framework writes it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
class CsrfCookieAttributesFilter(@Value("\${server.servlet.session.cookie.secure:true}") private val secure: Boolean) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        chain.doFilter(request, object : jakarta.servlet.http.HttpServletResponseWrapper(response) {
            override fun addCookie(cookie: jakarta.servlet.http.Cookie) {
                if (cookie.name == "XSRF-TOKEN") { cookie.setAttribute("SameSite", "Lax"); if (secure) cookie.secure = true }
                super.addCookie(cookie)
            }
            override fun addHeader(name: String, value: String?) = super.addHeader(name, if (value != null && name.equals("Set-Cookie", true) && value.startsWith("XSRF-TOKEN=")) fix(value) else value)
            override fun setHeader(name: String, value: String?) = super.setHeader(name, if (value != null && name.equals("Set-Cookie", true) && value.startsWith("XSRF-TOKEN=")) fix(value) else value)
            private fun fix(v: String): String {
                var out = v
                if (!out.contains("SameSite=", true)) out += "; SameSite=Lax"
                if (secure && !out.contains("Secure", true)) out += "; Secure"
                return out
            }
        })
    }
}
