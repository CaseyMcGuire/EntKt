package entkt.codegen

import com.squareup.kotlinpoet.FileSpec
import entkt.schema.EntSchema
import java.nio.file.Path

data class SchemaInput(
    val name: String,
    val schema: EntSchema,
)

class EntGenerator(
    private val packageName: String,
) {
    private val entityGenerator = EntityGenerator(packageName)
    private val createGenerator = CreateGenerator(packageName)
    private val updateGenerator = UpdateGenerator(packageName)
    private val queryGenerator = QueryGenerator(packageName)

    fun generate(schemas: List<SchemaInput>): List<FileSpec> {
        return schemas.flatMap { (name, schema) ->
            listOf(
                entityGenerator.generate(name, schema),
                createGenerator.generate(name, schema),
                updateGenerator.generate(name, schema),
                queryGenerator.generate(name, schema),
            )
        }
    }

    fun writeTo(outputDir: Path, schemas: List<SchemaInput>) {
        generate(schemas).forEach { fileSpec ->
            fileSpec.writeTo(outputDir)
        }
    }
}