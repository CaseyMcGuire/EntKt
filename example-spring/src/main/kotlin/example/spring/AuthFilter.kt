package example.spring

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Extracts the current user ID from the `X-User-Id` header and
 * stores it in the request-scoped [RequestContext].
 *
 * In a real app this would verify a JWT or session token.
 */
@Component
class AuthFilter(private val requestContext: RequestContext) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        request.getHeader("X-User-Id")?.let { header ->
            runCatching { UUID.fromString(header) }
                .onSuccess { requestContext.userId = it }
        }
        filterChain.doFilter(request, response)
    }
}
