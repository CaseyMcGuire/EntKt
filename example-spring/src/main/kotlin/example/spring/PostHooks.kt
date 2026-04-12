package example.spring

import example.ent.PostHooks
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PostHooksConfig(private val auth: AuthContext) {
    fun apply(hooks: PostHooks) {
        hooks.beforeSave { it.updatedAt = Instant.now() }
        hooks.beforeCreate { it.createdAt = Instant.now() }
        hooks.beforeUpdate { auth.requireOwner(it.entity.authorId) }
        hooks.beforeDelete { auth.requireOwner(it.authorId) }
    }
}
