package example.spring

import entkt.postgres.PostgresDriver
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
        userHooks: UserHooksConfig,
        postHooks: PostHooksConfig,
        friendshipHooks: FriendshipHooksConfig,
    ): EntClient {
        return EntClient(driver) {
            hooks {
                users { userHooks.apply(this) }
                posts { postHooks.apply(this) }
                friendships { friendshipHooks.apply(this) }
            }
        }
    }
}