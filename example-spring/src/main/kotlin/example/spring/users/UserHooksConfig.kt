package example.spring.users

import entkt.runtime.hook.EntityHooks
import example.ent.User
import example.ent.UserCreateHookContext
import example.ent.UserMutation
import example.ent.UserUpdateHookContext
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UserHooksConfig {
    fun apply(hooks: EntityHooks<UserMutation, UserCreateHookContext, UserUpdateHookContext, User>) {
        hooks.beforeSave { it.updatedAt = Instant.now() }
        hooks.beforeCreate { it.mutation.createdAt = Instant.now() }
    }
}
