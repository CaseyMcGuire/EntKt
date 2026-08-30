package example.spring.posts

import entkt.runtime.hook.EntityHooks
import example.ent.Post
import example.ent.PostCreateHookContext
import example.ent.PostMutation
import example.ent.PostUpdateHookContext
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PostHooksConfig {
    fun apply(hooks: EntityHooks<PostMutation, PostCreateHookContext, PostUpdateHookContext, Post>) {
        hooks.beforeSave { it.updatedAt = Instant.now() }
        hooks.beforeCreate { it.mutation.createdAt = Instant.now() }
        // Ownership checks moved to PostPolicy privacy rules
    }
}
