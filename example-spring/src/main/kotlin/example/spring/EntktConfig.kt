package example.spring

import entkt.postgres.PostgresDriver
import entkt.postgres.PostgresMigrator
import example.ent.EntClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
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

/**
 * Dev mode: auto-applies schema changes on startup by introspecting the
 * live database and diffing against the desired schemas.
 */
@Configuration
@Profile("dev")
class DevMigrationConfig {

    @Bean
    fun devMigration(dataSource: DataSource): DevMigrationRunner {
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(EntClient.SCHEMAS)
        return DevMigrationRunner
    }

    /** Marker bean so other beans can depend on migration completion. */
    object DevMigrationRunner
}

/**
 * Prod mode: applies versioned SQL migration files from the migrations
 * directory. Files are generated at build time via the `planMigration`
 * Gradle task and committed to version control.
 */
@Configuration
@Profile("!dev")
class ProdMigrationConfig {

    @Bean
    fun prodMigration(
        dataSource: DataSource,
        @Value("\${entkt.migrations.dir:db/migrations}") migrationsDir: String,
    ): ProdMigrationRunner {
        val runner = PostgresMigrator.runner(dataSource)
        runner.applyPending(java.nio.file.Path.of(migrationsDir))
        return ProdMigrationRunner
    }

    /** Marker bean so other beans can depend on migration completion. */
    object ProdMigrationRunner
}
