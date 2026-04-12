package example.spring

import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.RequestScope
import java.util.UUID

/**
 * Holds the authenticated user's ID for the current request.
 * Populated by [AuthFilter] from the X-User-Id header.
 *
 * Request-scoped with a proxy so singleton beans can inject it
 * and get the correct instance per request.
 */
@Component
@RequestScope
class RequestContext {
    var userId: UUID? = null
}
