package com.anadcurukovic.berylux.api.security

import com.anadcurukovic.berylux.api.web.Problems
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The per-address limits on the sign-in routes, checked right after the body size and before
 * sign-in, the body or any password hash. The address is the connection's own. Behind a reverse
 * proxy it's the proxy's, until the proxy's forwarded address is trusted (see the deployment notes).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RateLimitFilter(private val limits: RateLimits) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val limit = ROUTES[request.method to request.requestURI]
        val wait = limit?.let { limits.take(it, request.remoteAddr) }
        if (wait == null) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, wait.toString())
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("""{"status":429,"title":"${Problems.TOO_MANY_TRIES}"}""")
    }

    private companion object {
        val ROUTES = mapOf(
            HttpMethod.POST.name() to "/auth/register" to Limit.REGISTER,
            HttpMethod.POST.name() to "/auth/login" to Limit.LOGIN_ADDRESS,
            HttpMethod.POST.name() to "/auth/refresh" to Limit.REFRESH,
        )
    }
}
