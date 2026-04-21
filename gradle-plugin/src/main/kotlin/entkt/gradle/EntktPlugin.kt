package entkt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
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

        // Separate resolvable configuration for the codegen + migration
        // tooling. This keeps entkt's Kotlin off the plugin classloader
        // and avoids kotlin-reflect version conflicts with Gradle's
        // embedded Kotlin.
        val codegenConfig = project.configurations.create("entktCodegen") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }

        // Auto-add codegen dependencies at the same version as the plugin.
        val entktVersion = loadPluginVersion()
        project.afterEvaluate {
            if (codegenConfig.dependencies.isEmpty()) {
                codegenConfig.dependencies.add(project.dependencies.create("io.entkt:codegen:$entktVersion"))
                codegenConfig.dependencies.add(project.dependencies.create("io.entkt:postgres:$entktVersion"))
            }
        }

        val generatedDir = project.layout.buildDirectory.dir("generated/entkt")

        val generateTask = project.tasks.register("generateEntkt", JavaExec::class.java) { task ->
            task.classpath = codegenConfig.plus(schemasConfig)
            task.mainClass.set("entkt.codegen.GenerateMainKt")
            task.args(
                extension.packageName.get(),
                generatedDir.get().asFile.absolutePath,
            )
            task.outputs.dir(generatedDir)
            task.description = "Generate entkt entity classes from schemas"
            task.group = "entkt"
        }

        project.tasks.register("generateMigrationFile", JavaExec::class.java) { task ->
            task.classpath = codegenConfig.plus(schemasConfig)
            task.mainClass.set("entkt.postgres.PlanMigrationMainKt")
            task.args(
                extension.migrationsDirectory.get().asFile.absolutePath,
                project.providers.gradleProperty("description").getOrElse("migration"),
            )
            task.description = "Generate a versioned migration SQL file by diffing schemas against the snapshot"
            task.group = "entkt"
        }

        // Add generated sources to main source set
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.getByName("main").java.srcDir(generateTask.map { generatedDir })

        // compileKotlin/compileJava depend on generateEntkt
        project.tasks.configureEach { task ->
            if (task.name == "compileKotlin" || task.name == "compileJava") {
                task.dependsOn(generateTask)
            }
        }
    }

    private fun loadPluginVersion(): String {
        val props = java.util.Properties()
        EntktPlugin::class.java.getResourceAsStream("/entkt-plugin.properties")?.use { props.load(it) }
        return props.getProperty("version") ?: "0.1.0-SNAPSHOT"
    }
}