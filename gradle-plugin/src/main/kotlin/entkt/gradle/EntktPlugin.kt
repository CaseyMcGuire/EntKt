package entkt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

class EntktPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("entkt", EntktExtension::class.java)

        extension.migrationsDirectory.convention(project.layout.projectDirectory.dir("db/migrations"))

        val schemasConfig = project.configurations.create("schemas") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }

        // Schema classes (including enum types) are imported by generated
        // code, so they must be on the compile classpath too.
        project.configurations.named("implementation") {
            it.extendsFrom(schemasConfig)
        }

        val generateTask = project.tasks.register("generateEntkt", GenerateEntktTask::class.java) { task ->
            task.schemaClasspath.from(schemasConfig)
            task.packageName.set(extension.packageName)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/entkt"))
            task.description = "Generate entkt entity classes from schemas"
            task.group = "entkt"
        }

        project.tasks.register("planMigration", PlanMigrationTask::class.java) { task ->
            task.schemaClasspath.from(schemasConfig)
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.migrationDescription.set(
                project.providers.gradleProperty("description").orElse("migration"),
            )
            task.description = "Generate a versioned migration SQL file by diffing schemas against the snapshot"
            task.group = "entkt"
        }

        // Add generated sources to main source set
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.getByName("main").java.srcDir(generateTask.map { it.outputDirectory })

        // compileKotlin/compileJava depend on generateEntkt
        project.tasks.configureEach { task ->
            if (task.name == "compileKotlin" || task.name == "compileJava") {
                task.dependsOn(generateTask)
            }
        }
    }
}
