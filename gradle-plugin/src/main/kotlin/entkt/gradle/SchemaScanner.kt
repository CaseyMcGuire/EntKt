package entkt.gradle

import entkt.codegen.SchemaInput
import entkt.codegen.scanForSchemas
import java.io.File

internal object SchemaScanner {

    data class ScanResult(
        val schemas: List<SchemaInput>,
    )

    fun scan(classpath: Iterable<File>): ScanResult {
        return ScanResult(scanForSchemas(classpath))
    }

    fun noSchemasMessage(): String =
        "No EntSchema objects found on the schema classpath. " +
            "Add schema dependencies: dependencies { schemas(project(\":your-schema-module\")) }"
}
