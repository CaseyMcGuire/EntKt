package example.spring.posts

import example.ent.PostHooks
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PostHooksConfig {
    fun apply(hooks: PostHooks) {
        hooks.beforeSave { it.updatedAt = Instant.now() }
        hooks.beforeCreate { it.mutation.createdAt = Instant.now() }
        // Ownership checks moved to PostPolicy privacy rules
    }
}
