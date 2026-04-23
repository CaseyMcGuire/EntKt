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
    private val mutationGenerator = MutationGenerator(packageName)
    private val createGenerator = CreateGenerator(packageName)
    private val updateGenerator = UpdateGenerator(packageName)
    private val queryGenerator = QueryGenerator(packageName)
    private val repoGenerator = RepoGenerator(packageName)
    private val privacyGenerator = PrivacyGenerator(packageName)
    private val validationGenerator = ValidationGenerator(packageName)
    private val clientGenerator = ClientGenerator(packageName)

    fun generate(schemas: List<SchemaInput>): List<FileSpec> {
        val schemaNames: Map<EntSchema, String> = schemas.associate { it.schema to it.name }
        val perSchema = schemas.flatMap { (name, schema) ->
            listOf(
                entityGenerator.generate(name, schema, schemaNames),
                mutationGenerator.generate(name, schema, schemaNames),
                createGenerator.generate(name, schema, schemaNames),
                updateGenerator.generate(name, schema, schemaNames),
                queryGenerator.generate(name, schema, schemaNames),
                repoGenerator.generate(name, schema, schemaNames),
                privacyGenerator.generate(name, schema, schemaNames),
                validationGenerator.generate(name, schema, schemaNames),
            )
        }
        return perSchema + clientGenerator.generate(schemas)
    }

    fun writeTo(outputDir: Path, schemas: List<SchemaInput>) {
        generate(schemas).forEach { fileSpec ->
            fileSpec.writeTo(outputDir)
        }
    }
}