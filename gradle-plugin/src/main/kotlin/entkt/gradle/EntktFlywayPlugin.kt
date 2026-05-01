package entkt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec

class EntktFlywayPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply(EntktPlugin::class.java)

        val extension = project.extensions.create("entktFlyway", EntktFlywayExtension::class.java)

        val codegenConfig = project.configurations.getByName("entktCodegen")
        val schemasConfig = project.configurations.getByName("schemas")

        val flywayClasspath = codegenConfig.plus(schemasConfig)
        val flywayMain = "entkt.flyway.FlywayMainKt"

        project.tasks.register("generateFlywayMigration", JavaExec::class.java) { task ->
            task.classpath = flywayClasspath
            task.mainClass.set(flywayMain)
            task.args("generate", extension.migrationsDirectory.get().asFile.absolutePath)
            task.args("--description=${project.findProperty("description") ?: "migration"}")
            task.args("--manual-mode=${project.findProperty("manualMode") ?: "FAIL"}")
            task.description = "Generate next Flyway migration by diffing shadow DB against schemas"
            task.group = "entkt"
            task.doFirst {
                EntktPlugin.requireCodegenDeps(codegenConfig)
                EntktPlugin.requireClassOnClasspath(
                    flywayClasspath, flywayMain,
                    "entktCodegen(\"io.entkt:flyway:\$version\")"
                )
            }
        }

        project.tasks.register("validateFlywayMigrations", JavaExec::class.java) { task ->
            task.classpath = flywayClasspath
            task.mainClass.set(flywayMain)
            task.args("validate", extension.migrationsDirectory.get().asFile.absolutePath)
            task.description = "Check for schema drift between Flyway migrations and entkt schemas"
            task.group = "entkt"
            task.doFirst {
                EntktPlugin.requireCodegenDeps(codegenConfig)
                EntktPlugin.requireClassOnClasspath(
                    flywayClasspath, flywayMain,
                    "entktCodegen(\"io.entkt:flyway:\$version\")"
                )
            }
        }
    }
}
