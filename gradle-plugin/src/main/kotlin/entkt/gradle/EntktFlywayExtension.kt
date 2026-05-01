package entkt.gradle

import org.gradle.api.file.DirectoryProperty

interface EntktFlywayExtension {
    /** Directory containing Flyway migration files (e.g. `V1__initial.sql`). */
    val migrationsDirectory: DirectoryProperty
}
