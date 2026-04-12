package entkt.gradle

import entkt.codegen.buildEntitySchemas
import entkt.postgres.PostgresMigrator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class PlanMigrationTask : DefaultTask() {

    @get:Classpath
    abstract val schemaClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val migrationsDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val migrationDescription: Property<String>

    @TaskAction
    fun plan() {
        val scanResult = SchemaScanner.scan(schemaClasspath.files)
        if (scanResult.schemas.isEmpty()) {
            throw GradleException(SchemaScanner.noSchemasMessage())
        }

        val schemas = buildEntitySchemas(scanResult.schemas)
        val outputDir = migrationsDirectory.get().asFile.toPath()
        val desc = migrationDescription.getOrElse("migration")

        val planner = PostgresMigrator.planner()
        val result = planner.plan(
            schemas = schemas,
            outputDir = outputDir,
            description = desc,
        )

        if (result.filePath != null) {
            logger.lifecycle("entkt: generated migration ${result.filePath}")
            logger.lifecycle("  ${result.ops.size} auto-applied operation(s)")
            if (result.manual.isNotEmpty()) {
                logger.lifecycle("  ${result.manual.size} manual operation(s) — see file header")
            }
        } else {
            logger.lifecycle("entkt: no schema changes detected")
        }
    }
}
