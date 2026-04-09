package entkt.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

class EntktPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("entkt", EntktExtension::class.java)

        val generateTask = project.tasks.register("generateEntkt", GenerateEntktTask::class.java) { task ->
            task.packageName.set(extension.packageName)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/entkt"))
            task.description = "Generate entkt entity classes from schemas"
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
