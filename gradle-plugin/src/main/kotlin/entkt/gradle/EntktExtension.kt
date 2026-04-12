package entkt.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

interface EntktExtension {
    val packageName: Property<String>

    /** Directory for versioned migration SQL and snapshot files (default: `db/migrations`). */
    val migrationsDirectory: DirectoryProperty
}