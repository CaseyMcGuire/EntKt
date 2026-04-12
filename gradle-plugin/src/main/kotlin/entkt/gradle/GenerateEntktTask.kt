package entkt.gradle

import entkt.codegen.EntGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateEntktTask : DefaultTask() {

    @get:Classpath
    abstract val schemaClasspath: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val result = SchemaScanner.scan(schemaClasspath.files)
        if (result.schemas.isEmpty()) {
            throw GradleException(SchemaScanner.noSchemasMessage())
        }
        val generator = EntGenerator(packageName.get())
        generator.writeTo(outputDir.toPath(), result.schemas)

        logger.lifecycle("entkt: generated ${result.schemas.size * 4} files for ${result.schemas.size} schemas")
    }
}