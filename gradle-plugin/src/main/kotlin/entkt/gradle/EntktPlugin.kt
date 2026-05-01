package entkt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import java.util.jar.JarFile

class EntktPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("entkt", EntktExtension::class.java)

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
            task.doFirst { requireCodegenDeps(codegenConfig) }
        }

        project.tasks.register("validateEntSchemas", JavaExec::class.java) { task ->
            task.classpath = codegenConfig.plus(schemasConfig)
            task.mainClass.set("entkt.postgres.InspectMainKt")
            task.args("validate")
            task.description = "Validate entkt schema graph (finalization, cross-schema constraints)"
            task.group = "entkt"
            task.doFirst { requireCodegenDeps(codegenConfig) }
        }

        project.tasks.register("explainEntSchemas", JavaExec::class.java) { task ->
            task.classpath = codegenConfig.plus(schemasConfig)
            task.mainClass.set("entkt.postgres.InspectMainKt")
            val format = project.providers.gradleProperty("format").getOrElse("text")
            val cliArgs = mutableListOf("explain", "--format=$format")
            val filter = project.providers.gradleProperty("filter").orNull
            if (filter != null) cliArgs.add("--filter=$filter")
            task.args(cliArgs)
            task.description = "Print the resolved relational shape of all entkt schemas"
            task.group = "entkt"
            task.doFirst { requireCodegenDeps(codegenConfig) }
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

    companion object {
        internal fun requireCodegenDeps(config: Configuration) {
            if (config.isEmpty) {
                error(
                    "The entktCodegen configuration is empty. " +
                        "Add tool dependencies, for example:\n" +
                        "  entktCodegen(\"io.entkt:codegen:\$version\")\n" +
                        "  entktCodegen(\"io.entkt:postgres:\$version\")"
                )
            }
        }

        internal fun requireClassOnClasspath(
            classpath: FileCollection,
            className: String,
            dependencyHint: String,
        ) {
            val classEntry = className.replace('.', '/') + ".class"
            val found = classpath.files.any { file ->
                when {
                    file.isDirectory -> file.resolve(classEntry).exists()
                    file.extension == "jar" && file.exists() ->
                        JarFile(file).use { it.getJarEntry(classEntry) != null }
                    else -> false
                }
            }
            if (!found) {
                error(
                    "$className not found on the entktCodegen classpath. " +
                        "Add the required dependency, for example:\n" +
                        "  $dependencyHint"
                )
            }
        }
    }

}