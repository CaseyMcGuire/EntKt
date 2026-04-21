package example.spring

import entkt.postgres.PostgresDriver
import entkt.runtime.PrivacyContext
import entkt.runtime.Viewer
import example.ent.EntClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class EntktConfig {

    @Bean
    fun driver(dataSource: DataSource): PostgresDriver = PostgresDriver(dataSource)

    @Bean
    fun entClient(
        driver: PostgresDriver,
        auth: AuthContext,
        userHooks: UserHooksConfig,
        postHooks: PostHooksConfig,
        friendshipHooks: FriendshipHooksConfig,
    ): EntClient {
        return EntClient(driver) {
            privacyContext {
                val userId = auth.userId
                PrivacyContext(
                    if (userId != null) Viewer.User(userId) else Viewer.Anonymous,
                )
            }
            policies {
                users(UserPolicy)
                posts(PostPolicy)
            }
            hooks {
                users { userHooks.apply(this) }
                posts { postHooks.apply(this) }
                friendships { friendshipHooks.apply(this) }
            }
        }
    }
}