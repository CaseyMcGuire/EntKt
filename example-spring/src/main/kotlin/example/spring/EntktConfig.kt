package example.spring

import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import example.ent.EntClient
import example.spring.auth.AuthContext
import example.spring.friendships.FriendshipHooksConfig
import example.spring.friendships.FriendshipPolicy
import example.spring.posts.PostHooksConfig
import example.spring.posts.PostPolicy
import example.spring.posts.PostTagPolicy
import example.spring.tags.TagPolicy
import example.spring.users.UserHooksConfig
import example.spring.users.UserPolicy
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
                tags(TagPolicy)
                postTags(PostTagPolicy)
                friendships(FriendshipPolicy)
            }
            hooks {
                users { userHooks.apply(this) }
                posts { postHooks.apply(this) }
                friendships { friendshipHooks.apply(this) }
            }
        }
    }
}