package example.spring

import entkt.postgres.PostgresMigrator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

/**
 * Prod mode: applies versioned SQL migration files from the migrations
 * directory. Files are generated at build time via the `generateMigrationFile`
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