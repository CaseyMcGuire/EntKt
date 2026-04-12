package example.spring

import entkt.postgres.PostgresDriver
import entkt.postgres.PostgresMigrator
import example.ent.EntClient
import example.ent.Post
import example.ent.User
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import javax.sql.DataSource

@Configuration
class EntktConfig {

    /**
     * Run dev-mode migrations on startup: introspects the live database,
     * diffs against the entity schemas, and applies any additive changes
     * (new tables, nullable columns, indexes, foreign keys).
     *
     * In a production app you'd use [PostgresMigrator.runner] with
     * versioned SQL files instead.
     */
    @Bean
    fun entClient(dataSource: DataSource): EntClient {
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(User.SCHEMA, Post.SCHEMA))

        val driver = PostgresDriver(dataSource)
        return EntClient(driver) {
            hooks {
                users {
                    beforeSave { it.updatedAt = Instant.now() }
                    beforeCreate { it.createdAt = Instant.now() }
                }
                posts {
                    beforeSave { it.updatedAt = Instant.now() }
                    beforeCreate { it.createdAt = Instant.now() }
                }
            }
        }
    }
}
