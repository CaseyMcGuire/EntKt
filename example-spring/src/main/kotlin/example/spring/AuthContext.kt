package example.spring

import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.RequestScope
import java.util.UUID

/**
 * Request-scoped authentication context. Populated by [AuthFilter]
 * from the X-User-Id header.
 */
@Component
@RequestScope
class AuthContext {
    var userId: UUID? = null

    fun requireOwner(resourceOwnerId: UUID) {
        val currentUser = userId
            ?: throw AccessDeniedException("Authentication required")
        if (resourceOwnerId != currentUser) {
            throw AccessDeniedException("You can only modify your own resources")
        }
    }
}

class AccessDeniedException(message: String) : RuntimeException(message)
