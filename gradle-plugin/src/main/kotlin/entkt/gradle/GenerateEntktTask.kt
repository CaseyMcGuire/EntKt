package entkt.gradle

import entkt.codegen.EntGenerator
import entkt.codegen.SchemaInput
import entkt.schema.EntSchema
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URLClassLoader

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

        val classLoader = URLClassLoader(
            schemaClasspath.files.map { it.toURI().toURL() }.toTypedArray(),
            EntSchema::class.java.classLoader,
        )

        val schemas = findSchemas(classLoader)
        val generator = EntGenerator(packageName.get())
        generator.writeTo(outputDir.toPath(), schemas)

        logger.lifecycle("entkt: generated ${schemas.size * 4} files for ${schemas.size} schemas")
    }

    private fun findSchemas(classLoader: URLClassLoader): List<SchemaInput> {
        val schemas = mutableListOf<SchemaInput>()

        for (file in schemaClasspath.files) {
            if (file.isDirectory) {
                file.walkTopDown()
                    .filter { it.extension == "class" }
                    .forEach { classFile ->
                        val className = classFile.relativeTo(file).path
                            .removeSuffix(".class")
                            .replace(File.separatorChar, '.')
                        tryLoadSchema(classLoader, className)?.let { schemas.add(it) }
                    }
            }
        }

        return schemas
    }

    private fun tryLoadSchema(classLoader: ClassLoader, className: String): SchemaInput? {
        return try {
            val clazz = classLoader.loadClass(className)
            if (!EntSchema::class.java.isAssignableFrom(clazz)) return null
            val instance = clazz.kotlin.objectInstance as? EntSchema ?: return null
            SchemaInput(clazz.simpleName, instance)
        } catch (_: Exception) {
            null
        }
    }
}