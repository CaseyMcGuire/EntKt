package example.spring.posts

import entkt.runtime.hook.EntityHooks
import example.ent.Post
import example.ent.PostBeforeCreateState
import example.ent.PostBeforeSaveState
import example.ent.PostBeforeUpdateState
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PostHooksConfig {
    fun apply(
        hooks: EntityHooks<PostBeforeSaveState, PostBeforeCreateState, PostBeforeUpdateState, Post>,
    ) {
        hooks.beforeSave { it.setUpdatedAt(Instant.now()) }
        hooks.beforeCreate { it.setCreatedAt(Instant.now()) }
        // Ownership checks moved to PostPolicy privacy rules
    }
}
