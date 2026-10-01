package com.systemwebstudio.identity

import com.systemwebstudio.common.ApiErrorWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * A session must not keep authorizing a user who was disabled or deleted after login.
 * One primary-key lookup per authenticated request; the session is invalidated on failure.
 * Intentionally not a @Component: it is added only inside the security filter chain.
 */
class ActiveUserFilter(private val users: UserRepository, private val errors: ApiErrorWriter) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? StudioUserDetails
        if (principal != null) {
            val user = users.findById(principal.userId).orElse(null)
            if (user == null || !user.enabled) {
                request.getSession(false)?.invalidate()
                SecurityContextHolder.clearContext()
                errors.write(request, response, HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Account is disabled or no longer exists")
                return
            }
        }
        chain.doFilter(request, response)
    }
}
