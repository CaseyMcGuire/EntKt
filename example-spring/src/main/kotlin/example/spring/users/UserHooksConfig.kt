package example.spring.users

import entkt.runtime.hook.EntityHooks
import example.ent.User
import example.ent.UserBeforeCreateState
import example.ent.UserBeforeSaveState
import example.ent.UserBeforeUpdateState
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UserHooksConfig {
    fun apply(
        hooks: EntityHooks<UserBeforeSaveState, UserBeforeCreateState, UserBeforeUpdateState, User>,
    ) {
        hooks.beforeSave { it.setUpdatedAt(Instant.now()) }
        hooks.beforeCreate { it.setCreatedAt(Instant.now()) }
    }
}
