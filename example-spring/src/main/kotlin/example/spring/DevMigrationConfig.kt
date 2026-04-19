package example.spring

import entkt.postgres.PostgresMigrator
import example.ent.EntClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

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