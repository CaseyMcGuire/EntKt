package example.spring.users

import example.ent.UserHooks
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UserHooksConfig {
    fun apply(hooks: UserHooks) {
        hooks.beforeSave { it.updatedAt = Instant.now() }
        hooks.beforeCreate { it.mutation.createdAt = Instant.now() }
    }
}
