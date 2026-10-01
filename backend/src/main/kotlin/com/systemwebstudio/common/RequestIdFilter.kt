package com.systemwebstudio.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** One correlation id for the whole request: header, MDC/logs, error body and audit. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)
        val id = if (incoming != null && VALID.matches(incoming)) incoming else "req_" + UUID.randomUUID().toString().replace("-", "")
        request.setAttribute(ATTRIBUTE, id)
        response.setHeader(HEADER, id)
        MDC.put(MDC_KEY, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val ATTRIBUTE = "studio.requestId"
        const val MDC_KEY = "requestId"
        private val VALID = Regex("^[A-Za-z0-9._-]{8,64}$")
        fun current(): String? = MDC.get(MDC_KEY)
    }
}
