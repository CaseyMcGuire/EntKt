package entkt.gradle

import entkt.codegen.SchemaInput
import entkt.schema.EntSchema
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

internal object SchemaScanner {

    data class ScanResult(
        val schemas: List<SchemaInput>,
        val loadFailures: List<LoadFailure>,
    )

    data class LoadFailure(
        val className: String,
        val message: String,
    )

    fun scan(classpath: Iterable<File>): ScanResult {
        val classLoader = URLClassLoader(
            classpath.map { it.toURI().toURL() }.toTypedArray(),
            EntSchema::class.java.classLoader,
        )

        val schemas = mutableListOf<SchemaInput>()
        val failures = mutableListOf<LoadFailure>()
        for (file in classpath) {
            when {
                file.isDirectory -> scanDirectory(file, file, classLoader, schemas, failures)
                file.isFile && file.extension == "jar" -> scanJar(file, classLoader, schemas, failures)
            }
        }
        return ScanResult(schemas, failures)
    }

    private fun scanDirectory(
        root: File,
        dir: File,
        classLoader: ClassLoader,
        out: MutableList<SchemaInput>,
        failures: MutableList<LoadFailure>,
    ) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(root, file, classLoader, out, failures)
            } else if (file.extension == "class") {
                val className = file.relativeTo(root).path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                tryLoadSchema(classLoader, className, failures)?.let { out.add(it) }
            }
        }
    }

    private fun scanJar(
        jar: File,
        classLoader: ClassLoader,
        out: MutableList<SchemaInput>,
        failures: MutableList<LoadFailure>,
    ) {
        JarFile(jar).use { jf ->
            for (entry in jf.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (name.startsWith("META-INF/")) continue
                val className = name
                    .removeSuffix(".class")
                    .replace('/', '.')
                tryLoadSchema(classLoader, className, failures)?.let { out.add(it) }
            }
        }
    }

    fun noSchemasMessage(failures: List<LoadFailure>): String = buildString {
        append("No EntSchema objects found on the schema classpath. ")
        append("Add schema dependencies: dependencies { schemas(project(\":your-schema-module\")) }")
        if (failures.isNotEmpty()) {
            append("\n\nThe following classes failed to load (missing dependency?) ")
            append("and may include your schemas:")
            for (f in failures) {
                append("\n  - ${f.className}: ${f.message}")
            }
        }
    }

    private fun tryLoadSchema(
        classLoader: ClassLoader,
        className: String,
        failures: MutableList<LoadFailure>,
    ): SchemaInput? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (e: NoClassDefFoundError) {
            failures.add(LoadFailure(className, e.message ?: "unknown"))
            return null
        }
        if (!EntSchema::class.java.isAssignableFrom(clazz)) return null
        val instance = clazz.kotlin.objectInstance as? EntSchema ?: return null
        return SchemaInput(clazz.simpleName, instance)
    }
}
